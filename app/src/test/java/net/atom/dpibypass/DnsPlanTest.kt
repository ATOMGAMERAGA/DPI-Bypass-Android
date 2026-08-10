package net.atom.dpibypass

import net.atom.dpibypass.dns.CustomDns
import net.atom.dpibypass.dns.DnsPlanner
import net.atom.dpibypass.dns.DohProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DNS seçiminin GERÇEKTEN uygulanmasının testleri.
 *
 * Buradaki her durum, daha önce sessizce Cloudflare'a düşen ya da hiçbir sorguyu
 * çözemeyen bir girdiye karşılık gelir.
 */
class DnsPlanTest {

    // ---- sağlayıcı seçimi ----

    @Test
    fun provider_supplies_two_addresses_from_the_same_operator() {
        for (provider in DohProvider.entries) {
            assertEquals(
                "${provider.name} iki adres vermeli (yedeği ISS'in sunucusu olmamalı)",
                2,
                provider.dnsServers.size,
            )
            assertTrue(provider.dnsServers.all { DnsPlanner.isIpLiteral(it) })
            // DoH URL'i de IP'ye bağlanmalı: ad çözümü için sisteme dönmek,
            // ele geçirilmiş sunucuya sormak olurdu.
            val host = provider.url.removePrefix("https://").substringBefore('/')
            assertTrue("${provider.name} DoH adresi IP olmalı", DnsPlanner.isIpLiteral(host))
        }
    }

    @Test
    fun blankCustom_usesSelectedProvider() {
        val plan = DnsPlanner.plan(DohProvider.Google, "   ")
        assertEquals(DohProvider.Google.url, plan.dohUrl)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), plan.servers)
        assertFalse(plan.custom)
        assertNull(plan.warning)
    }

    @Test
    fun eachProviderChangesWhatAppsGet() {
        val servers = DohProvider.entries.map { DnsPlanner.plan(it, "").servers }
        assertEquals("her sağlayıcı farklı adres vermeli", servers.size, servers.toSet().size)
    }

    // ---- özel alan: düz IP ----

    @Test
    fun bareIp_isAcceptedAsPlainDnsAndAsDohEndpoint() {
        val plan = DnsPlanner.plan(DohProvider.Cloudflare, "9.9.9.9")
        assertEquals(listOf("9.9.9.9"), plan.servers)
        assertEquals("https://9.9.9.9/dns-query", plan.dohUrl)
        assertTrue(plan.custom)
    }

    @Test
    fun severalBareIps_areKeptInOrder() {
        val plan = DnsPlanner.plan(DohProvider.Cloudflare, "9.9.9.9, 149.112.112.112")
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), plan.servers)
    }

    // ---- özel alan: ana bilgisayar adı ----

    @Test
    fun hostname_isResolvedThroughTheProvidersDoh() {
        var bootstrapUrl: String? = null
        val plan = DnsPlanner.plan(DohProvider.AdGuardAds, "dns.adguard.com") { host, url ->
            bootstrapUrl = url
            if (host == "dns.adguard.com") listOf("94.140.14.14", "94.140.15.15") else emptyList()
        }
        // Bootstrap, sistem DNS'ine değil seçili sağlayıcının DoH'una gider.
        assertEquals(DohProvider.AdGuardAds.url, bootstrapUrl)
        assertEquals(listOf("94.140.14.14", "94.140.15.15"), plan.servers)
        assertEquals("https://dns.adguard.com/dns-query", plan.dohUrl)
        assertTrue(plan.custom)
        assertNull(plan.warning)
    }

    /**
     * Eski davranış: ad çözülemeyince SESSİZCE 1.1.1.1'e düşülürdü ve kullanıcı
     * "DNS'im etki etmiyor" derdi. Artık düşülüyor ama sebebi söyleniyor.
     */
    @Test
    fun unresolvableHostname_fallsBackLoudly() {
        val plan = DnsPlanner.plan(DohProvider.Google, "dns.example.invalid") { _, _ -> emptyList() }
        assertEquals(DohProvider.Google.dnsServers, plan.servers)
        assertFalse(plan.custom)
        assertNotNull(plan.warning)
        assertTrue(plan.warning!!.contains("dns.example.invalid"))
    }

    /**
     * Arayüz önizlemesinin çözümleyicisi yoktur. "Çözülemedi" demek yanlış olurdu:
     * ad bağlanırken çözülecek. İkisi ayrı işaretlerle bildirilir.
     */
    @Test
    fun previewWithoutResolver_marksHostPendingRatherThanFailed() {
        val plan = DnsPlanner.plan(DohProvider.Cloudflare, "dns.adguard.com")
        assertEquals("dns.adguard.com", plan.pendingHost)
        assertNull(plan.warning)
        // Yedek olarak sağlayıcının adresleri gösterilir.
        assertEquals(DohProvider.Cloudflare.dnsServers, plan.servers)
    }

    @Test
    fun literalCustom_isNeverPending() {
        assertNull(DnsPlanner.plan(DohProvider.Cloudflare, "9.9.9.9").pendingHost)
        assertNull(DnsPlanner.plan(DohProvider.Cloudflare, "").pendingHost)
    }

    @Test
    fun resolverFailure_doesNotCrashThePlan() {
        val plan = DnsPlanner.plan(DohProvider.Cloudflare, "dns.quad9.net") { _, _ ->
            throw IllegalStateException("ağ yok")
        }
        assertEquals(DohProvider.Cloudflare.dnsServers, plan.servers)
        assertNotNull(plan.warning)
    }

    // ---- özel alan: URL biçimleri ----

    @Test
    fun fullDohUrl_isKeptVerbatim() {
        val plan = DnsPlanner.plan(DohProvider.Cloudflare, "https://94.140.14.14/dns-query")
        assertEquals("https://94.140.14.14/dns-query", plan.dohUrl)
        assertEquals(listOf("94.140.14.14"), plan.servers)
    }

    @Test
    fun missingPath_getsTheConventionalDnsQueryPath() {
        assertEquals(
            CustomDns.Named("https://dns.adguard.com/dns-query", "dns.adguard.com"),
            DnsPlanner.parseCustom("https://dns.adguard.com"),
        )
    }

    @Test
    fun plainHttp_isRejected() {
        val parsed = DnsPlanner.parseCustom("http://1.1.1.1/dns-query")
        assertTrue(parsed is CustomDns.Invalid)
    }

    @Test
    fun garbage_isRejectedInsteadOfBecomingABrokenEndpoint() {
        assertTrue(DnsPlanner.parseCustom("bu bir dns değil") is CustomDns.Invalid)
        val plan = DnsPlanner.plan(DohProvider.Cloudflare, "?????")
        assertEquals(DohProvider.Cloudflare.dnsServers, plan.servers)
        assertNotNull(plan.warning)
    }

    @Test
    fun blankInput_parsesToNothing() {
        assertNull(DnsPlanner.parseCustom(""))
        assertNull(DnsPlanner.parseCustom("  \n "))
    }

    // ---- ayrıştırıcı yardımcıları ----

    @Test
    fun ipLiteralDetection() {
        assertTrue(DnsPlanner.isIpLiteral("1.1.1.1"))
        assertTrue(DnsPlanner.isIpLiteral("149.112.112.112"))
        assertTrue(DnsPlanner.isIpLiteral("2606:4700:4700::1111"))
        assertFalse(DnsPlanner.isIpLiteral("1.1.1"))
        assertFalse(DnsPlanner.isIpLiteral("1.1.1.256"))
        assertFalse(DnsPlanner.isIpLiteral("dns.google"))
        assertFalse(DnsPlanner.isIpLiteral(""))
    }

    /**
     * `effectiveDohUrl` üzerinden geçen yol: çıplak IP yazıldığında eskiden
     * "9.9.9.9?name=…" gibi geçersiz bir istek kuruluyor, hiçbir sorgu
     * çözülemiyordu.
     */
    @Test
    fun dohUrl_normalisesEveryAcceptedForm() {
        assertEquals(
            "https://9.9.9.9/dns-query",
            DnsPlanner.dohUrl(DohProvider.Cloudflare, "9.9.9.9"),
        )
        assertEquals(
            "https://dns.quad9.net/dns-query",
            DnsPlanner.dohUrl(DohProvider.Cloudflare, "dns.quad9.net"),
        )
        assertEquals(
            DohProvider.Cloudflare.url,
            DnsPlanner.dohUrl(DohProvider.Cloudflare, "geçersiz girdi"),
        )
        assertEquals(
            DohProvider.Google.url,
            DnsPlanner.dohUrl(DohProvider.Google, ""),
        )
    }
}
