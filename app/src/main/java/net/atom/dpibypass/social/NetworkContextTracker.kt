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
 * "Wi-Fi varsa mutlaka birincildir" varsayımı tek başına yeterli değil: Android
 * doğrulanmamış bir Wi-Fi yerine hücreseli seçebilir. Android 12+ üzerinde sistem
 * kendi seçtiği en iyi ağı bildirir ([selected]); sıralama ([update]) yalnızca
 * eski sürümlerde kullanılan yaklaşık bir yedektir. Doğrulanmış internet bilgisi
 * ([usable]) giriş portalı/bağlantısız ağda ölçüm yapılmasını engeller.
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
        if (kind == null) { networks.remove(network); validated.remove(network) } else networks[network] = kind
        val primary = networks.entries.minByOrNull { it.value.rank }?.key ?: return false
        val previous = lastPrimary
        lastPrimary = primary
        return previous != null && previous != primary
    }

    private val validated = HashMap<Long, Boolean>()
    private var systemChoice: Long? = null

    /**
     * Sistemin seçtiği en iyi alttaki ağ (Android 12+ `registerBestMatchingNetworkCallback`).
     * Önceki seçimden FARKLI bir ağa geçildiyse true; ağ kaybı (null) tek başına değişim değildir.
     */
    @Synchronized
    fun selected(network: Long?): Boolean {
        if (network == null) { systemChoice = null; return false }
        systemChoice = network
        val previous = lastPrimary
        lastPrimary = network
        return previous != null && previous != network
    }

    /** Ağın doğrulanmış internet/portal bilgisi (NET_CAPABILITY_VALIDATED, CAPTIVE_PORTAL yok). */
    @Synchronized
    fun capabilities(network: Long, validatedInternet: Boolean) { validated[network] = validatedInternet }

    /** Şu anki birincil ağ doğrulanmış internete sahip mi? Bilinmiyorsa true (ölçümü engellemez). */
    @Synchronized
    fun usable(): Boolean {
        val primary = systemChoice ?: networks.entries.minByOrNull { it.value.rank }?.key ?: return true
        return validated[primary] ?: true
    }

    @Synchronized
    fun clear() {
        networks.clear()
        validated.clear()
        systemChoice = null
        lastPrimary = null
    }
}
