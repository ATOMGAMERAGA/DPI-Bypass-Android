package net.atom.dpibypass

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 26 Ekim 2026 incelemesinde bulunan regresyonlar (docs/social-access-alpha.md →
 * "Revizyon — 26 Ekim 2026"). Testler yalnız 5488ab9'da da var olan API'yi
 * kullanır; o revizyonda kırmızı, bu revizyonda yeşildir.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetAndWaitRegressionTest {
    /**
     * Servisin sözleşmesini izleyen motor taklidi: taban + tek geçici aday, yol
     * başına hedef sonucu. [waits] hedef → "hangi ölçümde" Retry-After döneceği.
     */
    private class Engine : TrialAdapter {
        val events = mutableListOf<String>()
        var base: String? = "P1"
        var trial: String? = null
        var owner: String? = null
        val matrix = mutableMapOf<String?, Map<String, String>>()
        val direct = mutableMapOf<String, String>()
        /** (platformId, strateji) → bu çağrıda Retry-After dönsün mü? */
        var waitWhen: (platform: String, strategy: String?, call: Int) -> Boolean = { _, _, _ -> false }
        var requestsPerProbe = 1
        var bytesPerProbe = 2_048L
        val cooldowns = mutableMapOf<String, Long>()
        private val calls = mutableMapOf<String, Int>()
        override fun available() = true
        override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
            check(owner == null) { "single engine" }
            owner = operationId; trial = candidate.strategyId; events += "apply:${candidate.strategyId}"
        }
        override suspend fun rollback(operationId: String): Boolean {
            if (owner != null && owner != operationId) return false
            owner = null; trial = null; events += "rollback"; return true
        }
        override suspend fun commit(operationId: String): Boolean {
            if (owner != operationId) return false
            base = trial; owner = null; trial = null; events += "commit:$base"; return true
        }
        override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence {
            val strategy = if (throughCandidate) trial ?: base else null
            val key = "${target.platformId}:$strategy"
            val n = (calls[key] ?: 0) + 1; calls[key] = n
            events += "probe:${target.platformId}:${if (throughCandidate) "socks" else "direct"}"
            if (waitWhen(target.platformId, strategy, n))
                return NetworkEvidence("UNKNOWN", bytes = bytesPerProbe, requests = requestsPerProbe, reason = "THROUGH_CANDIDATE:HTTP_429:RATE_LIMITED",
                    retryAfterSeconds = 120, failureClass = "RATE_LIMITED", httpStatus = 429)
            val outcome = if (!throughCandidate) direct[target.platformId] ?: "FAIL" else matrix[strategy]?.get(target.platformId) ?: "FAIL"
            return NetworkEvidence(outcome, bytes = bytesPerProbe, requests = requestsPerProbe,
                reason = if (outcome == "PASS") "THROUGH_CANDIDATE:HTTP_200:NETWORK_PATH_ONLY" else "THROUGH_CANDIDATE:TCP_RESET",
                failureClass = if (outcome == "PASS") "" else "TCP_RESET")
        }
        override fun persistCooldown(key: String, delayMillis: Long) { cooldowns[key] = delayMillis }
        override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) {}
    }

    private val yt = Target.home("youtube")!!
    private val ig = Target.home("instagram")!!
    private val goal = Goal(platformId = "youtube", allowedSurfaces = setOf("OFFICIAL_NATIVE_APP"))
    private fun native(vararg ids: String) = ids.map { CandidateDefinition(id = "N4-$it", family = "N4", displayName = it, strategyId = it, surface = "OFFICIAL_NATIVE_APP") }
    private fun coordinator(a: TrialAdapter, scope: CoroutineScope) = ExperimentCoordinator(a, scope).also { it.enabled(true) }

    /** R1: bilinen sunucu sınıfı korunur; bilinmeyen değer başarıya ya da başka sınıfa dönüşmez. */
    @Test fun observationJsonKeepsKnownBlockingClassAndRejectsUnknown() {
        fun parse(cls: String) = Observation.fromJson(JSONObject().put("schemaVersion", 2).put("stableSamples", 0).put("blockingClass", cls)).blockingClass
        assertEquals("AGE_OR_ID_VERIFICATION_REQUIRED", parse("AGE_OR_ID_VERIFICATION_REQUIRED"))
        assertEquals("LOGIN_REQUIRED", parse("LOGIN_REQUIRED"))
        assertEquals("BOT_CHALLENGE", parse("BOT_CHALLENGE"))
        assertEquals("UNKNOWN", parse("SUCCESS"))
        assertEquals("UNKNOWN", parse("age_or_id_verification_required"))
    }

    /** R2a: odak dışı hedefin başlangıçtaki Retry-After'ı: hedefe özel bekleme, aday uygulanmaz. */
    @Test fun secondaryTargetRetryAfterAtBaselineStopsSearch() = runTest {
        val e = Engine().apply { waitWhen = { p, _, _ -> p == "instagram" } }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3"), listOf(ig)); runCurrent()
        assertEquals("COOLDOWN", c.state.value.pauseReason)
        assertTrue("Hedefe özel bekleme yazılmalı", e.cooldowns.containsKey("instagram"))
        assertTrue("Sonraki profil denenmemeli", e.events.none { it.startsWith("apply") })
    }

    /** R2b: aday altında odak dışı hedef 429: geçici aday geri alınır, sonraki profil denenmez. */
    @Test fun secondaryTargetRetryAfterUnderCandidateRollsBackAndStops() = runTest {
        val e = Engine().apply {
            matrix["P2"] = mapOf("youtube" to "PASS")
            matrix["P3"] = mapOf("youtube" to "PASS", "instagram" to "PASS")
            waitWhen = { p, s, _ -> p == "instagram" && s == "P2" }
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3"), listOf(ig)); runCurrent()
        assertEquals("COOLDOWN", c.state.value.pauseReason)
        assertTrue(e.cooldowns.containsKey("instagram"))
        assertNull("Geçici aday geri alınmalı", e.owner)
        assertFalse("Sonraki profil denenmemeli", e.events.contains("apply:P3"))
        assertNull(c.state.value.plan.strategyId)
    }

    /** R3: kararlılık ölçümündeki bekleme isteği normal başarısızlık sayılıp sonraki profile geçilmez. */
    @Test fun stabilityMeasurementRetryAfterIsNotTreatedAsFailure() = runTest {
        val e = Engine().apply {
            matrix["P2"] = mapOf("youtube" to "PASS")
            matrix["P3"] = mapOf("youtube" to "PASS")
            waitWhen = { p, s, n -> p == "youtube" && s == "P2" && n == 2 }
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2", "P3")); runCurrent()
        assertEquals("COOLDOWN", c.state.value.pauseReason)
        assertTrue(e.cooldowns.containsKey("youtube"))
        assertFalse(e.events.contains("apply:P3"))
        assertNull(e.owner)
        assertNull(c.state.value.plan.strategyId)
    }

    /** R4: HEAD→GET iki istektir; bütçe gerçek istek sayısını görür. */
    @Test fun headThenGetCountsBothRequests() = runTest {
        val e = Engine().apply { requestsPerProbe = 2 }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.AUTO_BETA, true, false, false, native("P2")); runCurrent()
        val probes = e.events.count { it.startsWith("probe:") }
        assertTrue(probes >= 3)
        assertEquals(probes * 2, c.state.value.requests)
    }

    /** R5: yeni bağlamdaki yalnız ağ PASS'i, önceki bağlamın kullanıcı içerik onayını güncel yapamaz. */
    @Test fun reverifyCannotResurrectPreviousContentConfirmation() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("youtube" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, yt, TestMode.MANUAL, true, false, false, native("P2")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        assertEquals(TargetStatus.WORKING, TargetStatusRules.of(c.state.value.results["youtube"], true, true, true))
        c.contextChanged(); advanceTimeBy(ExperimentCoordinator.REVERIFY_SETTLE_MS + 1); runCurrent()
        val r = c.state.value.results["youtube"]!!
        assertFalse(r.stale)
        assertEquals(CheckStatus.PASS, r.network.status)
        assertNotEquals("Önceki onay yeni bağlamda 'çalışıyor' gösterilmemeli", TargetStatus.WORKING, TargetStatusRules.of(r, true, true, true))
        assertFalse(r.working)
    }

    /** R6: bütün katalog seçiliyken varsayımsal toplu rezerv aramayı başlamadan durdurmamalı. */
    @Test fun allCatalogTargetsFindCommonPlanWithinBudget() = runTest {
        val targets = SocialPlatform.catalog.mapNotNull { Target.home(it.id) }
        val e = Engine().apply { matrix["P4"] = targets.associate { it.platformId to "PASS" } }
        val c = coordinator(e, backgroundScope)
        c.start(goal, targets.first(), TestMode.AUTO_BETA, true, false, false, native("P2", "P3", "P4"), targets.drop(1)); runCurrent()
        assertEquals("NETWORK_VERIFIED_AUTO", c.state.value.finishReason)
        assertEquals("P4", c.state.value.plan.strategyId)
    }

    /** R6: sekiz hedef, ortak plan son adayda; küçük gerçek yanıtlarla bütçe içinde bulunur. */
    @Test fun eightTargetsFindCommonPlanAtLastCandidate() = runTest {
        val targets = SocialPlatform.catalog.take(8).mapNotNull { Target.home(it.id) }
        val e = Engine().apply { matrix["P7"] = targets.associate { it.platformId to "PASS" } }
        val c = coordinator(e, backgroundScope)
        c.start(goal, targets.first(), TestMode.AUTO_BETA, true, false, false, native("P2", "P3", "P4", "P5", "P6", "P7"), targets.drop(1)); runCurrent()
        assertEquals("P7", c.state.value.plan.strategyId)
    }

    /** Her istek öncesi bütçe: aday ortasında biterse aday başarısız sayılmaz, geri alınır ve kaldığı yerden sürer. */
    @Test fun budgetExhaustedMidCandidateIsNotAFailureAndResumes() = runTest {
        val targets = SocialPlatform.catalog.mapNotNull { Target.home(it.id) }
        val e = Engine().apply { requestsPerProbe = 2; matrix["P7"] = targets.associate { it.platformId to "PASS" } }
        val c = coordinator(e, backgroundScope)
        c.start(goal, targets.first(), TestMode.AUTO_BETA, true, false, false, native("P2", "P3", "P4", "P5", "P6", "P7"), targets.drop(1)); runCurrent()
        assertEquals("BUDGET_LIMIT", c.state.value.pauseReason)
        assertNull("Yarım aday geri alınmalı", e.owner)
        assertTrue(c.state.value.requests <= 120)
        assertNull(c.state.value.plan.strategyId)
        repeat(4) { if (c.state.value.pauseReason == "BUDGET_LIMIT") { c.resume(); runCurrent() } }
        assertEquals("P7", c.state.value.plan.strategyId)
    }
}
