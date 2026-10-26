package net.atom.dpibypass.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.OperationMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.SettingsRepository
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.dns.DohResolver
import net.atom.dpibypass.engine.EngineOwnership
import net.atom.dpibypass.isp.Isp
import net.atom.dpibypass.isp.IspDetector
import net.atom.dpibypass.isp.Transport
import net.atom.dpibypass.strategy.StrategyPool
import net.atom.dpibypass.strategy.StrategyTestResult
import net.atom.dpibypass.strategy.StrategyTester
import net.atom.dpibypass.vpn.VpnState

/** Yüklü uygulama satırı (split tunneling ekranı için). */
data class AppEntry(
    val packageName: String,
    val label: String,
    val system: Boolean,
)

/**
 * O an gerçekten bağlı olunan ağın özeti. [isp] çözülene kadar null'dır; ASN
 * sorgusu ağ üzerinden yapıldığı için birkaç yüz milisaniye sürebilir.
 */
data class NetworkInfo(
    val transport: Transport = Transport.Unknown,
    val isp: Isp? = null,
    val resolving: Boolean = false,
) {
    /** Ekranda gösterilecek ad: çözülmüşse ISS adı, değilse taşıyıcı adı. */
    fun label(fallback: String): String = isp?.displayName
        ?: if (resolving) "Algılanıyor…" else fallback
}

class AppViewModel(app: Application) : AndroidViewModel(app), net.atom.dpibypass.ui.social.GuideBridge {

    private val repo = SettingsRepository(app)

    /**
     * Başlangıç değeri `Settings(loaded = false)`: DataStore okunana kadar arayüz
     * bunu kullanıcının gerçek tercihi gibi UYGULAMAMALI (bkz. [Settings.loaded]).
     */
    val settings: StateFlow<Settings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Son kaydetme hatası; arayüz "Kaydedildi" yerine bunu gösterir. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()
    fun clearSaveError() { _saveError.value = null }

    /** Bağlantı kurulamadıysa kullanıcıya gösterilecek neden. */
    val connectionFailure: StateFlow<String?> = VpnState.failureReason

    val connectionState: StateFlow<ConnectionState> = VpnState.state
    val profile = VpnState.profile

    /** Bağlantının kurulduğu an (epoch ms); ana ekrandaki süre sayacı okur. */
    val connectedSince: StateFlow<Long> = VpnState.connectedSince

    /**
     * Tünel açıkken uygulamalara gerçekten verilen DNS adresleri. Boşsa tünel
     * kapalıdır ve ayarlar ekranı yalnızca "verilecek" önizlemeyi gösterir.
     */
    val activeDnsServers: StateFlow<List<String>> = VpnState.dnsServers

    // ---- canlı strateji testi ----
    private val _testResults = MutableStateFlow<List<StrategyTestResult>>(emptyList())
    val testResults: StateFlow<List<StrategyTestResult>> = _testResults.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testWinner = MutableStateFlow<StrategyTestResult?>(null)
    val testWinner: StateFlow<StrategyTestResult?> = _testWinner.asStateFlow()

    // ---- yüklü uygulamalar ----
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    // ---- aktif ağ ----
    private val _network = MutableStateFlow(NetworkInfo())
    val network: StateFlow<NetworkInfo> = _network.asStateFlow()

    /**
     * Sosyal Erişim bildirimlerinin bağlamı. Tek yerde hesaplanır ki rehberin
     * açılış anındaki bağlamı ile kabul anındaki bağlamı AYNI kurala göre
     * karşılaştırılsın (bkz. [net.atom.dpibypass.social.RouteContexts]).
     */
    val routeContext: StateFlow<net.atom.dpibypass.social.RouteContext?> by lazy {
        combine(settings, network, VpnState.profile, VpnState.state,
            net.atom.dpibypass.social.SocialAccessRuntime.coordinator.state) { s, n, profile, connection, trial ->
            net.atom.dpibypass.social.RouteContexts.of(
                networkResolving = n.resolving, transport = n.transport.name, ispFamily = n.isp?.family?.name,
                contextGeneration = trial.contextGeneration, engineConnected = connection == ConnectionState.Connected,
                engineStrategy = profile?.strategyId, trial = trial,
                dnsKey = "${s.dohProvider.name}:${s.customDohUrl.hashCode()}", disableQuic = s.disableQuic,
                scopeKey = "${s.appFilterMode.name}:${s.selectedApps.sorted().hashCode()}", vodafone = s.vodafoneUnlimitedMode,
                processId = net.atom.dpibypass.social.SocialAccessRuntime.processId,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    /** Son VPN izni penceresinin sonucu (rehber reddi ayrı bir düzeltme adımıyla gösterir). */
    private val _vpnPermissionDenied = MutableStateFlow(false)
    val vpnPermissionDenied: StateFlow<Boolean> = _vpnPermissionDenied.asStateFlow()
    fun onVpnPermissionResult(granted: Boolean) { _vpnPermissionDenied.value = !granted }
    fun clearVpnPermissionDenied() { _vpnPermissionDenied.value = false }

    override fun routeContextNow(): net.atom.dpibypass.social.RouteContext? = routeContext.value
    override fun settingsNow(): Settings = settings.value
    override fun ispFamilyNow() = socialIspFamily()
    override suspend fun saveSocialMode(mode: net.atom.dpibypass.social.TestMode) { repo.setSocialAccessMode(mode) }

    init {
        net.atom.dpibypass.social.SocialAccessRuntime.initialize(app)
        net.atom.dpibypass.ui.social.SocialGuideHost.attach(this)
        // Bağlam akışı hemen başlasın: rehber ilk açılışı okurken null görmesin.
        routeContext.value
        refreshNetwork()
        // Sosyal Erişim açık/kapalı durumu, kaydedilmiş GERÇEK değerden izlenir.
        // `settings` StateFlow'u DataStore okunana kadar varsayılan `Settings()`
        // (kapalı) yayınlar; eskiden ekran bu ilk değeri görüp süren bir denemeyi
        // durdurabiliyordu.
        viewModelScope.launch {
            // Okuma hatasında varsayılan "kapalı" değeri gerçek tercih sayılmaz.
            repo.settings.filter { !it.readError }.map { it.socialAccessEnabled }.distinctUntilChanged().collect { enabled ->
                net.atom.dpibypass.social.SocialAccessRuntime.coordinator.enabled(enabled)
                net.atom.dpibypass.social.SocialAccessRuntime.observeNetworks(enabled)
            }
        }
    }

    /**
     * Aktif ağı yeniden çözer. Taşıyıcı (Wi-Fi / mobil) anında bilinir ve hemen
     * yayınlanır; ISS adı ağ üzerinden geldiği için arkadan tamamlanır. Böylece
     * ekran "Bilinmiyor"da takılı kalmaz, kademeli olarak dolar.
     */
    fun refreshNetwork() {
        val detector = IspDetector(getApplication<Application>())
        val transport = detector.activeTransport()
        _network.value = NetworkInfo(transport = transport, isp = null, resolving = true)
        launch {
            val isp = runCatching { detector.bestGuess() }.getOrNull()
            _network.value = NetworkInfo(transport = transport, isp = isp, resolving = false)
        }
    }

    // ---- ayar setter'ları ----
    fun setOperationMode(mode: OperationMode) = launch { repo.setOperationMode(mode) }
    fun setSelectedIsp(isp: Isp) = launch { repo.setSelectedIsp(isp) }
    fun setSelectedStrategy(id: String) = launch { repo.setSelectedStrategy(id) }
    fun setAdvancedEnabled(v: Boolean) = launch { repo.setAdvancedEnabled(v) }
    fun setAdvancedArgs(v: String) = launch { repo.setAdvancedArgs(v) }
    fun setDohProvider(p: DohProvider) = launch { repo.setDohProvider(p) }
    fun setCustomDohUrl(v: String) = launch { repo.setCustomDohUrl(v) }
    fun setAppFilterMode(m: AppFilterMode) = launch { repo.setAppFilterMode(m) }
    /** DataStore içinde atomik: hızlı art arda dokunuşlarda seçim kaybolmaz. */
    fun toggleApp(pkg: String, selected: Boolean) = launch {
        repo.updateSelectedApps { if (selected) it + pkg else it - pkg }
    }
    fun clearSelectedApps() = launch { repo.setSelectedApps(emptySet()) }
    fun setAutoConnectOnBoot(v: Boolean) = launch { repo.setAutoConnectOnBoot(v) }
    fun setVodafoneUnlimitedMode(v: Boolean) = launch { repo.setVodafoneUnlimitedMode(v) }
    val vodafoneModeActive: StateFlow<Boolean> = VpnState.vodafoneModeActive
    fun setTheme(t: ThemePref) = launch { repo.setTheme(t) }
    fun setHaptics(v: Boolean) = launch { repo.setHaptics(v) }
    fun setDisableQuic(v: Boolean) = launch { repo.setDisableQuic(v) }
    fun setExtraBlockedHosts(v: String) = launch { repo.setExtraBlockedHosts(v) }
    fun setOnboardingDone(v: Boolean) = launch { repo.setOnboardingDone(v) }
    fun setSamsungVpnIndicator(v: Boolean) = launch { repo.setSamsungVpnIndicator(v) }
    fun setSocialAccessEnabled(v: Boolean) = launch { repo.setSocialAccessEnabled(v) }
    fun setSocialPlatform(id: String, selected: Boolean) = launch {
        repo.updateSocialPlatforms { if (selected) it + id else it - id }
    }
    fun setSocialTargetUrl(v: String) = launch { repo.setSocialTargetUrl(v) }
    fun setSocialBrowserPackage(v: String) = launch { repo.setSocialBrowserPackage(v) }
    fun setSocialPrimary(id: String) = launch { repo.setSocialPrimary(id) }
    /** Kapsam dışındaki platform için tek adımlı düzeltme (kullanıcının açık komutu). */
    fun bringIntoScope(packages: Set<String>) = launch { repo.bringIntoScope(packages) }
    fun restoreScopeBackup() = launch { repo.restoreScopeBackup() }
    fun setSocialAccessMode(v: net.atom.dpibypass.social.TestMode) = launch {
        repo.setSocialAccessMode(v)
        if (v == net.atom.dpibypass.social.TestMode.MANUAL) net.atom.dpibypass.social.SocialAccessRuntime.coordinator.manual()
        else net.atom.dpibypass.social.SocialAccessRuntime.coordinator.automatic()
    }

    /**
     * Yalnız tercihi kaydeder; süren ya da biten bir denemeye dokunmaz (rehberde kip,
     * yeni tur başlatılırken o tura verilir). [setSocialAccessMode] ise süren denemeyi
     * de o kipe geçirir (Gelişmiş ayarlar).
     */
    fun saveSocialModeOnly(v: net.atom.dpibypass.social.TestMode) = launch { repo.setSocialAccessMode(v) }

    /** Sosyal Erişim yöntem sırası: o an bağlı olunan ağın ISS ailesine göre. */
    fun socialIspFamily() = (network.value.isp ?: settings.value.selectedIsp).family

    /**
     * Sosyal Erişim'de onaylanan yerel DPI profilini kullanıcının MANUEL strateji
     * seçimi de yapar (açık komut). Otomatik kipte ağ profili, plan onaylandığında
     * servis tarafından zaten yazılır. Strateji tüneli yeniden kurdurmaz.
     * [onDone] yalnız kayıt GERÇEKTEN başarılı olunca true alır.
     */
    fun keepSocialStrategy(strategyId: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = StrategyPool.byId(strategyId) != null && runCatching {
                repo.setSelectedStrategy(strategyId)
                val detector = IspDetector(getApplication<Application>())
                val transport = detector.activeTransport()
                val isp = network.value.isp ?: runCatching { detector.bestGuess() }.getOrNull() ?: settings.value.selectedIsp
                repo.saveNetworkProfile(IspDetector.networkKey(transport, isp), strategyId)
            }.isSuccess
            if (!ok) _saveError.value = "Profil kaydedilemedi; ayarlar değişmedi."
            onDone(ok)
        }
    }

    /**
     * "Sadece Discord" modu: uygulama yalnızca Discord'un trafiğini tünelden
     * geçirir (split tunneling → Include, seçili = Discord) ve en iyi stratejiyi
     * otomatik bulur. Kurulum sihirbazından çağrılır.
     */
    fun enableDiscordOnlyMode() = launch {
        // Tek atomik yazım; önceki kapsam yedeklenir ve geri yüklenebilir.
        repo.enableOnlyApps(setOf(DISCORD_PACKAGE))
    }

    /**
     * Test şu an çalıştırılabilir mi? ByeDPI'ın native durumu tekildir (global
     * `params`): tünel açıkken ikinci bir motor örneği açmak çalışan tüneli
     * bozar. Bu yüzden test yalnızca tünel kapalıyken yapılır.
     */
    val canRunTest: StateFlow<Boolean> = connectionState
        .map { it == ConnectionState.Disconnected || it == ConnectionState.Failed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Canlı strateji testi — VPN kurmadan, yerel SOCKS + DoH ile her preset'i tek
     * tek dener; en düşük ping'li çalışanı seçer ve kaydeder. "Gerçek interneti
     * yavaşlatmadan" testin çekirdeği burasıdır.
     *
     * Kazanan iki yere yazılır: manuel kipte önseçili olsun diye ayarlara, ve bu
     * ağın profiline — böylece "Otomatik"te bağlanırken hızlı yol doğrudan bu
     * stratejiyi doğrular. Testin bulduğu kazanan ile bağlanmanın seçtiği
     * strateji artık ayrışmaz.
     */
    private var testJob: Job? = null

    fun runStrategyTest() {
        if (_testing.value || !canRunTest.value) return
        // Motor tekil: VPN (ya da başka bir test) sahipken test başlamaz. Kullanıcı
        // test sürerken bağlanırsa servis testi iptal ettirir (bkz. EngineOwnership).
        val token = EngineOwnership.tryAcquire(EngineOwnership.Owner.STRATEGY_TEST) { testJob?.cancel() } ?: return
        _testing.value = true
        _testWinner.value = null
        testJob = viewModelScope.launch {
            try {
                val s = settings.value
                val doh = DohResolver(s.effectiveDohUrl())
                val tester = StrategyTester(viewModelScope, doh, s.vodafoneUnlimitedMode)
                val detector = IspDetector(getApplication<Application>())
                // Testin sıralaması da gerçek ağa göre yapılır: ev Wi-Fi'ında
                // SIM operatörünün stratejileriyle başlamak zaman kaybıdır.
                val transport = detector.activeTransport()
                val isp = detector.bestGuess() ?: s.selectedIsp
                val ordered = StrategyPool.orderedFor(isp.family)
                val social = if (s.socialAccessEnabled) net.atom.dpibypass.social.SocialAccessRuntime.probeHosts(s.socialPlatforms) else emptyList()
                val hosts = (StrategyTester.DEFAULT_BLOCKED_HOSTS + extraHosts(s) + social).distinct()

                // Tester sonuçlarını UI'a canlı yansıt. Test işinin çocuğudur:
                // test iptal edilirse ayna da durur.
                val mirror = this.launch {
                    tester.results.collect { _testResults.value = it }
                }
                // Tanı ekranı: süre bütçesi yok — kullanıcıya tüm preset sonuçları
                // gösterilir, ölçüm de cömert zaman aşımıyla yapılır.
                val best = tester.run(
                    strategies = ordered,
                    blockedHosts = hosts,
                    timeoutMs = StrategyTester.DEFAULT_TIMEOUT_MS,
                )
                mirror.cancel()
                if (best != null) {
                    repo.setSelectedStrategy(best.first.id)
                    repo.saveNetworkProfile(
                        IspDetector.networkKey(transport, isp),
                        best.first.id,
                    )
                    _testWinner.value = _testResults.value.firstOrNull { it.strategy.id == best.first.id }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveError.value = "Strateji testi tamamlanamadı."
            } finally {
                _testing.value = false
                EngineOwnership.release(token)
            }
        }
    }

    fun loadInstalledApps() {
        if (_apps.value.isNotEmpty()) return
        launch {
            val list = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val self = getApplication<Application>().packageName
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != self }
                    // İnternet izni olanları öne çıkarmak yerine hepsini gösteriyoruz;
                    // sık kullanılanlar UI'da üste alınır.
                    .map {
                        AppEntry(
                            packageName = it.packageName,
                            label = pm.getApplicationLabel(it).toString(),
                            system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                    .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
                    .toList()
            }
            _apps.value = list
        }
    }

    override fun onCleared() {
        net.atom.dpibypass.ui.social.SocialGuideHost.detach(this)
        super.onCleared()
    }

    private fun extraHosts(s: Settings): List<String> =
        s.extraBlockedHosts.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotBlank() }

    /** Ayar yazımı başarısız olursa uygulama çökmez ve sessiz de kalmaz. */
    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _saveError.value = "Ayar kaydedilemedi; önceki değer geçerli." }
        }
    }

    companion object {
        const val DISCORD_PACKAGE = "com.discord"
    }
}
