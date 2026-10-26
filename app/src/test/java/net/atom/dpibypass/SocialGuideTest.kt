package net.atom.dpibypass

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Rehber durum makinesi + GERÇEK ExperimentCoordinator. Motor, servisin
 * sözleşmesini izleyen bir taklittir (tek motor, taban + geçici aday,
 * commit/revert); Android/native yol ve gerçek platform hesapları bu testlerin
 * kapsamında DEĞİLDİR (bkz. docs/social-access-alpha.md, saha listesi).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocialGuideTest {
    private class Engine : TrialAdapter {
        val events = mutableListOf<String>()
        var base: String? = "P1"
        var trial: String? = null
        var trialOwner: String? = null
        var planOwner: String? = null
        var planPrevious: String? = null
        var commitWorks = true
        /** strategyId → PASS/FAIL (yerel yol); doğrudan yol her zaman [direct]. */
        val through = mutableMapOf<String?, String>()
        var direct = "FAIL"
        override fun available() = true
        override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
            check(trialOwner == null) { "single engine" }
            trialOwner = operationId; trial = candidate.strategyId; events += "apply:${candidate.strategyId ?: candidate.id}"
        }
        override suspend fun rollback(operationId: String): Boolean {
            if (trialOwner != null && trialOwner != operationId) return false
            trialOwner = null; trial = null; events += "rollback"; return true
        }
        override suspend fun commit(operationId: String): Boolean {
            if (!commitWorks || trialOwner != operationId) return false
            planPrevious = base; base = trial; planOwner = operationId; trialOwner = null; trial = null
            events += "commit:$base"; return true
        }
        override suspend fun revertPlan(operationId: String): Boolean {
            if (planOwner != operationId) return false
            base = planPrevious; planOwner = null; events += "revert:$base"; return true
        }
        override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence {
            val outcome = if (throughCandidate) through[trial ?: base] ?: "FAIL" else direct
            return NetworkEvidence(outcome, reason = if (outcome == "PASS") "THROUGH_CANDIDATE:HTTP_200:NETWORK_PATH_ONLY" else "THROUGH_CANDIDATE:TCP_RESET",
                failureClass = if (outcome == "PASS") "" else "TCP_RESET")
        }
        override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) {}
    }

    /** Rehberin dış dünyası: gerçek koordinatör + bellek içi yol kaydı (gerçek [AccessRoutes.record]/[AccessRoutes.commit]). */
    private class Env(val coordinator: ExperimentCoordinator, val engine: Engine) : GuideEnvironment {
        var ctx: RouteContext? = RouteContext("WIFI:TURKCELL", "gen=1|engine=P1", "proc-1")
        var now = 1_800_000_000_000L
        var ledger = RouteLedger()
        var writeFails = false
        var installed = setOf("instagram", "youtube", "signal")
        var measureHook: () -> Unit = {}
        var starts = 0
        val feedbacks = mutableListOf<String>()
        val reports = mutableListOf<String>()
        var routeWrites = 0
        var candidates = listOf("P2", "P3").map { CandidateDefinition(id = "N4-$it", family = "N4", displayName = "Yöntem $it", strategyId = it, surface = "OFFICIAL_NATIVE_APP") }
        override val trial: StateFlow<TrialState> get() = coordinator.state
        override fun currentContext() = ctx
        override fun nowMs() = now
        override fun metered() = false
        override fun appInstalled(platformId: String) = platformId in installed
        override fun inTunnel(packageName: String?) = packageName != null
        override fun catalog(goal: Goal) = candidates.map { it.copy(surface = if ("OFFICIAL_NATIVE_APP" in goal.allowedSurfaces) "OFFICIAL_NATIVE_APP" else "OFFICIAL_WEB") }
        override suspend fun setMode(mode: TestMode) {}
        override suspend fun start(goal: Goal, target: Target, mode: TestMode, baseline: Boolean?, metered: Boolean, allowMetered: Boolean,
            catalog: List<CandidateDefinition>, others: List<Target>): String? { starts++; return coordinator.start(goal, target, mode, baseline, metered, allowMetered, catalog, others) }
        override suspend fun feedback(event: Feedback): Boolean { feedbacks += event.outcome; return coordinator.feedback(event) }
        override suspend fun report(key: String, outcome: String): Boolean { reports += outcome; return coordinator.report(key, outcome) }
        override suspend fun stop() = coordinator.stop()
        override suspend fun resume() = coordinator.resume()
        override suspend fun correctAutoPass() = coordinator.correctAutoPass()
        override suspend fun retryRecovery() = coordinator.retryRecovery()
        override suspend fun manual() = coordinator.manual()
        override suspend fun recordRoute(platform: SocialPlatform, action: AccessAction, surface: AccessSurface, browser: String?, account: AccountContext,
            outcome: RouteOutcome, fresh: Boolean, inTunnel: Boolean, opened: RouteContext): RouteSave =
            AccessRoutes.record(platform, action, surface, browser, account, outcome, fresh, if (inTunnel) "TUNNEL" else "DIRECT", opened, { ctx }, { now },
                measure = { measureHook(); NetworkEvidence("PASS") },
                accept = { r, current, at ->
                    val (next, result) = AccessRoutes.commit(ledger, r, current, at) { if (writeFails) throw IOException("disk full"); routeWrites++ }
                    ledger = next; result
                })
    }

    private fun TestScope.setup(): Triple<SocialGuideController, Env, Engine> {
        val engine = Engine()
        val coordinator = ExperimentCoordinator(engine, backgroundScope).also { it.enabled(true) }
        val env = Env(coordinator, engine)
        return Triple(SocialGuideController(env, backgroundScope), env, engine)
    }

    private fun SocialGuideController.view(env: Env) = GuideFlow.view(state.value, env.trial.value, env.ctx)

    private fun SocialGuideController.setUpInstagram(action: AccessAction?, problem: GuideProblem) {
        pickApp("instagram"); pickAction(action)
        if (state.value.step == GuideStep.CONTENT_LINK) go(GuideStep.PICK_PROBLEM)
        pickProblem(problem)
    }

    // ---- Kabul senaryoları ----

    /** 1: sıfır kurulum → Instagram → genel içerik → otomatik → aç → dön → çalıştı → tek kayıt → aynı yolla yeniden açılır. */
    @Test fun firstRunAutoFlowRecordsOnceAndReopensSameRoute() = runTest {
        val (g, env, engine) = setup()
        engine.through["P2"] = "PASS"
        assertEquals(GuideStep.WELCOME, g.state.value.step)
        g.go(GuideStep.PICK_APP)
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        assertEquals("PICK_ACTION → (bağlantı sorusu) → PICK_PROBLEM", GuideStep.PICK_PROBLEM, g.state.value.step)
        g.startTrial(TestMode.AUTO_BETA, emptyList(), null); runCurrent()
        assertEquals("NETWORK_VERIFIED_AUTO", env.trial.value.finishReason)
        // Otomatik ağ PASS'i tek başına başarı değildir: uygulamada denemesi istenir.
        assertEquals(SessionView.TRY_NOW, g.view(env))
        g.opened(PendingSurface.APP, "com.instagram.android", launched = true)
        assertEquals(SessionView.ASK_RESULT, g.view(env))
        g.answer(RouteOutcome.WORKS); runCurrent()
        assertEquals(SessionView.RESULT, g.view(env))
        assertEquals(listOf("RESOLVED"), env.reports)
        assertTrue(env.feedbacks.isEmpty())
        assertEquals(1, env.ledger.reports.size)
        val d = AccessRoutes.decide(env.ledger, "instagram", AccessAction.VIEW_CONTENT, env.ctx!!)
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, d.verdict)
        assertEquals(RouteLaunch.App, AccessRoutes.launchFor(d.recommended!!, emptySet()))
        assertTrue(env.trial.value.plan.installed)
    }

    /** 2: önceki kurulum: tercih/plan/kayıt korunur; rehber zorla ilk adıma atmaz, kaldığı yere döner. */
    @Test fun existingUserReturnsWithoutRedoingSetup() = runTest {
        val (g, _, _) = setup()
        g.go(GuideStep.PICK_APP); g.pickApp("youtube"); g.pickAction(AccessAction.PLAY_MEDIA)
        val before = g.state.value
        g.onEnter()
        assertEquals(GuideStep.WELCOME, g.state.value.step)
        assertEquals(before.answers, g.state.value.answers)
        assertTrue(g.back())
        assertEquals(before.step, g.state.value.step)
        // Kayıt sürüm farkı / bozukluk: başarıya ya da yarım akışa dönüşmez.
        assertEquals(GuideState(), GuideFlow.decode("{bozuk"))
        assertEquals(GuideState(), GuideFlow.decode("""{"schema":99}"""))
    }

    /** 3: manuel → aday → dış test → çalışmadı → gerçek geri alma → sonraki aday → çalıştı → durur. */
    @Test fun manualFailureRollsBackThenNextCandidateThenStopsOnSuccess() = runTest {
        val (g, env, engine) = setup()
        g.setUpInstagram(AccessAction.ACCOUNT_ACCESS, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        assertEquals(SessionView.TRY_NOW, g.view(env))
        val firstAttempt = env.trial.value.attemptId
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        g.answer(RouteOutcome.NOT_WORKING)
        assertEquals("hesap işleminde hesap sorusu ayrı sorulur", SessionView.ASK_ACCOUNT, g.view(env))
        g.account(AccountContext.SAME_ACCOUNT_CONFIRMED); runCurrent()
        assertEquals(listOf("apply:P2", "rollback", "apply:P3"), engine.events.filter { it.startsWith("apply") || it == "rollback" })
        assertNotEquals(firstAttempt, env.trial.value.attemptId)
        assertEquals(SessionView.TRY_NOW, g.view(env))
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        g.answer(RouteOutcome.WORKS); g.account(AccountContext.SAME_ACCOUNT_CONFIRMED); runCurrent()
        assertEquals("RESOLVED_USER_REPORTED", env.trial.value.finishReason)
        assertEquals(SessionView.RESULT, g.view(env))
        assertEquals(listOf("STILL_PRESENT", "RESOLVED"), env.feedbacks)
        assertEquals(2, env.ledger.reports.size)
        assertEquals(2, engine.events.count { it.startsWith("apply") })
    }

    /** 4: otomatik ağ PASS, içerik FAIL → genel başarı yok; motor sıradaki yöntemle sürer. */
    @Test fun autoNetworkPassButAppFailureIsNotSuccess() = runTest {
        val (g, env, engine) = setup()
        engine.through["P2"] = "PASS"
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.AUTO_BETA, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        g.answer(RouteOutcome.NOT_WORKING); runCurrent()
        assertEquals(listOf("STILL_PRESENT"), env.reports)
        assertTrue("yanlış pozitif geri alındı", engine.events.contains("revert:P1"))
        assertNotEquals("RESOLVED_USER_REPORTED", env.trial.value.finishReason)
        assertNotEquals("NETWORK_VERIFIED_AUTO", env.trial.value.finishReason)
        // Sıradaki yöntem (P3) denendi; açılmadığı için tur dürüstçe "bulunamadı" ile bitti.
        assertTrue(engine.events.contains("apply:P3"))
        assertEquals("CANDIDATES_EXHAUSTED", env.trial.value.finishReason)
        assertEquals(RouteOutcome.NOT_WORKING, g.state.value.session!!.last!!.outcome)
        assertNull(env.trial.value.plan.strategyId)
    }

    /** 5: kısmi / emin değilim / doğrulama: her biri farklı ve çıkılabilir. */
    @Test fun partialUnsureAndVerificationAreDistinctAndExitable() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.PARTIAL); runCurrent()
        assertEquals(SessionView.PARTIAL_CHOICE, g.view(env))
        g.partialDecision(accept = true); runCurrent()
        assertEquals("PARTIAL_ACCEPTED", env.trial.value.finishReason)
        assertEquals(SessionView.RESULT, g.view(env))

        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.UNSURE); runCurrent()
        assertEquals(SessionView.PAUSED, g.view(env))
        assertEquals("USER_UNABLE_TO_TEST", env.trial.value.pauseReason)
        assertNull("emin değilim başarısızlık sayılmaz", env.trial.value.results["instagram"]?.content?.status?.takeIf { it == CheckStatus.FAIL })

        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.VERIFICATION_REQUIRED); runCurrent()
        assertEquals("PLATFORM_GATE", env.trial.value.finishReason)
        assertEquals(SessionView.RESULT, g.view(env))
        g.endSession()
        assertNull(g.state.value.session)
        assertEquals(GuideStep.WELCOME, g.state.value.step)
    }

    /** 6: hesap işleminde oturum kapalı/bilinmiyor → hesap başarısı yok, rapor kaybolmaz. */
    @Test fun accountActionWithoutSameAccountIsNotAccountSuccess() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.ACCOUNT_ACCESS, GuideProblem.VERIFICATION)
        assertTrue(GuideFlow.routeCheckOnly(g.state.value.answers))
        g.startRouteCheck(PendingSurface.APP, null)
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        g.answer(RouteOutcome.WORKS)
        assertEquals(SessionView.ASK_ACCOUNT, g.view(env))
        // Süreç bu soruda ölse bile cevap kaybolmaz.
        val restored = GuideFlow.decode(GuideFlow.encode(g.state.value))
        assertEquals(RouteOutcome.WORKS, restored.session?.answer)
        g.account(AccountContext.LOGGED_OUT); runCurrent()
        assertEquals(1, env.ledger.reports.size)
        assertTrue("teyitsiz hesap 'çalışıyor' hedef sonucuna yazılmaz", env.reports.isEmpty())
        val d = AccessRoutes.decide(env.ledger, "instagram", AccessAction.ACCOUNT_ACCESS, env.ctx!!)
        assertNull(d.recommended)
        assertNotEquals(RouteVerdict.VERIFIED_ROUTE, d.verdict)
    }

    /** 7: uygulama kurulu değil → tarayıcı yolu açıkça seçilir; açılamayan yüzey test kaydı düşmez. */
    @Test fun missingAppUsesExplicitWebAndFailedLaunchWritesNothing() = runTest {
        val (g, env, _) = setup()
        env.installed = emptySet()
        g.pickApp("instagram")
        assertTrue(g.state.value.answers.web)
        val goal = GuideFlow.goal(g.state.value.answers.copy(action = AccessAction.VIEW_CONTENT), TestMode.MANUAL, appInstalled = false, GuideFlow.primaryTarget(g.state.value.answers))
        assertEquals(setOf("OFFICIAL_WEB"), goal.allowedSurfaces)
        g.pickAction(AccessAction.ACCOUNT_ACCESS); g.pickProblem(GuideProblem.VERIFICATION)
        g.startRouteCheck(PendingSurface.WEB, null)
        g.opened(PendingSurface.WEB, null, launched = false, error = "NO_BROWSER")
        assertNull(g.state.value.session?.pending)
        assertEquals("NO_BROWSER", g.state.value.session?.error)
        assertEquals(SessionView.TRY_NOW, g.view(env))
    }

    /** 8: A tarayıcısındaki kanıt + B seçili → öneri A'yı açar; A yoksa yeniden kontrol. */
    @Test fun browserEvidenceStaysWithItsBrowser() {
        val ctx = RouteContext("WIFI:X", "p", "proc")
        val r = RouteReport("instagram", AccessAction.VIEW_CONTENT, AccessSurface.OFFICIAL_WEB, "browser.a", AccountContext.UNKNOWN, RouteOutcome.WORKS,
            ctx, "TUNNEL", "PASS", "", false, 1000)
        val ledger = AccessRoutes.accept(RouteLedger(), r, ctx, 1000).first
        val rec = AccessRoutes.decide(ledger, "instagram", AccessAction.VIEW_CONTENT, ctx).recommended!!
        assertEquals(RouteLaunch.Browser("browser.a"), AccessRoutes.launchFor(rec, setOf("browser.a", "browser.b")))
        assertEquals(RouteLaunch.BrowserMissing("browser.a"), AccessRoutes.launchFor(rec, setOf("browser.b")))
    }

    /** 9: bir yüzeyde doğrulama, desteklenen diğer yüzey denenmemiş → "bütün yollar" yok. */
    @Test fun gateOnOneSurfaceWithUntestedSupportedSurfaceIsNotAllGated() {
        val ctx = RouteContext("WIFI:X", "p", "proc")
        fun gate(p: String) = AccessRoutes.accept(RouteLedger(), RouteReport(p, AccessAction.ACCOUNT_ACCESS, AccessSurface.OFFICIAL_APP, null,
            AccountContext.SAME_ACCOUNT_CONFIRMED, RouteOutcome.VERIFICATION_REQUIRED, ctx, "TUNNEL", "PASS", "", false, 1000), ctx, 1000).first
        val instagram = AccessRoutes.decide(gate("instagram"), "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertNotEquals(RouteVerdict.ALL_SURFACES_GATED, instagram.verdict)
        assertEquals(listOf(AccessSurface.OFFICIAL_WEB), instagram.untested)
        // Signal'in telefonda web istemcisi yok: tek desteklenen yüzey doğrulama istediyse dürüstçe söylenir.
        val signal = AccessRoutes.decide(gate("signal"), "signal", AccessAction.ACCOUNT_ACCESS, ctx)
        assertEquals(RouteVerdict.ALL_SURFACES_GATED, signal.verdict)
        assertTrue(signal.untested.isEmpty())
        // Desteklenmeyen web işlemi kaydedilmez.
        assertEquals(RouteRejection.SURFACE_NOT_SUPPORTED, AccessRoutes.accept(RouteLedger(), RouteReport("signal", AccessAction.MESSAGING,
            AccessSurface.OFFICIAL_WEB, "b", AccountContext.UNKNOWN, RouteOutcome.WORKS, ctx, "DIRECT", "PASS", "", false, 1000), ctx, 1000).second)
    }

    /** 10: izin reddi, başka VPN, kapsam dışı, boş liste, ağ yok → hiçbirinde "hazır" yok. */
    @Test fun preparationIssuesNeverLookReady() {
        fun issues(connection: ConnectionState = ConnectionState.Connected, denied: Boolean = false, otherVpn: Boolean = false, failed: Boolean = false,
            empty: Boolean = false, out: Boolean = false, net: Boolean = true) =
            GuideFlow.prepIssues(net, connection, denied, otherVpn, failed, empty, out, needsBrowser = false, browserAvailable = true, needsEngine = true)
        assertEquals(emptyList<PrepIssue>(), issues())
        assertEquals(listOf(PrepIssue.ENGINE_OFF), issues(ConnectionState.Disconnected))
        assertEquals(listOf(PrepIssue.PERMISSION_DENIED), issues(ConnectionState.Disconnected, denied = true))
        assertEquals(listOf(PrepIssue.OTHER_VPN), issues(ConnectionState.Disconnected, otherVpn = true))
        assertEquals(listOf(PrepIssue.CONNECT_FAILED), issues(ConnectionState.Failed, failed = true))
        assertEquals(listOf(PrepIssue.CONNECTING), issues(ConnectionState.Connecting))
        assertEquals(listOf(PrepIssue.INCLUDE_LIST_EMPTY), issues(empty = true, out = true))
        assertEquals(listOf(PrepIssue.OUT_OF_SCOPE), issues(out = true))
        assertEquals(PrepIssue.NO_INTERNET, issues(net = false).first())
        assertEquals(listOf(PrepIssue.NO_BROWSER), GuideFlow.prepIssues(true, ConnectionState.Connected, false, false, false, false, false,
            needsBrowser = true, browserAvailable = false, needsEngine = true))
    }

    /** 11: çift dokunuş / hızlı tekrar → tek deneme, tek rapor; ileri-geri test başlatmaz. */
    @Test fun doubleTapsProduceSingleTrialAndSingleReport() = runTest {
        val (g, env, engine) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        assertEquals(1, env.starts)
        g.back(); g.go(GuideStep.SESSION); runCurrent()
        assertEquals(1, engine.events.count { it.startsWith("apply") })
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        g.answer(RouteOutcome.WORKS); g.answer(RouteOutcome.NOT_WORKING); g.submit(); runCurrent()
        assertEquals(listOf("RESOLVED"), env.feedbacks)
        assertEquals(1, env.ledger.reports.size)
        g.submit(); runCurrent()
        assertEquals(1, env.routeWrites)
    }

    /** 12: test açıldıktan sonra ağ/plan değişimi ya da deneme değişimi → gecikmiş rapor yeni bağlama yazılmaz. */
    @Test fun lateReportAfterContextOrAttemptChangeIsNotWritten() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        env.ctx = env.ctx!!.copy(networkKey = "CELLULAR:VODAFONE")
        assertEquals(SessionView.STALE, g.view(env))
        g.answer(RouteOutcome.WORKS); runCurrent()
        assertTrue(env.ledger.reports.isEmpty())
        assertTrue(env.feedbacks.isEmpty())
        assertEquals(Phase.WAITING_FOR_USER, env.trial.value.phase)
        // Kısa kontrol yinelenir; kurulum sıfırlanmaz.
        g.redoCheck()
        assertEquals(AccessAction.VIEW_CONTENT, g.state.value.answers.action)
        assertEquals(SessionView.TRY_NOW, g.view(env))
        // Deneme değişti (ör. durduruldu): eski açılışın raporu yazılmaz.
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        env.coordinator.stop(); runCurrent()
        assertEquals(SessionView.STALE, g.view(env))
    }

    /** 13: rapor ölçülürken bağlam değişti → kabul anında reddedilir, doğru mesaj, eski kanıt yok. */
    @Test fun contextChangeDuringMeasurementIsRejectedAtAcceptance() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        env.measureHook = { env.ctx = env.ctx!!.copy(profileKey = "gen=2|engine=P1") }
        g.answer(RouteOutcome.WORKS); runCurrent()
        assertTrue(env.ledger.reports.isEmpty())
        assertTrue("motor da bu bildirimi almadı", env.feedbacks.isEmpty())
        assertTrue(g.state.value.session!!.stale)
        assertEquals(SessionView.STALE, g.view(env))
    }

    /** 14: süreç ölümü: cevaplar geri gelir; yarım gönderim ve eski açılış körlemesine kullanılmaz. */
    @Test fun processDeathRestoresAnswersButNotPendingPass() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        val saved = GuideFlow.encode(g.state.value.let { it.copy(session = it.session!!.copy(answer = RouteOutcome.WORKS, submitting = true)) })
        // Yeni süreç: yeni koordinatör (motor ve deneme süreç kapsamlı), yeni süreç kimliği.
        val engine2 = Engine()
        val coordinator2 = ExperimentCoordinator(engine2, backgroundScope).also { it.enabled(true) }
        val env2 = Env(coordinator2, engine2).apply { ctx = env.ctx!!.copy(processId = "proc-2") }
        val g2 = SocialGuideController(env2, backgroundScope, GuideFlow.decode(saved))
        runCurrent()
        assertEquals(AccessAction.VIEW_CONTENT, g2.state.value.answers.action)
        assertEquals("instagram", g2.state.value.answers.primary)
        assertEquals(SessionView.STALE, GuideFlow.view(g2.state.value, env2.trial.value, env2.ctx))
        g2.submit(); runCurrent()
        assertTrue(env2.ledger.reports.isEmpty())
        assertTrue(env2.feedbacks.isEmpty())
        g2.redoCheck()
        assertEquals("eski deneme yok: yeniden başlatma önerilir", SessionView.INTERRUPTED, GuideFlow.view(g2.state.value, env2.trial.value, env2.ctx))
    }

    /** 17: ilk başarı hemen kullanılabilir; 10 dk ve yeniden başlatma beyanı olmadan "doğrulandı" yok. */
    @Test fun reverifyNeedsGapAndUserDeclaredFreshStart() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.VERIFICATION)
        g.startRouteCheck(PendingSurface.APP, null)
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.WORKS); runCurrent()
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, AccessRoutes.decide(env.ledger, "instagram", AccessAction.VIEW_CONTENT, env.ctx!!).verdict)
        // 5 dk sonra ikinci onay (yeniden başlatma beyanıyla) bile yetmez.
        env.now += 5 * 60_000
        g.startRouteCheck(PendingSurface.APP, null, SessionKind.REVERIFY)
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.WORKS)
        assertEquals("yeniden başlatma kullanıcıya sorulur", SessionView.ASK_FRESH, g.view(env))
        g.fresh(true); runCurrent()
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, AccessRoutes.decide(env.ledger, "instagram", AccessAction.VIEW_CONTENT, env.ctx!!).verdict)
        env.now += 6 * 60_000
        g.startRouteCheck(PendingSurface.APP, null, SessionKind.REVERIFY)
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.WORKS); g.fresh(false); runCurrent()
        assertEquals("beyan olmadan da aralık tek başına yetmez; ama önceki beyan seride", RouteVerdict.VERIFIED_ROUTE,
            AccessRoutes.decide(env.ledger, "instagram", AccessAction.VIEW_CONTENT, env.ctx!!).verdict)
    }

    /** 18: durdur, planı kaldır, özelliği kapat ve rehberden çık farklı etkiler üretir. */
    @Test fun stopClearPlanDisableAndLeaveAreDistinct() = runTest {
        val (g, env, engine) = setup()
        engine.through["P2"] = "PASS"
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.AUTO_BETA, emptyList(), null); runCurrent()
        assertEquals("P2", env.trial.value.plan.strategyId)
        g.endSession(); runCurrent()
        assertEquals("rehberden çıkmak planı etkilemez", "P2", env.trial.value.plan.strategyId)
        g.stop(); runCurrent()
        assertEquals("durdur planı korur", "P2", env.trial.value.plan.strategyId)
        env.coordinator.enabled(false); runCurrent()
        assertEquals("özelliği kapatmak planı korur", "P2", env.trial.value.plan.strategyId)
        env.coordinator.enabled(true); runCurrent()
        env.coordinator.clearPlan(); runCurrent()
        assertNull("planı kaldırmak planı kaldırır", env.trial.value.plan.strategyId)
        assertTrue(engine.events.contains("revert:P1"))
    }

    /** 19: kayıt hatası "kaydedildi" ya da "ağ değişti" diye gösterilmez; yalnız kayıt yeniden denenir. */
    @Test fun writeFailureIsReportedAsSuchAndRetriesOnlyTheWrite() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        env.writeFails = true
        g.answer(RouteOutcome.WORKS); runCurrent()
        val last = g.state.value.session!!.last!!
        assertEquals(SocialGuideController.ROUTE_WRITE_FAILED, last.route)
        assertTrue(env.ledger.reports.isEmpty())
        assertEquals(listOf("RESOLVED"), env.feedbacks)
        env.writeFails = false
        g.retryRouteSave(); runCurrent()
        assertEquals(SocialGuideController.ROUTE_SAVED, g.state.value.session!!.last!!.route)
        assertEquals(1, env.ledger.reports.size)
        assertEquals("motor bildirimi tekrarlanmaz", listOf("RESOLVED"), env.feedbacks)
    }

    /** 19b: bağlam değiştikten sonra "kaydı tekrar dene" eski bildirimi yeni bağlama yazamaz. */
    @Test fun retryAfterContextChangeCannotMoveEvidence() = runTest {
        val (g, env, _) = setup()
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.VERIFICATION)
        g.startRouteCheck(PendingSurface.APP, null)
        g.opened(PendingSurface.APP, "com.instagram.android", true)
        env.writeFails = true
        g.answer(RouteOutcome.WORKS); runCurrent()
        assertEquals(SocialGuideController.ROUTE_WRITE_FAILED, g.state.value.session!!.last!!.route)
        env.writeFails = false
        env.ctx = env.ctx!!.copy(networkKey = "CELLULAR:VODAFONE")
        g.retryRouteSave(); runCurrent()
        assertEquals("REJECTED_CONTEXT_CHANGED", g.state.value.session!!.last!!.route)
        assertTrue(env.ledger.reports.isEmpty())
    }

    /** 20: anonim test penceresi ve ek veri sorusu yalnız ilgili koşullarda; manuel yol korunur. */
    @Test fun controlledWindowAndMeteredQuestionAreConditional() {
        val a = GuideAnswers(primary = "youtube", action = AccessAction.PLAY_MEDIA, contentLink = "https://youtu.be/abcdefghijk")
        val t = GuideFlow.primaryTarget(a)
        assertTrue(GuideFlow.controlledWindow(a, TestMode.AUTO_BETA, t))
        assertFalse(GuideFlow.controlledWindow(a, TestMode.MANUAL, t))
        assertFalse(GuideFlow.controlledWindow(a.copy(action = AccessAction.ACCOUNT_ACCESS), TestMode.AUTO_BETA, t))
        assertFalse(GuideFlow.controlledWindow(a.copy(contentLink = ""), TestMode.AUTO_BETA, GuideFlow.primaryTarget(a.copy(contentLink = ""))))
        assertTrue(GuideFlow.asksMetered(a, TestMode.AUTO_BETA, t, metered = true))
        assertFalse(GuideFlow.asksMetered(a, TestMode.AUTO_BETA, t, metered = false))
        val goal = GuideFlow.goal(a, TestMode.AUTO_BETA, appInstalled = true, t)
        assertEquals("ANONYMOUS", goal.identityContext)
        assertTrue("CONTROLLED_WEB_VIEW" in goal.allowedSurfaces)
        assertTrue(goal.requiredCapabilities.contains("PLAY_MEDIA"))
        val account = GuideFlow.goal(a.copy(action = AccessAction.MESSAGING), TestMode.AUTO_BETA, appInstalled = true, t)
        assertEquals("SAME_ACCOUNT", account.identityContext)
        assertEquals(setOf("SEND_MESSAGE"), account.requiredCapabilities)
        assertFalse("CONTROLLED_WEB_VIEW" in account.allowedSurfaces)
    }

    // ---- Ayrıca ----

    @Test fun genuinelyDifferentPlatformLinkIsNotSilentlyTested() {
        assertTrue(GuideFlow.checkLink("instagram", "https://www.youtube.com/watch?v=abcdefghijk") is GuideFlow.LinkCheck.OtherPlatform)
        assertEquals(GuideFlow.LinkCheck.Invalid, GuideFlow.checkLink("instagram", "https://www.instagram.com/"))
        assertTrue(GuideFlow.checkLink("instagram", "https://www.instagram.com/p/Cabc123/") is GuideFlow.LinkCheck.Valid)
        assertTrue(GuideFlow.checkLink("custom", "https://x.com/a") is GuideFlow.LinkCheck.OtherPlatform)
        assertEquals(GuideFlow.LinkCheck.Empty, GuideFlow.checkLink("youtube", ""))
        // Bağlantı yalnız içerik/video işleminde sorulur; hesap işlemine taşınmaz.
        val a = GuideAnswers(primary = "instagram", action = AccessAction.ACCOUNT_ACCESS, contentLink = "https://www.instagram.com/p/Cabc123/")
        assertEquals(Target.HOME_CONTENT, GuideFlow.primaryTarget(a)!!.contentId)
    }

    @Test fun baselineQuestionOnlyWhenProblemUnknown() {
        assertFalse(GuideFlow.asksBaseline(GuideAnswers(problem = GuideProblem.NOT_OPENING)))
        assertEquals(true, GuideFlow.baselineStillPresent(GuideAnswers(problem = GuideProblem.MEDIA)))
        assertTrue(GuideFlow.asksBaseline(GuideAnswers(problem = GuideProblem.UNSURE)))
        assertNull(GuideFlow.baselineStillPresent(GuideAnswers(problem = GuideProblem.UNSURE, baseline = Baseline.UNKNOWN)))
        assertEquals(false, GuideFlow.baselineStillPresent(GuideAnswers(problem = GuideProblem.UNSURE, baseline = Baseline.WORKING)))
    }

    @Test fun startBlockedByCooldownIsShownNotHung() = runTest {
        val (g, env, _) = setup()
        val engine = env.engine
        engine.direct = "FAIL"
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        val blocked = object : TrialAdapter by engine {
            override fun cooldownRemainingMillis(key: String) = 60_000L
        }
        val c = ExperimentCoordinator(blocked, backgroundScope).also { it.enabled(true) }
        val env2 = Env(c, engine)
        val g2 = SocialGuideController(env2, backgroundScope, g.state.value)
        g2.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        assertNull(g2.state.value.session!!.trialSessionId)
        assertEquals("NOT_STARTED", g2.state.value.session!!.error)
        assertEquals(SessionView.INTERRUPTED, GuideFlow.view(g2.state.value, env2.trial.value, env2.ctx))
        assertEquals("COOLDOWN", c.state.value.pauseReason)
    }

    @Test fun keepFailureDoesNotHangInPreparing() = runTest {
        val (g, env, engine) = setup()
        engine.commitWorks = false
        g.setUpInstagram(AccessAction.VIEW_CONTENT, GuideProblem.NOT_OPENING)
        g.startTrial(TestMode.MANUAL, emptyList(), null); runCurrent()
        g.opened(PendingSurface.APP, "com.instagram.android", true); g.answer(RouteOutcome.WORKS); runCurrent()
        assertEquals(Phase.PAUSED, env.trial.value.phase)
        assertEquals("KEEP_FAILED", env.trial.value.pauseReason)
        assertEquals(SessionView.PAUSED, g.view(env))
        assertNull(env.trial.value.plan.strategyId)
        engine.commitWorks = true
        g.engineAction { resume() }; runCurrent()
        assertEquals("aynı aday yeniden denenir", "P2", env.trial.value.candidate?.strategyId)
        assertEquals(Phase.WAITING_FOR_USER, env.trial.value.phase)
    }

    @Test fun routeContextFollowsRealEngineSettingNotPlanBookkeeping() {
        val trial = TrialState(candidate = CandidateDefinition(id = "N4-P3", family = "N4", displayName = "P3", strategyId = "P3"), resourceDisposition = "TRIAL_ACTIVE")
        fun ctx(t: TrialState, engine: String?) = RouteContexts.of(false, "WIFI", "TURKCELL", 3, true, engine, t, "cf", false, "All:0", false, "proc")!!
        val during = ctx(trial, "P1")
        val afterKeep = ctx(trial.copy(resourceDisposition = "KEPT_ACTIVE", plan = PlanState("P3", "op", true)), "P3")
        assertEquals("deneme sırasında alınan bildirim, aynı ayar kalıcılaşınca bayatlamaz", during, afterKeep)
        assertNotEquals(during, ctx(TrialState(), "P1"))
        assertNull(RouteContexts.of(true, "WIFI", null, 1, true, "P1", TrialState(), "cf", false, "All:0", false, "proc"))
    }
}
