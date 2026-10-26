package net.atom.dpibypass.social

import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

interface AlphaEngine {
    fun resolveAlphaHost(host: String): List<String>
    suspend fun applyAlpha(strategyId: String, operationId: String)
    suspend fun rollbackAlpha(operationId: String): Boolean
    /** Uygulanmış adayı motorun kalıcı tabanı yapar (ayar değişiminde korunur). */
    suspend fun commitAlpha(operationId: String): Boolean
    /** [commitAlpha] ile kurulan planı, kurulmadan önceki tabana döndürür. */
    suspend fun revertCommittedAlpha(operationId: String): Boolean
}

/** Separate, app-private, fsync'd recovery record. No URL, account data or network IDs. */
class AlphaJournal(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "social-access-recovery.json"))
    @Synchronized fun read(): JSONObject? = try { JSONObject(file.openRead().bufferedReader().use { it.readText() }) } catch (_: FileNotFoundException) { null }
    @Synchronized fun write(value: JSONObject) {
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized fun stage(operation: String, stage: String, candidate: CandidateDefinition?) {
        val old = read()
        check(old == null || old.optString("operationId") == operation || old.optString("stage") == "ROLLED_BACK") { "Unresolved operation" }
        val record = old?.takeIf { it.optString("operationId") == operation } ?: JSONObject().put("schemaVersion", 2).put("operationId", operation)
        record.put("stage", stage).put("candidateId", candidate?.id ?: "")
        write(record)
        // KEPT: işlem tamamlandı ve (DPI adayında) motorun tabanına alındı; geri alınacak
        // geçici mutasyon kalmadı. Kayıt bırakılırsa sonraki hedefin denemesi
        // "çözülmemiş işlem" diye engelleniyordu.
        if (stage == "ROLLED_BACK" || stage == "KEPT") file.delete()
    }
    @Synchronized fun clearAfterProcessRecovery() { file.delete() }
}

class AndroidTrialAdapter(private val app: Context, val journal: AlphaJournal) : TrialAdapter {
    private val summary = AtomicFile(File(app.noBackupFilesDir, "social-access-summary.json"))
    private val corrections = AtomicFile(File(app.noBackupFilesDir, "social-access-observer-corrections.json"))
    private val history = AtomicFile(File(app.noBackupFilesDir, "social-access-history.json"))
    private val cooldown = AtomicFile(File(app.noBackupFilesDir, "social-access-cooldown.json"))
    private val resultsFile = AtomicFile(File(app.noBackupFilesDir, "social-access-results.json"))
    private fun readCooldowns(): JSONObject = runCatching { JSONObject(cooldown.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
    override fun cooldownRemainingMillis(key: String): Long = runCatching {
        val j = readCooldowns()
        val until = j.optJSONObject("targets")?.optLong(key) ?: j.optLong("untilUtcMs")
        (until - System.currentTimeMillis()).coerceIn(0, 86_400_000)
    }.getOrDefault(0)
    override fun persistCooldown(key: String, delayMillis: Long) {
        val j = readCooldowns()
        val targets = j.optJSONObject("targets") ?: JSONObject()
        targets.put(key, System.currentTimeMillis() + delayMillis)
        atomicWrite(cooldown, JSONObject().put("schemaVersion", 2).put("targets", targets))
    }
    fun clearHistory() { history.delete() }

    /**
     * Önizlenebilir, temizlenebilir tanılama çıktısı: uygulama/katalog/değerlendirici
     * sürümleri, Android sürümü, hedef sonuçları (özel alan adları "custom" olarak
     * maskelenir) ve deneme özetleri. URL, çerez, hesap kimliği, HTML yoktur.
     */
    fun diagnosticPreview(): String {
        val entries = runCatching { JSONObject(history.openRead().bufferedReader().use { it.readText() }).optJSONArray("entries") }.getOrNull() ?: JSONArray()
        val results = TargetResultCodec.decode(runCatching { resultsFile.openRead().bufferedReader().use { it.readText() } }.getOrNull())
        val masked = JSONArray(results.first.values.map { r -> JSONObject().put("target", if (r.platformId == SocialPlatform.CUSTOM.id) "custom" else r.platformId)
            .put("network", r.network.status.name).put("networkSource", r.network.source.name).put("content", r.content.status.name)
            .put("contentSource", r.content.source.name).put("strategyId", r.strategyId ?: "").put("failureClass", r.failureClass) })
        return JSONObject().put("schemaVersion", 3)
            .put("appVersion", net.atom.dpibypass.BuildConfig.VERSION_NAME)
            .put("catalogVersion", SocialPlatform.CATALOG_VERSION).put("evaluatorVersion", GoalEvaluator.VERSION)
            .put("androidSdk", Build.VERSION.SDK_INT).put("networkTransport", SocialAccessRuntime.transportLabel())
            .put("planStrategyId", results.second ?: "").put("results", masked).put("entries", entries)
            .put("accessRoutes", AccessRoutes.diagnostic(SocialAccessRuntime.routes.ledger.value, System.currentTimeMillis())).toString(2)
    }
    // Yalnızca yanlış-pozitif düzeltmesinde gerekir; açılışta ana iş parçacığında
    // dosya oluşturup fsync etmesin diye tembel.
    private val localKey by lazy {
        File(app.noBackupFilesDir, "social-access-local-key").let { file ->
            if (!file.exists()) {
                val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                java.io.FileOutputStream(file).use { it.write(key); it.fd.sync() }
            }
            file.readBytes()
        }
    }
    @Volatile var engine: AlphaEngine? = null
    private val rendererLock = Any()
    private var rendererOwner: String? = null
    private var releaseRenderer: (suspend (String) -> Boolean)? = null
    fun registerRenderer(operation: String, cleanup: suspend (String) -> Boolean) = synchronized(rendererLock) {
        check(rendererOwner == null || rendererOwner == operation)
        releaseRenderer = cleanup; rendererOwner = operation
    }
    fun forgetRenderer(operation: String) = synchronized(rendererLock) {
        if (rendererOwner == operation) { rendererOwner = null; releaseRenderer = null }
    }
    var target: Target? = null
    /** Resmî web yolu için kullanıcının seçtiği tarayıcı paketi (null: varsayılan). */
    @Volatile var browserPackage: String? = null
    override fun available() = engine != null
    override fun networkUsable(): Boolean = SocialAccessRuntime.networkUsable()
    override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
        if (candidate.strategyId != null) {
            checkNotNull(engine) { "VPN permission/connection required" }.applyAlpha(candidate.strategyId, operationId)
        } else if (candidate.surface == "OFFICIAL_WEB") {
            checkNotNull(target)
            // Resmî web yolu burada AÇILMAZ: rehber, kullanıcı "… ile aç" dediğinde
            // seçilen tarayıcı paketini açar ve açılışın gerçekten olduğunu görür.
            // Burada yalnız açılabilecek bir tarayıcının bulunduğu doğrulanır.
            checkNotNull(AccessLauncher.effectiveBrowser(app, browserPackage)) { "Browser unavailable" }
        }
    }
    override suspend fun rollback(operationId: String): Boolean {
        val renderer = synchronized(rendererLock) {
            if (rendererOwner != null && rendererOwner != operationId) return false
            releaseRenderer
        }
        if (renderer != null) {
            if (!renderer.invoke(operationId)) return false
            forgetRenderer(operationId)
        }
        return engine?.rollbackAlpha(operationId) ?: true
    }
    override suspend fun commit(operationId: String): Boolean = engine?.commitAlpha(operationId) ?: false
    override suspend fun revertPlan(operationId: String): Boolean = engine?.revertCommittedAlpha(operationId) ?: true

    /**
     * Oturum boyunca sabitlenen DNS cevapları: doğrudan kontrol ve aday ölçümü AYNI
     * doğrulanmış adrese gider; böylece fark DNS'ten değil taşıma yolundan gelir.
     * Motor açıksa cevap motorun şifreli DNS'inden, değilse sistem DNS'inden alınır.
     */
    private val pins = ConcurrentHashMap<String, List<InetAddress>>()
    override fun beginSession() { pins.clear() }
    private fun pinned(host: String): List<InetAddress> = pins.getOrPut(host) {
        val resolved = engine?.resolveAlphaHost(host)?.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: InetAddress.getAllByName(host).toList()
        resolved.filter(PublicTargetAddress::allowed).ifEmpty { throw UnknownHostException("PUBLIC_TARGET_REQUIRED") }
    }

    override suspend fun dnsDiagnosis(target: Target): String = withContext(Dispatchers.IO) {
        val host = runCatching { URI(target.probeUrl).host }.getOrNull() ?: return@withContext "UNKNOWN"
        val encrypted = engine?.let { runCatching { it.resolveAlphaHost(host) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: return@withContext "UNKNOWN"
        // Uygulamanın kendi süreci tünel dışında: buradaki sistem DNS'i alttaki
        // ağın (operatör/özel DNS) cevabıdır.
        val system = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        when {
            system.isNullOrEmpty() -> "SYSTEM_DNS_FAILED"
            system.none(PublicTargetAddress::allowed) && encrypted.isNotEmpty() -> "SYSTEM_DNS_NON_PUBLIC"
            else -> "CONSISTENT"
        }
    }

    override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence = withContext(Dispatchers.IO) {
        val route = if (throughCandidate) "THROUGH_CANDIDATE" else "DIRECT_CONTROL"
        HttpPathProbe(dns = ::pinned, socketFactory = if (throughCandidate) PublicSocksSocketFactory() else SocketFactory.getDefault())
            .probe(target.probeUrl, route)
    }

    override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) = journal.stage(operationId, stage, candidate)
    override fun clearRecovery() = journal.clearAfterProcessRecovery()
    override fun persistResults(results: Collection<TargetResult>, planStrategyId: String?) =
        atomicWrite(resultsFile, TargetResultCodec.encode(results, planStrategyId))
    override fun loadResults(): Pair<Map<String, TargetResult>, String?> =
        TargetResultCodec.decode(runCatching { resultsFile.openRead().bufferedReader().use { it.readText() } }.getOrNull())
    override fun persist(state: TrialState) {
        val j = JSONObject().put("schemaVersion", 2).put("phase", state.phase.name).put("testMode", state.mode.name)
            .put("sessionId", state.sessionId).put("attemptId", state.attemptId).put("targetRef", state.target?.targetRef ?: "").put("candidateId", state.candidate?.id ?: "")
            .put("contextGeneration", state.contextGeneration).put("modeGeneration", state.modeGeneration)
            .put("requests", state.requests).put("diagnosticBytes", state.diagnosticBytes).put("rendererLoads", state.rendererLoads)
            .put("userOutcome", state.userOutcome).put("autoVerdict", state.autoVerdict.name).put("finishReason", state.finishReason ?: "")
            .put("technicalOutcome", state.technicalOutcome).put("baselineOutcome", state.baselineOutcome).put("platformId", state.goal?.platformId ?: "")
            .put("targetCount", state.targets.size).put("planStrategyId", state.plan.strategyId ?: "")
            .put("identityContext", state.goal?.identityContext ?: "UNKNOWN").put("actualSurface", if (state.autoVerdict == Verdict.PASS) "CONTROLLED_WEB_VIEW" else "UNKNOWN")
            .put("evaluatorVersion", GoalEvaluator.VERSION).put("catalogVersion", SocialPlatform.CATALOG_VERSION).put("causalAttribution", state.causalAttribution)
        atomicWrite(summary, j)
        if (state.phase in setOf(Phase.FINISHED, Phase.ROLLING_BACK) && state.sessionId.isNotBlank()) {
            val timestamp = System.currentTimeMillis()
            val eventKey = "${state.sessionId}:${state.attemptId}:${state.userOutcome}:${state.autoVerdict}:${state.finishReason}"
            val old = runCatching { JSONObject(history.openRead().bufferedReader().use { it.readText() }).optJSONArray("entries") }.getOrNull() ?: JSONArray()
            val retained = (0 until old.length()).mapNotNull { old.optJSONObject(it) }
                .filter { timestamp - it.optLong("timestampUtcMs") in 0..(14L * 24 * 60 * 60 * 1000) }.takeLast(199)
            if (retained.none { it.optString("eventKey") == eventKey }) {
                j.put("timestampUtcMs", timestamp).put("eventKey", eventKey)
                atomicWrite(history, JSONObject().put("schemaVersion", 2).put("entries", JSONArray(retained + j)))
            }
        }
    }
    private fun correctionKey(target: Target, goal: Goal): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(localKey, "HmacSHA256"))
        return mac.doFinal((target.url + "|" + goal.toString() + "|" + GoalEvaluator.VERSION).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun readCorrections(): JSONObject = try { JSONObject(corrections.openRead().bufferedReader().use { it.readText() }) } catch (_: FileNotFoundException) { JSONObject() }
    override fun quarantine(target: Target, goal: Goal) {
        atomicWrite(corrections, readCorrections().put(correctionKey(target, goal), JSONObject()
            .put("disabled", true).put("observerVersion", GoalEvaluator.VERSION).put("timestampUtcMs", System.currentTimeMillis()).put("reason", "USER_REPORTED_FALSE_POSITIVE")))
    }
    override fun quarantined(target: Target, goal: Goal): Boolean = runCatching { readCorrections().optJSONObject(correctionKey(target, goal))?.optBoolean("disabled") ?: false }.getOrDefault(true)
    private fun atomicWrite(file: AtomicFile, value: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

/** OkHttp still performs its normal URL-host TLS/SNI and hostname verification.
 * Only the raw transport is sent through the existing loopback SOCKS listener,
 * using the pinned, checked numeric answer rather than a second remote DNS lookup. */
private class PublicSocksSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = object : Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))) {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val destination = endpoint as? InetSocketAddress ?: throw UnknownHostException("PUBLIC_TARGET_REQUIRED")
            val address = destination.address ?: throw UnknownHostException("PUBLIC_TARGET_REQUIRED")
            if (!PublicTargetAddress.allowed(address)) throw UnknownHostException("PUBLIC_TARGET_REQUIRED")
            super.connect(InetSocketAddress(address, destination.port), timeout)
        }
    }
    override fun createSocket(host: String, port: Int): Socket = createSocket(InetAddress.getByName(host), port)
    override fun createSocket(address: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(address, port)) }
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(InetAddress.getByName(host), port, local, localPort)
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket().apply {
        bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(address, port))
    }
}

/** Process-wide ownership, independent of Activity lifetime; no polling while disabled. */
object SocialAccessRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    lateinit var adapter: AndroidTrialAdapter; private set
    lateinit var coordinator: ExperimentCoordinator; private set
    lateinit var routes: AccessRouteStore; private set
    /** Bu uygulama sürecinin rastgele kimliği; erişim yolu kayıtları süreç değişince bayatlar. */
    val processId: String = java.util.UUID.randomUUID().toString()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null
    private val tracker = NetworkContextTracker()
    private var pendingContextChange: Job? = null
    @Volatile private var transport = "UNKNOWN"

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        adapter = AndroidTrialAdapter(app, AlphaJournal(app))
        routes = AccessRouteStore(app)
        scope.launch { routes.load() }
        // Native engine and owned renderer are process scoped: after process death none survives.
        // Validate rather than replay a prior success or restore Alpha arguments automatically.
        val recovered = runCatching {
            adapter.journal.read()?.let { record ->
                check(record.optInt("schemaVersion") == 2 && record.optString("operationId").isNotBlank())
                check(record.optString("stage") in setOf("APPLY_INTENT_DURABLE", "APPLIED", "KEEP_INTENT_DURABLE", "KEPT", "ROLLBACK_INTENT_DURABLE", "ROLLED_BACK"))
            }
        }.isSuccess
        if (recovered) adapter.journal.clearAfterProcessRecovery()
        coordinator = ExperimentCoordinator(adapter, scope, SystemClock::elapsedRealtime)
        initialized = true
        if (!recovered) scope.launch { coordinator.markRecoveryRequired() }
        cm = app.getSystemService(ConnectivityManager::class.java)
    }
    fun launch(action: suspend ExperimentCoordinator.() -> Unit) { scope.launch { coordinator.action() } }
    fun networkUsable(): Boolean = tracker.usable()
    /** Anonim taşıyıcı türü (SSID/operatör adı yok); tanılama için. */
    fun transportLabel(): String = transport

    @Synchronized fun observeNetworks(enabled: Boolean) {
        val manager = cm ?: return
        if (!enabled) {
            networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
            networkCallback = null; tracker.clear(); pendingContextChange?.cancel(); return
        }
        if (networkCallback != null) return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        // Android 12+: sistemin bu isteğe göre SEÇTİĞİ alttaki ağ izlenir (uygulamanın
        // kendi süreci tünel dışında olduğu için ölçümler de bu ağdan çıkar).
        // Eski sürümlerde tüm ağlar izlenir ve sıralama yaklaşık bir yedektir.
        val best = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (best && tracker.selected(network.networkHandle)) contextChangedSoon()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                tracker.capabilities(network.networkHandle, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkContextTracker.Kind.ETHERNET
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkContextTracker.Kind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkContextTracker.Kind.CELLULAR
                    else -> NetworkContextTracker.Kind.OTHER
                }
                transport = kind.name
                if (best) return
                if (tracker.update(network.networkHandle, kind)) contextChangedSoon()
            }
            override fun onLost(network: Network) {
                if (best) tracker.selected(null)
                else if (tracker.update(network.networkHandle, null)) contextChangedSoon()
            }
        }
        if (best) manager.registerBestMatchingNetworkCallback(request, networkCallback!!, Handler(Looper.getMainLooper()))
        else manager.registerNetworkCallback(request, networkCallback!!)
    }
    /** Geçişler birkaç geri çağrıyla gelir; ağ oturana kadar beklenir, tek değişim sayılır. */
    private fun contextChangedSoon() = synchronized(this) {
        pendingContextChange?.cancel()
        pendingContextChange = scope.launch { delay(2_000); coordinator.contextChanged() }
    }
    suspend fun vpnStopped() { if (initialized) withContext(Dispatchers.IO) { coordinator.engineStopped(); adapter.engine = null } }
    suspend fun engineStarted(strategyId: String?) { if (initialized) withContext(Dispatchers.IO) { coordinator.engineStarted(strategyId) } }
    suspend fun settingsChanging() { if (initialized) withContext(Dispatchers.IO) { coordinator.contextChanged() } }

    /**
     * Kullanıcının erişim yolu bildirimi. Bildirimle AYNI ANDA, o yüzeyin gerçek
     * güzergâhından (paket tünelde ve motor açıksa yerel SOCKS, değilse doğrudan)
     * platformun kontrol adresine tek bir ağ yolu ölçümü yapılır; böylece doğrulama
     * ekranının ağ sorunundan mı platformdan mı geldiği kabaca ayrılır. Hız sınırı
     * beklemesindeki hedefe istek atılmaz (NOT_MEASURED).
     *
     * Bağlam iki kez denetlenir: ölçümden önce (boşuna istek atılmasın) ve kayıt
     * KABUL edilirken ([current] o anki bağlamı verir) — ölçüm sürerken ağ, profil
     * ya da tarayıcı değiştiyse bildirim eski bağlama yazılmaz.
     */
    suspend fun recordRoute(platform: SocialPlatform, action: AccessAction, surface: AccessSurface, browser: String?, account: AccountContext,
        outcome: RouteOutcome, freshStart: Boolean, surfaceInTunnel: Boolean, context: RouteContext, current: () -> RouteContext?): RouteSave = withContext(Dispatchers.IO) {
        val through = surfaceInTunnel && adapter.available()
        val home = Target.home(platform.id)
        AccessRoutes.record(platform, action, surface, browser, account, outcome, freshStart, if (through) "TUNNEL" else "DIRECT", context, current,
            System::currentTimeMillis,
            measure = {
                val evidence = if (home == null || adapter.cooldownRemainingMillis(home.key) > 0 || !networkUsable()) null
                    else withTimeoutOrNull(20_000) { runCatching { adapter.probe(home, through) }.getOrNull() }
                evidence?.takeIf { it.retryAfterSeconds > 0 }?.let { e -> home?.let { runCatching { adapter.persistCooldown(it.key, e.retryAfterSeconds * 1000) } } }
                evidence
            },
            accept = { report, now, at -> routes.accept(report, now, at) })
    }

    /** Ölçülü (mobil veri gibi) bağlantı mı? Bilinmiyorsa ölçülü sayılır. */
    fun metered(): Boolean = cm?.isActiveNetworkMetered ?: true

    /** Cihazda bu uygulamanınki dışında etkin bir VPN var mı? (yalnız bilgi; dokunulmaz) */
    fun otherVpnActive(ownConnected: Boolean): Boolean {
        if (ownConnected) return false
        val manager = cm ?: return false
        return runCatching { manager.allNetworks.any { n -> manager.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } }.getOrDefault(false)
    }

    /** Seçili platformların strateji doğrulamasında denenecek alan adları. */
    fun probeHosts(platforms: Set<String>): List<String> =
        platforms.mapNotNull { SocialPlatform.byId(it) }.flatMap { it.probeHosts }.distinct()

    fun catalog(goal: Goal, family: net.atom.dpibypass.isp.IspFamily = net.atom.dpibypass.isp.Isp.Other.family): List<CandidateDefinition> = buildList {
        if ("OFFICIAL_WEB" in goal.allowedSurfaces) add(CandidateDefinition(id = "A1-official-web", family = "A1", displayName = "Resmî web yolu", surface = "OFFICIAL_WEB"))
        if (goal.identityContext == "ANONYMOUS" && goal.publicFallbackAllowed && "CONTROLLED_WEB_VIEW" in goal.allowedSurfaces) {
            add(CandidateDefinition(id = "A5-controlled-public", family = "A5", displayName = "Test penceresinde herkese açık içerik", surface = "CONTROLLED_WEB_VIEW"))
        }
        if ("OFFICIAL_NATIVE_APP" in goal.allowedSurfaces || "OFFICIAL_WEB" in goal.allowedSurfaces) {
            net.atom.dpibypass.strategy.StrategyPool.orderedFor(family).forEach {
                add(CandidateDefinition(id = "N4-${it.id}", family = "N4", displayName = "Yerel DPI: ${it.name}", strategyId = it.id,
                    surface = if ("OFFICIAL_NATIVE_APP" in goal.allowedSurfaces) "OFFICIAL_NATIVE_APP" else "OFFICIAL_WEB", rollbackKind = "RESTORE_EXPECTED_CURRENT"))
            }
        }
    }
}
