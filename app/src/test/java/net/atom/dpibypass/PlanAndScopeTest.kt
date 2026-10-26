package net.atom.dpibypass

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.engine.EngineOwnership
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.strategy.StrategyPool
import net.atom.dpibypass.strategy.StrategyScore
import net.atom.dpibypass.strategy.StrategyTestResult
import net.atom.dpibypass.vpn.AppScope
import net.atom.dpibypass.vpn.ScopeDecision
import net.atom.dpibypass.vpn.TunnelConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

@OptIn(ExperimentalCoroutinesApi::class)
class PlanAndScopeTest {
    @After fun reset() = EngineOwnership.resetForTest()

    // ---- uygulama kapsamı ----

    @Test fun emptyOrRemovedIncludeListNeverBecomesAllTraffic() {
        val self = "net.atom.dpibypass"
        assertEquals(ScopeDecision.EmptyInclude, AppScope.decide(AppFilterMode.Include, emptySet(), false, self) { true })
        // Listede yalnız kaldırılmış paketler ya da kendi paketimiz var.
        assertEquals(ScopeDecision.EmptyInclude, AppScope.decide(AppFilterMode.Include, setOf("gone.app", self), false, self) { it != "gone.app" })
        assertEquals(ScopeDecision.Only(listOf("com.discord")),
            AppScope.decide(AppFilterMode.Include, setOf("com.discord", "gone.app", self), false, self) { it != "gone.app" })
    }

    @Test fun ownPackageIsAlwaysOutsideAndVodafoneModeHasPriorityWithoutErasingTheList() {
        val self = "net.atom.dpibypass"
        assertEquals(ScopeDecision.AllExcept(listOf(self)), AppScope.decide(AppFilterMode.All, setOf("a"), false, self) { true })
        assertEquals(ScopeDecision.AllExcept(listOf(self, "a")), AppScope.decide(AppFilterMode.Exclude, setOf("a", self), false, self) { true })
        // Vodafone modu etkin kapsamı "Tümü" yapar; kaydedilmiş liste kararın girdisi olarak kalır.
        assertEquals(ScopeDecision.AllExcept(listOf(self)), AppScope.decide(AppFilterMode.Include, emptySet(), true, self) { true })
        assertTrue(AppScope.covers(AppFilterMode.Include, setOf("com.discord"), true, "com.instagram.android"))
        assertFalse(AppScope.covers(AppFilterMode.Include, setOf("com.discord"), false, "com.instagram.android"))
        assertFalse(AppScope.covers(AppFilterMode.Exclude, setOf("com.instagram.android"), false, "com.instagram.android"))
    }

    @Test fun visualSettingsDoNotChangeTunnelConfig() {
        val base = Settings(dohProvider = DohProvider.Cloudflare, selectedApps = setOf("a"))
        val config = TunnelConfig.from(base)
        assertEquals(config, TunnelConfig.from(base.copy(theme = ThemePref.Dark, haptics = false, onboardingDone = true,
            socialAccessEnabled = true, socialPlatforms = setOf("youtube"), socialTargetUrl = "https://x.com/", samsungVpnIndicator = true)))
        assertNotEquals(config, TunnelConfig.from(base.copy(disableQuic = true)))
        assertNotEquals(config, TunnelConfig.from(base.copy(customDohUrl = "9.9.9.9")))
        assertNotEquals(config, TunnelConfig.from(base.copy(selectedApps = setOf("a", "b"))))
    }

    // ---- native motor sahipliği ----

    @Test fun strategyTestCannotStartWhileVpnOwnsTheEngine() = runTest {
        val vpn = EngineOwnership.acquireForVpn()
        assertNotNull(vpn)
        assertNull(EngineOwnership.tryAcquire(EngineOwnership.Owner.STRATEGY_TEST))
        EngineOwnership.release(vpn!!)
        assertNotNull(EngineOwnership.tryAcquire(EngineOwnership.Owner.STRATEGY_TEST))
    }

    @Test fun connectPreemptsRunningStrategyTestAndWaitsForRelease() = runTest {
        var cancelled = false
        var token: Long? = null
        token = EngineOwnership.tryAcquire(EngineOwnership.Owner.STRATEGY_TEST) { cancelled = true }
        val vpn = async { EngineOwnership.acquireForVpn() }
        runCurrent()
        assertTrue("Bağlanma testi iptal ettirmeli", cancelled)
        assertFalse("Test kaynaklarını bırakmadan VPN motoru almamalı", vpn.isCompleted)
        delay(100); EngineOwnership.release(token!!)
        assertNotNull(vpn.await())
        assertEquals(EngineOwnership.Owner.VPN, EngineOwnership.owner())
        // Eski test jetonu yeni sahipliği bırakamaz.
        EngineOwnership.release(token)
        assertEquals(EngineOwnership.Owner.VPN, EngineOwnership.owner())
    }

    @Test fun restartedServiceTakesOverItsOwnStaleLease() = runTest {
        val first = EngineOwnership.acquireForVpn()
        val second = EngineOwnership.acquireForVpn()
        assertNotNull(second); assertNotEquals(first, second)
        EngineOwnership.release(first!!) // eski örneğin geç bırakması yeni sahipliği silmez
        assertEquals(EngineOwnership.Owner.VPN, EngineOwnership.owner())
    }

    // ---- strateji puanı ----

    @Test fun coverageBeatsSingleFastHostAndUnresolvedTargetsStayInDenominator() {
        val fastButPartial = StrategyTestResult(StrategyPool.P1, successCount = 2, totalTargets = 3, latencyMs = 20)
        val slowerButComplete = StrategyTestResult(StrategyPool.P2, successCount = 3, totalTargets = 3, latencyMs = 200)
        assertTrue(StrategyScore.isBetter(slowerButComplete, fastButPartial))
        // DNS'te çözülemeyen zorunlu hedef: çözülen alt kümede 2/2 geçse de tam kapsama değil.
        val unresolved = StrategyTestResult(StrategyPool.P3, successCount = 2, totalTargets = 3, latencyMs = 30, unresolvedTargets = 1)
        assertFalse(unresolved.fullCoverage)
        assertEquals("2/3 hedef · 1 çözülemedi", StrategyScore.coverageLabel(unresolved))
        assertTrue(slowerButComplete.fullCoverage)
    }

    // ---- ölçüm sınıflandırması ----

    @Test fun probeClassificationSeparatesFailureCausesWithoutOverclaiming() {
        val now = 1_700_000_000_000
        assertEquals("PASS", ProbeClassifier.fromHttp("DIRECT_CONTROL", 302, null, now, 10).technicalOutcome)
        assertEquals("PASS", ProbeClassifier.fromHttp("DIRECT_CONTROL", 403, null, now, 10).technicalOutcome)
        assertEquals("UNKNOWN", ProbeClassifier.fromHttp("DIRECT_CONTROL", 502, null, now, 10).technicalOutcome)
        ProbeClassifier.fromHttp("DIRECT_CONTROL", 429, "30", now, 10).let { assertEquals("UNKNOWN", it.technicalOutcome); assertEquals(30, it.retryAfterSeconds) }
        fun cls(e: Throwable) = ProbeClassifier.fromException("THROUGH_CANDIDATE", e).let { it.technicalOutcome to it.failureClass }
        assertEquals("FAIL" to "DNS_FAILURE", cls(UnknownHostException("x")))
        assertEquals("FAIL" to "DNS_NON_PUBLIC_ANSWER", cls(UnknownHostException("PUBLIC_TARGET_REQUIRED")))
        assertEquals("FAIL" to "TLS_HANDSHAKE_FAILED", cls(SSLHandshakeException("x")))
        assertEquals("FAIL" to "TLS_CERT_MISMATCH", cls(SSLPeerUnverifiedException("x")))
        assertEquals("FAIL" to "TCP_RESET", cls(SocketException("Connection reset")))
        assertEquals("FAIL" to "TCP_REFUSED", cls(ConnectException("refused")))
        // Tek zaman aşımı kesin engel değildir.
        assertEquals("UNKNOWN" to "TCP_TIMEOUT", cls(SocketTimeoutException("connect timed out")))
        assertEquals("UNKNOWN" to "READ_TIMEOUT", cls(SocketTimeoutException("timeout")))
        for (c in listOf("DNS_FAILURE", "TLS_CERT_MISMATCH", "TCP_RESET", "TCP_TIMEOUT", "NO_VALIDATED_NETWORK")) assertFalse(SocialText.failure(c).contains('_'))
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDate() {
        val now = java.time.ZonedDateTime.parse("Wed, 21 Oct 2026 07:28:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(120, ProbeClassifier.retryAfterSeconds("120", now))
        assertEquals(90, ProbeClassifier.retryAfterSeconds("Wed, 21 Oct 2026 07:29:30 GMT", now))
        assertEquals(1, ProbeClassifier.retryAfterSeconds("Wed, 21 Oct 2026 07:00:00 GMT", now))
        assertEquals(60, ProbeClassifier.retryAfterSeconds("soon", now))
        assertEquals(86_400, ProbeClassifier.retryAfterSeconds("999999999", now))
    }

    // ---- sonuç kalıcılığı ve durum ----

    @Test fun persistedResultsComeBackStaleAndCorruptRecordsAreIgnored() {
        val r = TargetResult("youtube", "youtube", Check(CheckStatus.PASS, CheckSource.AUTO), Check(CheckStatus.PASS, CheckSource.USER), "P2", verifiedAtMs = 5)
        val (back, plan) = TargetResultCodec.decode(TargetResultCodec.encode(listOf(r), "P2").toString())
        assertEquals("P2", plan)
        assertTrue("Süreç yeniden başladıktan sonra eski başarı güncel sayılmaz", back["youtube"]!!.stale)
        assertEquals(CheckStatus.PASS, back["youtube"]!!.content.status)
        assertEquals(TargetStatus.VERIFYING, TargetStatusRules.of(back["youtube"], true, true, true))
        assertTrue(TargetResultCodec.decode("{not json").first.isEmpty())
        assertTrue(TargetResultCodec.decode("""{"schemaVersion":99,"results":[]}""").first.isEmpty())
        assertTrue(TargetResultCodec.decode("""{"schemaVersion":1,"results":[{"key":"x","platformId":"unknown"}]}""").first.isEmpty())
    }

    @Test fun statusesDoNotBlendTogether() {
        val net = TargetResult("discord", "discord", Check(CheckStatus.PASS, CheckSource.AUTO))
        assertEquals(TargetStatus.OUT_OF_SCOPE, TargetStatusRules.of(net, false, true, true))
        assertEquals(TargetStatus.NO_NETWORK, TargetStatusRules.of(net, true, true, false))
        assertEquals(TargetStatus.NO_ENGINE, TargetStatusRules.of(null, true, false, true))
        // Otomatik ağ ölçümü "çalışıyor" demez; onay bekler.
        assertEquals(TargetStatus.NETWORK_OPEN_CONFIRM, TargetStatusRules.of(net, true, true, true))
        // Kullanıcı bildirimi yalnız aynı bağlam kuşağında alındıysa günceldir.
        assertEquals(TargetStatus.WORKING, TargetStatusRules.of(net.copy(content = Check(CheckStatus.PASS, CheckSource.USER), contentGeneration = 0), true, true, true))
        assertEquals(TargetStatus.NETWORK_OPEN_CONFIRM, TargetStatusRules.of(net.copy(content = Check(CheckStatus.PASS, CheckSource.USER), contentGeneration = -1), true, true, true))
        assertEquals(TargetStatus.PARTIAL, TargetStatusRules.of(net.copy(content = Check(CheckStatus.PARTIAL, CheckSource.USER), contentGeneration = 0), true, true, true))
        assertEquals(TargetStatus.NOT_WORKING, TargetStatusRules.of(net.copy(content = Check(CheckStatus.FAIL, CheckSource.USER), contentGeneration = 0), true, true, true))
        // Platform doğrulaması ağ açıkken de, bayatken de gizlenmez.
        assertEquals(TargetStatus.PLATFORM_GATE, TargetStatusRules.of(net.copy(content = Check(CheckStatus.GATED, CheckSource.USER), contentGeneration = 0), true, true, true))
        assertEquals(TargetStatus.PLATFORM_GATE, TargetStatusRules.of(net.copy(content = Check(CheckStatus.GATED, CheckSource.USER), stale = true), true, true, true))
        assertEquals(TargetStatus.NETWORK_BLOCKED, TargetStatusRules.of(net.copy(network = Check(CheckStatus.FAIL, CheckSource.AUTO)), true, true, true))
        for (s in TargetStatus.entries) assertFalse(TargetStatusRules.label(s).contains('_'))
    }

    // ---- katalog ----

    @Test fun catalogDeclaresOnlyVerifiableCapabilitiesAndRealPackages() {
        for (p in SocialPlatform.entries) {
            assertEquals(p.id, CheckMode.AUTO, p.mode(Capability.NETWORK))
            // İçerik otomatik gözlemi yalnız gözlemcisi olan platformlarda iddia edilir.
            if (!p.autoObserver) assertNotEquals(p.id, CheckMode.AUTO, p.mode(Capability.CONTENT))
            assertTrue(p.packages.all { it.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")) })
        }
        assertEquals(CheckMode.MANUAL, SocialPlatform.DISCORD.mode(Capability.VOICE))
        assertTrue(SocialPlatform.DISCORD.note.contains("ses", ignoreCase = true))
        assertEquals("https://chat.signal.org/", Target.home("signal")!!.probeUrl)
        assertEquals(listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com", "www.youtube.com"),
            SocialAccessRuntime.probeHosts(setOf("discord", "youtube", "unknown")).sortedBy { it != "discord.com" && it != "gateway.discord.gg" && it != "cdn.discordapp.com" })
    }

    @Test fun customDomainTargetsArePublicHostsOnly() {
        assertEquals("custom:status.example.org", Target.parse("custom", "https://status.example.org/health")?.key)
        assertEquals(Target.HOME_CONTENT, Target.parse("custom", "example.org")?.contentId)
        for (bad in listOf("https://127.0.0.1/", "https://router.local/", "https://intranet/", "http://example.org/", "https://user:pw@example.org/",
            "https://www.youtube.com/", "https://[::1]/", "https://printer.lan/"))
            assertNull(bad, Target.parse("custom", bad))
        assertNull(Target.detectPlatform("https://example.org/"))
    }

    @Test fun networkTrackerFollowsTheSystemChoiceAndValidation() {
        val t = NetworkContextTracker()
        assertFalse(t.selected(1))
        // Wi-Fi bağlı ama doğrulanmamış; sistem hücreseli seçti → gerçek değişim.
        assertTrue(t.selected(2))
        assertFalse(t.selected(null))
        assertFalse(t.selected(2))
        t.capabilities(2, validatedInternet = false)
        assertFalse(t.usable())
        t.capabilities(2, validatedInternet = true)
        assertTrue(t.usable())
    }
}
