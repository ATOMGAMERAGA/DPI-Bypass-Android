package net.atom.dpibypass.ui.social

import net.atom.dpibypass.social.AccessAction
import net.atom.dpibypass.social.AccountContext
import net.atom.dpibypass.social.GateCause
import net.atom.dpibypass.social.RouteOutcome
import net.atom.dpibypass.social.SocialPlatform
import net.atom.dpibypass.social.Target
import java.net.URI

/**
 * Rehberin kullanıcıya gösterdiği metinler. Temel akışta teknik terim (SOCKS,
 * TUN, profil kimliği, kuşak…) yoktur; bunlar yalnız "Ayrıntılar"da görünür.
 * Türkçe ekler, platform adının okunuşuna göre önceden yazılmıştır.
 */
object GuideText {
    private data class Name(val short: String, val acc: String, val loc: String)

    private val names = mapOf(
        "youtube" to Name("YouTube", "YouTube'u", "YouTube'da"),
        "instagram" to Name("Instagram", "Instagram'ı", "Instagram'da"),
        "x" to Name("X", "X'i", "X'te"),
        "tiktok" to Name("TikTok", "TikTok'u", "TikTok'ta"),
        "discord" to Name("Discord", "Discord'u", "Discord'da"),
        "reddit" to Name("Reddit", "Reddit'i", "Reddit'te"),
        "facebook" to Name("Facebook", "Facebook'u", "Facebook'ta"),
        "telegram" to Name("Telegram", "Telegram'ı", "Telegram'da"),
        "whatsapp" to Name("WhatsApp", "WhatsApp'ı", "WhatsApp'ta"),
        "signal" to Name("Signal", "Signal'i", "Signal'de"),
        "twitch" to Name("Twitch", "Twitch'i", "Twitch'te"),
        "snapchat" to Name("Snapchat", "Snapchat'i", "Snapchat'te"),
    )

    private fun host(url: String?): String? = url?.let { runCatching { URI(it).host }.getOrNull() }

    fun name(platformId: String?, url: String? = null): String =
        names[platformId]?.short ?: host(url) ?: SocialPlatform.byId(platformId)?.displayName ?: "Uygulama"

    /** "Instagram'ı", custom: "example.com sitesini". */
    fun acc(platformId: String?, url: String? = null): String =
        names[platformId]?.acc ?: host(url)?.let { "$it sitesini" } ?: "uygulamayı"

    /** "Instagram'da", custom: "example.com sitesinde". */
    fun loc(platformId: String?, url: String? = null): String =
        names[platformId]?.loc ?: host(url)?.let { "$it sitesinde" } ?: "uygulamada"

    fun actionChoice(a: AccessAction): String = when (a) {
        AccessAction.VIEW_CONTENT -> "Gönderi ya da içerik görmek"
        AccessAction.PLAY_MEDIA -> "Video izlemek"
        AccessAction.ACCOUNT_ACCESS -> "Hesabımı açmak (akış, profil)"
        AccessAction.MESSAGING -> "Mesajlaşmak"
        AccessAction.POSTING -> "Paylaşım yapmak"
        AccessAction.VOICE_CALL -> "Sesli görüşme yapmak"
    }

    /** Sonuç cümlesindeki işlem: "Instagram'da video çalıştı." */
    fun actionNoun(a: AccessAction?): String = when (a) {
        AccessAction.VIEW_CONTENT -> "içerik"
        AccessAction.PLAY_MEDIA -> "video"
        AccessAction.ACCOUNT_ACCESS -> "hesabın"
        AccessAction.MESSAGING -> "mesajlaşma"
        AccessAction.POSTING -> "paylaşım"
        AccessAction.VOICE_CALL -> "sesli görüşme"
        null -> "uygulama"
    }

    /** Dış testte yapılacak tek görev (en fazla iki cümle). Gönderme/arama kullanıcının kendi eylemidir. */
    fun task(a: AccessAction?, platformId: String?, url: String?, specificContent: Boolean, restart: Boolean): String {
        val open = if (restart) "${acc(platformId, url).replaceFirstChar { it.uppercase() }} tamamen kapatıp yeniden aç" else "${acc(platformId, url).replaceFirstChar { it.uppercase() }} aç"
        return when (a) {
            AccessAction.VIEW_CONTENT -> if (specificContent) "$open; seçtiğin içerik açılıyor mu bak. Sonra buraya dön."
                else "$open ve ${if (restart) "daha önce açmadığın" else "akışındaki"} yeni bir gönderiyi aç. Sonra buraya dön."
            AccessAction.PLAY_MEDIA -> "$open ve ${if (restart) "yeni " else ""}bir videoyu oynat; birkaç saniye gerçekten ilerlesin. Sonra buraya dön."
            AccessAction.ACCOUNT_ACCESS -> "$open; kendi profilin ve akışın yükleniyor mu bak. Sonra buraya dön."
            AccessAction.MESSAGING -> "$open ve istersen birine kendin kısa bir mesaj gönder. Sonra buraya dön."
            AccessAction.POSTING -> "$open ve paylaşım ekranını dene; paylaşıp paylaşmamak senin kararın. Sonra buraya dön."
            AccessAction.VOICE_CALL -> "$open ve istersen kendin kısa bir sesli görüşme başlat. Sonra buraya dön."
            null -> "$open ve normalde yaptığın şeyi dene. Sonra buraya dön."
        }
    }

    fun outcome(o: RouteOutcome): String = when (o) {
        RouteOutcome.WORKS -> "Çalıştı"
        RouteOutcome.PARTIAL -> "Bir kısmı çalıştı"
        RouteOutcome.NOT_WORKING -> "Çalışmadı"
        RouteOutcome.VERIFICATION_REQUIRED -> "Doğrulama istedi"
        RouteOutcome.UNSURE -> "Test edemedim / Emin değilim"
    }

    fun account(a: AccountContext): String = when (a) {
        AccountContext.SAME_ACCOUNT_CONFIRMED -> "Evet, kendi hesabımla"
        AccountContext.LOGGED_OUT -> "Hayır, oturum açık değildi"
        AccountContext.UNKNOWN -> "Emin değilim"
    }

    /** Doğrulama ekranının yorumu: ölçüm tek bir kontrol adresidir, kesin kök neden değildir. */
    fun gate(cause: GateCause?): String = when (cause) {
        GateCause.CHECK_ADDRESS_REACHABLE -> "O sırada platformun kontrol adresine ulaşılabiliyordu; istek büyük olasılıkla platformun kendisinden geliyor. Yerel bağlantı ayarı bunu değiştiremez."
        GateCause.NETWORK_PROBLEM_SUSPECTED -> "Aynı anda bir bağlantı hatası ölçüldü; ekran ağ sorunundan da gelebilir. Önce bağlantı yöntemlerini denemek işe yarayabilir."
        GateCause.NETWORK_UNCERTAIN -> "O anki ağ ölçümü sonuçsuz kaldı; sebebin ağ mı platform mu olduğu belli değil."
        GateCause.NETWORK_NOT_MEASURED, null -> "O anda ağ ölçülemedi; sebebin ağ mı platform mu olduğu belli değil."
    }

    fun launchError(code: String?): String? = when (code) {
        null -> null
        "NOT_INSTALLED" -> "Uygulama bu telefonda kurulu değil. Kendiliğinden bir şey indirmiyoruz."
        "NO_BROWSER" -> "Seçilen tarayıcı bulunamadı. Başka bir tarayıcı seç."
        "CONTEXT_UNKNOWN" -> "Ağ bilgisi henüz okunuyor. Birkaç saniye sonra tekrar dene."
        "TARGET_INVALID" -> "Hedef bağlantı geçerli değil. Bir önceki adımda düzeltebilirsin."
        "NOT_STARTED" -> null
        else -> "Açılamadı. Tekrar dene."
    }

    /** "https://www.instagram.com/p/…" yerine kısa gösterim. */
    fun shortTarget(t: Target?): String? = t?.takeIf { it.contentId != Target.HOME_CONTENT }?.url?.removePrefix("https://")?.take(60)
}
