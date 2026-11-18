package net.atom.dpibypass.wg

import java.util.Base64
import java.util.Locale

/**
 * wg-quick biçimindeki (.conf) WireGuard yapılandırması.
 *
 * Windscribe (Hesabım → Config Generator → WireGuard) ve Proton VPN
 * (account.protonvpn.com → Downloads → WireGuard configuration) bu dosyayı
 * verir. DPI Bypass dosyayı kendi tünelinde çalıştırır; sağlayıcının uygulaması
 * gerekmez (Android aynı anda tek VPN'e izin verdiği için gerekmemeli de).
 *
 * Yalnız ilk [Peer] kullanılır; sağlayıcı dosyaları tek eşlidir.
 */
data class WireGuardConfig(
    val privateKey: ByteArray,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val peerPublicKey: ByteArray,
    val presharedKey: ByteArray?,
    val endpointHost: String,
    val endpointPort: Int,
    val allowedIps: List<String>,
    val keepalive: Int,
) {
    val provider: Provider get() = Provider.guess(endpointHost, dns)

    val endpointDisplay: String get() = if (':' in endpointHost) "[$endpointHost]:$endpointPort" else "$endpointHost:$endpointPort"

    /** Ekranda gösterilen kısa özet; anahtarlar asla gösterilmez. */
    val summary: String get() = "${provider.label} · $endpointDisplay"

    /**
     * wireguard-go'nun UAPI biçimi (anahtarlar hex). Uç nokta Kotlin tarafında
     * çözülmüş IP ile verilir: köprü ad çözümlemesi yapmaz ve Türkiye'deki DNS
     * sağlayıcının adını yanlış yanıtlayabilir.
     */
    fun toUapi(endpointIp: String): String = buildString {
        append("private_key=").append(hex(privateKey)).append('\n')
        append("replace_peers=true\n")
        append("public_key=").append(hex(peerPublicKey)).append('\n')
        presharedKey?.let { append("preshared_key=").append(hex(it)).append('\n') }
        val host = if (':' in endpointIp) "[$endpointIp]" else endpointIp
        append("endpoint=").append(host).append(':').append(endpointPort).append('\n')
        // NAT'ın arkasında bağlantı sessizce düşmesin: sağlayıcı vermediyse 25 sn.
        append("persistent_keepalive_interval=").append(if (keepalive > 0) keepalive else DEFAULT_KEEPALIVE).append('\n')
        append("replace_allowed_ips=true\n")
        // Tünele yalnız seçili uygulamalar girer; eş tarafında her şeye izin verilir.
        append("allowed_ip=0.0.0.0/0\n")
        if (addresses.any { ':' in it }) append("allowed_ip=::/0\n")
    }

    override fun equals(other: Any?): Boolean =
        other is WireGuardConfig && privateKey.contentEquals(other.privateKey) && peerPublicKey.contentEquals(other.peerPublicKey) &&
            (presharedKey ?: ByteArray(0)).contentEquals(other.presharedKey ?: ByteArray(0)) && (presharedKey == null) == (other.presharedKey == null) &&
            addresses == other.addresses && dns == other.dns && mtu == other.mtu && endpointHost == other.endpointHost &&
            endpointPort == other.endpointPort && allowedIps == other.allowedIps && keepalive == other.keepalive

    override fun hashCode(): Int = privateKey.contentHashCode() * 31 + peerPublicKey.contentHashCode() + endpointHost.hashCode()

    override fun toString(): String = "WireGuardConfig($summary)"

    enum class Provider(val label: String) {
        PROTON("Proton VPN"), WINDSCRIBE("Windscribe"), OTHER("WireGuard");

        companion object {
            fun guess(endpoint: String, dns: List<String>): Provider {
                val e = endpoint.lowercase(Locale.ROOT)
                return when {
                    // Proton: DNS 10.2.0.1, uç noktalar genelde çıplak IP.
                    "protonvpn" in e || "10.2.0.1" in dns -> PROTON
                    // Windscribe: whiskergalaxy.com / windscribe.com, DNS 10.255.255.x
                    "windscribe" in e || "whiskergalaxy" in e || dns.any { it.startsWith("10.255.255.") } -> WINDSCRIBE
                    else -> OTHER
                }
            }
        }
    }

    sealed interface ParseResult {
        data class Ok(val config: WireGuardConfig) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    companion object {
        const val DEFAULT_KEEPALIVE = 25
        const val DEFAULT_MTU = 1280

        fun parseOrNull(text: String): WireGuardConfig? = (parse(text) as? ParseResult.Ok)?.config

        fun parse(text: String): ParseResult {
            if (text.isBlank()) return ParseResult.Error("Yapılandırma boş.")
            val sections = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
            var current: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    val name = line.drop(1).dropLast(1).trim().lowercase(Locale.ROOT)
                    // Yalnız ilk Peer okunur.
                    current = if (name == "peer" && sections.containsKey("peer")) "ignored" else name
                    sections.getOrPut(current) { mutableMapOf() }
                    continue
                }
                val key = line.substringBefore('=').trim().lowercase(Locale.ROOT)
                val value = line.substringAfter('=', "").trim()
                if (current == null || '=' !in line) return ParseResult.Error("Bu bir WireGuard yapılandırması değil ([Interface] ve [Peer] bölümleri olmalı).")
                sections.getValue(current).getOrPut(key) { mutableListOf() }.add(value)
            }
            val iface = sections["interface"] ?: return ParseResult.Error("[Interface] bölümü yok.")
            val peer = sections["peer"] ?: return ParseResult.Error("[Peer] bölümü yok.")
            val priv = key(iface.first("privatekey")) ?: return ParseResult.Error("PrivateKey eksik ya da bozuk. Dosyayı sağlayıcıdan yeniden indir.")
            val pub = key(peer.first("publickey")) ?: return ParseResult.Error("Peer PublicKey eksik ya da bozuk.")
            val psk = peer.first("presharedkey")?.let { key(it) ?: return ParseResult.Error("PresharedKey bozuk.") }
            val addresses = iface.list("address")
            if (addresses.isEmpty() || addresses.any { !validCidr(it) }) return ParseResult.Error("Address satırı eksik ya da geçersiz.")
            val dns = iface.list("dns").filter { validIp(it) }
            val mtu = iface.first("mtu")?.toIntOrNull()?.takeIf { it in 576..9000 } ?: DEFAULT_MTU
            val endpoint = peer.first("endpoint") ?: return ParseResult.Error("Peer Endpoint (sunucu adresi) eksik.")
            val (host, port) = splitEndpoint(endpoint) ?: return ParseResult.Error("Endpoint geçersiz: $endpoint")
            val allowed = peer.list("allowedips")
            val keepalive = peer.first("persistentkeepalive")?.toIntOrNull()?.coerceIn(0, 3600) ?: 0
            return ParseResult.Ok(WireGuardConfig(priv, addresses, dns, mtu, pub, psk, host, port, allowed, keepalive))
        }

        private fun Map<String, List<String>>.first(k: String): String? = this[k]?.firstOrNull()?.takeIf { it.isNotEmpty() }
        private fun Map<String, List<String>>.list(k: String): List<String> =
            this[k].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }

        private fun key(b64: String?): ByteArray? =
            runCatching { Base64.getDecoder().decode(b64?.trim() ?: return null) }.getOrNull()?.takeIf { it.size == 32 }

        fun splitEndpoint(raw: String): Pair<String, Int>? {
            val s = raw.trim()
            val host: String
            val portText: String
            if (s.startsWith("[")) {
                host = s.substringAfter('[').substringBefore(']')
                portText = s.substringAfter("]:", "")
            } else {
                if (s.count { it == ':' } != 1) return null
                host = s.substringBefore(':')
                portText = s.substringAfter(':')
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isEmpty() || host.length > 253 || !host.all { it.isLetterOrDigit() || it in ".-:_" }) return null
            return host to port
        }

        fun validIp(s: String): Boolean = ipv4(s) || (':' in s && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' })

        private fun ipv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
        }

        private fun validCidr(s: String): Boolean {
            val ip = s.substringBefore('/')
            val bits = s.substringAfter('/', "").let { if (it.isEmpty()) null else it.toIntOrNull() ?: return false }
            if (!validIp(ip)) return false
            val max = if (':' in ip) 128 else 32
            return bits == null || bits in 0..max
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
