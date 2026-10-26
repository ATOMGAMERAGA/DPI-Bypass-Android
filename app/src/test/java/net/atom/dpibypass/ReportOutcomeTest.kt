package net.atom.dpibypass

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.junit.Assert.*
import org.junit.Test

/**
 * Manuel dönüş seçenekleri (Çalışıyor / Kısmen / Çalışmıyor / Doğrulama istiyor /
 * Emin değilim) ve ağ kontrolünün kullanıcı bildirimini yenileyememesi.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportOutcomeTest {
    private class Engine : TrialAdapter {
        val events = mutableListOf<String>()
        var base: String? = "P1"; var trial: String? = null; var owner: String? = null; var planOwner: String? = null
        val matrix = mutableMapOf<String?, Map<String, String>>()
        override fun available() = true
        override suspend fun apply(candidate: CandidateDefinition, operationId: String) { owner = operationId; trial = candidate.strategyId; events += "apply:${candidate.strategyId}" }
        override suspend fun rollback(operationId: String): Boolean { owner = null; trial = null; events += "rollback"; return true }
        override suspend fun commit(operationId: String): Boolean { base = trial; planOwner = operationId; owner = null; trial = null; events += "commit:$base"; return true }
        override suspend fun revertPlan(operationId: String): Boolean { events += "revert"; base = "P1"; return true }
        override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence {
            events += "probe:${target.platformId}"
            val o = matrix[if (throughCandidate) trial ?: base else null]?.get(target.platformId) ?: "FAIL"
            return NetworkEvidence(o, reason = if (o == "PASS") "THROUGH_CANDIDATE:HTTP_200:NETWORK_PATH_ONLY" else "THROUGH_CANDIDATE:TCP_RESET")
        }
        override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) {}
    }
    private val ig = Target.home("instagram")!!
    private val goal = Goal(platformId = "instagram", allowedSurfaces = setOf("OFFICIAL_NATIVE_APP"))
    private fun native(vararg ids: String) = ids.map { CandidateDefinition(id = "N4-$it", family = "N4", displayName = it, strategyId = it, surface = "OFFICIAL_NATIVE_APP") }
    private fun coordinator(a: TrialAdapter, scope: CoroutineScope) = ExperimentCoordinator(a, scope).also { it.enabled(true) }

    /** Kabul 2: sunucu doğrulaması → doğru sınıf; plan geri alınmaz, profiller döndürülmez, sahte başarı yok. */
    @Test fun verificationRequiredAfterAutoNetworkPassDoesNotRotateProfiles() = runTest {
        val e = Engine().apply { matrix["P2"] = mapOf("instagram" to "PASS"); matrix["P3"] = mapOf("instagram" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, ig, TestMode.AUTO_BETA, true, false, false, native("P2", "P3")); runCurrent()
        assertEquals("P2", c.state.value.plan.strategyId)
        c.report("instagram", "VERIFICATION_REQUIRED"); runCurrent()
        val r = c.state.value.results["instagram"]!!
        assertEquals(CheckStatus.GATED, r.content.status)
        assertEquals(TargetStatus.PLATFORM_GATE, TargetStatusRules.of(r, true, true, true))
        assertFalse("Plan yanlış pozitif sayılıp geri alınmamalı", e.events.contains("revert"))
        assertFalse("Sunucu şartı için sıradaki profil denenmemeli", e.events.contains("apply:P3"))
        assertFalse(r.working)
    }

    /** Deneme sırasında "Doğrulama istiyor": aday geri alınır, arama durur, sonuç saklanmaz. */
    @Test fun verificationRequiredDuringManualTrialStopsSearch() = runTest {
        val e = Engine()
        val c = coordinator(e, backgroundScope)
        c.start(goal, ig, TestMode.MANUAL, true, false, false, native("P2", "P3")); runCurrent()
        assertEquals(Phase.WAITING_FOR_USER, c.state.value.phase)
        c.feedback(c.state.value.feedback("VERIFICATION_REQUIRED")); runCurrent()
        assertEquals("PLATFORM_GATE", c.state.value.finishReason)
        assertNull(e.owner)
        assertFalse(e.events.contains("apply:P3"))
        assertNull(c.state.value.plan.strategyId)
        assertEquals(TargetStatus.PLATFORM_GATE, TargetStatusRules.of(c.state.value.results["instagram"], true, true, true))
        // Ağ kontrolü platform doğrulamasını silmez.
        c.check(listOf(ig)); runCurrent()
        assertEquals(TargetStatus.PLATFORM_GATE, TargetStatusRules.of(c.state.value.results["instagram"], true, true, true))
    }

    /** "Emin değilim" kesin başarısızlık değildir; önceki başarı da bu yüzden korunmaz/silinmez sanılmaz. */
    @Test fun unsureIsNotAFailure() = runTest {
        val e = Engine().apply { matrix["P1"] = mapOf("instagram" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.check(listOf(ig)); runCurrent()
        c.report("instagram", "UNSURE"); runCurrent()
        val r = c.state.value.results["instagram"]!!
        assertNotEquals(CheckStatus.FAIL, r.content.status)
        assertEquals(TargetStatus.NETWORK_OPEN_CONFIRM, TargetStatusRules.of(r, true, true, true))
        // Deneme içinde de: aday başarısız sayılmaz, sıradaki profile geçilmez.
        e.matrix["P2"] = mapOf("instagram" to "PASS")
        c.start(goal, ig, TestMode.MANUAL, true, false, false, native("P2", "P3")); runCurrent()
        c.feedback(c.state.value.feedback("UNSURE")); runCurrent()
        assertEquals("USER_UNABLE_TO_TEST", c.state.value.pauseReason)
        assertFalse(e.events.contains("apply:P3"))
    }

    /** Ağ kontrolü (yeni bağlamda) kullanıcı bildirimini güncel yapamaz. */
    @Test fun networkCheckCannotRefreshUserReport() = runTest {
        val e = Engine().apply { matrix["P1"] = mapOf("instagram" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.check(listOf(ig)); runCurrent()
        c.report("instagram", "RESOLVED"); runCurrent()
        assertEquals(TargetStatus.WORKING, TargetStatusRules.of(c.state.value.results["instagram"], true, true, true))
        c.contextChanged(); runCurrent()
        c.check(listOf(ig)); runCurrent()
        val r = c.state.value.results["instagram"]!!
        assertEquals(CheckStatus.PASS, r.network.status)
        assertNotEquals(TargetStatus.WORKING, TargetStatusRules.of(r, true, true, true))
    }

    /** Kabul 4: başlangıçta zaten çalışan hedefte "yöntem başarılı" yazılmaz; kullanıcı bildirimi yöntem kredisi değildir. */
    @Test fun alreadyWorkingTargetGetsNoMethodCreditFromUserReport() = runTest {
        val e = Engine().apply { matrix["P1"] = mapOf("instagram" to "PASS"); matrix["P2"] = mapOf("instagram" to "PASS") }
        val c = coordinator(e, backgroundScope)
        c.start(goal, ig, TestMode.AUTO_BETA, true, false, false, native("P2")); runCurrent()
        assertNotEquals("NETWORK_VERIFIED_AUTO", c.state.value.finishReason)
        assertNull(c.state.value.plan.strategyId)
        assertNotEquals("NETWORK_PATH", c.state.value.causalAttribution)
    }
}
