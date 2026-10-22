package net.atom.dpibypass

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SocialCoordinatorTest {
    private class Adapter : TrialAdapter {
        val events = mutableListOf<String>()
        var owned: String? = null
        var rollbackWorks = true
        var probeResult = NetworkEvidence("PASS")
        var probeCount = 0
        override fun available() = true
        override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
            check(owned == null); owned = operationId; events += "apply:${candidate.id}"
        }
        override suspend fun rollback(operationId: String): Boolean {
            if (!rollbackWorks) return false
            check(owned == null || owned == operationId); owned = null; events += "rollback"; return true
        }
        override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence { probeCount++; return probeResult }
        override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) { events += stage }
    }
    private val target = Target.parse("youtube", "https://www.youtube.com/watch?v=abcdefghijk")!!
    private val candidates = listOf("first", "second").map { CandidateDefinition(id = it, family = it, displayName = it, surface = "CONTROLLED_WEB_VIEW") }
    private val goal = Goal(identityContext = "ANONYMOUS", allowedSurfaces = setOf("CONTROLLED_WEB_VIEW"), publicFallbackAllowed = true)
    private fun coordinator(adapter: Adapter, scope: CoroutineScope) = ExperimentCoordinator(adapter, scope).also { it.enabled(true) }

    @Test fun disabledCoordinatorCannotApplyOrProbe() = runTest {
        val a = Adapter(); val c = ExperimentCoordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        assertEquals(Phase.DISABLED, c.state.value.phase)
        assertTrue(a.events.isEmpty())
    }

    @Test fun alreadyAccessibleDoesNotApply() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, false, false, false, candidates)
        assertEquals("ALREADY_ACCESSIBLE", c.state.value.finishReason)
        assertFalse(a.events.any { it.startsWith("apply:") })
    }
    @Test fun manualRollbackPrecedesNextAndStaleFeedbackIsRejected() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        val old = c.state.value.feedback("STILL_PRESENT")
        c.feedback(old); runCurrent(); c.feedback(old); runCurrent()
        assertEquals(listOf("apply:first", "rollback", "apply:second"), a.events.filter { it.startsWith("apply:") || it == "rollback" })
        assertEquals("second", c.state.value.candidate?.id)
        c.stop()
    }
    @Test fun userResolutionStopsEvenIfTechnicalEvidenceIsUnknown() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        assertEquals("RESOLVED_USER_REPORTED", c.state.value.finishReason)
        assertEquals("KEPT_ACTIVE", c.state.value.resourceDisposition)
        assertEquals(1, a.events.count { it.startsWith("apply:") })
        c.stop(); assertNull(a.owned)
    }
    @Test fun failedRollbackBlocksNextApply() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        a.rollbackWorks = false
        c.feedback(c.state.value.feedback("STILL_PRESENT")); runCurrent()
        assertEquals(Phase.RECOVERY_REQUIRED, c.state.value.phase)
        assertEquals(1, a.events.count { it.startsWith("apply:") })
    }
    @Test fun switchingToManualInvalidatesLateAutoPass() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        val s = c.state.value
        c.manual()
        c.observe(positive(s))
        assertEquals(Phase.WAITING_FOR_USER, c.state.value.phase)
        assertNull(c.state.value.finishReason)
        c.stop()
    }
    @Test fun autoFailureRollsBackThenPassKeepsSecondWithoutUserQuestion() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        c.observe(positive(c.state.value).copy(blockingGate = Tri.TRUE)); runCurrent()
        c.observe(positive(c.state.value)); runCurrent()
        assertEquals("RESOLVED_AUTO_OBSERVED", c.state.value.finishReason)
        assertEquals("NOT_TESTED", c.state.value.userOutcome)
        assertEquals(2, a.events.count { it.startsWith("apply:") })
        c.stop()
    }
    @Test fun meteredAutoDoesNotStartRendererWithoutPreference() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, true, false, candidates); runCurrent()
        assertEquals(0, c.state.value.rendererLoads)
        assertEquals("AUTO_INCONCLUSIVE", c.state.value.finishReason)
        assertNull(a.owned)
    }
    @Test fun staleDocumentCannotFinishAnActiveTrial() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        val current = c.state.value
        c.observe(positive(current).copy(documentGeneration = current.documentGeneration - 1))
        assertEquals(Phase.AUTO_EVALUATING, c.state.value.phase)
        assertNull(c.state.value.finishReason)
        c.stop()
    }
    @Test fun inactivityRollsBackWithoutFailureCredit() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        advanceTimeBy(15 * 60_000L); runCurrent()
        assertEquals("USER_INACTIVE", c.state.value.pauseReason)
        assertEquals("NOT_TESTED", c.state.value.userOutcome)
        assertNull(a.owned)
    }
    @Test fun budgetResumeOnlyAppliesRemainingDistinctCandidates() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        val many = (1..14).map { CandidateDefinition(id = "candidate$it", family = "family$it", displayName = "$it", surface = "CONTROLLED_WEB_VIEW") }
        c.start(goal, target, TestMode.MANUAL, true, false, false, many); runCurrent()
        repeat(12) { c.feedback(c.state.value.feedback("STILL_PRESENT")); runCurrent() }
        assertEquals("BUDGET_LIMIT", c.state.value.pauseReason)
        assertEquals(12, a.events.count { it.startsWith("apply:") })
        c.resume(); runCurrent()
        assertEquals("candidate13", c.state.value.candidate?.id)
        assertEquals(13, a.events.count { it.startsWith("apply:") })
        c.stop()
    }
    @Test fun sharedLanguageIndependentEventSequences() = runTest {
        val cases = JSONObject(File("../tests/fixtures/social-access-events-v2.json").readText()).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val a = Adapter(); val c = coordinator(a, backgroundScope)
            val names = fixture.getJSONArray("candidates")
            val items = (0 until names.length()).map { names.getString(it) }.map {
                CandidateDefinition(id = it, family = it, displayName = it, surface = "CONTROLLED_WEB_VIEW") }
            c.start(goal, target, TestMode.valueOf(fixture.getString("mode")), fixture.getBoolean("baselinePresent"), false, false, items); runCurrent()
            fixture.optJSONArray("autoVerdicts")?.let { verdicts ->
                for (i in 0 until verdicts.length()) {
                    c.observe(positive(c.state.value).let { if (verdicts.getString(i) == "FAIL") it.copy(blockingGate = Tri.TRUE) else it }); runCurrent()
                }
            }
            var previous: Feedback? = null
            val events = fixture.getJSONArray("events")
            for (i in 0 until events.length()) {
                when (val event = events.getString(i)) {
                    "STOP" -> c.stop()
                    "REPLAY_LAST" -> c.feedback(previous!!)
                    else -> { previous = c.state.value.feedback(event); c.feedback(previous!!) }
                }
                runCurrent()
            }
            assertEquals(fixture.getString("id"), fixture.getString("expectedPhase"), c.state.value.phase.name)
            assertEquals(if (fixture.isNull("expectedFinishReason")) null else fixture.getString("expectedFinishReason"), c.state.value.finishReason)
            assertEquals(fixture.getInt("expectedActiveResourceCount"), if (a.owned == null) 0 else 1)
            assertEquals(if (fixture.isNull("expectedNextCandidate")) null else fixture.getString("expectedNextCandidate"),
                if (c.state.value.phase == Phase.WAITING_FOR_USER) c.state.value.candidate?.id else null)
            c.stop()
        }
    }
    @Test fun humanResolutionAlsoStopsAutomaticContentTest() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        assertEquals("RESOLVED_USER_REPORTED", c.state.value.finishReason)
        assertEquals(1, a.events.count { it.startsWith("apply:") }); c.stop()
    }
    @Test fun cooldownCannotBeBypassedByImmediateResume() = runTest {
        val a = Adapter().apply { probeResult = NetworkEvidence("UNKNOWN", retryAfterSeconds = 60) }
        val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        assertEquals("COOLDOWN", c.state.value.pauseReason)
        val requests = a.probeCount
        c.resume(); runCurrent()
        assertEquals(requests, a.probeCount); c.stop()
    }
    @Test fun repeatedEnableAndIdleContextChangesDoNotRewriteState() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        val version = c.state.value.version
        c.enabled(true); runCurrent()
        c.contextChanged(); runCurrent()
        c.manual(); runCurrent()
        assertEquals(version, c.state.value.version)
        assertEquals(Phase.IDLE, c.state.value.phase)
        c.enabled(false); runCurrent()
        assertEquals(Phase.DISABLED, c.state.value.phase)
        val disabledVersion = c.state.value.version
        c.enabled(false); runCurrent()
        assertEquals(disabledVersion, c.state.value.version)
    }
    @Test fun finishedResultSurvivesNetworkChangeButActiveTrialPauses() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, false, false, false, candidates)
        c.contextChanged(); runCurrent()
        assertEquals("ALREADY_ACCESSIBLE", c.state.value.finishReason)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        c.contextChanged(); runCurrent()
        assertEquals("CONTEXT_CHANGED", c.state.value.pauseReason)
        assertNull(a.owned)
    }
    @Test fun recoveryCanBeRetriedOnceRollbackWorks() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        a.rollbackWorks = false
        c.feedback(c.state.value.feedback("STILL_PRESENT")); runCurrent()
        assertEquals(Phase.RECOVERY_REQUIRED, c.state.value.phase)
        c.retryRecovery(); runCurrent()
        assertEquals(Phase.RECOVERY_REQUIRED, c.state.value.phase)
        a.rollbackWorks = true
        c.retryRecovery(); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertNull(a.owned)
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        assertEquals(Phase.WAITING_FOR_USER, c.state.value.phase); c.stop()
    }
    @Test fun corruptJournalAtStartupCanBeCleared() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        c.markRecoveryRequired(); runCurrent()
        c.start(goal, target, TestMode.MANUAL, true, false, false, candidates); runCurrent()
        assertTrue(a.events.none { it.startsWith("apply:") })
        c.retryRecovery(); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
    }
    @Test fun platformsWithoutObserverNeverStartTheRenderer() = runTest {
        val a = Adapter(); val c = coordinator(a, backgroundScope)
        val tiktok = Target.parse("tiktok", "https://www.tiktok.com/@user/video/123")!!
        c.start(goal.copy(platformId = "tiktok"), tiktok, TestMode.AUTO_BETA, true, false, false, candidates); runCurrent()
        assertEquals(0, c.state.value.rendererLoads)
        assertEquals("AUTO_UNAVAILABLE", c.state.value.finishReason)
        assertNull(a.owned)
    }
    private fun positive(s: TrialState) = Observation(sessionId = s.sessionId, attemptId = s.attemptId, operationId = s.operationId,
        contextGeneration = s.contextGeneration, modeGeneration = s.modeGeneration, documentGeneration = s.documentGeneration,
        targetRef = s.target!!.targetRef, goalHash = s.goal.hashCode().toString(), observerVersion = "1.0.0", evaluatorVersion = "1.0.0",
        actualSurface = "CONTROLLED_WEB_VIEW", identityContext = "ANONYMOUS", routeProvenance = "EXACT_TARGET_PATH",
        targetMatched = Tri.TRUE, contentPresent = Tri.TRUE, blockingGate = Tri.FALSE, freshEvidence = Tri.TRUE, documentVisible = Tri.TRUE,
        observedCapabilities = setOf("VIEW_SELECTED_CONTENT"), stableSamples = 2, stableDurationMs = 2000)
}
