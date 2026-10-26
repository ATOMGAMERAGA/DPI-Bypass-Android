package net.atom.dpibypass

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.social.DeviceApps
import net.atom.dpibypass.ui.social.GuideUi
import net.atom.dpibypass.ui.social.Session
import net.atom.dpibypass.ui.social.SocialGuideHost
import net.atom.dpibypass.ui.social.Welcome
import net.atom.dpibypass.ui.social.PickApp
import net.atom.dpibypass.ui.social.PickAction
import net.atom.dpibypass.ui.social.PickProblem
import net.atom.dpibypass.ui.social.PickMethod
import net.atom.dpibypass.ui.social.Prepare
import net.atom.dpibypass.ui.theme.DpiBypassTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Rehber sayfalarının görüntüleri: temiz test verisi (hesap, kişisel içerik yok).
 * Her sayfa gerçek Compose koduyla çizilir; görüntü üretilemezse test kırmızıdır.
 * Çıktı: app/build/outputs/guide-screenshots klasöründe PNG dosyaları
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class SocialGuideScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File("build/outputs/guide-screenshots").apply { mkdirs() }
    private val ctx = RouteContext("WIFI:TURKCELL", "gen=1|engine=P2", SocialAccessRuntime.processId)
    private val target = Target.home("instagram")!!
    private val answers = GuideAnswers(primary = "instagram", action = AccessAction.PLAY_MEDIA, problem = GuideProblem.MEDIA)
    private val device = DeviceApps(loaded = true, packages = SocialPlatform.catalog.associate { it.id to null } + ("instagram" to "com.instagram.android"),
        browsers = listOf(AccessLauncher.Browser("org.example.browser", "Tarayıcı")), defaultBrowser = "org.example.browser")
    private val settings = Settings(loaded = true, socialAccessEnabled = true, socialPrimaryPlatform = "instagram", socialPlatforms = setOf("instagram"))
    private val candidate = CandidateDefinition(id = "N4-P2", family = "N4", displayName = "Yerel DPI: P2", strategyId = "P2", surface = "OFFICIAL_NATIVE_APP")

    private fun pending(action: AccessAction = AccessAction.PLAY_MEDIA) = PendingExternalTest("p1", "g1", "instagram", "instagram", target.url, action,
        PendingSurface.APP, "com.instagram.android", ctx, "s1", "a1", "o1", true, 0)

    private fun waiting() = TrialState(phase = Phase.WAITING_FOR_USER, sessionId = "s1", attemptId = "a1", operationId = "o1", candidate = candidate,
        resourceDisposition = "TRIAL_ACTIVE", attempted = 2, target = target, targets = listOf(target))

    @Test fun renderMainFlowAndErrorPages() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SocialGuideHost.resetForTest()
        val vm = AppViewModel(app)
        val guide = SocialGuideHost.controller(app)
        var page by mutableStateOf<(@Composable () -> Unit)?>(null)
        var dark by mutableStateOf(false)
        var font by mutableStateOf(1f)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                DpiBypassTheme(if (dark) ThemePref.Dark else ThemePref.Light) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { page?.invoke() }
                }
            }
        }
        fun ui(gs: GuideState, trial: TrialState = TrialState(), s: Settings = settings, connection: ConnectionState = ConnectionState.Connected, denied: Boolean = false) =
            GuideUi(app, guide, gs, trial, s, connection, null, denied, ctx, RouteLedger(), device, vm, {}, {}, {}, {}, {}, {})
        fun shot(name: String, body: @Composable () -> Unit) {
            page = body
            compose.waitForIdle()
            compose.onNodeWithTag("guide-page").assertExistsCompat()
            // Robolectric'te captureToImage çizim beklerken takılabiliyor: kök görünüm doğrudan çizilir.
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val setup = GuideState(listOf(GuideStep.WELCOME), GuideAnswers())
        val session = { s: GuideSession -> GuideState(listOf(GuideStep.WELCOME, GuideStep.SESSION), answers, s) }
        val trialSession = GuideSession("g1", SessionKind.TRIAL, trialSessionId = "s1")

        shot("01-karsilama") { ui(setup, s = settings.copy(socialAccessEnabled = false, socialPrimaryPlatform = "")).Welcome {} }
        shot("02-uygulama-sec") { ui(setup.copy(steps = listOf(GuideStep.WELCOME, GuideStep.PICK_APP))).PickApp {} }
        shot("03-islem-sec") { ui(GuideState(listOf(GuideStep.WELCOME, GuideStep.PICK_ACTION), answers.copy(action = null))).PickAction {} }
        shot("04-sorun-ne") { ui(GuideState(listOf(GuideStep.WELCOME, GuideStep.PICK_PROBLEM), answers.copy(problem = null))).PickProblem {} }
        shot("05-baglanti-hazirla") { ui(GuideState(listOf(GuideStep.PREPARE), answers), connection = ConnectionState.Disconnected).Prepare {} }
        shot("06-izin-reddedildi") { ui(GuideState(listOf(GuideStep.PREPARE), answers), connection = ConnectionState.Disconnected, denied = true).Prepare {} }
        shot("07-yontem-sec") { ui(GuideState(listOf(GuideStep.PICK_METHOD), answers)).PickMethod {} }
        shot("08-deneniyor") { ui(session(trialSession), TrialState(phase = Phase.PROBING, sessionId = "s1", candidate = candidate, attempted = 2, target = target, targets = listOf(target))).Session {} }
        shot("09-simdi-dene") { ui(session(trialSession), waiting()).Session {} }
        shot("10-ne-oldu") { ui(session(trialSession.copy(pending = pending())), waiting()).Session {} }
        shot("11-hesap-sorusu") { ui(GuideState(listOf(GuideStep.SESSION), answers.copy(action = AccessAction.ACCOUNT_ACCESS),
            trialSession.copy(pending = pending(AccessAction.ACCOUNT_ACCESS), answer = RouteOutcome.WORKS)), waiting()).Session {} }
        val done = TrialState(phase = Phase.FINISHED, sessionId = "s1", finishReason = "RESOLVED_USER_REPORTED", candidate = candidate,
            resourceDisposition = "KEPT_ACTIVE", plan = PlanState("P2", "o1", true), target = target, targets = listOf(target))
        val last = Submission("p1", RouteOutcome.WORKS, null, PendingSurface.APP, "com.instagram.android", AccessAction.PLAY_MEDIA, "SAVED", true, "s1", 0, ctx, true)
        shot("12-sonuc-calisti") { ui(session(trialSession.copy(last = last)), done).Session {} }
        shot("13-sonuc-dogrulama") { ui(session(GuideSession("g2", SessionKind.ROUTE_CHECK, last = last.copy(outcome = RouteOutcome.VERIFICATION_REQUIRED, trialSessionId = null))),
            TrialState()).Session {} }
        shot("14-baglanti-degisti") { ui(session(trialSession.copy(pending = pending(), stale = true)), waiting()).Session {} }
        shot("15-bekleme-suresi") { ui(session(trialSession), TrialState(phase = Phase.PAUSED, pauseReason = "COOLDOWN", sessionId = "s1", target = target, targets = listOf(target))).Session {} }
        shot("16-uygulama-kurulu-degil") {
            GuideUi(app, guide, GuideState(listOf(GuideStep.SESSION), answers.copy(action = AccessAction.ACCOUNT_ACCESS), GuideSession("g3", SessionKind.ROUTE_CHECK)),
                TrialState(), settings, ConnectionState.Connected, null, false, ctx, RouteLedger(), device.copy(packages = device.packages + ("instagram" to null)),
                vm, {}, {}, {}, {}, {}, {}).Session {}
        }
        shot("17-tukenmis") { ui(session(trialSession), done.copy(finishReason = "CANDIDATES_EXHAUSTED", resourceDisposition = "NONE", plan = PlanState())).Session {} }
        shot("18-kurtarma") { ui(session(trialSession), TrialState(phase = Phase.RECOVERY_REQUIRED, sessionId = "s1")).Session {} }
        font = 2f
        shot("19-buyuk-yazi-simdi-dene") { ui(session(trialSession), waiting()).Session {} }
        font = 1f; dark = true
        shot("20-koyu-tema-sonuc") { ui(session(trialSession.copy(last = last)), done).Session {} }
        shot("21-koyu-tema-karsilama") { ui(setup, s = settings.copy(socialAccessEnabled = false, socialPrimaryPlatform = "")).Welcome {} }
        assertTrue((out.listFiles()?.count { it.name.endsWith(".png") } ?: 0) >= 21)
        SocialGuideHost.resetForTest()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()

}
