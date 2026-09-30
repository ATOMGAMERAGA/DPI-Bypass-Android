package net.atom.dpibypass.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.ActiveProfile

/**
 * Süreç-içi global bağlantı durumu. Servis günceller; UI (Activity/Compose) ve
 * Quick Settings Tile aynı süreçte olduğundan doğrudan gözlemler.
 */
object VpnState {
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _profile = MutableStateFlow<ActiveProfile?>(null)
    val profile: StateFlow<ActiveProfile?> = _profile.asStateFlow()

    // Bağlantının kurulduğu an (epoch ms), bağlı değilken 0. Ana ekrandaki canlı
    // "bağlı kalma süresi" sayacı buradan beslenir; durum burada değiştiği için
    // servis tarafında ekstra bir kayıt tutmaya gerek kalmaz.
    private val _connectedSince = MutableStateFlow(0L)
    val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

    // Tünel kurulurken uygulamalara GERÇEKTEN verilen DNS adresleri ve okunur
    // özeti. Ayarlar ekranı bunu gösterir: "seçtiğim DNS uygulanıyor mu?" sorusu
    // tahminle değil, servisin bildirdiği değerle yanıtlanır.
    private val _dnsServers = MutableStateFlow<List<String>>(emptyList())
    val dnsServers: StateFlow<List<String>> = _dnsServers.asStateFlow()

    private val _dnsLabel = MutableStateFlow("")
    val dnsLabel: StateFlow<String> = _dnsLabel.asStateFlow()

    private val _vodafoneModeActive = MutableStateFlow(false)
    val vodafoneModeActive: StateFlow<Boolean> = _vodafoneModeActive.asStateFlow()

    fun updateVodafoneMode(active: Boolean) {
        _vodafoneModeActive.value = active
    }

    fun updateDns(servers: List<String>, label: String) {
        _dnsServers.value = servers
        _dnsLabel.value = label
    }

    fun update(state: ConnectionState) {
        applyState(state)
    }

    fun update(state: ConnectionState, profile: ActiveProfile?) {
        applyState(state)
        _profile.value = profile
    }

    private fun applyState(state: ConnectionState) {
        if (state == ConnectionState.Connected) {
            // Yeniden "Connected" bildirimi gelirse (ör. watchdog toparlaması)
            // sayaç sıfırlanmaz; kesinti olmadıysa süre akmaya devam eder.
            if (_connectedSince.value == 0L) _connectedSince.value = System.currentTimeMillis()
        } else {
            _connectedSince.value = 0L
            // Tünel yokken "etkin DNS" diye bir şey de yok; eski değer ekranda
            // asılı kalmasın.
            if (state == ConnectionState.Disconnected || state == ConnectionState.Failed) {
                _dnsServers.value = emptyList()
                _dnsLabel.value = ""
                _vodafoneModeActive.value = false
            }
        }
        _state.value = state
    }

    fun isRunning(): Boolean =
        _state.value == ConnectionState.Connected ||
            _state.value == ConnectionState.Connecting ||
            _state.value == ConnectionState.Testing
}
