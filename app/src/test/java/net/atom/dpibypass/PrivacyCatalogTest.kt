package net.atom.dpibypass

import java.io.File
import net.atom.dpibypass.privacy.AccountKind
import net.atom.dpibypass.privacy.Claim
import net.atom.dpibypass.privacy.Evidence
import net.atom.dpibypass.privacy.PRIVACY_CHECKED_ON
import net.atom.dpibypass.privacy.PlatformId
import net.atom.dpibypass.privacy.PrivacyCatalog
import net.atom.dpibypass.privacy.PrivacyLinks
import net.atom.dpibypass.privacy.PrivacySources
import net.atom.dpibypass.privacy.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gizlilik Yardımcısı kataloğu: platform durumları, kaynaklar ve bağlantı güvenliği (saf JVM). */
class PrivacyCatalogTest {

    private fun allClaims(): List<Claim> = buildList {
        addAll(PrivacyCatalog.regulation); addAll(PrivacyCatalog.tokenFlow); addAll(PrivacyCatalog.minimalDataTips)
        PrivacyCatalog.platforms.forEach { p ->
            p.rows.forEach { add(it.required); add(it.observed) }
            addAll(p.dataFlow); addAll(p.appeal); add(p.nonEdevletNote); p.nonEdevletOption?.let(::add)
            p.settings.forEach { add(it.evidence) }
        }
    }

    @Test fun coversTheFiveRequestedPlatforms() {
        assertEquals(
            listOf(PlatformId.INSTAGRAM, PlatformId.TIKTOK, PlatformId.YOUTUBE, PlatformId.FACEBOOK, PlatformId.X),
            PrivacyCatalog.platforms.map { it.id },
        )
    }

    /** Yeni/mevcut hesap × web/uygulama her platformda ayrı satır; yönetmelik ile platform gözlemi ayrı iddia. */
    @Test fun everyPlatformHasSeparateNewExistingWebAppRows() {
        PrivacyCatalog.platforms.forEach { p ->
            val cells = p.rows.map { it.account to it.surface }.toSet()
            val expected = AccountKind.entries.flatMap { a -> Surface.entries.map { a to it } }.toSet()
            assertEquals("${p.id}", expected, cells)
            assertEquals("${p.id} yinelenen satır", 4, p.rows.size)
            p.rows.forEach { r ->
                assertNotSame(r.required, r.observed)
                assertTrue("${p.id}: yönetmelik satırı Resmî Gazete'ye dayanmalı", PrivacySources.REGULATION in r.required.sources)
                assertEquals(Evidence.VERIFIED, r.required.evidence)
            }
            p.rows.filter { it.account == AccountKind.EXISTING }.forEach {
                assertTrue(it.required.text.contains("31 Mart 2027"))
            }
        }
    }

    /** Bugün hiçbir platformun Türkiye belirteç adımı resmî kaynakta yok: durum "Doğrulanamadı" olmalı, uydurulmamalı. */
    @Test fun observedPlatformStatusIsUnverifiedWhenNoEvidence() {
        PrivacyCatalog.platforms.flatMap { it.rows }.forEach { r ->
            assertEquals(Evidence.UNVERIFIED, r.observed.evidence)
            assertEquals("Doğrulanamadı", r.observed.evidence.label)
            assertTrue(r.observed.sources.isNotEmpty())
        }
    }

    @Test fun everyClaimHasASourceAndCheckDate() {
        allClaims().forEach { c ->
            assertTrue("kaynaksız: ${c.text}", c.sources.isNotEmpty())
            c.sources.forEach { assertEquals(PRIVACY_CHECKED_ON, it.checkedOn) }
        }
        PrivacyCatalog.platforms.forEach { p -> assertTrue(p.helpLinks.isNotEmpty()) }
    }

    /** e-Devlet'siz seçenek yalnız kaynakla doğrulanmışsa gösterilir; aksi hâlde açıklama "Doğrulanamadı" der. */
    @Test fun nonEdevletOptionShownOnlyWhenVerified() {
        PrivacyCatalog.platforms.forEach { p ->
            p.nonEdevletOption?.let { assertEquals(Evidence.VERIFIED, it.evidence) }
            if (p.nonEdevletOption == null) assertEquals(Evidence.UNVERIFIED, p.nonEdevletNote.evidence)
        }
    }

    /** Her platformda yönetmeliğin itiraz hakkı ve platformun kendi itiraz/düzeltme yolu var. */
    @Test fun everyPlatformHasAnAppealRoute() {
        assertTrue(PrivacyCatalog.regulation.any { it.text.startsWith("İtiraz") && "Md. 10" in it.text })
        PrivacyCatalog.platforms.forEach { p ->
            assertTrue("${p.id}", p.appeal.any { it.evidence != Evidence.UNVERIFIED })
        }
    }

    /** Yaş/erişim kontrollerini korur: katalog VPN, proxy, yurt dışı çıkış ya da atlatma önermez. */
    @Test fun catalogNeverSuggestsBypass() {
        val forbidden = listOf("vpn", "proxy", "yurt dışı", "atlat", "sosyal erişim", "sahte", "başkasının belirteci")
        allClaims().forEach { c ->
            val t = c.text.lowercase()
            forbidden.forEach { assertFalse("'$it' içeriyor: ${c.text}", t.contains(it)) }
        }
    }

    @Test fun everyCatalogUrlIsOnTheAllowList() {
        val urls = PrivacyCatalog.allUrls()
        assertTrue(urls.size > 15)
        urls.forEach { assertTrue("izinli değil: $it", PrivacyLinks.isAllowed(it)) }
        PrivacyCatalog.platforms.flatMap { it.settings }.mapNotNull { it.webUrl }.forEach { assertTrue(PrivacyLinks.isAllowed(it)) }
    }

    @Test fun linkGuardRejectsUnsafeOrForeignAddresses() {
        listOf(
            "http://help.instagram.com/966909308115586",
            "https://help.instagram.com.evil.example/",
            "https://evil.example/@help.instagram.com",
            "https://user@help.instagram.com/",
            "https://help.instagram.com:8443/",
            "https://HELP.INSTAGRAM.COM/",
            "https://help.instagram.com /x",
            "https://help.instagram.com\\@evil.example/",
            "javascript:alert(1)",
            "intent://help.instagram.com#Intent;scheme=https;end",
            "file:///sdcard/x.html",
            "content://net.atom.dpibypass/x",
            "https://edevlet-yas-dogrulama.example/",
            "https://turkiye.gov.tr.example/",
            "//help.instagram.com/x",
            "",
        ).forEach { assertFalse("kabul edildi: $it", PrivacyLinks.isAllowed(it)) }
        assertTrue(PrivacyLinks.isAllowed("https://www.turkiye.gov.tr/"))
    }

    /**
     * Yerel çalışma ve veri toplamama: yardımcının kaynak dosyaları log, kalıcı depolama,
     * metin girişi, ağ istemcisi ya da VPN servisi kullanmaz.
     */
    @Test fun helperSourcesCollectAndLogNothing() {
        val roots = listOf("src/main/java/net/atom/dpibypass/privacy", "src/main/java/net/atom/dpibypass/ui/privacy")
        val files = roots.flatMap { r -> File(r).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("kaynaklar bulunamadı (çalışma dizini: ${File(".").absolutePath})", files.size >= 3)
        val forbidden = listOf(
            "Log.", "println(", "printStackTrace", "SharedPreferences", "DataStore", "dataStore", "openFileOutput",
            "TextField", "okhttp", "HttpURLConnection", "URL(", "VpnService", "ServiceController", "putExtra", "WebView",
        )
        files.forEach { f ->
            val src = f.readText()
            forbidden.forEach { assertFalse("${f.name} '$it' kullanıyor", src.contains(it)) }
        }
    }
}
