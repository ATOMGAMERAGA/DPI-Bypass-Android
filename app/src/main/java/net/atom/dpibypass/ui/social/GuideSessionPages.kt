package net.atom.dpibypass.ui.social

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.atom.dpibypass.social.AccessAction
import net.atom.dpibypass.social.AccessLauncher
import net.atom.dpibypass.social.AccessRoutes
import net.atom.dpibypass.social.AccessSurface
import net.atom.dpibypass.social.AccountContext
import net.atom.dpibypass.social.ControlledWebProbe
import net.atom.dpibypass.social.GuideFlow
import net.atom.dpibypass.social.GuideProblem
import net.atom.dpibypass.social.GuideStage
import net.atom.dpibypass.social.GuideStep
import net.atom.dpibypass.social.PendingSurface
import net.atom.dpibypass.social.Phase
import net.atom.dpibypass.social.RouteOutcome
import net.atom.dpibypass.social.RouteStatus
import net.atom.dpibypass.social.RouteVerdict
import net.atom.dpibypass.social.SessionKind
import net.atom.dpibypass.social.SessionView
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.SocialGuideController
import net.atom.dpibypass.social.SocialText
import net.atom.dpibypass.social.Submission
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.ui.design.ButtonTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Deneme sayfaları. Hangisinin gösterileceğine motorun gerçek durumu karar verir
// ([GuideFlow.view]); bu sayfalar yalnız gösterir ve kullanıcı niyetini iletir.
// Ekran yeniden çizildiğinde ya da ileri-geri gidildiğinde test başlamaz.
// ---------------------------------------------------------------------------

@Composable
internal fun GuideUi.Session(back: () -> Unit) {
    val s = gs.session ?: return Welcome(back)
    val stage = GuideFlow.stage(gs, view)
    val active = SocialAccessRuntime.coordinator.active()
    val stop = GuideAction("Durdur", guide::stop, tag = "session-stop")
    val details = details()
    when (view ?: SessionView.INTERRUPTED) {
        SessionView.RUNNING -> GuidePage(stage, runningTitle(), runningBody(), back, onClose, onHelp,
            primary = GuideAction("Durdur", guide::stop, tag = "session-stop"), primaryTone = ButtonTone.Danger,
            secondary = buildList {
                // Ölçüm sürerken kullanıcı uygulamada açıldığını gördüyse arama burada durur (motorun mevcut yolu).
                if (trial.phase == Phase.PROBING) add(GuideAction("Uygulamada açıldı, burada dur", guide::observedWorks, tag = "running-works"))
                // Rehber içinde kip değişimi: otomatik deneme adım adım sürer, ertelenenler sorulur.
                if (trial.mode == net.atom.dpibypass.social.TestMode.AUTO_BETA) add(GuideAction("Adım adım kendim deneyeceğim", {
                    viewModel.saveSocialModeOnly(net.atom.dpibypass.social.TestMode.MANUAL)
                    guide.engineAction { manual() }
                }, tag = "running-manual"))
            }) {
            Working(if (trial.attempted > 0) "${trial.attempted}. yöntem" else "Başlangıç")
            s.last?.takeIf { it.trialSessionId == trial.sessionId && it.outcome == RouteOutcome.NOT_WORKING }?.let {
                Note("Önceki yöntem \"çalışmadı\" olarak kaydedildi ve geri alındı.", icon = Icons.Rounded.Info)
            }
            Details(details)
        }

        SessionView.OBSERVING -> {
            val tap = trial.phase == Phase.PAUSED && trial.pauseReason == "USER_ACTION_REQUIRED"
            GuidePage(stage, "Test penceresinde bakılıyor",
                if (tap) "Videoyu başlatmak için pencerede oynat düğmesine dokun." else "Bu pencere hesabına bağlı değildir; yalnız herkese açık içeriği gösterir.",
                back, onClose, onHelp,
                primary = if (tap) GuideAction("Dokundum, devam et", { guide.engineAction { resume() } }, tag = "observe-resume")
                    else GuideAction("İçerik açıldı, burada dur", guide::observedWorks, tag = "observe-works"),
                secondary = listOf(stop)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) { ControlledWebProbe(trial, Modifier.fillMaxWidth().height(320.dp)) }
                Details(details)
            }
        }

        SessionView.TRY_NOW -> TryNow(back, s, active)

        SessionView.ASK_RESULT -> {
            val p = s.pending!!
            val where = when (p.surface) {
                PendingSurface.APP -> if (custom) "Kendin açtın" else "Uygulamada denedin"
                PendingSurface.WEB -> "${device.browserLabel(p.packageName) ?: "Tarayıcı"} ile denedin"
                PendingSurface.TEST_WINDOW -> "Test penceresinde denedin"
            }
            GuidePage(GuideStage.RESULT, "$locName ne oldu?", "$where: ${p.action?.let(GuideText::actionChoice)?.lowercase() ?: "genel kontrol"}.", back, onClose, onHelp,
                secondary = listOfNotNull(GuideAction("Tekrar aç", { reopen(p.surface, p.packageName) }).takeIf { p.surface != PendingSurface.TEST_WINDOW },
                    stop.takeIf { active })) {
                if (p.surface == PendingSurface.TEST_WINDOW)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) { ControlledWebProbe(trial, Modifier.fillMaxWidth().height(260.dp)) }
                RouteOutcome.entries.forEach { o -> OptionRow(GuideText.outcome(o), onClick = { guide.answer(o) }, tag = "outcome-${o.name}") }
            }
        }

        SessionView.ASK_ACCOUNT -> GuidePage(GuideStage.RESULT, "Kendi hesabın açık mıydı?",
            "Sonucun hesabına ait olup olmadığını ayırmak için soruyoruz. Hesap bilgisi saklanmaz.", { guide.answerBack() }, onClose, onHelp) {
            AccountContext.entries.forEach { acc -> OptionRow(GuideText.account(acc), onClick = { guide.account(acc) }, tag = "account-${acc.name}") }
        }

        SessionView.ASK_FRESH -> GuidePage(GuideStage.RESULT, "Uygulamayı yeniden başlatıp yeni bir içerik denedin mi?",
            "Bunu yalnız sen bilebilirsin; senin yerine işaretlemeyiz.", { guide.answerBack() }, onClose, onHelp) {
            OptionRow("Evet, yeniden başlattım ve yeni içerik denedim", onClick = { guide.fresh(true) }, tag = "fresh-yes")
            OptionRow("Hayır", onClick = { guide.fresh(false) }, tag = "fresh-no")
        }

        SessionView.SUBMITTING -> GuidePage(GuideStage.RESULT, "Kaydediliyor", "Sonucun bu denemeye yazılıyor; aynı anda kısa bir ağ ölçümü yapılıyor.",
            null, onClose, onHelp) { Working("Kaydediliyor…") }

        SessionView.STALE -> GuidePage(stage, "Bağlantın değişti", "Bu kısa kontrolü tekrar yapalım; diğer cevapların duruyor.", back, onClose, onHelp,
            primary = GuideAction("Kontrolü tekrarla", guide::redoCheck, tag = "stale-redo")) {
            Note("Önceki sonuç yeni bağlantıya yazılmadı.", icon = Icons.Rounded.Info)
        }

        SessionView.PARTIAL_CHOICE -> GuidePage(GuideStage.RESULT, "Bir kısmı çalıştı", "Böyle kullanabilir ya da sıradaki yolu deneyebilirsin.", back, onClose, onHelp,
            primary = GuideAction("Böyle kullan", { guide.partialDecision(true) }, tag = "partial-keep"),
            secondary = listOf(GuideAction("Diğer yolu dene", { guide.partialDecision(false) }, tag = "partial-other"), stop))

        SessionView.PAUSED -> Paused(back, stop)

        SessionView.RECOVERY -> GuidePage(stage, "Geri alma tamamlanamadı", "Güvenlik için yeni deneme engellendi. Geri almayı yeniden dene.", back, onClose, onHelp,
            primary = GuideAction("Geri almayı yeniden dene", { guide.engineAction { retryRecovery() } }, tag = "recovery-retry"),
            secondary = listOf(GuideAction("Tanılama", onOpenDiagnostics))) { Details(details) }

        SessionView.INTERRUPTED -> {
            // Başlatılamayan deneme (bekleme süresi, geçersiz hedef) "yarıda kaldı" diye gösterilmez.
            val notStarted = s.error != null && s.trialSessionId == null
            GuidePage(stage, if (notStarted) "Deneme başlatılamadı" else "Deneme yarıda kaldı",
                if (notStarted) trial.message.ifBlank { "Bir bekleme süresi ya da kapalı bir ayar denemeyi engelledi." }.take(200)
                else "Uygulama kapanmış ya da başka bir deneme başlamış olabilir. Eski sonuç kullanılmadı.",
                back, onClose, onHelp,
                primary = GuideAction(if (notStarted) "Tekrar dene" else "Aynı cevaplarla yeniden başlat", { restart(s.kind, s.surface) }, tag = "interrupted-restart"),
                secondary = listOf(GuideAction("Baştan başla", guide::startOver))) {
                GuideText.launchError(s.error)?.let { Note(it, error = true) }
            }
        }

        SessionView.RESULT -> Result(back, s.last)
    }
}

/** Uygulamayı yeniden açar; bekleyen test değişmez (aynı denemenin parçası). */
private fun GuideUi.reopen(surface: PendingSurface, pkg: String?) {
    val url = trial.target?.url?.takeIf { gs.session?.kind == SessionKind.TRIAL } ?: target?.url ?: return
    when (surface) {
        PendingSurface.APP -> if (!custom) AccessLauncher.open(context, AccessSurface.OFFICIAL_APP, platform, url, null)
        PendingSurface.WEB -> AccessLauncher.open(context, AccessSurface.OFFICIAL_WEB, platform, url, pkg)
        PendingSurface.TEST_WINDOW -> Unit
    }
}

private fun GuideUi.restart(kind: SessionKind, surface: PendingSurface) = when (kind) {
    SessionKind.TRIAL -> guide.startTrial(mode(), others(), browser())
    else -> guide.startRouteCheck(surface, browser(), kind)
}

private fun GuideUi.runningTitle(): String = when (trial.phase) {
    Phase.BASELINING -> "Şu anki durum ölçülüyor"
    Phase.SELECTING, Phase.PREPARING -> "Sıradaki yöntem hazırlanıyor"
    Phase.APPLYING -> "Yöntem uygulanıyor"
    Phase.PROBING -> "Bağlantı yolları kontrol ediliyor"
    Phase.CANCELLING, Phase.ROLLING_BACK -> "Geçici değişiklik geri alınıyor"
    else -> "Kontrol ediliyor"
}

private fun GuideUi.runningBody(): String {
    val many = trial.targets.size > 1
    val auto = trial.mode == net.atom.dpibypass.social.TestMode.AUTO_BETA
    return (if (many) "$name ve seçtiğin diğer ${trial.targets.size - 1} uygulama birlikte kontrol ediliyor." else "$name için yöntemler sırayla deneniyor.") +
        if (auto) " Sonunda senden kısa bir onay isteyeceğiz." else " Sıra sana gelince haber vereceğiz."
}

private fun GuideUi.details(): List<String> = buildList {
    add("Aşama: ${SocialText.phase(trial.phase)}")
    trial.candidate?.let { add("Yöntem: ${it.displayName}" + (it.strategyId?.let { id -> " ($id)" } ?: "")) }
    if (trial.attempted > 0) add("Denenen yöntem: ${trial.attempted}")
    add("Bağlantı ölçümü: ${SocialText.technical(trial.technicalOutcome)} · İstek: ${trial.requests}")
    trial.plan.strategyId?.let { add("Kayıtlı plan: $it" + if (trial.plan.installed) " (çalışıyor)" else " (bu bağlantıda kurulu değil)") }
    if (trial.deferred.isNotEmpty()) add("Ertelenen yöntem: ${trial.deferred.size}")
    if (trial.message.isNotBlank()) add(trial.message)
}

@Composable
private fun GuideUi.TryNow(back: () -> Unit, s: net.atom.dpibypass.social.GuideSession, active: Boolean) {
    val surface = GuideFlow.surfaceFor(s, trial)
    val afterAuto = s.kind == SessionKind.TRIAL && trial.phase == Phase.FINISHED
    val t = (if (s.kind == SessionKind.TRIAL) trial.target else null) ?: target
    val specific = t != null && t.contentId != Target.HOME_CONTENT
    val cap = accName.replaceFirstChar { it.uppercase() }
    val b = browser()
    val webSupported = platform?.let { p -> custom || AccessRoutes.actions(p).any { AccessSurface.OFFICIAL_WEB in AccessRoutes.surfaces(p, it) } } ?: false
    val appMissing = surface == PendingSurface.APP && !custom && appPackage == null && device.loaded
    val title = when {
        s.kind == SessionKind.REVERIFY -> "Kısa tekrar kontrol"
        afterAuto -> "Bağlantı yolu açıldı. Şimdi $locName dene"
        else -> "Şimdi $locName dene"
    }
    val body = GuideText.task(a.action, a.primary, t?.url, specific, restart = s.kind == SessionKind.REVERIFY)
    val primary = when {
        surface == PendingSurface.TEST_WINDOW -> GuideAction("Sonucu söyle", { open(PendingSurface.TEST_WINDOW) }, tag = "try-open")
        appMissing && webSupported -> GuideAction("Tarayıcıda dene", {
            guide.switchSurface(PendingSurface.WEB, b)
            if (fresh().asksBrowser) guide.go(GuideStep.BROWSER)
        }, tag = "try-web")
        appMissing -> null
        surface == PendingSurface.WEB && b == null -> GuideAction("Tarayıcı seç", { guide.go(GuideStep.BROWSER) }, enabled = device.browsers.isNotEmpty(), tag = "try-browser")
        surface == PendingSurface.WEB -> GuideAction(if (custom) "Siteyi ${device.browserLabel(b) ?: "tarayıcı"} ile aç" else "$name · ${device.browserLabel(b) ?: "tarayıcı"} ile aç",
            { open(PendingSurface.WEB) }, tag = "try-open")
        else -> GuideAction("$cap aç", { open(PendingSurface.APP) }, tag = "try-open")
    }
    val secondary = buildList {
        if (custom && surface == PendingSurface.WEB) add(GuideAction("Kendim açtım, sonucu söyleyeceğim", { open(PendingSurface.APP) }))
        if (surface == PendingSurface.WEB && device.browsers.size > 1 && !afterAuto) add(GuideAction("Başka tarayıcı seç", { guide.go(GuideStep.BROWSER) }))
        add(GuideAction("Test edemiyorum", guide::cannotTest, tag = "try-cannot"))
        if (active) add(GuideAction("Durdur", guide::stop, tag = "session-stop"))
    }
    GuidePage(GuideStage.CHECK, title, body, back, onClose, onHelp, primary = primary, secondary = secondary) {
        if (s.kind == SessionKind.TRIAL && trial.attempted > 0 && !afterAuto) Tag("${trial.attempted}. yöntem")
        if (surface == PendingSurface.TEST_WINDOW)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) { ControlledWebProbe(trial, Modifier.fillMaxWidth().height(320.dp)) }
        if (appMissing) Note(if (webSupported) "$name bu telefonda kurulu değil. İstersen tarayıcıda deneyebilirsin; kendiliğinden bir şey indirmiyoruz."
            else "$name bu telefonda kurulu değil ve bu işlem tarayıcıda yapılamıyor. Uygulamayı kendin kurarsan buradan devam edebilirsin.", error = true, icon = Icons.Rounded.ErrorOutline)
        if (a.action == AccessAction.MESSAGING || a.action == AccessAction.POSTING || a.action == AccessAction.VOICE_CALL)
            Note("Biz hiçbir şey göndermeyiz ya da aramayız; denemek senin kararın.", icon = Icons.Rounded.Info)
        GuideText.launchError(s.error)?.let { Note(it, error = true, icon = Icons.Rounded.ErrorOutline) }
    }
}

@Composable
private fun GuideUi.Paused(back: () -> Unit, stop: GuideAction) {
    val stage = GuideStage.CHECK
    val resume = { guide.engineAction { resume() } }
    when (trial.pauseReason) {
        "COOLDOWN" -> {
            val key = trial.targets.firstOrNull { true }?.key ?: target?.key
            // Kalan süre bir kez okunur (sayfa açılınca ve motor durumu değişince); arka planda sayaç çalışmaz.
            val minutesState = remember(trial.version) { mutableStateOf<Long?>(null) }
            LaunchedEffect(trial.version) {
                minutesState.value = withContext(Dispatchers.IO) {
                    trial.targets.maxOfOrNull { SocialAccessRuntime.adapter.cooldownRemainingMillis(it.key) } ?: key?.let { SocialAccessRuntime.adapter.cooldownRemainingMillis(it) }
                }?.let { (it + 59_999) / 60_000 }
            }
            val minutes = minutesState.value
            GuidePage(stage, "Kısa süre beklememiz istendi", "Bekleme bitmeden yeni istek gönderilmez." +
                (minutes?.takeIf { it > 0 }?.let { " Yaklaşık $it dakika sonra tekrar dene." } ?: ""), back, onClose, onHelp,
                primary = GuideAction("Tekrar dene", resume, tag = "paused-resume"),
                secondary = listOf(GuideAction("Şimdilik çık", onClose)))
        }
        "BUDGET_LIMIT" -> GuidePage(stage, "Bu turun deneme sınırı doldu", "Yarım kalan yöntem başarısız sayılmadı.", back, onClose, onHelp,
            primary = GuideAction("Kalanları dene", resume, tag = "paused-resume"), secondary = listOf(stop))
        "PERMISSION_REQUIRED" -> GuidePage(stage, "Bazı yöntemler için bağlantı gerekiyor", "Bağlantıyı açınca kalan yöntemler denenir.", back, onClose, onHelp,
            primary = if (engineConnected) GuideAction("Kalanları dene", resume, tag = "paused-resume")
                else GuideAction("Bağlantıyı hazırla", { viewModel.clearVpnPermissionDenied(); onConnect() }, tag = "paused-connect"),
            secondary = listOf(stop))
        "USER_UNABLE_TO_TEST" -> GuidePage(stage, "Test edilemedi olarak işaretlendi", "Bu başarısızlık sayılmadı ve geçici değişiklik geri alındı.", back, onClose, onHelp,
            primary = GuideAction("Sıradaki yöntemi dene", resume, tag = "paused-resume"),
            secondary = listOf(GuideAction("Şimdilik bırak", guide::endSession)))
        "USER_INACTIVE" -> GuidePage(stage, "Uzun süre yanıt gelmedi", "Geçici değişiklik geri alındı; başarısızlık yazılmadı.", back, onClose, onHelp,
            primary = GuideAction("Kaldığın yerden devam et", resume, tag = "paused-resume"),
            secondary = listOf(GuideAction("Şimdilik bırak", guide::endSession)))
        "CONTEXT_CHANGED" -> GuidePage(stage, "Bağlantın değişti", "Deneme geri alındı. Aynı cevaplarla kısa kontrolü yeniden başlatalım.", back, onClose, onHelp,
            primary = GuideAction("Yeniden başlat", { guide.startTrial(mode(), others(), browser()) }, tag = "paused-restart"),
            secondary = listOf(GuideAction("Şimdilik çık", onClose)))
        "NETWORK_UNAVAILABLE" -> GuidePage(stage, "İnternet bağlantısı yok", "Ağ gelince aynı cevaplarla yeniden başlatabilirsin.", back, onClose, onHelp,
            primary = GuideAction("Yeniden başlat", { guide.startTrial(mode(), others(), browser()) }, enabled = networkUsable, tag = "paused-restart"),
            secondary = listOf(GuideAction("Şimdilik çık", onClose)))
        "KEEP_FAILED" -> GuidePage(stage, "Ayar kalıcılaştırılamadı", "Geçici değişiklik geri alındı; başarı kaydedilmedi.", back, onClose, onHelp,
            primary = GuideAction("Tekrar dene", resume, tag = "paused-resume"), secondary = listOf(stop))
        else -> GuidePage(stage, "Deneme duraklatıldı", SocialText.pause(trial.pauseReason) ?: "Devam etmek için dokun.", back, onClose, onHelp,
            primary = GuideAction("Devam et", resume, tag = "paused-resume"), secondary = listOf(stop))
    }
}

/** Kısa tekrar doğrulama en erken ne zaman anlamlı (aynı bağlamda ≥10 dk). */
private fun GuideUi.reverifyAt(): Long? {
    val d = decision() ?: return null
    if (d.verdict != RouteVerdict.TENTATIVE_ROUTE || d.recommended?.status != RouteStatus.SINGLE_CONFIRMATION) return null
    return d.recommended.lastAtMs + AccessRoutes.REPEAT_GAP_MS
}

@Composable
private fun GuideUi.Result(back: () -> Unit, last: Submission?) {
    val s = gs.session!!
    val trialResult = s.kind == SessionKind.TRIAL && trial.sessionId == s.trialSessionId
    val outcome: RouteOutcome? = last?.takeIf { !trialResult || it.trialSessionId == trial.sessionId }?.outcome
    val reason = if (trialResult) trial.finishReason else null
    val done = GuideAction("Tamam", guide::endSession, tag = "result-done")
    val d = decision()
    val webUntested = d?.untested?.contains(AccessSurface.OFFICIAL_WEB) == true && !custom
    val checkWeb = GuideAction("Tarayıcıda da kontrol et", {
        guide.answers { copy(web = true) }
        guide.startRouteCheck(PendingSurface.WEB, browser())
        if (fresh().asksBrowser) guide.go(GuideStep.BROWSER)
    }, tag = "result-web")
    val tryMethods = GuideAction("Bağlantı yöntemlerini dene", {
        guide.answers { copy(problem = if (problem == GuideProblem.VERIFICATION || problem == null) GuideProblem.NOT_OPENING else problem, baseline = null) }
        guide.jump(GuideStep.PICK_METHOD)
        fresh().continueSetup()
    }, tag = "result-methods")
    val saveFailed = last?.route == SocialGuideController.ROUTE_WRITE_FAILED
    val successNoun = GuideText.actionNoun(a.action)

    val success = reason in setOf("RESOLVED_USER_REPORTED", "PARTIAL_ACCEPTED") || outcome == RouteOutcome.WORKS &&
        (reason == null || reason == "NETWORK_VERIFIED_AUTO" || reason == "RESOLVED_AUTO_OBSERVED")
    when {
        success -> {
            val partial = reason == "PARTIAL_ACCEPTED"
            val accountUnconfirmed = a.action?.needsAccount == true && last?.account != AccountContext.SAME_ACCOUNT_CONFIRMED
            val verified = d?.verdict == RouteVerdict.VERIFIED_ROUTE
            val at = reverifyAt()
            val now = System.currentTimeMillis()
            GuidePage(GuideStage.RESULT,
                if (partial) "$locName bir kısmı çalışıyor" else "$locName $successNoun çalıştı.",
                if (accountUnconfirmed) "Bu senin bildirimin. Kendi hesabınla olduğunu teyit etmediğin için hesap sonucu belirsiz kaldı."
                else "Bu senin bildirimin. Bu ayarla devam edebilirsin.",
                null, onClose, onHelp,
                primary = GuideAction("Bu ayarla devam et", guide::endSession, tag = "result-keep"),
                secondary = buildList {
                    if (at != null) add(GuideAction(if (now >= at) "İstersen kısa bir tekrar kontrol yap" else "Tekrar kontrol ${time(at)}'dan sonra açılır",
                        { guide.startRouteCheck(last?.surface ?: s.surface, last?.packageName?.takeIf { last.surface == PendingSurface.WEB } ?: browser(), SessionKind.REVERIFY) },
                        enabled = now >= at, tag = "result-reverify"))
                    trial.candidate?.strategyId?.takeIf { trialResult && trial.resourceDisposition == "KEPT_ACTIVE" && trial.plan.strategyId == it }?.let { id ->
                        add(GuideAction("Bu ayarı ana bağlantı ayarım da yap", { viewModel.keepSocialStrategy(id) }, tag = "result-manual"))
                    }
                },
            ) {
                Tag(if (verified) "Ayrı zamanlarda doğrulandı" else "İlk denemede çalıştı")
                if (saveFailed) RetrySave(last!!)
                else if (last?.route?.startsWith("REJECTED_") == true) Note("Sonuç bu bağlantı kaydına yazılamadı (${routeRejection(last.route)}).", icon = Icons.Rounded.Info)
                if (trialResult && trial.resourceDisposition != "KEPT_ACTIVE" && trial.candidate?.strategyId != null)
                    Note("Ayar kalıcılaştırılamadı; kaydedildi diyemiyoruz.", error = true)
                Details(details())
            }
        }

        reason == "RESOLVED_AUTO_OBSERVED" -> GuidePage(GuideStage.RESULT, "Test penceresinde içerik açıldı",
            "Bu pencere hesabına bağlı değil ve doğrudan bağlantı kullanır; uygulamanın çalıştığını göstermez.", null, onClose, onHelp,
            primary = GuideAction("Uygulamada da kontrol et", guide::requestAppCheck, tag = "result-appcheck"),
            secondary = listOf(GuideAction("Bende hâlâ sorun var", { guide.engineAction { correctAutoPass() } }), done)) { Details(details()) }

        reason == "ALREADY_ACCESSIBLE" -> GuidePage(GuideStage.RESULT, "Şu anda çalışıyor",
            "Sorun şu an görünmüyor. Yöntem denenmedi ve bir başarı yazılmadı.", null, onClose, onHelp,
            primary = done.copy(label = "Tamam"),
            secondary = listOf(GuideAction("Yine de test et", { guide.pickBaseline(net.atom.dpibypass.social.Baseline.UNKNOWN); guide.jump(GuideStep.PICK_METHOD) }, tag = "result-retest")))

        reason == "PLATFORM_GATE" || outcome == RouteOutcome.VERIFICATION_REQUIRED -> {
            val cause = d?.surfaces?.firstOrNull { it.surface == (last?.surface?.accessSurface() ?: AccessSurface.OFFICIAL_APP) }?.gateCause
            GuidePage(GuideStage.RESULT, "Bu işlem için platform doğrulama istiyor", GuideText.gate(cause), null, onClose, onHelp,
                primary = when {
                    webUntested -> checkWeb
                    cause == net.atom.dpibypass.social.GateCause.NETWORK_PROBLEM_SUSPECTED -> tryMethods
                    else -> done
                },
                secondary = listOfNotNull(done.takeIf { webUntested || cause == net.atom.dpibypass.social.GateCause.NETWORK_PROBLEM_SUSPECTED })) {
                if (d?.verdict == RouteVerdict.ALL_SURFACES_GATED) Note(d.explanation)
                if (saveFailed) RetrySave(last!!)
            }
        }

        outcome == RouteOutcome.PARTIAL -> GuidePage(GuideStage.RESULT, "Bir kısmı çalıştı", "Böyle kullanabilir ya da diğer yolu deneyebilirsin.", null, onClose, onHelp,
            primary = GuideAction("Böyle kullan", guide::endSession, tag = "result-keep"),
            secondary = listOfNotNull(if (webUntested) checkWeb.copy(label = "Diğer yolu dene") else tryMethods.copy(label = "Diğer yolu dene"))) {
            if (saveFailed) RetrySave(last!!)
        }

        outcome == RouteOutcome.UNSURE -> GuidePage(GuideStage.RESULT, "Emin olamadın; sorun değil", "Bu bir başarısızlık olarak kaydedilmedi.", null, onClose, onHelp,
            primary = GuideAction("Tekrar dene", { guide.redoCheck(); guide.clearLast() }, tag = "result-retry"), secondary = listOf(done))

        outcome == RouteOutcome.NOT_WORKING && !trialResult -> GuidePage(GuideStage.RESULT, "Çalışmadı", "Bu yol şu anda işe yaramadı.", null, onClose, onHelp,
            primary = if (GuideFlow.routeCheckOnly(a) && !webUntested) tryMethods else if (webUntested) checkWeb.copy(label = "Tarayıcıda dene") else tryMethods,
            secondary = listOfNotNull(tryMethods.takeIf { webUntested }, done)) {
            if (saveFailed) RetrySave(last!!)
        }

        reason == "CANDIDATES_EXHAUSTED" || reason == "NO_APPLICABLE_METHODS" -> GuidePage(GuideStage.RESULT, "Bu ağda çalışan bir yöntem bulunamadı",
            if (reason == "NO_APPLICABLE_METHODS") "Bu seçimle denenebilecek bir yöntem yoktu." else "Denenen yöntemlerin hiçbiri $locName işe yaramadı.",
            null, onClose, onHelp,
            primary = if (!a.web && !custom && platform?.let { p -> AccessRoutes.actions(p).any { AccessSurface.OFFICIAL_WEB in AccessRoutes.surfaces(p, it) } } == true)
                GuideAction("Tarayıcıda dene", {
                    guide.answers { copy(web = true) }
                    guide.jump(GuideStep.PICK_METHOD)
                    fresh().continueSetup()
                }, tag = "result-web")
                else done,
            secondary = listOf(GuideAction("Tanılama", onOpenDiagnostics), done.copy(label = "Sonra tekrar dene"))) { Details(details()) }

        reason == "AUTO_UNAVAILABLE" || reason == "AUTO_INCONCLUSIVE" -> GuidePage(GuideStage.RESULT, "Otomatik kontrol karar veremedi",
            "Bazı yöntemler senin kontrolünü gerektiriyor.", null, onClose, onHelp,
            primary = GuideAction("Adım adım devam et", {
                viewModel.saveSocialModeOnly(net.atom.dpibypass.social.TestMode.MANUAL)
                guide.engineAction { setMode(net.atom.dpibypass.social.TestMode.MANUAL); manual() }
            }, tag = "result-manual-continue"),
            secondary = listOf(done)) { Details(details()) }

        reason == "CANCELLED" -> GuidePage(GuideStage.RESULT, "Deneme durduruldu", "Geçici değişiklikler geri alındı. Önceden kaydedilen ayarın korunuyor.", null, onClose, onHelp,
            primary = done, secondary = listOf(GuideAction("Yeniden başlat", { guide.startTrial(mode(), others(), browser()) }, tag = "result-restart")))

        else -> GuidePage(GuideStage.RESULT, "Kontrol bitti", trial.message.take(200).ifBlank { null }, null, onClose, onHelp, primary = done) { Details(details()) }
    }
}

/** Yol kaydı diske yazılamadı: "kaydedildi" denmez; yalnız kayıt yeniden denenir. */
@Composable
private fun GuideUi.RetrySave(last: Submission) {
    Note("Sonuç diske yazılamadı; kaydedildi diyemiyoruz.", error = true, icon = Icons.Rounded.ErrorOutline)
    if (last.action == null || last.context == null) return
    OptionRow("Kaydı tekrar dene", onClick = guide::retryRouteSave, tag = "result-retry-save")
}

private fun routeRejection(code: String): String = when (code.removePrefix("REJECTED_")) {
    "CONTEXT_CHANGED" -> "bağlantı değişti"
    "SURFACE_NOT_SUPPORTED", "ACTION_NOT_SUPPORTED" -> "bu yol bu işlem için desteklenmiyor"
    "FUTURE_TIME" -> "cihaz saati tutarsız"
    else -> "geçersiz bildirim"
}

private fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(ms))
