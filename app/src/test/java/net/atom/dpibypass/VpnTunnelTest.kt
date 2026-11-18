package net.atom.dpibypass

import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.ExitMatcher
import net.atom.dpibypass.social.ExitProbe
import net.atom.dpibypass.social.ExitServer
import net.atom.dpibypass.ui.AppEntry
import net.atom.dpibypass.ui.vpn.VpnHeadline
import net.atom.dpibypass.ui.vpn.VpnView
import net.atom.dpibypass.vpn.AppScope
import net.atom.dpibypass.vpn.ConnectionOwner
import net.atom.dpibypass.vpn.ExitConfig
import net.atom.dpibypass.vpn.ExitRouter
import net.atom.dpibypass.vpn.ProcNet
import net.atom.dpibypass.vpn.ScopeDecision
import net.atom.dpibypass.vpn.TunnelConfig
import net.atom.dpibypass.vpn.VpnTunnelConfig
import net.atom.dpibypass.wg.WgEngine
import net.atom.dpibypass.wg.WgStatus
import net.atom.dpibypass.wg.WireGuardConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class VpnTunnelTest {

    // Proton VPN'in "WireGuard configuration" indirmesine benzer (anahtarlar örnek).
    private val proton = """
        [Interface]
        # Key for DPI Bypass
        # NetShield = 0
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        # NL-FREE#1
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 185.107.56.1:51820
    """.trimIndent()

    private val windscribe = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 100.98.1.2/32, fd00::2/128
        DNS = 10.255.255.3
        MTU = 1420

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        PresharedKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = fra-216-wg.whiskergalaxy.com:443
        PersistentKeepalive = 30
    """.trimIndent()

    private val closeables = mutableListOf<AutoCloseable>()

    @After fun tearDown() { closeables.reversed().forEach { runCatching { it.close() } } }

    // ---- WireGuard yapılandırması ----

    @Test fun parsesProtonConfig() {
        val c = WireGuardConfig.parseOrNull(proton)!!
        assertEquals(WireGuardConfig.Provider.PROTON, c.provider)
        assertEquals(listOf("10.2.0.2/32"), c.addresses)
        assertEquals(listOf("10.2.0.1"), c.dns)
        assertEquals("185.107.56.1", c.endpointHost)
        assertEquals(51820, c.endpointPort)
        assertEquals(WireGuardConfig.DEFAULT_MTU, c.mtu)
        assertNull(c.presharedKey)
        val uapi = c.toUapi("185.107.56.1")
        assertTrue(uapi.startsWith("private_key=c8"))
        assertTrue("endpoint=185.107.56.1:51820" in uapi)
        assertTrue("persistent_keepalive_interval=25" in uapi)
        assertTrue("allowed_ip=0.0.0.0/0" in uapi)
        assertFalse("IPv6 adresi yoksa ::/0 eklenmez", "::/0" in uapi)
        assertFalse("anahtarlar özet metninde görünmez", c.toString().contains("yAnz"))
    }

    @Test fun parsesWindscribeConfigWithIpv6AndPsk() {
        val c = WireGuardConfig.parseOrNull(windscribe)!!
        assertEquals(WireGuardConfig.Provider.WINDSCRIBE, c.provider)
        assertEquals(listOf("100.98.1.2/32", "fd00::2/128"), c.addresses)
        assertEquals(1420, c.mtu)
        assertEquals(30, c.keepalive)
        assertNotNull(c.presharedKey)
        val uapi = c.toUapi("2a02::1")
        assertTrue("endpoint=[2a02::1]:443" in uapi)
        assertTrue("preshared_key=" in uapi)
        assertTrue("allowed_ip=::/0" in uapi)
        assertTrue("persistent_keepalive_interval=30" in uapi)
    }

    @Test fun rejectsBrokenConfigsWithReadableReasons() {
        assertTrue(WireGuardConfig.parse("") is WireGuardConfig.ParseResult.Error)
        assertTrue(WireGuardConfig.parse("socks5://1.2.3.4:1080") is WireGuardConfig.ParseResult.Error)
        val noKey = proton.replace("PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "PrivateKey = kisa")
        assertTrue((WireGuardConfig.parse(noKey) as WireGuardConfig.ParseResult.Error).message.contains("PrivateKey"))
        val noEndpoint = proton.replace("Endpoint = 185.107.56.1:51820", "")
        assertTrue((WireGuardConfig.parse(noEndpoint) as WireGuardConfig.ParseResult.Error).message.contains("Endpoint"))
        val badAddress = proton.replace("10.2.0.2/32", "10.2.0.999/32")
        assertTrue(WireGuardConfig.parse(badAddress) is WireGuardConfig.ParseResult.Error)
    }

    @Test fun endpointSplitting() {
        assertEquals("a.b" to 1, WireGuardConfig.splitEndpoint("a.b:1"))
        assertEquals("2a02::1" to 443, WireGuardConfig.splitEndpoint("[2a02::1]:443"))
        assertNull(WireGuardConfig.splitEndpoint("2a02::1:443"))
        assertNull(WireGuardConfig.splitEndpoint("host:0"))
        assertNull(WireGuardConfig.splitEndpoint("bad host:1"))
    }

    @Test fun endpointIpPrefersLiteralThenDohIpv4() {
        assertEquals("1.2.3.4", WgEngine.endpointIp("1.2.3.4") { error("çözülmemeli") })
        assertEquals("5.6.7.8", WgEngine.endpointIp("vpn.example") { listOf("2a02::5", "5.6.7.8") })
    }

    @Test fun statusParsing() {
        val s = WgStatus.parse("handshake=1700000000;rx=2048;tx=512", WgStatus(summary = "x"))
        assertTrue(s.running)
        assertEquals(2048, s.rxBytes)
        assertTrue(s.handshakeFresh(1700000100))
        assertFalse(s.handshakeFresh(1700001000))
        assertFalse(WgStatus.parse("", WgStatus(running = true)).running)
    }

    // ---- kapsam: VPN uygulamaları her kipte tünele girer ----

    @Test fun vpnAppsAreForcedIntoScope() {
        val self = "net.atom.dpibypass"
        val vpn = setOf("com.instagram.android")
        // "Sadece Discord" DPI kapsamı + Instagram VPN'de: ikisi de tünelde.
        assertEquals(ScopeDecision.Only(listOf("com.discord", "com.instagram.android")),
            AppScope.decide(AppFilterMode.Include, setOf("com.discord"), false, self, forced = vpn) { true })
        // DPI kapsamı boş olsa da VPN uygulaması tek başına tüneli kurar.
        assertEquals(ScopeDecision.Only(listOf("com.instagram.android")),
            AppScope.decide(AppFilterMode.Include, emptySet(), false, self, forced = vpn) { true })
        // "Seçili hariç" listesinde olsa bile VPN uygulaması dışarıda kalmaz.
        assertEquals(ScopeDecision.AllExcept(listOf(self, "com.bank")),
            AppScope.decide(AppFilterMode.Exclude, setOf("com.bank", "com.instagram.android"), false, self, forced = vpn) { true })
        assertEquals(AppScope.Route.Vpn, AppScope.route(AppFilterMode.Include, setOf("com.discord"), false, "com.instagram.android", vpn))
        assertEquals(AppScope.Route.Dpi, AppScope.route(AppFilterMode.Include, setOf("com.discord"), false, "com.discord", vpn))
        assertEquals(AppScope.Route.Direct, AppScope.route(AppFilterMode.Include, setOf("com.discord"), false, "com.bank", vpn))
        assertEquals(AppScope.Route.Direct, AppScope.route(AppFilterMode.Exclude, setOf("com.bank"), false, "com.bank", vpn))
    }

    @Test fun tunnelConfigTracksVpnSettings() {
        val base = Settings(loaded = true)
        assertNull(VpnTunnelConfig.from(base.copy(vpnConfig = proton)))
        val on = base.copy(vpnTunnelEnabled = true, vpnConfig = proton)
        assertNotNull(VpnTunnelConfig.from(on))
        assertNull("bozuk yapılandırma tüneli kurmaz", VpnTunnelConfig.from(on.copy(vpnConfig = "x")))
        assertFalse("uygulama ya da platform seçilmeden köprü açılmaz", VpnTunnelConfig.bridgeNeeded(on))
        assertTrue(VpnTunnelConfig.bridgeNeeded(on.copy(vpnApps = setOf("com.instagram.android"))))
        assertNotEquals(TunnelConfig.from(on), TunnelConfig.from(on.copy(vpnApps = setOf("a"))))
        assertEquals("aynı yapılandırma aynı tünel", TunnelConfig.from(on), TunnelConfig.from(on.copy()))
    }

    @Test fun socialExitCanUseVpnTunnelInsteadOfOwnServer() {
        val s = Settings(loaded = true, exitEnabled = true, exitPlatforms = setOf("instagram"), exitViaVpn = true)
        assertNull("VPN Tüneli yokken çıkış kurulmaz", ExitConfig.from(s))
        val ready = s.copy(vpnTunnelEnabled = true, vpnConfig = proton)
        val exit = ExitConfig.from(ready)!!
        assertTrue(exit.viaVpn)
        assertEquals(WgEngine.server, exit.server)
        assertTrue(VpnTunnelConfig.bridgeNeeded(ready))
        assertEquals("kendi sunucusu seçiliyse eski davranış", ExitServer.parse("1.2.3.4:1080"),
            ExitConfig.from(ready.copy(exitViaVpn = false, exitServer = "1.2.3.4:1080"))?.server)
    }

    // ---- ekran ----

    @Test fun vpnHeadlineFollowsSetupOrder() {
        val ok = ExitProbe.Result(true, "NL", "1.1.1.1", 80)
        val running = WgStatus(running = true)
        val s = Settings(loaded = true)
        assertEquals(VpnHeadline.NO_ENGINE, VpnView.headline(s, false, ConnectionState.Connected, running, ok))
        assertEquals(VpnHeadline.NEED_CONFIG, VpnView.headline(s, true, ConnectionState.Connected, running, ok))
        val cfg = s.copy(vpnConfig = proton)
        assertEquals(VpnHeadline.OFF, VpnView.headline(cfg, true, ConnectionState.Connected, running, ok))
        val on = cfg.copy(vpnTunnelEnabled = true)
        assertEquals(VpnHeadline.NEED_ROUTES, VpnView.headline(on, true, ConnectionState.Connected, running, ok))
        val apps = on.copy(vpnApps = setOf("com.instagram.android"))
        assertEquals(VpnHeadline.NEED_CONNECT, VpnView.headline(apps, true, ConnectionState.Disconnected, WgStatus(), null))
        assertEquals(VpnHeadline.STARTING, VpnView.headline(apps, true, ConnectionState.Connected, WgStatus(), null))
        assertEquals(VpnHeadline.FAILED, VpnView.headline(apps, true, ConnectionState.Connected, WgStatus(error = "x"), null))
        assertEquals(VpnHeadline.CHECKING, VpnView.headline(apps, true, ConnectionState.Connected, running, null))
        assertEquals(VpnHeadline.WORKING, VpnView.headline(apps, true, ConnectionState.Connected, running, ok))
        assertEquals(VpnHeadline.IN_TURKEY, VpnView.headline(apps, true, ConnectionState.Connected, running, ok.copy(countryCode = "TR")))
    }

    @Test fun appListPutsSelectedFirstAndHidesSystemApps() {
        val apps = listOf(AppEntry("a.sys", "Ayarlar", true), AppEntry("z.ig", "Instagram", false), AppEntry("b.dc", "Discord", false))
        assertEquals(listOf("z.ig", "b.dc"), VpnView.visibleApps(apps, setOf("z.ig"), "").map { it.packageName })
        assertEquals(listOf("a.sys"), VpnView.visibleApps(apps, emptySet(), "ayar").map { it.packageName })
    }

    // ---- uygulama sahibi ----

    @Test fun parsesHevSourceUser() {
        assertEquals(InetSocketAddress(InetAddress.getByName("10.10.10.10"), 5000), ConnectionOwner.parseSource("10.10.10.10|5000"))
        assertEquals(InetSocketAddress(InetAddress.getByName("fd00:10:10::10"), 80), ConnectionOwner.parseSource("fd00:10:10::10|80"))
        assertNull(ConnectionOwner.parseSource("evil.example|80"))
        assertNull(ConnectionOwner.parseSource("10.10.10.10|0"))
        assertNull(ConnectionOwner.parseSource(null))
    }

    @Test fun procNetLookup() {
        val tcp = listOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 0A0A0A0A:1388 04030201:01BB 01 00000000:00000000 00:00000000 00000000 10123        0 1 1",
            "   1: 0100007F:0438 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 2 1",
        )
        val src = InetSocketAddress(InetAddress.getByName("10.10.10.10"), 5000)
        assertEquals(10123, ProcNet.find(tcp, src, allowWildcard = false))
        assertNull(ProcNet.find(tcp, InetSocketAddress(InetAddress.getByName("10.10.10.10"), 5001), false))
        val udp = listOf("header", "  5: 00000000:1388 00000000:0000 07 00000000:00000000 00:00000000 00000000 10200 0 3 2")
        assertEquals("bağlanmamış UDP soketi joker adresle eşleşir", 10200, ProcNet.find(udp, src, allowWildcard = true))
        val tcp6 = listOf("header", "  0: 0000000000000000FFFF00000A0A0A0A:1388 00000000000000000000000000000000:0000 01 0:0 0:0 0 10300 0 4 1")
        assertEquals("tcp6'daki ::ffff:IPv4 eşleşir", 10300, ProcNet.find(tcp6, src, false))
    }

    // ---- yönlendirici: uygulama bazında üç yol ----

    @Test fun routerSendsVpnAppToTunnelAndOthersToDpi() {
        val byeDpi = FakeSocks("dpi").also { closeables += it }
        val vpn = FakeSocks("vpn").also { closeables += it }
        val owner = ConnectionOwner { _, src, _ -> if (src.port == 5000) 10123 else 10999 }
        val port = startRouter(byeDpi, ExitRouter.Routes(appExit = ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", vpn.port), appUids = setOf(10123), owner = owner))

        // VPN uygulaması, sunucu-önce konuşan bir port: VPN'e gider.
        assertEquals("vpn", hevConnect(port, "10.10.10.10|5000", "1.2.3.4", 5222, "hi".toByteArray()))
        assertEquals("1.2.3.4:5222", vpn.requests.single().target)
        // Aynı hedef, başka uygulama: DPI motoruna.
        assertEquals("dpi", hevConnect(port, "10.10.10.10|6000", "1.2.3.4", 5222, "hi".toByteArray()))
        assertEquals(1, byeDpi.requests.size)
        // VPN uygulamasının 443 bağlantısı: alan adı okunup adla istenir.
        val hello = "GET / HTTP/1.1\r\nHost: www.instagram.com\r\n\r\n".toByteArray()
        assertEquals("vpn", hevConnect(port, "10.10.10.10|5000", "157.240.1.1", 80, hello))
        assertEquals("www.instagram.com:80", vpn.requests.last().target)
        // Kaynak bilgisi olmayan (eski hev) bağlantı: DPI motoru.
        assertEquals("dpi", hevConnect(port, null, "1.2.3.4", 5222, "hi".toByteArray()))
    }

    @Test fun vpnAppIsNotLeakedWhenTunnelIsDown() {
        val byeDpi = FakeSocks("dpi").also { closeables += it }
        val dead = ServerSocket(0).use { it.localPort }
        val owner = ConnectionOwner { _, _, _ -> 10123 }
        val port = startRouter(byeDpi, ExitRouter.Routes(appExit = ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", dead), appUids = setOf(10123), owner = owner))
        assertEquals("", hevConnect(port, "10.10.10.10|5000", "157.240.1.1", 80, "GET / HTTP/1.1\r\nHost: x.com\r\n\r\n".toByteArray(), allowFailure = true))
        assertTrue("VPN uygulaması Türkiye'den doğrudan gönderilmez", byeDpi.requests.isEmpty())
    }

    @Test fun domainExitStillWorksAlongsideAppRouting() {
        val byeDpi = FakeSocks("dpi").also { closeables += it }
        val vpn = FakeSocks("vpn").also { closeables += it }
        val owner = ConnectionOwner { _, src, _ -> if (src.port == 5000) 10123 else 10999 }
        val routes = ExitRouter.Routes(
            domainExit = ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", vpn.port), matcher = ExitMatcher.of(setOf("instagram"), ""),
            appExit = ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", vpn.port), appUids = setOf(10123), owner = owner,
        )
        val port = startRouter(byeDpi, routes)
        // Tarayıcı (VPN uygulaması değil) instagram.com açıyor: alan adı kuralıyla VPN'e.
        assertEquals("vpn", hevConnect(port, "10.10.10.10|6000", "157.240.1.1", 80, "GET / HTTP/1.1\r\nHost: www.instagram.com\r\n\r\n".toByteArray()))
        // Aynı tarayıcı başka bir site: DPI motoru.
        assertEquals("dpi", hevConnect(port, "10.10.10.10|6000", "1.1.1.1", 80, "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()))
    }

    @Test fun udpOfVpnAppGoesThroughTunnel() {
        val byeDpi = FakeSocks("dpi").also { closeables += it }
        val vpn = FakeSocks("vpn").also { closeables += it }
        val owner = ConnectionOwner { udp, src, _ -> if (udp && src.port == 7000) 10123 else 10999 }
        val port = startRouter(byeDpi, ExitRouter.Routes(appExit = ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", vpn.port), appUids = setOf(10123), owner = owner))
        assertEquals("vpn", udpRoundTrip(port, "10.10.10.10|7000"))
        assertEquals("dpi", udpRoundTrip(port, "10.10.10.10|7001"))
    }

    // ---- yardımcılar ----

    private fun startRouter(byeDpi: FakeSocks, routes: ExitRouter.Routes): Int {
        val port = ServerSocket(0).use { it.localPort }
        val router = ExitRouter(port, byeDpi.port, routes)
        router.start()
        closeables += router
        return port
    }

    /** Yamalı hev gibi: kullanıcı adında kaynak adresi, sonra CONNECT ve ilk paket. */
    private fun greet(s: Socket, source: String?): DataInputStream {
        val out = s.getOutputStream()
        val input = DataInputStream(s.getInputStream())
        if (source != null) {
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            input.readFully(ByteArray(2))
            val u = source.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(1, '-'.code.toByte())); out.flush()
            input.readFully(ByteArray(2))
        } else {
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            input.readFully(ByteArray(2))
        }
        return input
    }

    private fun hevConnect(routerPort: Int, source: String?, ip: String, port: Int, first: ByteArray, allowFailure: Boolean = false): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", routerPort), 2_000)
            s.soTimeout = 15_000
            val input = greet(s, source)
            val out = s.getOutputStream()
            out.write(byteArrayOf(5, 1, 0, 1) + InetAddress.getByName(ip).address + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
            return try {
                val reply = ByteArray(10)
                input.readFully(reply)
                if (reply[1].toInt() != 0) { assertTrue(allowFailure); return "" }
                out.write(first); out.flush()
                val buf = ByteArray(3)
                val n = input.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            } catch (e: Exception) {
                if (!allowFailure) throw e
                ""
            }
        }
    }

    private fun udpRoundTrip(routerPort: Int, source: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", routerPort), 2_000)
            s.soTimeout = 5_000
            val input = greet(s, source)
            s.getOutputStream().apply { write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); flush() }
            val reply = ByteArray(10).also { input.readFully(it) }
            val relayPort = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { d ->
                d.soTimeout = 5_000
                val pkt = byteArrayOf(0, 0, 0, 1, 9, 9, 9, 9, 0, 53) + "q".toByteArray()
                d.send(DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)))
                val buf = ByteArray(256)
                val p = DatagramPacket(buf, buf.size)
                d.receive(p)
                return String(buf, 10, p.length - 10).removeSuffix("q")
            }
        }
    }

    /**
     * Kayıt tutan küçük SOCKS5 sunucusu ([name] ile yanıt verir). CONNECT'te ilk
     * paketi okuyup adını yazar; UDP ASSOCIATE'te datagramı adıyla yankılar.
     */
    private class FakeSocks(val name: String) : AutoCloseable {
        data class Seen(val target: String)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val requests = CopyOnWriteArrayList<Seen>()
        private val udp = DatagramSocket(InetSocketAddress("127.0.0.1", 0))

        init {
            Thread {
                while (!server.isClosed) {
                    val c = runCatching { server.accept() }.getOrNull() ?: break
                    Thread { runCatching { serve(c) }; runCatching { c.close() } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            Thread {
                val buf = ByteArray(2048)
                while (!udp.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    runCatching { udp.receive(p) }.getOrNull() ?: break
                    val head = buf.copyOfRange(0, 10)
                    val reply = head + name.toByteArray() + buf.copyOfRange(10, p.length)
                    runCatching { udp.send(DatagramPacket(reply, reply.size, p.socketAddress)) }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(c: Socket) {
            c.soTimeout = 5_000
            val input = DataInputStream(c.getInputStream())
            val out = c.getOutputStream()
            input.readByte()
            ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            out.write(byteArrayOf(5, 0))
            val head = ByteArray(4).also { input.readFully(it) }
            val host = when (head[3].toInt()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                else -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
            }
            val port = input.readUnsignedShort()
            if (head[1].toInt() == 3) {
                val p = udp.localPort
                out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (p shr 8).toByte(), p.toByte())); out.flush()
                c.soTimeout = 0
                runCatching { while (input.read() >= 0) Unit }
                return
            }
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            input.read(ByteArray(4096))
            requests += Seen("$host:$port")
            out.write(name.toByteArray()); out.flush()
        }

        override fun close() { server.close(); udp.close() }
    }
}
