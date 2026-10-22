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

    @Test fun shareLinksAreNormalizedAndTrackingIsDropped() {
        val id = "abcdefghijk"
        listOf("https://youtu.be/$id?si=TRACK", "https://www.youtube.com/shorts/$id", "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id", "https://music.youtube.com/watch?v=$id&list=X", "youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id#t=10").forEach {
            assertEquals(it, "https://www.youtube.com/watch?v=$id", Target.parse("youtube", it)?.url)
        }
        assertEquals("https://www.instagram.com/reel/ABC123/", Target.parse("instagram", "https://www.instagram.com/reels/ABC123/?igsh=xyz")?.url)
        assertEquals("https://www.instagram.com/p/ABC123/", Target.parse("instagram", "https://www.instagram.com/someone/p/ABC123/")?.url)
        assertEquals("https://www.instagram.com/p/ABC123/", Target.parse("instagram", "https://instagram.com/tv/ABC123")?.url)
        assertEquals("https://x.com/user/status/123", Target.parse("x", "https://x.com/user/status/123?s=20")?.url)
        assertEquals("123", Target.parse("x", "https://twitter.com/user/status/123")?.contentId)
        assertEquals("https://vm.tiktok.com/ZMabc/", Target.parse("tiktok", "https://vm.tiktok.com/ZMabc/")?.url)
        assertEquals("https://discord.gg/invite", Target.parse("discord", "https://discord.gg/invite")?.url)
        assertEquals("https://www.facebook.com/watch/?v=42", Target.parse("facebook", "https://www.facebook.com/watch/?v=42&ref=share")?.url)
        // Yanlış platform, sahte alan adı, http ve kimlik bilgisi hâlâ reddedilir.
        assertNull(Target.parse("x", "https://www.youtube.com/watch?v=$id"))
        assertNull(Target.parse("tiktok", "https://tiktok.com.evil/video/1"))
        assertNull(Target.parse("reddit", "http://www.reddit.com/r/test"))
        assertNull(Target.parse("discord", "https://user:pw@discord.com/app"))
        assertNull(Target.parse("unknown", "https://example.com/"))
    }

    @Test fun platformIsDetectedFromSharedText() {
        assertEquals("https://youtu.be/abcdefghijk", Target.extractUrl("Şuna bak: https://youtu.be/abcdefghijk."))
        assertEquals(SocialPlatform.YOUTUBE, Target.detectPlatform("Şuna bak: https://youtu.be/abcdefghijk"))
        assertEquals(SocialPlatform.X, Target.detectPlatform("twitter.com/user/status/1"))
        assertEquals(SocialPlatform.DISCORD, Target.detectPlatform("https://discord.gg/abc"))
        assertEquals(SocialPlatform.REDDIT, Target.detectPlatform("https://old.reddit.com/r/test"))
        assertNull(Target.detectPlatform("https://youtube.com.evil/watch"))
        assertNull(Target.detectPlatform("merhaba"))
        assertEquals(Target.HOME_CONTENT, Target.home("discord")?.contentId)
        assertTrue(SocialPlatform.supportsAutoObserver("youtube"))
        assertFalse(SocialPlatform.supportsAutoObserver("tiktok"))
    }

    @Test fun userFacingTextNeverShowsRawCodes() {
        assertEquals("Doğrudan bağlantı: sunucuya ulaşıldı (HTTP 200)", SocialText.evidence("DIRECT_CONTROL:HTTP_200:NETWORK_PATH_ONLY"))
        assertEquals("Yöntem üzerinden: bağlantı kurulamadı", SocialText.evidence("THROUGH_CANDIDATE:TRANSPORT_UNKNOWN"))
        assertEquals("Bağlantı testi zaman aşımına uğradı", SocialText.evidence("PROBE_TIMEOUT"))
        for (p in Phase.entries) assertFalse(SocialText.phase(p).contains('_'))
        for (v in Verdict.entries) assertFalse(SocialText.verdict(v).contains('_'))
    }

    @Test fun primaryNetworkChangesOnlyWhenTheUnderlyingNetworkReallyChanges() {
        val t = NetworkContextTracker()
        assertFalse(t.update(1, NetworkContextTracker.Kind.WIFI))
        // "Mobil veri her zaman açık": hücresel ağ Wi-Fi'ın yanında gelip gider.
        assertFalse(t.update(2, NetworkContextTracker.Kind.CELLULAR))
        assertFalse(t.update(2, null))
        assertFalse(t.update(2, NetworkContextTracker.Kind.CELLULAR))
        assertFalse(t.update(1, NetworkContextTracker.Kind.WIFI))
        // Wi-Fi gidince hücresele geçiş gerçek bir değişimdir.
        assertTrue(t.update(1, null))
        // Wi-Fi'a dönüş de.
        assertTrue(t.update(3, NetworkContextTracker.Kind.WIFI))
        // Tüm ağlar gidip aynı ağ geri gelirse değişim yok.
        assertFalse(t.update(2, null)); assertFalse(t.update(3, null))
        assertFalse(t.update(3, NetworkContextTracker.Kind.WIFI))
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
