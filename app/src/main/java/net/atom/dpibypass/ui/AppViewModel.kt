package net.atom.dpibypass.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val settings: StateFlow<Settings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

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

    init {
        refreshNetwork()
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
    fun toggleApp(pkg: String, selected: Boolean) = launch {
        val current = settings.value.selectedApps.toMutableSet()
        if (selected) current.add(pkg) else current.remove(pkg)
        repo.setSelectedApps(current)
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

    /**
     * "Sadece Discord" modu: uygulama yalnızca Discord'un trafiğini tünelden
     * geçirir (split tunneling → Include, seçili = Discord) ve en iyi stratejiyi
     * otomatik bulur. Kurulum sihirbazından çağrılır.
     */
    fun enableDiscordOnlyMode() = launch {
        repo.setOperationMode(OperationMode.Auto)
        repo.setAppFilterMode(AppFilterMode.Include)
        repo.setSelectedApps(setOf(DISCORD_PACKAGE))
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
    fun runStrategyTest() {
        if (_testing.value || !canRunTest.value) return
        _testing.value = true
        _testWinner.value = null
        launch {
            try {
                val s = settings.value
                val doh = DohResolver(s.effectiveDohUrl())
                val tester = StrategyTester(viewModelScope, doh)
                val detector = IspDetector(getApplication<Application>())
                // Testin sıralaması da gerçek ağa göre yapılır: ev Wi-Fi'ında
                // SIM operatörünün stratejileriyle başlamak zaman kaybıdır.
                val transport = detector.activeTransport()
                val isp = detector.bestGuess() ?: s.selectedIsp
                val ordered = StrategyPool.orderedFor(isp.family)
                val hosts = StrategyTester.DEFAULT_BLOCKED_HOSTS + extraHosts(s)

                // Tester sonuçlarını UI'a canlı yansıt. (viewModelScope.launch -> Job;
                // aşağıdaki mirror.cancel() için gerekli.)
                val mirror = viewModelScope.launch {
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
            } finally {
                _testing.value = false
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

    private fun extraHosts(s: Settings): List<String> =
        s.extraBlockedHosts.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotBlank() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        const val DISCORD_PACKAGE = "com.discord"
    }
}
