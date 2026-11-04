package net.atom.dpibypass.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

/**
 * Gizlilik Yardımcısı bağlantılarının tek kapısı.
 *
 * Yalnızca katalogdaki resmî alan adlarına, yalnızca https ile, kullanıcı bilgisi
 * (`user@`), port ya da boşluk içermeyen adresler açılır. Açılış düz bir
 * `ACTION_VIEW` + `CATEGORY_BROWSABLE` niyetidir: ek (extra), bileşen, izin ya da
 * oturum bilgisi taşımaz. Android adresi tarayıcıda ya da (App Links ile) resmî
 * uygulamada açar. Adres hiçbir yere kaydedilmez veya loglanmaz.
 */
object PrivacyLinks {
    /** Resmî kaynak ve ayar sayfalarının alan adları — tam eşleşme, alt alan adı serbest değil. */
    val allowedHosts: Set<String> = setOf(
        "www.resmigazete.gov.tr",
        "www.turkiye.gov.tr",
        "siberguvenlik.gov.tr",
        "help.instagram.com",
        "www.instagram.com",
        "www.facebook.com",
        "www.tiktok.com",
        "support.tiktok.com",
        "support.google.com",
        "myaccount.google.com",
        "help.x.com",
        "x.com",
    )

    fun isAllowed(url: String): Boolean {
        if (url.isEmpty() || url.length > 512) return false
        if (url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.isAbsolute || uri.isOpaque) return false
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        if (uri.rawUserInfo != null || uri.port != -1) return false
        // Otorite tam olarak alan adı olmalı (gizli "@", port ya da büyük/küçük harf hilesi yok).
        if (uri.rawAuthority != host || host != host.lowercase()) return false
        return host in allowedHosts
    }

    /** İzinli adres için güvenli görüntüleme niyeti; değilse null. */
    fun viewIntent(url: String): Intent? {
        if (!isAllowed(url)) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
    }

    /** Açabildiyse true. Açamazsa (izinli değil ya da uygun uygulama yok) false; ekran kullanıcıya söyler. */
    fun open(context: Context, url: String): Boolean {
        val intent = viewIntent(url) ?: return false
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
