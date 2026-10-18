package net.atom.dpibypass.social

import android.content.Context
import android.content.Intent
import android.net.*
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.io.IOException
import kotlin.coroutines.resumeWithException

interface AlphaEngine {
    fun resolveAlphaHost(host: String): List<String>
    suspend fun applyAlpha(strategyId: String, operationId: String)
    suspend fun rollbackAlpha(operationId: String): Boolean
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
        if (stage == "ROLLED_BACK") file.delete()
    }
    @Synchronized fun clearAfterProcessRecovery() { file.delete() }
}

class AndroidTrialAdapter(private val app: Context, val journal: AlphaJournal) : TrialAdapter {
    private val summary = AtomicFile(File(app.noBackupFilesDir, "social-access-summary.json"))
    private val corrections = AtomicFile(File(app.noBackupFilesDir, "social-access-observer-corrections.json"))
    private val history = AtomicFile(File(app.noBackupFilesDir, "social-access-history.json"))
    private val cooldown = AtomicFile(File(app.noBackupFilesDir, "social-access-cooldown.json"))
    override fun cooldownRemainingMillis(): Long = runCatching {
        (JSONObject(cooldown.openRead().bufferedReader().use { it.readText() }).getLong("untilUtcMs") - System.currentTimeMillis()).coerceIn(0, 86_400_000)
    }.getOrDefault(0)
    override fun persistCooldown(delayMillis: Long) = atomicWrite(cooldown, JSONObject().put("untilUtcMs", System.currentTimeMillis() + delayMillis))
    fun clearHistory() { history.delete() }
    fun diagnosticPreview(): String = runCatching { history.openRead().bufferedReader().use { it.readText() } }.getOrElse { "{\"schemaVersion\":2,\"entries\":[]}" }
    private val localKey = File(app.noBackupFilesDir, "social-access-local-key").let { file ->
        if (!file.exists()) {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            java.io.FileOutputStream(file).use { it.write(key); it.fd.sync() }
        }
        file.readBytes()
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
    override fun available() = engine != null
    override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
        if (candidate.strategyId != null) {
            checkNotNull(engine) { "VPN permission/connection required" }.applyAlpha(candidate.strategyId, operationId)
        } else if (candidate.surface == "OFFICIAL_WEB") {
            val t = checkNotNull(target)
            // Only a fixed, canonical HTTPS target can reach this intent. Browser closure is not owned.
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val resolved = engine?.resolveAlphaHost(hostname)?.map { InetAddress.getByName(it) } ?: InetAddress.getAllByName(hostname).toList()
                    return resolved.filter(PublicTargetAddress::allowed).ifEmpty { throw UnknownHostException("PUBLIC_TARGET_REQUIRED") }
                }
            })
            .socketFactory(if (throughCandidate) PublicSocksSocketFactory() else SocketFactory.getDefault())
            .retryOnConnectionFailure(false).build()
        val call = client.newCall(Request.Builder().url(target.url).head().build())
        try {
            val received = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
                })
            }
            received.use { response ->
                val bytes = response.headers.toString().toByteArray(Charsets.UTF_8).size.toLong()
                NetworkEvidence(if (response.isSuccessful) "PASS" else "UNKNOWN", bytes,
                    reason = (if (throughCandidate) "THROUGH_CANDIDATE" else "DIRECT_CONTROL") + ":HTTP_${response.code}:NETWORK_PATH_ONLY",
                    retryAfterSeconds = if (response.code == 429 || response.code == 503) response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86400) ?: 60 else 0)
            }
        } catch (e: CancellationException) { call.cancel(); throw e }
        catch (_: Exception) { NetworkEvidence("UNKNOWN", bytes = 262_144, reason = if (throughCandidate) "THROUGH_CANDIDATE:TRANSPORT_UNKNOWN" else "DIRECT_CONTROL:TRANSPORT_UNKNOWN") }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) = journal.stage(operationId, stage, candidate)
    override fun persist(state: TrialState) {
        val j = JSONObject().put("schemaVersion", 2).put("phase", state.phase.name).put("testMode", state.mode.name)
            .put("sessionId", state.sessionId).put("attemptId", state.attemptId).put("targetRef", state.target?.targetRef ?: "").put("candidateId", state.candidate?.id ?: "")
            .put("contextGeneration", state.contextGeneration).put("modeGeneration", state.modeGeneration)
            .put("requests", state.requests).put("diagnosticBytes", state.diagnosticBytes).put("rendererLoads", state.rendererLoads)
            .put("userOutcome", state.userOutcome).put("autoVerdict", state.autoVerdict.name).put("finishReason", state.finishReason ?: "")
            .put("technicalOutcome", state.technicalOutcome).put("platformId", state.goal?.platformId ?: "")
            .put("identityContext", state.goal?.identityContext ?: "UNKNOWN").put("actualSurface", if (state.autoVerdict == Verdict.PASS) "CONTROLLED_WEB_VIEW" else "UNKNOWN")
            .put("evaluatorVersion", GoalEvaluator.VERSION).put("causalAttribution", state.causalAttribution)
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null
    private val networks = mutableSetOf<Network>()

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        adapter = AndroidTrialAdapter(app, AlphaJournal(app))
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
    fun observeNetworks(enabled: Boolean) {
        val manager = cm ?: return
        if (!enabled) { networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }; networkCallback = null; networks.clear(); return }
        if (networkCallback != null) return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(networks) { val hadNetwork = networks.isNotEmpty(); networks.add(network)
                    if (hadNetwork) launch { contextChanged() } }
            }
            override fun onLost(network: Network) {
                synchronized(networks) { if (networks.remove(network)) launch { contextChanged() } }
            }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback!!)
    }
    suspend fun vpnStopped() { if (initialized) withContext(Dispatchers.IO) { coordinator.stop(); adapter.engine = null } }
    suspend fun settingsChanging() { if (initialized) withContext(Dispatchers.IO) { coordinator.contextChanged() } }
    fun catalog(goal: Goal): List<CandidateDefinition> = buildList {
        if ("OFFICIAL_WEB" in goal.allowedSurfaces) add(CandidateDefinition(id = "A1-official-web", family = "A1", displayName = "Resmî web yolu", surface = "OFFICIAL_WEB"))
        if (goal.identityContext == "ANONYMOUS" && goal.publicFallbackAllowed && "CONTROLLED_WEB_VIEW" in goal.allowedSurfaces) {
            add(CandidateDefinition(id = "A5-controlled-public", family = "A5", displayName = "Test penceresinde herkese açık içerik", surface = "CONTROLLED_WEB_VIEW"))
        }
        if ("OFFICIAL_NATIVE_APP" in goal.allowedSurfaces || "OFFICIAL_WEB" in goal.allowedSurfaces) {
            net.atom.dpibypass.strategy.StrategyPool.orderedFor(net.atom.dpibypass.isp.Isp.Other.family).forEach {
                add(CandidateDefinition(id = "N4-${it.id}", family = "N4", displayName = "Yerel DPI: ${it.name}", strategyId = it.id,
                    surface = if ("OFFICIAL_NATIVE_APP" in goal.allowedSurfaces) "OFFICIAL_NATIVE_APP" else "OFFICIAL_WEB", rollbackKind = "RESTORE_EXPECTED_CURRENT"))
            }
        }
    }
}
