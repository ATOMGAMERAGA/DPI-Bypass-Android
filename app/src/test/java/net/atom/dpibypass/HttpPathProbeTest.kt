package net.atom.dpibypass

import kotlinx.coroutines.runBlocking
import net.atom.dpibypass.social.HttpPathProbe
import net.atom.dpibypass.social.NetworkEvidence
import net.atom.dpibypass.social.SocialText
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Ağ yolu ölçümünün KENDİ yerel TLS test sunucumuza karşı sınanması: bilinen
 * durumlar (giriş duvarı 200, 429, 5xx, HEAD 405, güvenilmeyen/yanlış sertifika,
 * bağlantı kesilmesi, zaman aşımı, DNS hatası) üretilir ve sınıflandırma doğrulanır.
 * Gerçek sosyal platformlara istek atılmaz. TLS doğrulaması kapatılmaz: istemci
 * yalnız test CA'sına güvenir.
 */
class HttpPathProbeTest {
    private val host = "probe.test"
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("DPI Bypass test CA").build()
    private val serverCert = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName(host).build()
    private val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert, ca.certificate).build().sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun probe(trust: HandshakeCertificates = clientTrust, dns: (String) -> List<InetAddress> = { if (it == host) listOf(InetAddress.getByName("127.0.0.1")) else throw UnknownHostException(it) },
        readTimeoutMs: Long = 2_000): NetworkEvidence = runBlocking {
        HttpPathProbe(dns = dns, connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs, callTimeoutMs = 5_000,
            sslSocketFactory = trust.sslSocketFactory(), trustManager = trust.trustManager, clock = { 0L })
            .probe("https://$host:${server.port}/", "DIRECT_CONTROL")
    }

    /** Kabul 1: HTTP 200 + giriş duvarı gövdesi → yalnız ağ yolu açık; gövde okunmaz, içerik başarısı değildir. */
    @Test fun loginWall200IsNetworkPathOnly() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<form action=\"/accounts/login\">"))
        val e = probe()
        assertEquals("PASS", e.technicalOutcome)
        assertTrue(e.reason.endsWith("NETWORK_PATH_ONLY"))
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(1, e.requests)
    }

    /** Kabul 12: 429 + Retry-After → belirsiz, hedefe özel bekleme süresi okunur. */
    @Test fun rateLimitCarriesRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val e = probe()
        assertEquals("UNKNOWN", e.technicalOutcome)
        assertEquals("RATE_LIMITED", e.failureClass)
        assertEquals(120, e.retryAfterSeconds)
    }

    @Test fun serverErrorIsUncertainNotBlocked() {
        server.enqueue(MockResponse().setResponseCode(502))
        val e = probe()
        assertEquals("UNKNOWN", e.technicalOutcome)
        assertEquals("SERVICE_ERROR", e.failureClass)
    }

    /** HEAD 405 → tek ve sınırlı GET; iki gerçek istek sayılır. */
    @Test fun headNotAllowedFallsBackToSingleRangedGet() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        val e = probe()
        assertEquals("PASS", e.technicalOutcome)
        assertEquals(2, e.requests)
        assertEquals("HEAD", server.takeRequest().method)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("bytes=0-0", get.getHeader("Range"))
    }

    /** Güvenilmeyen sertifika (giriş portalı/araya giren sayfa) DPI kesintisi sanılmaz. */
    @Test fun untrustedCertificateIsNotReportedAsDpi() {
        server.enqueue(MockResponse().setResponseCode(200))
        val otherCa = HeldCertificate.Builder().certificateAuthority(0).build()
        val e = probe(trust = HandshakeCertificates.Builder().addTrustedCertificate(otherCa.certificate).build())
        assertEquals("FAIL", e.technicalOutcome)
        assertEquals("TLS_CERT_UNTRUSTED", e.failureClass)
        assertFalse(SocialText.failure(e.failureClass).contains("SNI/DPI"))
        assertTrue("Başarısız deneme rezervin tamamı sayılmaz", e.bytes < 65_536)
    }

    /** Sertifika başka bir alan adına aitse hostname doğrulaması reddeder. */
    @Test fun certificateForAnotherHostIsAMismatch() {
        server.enqueue(MockResponse().setResponseCode(200))
        val e = runBlocking {
            HttpPathProbe(dns = { listOf(InetAddress.getByName("127.0.0.1")) }, connectTimeoutMs = 2_000, readTimeoutMs = 2_000, callTimeoutMs = 5_000,
                sslSocketFactory = clientTrust.sslSocketFactory(), trustManager = clientTrust.trustManager)
                .probe("https://other.test:${server.port}/", "DIRECT_CONTROL")
        }
        assertEquals("FAIL", e.technicalOutcome)
        assertEquals("TLS_CERT_MISMATCH", e.failureClass)
    }

    /** El sıkışması sırasında bağlantının kesilmesi (DPI benzeri) ölçülebilir hata olarak kalır. */
    @Test fun connectionDroppedDuringHandshakeIsMeasurableFailure() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = probe()
        assertNotEquals("PASS", e.technicalOutcome)
        assertNotEquals("TLS_CERT_UNTRUSTED", e.failureClass)
    }

    /** Tek zaman aşımı kesin engel değildir. */
    @Test fun timeoutStaysUncertain() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = probe(readTimeoutMs = 500)
        assertEquals("UNKNOWN", e.technicalOutcome)
    }

    @Test fun dnsFailureAndNonPublicAnswerAreDistinct() {
        val failed = probe(dns = { throw UnknownHostException(it) })
        assertEquals("DNS_FAILURE", failed.failureClass)
        val private = probe(dns = { throw UnknownHostException("PUBLIC_TARGET_REQUIRED") })
        assertEquals("DNS_NON_PUBLIC_ANSWER", private.failureClass)
        assertEquals("FAIL", private.technicalOutcome)
    }
}
