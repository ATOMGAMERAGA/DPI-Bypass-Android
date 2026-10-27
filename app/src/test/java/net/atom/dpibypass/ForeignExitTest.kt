package net.atom.dpibypass

import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.ExitConnector
import net.atom.dpibypass.social.ExitMatcher
import net.atom.dpibypass.social.ExitProbe
import net.atom.dpibypass.social.ExitServer
import net.atom.dpibypass.social.ExitStatus
import net.atom.dpibypass.social.ExitStatusBoard
import net.atom.dpibypass.social.Sniff
import net.atom.dpibypass.ui.social.ExitHeadline
import net.atom.dpibypass.ui.social.ExitView
import net.atom.dpibypass.vpn.ExitConfig
import net.atom.dpibypass.vpn.ExitRouter
import net.atom.dpibypass.vpn.TunnelConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

class ForeignExitTest {

    // ---- sunucu adresi ----

    @Test fun parsesCommonServerFormats() {
        assertEquals(ExitServer(ExitServer.Kind.SOCKS5, "85.10.20.30", 1080, "ali", "gizli"), ExitServer.parse("socks5://ali:gizli@85.10.20.30:1080"))
        assertEquals(ExitServer(ExitServer.Kind.SOCKS5, "de.example.net", 1080), ExitServer.parse("socks5h://de.example.net:1080"))
        assertEquals(ExitServer(ExitServer.Kind.HTTP, "proxy.example.com", 8080, "u", "p@ss"), ExitServer.parse("http://u:p%40ss@proxy.example.com:8080"))
        assertEquals(ExitServer(ExitServer.Kind.SOCKS5, "1.2.3.4", 1080), ExitServer.parse(" 1.2.3.4:1080 "))
        assertEquals(ExitServer(ExitServer.Kind.SOCKS5, "1.2.3.4", 1080, "user", "pass"), ExitServer.parse("1.2.3.4:1080:user:pass"))
    }

    @Test fun rejectsInvalidServers() {
        listOf("", "sunucu", "1.2.3.4", "1.2.3.4:0", "1.2.3.4:70000", "ftp://a:21", "socks5://a", "a b:1080", "1.2.3.4:1080:user")
            .forEach { assertNull(it, ExitServer.parse(it)) }
    }

    @Test fun displayNeverShowsPassword() {
        val s = ExitServer.parse("socks5://ali:cokgizli@1.2.3.4:1080")!!
        assertFalse(s.display.contains("cokgizli"))
        assertTrue(s.display.contains("ali"))
    }

    // ---- alan adı eşleme ----

    @Test fun matcherCoversSubdomainsOnly() {
        val m = ExitMatcher.of(setOf("instagram"), "ornek.com, *.cdn.test")
        assertTrue(m.matches("instagram.com"))
        assertTrue(m.matches("i.instagram.com"))
        assertTrue(m.matches("scontent-fra5-1.cdninstagram.com"))
        assertTrue(m.matches("WWW.Ornek.Com."))
        assertTrue(m.matches("a.cdn.test"))
        assertFalse(m.matches("notinstagram.com"))
        assertFalse(m.matches("instagram.com.evil.org"))
        assertFalse(m.matches("turkiye.gov.tr"))
        assertFalse(m.matches(null))
    }

    @Test fun exitConfigNeedsServerAndTargets() {
        val base = Settings(exitEnabled = true, exitServer = "socks5://1.2.3.4:1080", exitPlatforms = setOf("x"))
        assertNotNull(ExitConfig.from(base))
        assertNull(ExitConfig.from(base.copy(exitEnabled = false)))
        assertNull(ExitConfig.from(base.copy(exitServer = "bozuk")))
        assertNull(ExitConfig.from(base.copy(exitPlatforms = emptySet())))
        assertNotNull(ExitConfig.from(base.copy(exitPlatforms = emptySet(), exitExtraDomains = "ornek.com")))
        // Tünel yeniden kurulumu sunucu değişince tetiklenir.
        assertFalse(TunnelConfig.from(base) == TunnelConfig.from(base.copy(exitServer = "socks5://5.6.7.8:1080")))
    }

    // ---- ilk paketten alan adı ----

    @Test fun readsSniFromRealClientHello() {
        val hello = clientHello("www.instagram.com")
        assertEquals(Sniff.Result.Host("www.instagram.com"), Sniff.host(hello, hello.size))
        // Parça parça gelirse: her kesimde ya daha fazla veri beklenir ya doğru ad okunur; asla "bilinmiyor" denmez.
        for (len in 1 until hello.size) {
            val r = Sniff.host(hello, len)
            assertTrue("kesim $len: $r", r == Sniff.Result.NeedMore || r == Sniff.Result.Host("www.instagram.com"))
        }
        assertEquals(Sniff.Result.NeedMore, Sniff.host(hello, 3))
    }

    @Test fun readsHttpHost() {
        val req = "GET / HTTP/1.1\r\nUser-Agent: x\r\nHost: x.com:80\r\n\r\n".toByteArray()
        assertEquals(Sniff.Result.Host("x.com"), Sniff.host(req, req.size))
        assertEquals(Sniff.Result.NeedMore, Sniff.host(req, 2))
        val junk = byteArrayOf(0x50, 0x01, 0x02, 0x03)
        assertEquals(Sniff.Result.Unknown, Sniff.host(junk, junk.size))
    }

    @Test fun udpPortIsReadFromSocksHeader() {
        val v4 = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0x01, 0xBB.toByte(), 9, 9)
        assertEquals(443, ExitRouter.udpDestinationPort(v4, v4.size))
        val dom = byteArrayOf(0, 0, 0, 3, 1, 'a'.code.toByte(), 0, 53)
        assertEquals(53, ExitRouter.udpDestinationPort(dom, dom.size))
        assertEquals(-1, ExitRouter.udpDestinationPort(v4, 5))
    }

    // ---- yönlendirici: gerçek soketlerle ----

    private val closeables = mutableListOf<AutoCloseable>()
    @After fun tearDown() { closeables.reversed().forEach { runCatching { it.close() } } }

    @Test fun platformTrafficGoesAbroadWithDomainAndOtherTrafficGoesToByeDpi() {
        val byeDpi = FakeSocks().also { closeables += it }
        val abroad = FakeSocks(user = "ali", pass = "gizli").also { closeables += it }
        val router = startRouter(byeDpi, ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", abroad.port, "ali", "gizli"))

        val hello = clientHello("i.instagram.com")
        assertEquals("OK", hevConnect(router, "157.240.1.1", 443, hello))
        val seen = abroad.requests.single()
        assertEquals("i.instagram.com:443", seen.target)
        assertTrue("ilk paket aynen iletilir", seen.first.contentEquals(hello))
        assertTrue("platform trafiği ByeDPI'a hiç gitmez", byeDpi.requests.isEmpty())

        val other = clientHello("www.turkiye.gov.tr")
        assertEquals("OK", hevConnect(router, "94.55.118.33", 443, other))
        val direct = byeDpi.requests.single()
        assertEquals("94.55.118.33:443", direct.target)
        assertTrue(direct.first.contentEquals(other))
        assertEquals(1, abroad.requests.size)
    }

    @Test fun nonWebPortsPassThroughToByeDpiUntouched() {
        val byeDpi = FakeSocks(firstBytes = 3).also { closeables += it }
        val router = startRouter(byeDpi, ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", 1))
        assertEquals("OK", hevConnect(router, "10.0.0.1", 5222, "abc".toByteArray(), expectReplyFrom = "upstream"))
        assertEquals("10.0.0.1:5222", byeDpi.requests.single().target)
    }

    @Test fun deadServerClosesPlatformConnectionInsteadOfLeaking() {
        val byeDpi = FakeSocks().also { closeables += it }
        val dead = ServerSocket(0).also { it.close() }.localPort
        val router = startRouter(byeDpi, ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", dead))
        val before = ExitStatusBoard.status.value.failures
        assertEquals("", hevConnect(router, "157.240.1.1", 443, clientHello("www.instagram.com")))
        assertTrue("Türkiye'den doğrudan gönderilmez", byeDpi.requests.isEmpty())
        assertEquals(before + 1, ExitStatusBoard.status.value.failures)
    }

    @Test fun httpConnectUpstreamWorks() {
        val proxy = FakeHttpProxy().also { closeables += it }
        val socket = ExitConnector.open(ExitServer(ExitServer.Kind.HTTP, "127.0.0.1", proxy.port, "u", "p"), "x.com", 443, 3_000)
        socket.use { assertTrue(proxy.awaitRequest().startsWith("CONNECT x.com:443 HTTP/1.1")) }
        assertTrue(proxy.lastRequest.contains("Proxy-Authorization: Basic dTpw"))
    }

    @Test fun wrongPasswordIsReportedInTurkish() {
        val abroad = FakeSocks(user = "ali", pass = "dogru").also { closeables += it }
        val e = runCatching { ExitConnector.open(ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", abroad.port, "ali", "yanlis"), "x.com", 443, 3_000) }.exceptionOrNull()
        assertEquals("Kullanıcı adı ya da parola kabul edilmedi", e?.message)
    }

    // ---- ekran durumu ----

    @Test fun headlineFollowsSetupOrder() {
        val ok = ExitProbe.Result(true, "DE", "5.6.7.8", 120)
        val s = Settings(loaded = true)
        val running = ExitStatus(running = true)
        assertEquals(ExitHeadline.OFF, ExitView.headline(s, ConnectionState.Connected, running, ok))
        val on = s.copy(exitEnabled = true)
        assertEquals(ExitHeadline.NEED_PLATFORMS, ExitView.headline(on, ConnectionState.Connected, running, ok))
        val withApps = on.copy(exitPlatforms = setOf("instagram"))
        assertEquals(ExitHeadline.NEED_SERVER, ExitView.headline(withApps, ConnectionState.Connected, running, ok))
        val ready = withApps.copy(exitServer = "1.2.3.4:1080")
        assertEquals(ExitHeadline.NEED_CONNECT, ExitView.headline(ready, ConnectionState.Disconnected, ExitStatus(), null))
        assertEquals(ExitHeadline.APPLYING, ExitView.headline(ready, ConnectionState.Connected, ExitStatus(), ok))
        assertEquals(ExitHeadline.CHECKING, ExitView.headline(ready, ConnectionState.Connected, running, null))
        assertEquals(ExitHeadline.WORKING, ExitView.headline(ready, ConnectionState.Connected, running, ok))
        assertEquals(ExitHeadline.SERVER_IN_TURKEY, ExitView.headline(ready, ConnectionState.Connected, running, ok.copy(countryCode = "TR")))
        assertEquals(ExitHeadline.SERVER_DOWN, ExitView.headline(ready, ConnectionState.Connected, running, ExitProbe.Result(false, error = "x")))
    }

    @Test fun platformAppsOutsideScopeAreReported() {
        val s = Settings(exitEnabled = true, exitPlatforms = setOf("instagram"),
            appFilterMode = net.atom.dpibypass.data.AppFilterMode.Include, selectedApps = setOf("com.discord"))
        assertEquals(setOf("com.instagram.android"), ExitView.outOfScope(s, setOf("com.instagram.android", "com.discord")))
        assertTrue(ExitView.outOfScope(s.copy(appFilterMode = net.atom.dpibypass.data.AppFilterMode.All), setOf("com.instagram.android")).isEmpty())
    }

    // ---- yardımcılar ----

    private fun startRouter(byeDpi: FakeSocks, server: ExitServer): Int {
        val port = ServerSocket(0).use { it.localPort }
        val router = ExitRouter(port, byeDpi.port, server, ExitMatcher.of(setOf("instagram"), ""))
        router.start()
        closeables += router
        return port
    }

    /** hev'in yaptığı gibi: SOCKS5 CONNECT (IP ile), sonra uygulamanın ilk paketi; dönen yanıtı okur. */
    private fun hevConnect(routerPort: Int, ip: String, port: Int, first: ByteArray, expectReplyFrom: String = "router"): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", routerPort), 2_000)
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            input.readFully(ByteArray(2))
            val addr = InetAddress.getByName(ip).address
            out.write(byteArrayOf(5, 1, 0, 1) + addr + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
            val reply = ByteArray(10)
            input.readFully(reply)
            assertEquals("SOCKS yanıtı başarılı ($expectReplyFrom)", 0, reply[1].toInt())
            out.write(first); out.flush()
            val buf = ByteArray(2)
            return try { input.readFully(buf); String(buf) } catch (e: Exception) { "" }
        }
    }

    private fun clientHello(host: String): ByteArray {
        val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine(host, 443)
        engine.useClientMode = true
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.beginHandshake()
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    /** Kayıt tutan küçük SOCKS5 sunucusu: bağlantıyı kabul eder, ilk paketi okur, "OK" yazar. */
    private class FakeSocks(val user: String? = null, val pass: String? = null, val firstBytes: Int = -1) : AutoCloseable {
        data class Seen(val target: String, val first: ByteArray)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val requests = CopyOnWriteArrayList<Seen>()
        private val thread = Thread {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { serve(c) }; runCatching { c.close() } }.start()
            }
        }.apply { isDaemon = true; start() }

        private fun serve(c: Socket) {
            c.soTimeout = 5_000
            val input = DataInputStream(c.getInputStream())
            val out = c.getOutputStream()
            input.readByte()
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (user != null) {
                if (2.toByte() !in methods) { out.write(byteArrayOf(5, 0xFF.toByte())); return }
                out.write(byteArrayOf(5, 2))
                input.readByte()
                val u = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                val p = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                val ok = u == user && p == pass
                out.write(byteArrayOf(1, if (ok) 0 else 1))
                if (!ok) return
            } else out.write(byteArrayOf(5, 0))
            val head = ByteArray(4).also { input.readFully(it) }
            val host = when (head[3].toInt()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                else -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
            }
            val port = input.readUnsignedShort()
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val first = ByteArray(4096)
            val n = if (firstBytes > 0) { input.readFully(first, 0, firstBytes); firstBytes } else input.read(first)
            requests += Seen("$host:$port", first.copyOf(maxOf(n, 0)))
            out.write("OK".toByteArray()); out.flush()
        }

        override fun close() { server.close() }
    }

    private class FakeHttpProxy : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        @Volatile var lastRequest = ""
        private val got = CountDownLatch(1)
        init {
            Thread {
                runCatching {
                    server.accept().use { c ->
                        val sb = StringBuilder()
                        val input = c.getInputStream()
                        while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().toChar())
                        lastRequest = sb.toString()
                        got.countDown()
                        c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        Thread.sleep(200)
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        fun awaitRequest(): String { got.await(5, TimeUnit.SECONDS); return lastRequest }
        override fun close() { server.close() }
    }
}
