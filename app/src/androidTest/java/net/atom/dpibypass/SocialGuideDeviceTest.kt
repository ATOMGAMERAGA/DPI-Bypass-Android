package net.atom.dpibypass

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.atom.dpibypass.ui.MainActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.atom.dpibypass.data.SettingsRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cihaz/emülatörde gerçek Activity ile: Ayarlar → Sosyal Erişim girişi → rehber.
 * VPN izni penceresi, paket açma ve dönüş bu testte YAPILMAZ (sistem arayüzü);
 * bunlar docs/social-access-alpha.md saha listesindeki elle adımlardır.
 * Çalıştırma: ./gradlew connectedDebugAndroidTest (bağlı cihaz gerekir).
 */
@RunWith(AndroidJUnit4::class)
class SocialGuideDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun skipOnboarding() {
        // İlk açılış sihirbazı her şeyin üstüne biner; bu test onu sınamaz.
        runBlocking { SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>()).setOnboardingDone(true) }
    }

    @Test fun settingsEntryOpensGuideAndCloseReturns() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ayarlar").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("social-entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("social-entry").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guide-page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("guide-title").assertIsDisplayed()
        compose.onNodeWithTag("guide-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("social-entry").fetchSemanticsNodes().isNotEmpty() }
    }
}
