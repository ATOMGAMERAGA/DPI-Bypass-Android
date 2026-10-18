package net.atom.dpibypass

import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

class SocialAccessTest {
    @Test fun sharedGoalVectorsNeverPromoteUncertainEvidence() {
        val fixture = JSONObject(File("../tests/fixtures/social-access-v2.json").readText())
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val goal = JSONObject(fixture.getJSONObject("goal").toString())
            val observation = JSONObject(fixture.getJSONObject("observation").toString())
            case.optJSONObject("goalPatch")?.let { patch -> patch.keys().forEach { goal.put(it, patch.get(it)) } }
            case.getJSONObject("patch").let { patch -> patch.keys().forEach { observation.put(it, patch.get(it)) } }
            assertEquals(case.getString("id"), case.getString("expectedVerdict"),
                GoalEvaluator.evaluate(Goal.fromJson(goal), Observation.fromJson(observation)).name)
        }
    }

    @Test fun targetValidationRejectsLocalCredentialsRedirectsAndWrongPlatforms() {
        listOf("http://youtube.com/watch?v=abcdefghijk", "https://127.0.0.1/", "https://youtube.com.evil/watch?v=abcdefghijk",
            "https://user:secret@youtube.com/watch?v=abcdefghijk", "https://youtube.com/redirect?q=secret", "https://youtube.com:444/watch?v=abcdefghijk").forEach {
            assertNull(it, Target.parse("youtube", it))
        }
        assertNotNull(Target.parse("youtube", "https://www.youtube.com/watch?v=abcdefghijk"))
        assertNotNull(Target.parse("instagram", "https://www.instagram.com/p/ABCD123/"))
        assertFalse(Target.resourceAllowed("https://192.168.0.1/"))
        assertFalse(Target.resourceAllowed("https://googlevideo.com.evil/video"))
        assertTrue(Target.resourceAllowed("https://rr1---sn.googlevideo.com/video"))
    }

    @Test fun unknownOrMissingEvidenceIsNeverPass() {
        assertEquals(Verdict.INCONCLUSIVE, GoalEvaluator.evaluate(Goal(), Observation()))
        assertEquals(TestMode.MANUAL, TestMode.parse("future-mode"))
        assertEquals(TestMode.MANUAL, TestMode.parse(null))
    }
    @Test fun probeAddressPolicyRejectsPrivateAndMappedTargets() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "100.64.1.1", "::1", "fc00::1", "fe80::1", "64:ff9b::7f00:1"))
            assertFalse(ip, PublicTargetAddress.allowed(InetAddress.getByName(ip)))
        assertTrue(PublicTargetAddress.allowed(InetAddress.getByName("1.1.1.1")))
        assertTrue(PublicTargetAddress.allowed(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
