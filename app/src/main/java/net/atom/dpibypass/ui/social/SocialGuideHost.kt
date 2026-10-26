package net.atom.dpibypass.ui.social

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.isp.IspFamily
import net.atom.dpibypass.social.AccessAction
import net.atom.dpibypass.social.AccessLauncher
import net.atom.dpibypass.social.AccessSurface
import net.atom.dpibypass.social.AccountContext
import net.atom.dpibypass.social.CandidateDefinition
import net.atom.dpibypass.social.Feedback
import net.atom.dpibypass.social.Goal
import net.atom.dpibypass.social.GuideEnvironment
import net.atom.dpibypass.social.GuideFlow
import net.atom.dpibypass.social.GuideState
import net.atom.dpibypass.social.RouteContext
import net.atom.dpibypass.social.RouteOutcome
import net.atom.dpibypass.social.RouteSave
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.SocialGuideController
import net.atom.dpibypass.social.SocialPlatform
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.social.TestMode
import net.atom.dpibypass.social.TrialState
import net.atom.dpibypass.vpn.AppScope
import net.atom.dpibypass.vpn.VpnState
import java.io.File
import java.io.FileNotFoundException

/** Etkin Activity'nin ViewModel'inden rehbere gelen, süreç ömrüne bağlanmayan bilgiler. */
interface GuideBridge {
    fun routeContextNow(): RouteContext?
    fun settingsNow(): Settings
    fun ispFamilyNow(): IspFamily
    suspend fun saveSocialMode(mode: TestMode)
}

/**
 * Rehberin süreç kapsamlı sahibi. Rehber ekranından çıkmak (geri, başka sekme)
 * cevapları ve süren testi KAYBETTİRMEZ; süreç ölürse kayıt dosyasından geri
 * gelir ve bekleyen test bağlamı uyuşmadığı için körlemesine kullanılmaz.
 */
object SocialGuideHost {
    @Volatile private var bridge: GuideBridge? = null
    private var controller: SocialGuideController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Channel<GuideState>(Channel.CONFLATED)

    fun attach(b: GuideBridge) { bridge = b }
    fun detach(b: GuideBridge) { if (bridge === b) bridge = null }

    @Synchronized fun controller(context: Context): SocialGuideController {
        controller?.let { return it }
        val app = context.applicationContext
        val file = AtomicFile(File(app.noBackupFilesDir, "social-guide.json"))
        // Küçük bir JSON; ilk açılışta bir kez okunur.
        val raw = runBlocking(Dispatchers.IO) { try { file.openRead().bufferedReader().use { it.readText() } } catch (_: FileNotFoundException) { null } catch (_: Exception) { "" } }
        val created = SocialGuideController(AndroidGuideEnvironment(app), scope, GuideFlow.decode(raw)) { writes.trySend(it) }
        scope.launch(Dispatchers.IO) {
            for (state in writes) runCatching {
                val out = file.startWrite()
                try { out.write(GuideFlow.encode(state).toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
            }
        }
        controller = created
        return created
    }

    internal fun bridge(): GuideBridge? = bridge

    /** Yalnız testler: her test temiz bir rehberle başlasın (süreç yeniden başlamış gibi). */
    @androidx.annotation.VisibleForTesting
    @Synchronized internal fun resetForTest() { controller = null }

    private class AndroidGuideEnvironment(private val app: Context) : GuideEnvironment {
        override val trial: StateFlow<TrialState> get() = SocialAccessRuntime.coordinator.state
        override fun currentContext(): RouteContext? = bridge?.routeContextNow()
        override fun nowMs(): Long = System.currentTimeMillis()
        override fun metered(): Boolean = SocialAccessRuntime.metered()
        override fun appInstalled(platformId: String): Boolean =
            SocialPlatform.byId(platformId)?.let { AccessLauncher.installedPackage(app, it) } != null
        override fun inTunnel(packageName: String?): Boolean {
            val s = bridge?.settingsNow() ?: return false
            return packageName != null && VpnState.state.value == ConnectionState.Connected &&
                AppScope.covers(s.appFilterMode, s.selectedApps, s.vodafoneUnlimitedMode, packageName)
        }
        override fun catalog(goal: Goal): List<CandidateDefinition> =
            SocialAccessRuntime.catalog(goal, bridge?.ispFamilyNow() ?: net.atom.dpibypass.isp.Isp.Other.family)
        override suspend fun setMode(mode: TestMode) { bridge?.saveSocialMode(mode) }
        override suspend fun start(goal: Goal, target: Target, mode: TestMode, baseline: Boolean?, metered: Boolean, allowMetered: Boolean,
            catalog: List<CandidateDefinition>, others: List<Target>): String? = withContext(Dispatchers.IO) {
            SocialAccessRuntime.adapter.target = target
            SocialAccessRuntime.adapter.browserPackage = bridge?.settingsNow()?.socialBrowserPackage?.ifBlank { null }
            SocialAccessRuntime.coordinator.start(goal, target, mode, baseline, metered, allowMetered, catalog, others)
        }
        // Koordinatör çağrıları günlük (journal) dosyasına fsync'li yazar: ana iş parçacığında yapılmaz.
        override suspend fun feedback(event: Feedback): Boolean = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.feedback(event) }
        override suspend fun report(key: String, outcome: String): Boolean = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.report(key, outcome) }
        override suspend fun stop() = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.stop() }
        override suspend fun resume() = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.resume() }
        override suspend fun correctAutoPass() = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.correctAutoPass() }
        override suspend fun retryRecovery() = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.retryRecovery() }
        override suspend fun manual() = withContext(Dispatchers.IO) { SocialAccessRuntime.coordinator.manual() }
        override suspend fun recordRoute(platform: SocialPlatform, action: AccessAction, surface: AccessSurface, browser: String?,
            account: AccountContext, outcome: RouteOutcome, fresh: Boolean, inTunnel: Boolean, opened: RouteContext): RouteSave =
            runCatching {
                SocialAccessRuntime.recordRoute(platform, action, surface, browser, account, outcome, fresh, inTunnel, opened) { bridge?.routeContextNow() }
            }.getOrElse { RouteSave.WriteFailed }
    }
}
