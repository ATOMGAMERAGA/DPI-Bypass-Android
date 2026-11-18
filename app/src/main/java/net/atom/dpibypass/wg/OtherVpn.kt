package net.atom.dpibypass.wg

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Telefonda başka bir VPN uygulaması (Windscribe, Proton VPN…) açık mı?
 *
 * Android aynı anda yalnızca BİR VPN çalıştırır: DPI Bypass bağlanınca diğeri
 * kapanır. Bu yüzden bağlanmadan önce kullanıcıya sorulur ve sağlayıcının
 * WireGuard yapılandırmasını DPI Bypass'a eklemesi önerilir (VPN Tüneli): o
 * zaman iki iş tek tünelde birlikte yürür.
 */
object OtherVpn {
    /** Bilinen VPN uygulamaları: paket adı → görünen ad. */
    val KNOWN: Map<String, String> = linkedMapOf(
        "com.windscribe.vpn" to "Windscribe",
        "ch.protonvpn.android" to "Proton VPN",
    )

    fun active(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    fun installedNames(context: Context): List<String> = KNOWN.filterKeys { pkg ->
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
    }.values.toList()
}
