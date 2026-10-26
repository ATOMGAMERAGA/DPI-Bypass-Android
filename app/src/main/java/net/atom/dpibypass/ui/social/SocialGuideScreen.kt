package net.atom.dpibypass.ui.social

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.AccessAction
import net.atom.dpibypass.social.AccessLauncher
import net.atom.dpibypass.social.AccessRoutes
import net.atom.dpibypass.social.AccessSurface
import net.atom.dpibypass.social.Baseline
import net.atom.dpibypass.social.GuideFlow
import net.atom.dpibypass.social.GuideProblem
import net.atom.dpibypass.social.GuideStage
import net.atom.dpibypass.social.GuideState
import net.atom.dpibypass.social.GuideStep
import net.atom.dpibypass.social.PendingSurface
import net.atom.dpibypass.social.PrepIssue
import net.atom.dpibypass.social.RouteDecision
import net.atom.dpibypass.social.RouteLaunch
import net.atom.dpibypass.social.SessionKind
import net.atom.dpibypass.social.SessionView
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.SocialGuideController
import net.atom.dpibypass.social.SocialPlatform
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.social.TargetStatus
import net.atom.dpibypass.social.TargetStatusRules
import net.atom.dpibypass.social.TestMode
import net.atom.dpibypass.social.TrialState
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppTextField
import net.atom.dpibypass.vpn.AppScope
import net.atom.dpibypass.vpn.VodafoneModePolicy

// ---------------------------------------------------------------------------
// Sosyal Erişim rehberi.
//
// Her sayfada tek soru ya da tek görev. Adımlar [GuideStep] ile açıkça
// modellenir; deneme sırasındaki sayfalar motorun gerçek durumundan türetilir
// ([GuideFlow.view]). Ayrıntılı seçenekler "Gelişmiş ayarlar"da, tanılama ayrı
// sayfadadır. Ekrandan çıkmak bağlantıyı ya da süren denemeyi kapatmaz.
// ---------------------------------------------------------------------------

/** Cihazdaki ilgili paketler (ana iş parçacığı dışında okunur). */
data class DeviceApps(
    val loaded: Boolean = false,
    /** Platform → kurulu resmî paket (yoksa null). */
    val packages: Map<String, String?> = emptyMap(),
    val browsers: List<AccessLauncher.Browser> = emptyList(),
    val defaultBrowser: String? = null,
) {
    fun browserLabel(pkg: String?): String? = browsers.firstOrNull { it.packageName == pkg }?.label
    fun hasBrowser(pkg: String?): Boolean = pkg != null && browsers.any { it.packageName == pkg }

    companion object {
        fun load(context: Context) = DeviceApps(
            loaded = true,
            packages = SocialPlatform.catalog.associate { it.id to AccessLauncher.installedPackage(context, it) },
            browsers = AccessLauncher.browsers(context),
            defaultBrowser = AccessLauncher.defaultBrowser(context),
        )
    }
}

@Composable
fun SocialGuideScreen(
    viewModel: AppViewModel,
    onClose: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val guide = remember { SocialGuideHost.controller(context) }
    val gs by guide.state.collectAsStateWithLifecycle()
    val trial by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val failure by viewModel.connectionFailure.collectAsStateWithLifecycle()
    val denied by viewModel.vpnPermissionDenied.collectAsStateWithLifecycle()
    val routeCtx by viewModel.routeContext.collectAsStateWithLifecycle()
    val ledger by SocialAccessRuntime.routes.ledger.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    // Dış uygulamadan dönüş: paket/tarayıcı listesi yenilenir (kurulum/kaldırma). Test BAŞLATILMAZ.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val deviceState = remember { mutableStateOf(DeviceApps()) }
    LaunchedEffect(resumes) { deviceState.value = withContext(Dispatchers.IO) { DeviceApps.load(context) } }
    val device = deviceState.value
    var entered by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(settings.loaded) {
        if (!settings.loaded || entered) return@LaunchedEffect
        entered = true
        // Önceki sürümde odak seçilmemiş olabilir: kayıtlı birincil uygulama varsa o kullanılır, yoksa sorulur.
        if (gs.answers.primary == null && settings.socialPrimaryPlatform.isNotBlank())
            guide.answers { copy(primary = settings.socialPrimaryPlatform, contentLink = settings.socialTargetUrl) }
        guide.onEnter()
    }

    val ui = GuideUi(context, guide, gs, trial, settings, connection, failure, denied, routeCtx?.let { it }, ledger, device, viewModel,
        onClose, onOpenHome, onOpenAdvanced, onOpenDiagnostics, onConnect, onHelp = { help = true })

    val back: () -> Unit = {
        if (gs.step == GuideStep.SESSION && SocialAccessRuntime.coordinator.active()) leaving = true
        else if (!guide.back()) onClose()
    }
    BackHandler(onBack = back)

    if (!settings.loaded) {
        GuidePage(GuideStage.PREPARATION, "Hazırlanıyor", null, onBack = null, onClose = onClose, onHelp = { help = true }) { Working("Ayarlar okunuyor…") }
    } else when (gs.step) {
        GuideStep.WELCOME -> ui.Welcome(back)
        GuideStep.PICK_APP -> ui.PickApp(back)
        GuideStep.OTHER_APPS -> ui.OtherApps(back)
        GuideStep.CUSTOM_TARGET -> ui.CustomTarget(back)
        GuideStep.PICK_ACTION -> ui.PickAction(back)
        GuideStep.CONTENT_LINK -> ui.ContentLink(back)
        GuideStep.PICK_PROBLEM -> ui.PickProblem(back)
        GuideStep.PREPARE -> ui.Prepare(back)
        GuideStep.BROWSER -> ui.PickBrowser(back)
        GuideStep.PRECHECK -> ui.Precheck(back)
        GuideStep.PICK_METHOD -> ui.PickMethod(back)
        GuideStep.METERED -> ui.Metered(back)
        GuideStep.SESSION -> ui.Session(back)
    }

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Nasıl çalışır?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bu rehber, açılmayan uygulama için bu ağda işe yarayan bağlantı ayarını adım adım dener. Bağlantı cihazının içinde kalır; IP adresin ve ülken değişmez.")
                    Text("Giriş, yaş ya da kimlik doğrulaması platformun kendi şartıdır; bu uygulama onu atlatmaz. Sonuçlar senin bildirimine ve ölçümlere dayanır; nedenini her zaman kesin bilemeyiz.")
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Tamam") } },
            dismissButton = { TextButton(onClick = { help = false; onOpenAdvanced() }) { Text("Gelişmiş ayarlar") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Deneme sürüyor") },
            text = { Text("Çıkarsan deneme arka planda sürer; 15 dakika yanıt gelmezse geçici değişiklik geri alınır. Kayıtlı ayarın etkilenmez.") },
            confirmButton = { TextButton(onClick = { leaving = false; guide.stop() }, modifier = Modifier.testTag("leave-stop")) { Text("Durdur ve geri al") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { leaving = false; onClose() }) { Text("Arka planda sürsün") }
                    TextButton(onClick = { leaving = false }) { Text("Vazgeç") }
                }
            },
        )
    }
}

/** Bir sayfanın ihtiyaç duyduğu her şey; ekranlar arasında tekrar tekrar parametre geçmemek için. */
internal class GuideUi(
    val context: Context,
    val guide: SocialGuideController,
    val gs: GuideState,
    val trial: TrialState,
    val settings: Settings,
    val connection: ConnectionState,
    val failure: String?,
    val permissionDenied: Boolean,
    val routeCtx: net.atom.dpibypass.social.RouteContext?,
    val ledger: net.atom.dpibypass.social.RouteLedger,
    val device: DeviceApps,
    val viewModel: AppViewModel,
    val onClose: () -> Unit,
    val onOpenHome: () -> Unit,
    val onOpenAdvanced: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onConnect: () -> Unit,
    val onHelp: () -> Unit,
) {
    val a = gs.answers
    val platform: SocialPlatform? = SocialPlatform.byId(a.primary)
    val target: Target? = GuideFlow.primaryTarget(a)
    val name = GuideText.name(a.primary, target?.url)
    val accName = GuideText.acc(a.primary, target?.url)
    val locName = GuideText.loc(a.primary, target?.url)
    val custom = platform == SocialPlatform.CUSTOM
    val appPackage: String? = a.primary?.let { device.packages[it] }
    val engineConnected = connection == ConnectionState.Connected
    val networkUsable = SocialAccessRuntime.networkUsable()
    val view: SessionView? = gs.session?.let { GuideFlow.view(gs, trial, routeCtx) }

    /** Geçerli tarayıcı: oturumun seçtiği, kayıtlı tercih, sistem varsayılanı, ilk tarayıcı. */
    fun browser(preferred: String? = gs.session?.browserPackage): String? =
        listOf(preferred, settings.socialBrowserPackage.ifBlank { null }, device.defaultBrowser, device.browsers.firstOrNull()?.packageName)
            .firstOrNull { device.hasBrowser(it) }

    val needsBrowser: Boolean get() = a.web || custom
    /** Tarayıcı seçimi yalnız web yoluna geçildiğinde ve geçerli bir tercih yoksa sorulur. */
    val asksBrowser: Boolean get() = needsBrowser && device.loaded && device.browsers.size > 1 &&
        !device.hasBrowser(settings.socialBrowserPackage.ifBlank { null }) && gs.session?.browserPackage == null

    val includeEmpty: Boolean get() = VodafoneModePolicy.filterMode(settings.appFilterMode, settings.vodafoneUnlimitedMode) == AppFilterMode.Include &&
        settings.selectedApps.isEmpty()
    /** Denenecek paket (uygulama ya da tarayıcı) bağlantının kapsamında mı? */
    val testedPackage: String? get() = if (needsBrowser) browser() else appPackage
    val outOfScope: Boolean get() = testedPackage?.let { !AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, it) } ?: false

    fun issues(): List<PrepIssue> = GuideFlow.prepIssues(
        networkUsable = networkUsable, connection = connection, permissionDenied = permissionDenied,
        otherVpn = SocialAccessRuntime.otherVpnActive(engineConnected), connectFailed = connection == ConnectionState.Failed,
        includeListEmpty = includeEmpty && !GuideFlow.routeCheckOnly(a), primaryOutOfScope = outOfScope && !GuideFlow.routeCheckOnly(a),
        needsBrowser = needsBrowser, browserAvailable = device.browsers.isNotEmpty() || !device.loaded,
        needsEngine = !GuideFlow.routeCheckOnly(a),
    )

    fun others(): List<Target> = settings.socialPlatforms.filter { it != a.primary && it != SocialPlatform.CUSTOM.id }.mapNotNull { Target.home(it) }

    fun mode(): TestMode = settings.socialAccessMode

    /** Kurulum sorularından sonra sıradaki adım; gerekmeyen adımlar atlanır. */
    fun continueSetup() {
        when {
            issues().isNotEmpty() -> guide.go(GuideStep.PREPARE)
            asksBrowser -> guide.go(GuideStep.BROWSER)
            GuideFlow.routeCheckOnly(a) -> guide.startRouteCheck(if (needsBrowser) PendingSurface.WEB else PendingSurface.APP, browser())
            GuideFlow.asksBaseline(a) && a.baseline == null -> guide.go(GuideStep.PRECHECK)
            a.baseline == Baseline.WORKING && GuideFlow.asksBaseline(a) -> guide.startTrial(mode(), others(), browser())
            else -> guide.go(GuideStep.PICK_METHOD)
        }
    }

    fun startMethod() {
        if (GuideFlow.asksMetered(a, mode(), target, SocialAccessRuntime.metered()) && a.meteredAllowed == null) guide.go(GuideStep.METERED)
        else guide.startTrial(mode(), others(), browser())
    }

    /**
     * Seçilen yüzeyi açar. Hedeflenen yüzey açılamazsa BAŞKA yüzeye düşülmez;
     * hata sayfada gösterilir ve test kaydı düşülmez.
     */
    fun open(surface: PendingSurface) {
        val url = (if (gs.session?.kind == SessionKind.TRIAL && trial.sessionId == gs.session.trialSessionId) trial.target?.url else null) ?: target?.url ?: return
        when (surface) {
            PendingSurface.APP -> {
                if (custom) { guide.opened(PendingSurface.APP, null, launched = true); return }
                val p = platform ?: return
                val r = AccessLauncher.open(context, AccessSurface.OFFICIAL_APP, p, url, null)
                guide.opened(PendingSurface.APP, AccessLauncher.installedPackage(context, p), r == AccessLauncher.LaunchResult.OPENED, r.name)
            }
            PendingSurface.WEB -> {
                val b = browser()
                val r = AccessLauncher.open(context, AccessSurface.OFFICIAL_WEB, platform, url, b)
                guide.opened(PendingSurface.WEB, b, r == AccessLauncher.LaunchResult.OPENED, r.name)
            }
            PendingSurface.TEST_WINDOW -> guide.opened(PendingSurface.TEST_WINDOW, null, launched = true)
        }
    }

    /** Birincil uygulamanın kayıtlı durumu (kullanıcı bildirimi + ölçüm). */
    fun primaryStatus(): TargetStatus? {
        val t = target ?: return null
        val inScope = testedPackage?.let { AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, it) } ?: true
        return trial.results[t.key]?.let { TargetStatusRules.of(it, inScope, engineConnected, networkUsable) }
    }

    fun decision(action: AccessAction? = a.action): RouteDecision? {
        val ctx = routeCtx ?: return null
        val p = platform?.takeIf { !custom } ?: return null
        return action?.let { AccessRoutes.decide(ledger, p.id, it, ctx) }
    }
}

// ---------------------------------------------------------------------------
// Kurulum adımları
// ---------------------------------------------------------------------------

@Composable
internal fun GuideUi.Welcome(back: () -> Unit) {
    val resumable = gs.steps.size > 1
    val sessionOpen = gs.session != null && view !in setOf(SessionView.RESULT, SessionView.INTERRUPTED, null)
    val canBack = if (resumable) back else null
    when {
        !settings.socialAccessEnabled -> GuidePage(GuideStage.PREPARATION, "Bir uygulama açılmıyor mu?",
            "Birkaç kısa soruyla sorunu bulalım ve bu ağda çalışan ayarı deneyelim.", canBack, onClose, onHelp,
            primary = GuideAction("Başlayalım", {
                viewModel.setSocialAccessEnabled(true)
                if (resumable) guide.back() else guide.go(GuideStep.PICK_APP)
            }, tag = "welcome-start"),
            secondary = listOf(GuideAction("Gelişmiş ayarlar", onOpenAdvanced)),
        ) { Note("Başlayınca Sosyal Erişim açılır. İstediğin zaman Gelişmiş ayarlardan kapatabilirsin.") }

        resumable || sessionOpen -> GuidePage(GuideStage.PREPARATION, "Kaldığın yerden devam et",
            if (platform != null) "$name için başladığın kontrol duruyor. Cevapların kayıtlı." else "Başladığın kontrol duruyor. Cevapların kayıtlı.",
            canBack, onClose, onHelp,
            primary = GuideAction("Kaldığın yerden devam et", { if (resumable) guide.back() else guide.resumeSession() }, tag = "welcome-resume"),
            secondary = listOf(GuideAction("Baştan başla", guide::startOver, tag = "welcome-restart"), GuideAction("Gelişmiş ayarlar", onOpenAdvanced)),
        ) { sessionSummary()?.let { Note(it) } }

        platform != null -> {
            val status = primaryStatus()
            val rec = decision()?.recommended
            // Hesap işleminde hedefin genel "çalışıyor" sonucu hesap başarısı sayılmaz: yalnız yol kaydı.
            val working = rec != null || a.action?.needsAccount != true && (status == TargetStatus.WORKING || status == TargetStatus.PARTIAL)
            val launch = rec?.let { AccessRoutes.launchFor(it, device.browsers.map { b -> b.packageName }.toSet()) }
            if (working) GuidePage(GuideStage.RESULT, "$name için kayıtlı ayar var",
                if (status == TargetStatus.PARTIAL || rec?.status == net.atom.dpibypass.social.RouteStatus.PARTIAL) "Son kontrolde kısmen çalıştı (senin bildirimin)."
                else "Son kontrolde çalıştı (senin bildirimin). Bu ayarla açabilirsin.",
                null, onClose, onHelp,
                primary = when (launch) {
                    is RouteLaunch.BrowserMissing -> GuideAction("Tarayıcıda yeniden kontrol et", {
                        guide.answers { copy(web = true) }; guide.startRouteCheck(PendingSurface.WEB, browser(null))
                    }, tag = "welcome-open")
                    else -> GuideAction("${accName.replaceFirstChar { it.uppercase() }} aç", { openSaved(launch) }, tag = "welcome-open")
                },
                secondary = listOf(
                    GuideAction("Sorun yaşıyorum", { guide.go(GuideStep.PICK_ACTION) }, tag = "welcome-problem"),
                    GuideAction("Başka bir uygulama", { guide.go(GuideStep.PICK_APP) }),
                    GuideAction("Gelişmiş ayarlar", onOpenAdvanced),
                ),
            ) {
                Tag("Senin bildirimin")
                if (launch is RouteLaunch.BrowserMissing) Note("Bu yol başka bir tarayıcıda doğrulanmıştı ve o tarayıcı artık yok. Kanıt başka tarayıcıya taşınmaz; bir kez yeniden kontrol edelim.", error = true)
                if (status == TargetStatus.VERIFYING) Note("Ağın ya da ayarın değişti; açınca bir kez kontrol etmek iyi olur.")
            }
            else GuidePage(GuideStage.PREPARATION, "$name için kontrol",
                if (status == TargetStatus.VERIFYING) "Ağın değişti; kayıtlı sonuç bu bağlantı için geçerli değil." else "Henüz çalışan bir ayar kaydedilmedi.",
                null, onClose, onHelp,
                primary = GuideAction("${accName.replaceFirstChar { it.uppercase() }} kontrol et", { guide.go(GuideStep.PICK_ACTION) }, tag = "welcome-check"),
                secondary = listOf(GuideAction("Başka bir uygulama", { guide.go(GuideStep.PICK_APP) }), GuideAction("Gelişmiş ayarlar", onOpenAdvanced)),
            )
        }

        else -> GuidePage(GuideStage.PREPARATION, "Bir uygulama açılmıyor mu?",
            "Birkaç kısa soruyla sorunu bulalım ve bu ağda çalışan ayarı deneyelim.", null, onClose, onHelp,
            primary = GuideAction("Başlayalım", { guide.go(GuideStep.PICK_APP) }, tag = "welcome-start"),
            secondary = listOf(GuideAction("Gelişmiş ayarlar", onOpenAdvanced), GuideAction("Tanılama", onOpenDiagnostics)),
        )
    }
}

/** Kayıtlı yolu açar: kanıt hangi tarayıcıdaysa yalnız o; soru sorulmaz (yalnız açılır). */
internal fun GuideUi.openSaved(launch: RouteLaunch?) {
    val url = target?.url ?: return
    when (launch) {
        is RouteLaunch.Browser -> AccessLauncher.open(context, AccessSurface.OFFICIAL_WEB, platform, url, launch.packageName)
        RouteLaunch.App -> AccessLauncher.open(context, AccessSurface.OFFICIAL_APP, platform, url, null)
        else -> if (needsBrowser) AccessLauncher.open(context, AccessSurface.OFFICIAL_WEB, platform, url, browser(null))
            else AccessLauncher.open(context, AccessSurface.OFFICIAL_APP, platform, url, null)
    }
}

private fun GuideUi.sessionSummary(): String? = when (view) {
    SessionView.RUNNING -> "Yöntemler deneniyor."
    SessionView.TRY_NOW -> "Sıradaki adım: $locName denemek."
    SessionView.ASK_RESULT, SessionView.ASK_ACCOUNT, SessionView.ASK_FRESH -> "Sıradaki adım: ne olduğunu söylemek."
    SessionView.PAUSED -> "Deneme duraklatıldı."
    SessionView.STALE -> "Bağlantın değişti; kısa kontrol yinelenecek."
    else -> null
}

@Composable
internal fun GuideUi.PickApp(back: () -> Unit) {
    GuidePage(GuideStage.PREPARATION, "Hangi uygulama açılmıyor?", "Sorun yaşadığın uygulamayı seç.", back, onClose, onHelp) {
        if (!device.loaded) Working("Uygulamalar okunuyor…")
        val sorted = SocialPlatform.catalog.sortedWith(compareBy({ device.packages[it.id] == null }, { it.displayName.lowercase() }))
        sorted.forEach { p ->
            val pkg = device.packages[p.id]
            val webOk = AccessRoutes.actions(p).any { AccessSurface.OFFICIAL_WEB in AccessRoutes.surfaces(p, it) }
            OptionRow(
                title = p.displayName,
                subtitle = when { pkg != null -> null; webOk -> "Kurulu değil · tarayıcıda denenebilir"; else -> "Kurulu değil" },
                selected = a.primary == p.id,
                leading = { AppAvatar(p.displayName, pkg) },
                onClick = { viewModel.setSocialPrimary(p.id); guide.pickApp(p.id) },
                tag = "app-${p.id}",
            )
        }
        OptionRow("Listede yok / site adresi gireceğim", onClick = { viewModel.setSocialPrimary(SocialPlatform.CUSTOM.id); guide.pickApp(SocialPlatform.CUSTOM.id) },
            selected = custom, tag = "app-custom")
    }
}

@Composable
internal fun GuideUi.OtherApps(back: () -> Unit) {
    GuidePage(GuideStage.PREPARATION, "Başka uygulamalar da sorunsuz kalsın",
        "Bulunan ayar bunlarda da denenir. Birini bozan ayar seçilmez.", back, onClose, onHelp,
        primary = GuideAction("Tamam", back, tag = "others-done")) {
        SocialPlatform.catalog.filter { it.id != a.primary }.forEach { p ->
            CheckOption(p.displayName, p.id in settings.socialPlatforms, { viewModel.setSocialPlatform(p.id, it) },
                leading = { AppAvatar(p.displayName, device.packages[p.id]) })
        }
    }
}

@Composable
internal fun GuideUi.CustomTarget(back: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf(if (custom) a.contentLink else "") }
    val check = GuideFlow.checkLink(SocialPlatform.CUSTOM.id, text)
    GuidePage(GuideStage.PREPARATION, "Site adresini yaz", "Açılmayan sitenin adresini ya da bağlantısını yapıştır.", back, onClose, onHelp,
        primary = GuideAction("Bu siteyi kontrol et", {
            val t = (check as? GuideFlow.LinkCheck.Valid)?.target ?: return@GuideAction
            guide.answers { copy(contentLink = t.url, action = null, general = true, web = false) }
            viewModel.setSocialTargetUrl(t.url)
            guide.go(GuideStep.PICK_PROBLEM)
        }, enabled = check is GuideFlow.LinkCheck.Valid, tag = "custom-continue"),
    ) {
        AppTextField(value = text, onValueChange = { text = it }, placeholder = "ornek.com ya da https://…",
            trailing = { IconButton(onClick = { clipboard.getText()?.text?.let { text = Target.extractUrl(it) } }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Yapıştır") } })
        when (check) {
            is GuideFlow.LinkCheck.OtherPlatform -> {
                Note("Bu bağlantı ${check.platform.displayName} için. Onu listeden seçerek devam edebilirsin.")
                OptionRow("${check.platform.displayName} olarak devam et", onClick = {
                    viewModel.setSocialPrimary(check.platform.id)
                    guide.pickApp(check.platform.id)
                    guide.answers { copy(contentLink = text) }
                    viewModel.setSocialTargetUrl(text)
                }, tag = "custom-switch")
            }
            GuideFlow.LinkCheck.Invalid -> Note("Bu adres tanınmadı. Herkese açık bir HTTPS adresi yaz (ör. ornek.com).", error = true, icon = Icons.Rounded.ErrorOutline)
            else -> Unit
        }
    }
}

@Composable
internal fun GuideUi.PickAction(back: () -> Unit) {
    val p = platform ?: return PickApp(back)
    GuidePage(GuideStage.PREPARATION, "$locName ne yapmak istiyorsun?", "Seçtiğin işlemi kontrol edeceğiz.", back, onClose, onHelp,
        secondary = listOf(GuideAction("Başka uygulamalar da sorunsuz kalsın", { guide.go(GuideStep.OTHER_APPS) }))) {
        GuideFlow.actions(p).forEach { act ->
            OptionRow(GuideText.actionChoice(act), onClick = { guide.pickAction(act) }, selected = a.action == act, tag = "action-${act.name}")
        }
        OptionRow("Emin değilim, genel kontrol yap", onClick = { guide.pickAction(null) }, selected = a.general, tag = "action-general")
        if (appPackage == null) Note("$name bu telefonda kurulu değil; kontrol tarayıcıda yapılır.")
    }
}

@Composable
internal fun GuideUi.ContentLink(back: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf(a.contentLink) }
    val check = GuideFlow.checkLink(a.primary, text)
    GuidePage(GuideStage.PREPARATION, "Belirli bir içerik mi açılmıyor?", "Varsa bağlantısını yapıştır; yoksa genel kontrol yapılır.", back, onClose, onHelp,
        primary = when (check) {
            is GuideFlow.LinkCheck.Valid -> GuideAction("Bu içerikle devam et", {
                guide.answers { copy(contentLink = check.target.url) }; viewModel.setSocialTargetUrl(check.target.url); guide.go(GuideStep.PICK_PROBLEM)
            }, tag = "link-continue")
            GuideFlow.LinkCheck.Empty -> GuideAction("Bağlantısız devam et", {
                guide.answers { copy(contentLink = "") }; viewModel.setSocialTargetUrl(""); guide.go(GuideStep.PICK_PROBLEM)
            }, tag = "link-continue")
            else -> GuideAction("Bağlantıyı düzelt", {}, enabled = false, tag = "link-continue")
        },
        secondary = if (check is GuideFlow.LinkCheck.Empty) emptyList() else listOf(GuideAction("Bağlantıyı temizle", { text = "" })),
    ) {
        AppTextField(value = text, onValueChange = { text = it }, placeholder = "Bağlantı (isteğe bağlı)",
            trailing = { IconButton(onClick = { clipboard.getText()?.text?.let { text = Target.extractUrl(it) } }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Yapıştır") } })
        when (check) {
            is GuideFlow.LinkCheck.OtherPlatform -> {
                Note("Bu bağlantı ${check.platform.displayName} için, $name için değil. Başka bir hedefe sessizce test yapmayız.", error = true)
                if (check.platform != SocialPlatform.CUSTOM) OptionRow("${check.platform.displayName} olarak devam et", onClick = {
                    viewModel.setSocialPrimary(check.platform.id); guide.pickApp(check.platform.id); guide.answers { copy(contentLink = text) }
                })
            }
            GuideFlow.LinkCheck.Invalid -> Note("Bu bağlantı $name içeriği olarak tanınmadı. Paylaş menüsünden kopyalanan resmî bağlantıyı yapıştır.", error = true, icon = Icons.Rounded.ErrorOutline)
            is GuideFlow.LinkCheck.Valid -> GuideText.shortTarget(check.target)?.let { Note("İçerik: $it", icon = Icons.Rounded.Info) }
            else -> Unit
        }
    }
}

@Composable
internal fun GuideUi.PickProblem(back: () -> Unit) {
    GuidePage(GuideStage.PREPARATION, "Sorun ne?", "Gördüğüne en yakın olanı seç.", back, onClose, onHelp) {
        GuideFlow.problems(platform).forEach { pr ->
            OptionRow(pr.label, onClick = { guide.pickProblem(pr); continueAfter(pr) }, selected = a.problem == pr, tag = "problem-${pr.name}",
                subtitle = if (pr == GuideProblem.VERIFICATION) "Önce hangi yolun çalıştığına bakarız; doğrulamayı atlatmayız." else null)
        }
    }
}

/** Denetleyicinin GÜNCEL durumuyla (aynı karede yazılan cevap dahil) yeni bir görünüm. */
internal fun GuideUi.fresh(settingsOverride: Settings = settings) = GuideUi(context, guide, guide.state.value, trial, settingsOverride, connection,
    failure, permissionDenied, routeCtx, ledger, device, viewModel, onClose, onOpenHome, onOpenAdvanced, onOpenDiagnostics, onConnect, onHelp)

/** Seçim yazıldıktan HEMEN sonra sıradaki adım: cevap tekrar okunarak karar verilir. */
private fun GuideUi.continueAfter(problem: GuideProblem) {
    val next = fresh()
    if (next.a.problem == problem) next.continueSetup()
}

@Composable
internal fun GuideUi.Prepare(back: () -> Unit) {
    val issue = issues().firstOrNull()
    val cap = accName.replaceFirstChar { it.uppercase() }
    when (issue) {
        null -> GuidePage(GuideStage.PREPARATION, "Bağlantı hazır", "Şimdi $name kontrol edilebilir.", back, onClose, onHelp,
            primary = GuideAction("$cap kontrol et", { continueSetup() }, tag = "prepare-continue"))
        PrepIssue.NO_INTERNET -> GuidePage(GuideStage.PREPARATION, "İnternet bağlantısı yok", "Wi-Fi ya da mobil veriyi açınca bu sayfa kendiliğinden güncellenir.", back, onClose, onHelp,
            primary = GuideAction("Ağ ayarlarını aç", { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }),
            secondary = listOf(GuideAction("Şimdilik çık", onClose)))
        PrepIssue.NO_BROWSER -> GuidePage(GuideStage.PREPARATION, "Tarayıcı bulunamadı", "$name kurulu değil ve açabilecek bir tarayıcı yok. Kendiliğinden bir şey indirmiyoruz.", back, onClose, onHelp,
            primary = GuideAction("Başka uygulama seç", { guide.go(GuideStep.PICK_APP) }))
        PrepIssue.ENGINE_OFF, PrepIssue.OTHER_VPN -> GuidePage(GuideStage.PREPARATION, if (issue == PrepIssue.OTHER_VPN) "Başka bir VPN açık" else "Bağlantıyı hazırla",
            if (issue == PrepIssue.OTHER_VPN) "Devam edersen Android o VPN'i kapatıp bu bağlantıyı açar. Açılan sistem penceresinde izin ver."
            else "Android'in bağlantı izni gerekiyor. Açılan sistem penceresinde izin ver.", back, onClose, onHelp,
            primary = GuideAction("Bağlantıyı hazırla", { viewModel.clearVpnPermissionDenied(); onConnect() }, tag = "prepare-connect"),
            secondary = listOf(GuideAction("Şimdilik çık", onClose))) {
            Note("Bu bağlantı cihazının içinde kalır; IP adresin ve ülken değişmez.", icon = Icons.Rounded.Info)
        }
        PrepIssue.CONNECTING -> GuidePage(GuideStage.PREPARATION, "Bağlanıyor", "Bağlantı kurulunca bu sayfa kendiliğinden ilerler.", back, onClose, onHelp,
            secondary = listOf(GuideAction("Şimdilik çık", onClose))) { Working("Bağlantı kuruluyor…") }
        PrepIssue.PERMISSION_DENIED -> GuidePage(GuideStage.PREPARATION, "İzin verilmedi", "Bağlantı izni olmadan yöntemler denenemez. İstersen tekrar deneyebilirsin.", back, onClose, onHelp,
            primary = GuideAction("Tekrar dene", { viewModel.clearVpnPermissionDenied(); onConnect() }, tag = "prepare-retry"),
            secondary = listOf(GuideAction("Şimdilik çık", onClose)))
        PrepIssue.CONNECT_FAILED -> GuidePage(GuideStage.PREPARATION, "Bağlantı kurulamadı", failure?.take(160) ?: "Bağlantı başlatılamadı.", back, onClose, onHelp,
            primary = GuideAction("Tekrar dene", { onConnect() }, tag = "prepare-retry"),
            secondary = listOf(GuideAction("Ana ekrana git", onOpenHome), GuideAction("Şimdilik çık", onClose)))
        PrepIssue.INCLUDE_LIST_EMPTY, PrepIssue.OUT_OF_SCOPE -> {
            val pkg = testedPackage
            val label = if (needsBrowser) (device.browserLabel(pkg) ?: "Tarayıcı") else name
            GuidePage(GuideStage.PREPARATION, if (issue == PrepIssue.INCLUDE_LIST_EMPTY) "Bağlantı hiçbir uygulamayı kapsamıyor" else "$label bağlantının dışında",
                if (issue == PrepIssue.INCLUDE_LIST_EMPTY) "\"Yalnızca seçili\" listesi boş. $label listeye eklensin mi?"
                else "Uygulama filtren yüzünden $label trafiği bu bağlantıdan geçmiyor.", back, onClose, onHelp,
                primary = GuideAction("$label bağlantıya eklensin", { pkg?.let { viewModel.bringIntoScope(setOf(it)) } }, enabled = pkg != null, tag = "prepare-scope"),
                secondary = listOf(GuideAction("Şimdilik çık", onClose))) {
                Note("Yalnız $label eklenir; diğer filtre ve Vodafone ayarların değişmez.", icon = Icons.Rounded.Info)
            }
        }
    }
}

@Composable
internal fun GuideUi.PickBrowser(back: () -> Unit) {
    GuidePage(GuideStage.PREPARATION, "Hangi tarayıcıda açalım?", "$name tarayıcıda denenecek. Sonuç bu tarayıcı için kaydedilir.", back, onClose, onHelp) {
        device.browsers.forEach { b ->
            OptionRow(b.label, onClick = {
                viewModel.setSocialBrowserPackage(b.packageName)
                // Süren oturumda yalnız açılacak tarayıcı değişir (kanıt taşınmaz); kurulumda akış sürer.
                if (gs.session != null && gs.steps.getOrNull(gs.steps.size - 2) == GuideStep.SESSION) { guide.switchSurface(PendingSurface.WEB, b.packageName); guide.back() }
                else continueSetupAfterBrowser(b.packageName)
            }, selected = b.packageName == settings.socialBrowserPackage, leading = { AppAvatar(b.label, b.packageName) }, tag = "browser-${b.packageName}",
                subtitle = if (b.packageName == device.defaultBrowser) "Sistem varsayılanı" else null)
        }
    }
}

/** Tarayıcı seçildikten sonra kurulum (henüz oturum yoksa) kaldığı yerden sürer. */
private fun GuideUi.continueSetupAfterBrowser(pkg: String) = fresh(settings.copy(socialBrowserPackage = pkg)).continueSetup()

@Composable
internal fun GuideUi.Precheck(back: () -> Unit) {
    val cap = accName.replaceFirstChar { it.uppercase() }
    GuidePage(GuideStage.CHECK, "$locName seçtiğin işlem şu anda çalışıyor mu?", "Bilmiyorsan uygulamayı açıp bakabilir, sonra buraya dönebilirsin.", back, onClose, onHelp,
        secondary = listOf(GuideAction("$cap aç ve bak", { openSaved(null) }))) {
        OptionRow("Hayır, çalışmıyor", onClick = { guide.pickBaseline(Baseline.NOT_WORKING); guide.go(GuideStep.PICK_METHOD) }, selected = a.baseline == Baseline.NOT_WORKING, tag = "baseline-no")
        OptionRow("Evet, şu anda çalışıyor", onClick = { guide.pickBaseline(Baseline.WORKING); guide.startTrial(mode(), others(), browser()) }, selected = a.baseline == Baseline.WORKING, tag = "baseline-yes")
        OptionRow("Bilmiyorum", onClick = { guide.pickBaseline(Baseline.UNKNOWN); guide.go(GuideStep.PICK_METHOD) }, selected = a.baseline == Baseline.UNKNOWN, tag = "baseline-unknown")
    }
}

@Composable
internal fun GuideUi.PickMethod(back: () -> Unit) {
    val selected = mode()
    val others = others()
    GuidePage(GuideStage.CHECK, "Nasıl deneyelim?",
        "Otomatik kontrol bağlantı yollarını kendisi ölçer; uygulamanın içinde çalışıp çalışmadığını yine sana sorarız.", back, onClose, onHelp,
        primary = GuideAction("Yöntemleri dene", { startMethod() }, tag = "method-start"),
        secondary = listOf(GuideAction(if (others.isEmpty()) "Başka uygulamalar da sorunsuz kalsın" else "Birlikte kontrol: ${others.joinToString { GuideText.name(it.platformId) }} · değiştir",
            { guide.go(GuideStep.OTHER_APPS) }, tag = "method-others"))) {
        OptionRow("Otomatik kontrol (Beta)", subtitle = "Yöntemleri sırayla ağ düzeyinde ölçer; sonunda senden kısa bir onay ister.",
            onClick = { viewModel.saveSocialModeOnly(TestMode.AUTO_BETA) }, selected = selected == TestMode.AUTO_BETA, tag = "method-auto")
        OptionRow("Adım adım kendim deneyeceğim", subtitle = "Her yöntemden sonra uygulamayı açıp ne olduğunu söylersin.",
            onClick = { viewModel.saveSocialModeOnly(TestMode.MANUAL) }, selected = selected == TestMode.MANUAL, tag = "method-manual")
    }
}

@Composable
internal fun GuideUi.Metered(back: () -> Unit) {
    GuidePage(GuideStage.CHECK, "Mobil veride ek veri kullanılsın mı?", "Test penceresi seçtiğin içeriği açar ve birkaç MB veri harcayabilir.", back, onClose, onHelp) {
        OptionRow("Evet, kullanılsın", onClick = { guide.answers { copy(meteredAllowed = true) }; guide.startTrial(mode(), others(), browser()) }, tag = "metered-yes")
        OptionRow("Hayır, test penceresiz devam et", onClick = { guide.answers { copy(meteredAllowed = false) }; guide.startTrial(mode(), others(), browser()) }, tag = "metered-no")
    }
}
