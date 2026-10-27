package net.atom.dpibypass.vpn

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Watchdog kararı (saf, birim testli).
 *
 * Yeniden başlatma HER açık bağlantıyı koparır: oyundaki (ör. Roblox) UDP
 * oturumu, sesli görüşme, indirme. Eskiden watchdog dakikada bir engelli bir
 * siteye TLS denemesi yapıyor, iki kez başarısız olunca motoru yeniden
 * başlatıyordu. Mobil ağdaki kısa bir kesinti ya da ISS'in o siteyi başka
 * yöntemle engellemesi bu yüzden oyunun ortasında "bağlantı kesildi" demekti;
 * aynı argümanlarla yeniden başlatmak DPI tarafındaki bir değişikliği de
 * düzeltmez. Artık yalnızca motorun KENDİSİ bozulduğunda müdahale edilir:
 *
 *   - Olay döngüsü bittiyse (ölü) hemen yeniden başlatılır.
 *   - Döngü yaşıyor ama yerel dinleyici art arda [unresponsiveLimit] kez yanıt
 *     vermiyorsa (kilitlenme) yeniden başlatılır.
 *
 * Ardışık yeniden başlatmalar arasında artan bir bekleme vardır; motor
 * [stableResetMs] boyunca sağlıklı kalırsa bekleme başa döner.
 */
class ProxyWatchdogPolicy(
    private val unresponsiveLimit: Int = 3,
    private val minBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 30_000L,
    private val stableResetMs: Long = 60_000L,
) {
    enum class Action { None, Restart }

    private var unresponsive = 0
    private var backoffMs = minBackoffMs
    private var lastRestartAt: Long? = null

    fun observe(proxyAlive: Boolean, listenerResponds: Boolean, nowMs: Long): Action {
        if (proxyAlive && listenerResponds) {
            unresponsive = 0
            val last = lastRestartAt
            if (last != null && nowMs - last >= stableResetMs) backoffMs = minBackoffMs
            return Action.None
        }
        if (proxyAlive) {
            unresponsive++
            if (unresponsive < unresponsiveLimit) return Action.None
        }
        val last = lastRestartAt
        if (last != null && nowMs - last < backoffMs) return Action.None
        if (last != null) backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
        lastRestartAt = nowMs
        unresponsive = 0
        return Action.Restart
    }
}

/** Yerel SOCKS5 dinleyicisinin olay döngüsü dönüyor mu? */
object ProxyListenerProbe {
    /**
     * SOCKS5 selamı gönderir. Herhangi bir yanıt — bağlantı havuzu dolu olduğu
     * için hemen kapatılmak dahil — döngünün çalıştığını gösterir. Yalnızca
     * bağlantı reddi ya da zaman aşımı "yanıt yok" sayılır; dolu havuz için
     * motoru yeniden başlatmak sadece mevcut bağlantıları da koparırdı.
     */
    fun responds(port: Int, timeoutMs: Int = 2_000): Boolean = Socket().use { socket ->
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        } catch (e: IOException) {
            return false
        }
        try {
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x00)); flush() }
            socket.getInputStream().read()
            true
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        }
    }
}
