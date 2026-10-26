package net.atom.dpibypass.social

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ---------------------------------------------------------------------------
// Sosyal Erişim rehberi — saf model.
//
// Eski ekran Durum, Uygulamalar, Erişim yolu, Plan, Seçenekler, Başlat, Deneme,
// Test penceresi ve Tanılama'yı tek sayfada topluyordu. Rehber aynı motoru
// (ExperimentCoordinator + erişim yolu kaydı) kullanır ama kararları SIRAYA koyar:
// her adım tek bir soru ya da görevdir.
//
// Ayrı tutulan dört şey:
//  - kullanıcının cevapları ([GuideAnswers]) — geri dönmek onları silmez
//  - gösterilen adım ([GuideState.steps]) — yalnız kullanıcı adımları; deneme
//    sırasında gösterilen sayfa motorun gerçek durumundan TÜRETİLİR ([GuideFlow.view])
//  - dışarıda açılan test ([PendingExternalTest]) — açılış anındaki hedef, işlem,
//    yüzey, paket, bağlam ve deneme kimliği; dönüşte yalnız ona rapor alınır
//  - kalıcı kanıt — motorun sonuçları ve erişim yolu kaydı (burada tutulmaz)
//
// Android'e bağımlı değildir: geçişler JVM testlerinde sınanır.
// ---------------------------------------------------------------------------

/** Üstteki aşama göstergesi. Koşullu akışta sabit "3/8" sayacı yerine kullanılır. */
enum class GuideStage(val label: String) { PREPARATION("Hazırlık"), CHECK("Kontrol"), RESULT("Sonuç") }

/** Kullanıcının ilerlettiği adımlar. Deneme sırasındaki sayfalar [SessionView]'dır. */
enum class GuideStep(val stage: GuideStage) {
    WELCOME(GuideStage.PREPARATION),
    PICK_APP(GuideStage.PREPARATION),
    OTHER_APPS(GuideStage.PREPARATION),
    CUSTOM_TARGET(GuideStage.PREPARATION),
    PICK_ACTION(GuideStage.PREPARATION),
    CONTENT_LINK(GuideStage.PREPARATION),
    PICK_PROBLEM(GuideStage.PREPARATION),
    PREPARE(GuideStage.PREPARATION),
    BROWSER(GuideStage.PREPARATION),
    PRECHECK(GuideStage.CHECK),
    PICK_METHOD(GuideStage.CHECK),
    METERED(GuideStage.CHECK),
    SESSION(GuideStage.CHECK);

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** "Sorun ne?" cevapları ve motorun sorun sınıfı. */
enum class GuideProblem(val problemClass: String, val label: String) {
    NOT_OPENING("NETWORK_UNREACHABLE", "Hiç açılmıyor"),
    MEDIA("MEDIA_PATH_FAILURE", "Açılıyor ama video yüklenmiyor"),
    SLOW("POSSIBLE_THROTTLING", "Çok yavaş"),
    VERIFICATION("AGE_OR_ID_VERIFICATION_REQUIRED", "Giriş veya doğrulama istiyor"),
    UNSURE("UNKNOWN", "Emin değilim");

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** "Şu anda çalışıyor mu?" — denemeden önceki durum. Sonradan gelen sonuçla karıştırılmaz. */
enum class Baseline { NOT_WORKING, WORKING, UNKNOWN;
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** Dışarıda açılan yüzey. Test penceresi resmî yüzey değildir ve erişim yolu kaydına yazılmaz. */
enum class PendingSurface { APP, WEB, TEST_WINDOW;
    fun accessSurface(): AccessSurface? = when (this) { APP -> AccessSurface.OFFICIAL_APP; WEB -> AccessSurface.OFFICIAL_WEB; TEST_WINDOW -> null }
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/**
 * Kullanıcının cevapları. Hiçbiri tek başına kanıt değildir.
 * [action] null ve [general] true: "Emin değilim, genel kontrol yap".
 */
data class GuideAnswers(
    val primary: String? = null,
    val action: AccessAction? = null,
    val general: Boolean = false,
    val contentLink: String = "",
    val problem: GuideProblem? = null,
    val baseline: Baseline? = null,
    /** Kullanıcı tarayıcı yolunu açıkça seçti ya da uygulama kurulu değil. */
    val web: Boolean = false,
    val meteredAllowed: Boolean? = null,
) {
    val actionAnswered: Boolean get() = action != null || general
}

/**
 * Dışarıda açılmış tek bir test. Dönüşte toplanan rapor YALNIZ buna yazılır;
 * bağlam, deneme ya da hedef değiştiyse rapor alınmaz, kısa kontrol yinelenir.
 */
data class PendingExternalTest(
    val id: String,
    val guideSessionId: String,
    val platformId: String,
    val targetKey: String,
    val url: String,
    val action: AccessAction?,
    val surface: PendingSurface,
    /** Gerçekten açılan paket (uygulama ya da tarayıcı); test penceresinde null. */
    val packageName: String?,
    val context: RouteContext,
    val trialSessionId: String?,
    val trialAttemptId: String?,
    val trialOperationId: String?,
    /** Paket açılış anında tünelde miydi? */
    val inTunnel: Boolean,
    val openedAtMs: Long,
) {
    val needsAccount: Boolean get() = action?.needsAccount == true
    val trialBound: Boolean get() = trialAttemptId != null
}

/** Bir oturumun türü. */
enum class SessionKind {
    /** Motor yöntemleri dener (ExperimentCoordinator turu). */
    TRIAL,
    /** Motoru değiştirmeden seçilen yolu aç → sonucu kaydet. */
    ROUTE_CHECK,
    /** İsteğe bağlı kısa tekrar doğrulama (ayrı zaman + yeniden başlatma beyanı). */
    REVERIFY;

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** Gönderilmiş tek bildirimin sonucu. */
data class Submission(
    val pendingId: String,
    val outcome: RouteOutcome,
    val account: AccountContext?,
    val surface: PendingSurface,
    val packageName: String?,
    val action: AccessAction?,
    /** Erişim yolu kaydına yazma sonucu; null: bu bildirim kayda yazılacak türden değil. */
    val route: String?,
    /** Motor bildirimi kabul etti mi? null: motora gidecek bildirim yoktu. */
    val engineAccepted: Boolean?,
    val trialSessionId: String?,
    val atMs: Long,
    /** Testin AÇILDIĞI bağlam: kayıt yeniden denenirse yalnız bu bağlama yazılabilir. */
    val context: RouteContext? = null,
    val inTunnel: Boolean = false,
    val fresh: Boolean = false,
)

data class GuideSession(
    val id: String,
    val kind: SessionKind,
    /** Bu rehberin başlattığı motor turu (yalnız TRIAL). */
    val trialSessionId: String? = null,
    val surface: PendingSurface = PendingSurface.APP,
    val browserPackage: String? = null,
    val pending: PendingExternalTest? = null,
    val answer: RouteOutcome? = null,
    val account: AccountContext? = null,
    val fresh: Boolean? = null,
    val submitting: Boolean = false,
    val submitted: Set<String> = emptySet(),
    val last: Submission? = null,
    /** Kullanıcı test penceresi başarısından sonra uygulamada da kontrol etmek istedi. */
    val appCheckRequested: Boolean = false,
    /** Açılış ya da başlatma hatası (yeniden deneme ile temizlenir). */
    val error: String? = null,
    /** Bildirim gönderilirken bağlam/deneme değişti: kısa kontrol yinelenmeli. */
    val stale: Boolean = false,
)

/** Rehberin tamamı. [steps] geri yığınıdır; son eleman gösterilen adımdır. */
data class GuideState(
    val steps: List<GuideStep> = listOf(GuideStep.WELCOME),
    val answers: GuideAnswers = GuideAnswers(),
    val session: GuideSession? = null,
) {
    val step: GuideStep get() = steps.last()
}

/** Deneme sırasında gösterilen sayfa; motorun gerçek durumundan türetilir. */
enum class SessionView {
    RUNNING, OBSERVING, TRY_NOW, ASK_RESULT, ASK_ACCOUNT, ASK_FRESH, SUBMITTING, STALE,
    PARTIAL_CHOICE, PAUSED, RESULT, INTERRUPTED, RECOVERY,
}

/** Bağlantıyı hazırlarken giderilmesi gereken durumlar (yapılabilen kontrol kullanıcıya sorulmaz). */
enum class PrepIssue { NO_INTERNET, PERMISSION_DENIED, OTHER_VPN, CONNECTING, CONNECT_FAILED, ENGINE_OFF, INCLUDE_LIST_EMPTY, OUT_OF_SCOPE, NO_BROWSER }

object GuideFlow {
    /** Otomatik gözlemcinin (test penceresi) karar verebildiği işlemler. */
    private val ObservableActions = setOf(AccessAction.VIEW_CONTENT, AccessAction.PLAY_MEDIA)

    /** Kullanıcının seçebileceği işlemler; "Emin değilim" ayrıca sunulur. */
    fun actions(platform: SocialPlatform): List<AccessAction> = AccessRoutes.actions(platform)

    /** Belirli içerik bağlantısı yalnız içerik/video işleminde sorulur. */
    fun asksForLink(a: GuideAnswers): Boolean = a.action in ObservableActions && a.primary != SocialPlatform.CUSTOM.id

    /** Video seçeneği yalnız medyası olan platformda sunulur. */
    fun problems(platform: SocialPlatform?): List<GuideProblem> = GuideProblem.entries.filter {
        it != GuideProblem.MEDIA || platform?.mode(Capability.MEDIA) != CheckMode.UNSUPPORTED
    }

    /** "Sorun ne?" zaten bir sorun bildirdiyse başlangıç sorusu tekrar sorulmaz. */
    fun asksBaseline(a: GuideAnswers): Boolean = a.problem == GuideProblem.UNSURE

    /** Motorun "sorun başlangıçta var mı?" girdisi. */
    fun baselineStillPresent(a: GuideAnswers): Boolean? = when {
        a.problem != null && a.problem != GuideProblem.UNSURE -> true
        a.baseline == Baseline.NOT_WORKING -> true
        a.baseline == Baseline.WORKING -> false
        else -> null
    }

    /** Doğrulama sorunu sunucu tarafı olabilir: motor profilleri döndürülmez, yalnız yollar kontrol edilir. */
    fun routeCheckOnly(a: GuideAnswers): Boolean = a.problem == GuideProblem.VERIFICATION && a.primary != SocialPlatform.CUSTOM.id

    /** Anonim test penceresi yalnız herkese açık belirli içerikte ve otomatik kipte. */
    fun controlledWindow(a: GuideAnswers, mode: TestMode, target: Target?): Boolean =
        mode == TestMode.AUTO_BETA && a.action in ObservableActions && target != null && target.contentId != Target.HOME_CONTENT &&
            SocialPlatform.supportsAutoObserver(target.platformId)

    /** Ek veri izni sorusu: yalnız test penceresi kullanılacak ve bağlantı ölçülüyse. */
    fun asksMetered(a: GuideAnswers, mode: TestMode, target: Target?, metered: Boolean): Boolean =
        metered && controlledWindow(a, mode, target)

    /** Birincil hedef: özel alan adı, geçerli içerik bağlantısı ya da platformun ana adresi. */
    fun primaryTarget(a: GuideAnswers): Target? {
        val p = SocialPlatform.byId(a.primary) ?: return null
        if (p == SocialPlatform.CUSTOM) return Target.parse(p.id, a.contentLink)
        val link = a.contentLink.takeIf { it.isNotBlank() && asksForLink(a) }?.let { Target.parse(p.id, it) }
        return link ?: Target.home(p.id)
    }

    /** Bağlantı alanının durumu: boş, geçerli, başka platforma ait ya da geçersiz. */
    sealed interface LinkCheck {
        data object Empty : LinkCheck
        data class Valid(val target: Target) : LinkCheck
        data class OtherPlatform(val platform: SocialPlatform) : LinkCheck
        data object Invalid : LinkCheck
    }

    fun checkLink(platformId: String?, raw: String): LinkCheck {
        if (raw.isBlank()) return LinkCheck.Empty
        val p = SocialPlatform.byId(platformId) ?: return LinkCheck.Invalid
        val detected = Target.detectPlatform(raw)
        if (p == SocialPlatform.CUSTOM) {
            if (detected != null) return LinkCheck.OtherPlatform(detected)
            return Target.parse(p.id, raw)?.let { LinkCheck.Valid(it) } ?: LinkCheck.Invalid
        }
        if (detected != null && detected != p) return LinkCheck.OtherPlatform(detected)
        return Target.parse(p.id, raw)?.let { LinkCheck.Valid(it) } ?: LinkCheck.Invalid
    }

    /** Deneme hedefi: işlem gerçek Goal'e bağlanır; desteklenmeyen işlem otomatik "doğrulandı" olmaz. */
    fun goal(a: GuideAnswers, mode: TestMode, appInstalled: Boolean, target: Target?): Goal {
        val p = SocialPlatform.byId(a.primary) ?: SocialPlatform.CUSTOM
        val controlled = controlledWindow(a, mode, target)
        val capabilities = buildSet {
            add(a.action?.capability ?: "OPEN_APP")
            if (a.problem == GuideProblem.MEDIA) add(AccessAction.PLAY_MEDIA.capability)
        }
        val useWeb = a.web || !appInstalled && p != SocialPlatform.CUSTOM
        return Goal(
            platformId = p.id,
            problemClass = a.problem?.problemClass ?: "UNKNOWN",
            identityContext = when { controlled -> "ANONYMOUS"; a.action?.needsAccount == true -> "SAME_ACCOUNT"; else -> "UNKNOWN" },
            allowedSurfaces = buildSet {
                if (!useWeb) add("OFFICIAL_NATIVE_APP")
                if (useWeb) add("OFFICIAL_WEB")
                if (controlled) add("CONTROLLED_WEB_VIEW")
            },
            requiredCapabilities = capabilities,
            publicFallbackAllowed = controlled,
        )
    }

    /** Hazırlık sorunları, öncelik sırasıyla; boş liste hazır demektir. */
    fun prepIssues(
        networkUsable: Boolean,
        connection: net.atom.dpibypass.data.ConnectionState,
        permissionDenied: Boolean,
        otherVpn: Boolean,
        connectFailed: Boolean,
        includeListEmpty: Boolean,
        primaryOutOfScope: Boolean,
        needsBrowser: Boolean,
        browserAvailable: Boolean,
        needsEngine: Boolean,
    ): List<PrepIssue> = buildList {
        if (!networkUsable) add(PrepIssue.NO_INTERNET)
        if (needsBrowser && !browserAvailable) add(PrepIssue.NO_BROWSER)
        if (needsEngine) {
            when (connection) {
                net.atom.dpibypass.data.ConnectionState.Connected -> Unit
                net.atom.dpibypass.data.ConnectionState.Connecting, net.atom.dpibypass.data.ConnectionState.Testing -> add(PrepIssue.CONNECTING)
                else -> when {
                    permissionDenied -> add(PrepIssue.PERMISSION_DENIED)
                    connectFailed -> add(PrepIssue.CONNECT_FAILED)
                    otherVpn -> add(PrepIssue.OTHER_VPN)
                    else -> add(PrepIssue.ENGINE_OFF)
                }
            }
        }
        if (includeListEmpty) add(PrepIssue.INCLUDE_LIST_EMPTY)
        else if (primaryOutOfScope) add(PrepIssue.OUT_OF_SCOPE)
    }

    private val BusyPhases = setOf(Phase.BASELINING, Phase.SELECTING, Phase.PREPARING, Phase.APPLYING,
        Phase.PROBING, Phase.CANCELLING, Phase.ROLLING_BACK)

    /** Bekleyen test hâlâ bu denemeye ve bu bağlama mı ait? */
    fun pendingCurrent(p: PendingExternalTest, trial: TrialState, current: RouteContext?): Boolean {
        if (current != null && current != p.context) return false
        // Tur sonrası uygulama kontrolü: o tur hâlâ bitmiş ve aynı olmalı.
        if (!p.trialBound) return p.trialSessionId == null || trial.sessionId == p.trialSessionId && trial.phase == Phase.FINISHED
        return trial.sessionId == p.trialSessionId && trial.attemptId == p.trialAttemptId && trial.operationId == p.trialOperationId &&
            trial.phase == Phase.WAITING_FOR_USER
    }

    /** Motor turu sonrası uygulamada kontrol bekleniyor mu? (otomatik ağ PASS tek başına başarı değildir) */
    fun awaitingAppCheck(trial: TrialState, session: GuideSession): Boolean {
        if (trial.sessionId != session.trialSessionId || trial.phase != Phase.FINISHED) return false
        val confirmed = session.last?.trialSessionId == trial.sessionId && session.last.engineAccepted == true
        return when (trial.finishReason) {
            "NETWORK_VERIFIED_AUTO" -> !confirmed
            "RESOLVED_AUTO_OBSERVED" -> session.appCheckRequested && !confirmed
            else -> false
        }
    }

    /**
     * Deneme sırasında gösterilecek sayfa. Sıra önemlidir: kurtarma > gönderim >
     * bekleyen dış test > motorun gerçek aşaması. Ekran ileri-geri gidilince yeni
     * test başlatmaz; yalnız durumu okur.
     */
    fun view(state: GuideState, trial: TrialState, current: RouteContext?): SessionView {
        val s = state.session ?: return SessionView.INTERRUPTED
        if (trial.phase == Phase.RECOVERY_REQUIRED) return SessionView.RECOVERY
        if (s.submitting) return SessionView.SUBMITTING
        if (s.stale) return SessionView.STALE
        s.pending?.let { p ->
            if (!pendingCurrent(p, trial, current)) return SessionView.STALE
            return when {
                s.answer == null -> SessionView.ASK_RESULT
                p.needsAccount && s.account == null -> SessionView.ASK_ACCOUNT
                s.kind == SessionKind.REVERIFY && s.fresh == null -> SessionView.ASK_FRESH
                else -> SessionView.SUBMITTING
            }
        }
        if (s.kind != SessionKind.TRIAL) return if (s.last != null) SessionView.RESULT else SessionView.TRY_NOW
        if (s.trialSessionId == null || trial.sessionId != s.trialSessionId) return SessionView.INTERRUPTED
        return when {
            trial.phase in BusyPhases -> SessionView.RUNNING
            trial.phase == Phase.AUTO_EVALUATING || trial.phase == Phase.PAUSED && trial.pauseReason == "USER_ACTION_REQUIRED" -> SessionView.OBSERVING
            trial.phase == Phase.WAITING_FOR_USER -> if (trial.userOutcome == "PARTIAL") SessionView.PARTIAL_CHOICE else SessionView.TRY_NOW
            trial.phase == Phase.PAUSED -> SessionView.PAUSED
            trial.phase == Phase.FINISHED -> if (awaitingAppCheck(trial, s)) SessionView.TRY_NOW else SessionView.RESULT
            else -> SessionView.INTERRUPTED
        }
    }

    /** Aşama göstergesi: kullanıcı adımı ya da deneme sayfası. */
    fun stage(state: GuideState, view: SessionView?): GuideStage = when {
        state.step != GuideStep.SESSION -> state.step.stage
        view == SessionView.RESULT || view == SessionView.PARTIAL_CHOICE -> GuideStage.RESULT
        else -> GuideStage.CHECK
    }

    /** Dışarıda açılacak yüzey: denemenin adayı, yoksa oturumun seçtiği yüzey. */
    fun surfaceFor(session: GuideSession, trial: TrialState): PendingSurface = when (trial.candidate?.surface?.takeIf { session.kind == SessionKind.TRIAL && trial.phase == Phase.WAITING_FOR_USER }) {
        "OFFICIAL_WEB" -> PendingSurface.WEB
        "CONTROLLED_WEB_VIEW" -> PendingSurface.TEST_WINDOW
        "OFFICIAL_NATIVE_APP" -> PendingSurface.APP
        else -> session.surface
    }

    /** Kullanıcı cevabının motordaki karşılığı (deneme içi geri bildirim). */
    fun feedbackOutcome(o: RouteOutcome): String = when (o) {
        RouteOutcome.WORKS -> "RESOLVED"
        RouteOutcome.PARTIAL -> "PARTIAL"
        RouteOutcome.NOT_WORKING -> "STILL_PRESENT"
        RouteOutcome.VERIFICATION_REQUIRED -> "VERIFICATION_REQUIRED"
        RouteOutcome.UNSURE -> "UNSURE"
    }

    /** Tur dışı hedef bildirimi. */
    fun reportOutcome(o: RouteOutcome): String = feedbackOutcome(o)

    /** Tek kullanıcı bildiriminin yapacağı işler. Aynı bildirim iki ayrı kayda iki kez yazılmaz. */
    data class ReportPlan(val routeAction: AccessAction?, val routeSurface: AccessSurface?, val feedback: String?, val report: String?)

    fun plan(p: PendingExternalTest, outcome: RouteOutcome, account: AccountContext?): ReportPlan {
        val routeSurface = p.surface.accessSurface()
        val platform = SocialPlatform.byId(p.platformId)
        // Erişim yolu kaydı işlem başınadır: "genel kontrol" ve özel alan adı yazılmaz,
        // desteklenmeyen yüzey yazılmaz.
        val routeAction = p.action?.takeIf { platform != null && platform != SocialPlatform.CUSTOM && routeSurface != null &&
            routeSurface in AccessRoutes.surfaces(platform, it) }
        // Deneme sürerken bildirim o denemeye (geri bildirim); tur bittikten sonra
        // (otomatik ağ PASS sonrası) ya da yol kontrolünde hedef sonucuna (rapor) gider.
        // Test penceresinde değil, kullanıcının kendi yüzeyinde alınan bildirim hedefe yazılır.
        // Hesap işleminde kendi hesabıyla teyit edilmemiş "çalıştı", tur dışında hedefin
        // "çalışıyor" sonucuna yazılmaz (girişsiz içerik hesap başarısı değildir); yol
        // kaydında hesap bağlamıyla ayrı tutulur. Deneme içindeyse yöntem yine korunur
        // (uygulama açıldı) ama sonuç sayfası hesap sonucunu belirsiz gösterir.
        val accountUnconfirmed = p.needsAccount && account != AccountContext.SAME_ACCOUNT_CONFIRMED && outcome == RouteOutcome.WORKS
        val feedback = if (p.trialBound) feedbackOutcome(outcome) else null
        val report = if (!p.trialBound && p.surface != PendingSurface.TEST_WINDOW && !accountUnconfirmed) reportOutcome(outcome) else null
        return ReportPlan(routeAction, if (routeAction != null) routeSurface else null, feedback, report)
    }

    // ---- Kalıcılık (SavedStateHandle): süreç ölümünden sonra cevaplar geri gelir ----

    const val SCHEMA = 1

    fun encode(s: GuideState): String = JSONObject().put("schema", SCHEMA)
        .put("steps", JSONArray(s.steps.map { it.name }))
        .put("answers", JSONObject().put("primary", s.answers.primary ?: "").put("action", s.answers.action?.name ?: "")
            .put("general", s.answers.general).put("contentLink", s.answers.contentLink).put("problem", s.answers.problem?.name ?: "")
            .put("baseline", s.answers.baseline?.name ?: "").put("web", s.answers.web)
            .put("meteredAllowed", s.answers.meteredAllowed?.toString() ?: ""))
        .put("session", s.session?.let { encodeSession(it) } ?: JSONObject.NULL)
        .toString()

    private fun encodeSession(s: GuideSession) = JSONObject().put("id", s.id).put("kind", s.kind.name).put("trialSessionId", s.trialSessionId ?: "")
        .put("surface", s.surface.name).put("browserPackage", s.browserPackage ?: "")
        .put("pending", s.pending?.let { encodePending(it) } ?: JSONObject.NULL)
        .put("answer", s.answer?.name ?: "").put("account", s.account?.name ?: "").put("fresh", s.fresh?.toString() ?: "")
        .put("submitted", JSONArray(s.submitted.toList())).put("appCheckRequested", s.appCheckRequested).put("submitting", s.submitting)
        .put("last", s.last?.let { l -> JSONObject().put("pendingId", l.pendingId).put("outcome", l.outcome.name).put("account", l.account?.name ?: "")
            .put("surface", l.surface.name).put("packageName", l.packageName ?: "").put("action", l.action?.name ?: "")
            .put("route", l.route ?: "").put("engineAccepted", l.engineAccepted?.toString() ?: "").put("trialSessionId", l.trialSessionId ?: "")
            .put("atMs", l.atMs).put("inTunnel", l.inTunnel).put("fresh", l.fresh)
            .put("networkKey", l.context?.networkKey ?: "").put("profileKey", l.context?.profileKey ?: "").put("processId", l.context?.processId ?: "") } ?: JSONObject.NULL)

    private fun encodePending(p: PendingExternalTest) = JSONObject().put("id", p.id).put("guideSessionId", p.guideSessionId).put("platformId", p.platformId)
        .put("targetKey", p.targetKey).put("url", p.url).put("action", p.action?.name ?: "").put("surface", p.surface.name)
        .put("packageName", p.packageName ?: "").put("networkKey", p.context.networkKey).put("profileKey", p.context.profileKey)
        .put("processId", p.context.processId).put("trialSessionId", p.trialSessionId ?: "").put("trialAttemptId", p.trialAttemptId ?: "")
        .put("trialOperationId", p.trialOperationId ?: "").put("inTunnel", p.inTunnel).put("openedAtMs", p.openedAtMs)

    /**
     * Bozuk ya da başka sürümün yazdığı kayıt sessizce başarıya dönüşmez: okunamayan
     * kısım atılır, rehber karşılama adımından başlar. Gönderim sürüyorken ölen süreç
     * "gönderiliyor"da kalmaz.
     */
    fun decode(raw: String?): GuideState = runCatching {
        val j = JSONObject(raw ?: return GuideState())
        if (j.optInt("schema") != SCHEMA) return GuideState()
        val steps = j.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { GuideStep.parse(a.optString(it)) } }.orEmpty()
            .ifEmpty { listOf(GuideStep.WELCOME) }
        val a = j.optJSONObject("answers") ?: JSONObject()
        val answers = GuideAnswers(
            primary = a.optString("primary").takeIf { SocialPlatform.byId(it) != null },
            action = AccessAction.parse(a.optString("action")), general = a.optBoolean("general"),
            contentLink = a.optString("contentLink").take(2048), problem = GuideProblem.parse(a.optString("problem")),
            baseline = Baseline.parse(a.optString("baseline")), web = a.optBoolean("web"),
            meteredAllowed = a.optString("meteredAllowed").toBooleanStrictOrNull(),
        )
        val session = j.optJSONObject("session")?.let { decodeSession(it) }
        GuideState(if (session == null) steps.filter { it != GuideStep.SESSION }.ifEmpty { listOf(GuideStep.WELCOME) } else steps, answers, session)
    }.getOrDefault(GuideState())

    private fun decodeSession(o: JSONObject): GuideSession? {
        val kind = SessionKind.parse(o.optString("kind")) ?: return null
        val last = o.optJSONObject("last")?.let { l ->
            Submission(l.optString("pendingId"), RouteOutcome.parse(l.optString("outcome")) ?: return@let null,
                AccountContext.parse(l.optString("account")), PendingSurface.parse(l.optString("surface")) ?: PendingSurface.APP,
                l.optString("packageName").ifBlank { null }, AccessAction.parse(l.optString("action")), l.optString("route").ifBlank { null },
                l.optString("engineAccepted").toBooleanStrictOrNull(), l.optString("trialSessionId").ifBlank { null }, l.optLong("atMs"),
                RouteContext(l.optString("networkKey"), l.optString("profileKey"), l.optString("processId")).takeIf { it.networkKey.isNotBlank() && it.processId.isNotBlank() },
                l.optBoolean("inTunnel"), l.optBoolean("fresh"))
        }
        return GuideSession(
            id = o.optString("id").ifBlank { return null }, kind = kind, trialSessionId = o.optString("trialSessionId").ifBlank { null },
            surface = PendingSurface.parse(o.optString("surface")) ?: PendingSurface.APP, browserPackage = o.optString("browserPackage").ifBlank { null },
            pending = o.optJSONObject("pending")?.let { decodePending(it) },
            answer = RouteOutcome.parse(o.optString("answer")), account = AccountContext.parse(o.optString("account")),
            fresh = o.optString("fresh").toBooleanStrictOrNull(),
            submitted = o.optJSONArray("submitted")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty(),
            last = last, appCheckRequested = o.optBoolean("appCheckRequested"), submitting = o.optBoolean("submitting"),
        )
    }

    private fun decodePending(o: JSONObject): PendingExternalTest? {
        val surface = PendingSurface.parse(o.optString("surface")) ?: return null
        val ctx = RouteContext(o.optString("networkKey"), o.optString("profileKey"), o.optString("processId"))
        if (ctx.networkKey.isBlank() || ctx.processId.isBlank()) return null
        return PendingExternalTest(o.optString("id").ifBlank { return null }, o.optString("guideSessionId"), o.optString("platformId"),
            o.optString("targetKey"), o.optString("url"), AccessAction.parse(o.optString("action")), surface,
            o.optString("packageName").ifBlank { null }, ctx, o.optString("trialSessionId").ifBlank { null },
            o.optString("trialAttemptId").ifBlank { null }, o.optString("trialOperationId").ifBlank { null },
            o.optBoolean("inTunnel"), o.optLong("openedAtMs"))
    }

    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Erişim yolu bildiriminin bağlamı (hassas veri yok): ağ = taşıyıcı + ISS ailesi,
 * profil = motorun GERÇEKTEN çalıştırdığı ayar (deneme sürerken adayın ayarı) +
 * DNS/QUIC/kapsam/Vodafone + bağlam kuşağı, süreç = bu uygulama sürecinin kimliği.
 * Plan kaydının kendisi (bir defter bilgisi) profile girmez: deneme sırasında
 * alınan bildirim, aynı ayar kalıcılaştırılınca bayatlamaz.
 */
object RouteContexts {
    fun of(
        networkResolving: Boolean,
        transport: String,
        ispFamily: String?,
        contextGeneration: Long,
        engineConnected: Boolean,
        engineStrategy: String?,
        trial: TrialState,
        dnsKey: String,
        disableQuic: Boolean,
        scopeKey: String,
        vodafone: Boolean,
        processId: String,
    ): RouteContext? {
        if (networkResolving) return null
        val trialStrategy = trial.candidate?.strategyId?.takeIf { trial.resourceDisposition == "TRIAL_ACTIVE" }
        val engine = if (!engineConnected) "off" else trialStrategy ?: engineStrategy ?: "?"
        return RouteContext(
            networkKey = "$transport:${ispFamily ?: "unknown"}",
            profileKey = listOf("gen=$contextGeneration", "engine=$engine", "dns=$dnsKey", "quic=$disableQuic", "scope=$scopeKey", "vf=$vodafone").joinToString("|"),
            processId = processId,
        )
    }
}
