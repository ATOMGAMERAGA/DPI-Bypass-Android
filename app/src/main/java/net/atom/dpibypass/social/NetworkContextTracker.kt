package net.atom.dpibypass.social

/**
 * Alttaki (VPN olmayan) BİRİNCİL ağı izler; deneme bağlamı yalnızca o değişince
 * değişmiş sayılır.
 *
 * Eskiden her yeni ağın gelişi bağlam değişimi sayılıyordu. "Mobil veri her zaman
 * açık" olan telefonlarda (Samsung'da varsayılan) Wi-Fi ve hücresel ağ birlikte
 * durur ve hücresel ağ arada bir düşüp geri gelir; bu da süren denemeleri sebepsiz
 * yere "ağ değişti" diye durduruyordu. Artık Wi-Fi'dayken hücresel ağın gidip
 * gelmesi hiçbir şey tetiklemez; Wi-Fi'dan hücresele (veya başka bir Wi-Fi'a)
 * geçiş tetikler.
 *
 * Saf Kotlin: Android'e bağımlı değildir, birim testinde doğrudan sınanır.
 */
class NetworkContextTracker {
    enum class Kind(val rank: Int) { ETHERNET(0), WIFI(1), CELLULAR(2), OTHER(3) }

    private val networks = LinkedHashMap<Long, Kind>()
    private var lastPrimary: Long? = null

    /**
     * Bir ağın görünmesi/özelliklerinin değişmesi ([kind] dolu) ya da kaybı
     * ([kind] null). Birincil ağ daha önce bilinen birincilden FARKLI bir ağa
     * geçtiyse true döner. Hiç ağ kalmaması tek başına değişim sayılmaz; sonra
     * aynı ağ geri gelirse de sayılmaz.
     */
    @Synchronized
    fun update(network: Long, kind: Kind?): Boolean {
        if (kind == null) networks.remove(network) else networks[network] = kind
        val primary = networks.entries.minByOrNull { it.value.rank }?.key ?: return false
        val previous = lastPrimary
        lastPrimary = primary
        return previous != null && previous != primary
    }

    @Synchronized
    fun clear() {
        networks.clear()
        lastPrimary = null
    }
}
