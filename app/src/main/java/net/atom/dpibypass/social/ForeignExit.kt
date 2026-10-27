package net.atom.dpibypass.social

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

// ---------------------------------------------------------------------------
// Yurt dışı çıkış — Sosyal Erişim'in asıl amacı.
//
// Platformlar e-Devlet (kimlik/yaş) doğrulamasını bağlantının GELDİĞİ ÜLKEYE göre
// ister: IP adresi Türkiye'deyse doğrulama ekranı çıkar. DPI aşma bunu çözmez,
// çünkü paketler yine Türkiye'deki IP'den çıkar. Bu yüzden seçili platformların
// alan adlarına giden bağlantılar kullanıcının verdiği YURT DIŞINDAKİ bir proxy
// sunucusundan çıkarılır; geri kalan her şey eskisi gibi yerel DPI motorundan
// (ByeDPI) doğrudan gider. Ayrım bağlantının ilk paketindeki alan adına (TLS SNI
// ya da HTTP Host) göre yapılır, bkz. [Sniff] ve vpn/ExitRouter.
// ---------------------------------------------------------------------------

/** Kullanıcının verdiği yurt dışı proxy sunucusu. */
data class ExitServer(
    val kind: Kind,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
) {
    enum class Kind(val label: String) { SOCKS5("SOCKS5"), HTTP("HTTP") }

    val hasAuth: Boolean get() = username.isNotEmpty()

    /** Ekranda gösterilen kısa ad; parola asla gösterilmez. */
    val display: String get() = "${kind.label} · $host:$port" + if (hasAuth) " · kullanıcı: $username" else ""

    companion object {
        /**
         * Kabul edilen biçimler:
         *  - `socks5://kullanici:parola@sunucu:port` (socks5h, socks da olur)
         *  - `http://kullanici:parola@sunucu:port` (https:// yazılsa da HTTP CONNECT)
         *  - `sunucu:port` (SOCKS5 sayılır)
         *  - `sunucu:port:kullanici:parola` (sağlayıcıların sık verdiği biçim, SOCKS5)
         * Geçersizse null.
         */
        fun parse(raw: String): ExitServer? {
            val text = raw.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if ("://" in text) {
                val uri = runCatching { URI(text) }.getOrNull() ?: return null
                val kind = when (uri.scheme?.lowercase(Locale.ROOT)) {
                    "socks5", "socks5h", "socks" -> Kind.SOCKS5
                    "http", "https" -> Kind.HTTP
                    else -> return null
                }
                val host = uri.host?.trim('[', ']')?.takeIf { validHost(it) } ?: return null
                val port = uri.port.takeIf { it in 1..65535 } ?: return null
                val info = uri.rawUserInfo.orEmpty()
                val user = decode(info.substringBefore(':'))
                val pass = if (':' in info) decode(info.substringAfter(':')) else ""
                return ExitServer(kind, host, port, user, pass).takeIf { it.credentialsFit() }
            }
            val parts = text.split(':')
            if (parts.size != 2 && parts.size != 4) return null
            val host = parts[0].takeIf { validHost(it) } ?: return null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val server = if (parts.size == 4) ExitServer(Kind.SOCKS5, host, port, parts[2], parts[3])
            else ExitServer(Kind.SOCKS5, host, port)
            return server.takeIf { it.credentialsFit() }
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun validHost(host: String): Boolean =
            host.isNotEmpty() && host.length <= 253 && host.all { it.isLetterOrDigit() || it in ".-_:" }

        /** SOCKS5 kullanıcı adı/parola alanları en çok 255 bayttır. */
        private fun ExitServer.credentialsFit() =
            username.toByteArray().size <= 255 && password.toByteArray().size <= 255 &&
                (password.isEmpty() || username.isNotEmpty())
    }
}

/**
 * Yurt dışından çıkarılabilen platformlar. [domains] platformun hesap, API ve
 * içerik sunucularıdır: doğrulama kararı API isteğinin geldiği IP'ye göre
 * verildiği için yalnız ana sayfayı değil, uygulamanın konuştuğu bütün adları
 * kapsar. Alt alan adları da eşleşir (`instagram.com` → `i.instagram.com`).
 */
enum class ExitPlatform(
    val id: String,
    val displayName: String,
    val packages: Set<String>,
    val domains: Set<String>,
    val note: String = "",
) {
    INSTAGRAM("instagram", "Instagram", setOf("com.instagram.android", "com.instagram.barcelona"),
        setOf("instagram.com", "cdninstagram.com", "ig.me", "threads.net", "threads.com",
            "facebook.com", "fbcdn.net", "facebook.net", "fbsbx.com"),
        note = "Threads de dahil. Instagram, Meta'nın ortak sunucularını kullandığı için Facebook adresleri de yurt dışından gider."),
    FACEBOOK("facebook", "Facebook", setOf("com.facebook.katana", "com.facebook.lite", "com.facebook.orca"),
        setOf("facebook.com", "fb.com", "fb.watch", "fbcdn.net", "facebook.net", "fbsbx.com", "messenger.com", "m.me"),
        note = "Messenger dahil."),
    X("x", "X (Twitter)", setOf("com.twitter.android"),
        setOf("x.com", "twitter.com", "twimg.com", "t.co")),
    TIKTOK("tiktok", "TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        setOf("tiktok.com", "tiktokv.com", "tiktokv.eu", "tiktokv.us", "tiktokw.us", "tiktokcdn.com", "tiktokcdn-eu.com",
            "tiktokcdn-us.com", "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "ttwstatic.com", "musical.ly")),
    YOUTUBE("youtube", "YouTube", setOf("com.google.android.youtube"),
        setOf("youtube.com", "youtu.be", "youtube-nocookie.com", "youtubei.googleapis.com", "googlevideo.com", "ytimg.com", "ggpht.com"),
        note = "Google hesabına girişin kendisi (accounts.google.com) Türkiye'den yapılır; YouTube'un yaş/kimlik kontrolü YouTube sunucularında olduğu için onlar yurt dışından gider."),
    REDDIT("reddit", "Reddit", setOf("com.reddit.frontpage"),
        setOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com")),
    DISCORD("discord", "Discord", setOf("com.discord"),
        setOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media"),
        note = "Mesajlar ve sunucular yurt dışından gider; sesli görüşme UDP olduğu için yerel yoldan gider."),
    TWITCH("twitch", "Twitch", setOf("tv.twitch.android.app"),
        setOf("twitch.tv", "ttvnw.net", "jtvnw.net")),
    SNAPCHAT("snapchat", "Snapchat", setOf("com.snapchat.android"),
        setOf("snapchat.com", "sc-cdn.net", "sc-static.net", "snap-dev.net", "snapkit.com")),
    BLUESKY("bluesky", "Bluesky", setOf("xyz.blueskyweb.app"),
        setOf("bsky.app", "bsky.social", "bsky.network")),
    KICK("kick", "Kick", setOf("com.kick.mobile"),
        setOf("kick.com"));

    companion object {
        fun byId(id: String?): ExitPlatform? = entries.firstOrNull { it.id == id }
    }
}

/** Hangi alan adının yurt dışından çıkacağına karar veren saf eşleyici. */
class ExitMatcher(domains: Collection<String>) {
    private val suffixes: Set<String> = domains.mapNotNull { normalize(it) }.toSet()

    val isEmpty: Boolean get() = suffixes.isEmpty()

    fun matches(host: String?): Boolean {
        var h = normalize(host ?: return false) ?: return false
        while (true) {
            if (h in suffixes) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    companion object {
        fun of(platformIds: Set<String>, extraDomains: String): ExitMatcher = ExitMatcher(
            platformIds.mapNotNull(ExitPlatform::byId).flatMap { it.domains } + parseExtra(extraDomains),
        )

        /** Kullanıcının elle eklediği alan adları: virgül, boşluk ya da satırla ayrılır. */
        fun parseExtra(raw: String): List<String> =
            raw.split(',', '\n', ' ', ';').mapNotNull { normalize(it) }

        fun normalize(raw: String): String? {
            var s = raw.trim().lowercase(Locale.ROOT)
            s = s.substringAfter("://").substringBefore('/').removePrefix("*.").removePrefix(".").trimEnd('.')
            if (s.isEmpty() || s.length > 253) return null
            if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
            return s
        }
    }
}

/**
 * Bağlantının ilk baytlarından hedef alan adını okur. TLS'de ClientHello'daki
 * SNI, düz HTTP'de Host başlığı. Hiçbir veri değiştirilmez; okunan baytlar
 * hangi yol seçilirse seçilsin olduğu gibi iletilir.
 */
object Sniff {
    sealed interface Result {
        data object NeedMore : Result
        data object Unknown : Result
        data class Host(val name: String) : Result
    }

    /** Ayrıştırma için tutulan en büyük ilk veri (bir TLS kaydı + başlık). */
    const val MAX_BYTES = 16 * 1024 + 5

    fun host(buf: ByteArray, len: Int): Result {
        if (len <= 0) return Result.NeedMore
        return if ((buf[0].toInt() and 0xFF) == 0x16) tls(buf, len) else http(buf, len)
    }

    private fun tls(b: ByteArray, len: Int): Result {
        if (len < 5) return Result.NeedMore
        if (u8(b, 1) != 3) return Result.Unknown
        val recordEnd = 5 + u16(b, 3)
        val end = minOf(recordEnd, len)
        val complete = len >= recordEnd
        fun short() = if (complete) Result.Unknown else Result.NeedMore
        if (end < 9) return short()
        if (u8(b, 5) != 1) return Result.Unknown // ClientHello değil
        var p = 9 + 2 + 32 // tür(1)+uzunluk(3) sonrası: sürüm + random
        if (p + 1 > end) return short()
        p += 1 + u8(b, p) // oturum kimliği
        if (p + 2 > end) return short()
        p += 2 + u16(b, p) // şifre takımları
        if (p + 1 > end) return short()
        p += 1 + u8(b, p) // sıkıştırma
        if (p + 2 > end) return short()
        val extEnd = p + 2 + u16(b, p)
        p += 2
        while (p + 4 <= minOf(extEnd, end)) {
            val type = u16(b, p)
            val size = u16(b, p + 2)
            p += 4
            if (type == 0) {
                if (p + size > end) return short()
                // server_name_list: liste uzunluğu(2), ad türü(1), ad uzunluğu(2), ad
                var q = p + 2
                val listEnd = minOf(p + size, end)
                while (q + 3 <= listEnd) {
                    val nameType = u8(b, q)
                    val nameLen = u16(b, q + 1)
                    q += 3
                    if (q + nameLen > listEnd) return Result.Unknown
                    if (nameType == 0) {
                        val name = String(b, q, nameLen, Charsets.US_ASCII)
                        return ExitMatcher.normalize(name)?.let { Result.Host(it) } ?: Result.Unknown
                    }
                    q += nameLen
                }
                return Result.Unknown
            }
            p += size
        }
        return if (p >= extEnd) Result.Unknown else short()
    }

    private val Methods = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ")

    private fun http(b: ByteArray, len: Int): Result {
        val head = String(b, 0, minOf(len, 8192), Charsets.ISO_8859_1)
        if (Methods.none { head.startsWith(it) }) {
            // Yöntem adı daha tamamlanmadıysa ("GE") bir paket daha beklenir.
            return if (Methods.any { it.startsWith(head) }) Result.NeedMore else Result.Unknown
        }
        val headersEnd = head.indexOf("\r\n\r\n")
        val headers = if (headersEnd >= 0) head.substring(0, headersEnd) else head
        headers.split("\r\n").drop(1).forEach { line ->
            if (line.startsWith("host:", ignoreCase = true)) {
                val value = line.substring(5).trim()
                val host = if (value.startsWith("[")) value.substringBefore(']').drop(1) else value.substringBefore(':')
                return ExitMatcher.normalize(host)?.let { Result.Host(it) } ?: Result.Unknown
            }
        }
        return if (headersEnd >= 0 || len >= 8192) Result.Unknown else Result.NeedMore
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
}

/** Türkçe ülke adı ve bayrak; ülke kodu bilinmiyorsa kodun kendisi. */
object Countries {
    fun name(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        if (cc.length != 2) return cc
        return Locale("", cc).getDisplayCountry(Locale.forLanguageTag("tr-TR")).ifBlank { cc }
    }

    fun flag(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }
}
