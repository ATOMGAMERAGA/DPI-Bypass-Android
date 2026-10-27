package net.atom.dpibypass

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.social.ExitProbe
import net.atom.dpibypass.social.ExitStatus
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.social.SocialExitContent
import net.atom.dpibypass.ui.theme.DpiBypassTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Sosyal Erişim (yurt dışı çıkış) ekranı: kapalı → kurulum → çalışıyor → sunucu
 * kapalı durumları gerçek Compose koduyla çizilir. Çıktı: app/build/outputs/exit-screenshots
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class SocialExitScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File("build/outputs/exit-screenshots").apply { mkdirs() }

    @Test fun renderStates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        var dark by mutableStateOf(false)
        var settings by mutableStateOf(Settings(loaded = true))
        var connection by mutableStateOf(ConnectionState.Disconnected)
        var status by mutableStateOf(ExitStatus())
        var probe by mutableStateOf<ExitProbe.Result?>(null)
        compose.setContent {
            DpiBypassTheme(if (dark) ThemePref.Dark else ThemePref.Light) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SocialExitContent(settings, connection, status, probe, false, vm, onConnect = {}, onOpenGuide = {})
                }
            }
        }
        fun shot(name: String) {
            compose.waitForIdle()
            val root = compose.activity.window.decorView
            val bitmap = compose.runOnIdle { renderHardware(root) }
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("exit-headline").assertTextContains("Kapalı")
        shot("01-kapali")

        settings = settings.copy(exitEnabled = true, exitPlatforms = setOf("instagram", "x", "tiktok"))
        compose.onNodeWithTag("exit-headline").assertTextContains("Sunucu gerekli")
        shot("02-sunucu-gerekli")

        settings = settings.copy(exitServer = "socks5://ali:gizli@85.10.20.30:1080")
        compose.onNodeWithTag("exit-headline").assertTextContains("bağlan", substring = true)
        shot("03-hazir")

        connection = ConnectionState.Connected
        status = ExitStatus(running = true, server = "SOCKS5 · 85.10.20.30:1080", viaExit = 42, direct = 118, lastExitHost = "i.instagram.com")
        probe = ExitProbe.Result(true, "DE", "85.10.20.30", 84)
        compose.onNodeWithTag("exit-headline").assertTextContains("Almanya", substring = true)
        shot("04-calisiyor")

        dark = true
        shot("05-calisiyor-koyu")

        probe = ExitProbe.Result(false, error = "Sunucuya bağlanılamadı (adres/port yanlış ya da sunucu kapalı)")
        status = status.copy(failures = 3, lastError = "i.instagram.com: Sunucuya bağlanılamadı")
        compose.onNodeWithTag("exit-headline").assertTextContains("ulaşılamıyor", substring = true)
        shot("06-sunucu-kapali")
        assertTrue((out.listFiles()?.count { it.name.endsWith(".png") } ?: 0) >= 6)
    }

    /**
     * Ekran buzlu cam katmanı (RenderNode) çizer; yazılım tuvali bunu desteklemez.
     * Görünüm bir RenderNode'a kaydedilip donanımsal çiziciyle bir ImageReader'a basılır.
     */
    private fun renderHardware(root: android.view.View): Bitmap {
        val w = root.width
        val h = root.height
        val node = android.graphics.RenderNode("shot").apply { setPosition(0, 0, w, h) }
        val canvas = node.beginRecording()
        root.draw(canvas)
        node.endRecording()
        val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1)
        val renderer = android.graphics.HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val plane = image.planes[0]
            val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            image.close()
            return Bitmap.createBitmap(padded, 0, 0, w, h)
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
