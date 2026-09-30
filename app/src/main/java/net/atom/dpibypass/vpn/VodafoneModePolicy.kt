package net.atom.dpibypass.vpn

import net.atom.dpibypass.data.AppFilterMode

/**
 * Vodafone hotspot'una bağlanan rootsuz cihazda yerel proxy'nin çıkış soketlerini
 * ayarlar. Hotspot telefonunun yönlendirmesi 65'i 64'e düşürür.
 */
object VodafoneModePolicy {
    private const val HOTSPOT_TTL = "65"

    fun proxyArgs(base: Array<String>, enabled: Boolean): Array<String> =
        if (enabled) base + arrayOf("-g", HOTSPOT_TTL, "-X") else base

    fun filterMode(saved: AppFilterMode, enabled: Boolean): AppFilterMode =
        if (enabled) AppFilterMode.All else saved

    fun captureIpv6(enabled: Boolean): Boolean = enabled
}
