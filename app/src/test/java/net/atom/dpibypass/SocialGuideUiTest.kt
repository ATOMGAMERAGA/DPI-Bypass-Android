package net.atom.dpibypass

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.social.AccessAction
import net.atom.dpibypass.social.GuideProblem
import net.atom.dpibypass.social.GuideStep
import net.atom.dpibypass.social.PendingSurface
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.social.SocialGuideHost
import net.atom.dpibypass.ui.social.SocialGuideScreen
import net.atom.dpibypass.ui.theme.DpiBypassTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rehberin GERÇEK Compose arayüzü (Robolectric, JVM). Sayfalar gerçek
 * [AppViewModel], DataStore ve süreç kapsamlı rehber denetleyicisiyle çizilir.
 * Cihaz/emülatör testinin yerine geçmez: VPN izni penceresi, gerçek paket açma
 * ve dönüş Robolectric'te taklit edilir (bkz. docs, saha listesi).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w320dp-h568dp")
class SocialGuideUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private var closed = false

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        SocialGuideHost.resetForTest()
    }

    @After fun tearDown() { SocialGuideHost.resetForTest() }

    private fun installInstagram() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.instagram.android"
            applicationInfo = ApplicationInfo().apply { packageName = "com.instagram.android"; name = "Instagram" }
        })
    }

    private fun show(fontScale: Float = 1f, theme: ThemePref = ThemePref.System): AppViewModel {
        val vm = AppViewModel(app)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                DpiBypassTheme(theme) {
                    SocialGuideScreen(vm, onClose = { closed = true }, onOpenHome = {}, onOpenAdvanced = {}, onOpenDiagnostics = {}, onConnect = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("welcome-start").fetchSemanticsNodes().isNotEmpty() }
        return vm
    }

    private fun title() = compose.onNodeWithTag("guide-title").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()

    /** Sıfır kurulum: her sayfada tek soru; geri cevapları silmez; aşama göstergesi koşullu akışta sayı uydurmaz. */
    @Test fun firstRunIsOneQuestionPerPageAndBackKeepsAnswers() {
        installInstagram()
        show()
        compose.onNodeWithText("Bir uygulama açılmıyor mu?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Aşama 1/3: Hazırlık").assertExists()
        compose.onNodeWithTag("welcome-start").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("app-instagram").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Hangi uygulama açılmıyor?", title())
        compose.onNodeWithTag("app-instagram").performScrollTo().performClick()
        assertEquals("Instagram'da ne yapmak istiyorsun?", title())
        compose.onNodeWithTag("action-VIEW_CONTENT").performClick()
        assertEquals("Belirli bir içerik mi açılmıyor?", title())
        compose.onNodeWithTag("link-continue").assertTextEquals("Bağlantısız devam et").performClick()
        assertEquals("Sorun ne?", title())
        // Geri: cevap duruyor, yeni test başlamıyor.
        compose.onNodeWithTag("guide-back").performClick()
        compose.onNodeWithTag("guide-back").performClick()
        assertEquals("Instagram'da ne yapmak istiyorsun?", title())
        compose.onNodeWithTag("action-VIEW_CONTENT").assertIsSelected()
        val guide = SocialGuideHost.controller(app)
        assertNull("geri gitmek test başlatmaz", guide.state.value.session)
        assertEquals(AccessAction.VIEW_CONTENT, guide.state.value.answers.action)
    }

    /** Büyük yazı (2,0×) ve dar ekran: ana eylem görünür, başlık ve geri/kapat erişilebilir adlıdır. */
    @Test fun largeFontSmallScreenKeepsPrimaryActionVisibleAndLabelled() {
        show(fontScale = 2f, theme = ThemePref.Dark)
        compose.onNodeWithTag("welcome-start").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("guide-title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Rehberden çık").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yardım").assertIsDisplayed()
        compose.onNodeWithTag("welcome-start").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("app-youtube").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Geri").assertIsDisplayed()
        // Seçenekler dikey ve kaydırılabilir: son seçenek de ulaşılabilir.
        compose.onNodeWithTag("app-custom").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("app-youtube").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
    }

    /** Uygulama kurulu değil: hedeflenen yüzey açılamaz, test kaydı düşülmez, başka yüzeye sessizce geçilmez. */
    @Test fun missingAppShowsVisibleFixAndRecordsNothing() {
        show()
        val guide = SocialGuideHost.controller(app)
        compose.runOnIdle {
            guide.go(GuideStep.PICK_APP); guide.pickApp("instagram"); guide.pickAction(AccessAction.ACCOUNT_ACCESS)
            guide.pickProblem(GuideProblem.VERIFICATION)
            // Kurulumdan sonra uygulama kaldırıldı ama oturum uygulama yüzeyini istiyor.
            guide.startRouteCheck(PendingSurface.APP, null)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("try-web").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Instagram bu telefonda kurulu değil", substring = true).assertExists()
        assertNull(guide.state.value.session?.pending)
        compose.onAllNodesWithTag("try-open").assertCountEquals(0)
    }

    /** Rehberden çıkmak bağlantıyı ya da süren işi kapatmaz; yalnız ekranı kapatır. */
    @Test fun closeOnlyLeavesTheScreen() {
        show()
        compose.onNodeWithContentDescription("Rehberden çık").performClick()
        assertTrue(closed)
        assertNull(SocialGuideHost.controller(app).state.value.session)
    }
}
