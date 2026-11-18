package net.atom.dpibypass.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import net.atom.dpibypass.wg.WireGuardConfig
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Tünele giren bir bağlantının hangi uygulamaya (UID) ait olduğunu söyler.
 *
 * hev (yamalı) her oturumda uygulamanın TUN üzerindeki kaynak adresini SOCKS5
 * kullanıcı adı olarak gönderir; ExitRouter bunu hedef adresle birlikte buraya
 * verir. Android 10+ resmî API'yi (yalnız etkin VPN uygulaması çağırabilir),
 * Android 8–9 /proc/net tablolarını kullanır. Bulunamazsa null: bağlantı VPN
 * uygulamasına ait sayılmaz ve olağan yoldan (DPI motoru) gider.
 */
fun interface ConnectionOwner {
    fun uid(udp: Boolean, source: InetSocketAddress, destination: InetSocketAddress): Int?

    companion object {
        /** hev'in gönderdiği "ip|port" kullanıcı adını çözer; ad çözümlemesi YAPMAZ. */
        fun parseSource(user: String?): InetSocketAddress? {
            val text = user ?: return null
            val ip = text.substringBeforeLast('|', "")
            val port = text.substringAfterLast('|', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (!WireGuardConfig.validIp(ip)) return null
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return null
            return InetSocketAddress(addr, port)
        }
    }
}

class AndroidConnectionOwner(context: Context) : ConnectionOwner {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun uid(udp: Boolean, source: InetSocketAddress, destination: InetSocketAddress): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proto = if (udp) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            fun ask(src: InetSocketAddress, dst: InetSocketAddress): Int? =
                runCatching { cm.getConnectionOwnerUid(proto, src, dst) }.getOrNull()?.takeIf { it != Process.INVALID_UID }
            ask(source, destination)?.let { return it }
            // Bağlanmamış UDP soketleri joker yerel adresle bağlıdır.
            if (udp) {
                val any = InetAddress.getByAddress(ByteArray(if (source.address.address.size == 4) 4 else 16))
                ask(InetSocketAddress(any, source.port), destination)?.let { return it }
            }
            return null
        }
        return ProcNet.lookup(udp, source)
    }
}

/** Android 8–9: /proc/net/{tcp,tcp6,udp,udp6} içinden yerel adres/port eşleşmesi. */
object ProcNet {
    fun lookup(udp: Boolean, source: InetSocketAddress): Int? {
        val names = if (udp) listOf("udp", "udp6") else listOf("tcp", "tcp6")
        for (name in names) {
            val lines = runCatching { File("/proc/net/$name").readLines() }.getOrNull() ?: continue
            find(lines, source, allowWildcard = udp)?.let { return it }
        }
        return null
    }

    /** Saf ayrıştırıcı (birim testli). */
    fun find(lines: List<String>, source: InetSocketAddress, allowWildcard: Boolean): Int? {
        val want = source.address.address
        var wildcard: Int? = null
        for (line in lines.drop(1)) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 8) continue
            val local = f[1]
            val port = local.substringAfter(':').toIntOrNull(16) ?: continue
            if (port != source.port) continue
            val ip = decode(local.substringBefore(':')) ?: continue
            val uid = f[7].toIntOrNull() ?: continue
            if (sameAddress(ip, want)) return uid
            if (allowWildcard && ip.all { it == 0.toByte() }) wildcard = uid
        }
        return wildcard
    }

    /** /proc biçimi: 32 bitlik sözcükler küçük uçlu (little-endian) hex. */
    internal fun decode(hex: String): ByteArray? {
        if (hex.length != 8 && hex.length != 32) return null
        val out = ByteArray(hex.length / 2)
        for (w in 0 until hex.length / 8) {
            for (b in 0 until 4) {
                val i = w * 8 + (3 - b) * 2
                out[w * 4 + b] = hex.substring(i, i + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
        return out
    }

    private fun sameAddress(a: ByteArray, b: ByteArray): Boolean {
        if (a.contentEquals(b)) return true
        // tcp6 tablosunda IPv4 adresleri ::ffff:a.b.c.d olarak görünür.
        fun v4(x: ByteArray): ByteArray? = when {
            x.size == 4 -> x
            x.size == 16 && x.copyOfRange(0, 10).all { it == 0.toByte() } && x[10] == 0xFF.toByte() && x[11] == 0xFF.toByte() -> x.copyOfRange(12, 16)
            else -> null
        }
        val va = v4(a); val vb = v4(b)
        return va != null && vb != null && va.contentEquals(vb)
    }
}
