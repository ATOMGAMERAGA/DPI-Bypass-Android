package net.atom.dpibypass

import net.atom.dpibypass.social.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** İşlem bazında doğrulanmış erişim yolu: kabul, izolasyon, bayatlama, kalıcılık. */
class AccessRoutesTest {
    private val ctx = RouteContext("CELLULAR:Vodafone", "plan=P2|dns=cloudflare|quic=off|scope=all", "proc-1")
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private fun report(outcome: RouteOutcome = RouteOutcome.WORKS, action: AccessAction = AccessAction.ACCOUNT_ACCESS,
        surface: AccessSurface = AccessSurface.OFFICIAL_APP, account: AccountContext = AccountContext.SAME_ACCOUNT_CONFIRMED,
        at: Long = t0, context: RouteContext = ctx, network: String = "PASS", fresh: Boolean = false, platform: String = "instagram",
        browser: String? = if (surface == AccessSurface.OFFICIAL_WEB) "org.mozilla.firefox" else null, ui: Int = AccessRoutes.UI_VERSION) =
        RouteReport(platform, action, surface, browser, account, outcome, context, "TUNNEL", network, "", fresh, at, ui)

    private fun ledgerOf(vararg reports: RouteReport, now: Long = reports.maxOf { it.atMs }): RouteLedger =
        reports.fold(RouteLedger()) { l, r -> AccessRoutes.accept(l, r, r.context, now).also { assertNull(it.second) }.first }

    @Test fun reportFromOldUiVersionIsRejected() {
        val (l, why) = AccessRoutes.accept(RouteLedger(), report(ui = 0), ctx, t0)
        assertEquals(RouteRejection.STALE_UI, why)
        assertTrue(l.reports.isEmpty())
    }

    @Test fun reportFromAnotherContextIsRejected() {
        val (_, why) = AccessRoutes.accept(RouteLedger(), report(), ctx.copy(profileKey = "plan=P3"), t0)
        assertEquals(RouteRejection.CONTEXT_CHANGED, why)
    }

    @Test fun webReportNeedsExplicitBrowserAndUnsupportedActionIsRejected() {
        assertEquals(RouteRejection.BROWSER_REQUIRED, AccessRoutes.accept(RouteLedger(), report(surface = AccessSurface.OFFICIAL_WEB, browser = null), ctx, t0).second)
        // Snapchat kataloğunda sesli görüşme yok.
        assertEquals(RouteRejection.ACTION_NOT_SUPPORTED, AccessRoutes.accept(RouteLedger(), report(platform = "snapchat", action = AccessAction.VOICE_CALL), ctx, t0).second)
        assertEquals(RouteRejection.UNKNOWN_PLATFORM, AccessRoutes.accept(RouteLedger(), report(platform = "custom"), ctx, t0).second)
    }

    @Test fun singleReportIsTentativeAndTwoSeparatedFreshReportsVerify() {
        val one = ledgerOf(report())
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, AccessRoutes.decide(one, "instagram", AccessAction.ACCOUNT_ACCESS, ctx).verdict)
        // İki onay ama aynı dakikada: hâlâ tek seans etkisi olabilir.
        val close = ledgerOf(report(), report(at = t0 + minute, fresh = true))
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, AccessRoutes.decide(close, "instagram", AccessAction.ACCOUNT_ACCESS, ctx).verdict)
        // Yeniden başlatma bildirimi olmadan zaman aralığı yetmez.
        val noFresh = ledgerOf(report(), report(at = t0 + 15 * minute))
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, AccessRoutes.decide(noFresh, "instagram", AccessAction.ACCOUNT_ACCESS, ctx).verdict)
        val ok = ledgerOf(report(), report(at = t0 + 15 * minute, fresh = true))
        val d = AccessRoutes.decide(ok, "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertEquals(RouteVerdict.VERIFIED_ROUTE, d.verdict)
        assertEquals(AccessSurface.OFFICIAL_APP, d.recommended?.surface)
        assertEquals(2, d.recommended?.confirmations)
    }

    @Test fun laterFailureOverridesEarlierSuccess() {
        val l = ledgerOf(report(), report(at = t0 + 15 * minute, fresh = true), report(RouteOutcome.NOT_WORKING, at = t0 + 20 * minute))
        val d = AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertEquals(RouteVerdict.NO_WORKING_ROUTE, d.verdict)
        assertNull(d.recommended)
    }

    /** Aynı hesap teyidi olmadan hesap işlevinde başarı sayılmaz. */
    @Test fun accountActionNeedsSameAccountConfirmation() {
        val l = ledgerOf(report(account = AccountContext.LOGGED_OUT), report(account = AccountContext.UNKNOWN, at = t0 + 15 * minute, fresh = true))
        val d = AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertNotEquals(RouteVerdict.VERIFIED_ROUTE, d.verdict)
        assertNull(d.recommended)
        // Girişsiz içerik görüntüleme ise ayrı bir işlevdir ve sayılabilir — ama "hesabın değil" diye etiketlenir.
        val view = ledgerOf(report(action = AccessAction.VIEW_CONTENT, account = AccountContext.LOGGED_OUT))
        val v = AccessRoutes.decide(view, "instagram", AccessAction.VIEW_CONTENT, ctx)
        assertEquals(RouteVerdict.TENTATIVE_ROUTE, v.verdict)
        assertTrue(v.explanation.contains("girişsiz"))
    }

    /** Yüzey ve işlem izolasyonu: web'deki başarı uygulamaya, içerik başarısı mesajlaşmaya yazılmaz. */
    @Test fun surfacesAndActionsAreIsolated() {
        val l = ledgerOf(report(surface = AccessSurface.OFFICIAL_WEB), report(RouteOutcome.VERIFICATION_REQUIRED, at = t0 + minute))
        val d = AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertEquals(AccessSurface.OFFICIAL_WEB, d.recommended?.surface)
        assertEquals(RouteStatus.PLATFORM_GATE, d.surfaces.first { it.surface == AccessSurface.OFFICIAL_APP }.status)
        assertEquals(RouteVerdict.NOT_TESTED, AccessRoutes.decide(l, "instagram", AccessAction.MESSAGING, ctx).verdict)
        assertEquals(RouteVerdict.NOT_TESTED, AccessRoutes.decide(l, "youtube", AccessAction.ACCOUNT_ACCESS, ctx).verdict)
    }

    /** Hipotez 2: doğrulama ekranı anında ağ kapalıysa sunucu şartı sayılmaz. */
    @Test fun verificationScreenWithBrokenNetworkIsNotCalledServerGate() {
        val l = ledgerOf(report(RouteOutcome.VERIFICATION_REQUIRED, network = "FAIL"))
        val d = AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertEquals(GateCause.NETWORK_PROBLEM_SUSPECTED, d.surfaces.single().gateCause)
        assertEquals(RouteVerdict.NO_WORKING_ROUTE, d.verdict)
        assertTrue(d.explanation.contains("bağlantı yöntemlerini dene"))
    }

    /** Bütün yüzeyler aynı sunucu şartıyla engelliyse bu açıkça söylenir; seçenek adı değiştirip çözülmüş gösterilmez. */
    @Test fun allSurfacesGatedIsReportedHonestly() {
        val l = ledgerOf(report(RouteOutcome.VERIFICATION_REQUIRED), report(RouteOutcome.VERIFICATION_REQUIRED, surface = AccessSurface.OFFICIAL_WEB, at = t0 + minute))
        val d = AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertEquals(RouteVerdict.ALL_SURFACES_GATED, d.verdict)
        assertNull(d.recommended)
        assertTrue(d.explanation.contains("sunucu"))
    }

    /** Profil (motor tek ve ortak), ağ ya da süreç değişince eski kayıt güncel gösterilmez. */
    @Test fun profileNetworkOrProcessChangeMakesRecordsStale() {
        val l = ledgerOf(report(), report(at = t0 + 15 * minute, fresh = true))
        // Başka bir platform için global profil değişti: Instagram kaydı da bayatlar.
        val otherProfile = ctx.copy(profileKey = "plan=P5|dns=cloudflare|quic=off|scope=all")
        assertEquals(RouteVerdict.NEEDS_REVERIFICATION, AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, otherProfile).verdict)
        assertEquals(RouteVerdict.NEEDS_REVERIFICATION, AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx.copy(networkKey = "WIFI:TurkTelekom")).verdict)
        val restarted = AccessRoutes.decide(l, "instagram", AccessAction.ACCOUNT_ACCESS, ctx.copy(processId = "proc-2"))
        assertEquals(RouteVerdict.NEEDS_REVERIFICATION, restarted.verdict)
        assertEquals(RouteStatus.PREVIOUS_SESSION, restarted.surfaces.single().status)
        assertNull(restarted.recommended)
    }

    @Test fun unsureIsNeitherSuccessNorFailure() {
        val d = AccessRoutes.decide(ledgerOf(report(RouteOutcome.UNSURE)), "instagram", AccessAction.ACCOUNT_ACCESS, ctx)
        assertNull(d.recommended)
        assertNotEquals(RouteVerdict.NO_WORKING_ROUTE, d.verdict)
        assertNotEquals(RouteVerdict.ALL_SURFACES_GATED, d.verdict)
    }

    @Test fun jsonRoundTripAndFutureSchemaIsNotOverwritten() {
        val l = AccessRoutes.setAccountAge(ledgerOf(report(), report(surface = AccessSurface.OFFICIAL_WEB, at = t0 + minute)), "instagram", AccountAge.BEFORE_REGULATION)
        val back = AccessRoutes.decode(AccessRoutes.encode(l).toString())
        assertTrue(back.readable)
        assertEquals(l, back.ledger)
        // Daha yeni sürümün yazdığı dosya: okunamaz işaretlenir (yedeklenir), başarıya çevrilmez.
        val future = AccessRoutes.decode(JSONObject().put("schemaVersion", 2).put("reports", org.json.JSONArray()).toString())
        assertFalse(future.readable)
        assertFalse(AccessRoutes.decode("{bozuk").readable)
        assertTrue(AccessRoutes.decode(null).readable)
    }

    @Test fun malformedOrUnknownValuesAreDroppedNotPromoted() {
        val good = AccessRoutes.encode(ledgerOf(report())).getJSONArray("reports").getJSONObject(0)
        fun with(key: String, value: Any) = JSONObject(good.toString()).put(key, value)
        val raw = JSONObject().put("schemaVersion", 1).put("reports", org.json.JSONArray(listOf(
            good, with("outcome", "SUCCESS"), with("surface", "ANONYMOUS"), with("platformId", "myspace"), with("freshStart", "yes"),
            with("networkAtReport", "OK"), with("atMs", "dün"), with("routePath", "VPN"))))
        val decoded = AccessRoutes.decode(raw.toString())
        assertEquals(1, decoded.ledger.reports.size)
        assertEquals(7, decoded.dropped)
    }

    /** Kabul 16: tanılama çıktısında bağlantı, süreç kimliği, tarayıcı paketi ve ISS adı yok. */
    @Test fun diagnosticExportIsMasked() {
        val text = AccessRoutes.diagnostic(ledgerOf(report(surface = AccessSurface.OFFICIAL_WEB)), t0).toString()
        assertFalse(text.contains("://"))
        assertFalse(text.contains("proc-1"))
        assertFalse(text.contains("firefox"))
        assertFalse(text.contains("Vodafone"))
        assertTrue(text.contains("CELLULAR"))
    }

    /** Hipotez 3: geçiş dönemi notu yalnız resmî metindeki tarihlerle. */
    @Test fun transitionNoteUsesOnlyVerifiedDates() {
        assertNotNull(AccessRoutes.transitionNote(null, LocalDate.of(2026, 10, 26)))
        assertTrue(AccessRoutes.transitionNote(AccountAge.BEFORE_REGULATION, LocalDate.of(2026, 11, 15))!!.contains("31.03.2027"))
        assertNull(AccessRoutes.transitionNote(AccountAge.BEFORE_REGULATION, LocalDate.of(2027, 4, 1)))
        assertTrue(AccessRoutes.transitionNote(AccountAge.AFTER_REGULATION, LocalDate.of(2026, 11, 15))!!.contains("hesap açılışında"))
        assertNull(AccessRoutes.transitionNote(AccountAge.UNKNOWN, LocalDate.of(2026, 11, 15)))
    }
}
