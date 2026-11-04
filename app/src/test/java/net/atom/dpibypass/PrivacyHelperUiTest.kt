package net.atom.dpibypass

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.privacy.AccountKind
import net.atom.dpibypass.privacy.PlatformId
import net.atom.dpibypass.privacy.PrivacyCatalog
import net.atom.dpibypass.privacy.PrivacyLinks
import net.atom.dpibypass.privacy.Surface
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.nav.Dest
import net.atom.dpibypass.ui.nav.PrivacyHelperRoute
import net.atom.dpibypass.ui.nav.showsDock
import net.atom.dpibypass.ui.nav.tabRouteFor
import net.atom.dpibypass.ui.privacy.PrivacyHelperScreen
import net.atom.dpibypass.ui.settings.SettingsScreen
import net.atom.dpibypass.ui.theme.DpiBypassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Gizlilik Yardımcısı'nın gerçek Compose arayüzü (Robolectric, JVM): Ayarlar düğmesi,
 * platform durumları ve bağlantıların güvenli açılması. Gerçek cihazda tarayıcı/uygulama
 * açılışının yerine geçmez.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class PrivacyHelperUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        while (shadowOf(app).nextStartedActivity != null) Unit
    }

    private fun showHelper(openLink: ((String) -> Boolean)? = null, onBack: () -> Unit = {}) {
        compose.setContent { DpiBypassTheme(ThemePref.System) { PrivacyHelperScreen(onBack = onBack, openLink = openLink) } }
        compose.waitForIdle()
    }

    @Test fun settingsHasOneButtonThatOpensTheHelper() {
        var opened = 0
        val vm = AppViewModel(app)
        compose.setContent { DpiBypassTheme(ThemePref.System) { SettingsScreen(vm, onOpenPrivacy = { opened++ }) } }
        compose.onNodeWithTag("settings-privacy-helper").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gizlilik Yardımcısı").assertIsDisplayed()
        compose.onNodeWithTag("settings-privacy-helper").performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        // Alt sayfa Ayarlar sekmesine aittir ve gezinme çubuğu görünür kalır.
        assertEquals(Dest.Settings.route, tabRouteFor(PrivacyHelperRoute))
        assertTrue(showsDock(PrivacyHelperRoute))
    }

    @Test fun overviewListsPlatformsAndStatesLocalNoVpnBehaviour() {
        showHelper()
        compose.onNodeWithTag("privacy-intro").assertIsDisplayed()
        compose.onNodeWithText("VPN veya proxy kullanmaz", substring = true).assertExists()
        PlatformId.entries.forEach { compose.onNodeWithTag("privacy-platform-${it.name}").performScrollTo().assertIsDisplayed() }
        assertTrue(compose.onAllNodesWithText("Türkiye belirteç adımı: Doğrulanamadı", substring = true).fetchSemanticsNodes().size == PlatformId.entries.size)
    }

    @Test fun platformDetailSeparatesRegulationFromObservedPerAccountAndSurface() {
        showHelper()
        PlatformId.entries.forEach { id ->
            compose.onNodeWithTag("privacy-platform-${id.name}").performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("Gizlilik · ${id.label}").fetchSemanticsNodes().isNotEmpty())
            AccountKind.entries.forEach { a ->
                Surface.entries.forEach { s ->
                    compose.onNodeWithTag("privacy-row-${id.name}-${a.name}-${s.name}").performScrollTo().assertIsDisplayed()
                }
            }
            assertEquals(4, compose.onAllNodesWithText("Yönetmeliğin istediği").fetchSemanticsNodes().size)
            assertEquals(4, compose.onAllNodesWithText("Platformda fiilen görülen").fetchSemanticsNodes().size)
            compose.onNodeWithTag("privacy-dataflow").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("privacy-nonedevlet").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("privacy-appeal").performScrollTo().assertIsDisplayed()
            PrivacyCatalog.platform(id).settings.indices.forEach { i ->
                compose.onNodeWithTag("privacy-settings-${id.name}-$i").performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("privacy-back").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("privacy-intro").assertExists()
        }
    }

    /** Varsayılan açıcı: yalnız ACTION_VIEW + BROWSABLE, https resmî adres, ek veri yok. */
    @Test fun webSettingsLinkOpensSafeViewIntent() {
        showHelper()
        compose.onNodeWithTag("privacy-platform-INSTAGRAM").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-web-settings-INSTAGRAM-0").performScrollTo().performClick()
        compose.waitForIdle()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("bir etkinlik başlatılmalıydı", started)
        val expected = PrivacyCatalog.instagram.settings.first().webUrl
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(expected, started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(started.extras)
        assertNull(started.component)
        compose.onNodeWithTag("privacy-link-error").assertDoesNotExist()
    }

    @Test fun helpLinksAreOpenedThroughTheGuard() {
        val opened = mutableListOf<String>()
        showHelper(openLink = { opened += it; PrivacyLinks.isAllowed(it) })
        PlatformId.entries.forEach { id ->
            compose.onNodeWithTag("privacy-platform-${id.name}").performScrollTo().performClick()
            compose.onNodeWithTag("privacy-help-${id.name}-0").performScrollTo().performClick()
            compose.onNodeWithTag("privacy-back").performClick()
            compose.waitForIdle()
        }
        assertEquals(PlatformId.entries.map { PrivacyCatalog.platform(it).helpLinks.first().url }, opened)
        compose.onNodeWithTag("privacy-link-error").assertDoesNotExist()
    }

    @Test fun failedOpenShowsMessageInsteadOfSilentlySucceeding() {
        showHelper(openLink = { false })
        compose.onNodeWithTag("privacy-platform-TIKTOK").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-help-TIKTOK-0").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("privacy-link-error").assertExists()
    }

    @Test fun guardRefusesForeignAddressWithoutStartingAnything() {
        assertFalse(PrivacyLinks.open(app, "https://edevlet-yas-dogrulama.example/giris"))
        assertFalse(PrivacyLinks.open(app, "http://www.turkiye.gov.tr/"))
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(PrivacyLinks.open(app, "https://www.turkiye.gov.tr/"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("https://www.turkiye.gov.tr/", started.dataString)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun closeReturnsToSettings() {
        var closed = false
        showHelper(onBack = { closed = true })
        compose.onNodeWithTag("privacy-close").performClick()
        assertTrue(closed)
    }
}
