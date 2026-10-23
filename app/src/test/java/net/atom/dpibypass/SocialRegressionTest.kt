package net.atom.dpibypass

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.junit.Assert.*
import org.junit.Test

/**
 * Kullanıcı şikâyetlerinin regresyon testleri. Bu dosyadaki testler yalnızca
 * 5d92f5e'de de var olan API'yi kullanır; eski kodda başarısız, yenisinde
 * başarılıdır (bkz. docs/social-access-alpha.md → kök neden tablosu).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocialRegressionTest {
    /** Motoru taklit eder: hangi profil kurulu, hangi işlem sahibi; yol başına ağ sonucu. */
    private class Engine : TrialAdapter {
        val events = mutableListOf<String>()
        var installed: String? = null
        var owner: String? = null
        /** strategyId → o profil kuruluyken hedef anahtarı → sonuç. null anahtar: motorun açılış profili. */
        val through = mutableMapOf<String?, Map<String, String>>()
        val direct = mutableMapOf<String, String>()
        override fun available() = true
        override suspend fun apply(candidate: CandidateDefinition, operationId: String) {
            check(owner == null) { "previous trial still owned" }
            owner = operationId; events += "apply:${candidate.strategyId ?: candidate.id}"
            if (candidate.strategyId != null) installedTrial = candidate.strategyId
        }
        var installedTrial: String? = null
        override suspend fun rollback(operationId: String): Boolean {
            check(owner == null || owner == operationId); owner = null; installedTrial = null; events += "rollback"; return true
        }
        override suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence {
            events += "probe:${target.platformId}:${if (throughCandidate) "socks" else "direct"}"
            val outcome = if (!throughCandidate) direct[target.platformId] ?: "FAIL"
                else through[installedTrial ?: installed]?.get(target.platformId) ?: "FAIL"
            return NetworkEvidence(outcome, reason = (if (throughCandidate) "THROUGH_CANDIDATE" else "DIRECT_CONTROL") + if (outcome == "PASS") ":HTTP_200:NETWORK_PATH_ONLY" else ":TCP_RESET")
        }
        override fun journal(operationId: String, stage: String, candidate: CandidateDefinition?) {
            events += stage
            // Servisteki gibi: KEPT, adayı motorun tabanı yapar ve geçici sahipliği bitirir.
            if (stage == "KEPT" && candidate?.strategyId != null && owner == operationId) { installed = installedTrial; installedTrial = null; owner = null }
        }
    }

    private val youtube = Target.parse("youtube", "https://www.youtube.com/watch?v=abcdefghijk")!!
    private val instagram = Target.home("instagram")!!
    private val goal = Goal(platformId = "youtube", allowedSurfaces = setOf("OFFICIAL_NATIVE_APP"))
    private fun native(vararg ids: String) = ids.map { CandidateDefinition(id = "N4-$it", family = "N4", displayName = it, strategyId = it, surface = "OFFICIAL_NATIVE_APP") }
    private fun coordinator(a: TrialAdapter, scope: CoroutineScope) = ExperimentCoordinator(a, scope).also { it.enabled(true) }

    /** Belirti: "Bir ayarı değiştirince çalışan yöntem kendiliğinden kapanıyor." */
    @Test fun keptNativeMethodSurvivesTunnelSettingsChange() = runTest {
        val e = Engine(); val c = coordinator(e, backgroundScope)
        c.start(goal, youtube, TestMode.MANUAL, true, false, false, native("P2", "P3")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        assertEquals("RESOLVED_USER_REPORTED", c.state.value.finishReason)
        val before = e.events.count { it == "rollback" }
        // DNS / QUIC / kapsam değişikliği: DpiVpnService.reconfigureTunnel → settingsChanging → contextChanged
        c.contextChanged(); runCurrent()
        assertEquals("Onaylanan profil ayar değişikliğinde geri alınmamalı", before, e.events.count { it == "rollback" })
        assertEquals("KEPT_ACTIVE", c.state.value.resourceDisposition)
    }

    /** Belirti: "Başka bir uygulamayı düzeltirken öncekinin çalışması bozuluyor." */
    @Test fun testingSecondPlatformDoesNotRevertFirstPlatformsKeptMethod() = runTest {
        val e = Engine(); val c = coordinator(e, backgroundScope)
        c.start(goal, youtube, TestMode.MANUAL, true, false, false, native("P2")); runCurrent()
        c.feedback(c.state.value.feedback("RESOLVED")); runCurrent()
        val rollbacks = e.events.count { it == "rollback" }
        c.start(goal.copy(platformId = "instagram"), instagram, TestMode.MANUAL, true, false, false, native("P3")); runCurrent()
        assertEquals("İkinci hedefin denemesi ilk hedefin onaylı profilini geri almamalı", rollbacks, e.events.count { it == "rollback" })
    }

    /** Belirti: "Otomatik test çalışan yöntemi seçemiyor" — DPI adayları otomatikte hiç değerlendirilmiyordu. */
    @Test fun automaticModeEvaluatesNativeCandidateOnItsRealPath() = runTest {
        val e = Engine().apply {
            direct["youtube"] = "FAIL"
            through[null] = mapOf("youtube" to "FAIL")
            through["P2"] = mapOf("youtube" to "FAIL")
            through["P3"] = mapOf("youtube" to "PASS")
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, youtube, TestMode.AUTO_BETA, true, false, false, native("P2", "P3")); runCurrent()
        assertTrue("Aday yolundan (SOCKS) ölçülmeli", e.events.contains("probe:youtube:socks"))
        assertEquals("NETWORK_VERIFIED_AUTO", c.state.value.finishReason)
        assertEquals("P3", c.state.value.candidate?.strategyId)
        assertEquals(listOf("apply:P2", "rollback", "apply:P3"), e.events.filter { it.startsWith("apply:") || it == "rollback" })
    }

    /** Başlangıçta zaten erişilebilen hedefte yönteme sahte başarı yazılmaz. */
    @Test fun alreadyReachableTargetGivesNoMethodCredit() = runTest {
        val e = Engine().apply {
            direct["youtube"] = "PASS"
            through[null] = mapOf("youtube" to "PASS")
            through["P2"] = mapOf("youtube" to "PASS")
        }
        val c = coordinator(e, backgroundScope)
        c.start(goal, youtube, TestMode.AUTO_BETA, true, false, false, native("P2", "P3")); runCurrent()
        assertNotEquals("NETWORK_VERIFIED_AUTO", c.state.value.finishReason)
        assertNull(e.owner)
    }
}
