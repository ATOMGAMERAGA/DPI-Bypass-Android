package net.atom.dpibypass.social

import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.net.InetAddress

enum class TestMode { MANUAL, AUTO_BETA;
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: MANUAL }
}
enum class Tri { TRUE, FALSE, UNKNOWN }
enum class Verdict { PASS, FAIL, INCONCLUSIVE, UNSUPPORTED, NEEDS_INTERACTION, CANCELLED, NOT_RUN }
enum class Phase { DISABLED, IDLE, BASELINING, SELECTING, PREPARING, APPLYING, PROBING, AUTO_EVALUATING,
    WAITING_FOR_USER, CANCELLING, ROLLING_BACK, PAUSED, FINISHED, RECOVERY_REQUIRED }
enum class ProblemClass { NETWORK_UNREACHABLE, DNS_FAILURE_OR_INTERFERENCE, TLS_OR_SNI_INTERFERENCE_SUSPECTED,
    QUIC_OR_UDP_PATH_FAILURE, MEDIA_PATH_FAILURE, POSSIBLE_THROTTLING, LOGIN_REQUIRED,
    AGE_OR_ID_VERIFICATION_REQUIRED, BOT_CHALLENGE, ACCOUNT_RESTRICTION, CONTENT_NOT_AVAILABLE,
    RATE_LIMITED, CAPTIVE_PORTAL, SERVICE_OUTAGE_SUSPECTED, MIXED, UNKNOWN }

object PublicTargetAddress {
    fun allowed(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 16) {
            if (bytes[0] and 254 == 252) return false
            if (bytes.take(12) == listOf(0, 100, 255, 155, 0, 0, 0, 0, 0, 0, 0, 0))
                return allowed(InetAddress.getByAddress(address.address.takeLast(4).toByteArray()))
            return true
        }
        return bytes[0] !in setOf(0, 10, 127) && bytes[0] < 224 && !(bytes[0] == 100 && bytes[1] in 64..127) &&
            !(bytes[0] == 169 && bytes[1] == 254) && !(bytes[0] == 172 && bytes[1] in 16..31) &&
            !(bytes[0] == 192 && bytes[1] == 168) && !(bytes[0] == 198 && bytes[1] in 18..19)
    }
}

data class Goal(val platformId: String = "youtube", val problemClass: String = "UNKNOWN",
    val identityContext: String = "SAME_ACCOUNT", val allowedSurfaces: Set<String> = setOf("OFFICIAL_NATIVE_APP"),
    val requiredCapabilities: Set<String> = setOf("VIEW_SELECTED_CONTENT"), val publicFallbackAllowed: Boolean = false) {
    companion object {
        fun fromJson(j: JSONObject) = Goal(j.optString("platformId", "youtube"), j.optString("problemClass", "UNKNOWN"),
            j.optString("identityContext", "UNKNOWN"), j.strings("allowedSurfaces"), j.strings("requiredCapabilities"), j.optBoolean("publicFallbackAllowed"))
    }
}

data class Target(val platformId: String, val url: String, val contentId: String, val targetRef: String = UUID.randomUUID().toString()) {
    /** Hedef sonuçlarının anahtarı: platform başına bir sonuç; özel alan adında alan adı başına. */
    val key: String get() = if (platformId == SocialPlatform.CUSTOM.id) "custom:" + (runCatching { URI(url).host }.getOrNull() ?: url) else platformId
    /** Ağ yolu kontrolünün adresi: içerik bağlantısı verilmediyse platformun kontrol adresi. */
    val probeUrl: String get() = if (contentId == HOME_CONTENT) SocialPlatform.byId(platformId)?.probeUrl?.ifEmpty { url } ?: url else url
    companion object {
        fun resourceAllowed(raw: String): Boolean = runCatching {
            val u = URI(raw)
            if (u.scheme != "https" || u.userInfo != null || u.port !in listOf(-1, 443)) return false
            val host = u.host?.lowercase() ?: return false
            setOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com", "google.com",
                "instagram.com", "cdninstagram.com", "fbcdn.net").any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)
        /** Platform ana sayfası; bağlantı verilmediğinde Manuel testin hedefi. */
        const val HOME_CONTENT = "home"
        fun home(platform: String): Target? = SocialPlatform.byId(platform)?.takeIf { it.homeUrl.isNotEmpty() }?.let { Target(it.id, it.homeUrl, HOME_CONTENT) }

        /** Paylaşım metninden ("Şuna bak: https://…") ilk bağlantıyı ayıklar. */
        fun extractUrl(text: String): String {
            val trimmed = text.trim()
            Regex("https?://[^\\s<>\"']+").find(trimmed)?.let { return it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']') }
            return trimmed
        }

        /** Bağlantının ait olduğu platform; tanınmıyorsa null. */
        fun detectPlatform(raw: String): SocialPlatform? = runCatching {
            val host = URI(normalize(raw)).host?.lowercase() ?: return null
            SocialPlatform.catalog.firstOrNull { it.owns(host) }
        }.getOrNull()

        /** Şemasız girişe ("youtube.com/…") https eklenir; http:// olduğu gibi kalır ve reddedilir. */
        private fun normalize(raw: String): String {
            val url = extractUrl(raw)
            return if ("://" in url) url else "https://$url"
        }

        fun parse(platform: String, raw: String): Target? = runCatching {
            val u = URI(normalize(raw))
            // Fragman sunucuya hiç gitmez; atılır. Sorgu da (si, igsh gibi izleme
            // parametreleri) yalnızca platformun gerçekten ihtiyaç duyduğu anahtarlar
            // dışında kanonik bağlantıya taşınmaz.
            if (u.scheme != "https" || u.userInfo != null || u.port !in listOf(-1, 443)) return null
            val host = u.host?.lowercase() ?: return null
            val path = u.rawPath?.ifEmpty { "/" } ?: "/"
            val canonical: String
            val id: String
            when (platform) {
                "youtube" -> {
                    if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) return null
                    val segment = Regex("/(shorts|live|embed)/([^/]+)/?").matchEntire(path)
                    id = if (host == "youtu.be") path.removePrefix("/").removeSuffix("/") else if (path == "/watch") {
                        u.rawQuery?.split('&')?.singleOrNull { it.startsWith("v=") }?.removePrefix("v=") ?: return null
                    } else segment?.groupValues?.get(2) ?: return null
                    if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
                    canonical = "https://www.youtube.com/watch?v=$id"
                }
                "instagram" -> {
                    if (host !in setOf("instagram.com", "www.instagram.com", "m.instagram.com")) return null
                    // /p/, /reel/, /reels/, /tv/ ve kullanıcı adı önekli biçimler (/kullanici/p/…).
                    val match = Regex("(?:/[A-Za-z0-9._]{1,30})?/(p|reel|reels|tv)/([A-Za-z0-9_-]{3,80})/?").matchEntire(path) ?: return null
                    id = match.groupValues[2]
                    val kind = when (match.groupValues[1]) { "reels", "reel" -> "reel"; else -> "p" }
                    canonical = "https://www.instagram.com/$kind/$id/"
                }
                "custom" -> {
                    // Herkese açık bir alan adı olmalı: IP sabiti, tek etiketli ad ve
                    // yerel ağ son ekleri reddedilir. Adresin kendisi probe sırasında
                    // PublicTargetAddress ile ayrıca denetlenir.
                    if (host.length > 253 || !host.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))) return null
                    if (listOf(".local", ".lan", ".internal", ".home", ".arpa", ".localhost").any { host.endsWith(it) }) return null
                    if (SocialPlatform.catalog.any { it.owns(host) }) return null
                    if (path.length > 200 || !path.matches(Regex("/[A-Za-z0-9._~@%+\\-/]*"))) return null
                    id = HOME_CONTENT
                    canonical = "https://$host$path"
                }
                else -> {
                    val p = SocialPlatform.byId(platform) ?: return null
                    if (p.autoObserver || !p.owns(host)) return null
                    if (path.length > 200 || !path.matches(Regex("/[A-Za-z0-9._~@%+\\-/]*"))) return null
                    val query = u.rawQuery?.split('&')?.filter { pair -> p.queryKeys.any { pair.startsWith("$it=") } }
                        ?.filter { it.matches(Regex("[A-Za-z_]+=[A-Za-z0-9_\\-]{1,64}")) }.orEmpty()
                    id = path.split('/').lastOrNull { it.isNotEmpty() } ?: HOME_CONTENT
                    canonical = "https://$host$path" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
                }
            }
            Target(platform, canonical, id)
        }.getOrNull()
    }
}

/** Bir platform için ayrı ayrı değerlendirilen özellikler. */
enum class Capability { NETWORK, CONTENT, MEDIA, VOICE, NATIVE_APP }

/** Bir özelliğin nasıl doğrulanabildiği. */
enum class CheckMode { AUTO, MANUAL, UNSUPPORTED }

/**
 * Sürümlü platform kataloğu. Her girdi yalnızca doğrulanmış verileri taşır:
 *  - [packages]: platformun resmî Android paket adları (kapsam kontrolü için)
 *  - [hosts]: bağlantı tanıma için sahip olunan alan adları (paylaşılan CDN'ler
 *    bilinçli olarak YOK: bir CDN'in tamamı bir platforma ait sayılmaz)
 *  - [probeUrl]: düşük maliyetli ağ yolu kontrolü (TLS + HTTP yanıtı). Yalnızca
 *    teknik ağ erişimini gösterir; içerik/hesap/uygulama başarısı değildir.
 *  - [probeHosts]: bağlanırken yapılan strateji doğrulamasında TLS el sıkışması
 *    denenen alan adları (çoklu uygulama ortak planı bunlarla aranır)
 *  - [features]: hangi özelliğin otomatik, hangisinin kullanıcı onayıyla
 *    doğrulanabildiği. Burada AUTO yazmayan hiçbir şey otomatik "doğrulandı" olmaz.
 *  - [note]: bilinen sınır (ör. native uygulamanın kendi protokolü).
 *
 * [autoObserver] yalnızca test penceresinde içerik gözlemcisi (social-observer.js)
 * olan platformlarda açıktır.
 */
enum class SocialPlatform(val id: String, val displayName: String, val hosts: Set<String>, val homeUrl: String,
    val autoObserver: Boolean, val queryKeys: Set<String> = emptySet(), val packages: Set<String> = emptySet(),
    val probeHosts: List<String> = emptyList(), val features: Map<Capability, CheckMode> = emptyMap(),
    val note: String = "", private val probe: String? = null) {
    YOUTUBE("youtube", "YouTube", setOf("youtube.com", "youtu.be"), "https://www.youtube.com/", true,
        packages = setOf("com.google.android.youtube"), probeHosts = listOf("www.youtube.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.AUTO,
            Capability.MEDIA to CheckMode.AUTO, Capability.NATIVE_APP to CheckMode.MANUAL),
        note = "İçerik/medya otomatik gözlemi yalnız belirli herkese açık video bağlantısında ve test penceresinde (doğrudan yol) çalışır."),
    INSTAGRAM("instagram", "Instagram", setOf("instagram.com"), "https://www.instagram.com/", true,
        packages = setOf("com.instagram.android"), probeHosts = listOf("www.instagram.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.AUTO,
            Capability.MEDIA to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL),
        note = "İçerik otomatik gözlemi yalnız belirli herkese açık gönderi bağlantısında ve test penceresinde çalışır."),
    X("x", "X (Twitter)", setOf("x.com", "twitter.com"), "https://x.com/", false,
        packages = setOf("com.twitter.android"), probeHosts = listOf("x.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL,
            Capability.MEDIA to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL)),
    TIKTOK("tiktok", "TikTok", setOf("tiktok.com"), "https://www.tiktok.com/", false,
        packages = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), probeHosts = listOf("www.tiktok.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL,
            Capability.MEDIA to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL)),
    DISCORD("discord", "Discord", setOf("discord.com", "discord.gg", "discordapp.com"), "https://discord.com/app", false,
        packages = setOf("com.discord"), probeHosts = listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL,
            Capability.VOICE to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL),
        note = "Ana sayfanın açılması sesli görüşmenin çalıştığını kanıtlamaz; ses UDP gerektirir."),
    REDDIT("reddit", "Reddit", setOf("reddit.com", "redd.it"), "https://www.reddit.com/", false,
        packages = setOf("com.reddit.frontpage"), probeHosts = listOf("www.reddit.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL,
            Capability.MEDIA to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL)),
    FACEBOOK("facebook", "Facebook", setOf("facebook.com", "fb.watch", "fb.com"), "https://www.facebook.com/", false, setOf("v", "story_fbid", "id"),
        packages = setOf("com.facebook.katana"), probeHosts = listOf("www.facebook.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL,
            Capability.MEDIA to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL)),
    // Aşağıdakiler erişimleri engellenmiş varsayılarak EKLENMEDİ: kullanıcı kendi
    // ağında sorun yaşıyorsa ağ yolunu ölçebilsin ve aynı ortak plana dahil
    // edebilsin diye vardır. Yalnız teknik ağ yolu otomatik ölçülür.
    TELEGRAM("telegram", "Telegram", setOf("telegram.org", "t.me", "telegram.me"), "https://web.telegram.org/", false,
        packages = setOf("org.telegram.messenger"), probeHosts = listOf("web.telegram.org"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.NATIVE_APP to CheckMode.MANUAL, Capability.VOICE to CheckMode.MANUAL),
        note = "Web kontrolü yalnız web istemcisinin ağ yolunu ölçer; uygulama kendi protokolüyle IP adreslerine bağlanır."),
    WHATSAPP("whatsapp", "WhatsApp", setOf("whatsapp.com", "wa.me"), "https://web.whatsapp.com/", false,
        packages = setOf("com.whatsapp", "com.whatsapp.w4b"), probeHosts = listOf("web.whatsapp.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.NATIVE_APP to CheckMode.MANUAL, Capability.VOICE to CheckMode.MANUAL),
        note = "Web kontrolü mesajlaşmanın veya aramaların çalıştığını kanıtlamaz."),
    SIGNAL("signal", "Signal", setOf("signal.org", "signal.me"), "https://signal.org/", false,
        packages = setOf("org.thoughtcrime.securesms"), probeHosts = listOf("chat.signal.org"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.NATIVE_APP to CheckMode.MANUAL, Capability.VOICE to CheckMode.MANUAL),
        note = "Ağ kontrolü Signal servis uç noktasının TLS/HTTP yanıtını ölçer; mesaj iletimi kullanıcı onayıyla doğrulanır.",
        probe = "https://chat.signal.org/"),
    TWITCH("twitch", "Twitch", setOf("twitch.tv"), "https://www.twitch.tv/", false,
        packages = setOf("tv.twitch.android.app"), probeHosts = listOf("www.twitch.tv"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL,
            Capability.MEDIA to CheckMode.MANUAL, Capability.NATIVE_APP to CheckMode.MANUAL)),
    SNAPCHAT("snapchat", "Snapchat", setOf("snapchat.com"), "https://www.snapchat.com/", false,
        packages = setOf("com.snapchat.android"), probeHosts = listOf("www.snapchat.com"),
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.NATIVE_APP to CheckMode.MANUAL)),
    /** Katalogda olmayan bir servis: kullanıcının verdiği herkese açık HTTPS alan adı. */
    CUSTOM("custom", "Özel alan adı", emptySet(), "", false,
        features = mapOf(Capability.NETWORK to CheckMode.AUTO, Capability.CONTENT to CheckMode.MANUAL),
        note = "Yalnız verdiğin alan adının ağ yolu ölçülür; ilgili uygulamanın başka sunucuları bilinmez.");

    fun owns(host: String): Boolean = hosts.any { host == it || host.endsWith(".$it") }
    /** Ağ yolu kontrolünün adresi; özel alan adında hedefin kendisi kullanılır. */
    val probeUrl: String get() = probe ?: homeUrl
    fun mode(capability: Capability): CheckMode = features[capability] ?: CheckMode.UNSUPPORTED

    companion object {
        /** Katalog veri sürümü; tanılama çıktısına yazılır. Girdi değişince artırılır. */
        const val CATALOG_VERSION = "2026-10-23.1"
        fun byId(id: String?): SocialPlatform? = entries.firstOrNull { it.id == id }
        fun supportsAutoObserver(id: String?): Boolean = byId(id)?.autoObserver == true
        /** Kullanıcıya seçim olarak sunulan katalog platformları (özel alan adı ayrıca). */
        val catalog: List<SocialPlatform> get() = entries.filter { it != CUSTOM }
    }
}

/** Kullanıcıya gösterilen metinler: iç kodlar (HTTP_200, INCONCLUSIVE…) ekrana ham çıkmaz. */
object SocialText {
    fun evidence(reason: String): String {
        val parts = reason.split(':')
        val route = when (parts.firstOrNull()) {
            "DIRECT_CONTROL" -> "Doğrudan bağlantı"
            "THROUGH_CANDIDATE" -> "Yöntem üzerinden"
            "PROBE_TIMEOUT" -> return "Bağlantı testi zaman aşımına uğradı"
            "BASELINE_TRANSPORT_UNKNOWN" -> return "Başlangıç ölçümü zaman aşımına uğradı"
            else -> return "Bağlantı testi sonucu belirsiz"
        }
        val http = parts.firstOrNull { it.startsWith("HTTP_") }?.removePrefix("HTTP_")?.toIntOrNull()
        return when {
            http != null && http in 200..399 -> "$route: sunucuya ulaşıldı (HTTP $http)"
            http == 429 -> "$route: hız sınırı (HTTP 429)"
            http != null && http in 400..499 -> "$route: sunucuya ulaşıldı, sunucu HTTP $http döndü (ağ yolu açık; içerik ayrı)"
            http != null -> "$route: sunucu yanıt verdi ama hata döndü (HTTP $http)"
            else -> "$route: " + failure(parts.lastOrNull().orEmpty())
        }
    }

    /** Ölçülen hata sınıfının okunur adı; kesin teşhis iddiası taşımaz. */
    fun failure(cls: String): String = when (cls) {
        "DNS_FAILURE" -> "alan adı çözülemedi"
        "DNS_NON_PUBLIC_ANSWER" -> "DNS yerel/özel bir adres döndürdü (DNS müdahalesi şüphesi)"
        "TLS_CERT_MISMATCH" -> "sertifika bu alan adına ait değil (araya giren sayfa ya da giriş portalı olabilir)"
        "TLS_CERT_UNTRUSTED" -> "sertifika güvenilir değil (giriş portalı, araya giren sayfa ya da yanlış cihaz saati olabilir; DPI kesintisi değil)"
        "TLS_HANDSHAKE_FAILED", "TLS_FAILURE" -> "TLS el sıkışması kesildi (SNI/DPI müdahalesi şüphesi)"
        "TCP_REFUSED" -> "bağlantı reddedildi"
        "TCP_RESET" -> "bağlantı sıfırlandı (DPI müdahalesi şüphesi)"
        "TCP_TIMEOUT" -> "bağlantı zaman aşımına uğradı (tek ölçümle kesin değil)"
        "READ_TIMEOUT" -> "yanıt zaman aşımına uğradı"
        "RATE_LIMITED" -> "hız sınırı"
        "SERVICE_ERROR" -> "sunucu hata döndü (kesinti olabilir)"
        "NO_VALIDATED_NETWORK" -> "doğrulanmış internet bağlantısı yok (ağ yok ya da giriş portalı)"
        "TRANSPORT_UNKNOWN" -> "bağlantı kurulamadı"
        else -> "sonuç belirsiz"
    }

    fun check(c: Check, mode: CheckMode): String = when {
        mode == CheckMode.UNSUPPORTED -> "ölçülmez"
        c.status == CheckStatus.PASS -> if (c.source == CheckSource.USER) "çalışıyor (onayın)" else "açık (otomatik)"
        c.status == CheckStatus.PARTIAL -> "kısmen (onayın)"
        c.status == CheckStatus.GATED -> "platform doğrulama istiyor (bildirimin)"
        c.status == CheckStatus.FAIL -> if (c.source == CheckSource.USER) "çalışmıyor (bildirimin)" else "kapalı (otomatik)"
        c.status == CheckStatus.MANUAL_REQUIRED || mode == CheckMode.MANUAL -> "onayın gerekli"
        else -> "ölçülmedi"
    }
    fun technical(outcome: String): String = when (outcome) {
        "PASS" -> "Ağ yolu açık"
        "FAIL" -> "Ağ yolu kapalı"
        else -> "Belirsiz"
    }
    fun verdict(v: Verdict): String = when (v) {
        Verdict.PASS -> "İçerik açıldı"
        Verdict.FAIL -> "Engel gözlendi"
        Verdict.INCONCLUSIVE -> "Belirsiz"
        Verdict.UNSUPPORTED -> "Otomatik gözlem yok"
        Verdict.NEEDS_INTERACTION -> "Dokunmanız gerekiyor"
        Verdict.CANCELLED -> "İptal edildi"
        Verdict.NOT_RUN -> "—"
    }
    fun phase(p: Phase): String = when (p) {
        Phase.DISABLED -> "Kapalı"
        Phase.IDLE -> "Hazır"
        Phase.BASELINING -> "Başlangıç ölçülüyor"
        Phase.SELECTING, Phase.PREPARING -> "Hazırlanıyor"
        Phase.APPLYING -> "Yöntem uygulanıyor"
        Phase.PROBING -> "Test ediliyor"
        Phase.AUTO_EVALUATING -> "Test penceresi gözleniyor"
        Phase.WAITING_FOR_USER -> "Kullanıcı kontrolü gerekiyor"
        Phase.CANCELLING, Phase.ROLLING_BACK -> "Geri alınıyor"
        Phase.PAUSED -> "Duraklatıldı"
        Phase.FINISHED -> "Tamamlandı"
        Phase.RECOVERY_REQUIRED -> "Kurtarma gerekli"
    }
    fun pause(reason: String?): String? = when (reason) {
        "BUDGET_LIMIT" -> "Bu turun deneme bütçesi doldu."
        "COOLDOWN" -> "Hedef hız sınırı bildirdi; bekleme süresi dolmadı."
        "USER_ACTION_REQUIRED" -> "Test penceresinde bir dokunuş gerekiyor."
        "PERMISSION_REQUIRED" -> "Bazı yöntemler için yerel DPI bağlantısı gerekiyor."
        "USER_UNABLE_TO_TEST" -> "Test edilemedi olarak işaretlendi."
        "USER_INACTIVE" -> "Yanıt gelmediği için deneme geri alındı."
        "CONTEXT_CHANGED" -> "Ağ değişti, yeniden kontrol gerekiyor; denemeyi yeniden başlatın."
        "NETWORK_UNAVAILABLE" -> "Doğrulanmış internet bağlantısı yok; ağ gelince yeniden başlatın."
        "KEEP_FAILED" -> "Çalışan yöntem kalıcılaştırılamadı; geri alındı ve başarı yazılmadı."
        else -> null
    }
}

/** Bir özelliğin ölçülen/bildirilen durumu. */
/** [GATED]: platform giriş/yaş/kimlik/bot doğrulaması istiyor (sunucu tarafı; ağ başarısızlığı değil). */
enum class CheckStatus { PASS, PARTIAL, FAIL, UNKNOWN, MANUAL_REQUIRED, GATED }
enum class CheckSource { NONE, AUTO, USER }
data class Check(val status: CheckStatus = CheckStatus.UNKNOWN, val source: CheckSource = CheckSource.NONE, val detail: String = "")

/**
 * Bir hedefin (platform ya da özel alan adı) son sonucu. Her hedef ayrı tutulur:
 * birinin başarısı diğerininki sayılmaz, başarısız biri çalışanları silmez.
 *  - [network]: teknik ağ yolu (otomatik ölçüm, yalnız TLS+HTTP yanıtı)
 *  - [content]: içerik/uygulama (test penceresi gözlemi ya da kullanıcı bildirimi)
 *  - [strategyId]: sonucun ölçüldüğü motor planı
 *  - [stale]: bağlam (ağ, DNS, kapsam, süreç) değişti; yeniden doğrulanmadan
 *    güncel başarı diye gösterilmez.
 *  - [contentGeneration]: içerik sonucunun alındığı bağlam kuşağı. Yeni bağlamdaki
 *    yalnız ağ ölçümü eski kullanıcı onayını GÜNCEL yapamaz ([contentCurrent]).
 */
data class TargetResult(val key: String, val platformId: String, val network: Check = Check(), val content: Check = Check(),
    val strategyId: String? = null, val contextGeneration: Long = 0, val verifiedAtMs: Long = 0, val stale: Boolean = false,
    val failureClass: String = "", val conflict: String = "", val contentGeneration: Long = -1) {
    /** İçerik sonucu bu bağlamda mı alındı? Önceki bağlamın onayı yalnız geçmiş bilgisidir. */
    val contentCurrent: Boolean get() = !stale && content.source != CheckSource.NONE && contentGeneration == contextGeneration
    val working: Boolean get() = !stale && network.status == CheckStatus.PASS && content.status != CheckStatus.FAIL &&
        content.status != CheckStatus.GATED && (content.source == CheckSource.NONE || contentCurrent)
}

/** Bir hedefin kullanıcıya gösterilen tek durumu; birbirine karışmaması için ayrı sınıflar. */
enum class TargetStatus { OUT_OF_SCOPE, NO_ENGINE, NO_NETWORK, NOT_CHECKED, VERIFYING, WORKING, NETWORK_OPEN_CONFIRM, PARTIAL, NOT_WORKING,
    NETWORK_BLOCKED, UNCERTAIN, PLATFORM_GATE }

object TargetStatusRules {
    /**
     * Öncelik: kapsam dışı > ağ yok > bayat (yeniden doğrulanmalı) > platform
     * doğrulaması > kullanıcı bildirimi (yalnız bu bağlamda alınmışsa) > otomatik ağ
     * ölçümü. Otomatik ağ ölçümü tek başına "çalışıyor" üretmez; içerik/uygulama için
     * kullanıcı onayı ayrı istenir. Hesap doğrulaması gözlendiyse ağ açık olsa bile
     * gizlenmez (bayat olsa bile — kullanıcı yeniden bildirene kadar).
     */
    fun of(result: TargetResult?, inScope: Boolean, engineConnected: Boolean, networkUsable: Boolean): TargetStatus = when {
        !inScope -> TargetStatus.OUT_OF_SCOPE
        !networkUsable -> TargetStatus.NO_NETWORK
        result == null -> if (engineConnected) TargetStatus.NOT_CHECKED else TargetStatus.NO_ENGINE
        result.content.status == CheckStatus.GATED -> TargetStatus.PLATFORM_GATE
        result.stale -> TargetStatus.VERIFYING
        !result.contentCurrent -> when (result.network.status) {
            CheckStatus.PASS -> TargetStatus.NETWORK_OPEN_CONFIRM
            CheckStatus.FAIL -> TargetStatus.NETWORK_BLOCKED
            else -> TargetStatus.UNCERTAIN
        }
        result.content.status == CheckStatus.FAIL && result.content.source == CheckSource.USER -> TargetStatus.NOT_WORKING
        result.content.status == CheckStatus.PARTIAL -> TargetStatus.PARTIAL
        result.network.status == CheckStatus.PASS && result.content.status == CheckStatus.PASS -> TargetStatus.WORKING
        result.network.status == CheckStatus.PASS -> TargetStatus.NETWORK_OPEN_CONFIRM
        result.network.status == CheckStatus.FAIL -> TargetStatus.NETWORK_BLOCKED
        else -> TargetStatus.UNCERTAIN
    }

    fun label(s: TargetStatus): String = when (s) {
        TargetStatus.OUT_OF_SCOPE -> "Kapsam dışı"
        TargetStatus.NO_ENGINE -> "Bağlantı kapalı"
        TargetStatus.NO_NETWORK -> "Ağ yok"
        TargetStatus.NOT_CHECKED -> "Kontrol edilmedi"
        TargetStatus.VERIFYING -> "Ağ değişti, yeniden kontrol gerekiyor"
        TargetStatus.WORKING -> "Çalışıyor"
        TargetStatus.NETWORK_OPEN_CONFIRM -> "Ağ açık · kullanıcı kontrolü gerekiyor"
        TargetStatus.PARTIAL -> "Kısmen çalışıyor"
        TargetStatus.PLATFORM_GATE -> "Platform doğrulama istiyor"
        TargetStatus.NOT_WORKING -> "Çalışmıyor"
        TargetStatus.NETWORK_BLOCKED -> "Ağ yolu kapalı"
        TargetStatus.UNCERTAIN -> "Belirsiz"
    }
}

/** Sonuçların sürümlü JSON biçimi. Bozuk/bilinmeyen kayıt yok sayılır, uygulama çökmez. */
object TargetResultCodec {
    const val SCHEMA = 1
    fun encode(results: Collection<TargetResult>, planStrategyId: String?): JSONObject = JSONObject().put("schemaVersion", SCHEMA)
        .put("catalogVersion", SocialPlatform.CATALOG_VERSION).put("planStrategyId", planStrategyId ?: "")
        .put("results", org.json.JSONArray(results.map { r -> JSONObject().put("key", r.key).put("platformId", r.platformId)
            .put("network", r.network.status.name).put("networkSource", r.network.source.name)
            .put("content", r.content.status.name).put("contentSource", r.content.source.name)
            .put("strategyId", r.strategyId ?: "").put("verifiedAtMs", r.verifiedAtMs).put("failureClass", r.failureClass) }))

    /** Süreç yeniden başladıktan sonra okunan her sonuç BAYATTIR: yeniden ölçülmeden güncel sayılmaz. */
    fun decode(raw: String?): Pair<Map<String, TargetResult>, String?> = runCatching {
        val j = JSONObject(raw ?: return emptyMap<String, TargetResult>() to null)
        if (j.optInt("schemaVersion") != SCHEMA) return emptyMap<String, TargetResult>() to null
        val array = j.optJSONArray("results") ?: org.json.JSONArray()
        val results = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val platform = o.optString("platformId")
            if (SocialPlatform.byId(platform) == null || o.optString("key").isBlank()) return@mapNotNull null
            fun status(k: String) = CheckStatus.entries.firstOrNull { it.name == o.optString(k) } ?: CheckStatus.UNKNOWN
            fun source(k: String) = CheckSource.entries.firstOrNull { it.name == o.optString(k) } ?: CheckSource.NONE
            TargetResult(o.optString("key"), platform, Check(status("network"), source("networkSource")), Check(status("content"), source("contentSource")),
                o.optString("strategyId").ifBlank { null }, 0, o.optLong("verifiedAtMs"), stale = true, failureClass = o.optString("failureClass"))
        }.associateBy { it.key }
        results to j.optString("planStrategyId").ifBlank { null }
    }.getOrDefault(emptyMap<String, TargetResult>() to null)
}

/**
 * Ağ yolu ölçümünün sınıflandırması. Yalnız ölçülebilen kanıta göre sınıf verir;
 * tek bir zaman aşımı kesin engel sayılmaz (UNKNOWN kalır).
 */
object ProbeClassifier {
    /** HTTP yanıtı, doğrulanmış TLS üzerinden alındı: ağ yolu açık, içerik başarısı DEĞİL. */
    fun fromHttp(route: String, code: Int, retryAfter: String?, nowMs: Long, bytes: Long): NetworkEvidence = when {
        code == 429 -> NetworkEvidence("UNKNOWN", bytes, reason = "$route:HTTP_429:RATE_LIMITED", retryAfterSeconds = retryAfterSeconds(retryAfter, nowMs),
            failureClass = "RATE_LIMITED", httpStatus = code)
        code == 503 && retryAfter != null -> NetworkEvidence("UNKNOWN", bytes, reason = "$route:HTTP_503:SERVICE_ERROR",
            retryAfterSeconds = retryAfterSeconds(retryAfter, nowMs), failureClass = "SERVICE_ERROR", httpStatus = code)
        code in 500..599 -> NetworkEvidence("UNKNOWN", bytes, reason = "$route:HTTP_$code:SERVICE_ERROR", failureClass = "SERVICE_ERROR", httpStatus = code)
        code in 200..499 -> NetworkEvidence("PASS", bytes, reason = "$route:HTTP_$code:NETWORK_PATH_ONLY", httpStatus = code)
        else -> NetworkEvidence("UNKNOWN", bytes, reason = "$route:HTTP_$code:UNEXPECTED", failureClass = "HTTP_UNEXPECTED", httpStatus = code)
    }

    fun fromException(route: String, e: Throwable): NetworkEvidence {
        val message = e.message.orEmpty()
        val (outcome, cls) = when (e) {
            is java.net.UnknownHostException -> "FAIL" to if ("PUBLIC_TARGET_REQUIRED" in message) "DNS_NON_PUBLIC_ANSWER" else "DNS_FAILURE"
            is javax.net.ssl.SSLPeerUnverifiedException -> "FAIL" to "TLS_CERT_MISMATCH"
            // Güvenilmeyen sertifika zinciri (giriş portalı, araya giren sayfa, yanlış
            // cihaz saati) SNI/DPI kesintisinden AYRI sınıftır: aynı istisna türüyle gelir.
            is javax.net.ssl.SSLHandshakeException -> "FAIL" to if (certificateProblem(e)) "TLS_CERT_UNTRUSTED" else "TLS_HANDSHAKE_FAILED"
            is javax.net.ssl.SSLException -> "FAIL" to "TLS_FAILURE"
            is java.net.ConnectException -> "FAIL" to "TCP_REFUSED"
            is java.net.SocketTimeoutException -> "UNKNOWN" to if ("connect" in message.lowercase()) "TCP_TIMEOUT" else "READ_TIMEOUT"
            is java.net.SocketException -> if ("reset" in message.lowercase()) "FAIL" to "TCP_RESET" else "UNKNOWN" to "TRANSPORT_UNKNOWN"
            else -> "UNKNOWN" to "TRANSPORT_UNKNOWN"
        }
        return NetworkEvidence(outcome, bytes = 0, reason = "$route:$cls", failureClass = cls)
    }

    private fun certificateProblem(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException }

    /** `Retry-After`: saniye ya da HTTP-date. Geçersizse 60 sn; 1 sn..24 sa aralığına sıkıştırılır. */
    fun retryAfterSeconds(value: String?, nowMs: Long): Long {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return 60
        v.toLongOrNull()?.let { return it.coerceIn(1, 86_400) }
        return runCatching {
            val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((at - nowMs + 999) / 1000).coerceIn(1, 86_400)
        }.getOrDefault(60)
    }
}

data class Observation(val schemaVersion: Int = 2, val sessionId: String = "", val attemptId: String = "",
    val operationId: String = "", val contextGeneration: Long = 0, val modeGeneration: Long = 0,
    val documentGeneration: Long = 0, val goalHash: String = "", val targetRef: String = "",
    val observerVersion: String = "", val evaluatorVersion: String = "", val actualSurface: String = "UNKNOWN",
    val identityContext: String = "UNKNOWN", val identityEvidence: String = "UNKNOWN", val routeProvenance: String = "UNVERIFIED",
    val targetMatched: Tri = Tri.UNKNOWN, val contentPresent: Tri = Tri.UNKNOWN, val blockingGate: Tri = Tri.UNKNOWN,
    val mediaProgress: Tri = Tri.UNKNOWN, val freshEvidence: Tri = Tri.UNKNOWN, val documentVisible: Tri = Tri.UNKNOWN,
    val observedCapabilities: Set<String> = emptySet(), val stableSamples: Int = 0, val stableDurationMs: Long = 0,
    val mediaProgressSeconds: Double = 0.0, val seeking: Boolean = false, val advertisement: Boolean = false,
    val preview: Boolean = false, val observerError: Boolean = false, val needsInteraction: Boolean = false,
    val blockingClass: String = "UNKNOWN") {
    companion object {
        fun fromJson(j: JSONObject): Observation {
            val allowed = setOf("schemaVersion", "sessionId", "attemptId", "operationId", "contextGeneration", "modeGeneration", "documentGeneration",
                "goalHash", "targetRef", "observerId", "observerVersion", "evaluatorVersion", "actualSurface", "identityContext", "identityEvidence", "routeProvenance",
                "targetMatched", "contentPresent", "blockingGate", "mediaProgress", "freshEvidence", "documentVisible", "observedCapabilities", "stableSamples",
                "stableDurationMs", "mediaProgressSeconds", "seeking", "advertisement", "preview", "observerError", "needsInteraction", "blockingClass",
                "tabId", "signalSource", "observedAtMonotonic", "observationWindow", "budgetAccuracy", "cacheUncertain", "rendererLoadCount", "autoVerdict", "reasonCodes")
            if (j.keys().asSequence().any { it !in allowed } || j.opt("schemaVersion") !is Int && j.opt("schemaVersion") !is Long)
                return Observation(observerError = true)
            val booleanFields = setOf("seeking", "advertisement", "preview", "observerError", "needsInteraction", "cacheUncertain")
            if (booleanFields.any { j.has(it) && j.opt(it) !is Boolean } ||
                j.optString("identityEvidence", "UNKNOWN") !in setOf("UNKNOWN", "USER_BOUND_SESSION", "PLATFORM_SIGNAL") ||
                j.optString("observerId", "youtube-dom") !in setOf("youtube-dom", "controlled-web-v1") ||
                j.opt("stableSamples") !is Int && j.opt("stableSamples") !is Long) return Observation(observerError = true)
            for (key in setOf("observedAtMonotonic", "observationWindow", "mediaProgressSeconds", "stableDurationMs", "rendererLoadCount")) {
                if (j.has(key)) {
                    val number = j.opt(key) as? Number ?: return Observation(observerError = true)
                    if (!number.toDouble().isFinite() || number.toDouble() < 0) return Observation(observerError = true)
                }
            }
            if (j.optBoolean("cacheUncertain", false)) return Observation(observerError = true)
            fun tri(key: String) = Tri.entries.firstOrNull { it.name == j.optString(key) } ?: Tri.UNKNOWN
            return Observation(j.optInt("schemaVersion", -1), j.optString("sessionId"), j.optString("attemptId"), j.optString("operationId"),
                j.optLong("contextGeneration"), j.optLong("modeGeneration"), j.optLong("documentGeneration"), j.optString("goalHash"), j.optString("targetRef"),
                j.optString("observerVersion"), j.optString("evaluatorVersion"), j.optString("actualSurface", "UNKNOWN"), j.optString("identityContext", "UNKNOWN"),
                j.optString("identityEvidence", "UNKNOWN"), j.optString("routeProvenance", "UNVERIFIED"), tri("targetMatched"), tri("contentPresent"), tri("blockingGate"),
                tri("mediaProgress"), tri("freshEvidence"), tri("documentVisible"), j.strings("observedCapabilities"), j.optInt("stableSamples"), j.optLong("stableDurationMs"),
                j.optDouble("mediaProgressSeconds", 0.0), j.optBoolean("seeking"), j.optBoolean("advertisement"), j.optBoolean("preview"), j.optBoolean("observerError"), j.optBoolean("needsInteraction"),
                blockingClass(j.optString("blockingClass", "UNKNOWN")))
        }

        /** Gözlemcinin tanıyabildiği sunucu/hesap sınıfları; bilinmeyen değer UNKNOWN kalır (başarıya ya da başka sınıfa dönüşmez). */
        val KnownBlockingClasses = setOf("AGE_OR_ID_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "BOT_CHALLENGE", "ACCOUNT_RESTRICTION")
        fun blockingClass(raw: String?): String = raw?.takeIf { it in KnownBlockingClasses } ?: "UNKNOWN"
    }
}

object GoalEvaluator {
    const val VERSION = "1.0.0"
    fun evaluate(g: Goal, o: Observation): Verdict {
        if (g.platformId !in setOf("youtube", "instagram") || g.identityContext !in setOf("ANONYMOUS", "SAME_ACCOUNT") ||
            g.requiredCapabilities.isEmpty() || !setOf("VIEW_SELECTED_CONTENT", "PLAY_MEDIA", "VIEW_ACCOUNT_FEED").containsAll(g.requiredCapabilities) ||
            o.actualSurface !in setOf("OFFICIAL_WEB", "OFFICIAL_NATIVE_APP", "CONTROLLED_WEB_VIEW", "LOCAL_ALTERNATIVE_CLIENT") || !o.mediaProgressSeconds.isFinite()) return Verdict.INCONCLUSIVE
        if (o.schemaVersion != 2 || o.observerVersion != VERSION || o.evaluatorVersion != VERSION || o.observerError) return Verdict.INCONCLUSIVE
        if (o.identityEvidence !in setOf("UNKNOWN", "USER_BOUND_SESSION", "PLATFORM_SIGNAL")) return Verdict.INCONCLUSIVE
        if (o.targetMatched != Tri.TRUE || o.actualSurface !in g.allowedSurfaces || o.documentVisible != Tri.TRUE) return Verdict.INCONCLUSIVE
        if (o.identityContext != g.identityContext || (g.identityContext == "SAME_ACCOUNT" && o.identityEvidence !in setOf("USER_BOUND_SESSION", "PLATFORM_SIGNAL"))) return Verdict.INCONCLUSIVE
        if (o.blockingGate == Tri.TRUE) return Verdict.FAIL
        if (o.needsInteraction) return Verdict.NEEDS_INTERACTION
        if (o.routeProvenance != "EXACT_TARGET_PATH" || o.freshEvidence != Tri.TRUE || o.blockingGate != Tri.FALSE || o.contentPresent != Tri.TRUE) return Verdict.INCONCLUSIVE
        if (o.stableSamples < 2 || o.stableDurationMs < 2000 || o.advertisement || o.preview || o.seeking || !o.observedCapabilities.containsAll(g.requiredCapabilities)) return Verdict.INCONCLUSIVE
        if (g.problemClass == "POSSIBLE_THROTTLING") return Verdict.INCONCLUSIVE
        if (("PLAY_MEDIA" in g.requiredCapabilities || g.problemClass == "MEDIA_PATH_FAILURE") && (o.mediaProgress != Tri.TRUE || o.mediaProgressSeconds < 3.0)) return Verdict.INCONCLUSIVE
        return Verdict.PASS
    }
}

internal fun JSONObject.strings(key: String): Set<String> {
    val a = optJSONArray(key) ?: return emptySet()
    return (0 until a.length()).map { a.optString(it) }.toSet()
}

data class CandidateDefinition(val schemaVersion: Int = 2, val id: String, val family: String,
    val displayName: String, val strategyId: String? = null, val surface: String = "OFFICIAL_WEB",
    val effectKey: String = "$family:$strategyId:$surface", val candidateVersion: String = "1.0.0",
    val rollbackKind: String = "RELEASE_OWNED_RESOURCES_ONLY")

/**
 * Motordaki onaylı plan. [installed] motorun bu planla gerçekten çalıştığını
 * (servisin bildirdiği), [operationId] planı kuran işlemi gösterir; geri alma
 * yalnızca bu işlem için yapılabilir.
 */
data class PlanState(val strategyId: String? = null, val operationId: String = "", val installed: Boolean = false,
    val source: String = "NONE")

data class Feedback(val sessionId: String, val attemptId: String, val operationId: String, val contextGeneration: Long,
    val modeGeneration: Long, val eventId: String, val expectedStateVersion: Long, val outcome: String)

data class TrialState(val phase: Phase = Phase.DISABLED, val mode: TestMode = TestMode.MANUAL, val sessionId: String = "",
    val attemptId: String = "", val operationId: String = "", val contextGeneration: Long = 1, val modeGeneration: Long = 1,
    val documentGeneration: Long = 1, val version: Long = 0, val candidate: CandidateDefinition? = null,
    val finishReason: String? = null, val pauseReason: String? = null, val resourceDisposition: String = "NONE",
    val message: String = "", val technicalOutcome: String = "UNKNOWN", val autoVerdict: Verdict = Verdict.NOT_RUN,
    val userOutcome: String = "NOT_TESTED", val deferred: List<String> = emptyList(), val attempted: Int = 0,
    val requests: Int = 0, val diagnosticBytes: Long = 0, val rendererLoads: Int = 0,
    val causalAttribution: String = "NOT_ESTABLISHED", val goal: Goal? = null, val target: Target? = null,
    /** Bu turda birlikte değerlendirilen hedefler; ilki [target] (odak). */
    val targets: List<Target> = emptyList(),
    /** Hedef başına son sonuçlar (tur dışında da yaşar). */
    val results: Map<String, TargetResult> = emptyMap(),
    /** Odak hedefin aday uygulanmadan önceki doğrudan ağ yolu sonucu. */
    val baselineOutcome: String = "UNKNOWN",
    /** Sosyal Erişim'in motorda kalıcılaştırdığı (onaylanan) plan. */
    val plan: PlanState = PlanState()) {
    fun feedback(outcome: String) = Feedback(sessionId, attemptId, operationId, contextGeneration, modeGeneration, UUID.randomUUID().toString(), version, outcome)
}
