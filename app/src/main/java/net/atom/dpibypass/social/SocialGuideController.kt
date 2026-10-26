package net.atom.dpibypass.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rehberin dış dünyası. Uygulamada [ExperimentCoordinator] + erişim yolu kaydı +
 * ayarlar; testte aynı koordinatör sahte bir adaptörle kullanılır. İkinci bir
 * deney yöneticisi YOKTUR: rehber yalnız mevcut motora niyet iletir.
 */
interface GuideEnvironment {
    val trial: StateFlow<TrialState>
    /** Şu anki erişim yolu bağlamı; ISS çözülürken null. */
    fun currentContext(): RouteContext?
    fun nowMs(): Long
    fun metered(): Boolean
    fun appInstalled(platformId: String): Boolean
    /** Paket tünelde mi (motor açık ve kapsamda)? */
    fun inTunnel(packageName: String?): Boolean
    /** Hedefe göre aday yöntemler (ISS ailesine göre sıralı). */
    fun catalog(goal: Goal): List<CandidateDefinition>
    suspend fun setMode(mode: TestMode)
    suspend fun start(goal: Goal, target: Target, mode: TestMode, baseline: Boolean?, metered: Boolean, allowMetered: Boolean,
        catalog: List<CandidateDefinition>, others: List<Target>): String?
    suspend fun feedback(event: Feedback): Boolean
    suspend fun report(key: String, outcome: String): Boolean
    suspend fun stop()
    suspend fun resume()
    suspend fun correctAutoPass()
    /** Otomatik kipten "adım adım" kipe geçer; ertelenen yöntemler kullanıcıya sorulur. */
    suspend fun manual()
    suspend fun retryRecovery()
    suspend fun recordRoute(platform: SocialPlatform, action: AccessAction, surface: AccessSurface, browser: String?, account: AccountContext,
        outcome: RouteOutcome, fresh: Boolean, inTunnel: Boolean, opened: RouteContext): RouteSave
}

/**
 * Rehberin durum makinesi. Kullanıcı niyetleri senkron olarak durumu değiştirir;
 * motor/kayıt yan etkileri [scope]'ta, tek sahipli ve tekil ([busy] bayrakları,
 * gönderilmiş bildirim kimlikleri) yürür. Composable'lar burada hiçbir şeyi
 * yeniden kompozisyon sırasında başlatmaz.
 */
class SocialGuideController(
    private val env: GuideEnvironment,
    private val scope: CoroutineScope,
    initial: GuideState = GuideState(),
    private val persist: (GuideState) -> Unit = {},
) {
    private val mutable = MutableStateFlow(recover(initial))
    val state: StateFlow<GuideState> = mutable.asStateFlow()
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var acting = false

    /** Süreç ölümünden sonra yarım kalan "gönderiliyor" durumu kör bir tekrar üretmez. */
    private fun recover(s: GuideState): GuideState {
        val session = s.session ?: return s
        return if (session.submitting) s.copy(session = session.copy(submitting = false, stale = true)) else s
    }

    private fun set(next: GuideState) {
        mutable.value = next
        runCatching { persist(next) }
    }

    private inline fun update(block: GuideState.() -> GuideState) = set(mutable.value.block())
    private inline fun updateSession(block: GuideSession.() -> GuideSession) {
        val s = mutable.value.session ?: return
        set(mutable.value.copy(session = s.block()))
    }

    // ---- Gezinme ----

    fun go(step: GuideStep) = update { if (step == this.step) this else copy(steps = steps + step) }

    /** Geri: cevaplar silinmez, yeni test başlamaz. Dönüş false ise yığının başındayız. */
    fun back(): Boolean {
        val s = mutable.value
        if (s.steps.size <= 1) return false
        set(s.copy(steps = s.steps.dropLast(1)))
        return true
    }

    /** Karşılamaya dön (kaldığın yer korunur; motor durdurulmaz). */
    fun home() = update { copy(steps = listOf(GuideStep.WELCOME)) }

    /**
     * Rehbere yeniden girildi: yarım kalan yerin üstüne karşılama konur. "Kaldığın
     * yerden devam et" yalnız geri yığınını açar; hiçbir test kendiliğinden yeniden çalışmaz.
     */
    fun onEnter() = update { if (step == GuideStep.WELCOME) this else copy(steps = steps + GuideStep.WELCOME) }

    /** Adım yığınını açıkça kurar (ör. sonuçtan "tarayıcıda dene" ile yöntem seçimine). Cevaplar korunur. */
    fun jump(vararg to: GuideStep) = update { copy(steps = listOf(GuideStep.WELCOME) + to.filter { it != GuideStep.WELCOME }) }

    /** Baştan başla: önceki cevaplar ön seçili kalır, adım yığını sıfırlanır. */
    fun startOver() = update { copy(steps = listOf(GuideStep.WELCOME, GuideStep.PICK_APP)) }

    /** Yarım kalmış oturuma dön: motor durumu yeniden okunur, eski test TEKRAR ÇALIŞTIRILMAZ. */
    fun resumeSession() = update { if (session == null) this else copy(steps = listOf(GuideStep.WELCOME, GuideStep.SESSION)) }

    // ---- Cevaplar ----

    fun answers(block: GuideAnswers.() -> GuideAnswers) = update { copy(answers = answers.block()) }

    fun pickApp(platformId: String) {
        val p = SocialPlatform.byId(platformId) ?: return
        val changed = mutable.value.answers.primary != platformId
        update {
            copy(answers = if (changed) GuideAnswers(primary = platformId, web = p != SocialPlatform.CUSTOM && !env.appInstalled(platformId))
                else answers.copy(web = answers.web || p != SocialPlatform.CUSTOM && !env.appInstalled(platformId)))
        }
        go(if (p == SocialPlatform.CUSTOM) GuideStep.CUSTOM_TARGET else GuideStep.PICK_ACTION)
    }

    fun pickAction(action: AccessAction?) {
        answers { copy(action = action, general = action == null) }
        go(if (GuideFlow.asksForLink(mutable.value.answers)) GuideStep.CONTENT_LINK else GuideStep.PICK_PROBLEM)
    }

    fun pickProblem(problem: GuideProblem) {
        answers { copy(problem = problem, baseline = if (problem == GuideProblem.UNSURE) baseline else null) }
    }

    fun pickBaseline(b: Baseline) = answers { copy(baseline = b) }

    // ---- Oturum başlatma ----

    /**
     * Motor turunu başlatır. Çift dokunuş ikinci turu başlatmaz ([starting]).
     * Seçili ayrıntılar (işlem, sorun, yüzey) gerçek Goal'e çevrilir.
     */
    fun startTrial(mode: TestMode, others: List<Target>, browser: String?) {
        if (starting) return
        val a = mutable.value.answers
        val target = GuideFlow.primaryTarget(a) ?: run { update { copy(session = GuideSession(GuideFlow.newId(), SessionKind.TRIAL, error = "TARGET_INVALID"), steps = steps.withSession()) }; return }
        starting = true
        val installed = env.appInstalled(target.platformId)
        val goal = GuideFlow.goal(a, mode, installed, target)
        // Özel alan adının resmî uygulaması bilinmez: varsayılan yüzey tarayıcıdır.
        val surface = if (a.primary != SocialPlatform.CUSTOM.id && "OFFICIAL_NATIVE_APP" in goal.allowedSurfaces) PendingSurface.APP else PendingSurface.WEB
        val session = GuideSession(GuideFlow.newId(), SessionKind.TRIAL, surface = surface, browserPackage = browser)
        update { copy(session = session, steps = steps.withSession()) }
        scope.launch {
            try {
                env.setMode(mode)
                val catalog = env.catalog(goal)
                val metered = env.metered()
                val id = env.start(goal, target, mode, GuideFlow.baselineStillPresent(a), metered, a.meteredAllowed == true, catalog,
                    others.filter { it.key != target.key })
                updateSession { if (this.id == session.id) copy(trialSessionId = id, error = if (id == null) "NOT_STARTED" else null) else this }
            } finally { starting = false }
        }
    }

    /**
     * Motoru değiştirmeden seçilen yüzeyi kontrol eder. Otomatik ağ PASS'i bekleyen
     * bir tur varsa bu kontrol O TURUN uygulama kontrolüdür (ayrı rapor yazılmaz).
     */
    fun startRouteCheck(surface: PendingSurface, browser: String?, kind: SessionKind = SessionKind.ROUTE_CHECK) {
        val a = mutable.value.answers
        val trial = env.trial.value
        val key = GuideFlow.primaryTarget(a)?.key
        val pendingTrial = trial.phase == Phase.FINISHED && trial.finishReason == "NETWORK_VERIFIED_AUTO" && trial.plan.installed && trial.target?.key == key
        val session = if (pendingTrial && kind == SessionKind.ROUTE_CHECK)
            GuideSession(GuideFlow.newId(), SessionKind.TRIAL, trialSessionId = trial.sessionId, surface = surface, browserPackage = browser)
        else GuideSession(GuideFlow.newId(), kind, surface = surface, browserPackage = browser)
        update { copy(session = session, steps = steps.withSession()) }
    }

    private fun List<GuideStep>.withSession() = if (lastOrNull() == GuideStep.SESSION) this else this + GuideStep.SESSION

    // ---- Dış test ----

    /**
     * Kullanıcı hedefi açtı. Açılış gerçekten olduysa bekleyen test, açılış anındaki
     * bağlam ve deneme kimliğiyle yazılır. Açılamadıysa test kaydı DÜŞÜLMEZ.
     */
    fun opened(surface: PendingSurface, packageName: String?, launched: Boolean, error: String? = null) {
        val st = mutable.value
        val session = st.session ?: return
        if (!launched) { updateSession { copy(error = error ?: "LAUNCH_FAILED") }; return }
        val ctx = env.currentContext() ?: run { updateSession { copy(error = "CONTEXT_UNKNOWN") }; return }
        val trial = env.trial.value
        val target = (if (session.kind == SessionKind.TRIAL && trial.sessionId == session.trialSessionId) trial.target else null)
            ?: GuideFlow.primaryTarget(st.answers) ?: return
        val bound = session.kind == SessionKind.TRIAL && trial.sessionId == session.trialSessionId && trial.phase == Phase.WAITING_FOR_USER
        val pending = PendingExternalTest(
            id = GuideFlow.newId(), guideSessionId = session.id, platformId = target.platformId, targetKey = target.key, url = target.url,
            action = st.answers.action, surface = surface, packageName = packageName, context = ctx,
            trialSessionId = if (session.kind == SessionKind.TRIAL) session.trialSessionId else null,
            trialAttemptId = if (bound) trial.attemptId else null, trialOperationId = if (bound) trial.operationId else null,
            inTunnel = env.inTunnel(packageName), openedAtMs = env.nowMs(),
        )
        updateSession { copy(pending = pending, answer = null, account = null, fresh = null, error = null, stale = false,
            browserPackage = if (surface == PendingSurface.WEB) packageName else browserPackage) }
    }

    fun answer(o: RouteOutcome) {
        val s = mutable.value.session ?: return
        val p = s.pending ?: return
        if (s.submitting || p.id in s.submitted) return
        updateSession { copy(answer = o) }
        maybeSubmit()
    }

    fun account(a: AccountContext) { updateSession { copy(account = a) }; maybeSubmit() }
    fun fresh(v: Boolean) { updateSession { copy(fresh = v) }; maybeSubmit() }

    private fun maybeSubmit() {
        val s = mutable.value.session ?: return
        val p = s.pending ?: return
        if (s.answer == null || p.needsAccount && s.account == null || s.kind == SessionKind.REVERIFY && s.fresh == null) return
        submit()
    }

    /**
     * Tek kullanıcı bildirimi → tek kayıt. Sıra:
     *  1. bekleyen test hâlâ bu bağlama ve bu denemeye ait mi? değilse hiçbir şey yazılmaz
     *  2. bildirim kimliği gönderildi diye işaretlenir (çift dokunuş/yarış ikinci kaydı üretmez)
     *  3. erişim yolu kaydı (ağ ölçümü + KABUL ANINDA bağlam kontrolü)
     *  4. motor: deneme içindeyse geri bildirim, tur dışındaysa hedef raporu
     * Kayıt diske yazılamazsa "kaydedildi" denmez; bağlam değiştiyse kısa kontrol yinelenir.
     */
    fun submit() {
        val st = mutable.value
        val s = st.session ?: return
        val p = s.pending ?: return
        val outcome = s.answer ?: return
        if (s.submitting || p.id in s.submitted) return
        val trial = env.trial.value
        if (!GuideFlow.pendingCurrent(p, trial, env.currentContext())) { updateSession { copy(stale = true) }; return }
        val snapshot = if (p.trialBound) trial.feedback(GuideFlow.feedbackOutcome(outcome)) else null
        val account = s.account ?: AccountContext.UNKNOWN
        updateSession { copy(submitting = true, submitted = submitted + p.id) }
        scope.launch {
            val plan = GuideFlow.plan(p, outcome, s.account)
            val route = runCatching {
                val platform = SocialPlatform.byId(p.platformId)
                if (plan.routeAction == null || plan.routeSurface == null || platform == null) null
                else env.recordRoute(platform, plan.routeAction, plan.routeSurface, if (p.surface == PendingSurface.WEB) p.packageName else null,
                    account, outcome, s.fresh == true, p.inTunnel, p.context)
            }.getOrElse { RouteSave.WriteFailed }
            if (route is RouteSave.Rejected && route.reason == RouteRejection.CONTEXT_CHANGED) {
                updateSession { if (pending?.id == p.id) copy(submitting = false, stale = true) else this }
                return@launch
            }
            val engine: Boolean? = when {
                snapshot != null -> runCatching { env.feedback(snapshot) }.getOrDefault(false)
                plan.report != null -> runCatching { env.report(p.targetKey, plan.report) }.getOrDefault(false)
                else -> null
            }
            if (snapshot != null && engine == false) {
                updateSession { if (pending?.id == p.id) copy(submitting = false, stale = true) else this }
                return@launch
            }
            val submission = Submission(p.id, outcome, s.account, p.surface, p.packageName, p.action, route?.let(::routeCode), engine,
                p.trialSessionId, env.nowMs(), p.context, p.inTunnel, s.fresh == true)
            updateSession { if (pending?.id == p.id) copy(submitting = false, pending = null, answer = null, account = null, fresh = null, last = submission) else this }
        }
    }

    /**
     * Diske yazılamayan yol kaydını yeniden dener; motor bildirimi tekrarlanmaz.
     * Kayıt yalnız testin AÇILDIĞI bağlama yazılabilir: bağlam değiştiyse reddedilir.
     */
    fun retryRouteSave() {
        val s = mutable.value.session ?: return
        val last = s.last ?: return
        val action = last.action ?: return
        val platform = SocialPlatform.byId(mutable.value.answers.primary)?.takeIf { it != SocialPlatform.CUSTOM } ?: return
        val surface = last.surface.accessSurface() ?: return
        val opened = last.context ?: return
        if (last.route != ROUTE_WRITE_FAILED || acting) return
        acting = true
        scope.launch {
            try {
                val r = runCatching { env.recordRoute(platform, action, surface, if (surface == AccessSurface.OFFICIAL_WEB) last.packageName else null,
                    last.account ?: AccountContext.UNKNOWN, last.outcome, last.fresh, last.inTunnel, opened) }.getOrElse { RouteSave.WriteFailed }
                updateSession { if (this.last?.pendingId == last.pendingId) copy(last = last.copy(route = routeCode(r))) else this }
            } finally { acting = false }
        }
    }

    /** Hesap/yeniden başlatma sorusundan bir önceki soruya dönüş (henüz hiçbir şey gönderilmedi). */
    fun answerBack() = updateSession { if (submitting) this else if (account != null || fresh != null) copy(account = null, fresh = null) else copy(answer = null) }

    /** Sonuç sayfasından aynı kısa kontrolü yeniden yapmak için son bildirimin gösterimini kaldırır (kayıt silinmez). */
    fun clearLast() = updateSession { copy(last = null) }

    /** Bağlam değişti: kurulum sıfırlanmaz, yalnız bu kısa kontrol yeniden açılır. */
    fun redoCheck() = updateSession { copy(pending = null, answer = null, account = null, fresh = null, stale = false, submitting = false, error = null) }

    // ---- Motor eylemleri (tek sahipli) ----

    /** "Böyle kullan" / "Diğer yolu dene": kısmi sonuçtan sonra tek karar. */
    fun partialDecision(accept: Boolean) {
        val trial = env.trial.value
        val s = mutable.value.session ?: return
        if (acting || s.kind != SessionKind.TRIAL || trial.sessionId != s.trialSessionId || trial.phase != Phase.WAITING_FOR_USER || trial.userOutcome != "PARTIAL") return
        acting = true
        scope.launch { try { env.feedback(trial.feedback(if (accept) "PARTIAL_ACCEPTED" else "STILL_PRESENT")) } finally { acting = false } }
    }

    /** Test penceresinde çalıştı: kullanıcı onayı (deneme içi tek geri bildirim). */
    fun observedWorks() {
        val trial = env.trial.value
        val s = mutable.value.session ?: return
        if (acting || trial.sessionId != s.trialSessionId) return
        acting = true
        scope.launch { try { env.feedback(trial.feedback("RESOLVED")) } finally { acting = false } }
    }

    /** "Durdur": yalnız geçici deneme geri alınır; onaylı plan korunur. Çift iptal üretmez. */
    fun stop() {
        if (stopping) return
        stopping = true
        scope.launch { try { env.stop() } finally { stopping = false } }
    }

    fun engineAction(block: suspend GuideEnvironment.() -> Unit) {
        if (acting) return
        acting = true
        scope.launch { try { env.block() } finally { acting = false } }
    }

    fun requestAppCheck() = updateSession { copy(appCheckRequested = true) }

    /** Açılış öncesinde yüzey değişimi (ör. uygulama kurulu değil → tarayıcı). Kanıt taşınmaz. */
    fun switchSurface(surface: PendingSurface, browser: String?) =
        updateSession { if (pending != null) this else copy(surface = surface, browserPackage = browser, error = null) }

    /**
     * "Test edemiyorum": deneme içindeyse bu deneme başarısız SAYILMADAN geri alınır
     * (motorun "emin değilim" yolu); yol kontrolünde hiçbir şey yazılmaz.
     */
    fun cannotTest() {
        val trial = env.trial.value
        val s = mutable.value.session ?: return
        if (s.pending != null) return
        if (s.kind == SessionKind.TRIAL && trial.sessionId == s.trialSessionId && trial.phase == Phase.WAITING_FOR_USER)
            engineAction { feedback(trial.feedback("UNSURE")) }
        else endSession()
    }

    fun clearError() = updateSession { copy(error = null) }

    /** Oturumu bırak (motor durdurulmaz; çalışan plan korunur). */
    fun endSession() = update { copy(session = null, steps = listOf(GuideStep.WELCOME)) }

    companion object {
        const val ROUTE_SAVED = "SAVED"
        const val ROUTE_WRITE_FAILED = "WRITE_FAILED"
        fun routeCode(r: RouteSave): String = when (r) {
            RouteSave.Saved -> ROUTE_SAVED
            RouteSave.WriteFailed -> ROUTE_WRITE_FAILED
            is RouteSave.Rejected -> "REJECTED_" + r.reason.name
        }
    }
}
