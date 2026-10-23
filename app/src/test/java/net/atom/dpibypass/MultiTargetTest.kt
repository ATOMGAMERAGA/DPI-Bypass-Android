package net.atom.dpibypass

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.junit.Assert.*
import org.junit.Test

/**
 * Çoklu uygulama planı, gerileme kontrolü, plan kalıcılığı ve otomatik ağ yolu
 * değerlendirmesi. Motor, servisin sözleşmesini (tek native motor, taban + geçici
 * aday, commit/revert) izleyen bir taklitle sınanır; gerçek Android/native yolu
 * bu testlerin kapsamında DEĞİLDİR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiTargetTest {
    private class Engine : TrialAdapter {
        val events = mutableListOf<String>()
        var base: String? = "P1"
        var trial: String? = null
        var trialOwner: String? = null
        var planOwner: String? = null
        var planPrevious: String? = null
        var connected = true
        var usable = true
        /** strategyId → platformId → PASS/FAIL (yerel SOCKS yolu). */
        val matrix = mutableMapOf<String?, Map<String, String>>()
        val direct = mutableMapOf<String, String>()
        var retryAfter = 0L
        var probeDelayMs = 0L
        override fun available() = connected
        override fun networkUsable() = usable
        override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
            check(trialOwner == null) { "single native engine: trial already owned" }
            trialOwner = operationId; trial = candidate.strategyId; events += "apply:${candidate.strategyId ?: candidate.id}"
        }
        override suspend fun rollback(operationId: String): Boolean {
            if (trialOwner != null && trialOwner != operationId) return false
            trialOwner = null; trial = null; events += "rollback"; return true
        }
        override suspend fun commit(operationId: String): Boolean {
            if (trialOwner != operationId) return false
            planPrevious = base; base = trial; planOwner = operationId; trialOwner = null; trial = null
            events += "commit:$base"; return true
        }
        override suspend fun revertPlan(operationId: String): Boolean {
            if (planOwner != operationId) return false
            base = planPrevious; planOwner = null; events += "revert:$base"; return true
        }
        override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence {
            if (probeDelayMs > 0) delay(probeDelayMs)
            events += "probe:${target.platformId}:${if (throughCandidate) "socks" else "direct"}"
            val outcome = if (!throughCandidate) direct[target.platformId] ?: "FAIL" else matrix[trial ?: base]?.get(target.platformId) ?: "FAIL"
            return NetworkEvidence(outcome, retryAfterSeconds = retryAfter,
                reason = (if (throughCandidate) "THROUGH_CANDIDATE" else "DIRECT_CONTROL") + if (outcome == "PASS") ":HTTP_200:NETWORK_PATH_ONLY" else ":TCP_RESET",
                failureClass = if (outcome == "PASS") "" else "TCP_RESET")
        }
        val persisted = mutableListOf<Pair<Int, String?>>()
        override fun persistResults(results: Collection<TargetResult>, planStrategyId: String?) { persisted += results.size to planStrategyId }
        override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) {}
    }

    private val yt = Target.home("youtube")!!
    private val ig = Target.home("instagram")!!
    private val dc = Target.home("discord")!!
    private val goal = Goal(platformId = "youtube", allowedSurfaces = setOf("OFFICIAL_NATIVE_APP"))
    private fun native(vararg ids: String) = ids.map { CandidateDefinition(id = "N4-$it", family = "N4", displayName = it, strategyId = it, surface = "OFFICIAL_NATIVE_APP") }
    private fun coordinator(a: TrialAdapter, scope: CoroutineScope) = ExperimentCoordinator(a, scope).also { it.enabled(true) }

    @Test fun candidateThatBreaksAWorkingAppIsRejectedAndCommonPlanIsKept() = runTest {
        // Discord P1 ile çalışıyor; YouTube ve Instagram kapalı. P2 YouTube'u açıyor ama
        // Discord'u bozuyor; P3 üçünü birden açıyor.
        val e = Engine().apply {
            matrix["P1"] = mapOf("discord" to "PASS")
            matrix["P2"] = mapOf("youtube" to "PASS", "instagram" to "PASS")
            matrix["P3"] = mapOf("youtube" to "PASS", "instagram" to "PASS", "discord" to "PASS")
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3"), listOf(ig, dc)); runCurrent()
        val s = c.state.value
        assertEquals("NETWORK_VERIFIED_AUTO", s.finishReason)
        assertEquals("P3", s.plan.strategyId)
        assertTrue(s.plan.installed)
        assertEquals("P3", e.base)
        // P2 Discord'u bozduğu için reddedildi ve geri alındı; asla commit edilmedi.
        assertEquals(listOf("apply:P2", "rollback", "apply:P3", "commit:P3"), e.events.filter { it.startsWith("apply") || it == "rollback" || it.startsWith("commit") })
        assertEquals("P2", s.results["discord"]?.conflict)
        // Her hedefin sonucu ayrı; hepsi aynı planla ölçüldü.
        for (key in listOf("youtube", "instagram", "discord")) {
            assertEquals(key, CheckStatus.PASS, s.results[key]?.network?.status)
            assertEquals(key, "P3", s.results[key]?.strategyId)
        }
        // Otomatik ağ ölçümü içerik başarısı sayılmaz.
        assertNotEquals(CheckStatus.PASS, s.results["youtube"]?.content?.status)
        assertEquals("NETWORK_PATH", s.causalAttribution)
    }

    @Test fun noCommonPlanKeepsBestPartialOnlyAfterSecondMeasurement() = runTest {
        val e = Engine().apply {
            matrix["P2"] = mapOf("youtube" to "PASS")
            matrix["P3"] = mapOf("instagram" to "PASS")
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3"), listOf(ig)); runCurrent()
        assertEquals("NETWORK_VERIFIED_AUTO", c.state.value.finishReason)
        assertEquals("P2", c.state.value.plan.strategyId)
        // Kısmi: Instagram bu planla açılmadı ve bu gizlenmedi.
        assertEquals(CheckStatus.FAIL, c.state.value.results["instagram"]?.network?.status)
        assertTrue(c.state.value.message.contains("kısmi"))
        assertEquals(2, e.events.count { it == "apply:P2" })
    }

    @Test fun unstableGainIsNotKept() = runTest {
        var calls = 0
        val e = object : TrialAdapter by Engine() {
            val inner = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS") }
            override fun available() = true
            override suspend fun apply(candidate: CandidateDefinition, operationId: String) = inner.apply(candidate, operationId)
            override suspend fun rollback(operationId: String) = inner.rollback(operationId)
            override suspend fun commit(operationId: String) = inner.commit(operationId)
            override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence {
                // Aday altında ilk ölçüm PASS, ikinci ölçüm FAIL: kararsız.
                if (throughCandidate && inner.trial == "P2") return NetworkEvidence(if (calls++ == 0) "PASS" else "FAIL")
                return inner.probe(target, throughCandidate)
            }
            override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) {}
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2"), emptyList()); runCurrent()
        assertNotEquals("NETWORK_VERIFIED_AUTO", c.state.value.finishReason)
        assertNull(c.state.value.plan.strategyId)
    }

    @Test fun webViewObservationCannotCreditANativeCandidate() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "FAIL") }
        val c = coordinator(e, backgroundScope)
        val video = Target.parse("youtube", "https://www.youtube.com/watch?v=abcdefghijk")!!
        c.start(goal, video, TestMode.MANUAL, true, false, false, native("P2")); runCurrent()
        assertEquals(Phase.WAITING_FOR_USER, c.state.value.phase)
        c.automatic()
        val s = c.state.value
        c.observe(Observation(sessionId = s.sessionId, attemptId = s.attemptId, operationId = s.operationId, contextGeneration = s.contextGeneration,
            modeGeneration = s.modeGeneration, documentGeneration = s.documentGeneration, targetRef = video.targetRef, goalHash = s.goal.hashCode().toString(),
            observerVersion = "1.0.0", evaluatorVersion = "1.0.0", actualSurface = "CONTROLLED_WEB_VIEW", identityContext = "SAME_ACCOUNT",
            identityEvidence = "PLATFORM_SIGNAL", routeProvenance = "EXACT_TARGET_PATH", targetMatched = Tri.TRUE, contentPresent = Tri.TRUE,
            blockingGate = Tri.FALSE, freshEvidence = Tri.TRUE, documentVisible = Tri.TRUE, observedCapabilities = setOf("VIEW_SELECTED_CONTENT"),
            stableSamples = 3, stableDurationMs = 6000))
        assertNull(c.state.value.finishReason)
        assertNull(c.state.value.plan.strategyId)
    }

    @Test fun settingsChangeKeepsPlanAndReverifiesOnceWithoutLoop() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.MANUAL, true, false, false, native("P2")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        assertEquals("P2", e.base)
        assertEquals(CheckStatus.PASS, c.state.value.results["youtube"]?.content?.status)
        e.events.clear()
        c.contextChanged(); runCurrent()
        assertTrue("Sonuç bayatlamalı", c.state.value.results["youtube"]!!.stale)
        assertEquals("P2", e.base)
        assertTrue(e.events.none { it == "rollback" || it.startsWith("revert") })
        advanceTimeBy(ExperimentCoordinator.REVERIFY_SETTLE_MS + 1); runCurrent()
        assertEquals(listOf("probe:youtube:socks"), e.events)
        assertFalse(c.state.value.results["youtube"]!!.stale)
        // Aynı bağlam kuşağında ikinci tur yok; profil döndürülmez.
        c.reverify(); runCurrent()
        assertEquals(1, e.events.size)
        // Plan ve onay korunur (aynı strateji, ağ yolu hâlâ açık).
        assertEquals(CheckStatus.PASS, c.state.value.results["youtube"]?.content?.status)
    }

    @Test fun reverifyReportsRegressionButDoesNotRotateProfiles() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.MANUAL, true, false, false, native("P2", "P3")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        e.matrix["P2"] = mapOf("youtube" to "FAIL") // yeni ağda artık çalışmıyor
        e.events.clear()
        c.contextChanged(); advanceTimeBy(ExperimentCoordinator.REVERIFY_SETTLE_MS + 1); runCurrent()
        assertEquals(CheckStatus.FAIL, c.state.value.results["youtube"]?.network?.status)
        assertTrue(c.state.value.message.contains("YouTube"))
        assertTrue(e.events.none { it.startsWith("apply") })
    }

    @Test fun userCorrectionOfAutoNetworkPassRevertsAndIsNotReCredited() = runTest {
        val e = Engine().apply {
            matrix["P2"] = mapOf("youtube" to "PASS")
            matrix["P3"] = mapOf("youtube" to "PASS")
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3")); runCurrent()
        assertEquals("P2", c.state.value.plan.strategyId)
        c.report("youtube", "STILL_PRESENT"); runCurrent()
        assertTrue(e.events.contains("revert:P1"))
        // Sıradaki adaya geçildi; reddedilen P2 yeniden başarı sayılmadı.
        assertEquals("P3", c.state.value.plan.strategyId)
        assertEquals(1, e.events.count { it == "commit:P2" })
    }

    @Test fun clearPlanIsExplicitAndRestoresPreviousEngineSetting() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.MANUAL, true, false, false, native("P2")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        c.stop(); runCurrent()
        assertEquals("Durdur yalnız geçici denemeyi geri alır", "P2", e.base)
        c.enabled(false); runCurrent()
        assertEquals("Özelliği kapatmak onaylı planı kaldırmaz", "P2", e.base)
        c.enabled(true); runCurrent()
        c.clearPlan(); runCurrent()
        assertEquals("P1", e.base)
        assertNull(c.state.value.plan.strategyId)
    }

    @Test fun engineRestartMarksPlanInstalledOnlyWhenEngineReallyUsesIt() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.MANUAL, true, false, false, native("P2")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        c.engineStopped(); runCurrent()
        assertFalse(c.state.value.plan.installed)
        assertTrue(c.state.value.results.values.all { it.stale })
        c.engineStarted("P1"); runCurrent()
        assertFalse("Bağlantı farklı strateji seçtiyse plan kurulu sayılmaz", c.state.value.plan.installed)
        c.engineStarted("P2"); runCurrent()
        assertTrue(c.state.value.plan.installed)
    }

    @Test fun checkMeasuresAllTargetsWithoutTouchingTheEngine() = runTest {
        val e = Engine().apply { matrix["P1"] = mapOf("discord" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.check(listOf(yt, ig, dc)); runCurrent()
        assertTrue(e.events.none { it.startsWith("apply") || it == "rollback" })
        assertEquals(CheckStatus.PASS, c.state.value.results["discord"]?.network?.status)
        assertEquals(CheckStatus.FAIL, c.state.value.results["youtube"]?.network?.status)
        assertEquals("TCP_RESET", c.state.value.results["youtube"]?.failureClass)
        assertEquals(Phase.IDLE, c.state.value.phase)
    }

    @Test fun noValidatedNetworkPausesWithoutProbing() = runTest {
        val e = Engine().apply { usable = false }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2")); runCurrent()
        assertEquals("NETWORK_UNAVAILABLE", c.state.value.pauseReason)
        assertTrue(e.events.isEmpty())
    }

    @Test fun cancellationDuringProbeRollsBackAndIsNotACandidateFailure() = runTest {
        val e = Engine().apply { probeDelayMs = 10_000; matrix["P2"] = mapOf("youtube" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2")); runCurrent()
        advanceTimeBy(25_000); runCurrent() // baseline bitti, aday ölçülüyor
        assertEquals("P2", e.trial)
        c.stop(); runCurrent()
        assertNull(e.trialOwner)
        assertEquals("CANCELLED", c.state.value.finishReason)
        assertNotEquals(Verdict.FAIL, c.state.value.autoVerdict)
        assertNull(c.state.value.plan.strategyId)
    }

    @Test fun rateLimitedTargetIsNotHammeredByOtherCandidates() = runTest {
        val e = Engine().apply { retryAfter = 120 }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3", "P4")); runCurrent()
        assertEquals("COOLDOWN", c.state.value.pauseReason)
        assertTrue(e.events.none { it.startsWith("apply") })
        val probes = e.events.size
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3", "P4")); runCurrent()
        assertEquals(probes, e.events.size)
    }

    @Test fun manyTargetsAndCandidatesStayWithinAFiniteBudget() = runTest {
        val e = Engine()
        val c = coordinator(e, backgroundScope)
        val targets = SocialPlatform.catalog.mapNotNull { Target.home(it.id) }
        c.start(goal, targets.first(), TestMode.AUTO_BETA, true, false, false, native("P1", "P2", "P3", "P4", "P5", "P6", "P7"), targets.drop(1)); runCurrent()
        assertTrue(c.state.value.requests <= 80)
        assertTrue(c.state.value.phase == Phase.PAUSED && c.state.value.pauseReason == "BUDGET_LIMIT" || c.state.value.phase == Phase.FINISHED)
    }

    @Test fun resultsArePersistedPerTargetWithPlan() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS", "instagram" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2"), listOf(ig)); runCurrent()
        assertEquals(2 to "P2", e.persisted.last())
    }
}
