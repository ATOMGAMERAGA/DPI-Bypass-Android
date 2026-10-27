package net.atom.dpibypass.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.atom.dpibypass.R
import net.atom.dpibypass.data.ActiveProfile
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.OperationMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.SettingsRepository
import net.atom.dpibypass.dns.DnsPlan
import net.atom.dpibypass.dns.DnsPlanner
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.dns.DohResolver
import net.atom.dpibypass.engine.ByeDpiProxy
import net.atom.dpibypass.engine.EngineOwnership
import net.atom.dpibypass.engine.TProxyService
import net.atom.dpibypass.isp.IspDetector
import net.atom.dpibypass.strategy.Strategy
import net.atom.dpibypass.strategy.StrategyPool
import net.atom.dpibypass.strategy.StrategyScore
import net.atom.dpibypass.strategy.StrategyTester
import net.atom.dpibypass.util.NotificationUtils
import net.atom.dpibypass.util.shellSplit
import java.io.File
import net.atom.dpibypass.social.AlphaEngine
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.IneligibleCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.net.InetSocketAddress

/**
 * Ana tünel servisi:
 *   VpnService (TUN) -> hev-socks5-tunnel -> ByeDPI (SOCKS5 + DPI desync) -> İnternet
 *
 * Foreground servis olarak kalıcı bildirimle çalışır. Otomatik modda strateji
 * testini yapar, watchdog ile arka planda ölürse kendini toparlar.
 *
 * PERFORMANS PRENSİBİ: trafik uzak sunucuya YÖNLENDİRİLMEZ; yalnızca bağlantı
 * kurulumundaki ilk paketler yerelde parçalanır. Böylece hız/ping kaybı olmaz.
 */
class DpiVpnService : LifecycleVpnService(), AlphaEngine {

    private lateinit var settingsRepo: SettingsRepository
    private var byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var watchdogJob: Job? = null
    private var settingsJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var stopping = false

    private var currentArgs: Array<String> = emptyArray()
    private var currentBaseArgs: Array<String> = emptyArray()
    private var currentVodafoneMode = false
    private var appliedConfig: TunnelConfig? = null
    private var currentProfile: ActiveProfile? = null
    private var currentDohUrl: String = DohProvider.Cloudflare.url
    /** O an gerçekten uygulanan DNS planı (bkz. [applyDnsPlan]). */
    private var dnsPlan: DnsPlan? = null
    // Samsung kalıcı VPN durum göstergesi (ayarlardan). Tünel açıkken bildirim
    // kapatılamaz hale gelir; sistem çubuğundaki VPN göstergesi görünür kalır.
    private var persistentIndicator = false
    // Bağlantının kurulduğu an — Now Bar / kilit ekranı bildirimindeki canlı "bağlı
    // süresi" kronometresi buradan sayar.
    private var connectedSince = 0L
    private var alphaOperation: String? = null
    private var alphaPreviousArgs: Array<String>? = null
    private var alphaInstalledArgs: Array<String>? = null
    private var alphaStrategyId: String? = null
    // Sosyal Erişim'de onaylanıp motorun TABANINA alınan plan (bkz. commitAlpha).
    // Taban değiştiği için tünel yeniden kurulumu ve Vodafone modu değişimi bu
    // planı korur; yalnız kullanıcının açık "Planı kaldır" komutu geri alır.
    private var committedOperation: String? = null
    private var committedPreviousBase: Array<String>? = null
    private var committedPreviousProfile: ActiveProfile? = null
    /** Otomatik kipte bu bağlantının ağ anahtarı (taşıyıcı + ISS); manuel kipte null. */
    private var currentNetworkKey: String? = null
    private var engineToken: Long? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        SocialAccessRuntime.initialize(applicationContext)
        NotificationUtils.registerChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch { start() }
            ACTION_STOP -> lifecycleScope.launch { stop() }
            else -> Log.w(TAG, "Bilinmeyen action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engineToken?.let { EngineOwnership.release(it) }
        engineToken = null
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN izni geri alındı")
        lifecycleScope.launch { stop() }
    }

    // ---- yaşam döngüsü ----

    private suspend fun start() {
        if (VpnState.state.value == ConnectionState.Connected) {
            Log.w(TAG, "Zaten bağlı")
            return
        }
        // Foreground'a erken gir (ANR/limit riskini azalt), sonra çalış.
        goForeground(getString(R.string.status_connecting), connected = false)
        VpnState.updateFailure(null)

        try {
            // Native motor süreçte TEKİLDİR. Kullanıcının bağlanma komutu önceliklidir:
            // süren strateji testi iptal ettirilir ve kaynaklarını bırakması beklenir.
            if (engineToken == null) engineToken = EngineOwnership.acquireForVpn()
                ?: throw IllegalStateException("ENGINE_BUSY")
            val settings = settingsRepo.settings.first()
            // "Yalnızca seçili" kipinde kapsamda kurulu uygulama yoksa Android tüm
            // trafiği tünele alırdı: kurulmaz, neden açıkça gösterilir.
            if (scopeDecision(settings) == ScopeDecision.EmptyInclude) throw IllegalStateException("EMPTY_INCLUDE_SCOPE")
            persistentIndicator = settings.samsungVpnIndicator
            // DNS planı strateji testinden ÖNCE kurulur: test de aynı DoH'u kullanır.
            applyDnsPlan(settings)
            val plan = resolvePlan(settings)
            currentProfile = ActiveProfile(
                plan.strategy.id,
                plan.strategy.name,
                plan.ispName,
                plan.latencyMs?.toInt(),
                plan.coverage,
            )
            currentBaseArgs = plan.strategy.toArgv(PROXY_PORT)
            currentVodafoneMode = settings.vodafoneUnlimitedMode
            currentArgs = VodafoneModePolicy.proxyArgs(currentBaseArgs, currentVodafoneMode)

            mutex.withLock {
                VpnState.update(ConnectionState.Connecting)
                startProxy(currentArgs)
                startTun2Socks(settings)
                appliedConfig = TunnelConfig.from(settings)
            }

            VpnState.update(ConnectionState.Connected, currentProfile)
            SocialAccessRuntime.adapter.engine = this
            SocialAccessRuntime.engineStarted(plan.strategy.id)
            VpnState.updateVodafoneMode(currentVodafoneMode)
            connectedSince = System.currentTimeMillis()
            goForeground(
                getString(R.string.notification_connected, currentProfile!!.shortLabel()),
                connected = true,
            )
            startWatchdog()
            startSettingsWatcher()
            Log.i(TAG, "Bağlandı: ${currentProfile?.shortLabel()} · DNS=${dnsPlan?.servers}")
        } catch (e: Exception) {
            Log.e(TAG, "Başlatma başarısız", e)
            VpnState.update(ConnectionState.Failed, null)
            VpnState.updateFailure(when (e.message) {
                "EMPTY_INCLUDE_SCOPE" -> "\"Yalnızca seçili\" kipinde kurulu, seçili uygulama yok. Uygulamalar ekranından seçin ya da \"Tümü\"ne geçin; aksi hâlde Android bütün trafiği tünele alırdı."
                "ENGINE_BUSY" -> "Strateji testi motoru bırakmadı; birkaç saniye sonra yeniden deneyin."
                else -> "Tünel kurulamadı. Tekrar deneyin; sürerse VPN iznini kontrol edin."
            })
            stop(keepFailure = true)
        }
    }

    private suspend fun stop(keepFailure: Boolean = false) {
        Log.i(TAG, "Durduruluyor")
        SocialAccessRuntime.vpnStopped()
        watchdogJob?.cancel()
        watchdogJob = null
        settingsJob?.cancel()
        settingsJob = null
        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
                stopProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Durdurma hatası", e)
            } finally {
                stopping = false
            }
        }
        if (!keepFailure) VpnState.update(ConnectionState.Disconnected, null)
        appliedConfig = null
        committedOperation = null; committedPreviousBase = null; committedPreviousProfile = null
        alphaOperation = null; alphaPreviousArgs = null; alphaInstalledArgs = null; alphaStrategyId = null
        engineToken?.let { EngineOwnership.release(it) }
        engineToken = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- DNS ----

    /**
     * Seçilen/yazılan DNS'i UYGULANABİLİR hâle getirir ve sakla.
     *
     * Ad çözümlemesi ağa çıkar, bu yüzden IO'da yapılır ve tünel kurulmadan ÖNCE
     * bir kez çalışır: `buildTun` ana iş parçacığında çağrıldığı için orada ağ
     * işi yapılamaz.
     *
     * Bootstrap bilinçli olarak SEÇİLİ SAĞLAYICININ DoH'u üzerinden yapılır:
     * kullanıcının yazdığı ana bilgisayar adını sistem DNS'ine sormak, tam da
     * kaçınmaya çalıştığımız ele geçirilmiş sunucuya sormak olurdu.
     */
    private suspend fun applyDnsPlan(settings: Settings): DnsPlan =
        withContext(Dispatchers.IO) {
            val plan = DnsPlanner.plan(
                provider = settings.dohProvider,
                customRaw = settings.customDohUrl,
            ) { host, bootstrapDohUrl ->
                DohResolver(bootstrapDohUrl).resolve(host)
            }
            dnsPlan = plan
            currentDohUrl = plan.dohUrl
            plan.warning?.let { Log.w(TAG, "DNS: $it") }
            plan
        }

    /**
     * Ayar değişikliklerini CANLI uygular.
     *
     * DNS sunucuları ve UDP kararı TUN'un kendi yapılandırmasındadır ve eskiden
     * yalnızca bağlanma anında okunuyordu: kullanıcı DNS'i değiştirdiğinde bir
     * sonraki bağlanmaya kadar hiçbir şey olmuyordu — "seçtiğim DNS etki etmiyor"
     * şikâyetinin en görünür sebebi buydu. Artık değişiklik anında uygulanır;
     * Vodafone modu değişirse proxy de yeni TTL ile yeniden kurulur.
     */
    private fun startSettingsWatcher() {
        settingsJob?.cancel()
        settingsJob = lifecycleScope.launch {
            settingsRepo.settings
                .map(TunnelConfig::from)
                .distinctUntilChanged()
                // Özel DNS alanı her tuş vuruşunda kaydedilir; her harfte TUN'u
                // yeniden kurmak olmaz. collectLatest, yeni bir değer gelince
                // bekleyen işi iptal eder — yani yazma bitene kadar sayaç başa
                // döner, tünel yalnızca bir kez yeniden kurulur.
                .collectLatest { config ->
                    if (config == appliedConfig) return@collectLatest
                    delay(SETTINGS_SETTLE_MS)
                    // Yeni bir ayar gelince bekleme iptal edilir; yeniden kurulum
                    // başladıktan sonra yarıda bırakılırsa tünel kapanık kalır.
                    withContext(NonCancellable) { reconfigureTunnel() }
                }
        }
    }

    private suspend fun reconfigureTunnel() {
        if (VpnState.state.value != ConnectionState.Connected) return
        // Süren geçici Sosyal Erişim denemesi geri alınır; onaylı plan motorun
        // tabanında olduğu için korunur. Kurtarma gerekse bile kullanıcının yeni
        // DNS/kapsam/QUIC ayarı UYGULANIR (eskiden burada sessizce atlanıyordu).
        SocialAccessRuntime.settingsChanging()
        val settings = settingsRepo.settings.first()
        if (scopeDecision(settings) == ScopeDecision.EmptyInclude) {
            Log.w(TAG, "Yalnızca seçili kipinde uygulama kalmadı; tünel kapatılıyor")
            VpnState.update(ConnectionState.Failed, null)
            VpnState.updateFailure("\"Yalnızca seçili\" listesinde kurulu uygulama kalmadı; bütün trafiği tünele almamak için bağlantı kapatıldı.")
            lifecycleScope.launch { stop(keepFailure = true) }
            return
        }
        applyDnsPlan(settings)
        var failed = false
        mutex.withLock {
            if (stopping) return
            try {
                val modeChanged = currentVodafoneMode != settings.vodafoneUnlimitedMode
                VpnState.updateVodafoneMode(false)
                stopTun2Socks()
                // Native tarafın kapanmayı bitirmesi için kısa bir nefes: aynı
                // fd/kaynaklar hemen yeniden açılmasın.
                delay(TUN_RESTART_GAP_MS)
                if (modeChanged) {
                    stopProxy()
                    currentVodafoneMode = settings.vodafoneUnlimitedMode
                    // Taban (onaylı plan dahil) korunur; yalnız Vodafone ekleri değişir.
                    // Geri alınamamış bir deneme kaldıysa artık geçersizdir.
                    alphaOperation = null; alphaPreviousArgs = null; alphaInstalledArgs = null; alphaStrategyId = null
                    currentArgs = VodafoneModePolicy.proxyArgs(currentBaseArgs, currentVodafoneMode)
                    startProxy(currentArgs)
                }
                startTun2Socks(settings)
                appliedConfig = TunnelConfig.from(settings)
                VpnState.updateVodafoneMode(currentVodafoneMode)
                Log.i(TAG, "Tünel yeniden yapılandırıldı · DNS=${dnsPlan?.servers}")
            } catch (e: Exception) {
                Log.e(TAG, "Yeniden yapılandırma başarısız", e)
                failed = true
            }
        }
        if (failed) {
            VpnState.update(ConnectionState.Failed, null)
            // stop() bu izleyici işi iptal eder; kendi içinden çağrılırsa
            // temizlik yarıda kalırdı. Ayrı bir coroutine'de çalıştırılır.
            lifecycleScope.launch { stop() }
        }
    }

    // ---- strateji seçimi ----

    /** Bağlanma kararı: hangi strateji, ölçülen gecikmesi ve gösterilecek ISS adı. */
    private data class ConnectionPlan(
        val strategy: Strategy,
        val latencyMs: Long?,
        val ispName: String,
        val coverage: String? = null,
    )

    /**
     * Otomatik kipte strateji seçimi iki aşamalıdır:
     *
     *   1. HIZLI YOL — bu ağda en son kazanan strateji hatırlanıyorsa YALNIZCA o
     *      denenir. Çalışıyorsa bağlantı ~1 saniyede kurulur. Bu, hatırlanan
     *      değeri körü körüne kullanmak değildir: strateji o an canlı olarak
     *      doğrulanır, yani "çalıştığı kanıtlanmış" olur.
     *   2. YARIŞ — hatırlanan yoksa, eskiyse ya da artık çalışmıyorsa bütün
     *      havuz ölçülür ve (başarı, gecikme) sırasına göre EN İYİSİ seçilir.
     *      Kazanan ağ profiline yazılır; sonraki bağlanmalar 1. adımdan çıkar.
     *
     * Eskiden burada "ilk çalışanı al" vardı ve liste sırası yüzünden bu pratikte
     * hep P1 demekti (bkz. StrategyTester başlığı).
     */
    private suspend fun resolvePlan(settings: Settings): ConnectionPlan {
        // 1) Gelişmiş serbest argümanlar
        currentNetworkKey = null
        if (settings.advancedEnabled && settings.advancedArgs.isNotBlank()) {
            val custom = Strategy(
                "CUSTOM", getString(R.string.strategy_custom), "",
                settings.advancedArgs.trim(),
            )
            return ConnectionPlan(custom, null, settings.selectedIsp.displayName)
        }
        // 2) Manuel: seçili preset. ISS tespiti için ağ sorgusu yapılmaz — manuel
        //    kipte kimse tahmini beklemiyor, bağlanma anında kurulmalı.
        if (settings.operationMode == OperationMode.Manual) {
            val strategy = StrategyPool.byId(settings.selectedStrategyId) ?: StrategyPool.P1
            return ConnectionPlan(strategy, null, settings.selectedIsp.displayName)
        }

        // 3) Otomatik
        VpnState.update(ConnectionState.Testing)
        val detector = IspDetector(applicationContext)
        val transport = detector.activeTransport()
        // O an GERÇEKTEN bağlı olunan ağa göre: Wi-Fi'daysak SIM'e değil, dış
        // IP'nin ASN'sine bakılır (bkz. IspDetector). TEK KEZ sorulur; eskiden
        // aynı sorgu strateji sırası ve ISS adı için iki kez yapılıyordu.
        val isp = detector.bestGuess() ?: settings.selectedIsp
        val ispName = isp.displayName
        val networkKey = IspDetector.networkKey(transport, isp)

        // `currentDohUrl` applyDnsPlan tarafından zaten normalleştirildi (özel
        // alana çıplak IP yazılmış olabilir); ham ayarı tekrar ayrıştırmayız.
        val doh = DohResolver(currentDohUrl)
        val tester = StrategyTester(lifecycleScope, doh, settings.vodafoneUnlimitedMode)
        // Çoklu uygulama: Sosyal Erişim'de seçili platformların alan adları da aynı
        // doğrulamaya girer; kazanan hepsine birlikte bakılarak seçilir.
        val social = if (settings.socialAccessEnabled) SocialAccessRuntime.probeHosts(settings.socialPlatforms) else emptyList()
        val games = if (robloxInTunnel(settings)) StrategyTester.ROBLOX_HOSTS else emptyList()
        val hosts = (StrategyTester.DEFAULT_BLOCKED_HOSTS + extraHosts(settings) + social + games).distinct()
        currentNetworkKey = networkKey

        // --- 1. aşama: hatırlanan kazananı doğrula ---
        val remembered = runCatching { settingsRepo.networkProfile(networkKey) }.getOrNull()
        val rememberedStrategy = StrategyPool.byId(remembered?.strategyId)
        if (remembered != null && rememberedStrategy != null &&
            remembered.isFresh(System.currentTimeMillis())
        ) {
            val latency = tester.verify(rememberedStrategy, hosts)
            if (latency != null) {
                Log.i(TAG, "Hızlı yol: ${rememberedStrategy.id} doğrulandı (${latency} ms)")
                return ConnectionPlan(rememberedStrategy, latency, ispName, tester.lastBest?.let(StrategyScore::coverageLabel))
            }
            Log.i(TAG, "Hatırlanan ${rememberedStrategy.id} artık çalışmıyor; yarış başlıyor")
        }

        // --- 2. aşama: yarış ---
        val ordered = StrategyPool.raceOrder(isp.family, rememberedStrategy)
        val best = tester.run(
            strategies = ordered,
            blockedHosts = hosts,
            timeoutMs = StrategyTester.QUICK_TIMEOUT_MS,
            budgetMs = RACE_BUDGET_MS,
        )
        if (best != null) {
            // Ağ profiline kaydet: aynı ağa bir daha bağlanırken doğrudan hızlı yol.
            runCatching { settingsRepo.saveNetworkProfile(networkKey, best.first.id) }
            Log.i(TAG, "Yarış kazananı: ${best.first.id} (${best.second} ms)")
            return ConnectionPlan(best.first, best.second, ispName, tester.lastBest?.let(StrategyScore::coverageLabel))
        }

        // Hiçbiri çalışmadı → yine de en olası preset ile dene.
        Log.w(TAG, "Otomatik test hiçbir çalışan strateji bulamadı; ilk preset kullanılıyor")
        return ConnectionPlan(ordered.first(), null, ispName)
    }

    private fun robloxInTunnel(settings: Settings): Boolean =
        runCatching { packageManager.getApplicationInfo(StrategyTester.ROBLOX_PACKAGE, 0) }.isSuccess &&
            AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, StrategyTester.ROBLOX_PACKAGE)

    private fun extraHosts(settings: Settings): List<String> =
        settings.extraBlockedHosts
            .split(',', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    // ---- ByeDPI proxy ----

    private fun startProxy(args: Array<String>) {
        if (proxyJob != null) throw IllegalStateException("Proxy zaten çalışıyor")
        byeDpiProxy = ByeDpiProxy()
        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = byeDpiProxy.startProxy(args)
            withContext(Dispatchers.Main) {
                if (code != 0 && !stopping) {
                    Log.e(TAG, "Proxy $code koduyla durdu")
                }
            }
        }
    }

    private suspend fun stopProxy() {
        if (proxyJob == null) return
        try {
            byeDpiProxy.stopProxy()
        } catch (e: Exception) {
            Log.w(TAG, "stopProxy: ${e.message}")
        }
        proxyJob?.join()
        proxyJob = null
    }

    // ---- hev-socks5-tunnel ----

    private fun startTun2Socks(settings: Settings) {
        if (tunFd != null) throw IllegalStateException("TUN zaten açık")

        val udpLine = if (settings.disableQuic) "" else "\n  udp: 'udp'"
        // connect-timeout: SOCKS el sıkışması ByeDPI'ın uzak sunucuya TCP
        // bağlantısını da kapsar; mobil ağda 5 sn'lik varsayılan, oyunun giriş
        // isteklerini zayıf çekimde gereksiz yere düşürüyordu.
        val config = """
            |misc:
            |  task-stack-size: 81920
            |  connect-timeout: 10000
            |socks5:
            |  mtu: 8500
            |  address: 127.0.0.1
            |  port: $PROXY_PORT$udpLine
        """.trimMargin()

        val configFile = File.createTempFile("tun2socks", ".yml", cacheDir).apply {
            writeText(config)
        }

        val applied = mutableListOf<String>()
        val fd = buildTun(settings, applied).establish()
            ?: throw IllegalStateException("VPN establish() null döndü")
        tunFd = fd
        TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
        // Arayüze ancak TUN gerçekten kurulduktan sonra "etkin" denir; niyet ile
        // sonucu ayırmak, "seçtim ama olmadı" durumunun görünmesini sağlar.
        VpnState.updateDns(applied, dnsPlan?.label.orEmpty())
    }

    private fun stopTun2Socks() {
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "TProxyStopService: ${e.message}")
        }
        tunFd?.close()
        tunFd = null
    }

    /** [appliedDns], gerçekten kabul edilen DNS adresleriyle doldurulur. */
    private fun buildTun(settings: Settings, appliedDns: MutableList<String>): Builder {
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        builder.addAddress("10.10.10.10", 32)
        builder.addRoute("0.0.0.0", 0)
        if (VodafoneModePolicy.captureIpv6(settings.vodafoneUnlimitedMode)) {
            // IPv6 hotspot'ta ikinci bir kaynak adresi gösterebilir. Tam rota
            // TUN'a girer; ByeDPI -X ile genel IPv6 çıkışını reddeder.
            builder.addAddress("fd00:10:10::10", 128)
            builder.addRoute("::", 0)
        }

        // Seçilen DNS'in adresleri uygulamalara BURADA verilir; sorgu ISS'in
        // hijack ettiği yerel DNS yerine bu sunuculara, desync tüneli üzerinden
        // gider. Liste `dnsPlan`den gelir: kullanıcı özel bir adres yazdıysa o,
        // yazmadıysa seçili sağlayıcının adres çifti (bkz. applyDnsPlan).
        val servers = dnsPlan?.servers?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_DNS)
        servers.forEach { ip ->
            try {
                builder.addDnsServer(ip)
                appliedDns += ip
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "DNS adresi kabul edilmedi: $ip (${e.message})")
            }
        }
        // Hiçbiri kabul edilmediyse tünel DNS'siz kalır ve uygulamalar ağın kendi
        // (ele geçirilmiş olabilecek) sunucusuna döner — bu sessizce olmamalı.
        if (appliedDns.isEmpty()) {
            Log.w(TAG, "Geçerli DNS adresi yok; $DEFAULT_DNS kullanılıyor")
            builder.addDnsServer(DEFAULT_DNS)
            appliedDns += DEFAULT_DNS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applySplitTunneling(builder, settings)
        return builder
    }

    /** Uygulama kapsamı kararı (saf, birim testli: [AppScope]). Kendi paketimiz daima tünel dışında. */
    private fun scopeDecision(settings: Settings): ScopeDecision = AppScope.decide(
        settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, applicationContext.packageName,
    ) { pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess }

    /** Uygulama ayırma (split tunneling). Kendi paketini daima hariç tut (döngü olmasın). */
    private fun applySplitTunneling(builder: Builder, settings: Settings) {
        when (val decision = scopeDecision(settings)) {
            is ScopeDecision.AllExcept -> decision.disallowed.forEach { safeDisallow(builder, it) }
            is ScopeDecision.Only -> {
                var added = 0
                decision.allowed.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg); added++
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Uygulama bulunamadı: $pkg")
                    }
                }
                // Karar ile kurulum arasında paket kaldırıldıysa izin listesi boş
                // kalır ve Android tüm trafiği alırdı.
                check(added > 0) { "EMPTY_INCLUDE_SCOPE" }
            }
            ScopeDecision.EmptyInclude -> throw IllegalStateException("EMPTY_INCLUDE_SCOPE")
        }
    }

    private fun safeDisallow(builder: Builder, pkg: String) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Uygulama bulunamadı: $pkg")
        }
    }

    // ---- watchdog ----

    /**
     * Motoru yalnızca KENDİSİ bozulduğunda toparlar (bkz. [ProxyWatchdogPolicy]).
     * Olay döngüsünün bitişi `join` ile anında yakalanır; eskiden bu 2 dakikaya
     * kadar sürüyordu ve tüm uygulamalar o süre bağlantısız kalıyordu.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        val policy = ProxyWatchdogPolicy()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !stopping) {
                val job = proxyJob
                if (job?.isActive == true) withTimeoutOrNull(LISTENER_PROBE_INTERVAL_MS) { job.join() }
                else delay(DEAD_RECHECK_MS)
                if (stopping) break
                // Strateji/ayar değişimi motoru o an kendisi yeniden kuruyor.
                if (mutex.isLocked) continue
                val alive = proxyJob?.isActive == true
                val responds = alive && ProxyListenerProbe.responds(PROXY_PORT)
                if (policy.observe(alive, responds, SystemClock.elapsedRealtime()) == ProxyWatchdogPolicy.Action.Restart) {
                    Log.w(TAG, "Watchdog: motor ${if (alive) "yanıt vermiyor" else "durmuş"}; yeniden başlatılıyor")
                    restartProxy()
                }
            }
        }
    }

    private suspend fun restartProxy() {
        mutex.withLock {
            if (stopping) return
            try {
                stopProxy()
                startProxy(currentArgs)
            } catch (e: Exception) {
                Log.e(TAG, "Proxy yeniden başlatılamadı", e)
            }
        }
    }

    /** No second native engine: Alpha borrows this existing service under its ownership mutex. */
    override fun resolveAlphaHost(host: String): List<String> = DohResolver(currentDohUrl).resolve(host)

    override suspend fun applyAlpha(strategyId: String, operationId: String) {
        mutex.withLock {
            check(!stopping && VpnState.state.value == ConnectionState.Connected)
            if (alphaOperation == operationId) return
            check(alphaOperation == null) { "Existing Alpha owner" }
            val strategy = checkNotNull(StrategyPool.byId(strategyId))
            val args = VodafoneModePolicy.proxyArgs(strategy.toArgv(PROXY_PORT), currentVodafoneMode)
            if (args.contentEquals(currentArgs)) throw IneligibleCandidate("NO_OP_EFFECT")
            val record = checkNotNull(SocialAccessRuntime.adapter.journal.read())
            check(record.optString("operationId") == operationId)
            // Undo is durable before the first native stop/start can mutate anything.
            record.put("previousArgs", JSONArray(currentArgs.toList())).put("installedArgs", JSONArray(args.toList()))
            SocialAccessRuntime.adapter.journal.write(record)
            alphaPreviousArgs = currentArgs.copyOf(); alphaInstalledArgs = args.copyOf(); alphaOperation = operationId; alphaStrategyId = strategyId
            stopProxy()
            currentArgs = args
            startProxy(currentArgs)
            check(waitForAlphaProxy()) { "ALPHA_LISTENER_UNAVAILABLE" }
        }
    }

    override suspend fun rollbackAlpha(operationId: String): Boolean = withTimeoutOrNull(15_000) { mutex.withLock {
        if (alphaOperation == null) return@withLock true
        if (alphaOperation != operationId) return@withLock false
        val previous = alphaPreviousArgs ?: return@withLock false
        val installed = alphaInstalledArgs ?: return@withLock false
        // Preserve external/user edits rather than overwriting a full old settings snapshot.
        if (!currentArgs.contentEquals(installed) && !currentArgs.contentEquals(previous)) return@withLock false
        stopProxy(); currentArgs = previous; startProxy(currentArgs)
        val restored = currentArgs.contentEquals(previous) && waitForAlphaProxy()
        if (restored) { alphaOperation = null; alphaPreviousArgs = null; alphaInstalledArgs = null; alphaStrategyId = null }
        restored
    } } ?: false

    /**
     * Uygulanmış ve onaylanmış adayı motorun TABANI yapar. Bundan sonra tünel
     * yeniden kurulumu, Vodafone modu değişimi ve watchdog bu ayarla çalışır;
     * sonraki denemeler bu planın üstüne kurulur ve geri alındığında buna döner.
     * Otomatik kipte bu ağın profiline de yazılır (manuel kipte kullanıcının
     * seçimi sessizce değiştirilmez).
     */
    override suspend fun commitAlpha(operationId: String): Boolean = mutex.withLock {
        if (alphaOperation != operationId) return@withLock false
        val installed = alphaInstalledArgs ?: return@withLock false
        if (!currentArgs.contentEquals(installed)) return@withLock false
        val strategy = StrategyPool.byId(alphaStrategyId) ?: return@withLock false
        committedPreviousBase = currentBaseArgs.copyOf()
        committedPreviousProfile = currentProfile
        committedOperation = operationId
        currentBaseArgs = strategy.toArgv(PROXY_PORT)
        alphaOperation = null; alphaPreviousArgs = null; alphaInstalledArgs = null; alphaStrategyId = null
        currentProfile = currentProfile?.copy(strategyId = strategy.id, strategyName = strategy.name, latencyMs = null, coverage = null, socialPlan = true)
        VpnState.update(ConnectionState.Connected, currentProfile)
        currentNetworkKey?.let { key -> runCatching { settingsRepo.saveNetworkProfile(key, strategy.id) } }
        true
    }

    /** Onaylı planı, kurulmadan önceki tabana döndürür. Dışarıdan değişmiş ayarı ezmez. */
    override suspend fun revertCommittedAlpha(operationId: String): Boolean = withTimeoutOrNull(15_000) { mutex.withLock {
        if (committedOperation == null) return@withLock true
        if (committedOperation != operationId || alphaOperation != null) return@withLock false
        val previousBase = committedPreviousBase ?: return@withLock false
        if (!currentArgs.contentEquals(VodafoneModePolicy.proxyArgs(currentBaseArgs, currentVodafoneMode))) return@withLock false
        stopProxy()
        currentBaseArgs = previousBase
        currentArgs = VodafoneModePolicy.proxyArgs(currentBaseArgs, currentVodafoneMode)
        startProxy(currentArgs)
        val restored = waitForAlphaProxy()
        if (restored) {
            currentProfile = committedPreviousProfile ?: currentProfile
            VpnState.update(ConnectionState.Connected, currentProfile)
            committedOperation = null; committedPreviousBase = null; committedPreviousProfile = null
        }
        restored
    } } ?: false

    private suspend fun waitForAlphaProxy(): Boolean = withContext(Dispatchers.IO) {
        repeat(30) {
            if (proxyJob?.isActive != true) return@withContext false
            val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PROXY_PORT), 100) }; true }.getOrDefault(false)
            if (ready) return@withContext true
            delay(50)
        }
        false
    }

    // ---- foreground/bildirim ----

    private fun goForeground(content: String, connected: Boolean) {
        val notification = NotificationUtils.buildNotification(
            this,
            getString(R.string.app_name),
            content,
            connected,
            liveIndicator = persistentIndicator,
            connectedSinceMs = if (connectedSince > 0L) connectedSince else System.currentTimeMillis(),
            // Durum çubuğu "chip"i için en kısa özet: bağlıysa strateji kimliği,
            // değilse tek kelimelik durum.
            shortStatus = if (connected) currentProfile?.strategyId ?: "Açık" else "Kuruluyor",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationUtils.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "DpiVpnService"
        const val ACTION_START = "net.atom.dpibypass.START"
        const val ACTION_STOP = "net.atom.dpibypass.STOP"
        const val PROXY_PORT = 1080
        private const val DEFAULT_DNS = "1.1.1.1"
        /** Motor yaşarken yerel dinleyicinin yoklanma aralığı (yalnız loopback). */
        private const val LISTENER_PROBE_INTERVAL_MS = 15_000L

        /** Motor durmuşken yeniden başlatma için tekrar bakma aralığı. */
        private const val DEAD_RECHECK_MS = 1_000L

        /**
         * Ayar değişikliğinden sonra tünelin yeniden kurulması için beklenen süre.
         * Özel DNS alanına yazarken her tuş vuruşu bir kayıt üretir; bu pencere
         * yazmanın bitmesini bekler.
         */
        private const val SETTINGS_SETTLE_MS = 1200L

        /** TUN kapanışı ile yeniden açılışı arasındaki emniyet payı. */
        private const val TUN_RESTART_GAP_MS = 150L

        /**
         * Strateji yarışının üst sınırı. Dolduğunda o ana kadarki EN İYİSİ ile
         * bağlanılır; kullanıcı "bağlan" dedikten sonra belirsiz süre beklemez.
         * Yalnızca hatırlanan strateji yoksa ya da artık çalışmıyorsa devreye
         * girer — olağan bağlanma bu yola hiç uğramaz.
         */
        private const val RACE_BUDGET_MS = 9_000L
    }
}
