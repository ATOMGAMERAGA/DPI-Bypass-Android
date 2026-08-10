package net.atom.dpibypass.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.atom.dpibypass.R
import net.atom.dpibypass.data.ActiveProfile
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.OperationMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.SettingsRepository
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.dns.DnsPlan
import net.atom.dpibypass.dns.DnsPlanner
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.dns.DohResolver
import net.atom.dpibypass.engine.ByeDpiProxy
import net.atom.dpibypass.engine.TProxyService
import net.atom.dpibypass.isp.IspDetector
import net.atom.dpibypass.strategy.Strategy
import net.atom.dpibypass.strategy.StrategyPool
import net.atom.dpibypass.strategy.StrategyTester
import net.atom.dpibypass.strategy.Socks5TestClient
import net.atom.dpibypass.util.NotificationUtils
import net.atom.dpibypass.util.shellSplit
import java.io.File

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
class DpiVpnService : LifecycleVpnService() {

    private lateinit var settingsRepo: SettingsRepository
    private var byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var watchdogJob: Job? = null
    private var settingsJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var stopping = false

    private var currentArgs: Array<String> = emptyArray()
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

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
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

        try {
            val settings = settingsRepo.settings.first()
            persistentIndicator = settings.samsungVpnIndicator
            // DNS planı strateji testinden ÖNCE kurulur: test de aynı DoH'u kullanır.
            applyDnsPlan(settings)
            val plan = resolvePlan(settings)
            currentProfile = ActiveProfile(
                plan.strategy.id,
                plan.strategy.name,
                plan.ispName,
                plan.latencyMs?.toInt(),
            )
            currentArgs = plan.strategy.toArgv(PROXY_PORT)

            mutex.withLock {
                VpnState.update(ConnectionState.Connecting)
                startProxy(currentArgs)
                startTun2Socks(settings)
            }

            VpnState.update(ConnectionState.Connected, currentProfile)
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
            stop()
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Durduruluyor")
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
        VpnState.update(ConnectionState.Disconnected, null)
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
     * ByeDPI ayakta kalır, yalnızca TUN yeniden kurulur.
     */
    private fun startSettingsWatcher() {
        settingsJob?.cancel()
        settingsJob = lifecycleScope.launch {
            settingsRepo.settings
                .map { TunnelConfig(it.dohProvider.name, it.customDohUrl.trim(), it.disableQuic) }
                .distinctUntilChanged()
                // İlk değer zaten bağlanırken uygulandı.
                .drop(1)
                // Özel DNS alanı her tuş vuruşunda kaydedilir; her harfte TUN'u
                // yeniden kurmak olmaz. collectLatest, yeni bir değer gelince
                // bekleyen işi iptal eder — yani yazma bitene kadar sayaç başa
                // döner, tünel yalnızca bir kez yeniden kurulur.
                .collectLatest {
                    delay(SETTINGS_SETTLE_MS)
                    reconfigureTunnel()
                }
        }
    }

    /** TUN'u etkileyen ayarlar. Yalnızca bunlar değişince yeniden kurulur. */
    private data class TunnelConfig(
        val provider: String,
        val customDoh: String,
        val disableQuic: Boolean,
    )

    private suspend fun reconfigureTunnel() {
        if (VpnState.state.value != ConnectionState.Connected) return
        val settings = settingsRepo.settings.first()
        applyDnsPlan(settings)
        var failed = false
        mutex.withLock {
            if (stopping) return
            try {
                stopTun2Socks()
                // Native tarafın kapanmayı bitirmesi için kısa bir nefes: aynı
                // fd/kaynaklar hemen yeniden açılmasın.
                delay(TUN_RESTART_GAP_MS)
                startTun2Socks(settings)
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
        val tester = StrategyTester(lifecycleScope, doh)
        val hosts = StrategyTester.DEFAULT_BLOCKED_HOSTS + extraHosts(settings)

        // --- 1. aşama: hatırlanan kazananı doğrula ---
        val remembered = runCatching { settingsRepo.networkProfile(networkKey) }.getOrNull()
        val rememberedStrategy = StrategyPool.byId(remembered?.strategyId)
        if (remembered != null && rememberedStrategy != null &&
            remembered.isFresh(System.currentTimeMillis())
        ) {
            val latency = tester.verify(rememberedStrategy, hosts)
            if (latency != null) {
                Log.i(TAG, "Hızlı yol: ${rememberedStrategy.id} doğrulandı (${latency} ms)")
                return ConnectionPlan(rememberedStrategy, latency, ispName)
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
            return ConnectionPlan(best.first, best.second, ispName)
        }

        // Hiçbiri çalışmadı → yine de en olası preset ile dene.
        Log.w(TAG, "Otomatik test hiçbir çalışan strateji bulamadı; ilk preset kullanılıyor")
        return ConnectionPlan(ordered.first(), null, ispName)
    }

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
        val config = """
            |misc:
            |  task-stack-size: 81920
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

    /** Uygulama ayırma (split tunneling). Kendi paketini daima hariç tut (döngü olmasın). */
    private fun applySplitTunneling(builder: Builder, settings: Settings) {
        val self = applicationContext.packageName
        when (settings.appFilterMode) {
            AppFilterMode.All -> {
                safeDisallow(builder, self)
            }
            AppFilterMode.Include -> {
                // Yalnızca seçili uygulamalar tünelde; kendi paketimizi eklemeyerek hariç tutuyoruz.
                settings.selectedApps.filter { it != self }.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Uygulama bulunamadı: $pkg")
                    }
                }
            }
            AppFilterMode.Exclude -> {
                safeDisallow(builder, self)
                settings.selectedApps.filter { it != self }.forEach { safeDisallow(builder, it) }
            }
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

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            var failures = 0
            var backoff = 2000L
            while (isActive && !stopping) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (stopping) break

                val proxyDead = proxyJob?.isActive != true
                val healthy = !proxyDead && healthCheck()

                if (healthy) {
                    failures = 0
                    backoff = 2000L
                } else {
                    failures++
                    Log.w(TAG, "Watchdog: sağlıksız (proxyDead=$proxyDead, ardışık=$failures)")
                    if (failures >= 2) {
                        Log.w(TAG, "Watchdog: proxy yeniden başlatılıyor (backoff=${backoff}ms)")
                        restartProxy()
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(30_000L)
                        failures = 0
                    }
                }
            }
        }
    }

    /** Kontrol domaine tünel üzerinden bağlanabiliyor muyuz? */
    private fun healthCheck(): Boolean {
        return try {
            val doh = DohResolver(currentDohUrl)
            val ip = doh.resolve(StrategyTester.CONTROL_HOST).firstOrNull() ?: return false
            Socks5TestClient.testTls(PROXY_PORT, ip, StrategyTester.CONTROL_HOST, timeoutMs = 4000) != null
        } catch (e: Exception) {
            false
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
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L

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
