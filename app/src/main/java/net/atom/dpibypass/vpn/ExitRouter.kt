package net.atom.dpibypass.vpn

import net.atom.dpibypass.social.ExitConnector
import net.atom.dpibypass.social.ExitMatcher
import net.atom.dpibypass.social.ExitServer
import net.atom.dpibypass.social.ExitStatusBoard
import net.atom.dpibypass.social.Sniff
import net.atom.dpibypass.wg.WireGuardConfig
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * hev-socks5-tunnel ile ByeDPI arasına giren yerel SOCKS5 yönlendirici.
 *
 *   TUN -> hev -> ExitRouter --(VPN Tüneli uygulaması)--> WireGuard köprüsü -> sağlayıcı
 *                            |--(platform alan adı)-----> yurt dışı çıkış -> platform
 *                            \--(diğer her şey)---------> ByeDPI -> İnternet
 *
 * hev bağlantıları IP adresiyle ister; alan adı bilinmez. Bu yüzden 443/80
 * portundaki bağlantılarda SOCKS yanıtı hemen verilir, uygulamanın ilk paketi
 * (TLS ClientHello / HTTP isteği) okunur ve içindeki alan adına göre yol seçilir.
 * Okunan baytlar seçilen yola aynen yazılır; ByeDPI ilk paketi yine tek parça
 * görür ve desync stratejisi eskisi gibi çalışır.
 *
 * SIZINTI KURALLARI (platform trafiği asla sessizce Türkiye'den çıkmaz):
 *  - yurt dışı sunucuya ulaşılamazsa bağlantı KAPATILIR, doğrudan yola düşülmez;
 *  - UDP/443 (QUIC) düşürülür: QUIC'in alan adı ilk pakette şifreli olduğundan
 *    ayırt edilemez; uygulamalar TCP'ye (TLS) döner ve o yol ayrıştırılır.
 *  - Diğer UDP (DNS, oyun, sesli görüşme) ByeDPI'ın UDP aktarımına iletilir.
 */
class ExitRouter(
    private val listenPort: Int,
    private val byeDpiPort: Int,
    private val routes: Routes,
    private val log: (String) -> Unit = {},
) : Closeable {
    constructor(listenPort: Int, byeDpiPort: Int, server: ExitServer, matcher: ExitMatcher, log: (String) -> Unit = {}) :
        this(listenPort, byeDpiPort, Routes(domainExit = server, matcher = matcher), log)

    /**
     * Yönlendirme kuralları (öncelik sırasıyla):
     *  1. [appUids] içindeki uygulamaların BÜTÜN trafiği (TCP + UDP) [appExit]
     *     (VPN Tüneli / WireGuard köprüsü) üzerinden;
     *  2. [matcher]'a uyan alan adları [domainExit] (Sosyal Erişim yurt dışı çıkışı) üzerinden;
     *  3. geri kalan her şey ByeDPI (yerel DPI aşma) üzerinden.
     * Seçili yol kurulamazsa bağlantı KAPATILIR; Türkiye'den doğrudan gönderilmez.
     */
    data class Routes(
        val domainExit: ExitServer? = null,
        val matcher: ExitMatcher = ExitMatcher(emptyList()),
        val appExit: ExitServer? = null,
        val appUids: Set<Int> = emptySet(),
        val owner: ConnectionOwner? = null,
    ) {
        val domainActive: Boolean get() = domainExit != null && !matcher.isEmpty
        val appActive: Boolean get() = appExit != null && appUids.isNotEmpty() && owner != null
        /** Ekranda gösterilen özet. */
        val display: String get() = listOfNotNull(
            domainExit?.takeIf { domainActive }?.display,
            appExit?.takeIf { appActive }?.let { "VPN Tüneli · ${appUids.size} uygulama" },
        ).joinToString(" + ")
    }

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "exit-router").apply { isDaemon = true } }
    private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false
    // Açık soketler. Engelleyen soket G/Ç'si iş parçacığı kesmesiyle (shutdownNow)
    // durmaz; yönlendirici kapatılırken soketler kapatılmazsa her bağlantının iki
    // iş parçacığı ve tanıtıcıları, karşı taraf kapatana kadar (bazen saatlerce)
    // yaşardı — her ayar değişikliği ve yeniden bağlanma sızıntıyı büyütüyordu.
    private val openSockets: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

    private fun <T : Closeable> track(c: T): T {
        openSockets.add(c)
        if (closed) runCatching { c.close() }
        return c
    }

    private fun untrack(c: Closeable) {
        openSockets.remove(c)
    }

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(loopback, listenPort), 256)
        serverSocket = ss
        ExitStatusBoard.started(routes.display)
        pool.execute {
            while (!closed) {
                val client = try { ss.accept() } catch (e: IOException) { break }
                track(client)
                try {
                    pool.execute { handle(client) }
                } catch (e: RejectedExecutionException) {
                    untrack(client)
                    runCatching { client.close() }
                    break
                }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket?.close() }
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
        pool.shutdownNow()
        ExitStatusBoard.stopped()
    }

    private fun handle(client: Socket) {
        try {
            handleTracked(client)
        } finally {
            untrack(client)
        }
    }

    private fun handleTracked(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val out = client.getOutputStream()
            // Karşılama: hev (yamalı) kaynak adresini kullanıcı adı olarak gönderir
            // ("ip|port"); bu yöntem sunulduysa seçilir, yoksa kimlik doğrulamasız.
            val greet = ExitConnector.readExact(input, 2)
            if (greet[0].toInt() != 5) return client.close()
            val methods = ExitConnector.readExact(input, greet[1].toInt() and 0xFF)
            var source: InetSocketAddress? = null
            if (methods.contains(2.toByte())) {
                out.write(byteArrayOf(5, 2)); out.flush()
                val ver = ExitConnector.readExact(input, 2)
                val user = String(ExitConnector.readExact(input, ver[1].toInt() and 0xFF), Charsets.US_ASCII)
                val passLen = input.read()
                if (passLen < 0) return client.close()
                ExitConnector.readExact(input, passLen)
                out.write(byteArrayOf(1, 0)); out.flush()
                source = ConnectionOwner.parseSource(user)
            } else {
                out.write(byteArrayOf(5, 0)); out.flush()
            }
            val request = readRequest(input) ?: return client.close()
            when (request.cmd) {
                CMD_CONNECT -> connect(client, request, source)
                CMD_UDP_ASSOCIATE -> udpAssociate(client, source)
                else -> {
                    // hev'in kendi UDP-over-TCP komutu dahil: desteklenmez (QUIC kapalı kipte de böyleydi).
                    out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                    client.close()
                }
            }
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    private class Request(val cmd: Int, val raw: ByteArray, val port: Int, val host: String)

    private fun readRequest(input: InputStream): Request? {
        val head = ExitConnector.readExact(input, 4)
        if (head[0].toInt() != 5) return null
        val addr: ByteArray = when (head[3].toInt()) {
            1 -> ExitConnector.readExact(input, 4)
            4 -> ExitConnector.readExact(input, 16)
            3 -> { val n = input.read(); if (n < 0) return null; byteArrayOf(n.toByte()) + ExitConnector.readExact(input, n) }
            else -> return null
        }
        val portBytes = ExitConnector.readExact(input, 2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        val host = when (head[3].toInt()) {
            3 -> String(addr, 1, addr.size - 1, Charsets.US_ASCII)
            else -> InetAddress.getByAddress(addr).hostAddress.orEmpty()
        }
        return Request(head[1].toInt() and 0xFF, head + addr + portBytes, port, host)
    }

    /** Bağlantı VPN Tüneli'ne yönlendirilen bir uygulamadan mı geliyor? */
    private fun isAppRoute(udp: Boolean, source: InetSocketAddress?, host: String, port: Int): Boolean {
        if (!routes.appActive || source == null) return false
        if (!WireGuardConfig.validIp(host)) return false
        val dst = runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull() ?: return false
        val uid = runCatching { routes.owner?.uid(udp, source, dst) }.getOrNull() ?: return false
        return uid in routes.appUids
    }

    private fun connect(client: Socket, request: Request, source: InetSocketAddress?) {
        val out = client.getOutputStream()
        if (isAppRoute(false, source, request.host, request.port)) return connectViaApp(client, request)
        if (!routes.domainActive) {
            // Yalnız VPN Tüneli etkin: diğer uygulamalar eskisi gibi doğrudan ByeDPI'a.
            val upstream = openByeDpi(request.raw) ?: return client.close()
            ExitStatusBoard.direct()
            return pipe(client, upstream)
        }
        if (request.port != 443 && request.port != 80) {
            // Sunucunun önce konuştuğu protokoller olabilir; ilk paket beklenmez.
            val upstream = openByeDpi(request.raw) ?: return client.close()
            ExitStatusBoard.direct()
            return pipe(client, upstream)
        }
        out.write(OK_REPLY); out.flush()
        val (first, host) = sniff(client)
        val domainExit = routes.domainExit
        if (host != null && domainExit != null && routes.matcher.matches(host)) {
            val remote = try {
                ExitConnector.open(domainExit, host, request.port, UPSTREAM_TIMEOUT_MS)
            } catch (e: IOException) {
                // Kasıtlı: doğrudan yola DÜŞÜLMEZ. Platform Türkiye IP'sini görmesin.
                ExitStatusBoard.failure(host, e.message ?: "bağlantı kurulamadı")
                log("Yurt dışı çıkış başarısız: $host (${e.message})")
                return client.close()
            }
            ExitStatusBoard.exit(host)
            remote.getOutputStream().apply { write(first); flush() }
            return pipe(client, remote)
        }
        val upstream = openByeDpi(request.raw, expectReply = true) ?: return client.close()
        ExitStatusBoard.direct()
        upstream.getOutputStream().apply { if (first.isNotEmpty()) { write(first); flush() } }
        pipe(client, upstream)
    }

    /** İlk paketi okuyup alan adını (TLS SNI / HTTP Host) çıkarır. */
    private fun sniff(client: Socket): Pair<ByteArray, String?> {
        val buf = ByteArray(Sniff.MAX_BYTES)
        var len = 0
        var sniffed: Sniff.Result = Sniff.Result.NeedMore
        client.soTimeout = SNIFF_TIMEOUT_MS
        try {
            while (sniffed == Sniff.Result.NeedMore && len < buf.size) {
                val r = client.getInputStream().read(buf, len, buf.size - len)
                if (r < 0) break
                len += r
                sniffed = Sniff.host(buf, len)
            }
        } catch (_: SocketTimeoutException) {
        }
        return buf.copyOf(len) to (sniffed as? Sniff.Result.Host)?.name
    }

    /**
     * VPN Tüneli uygulamasının bağlantısı: bütünüyle WireGuard köprüsünden çıkar.
     * 443/80'de alan adı okunabiliyorsa ad, sağlayıcının DNS'iyle (tünel içinde)
     * çözülsün diye adla istenir; platform bölgeyi VPN ülkesine göre seçer.
     */
    private fun connectViaApp(client: Socket, request: Request) {
        val exit = routes.appExit ?: return client.close()
        val out = client.getOutputStream()
        if (request.port == 443 || request.port == 80) {
            out.write(OK_REPLY); out.flush()
            val (first, host) = sniff(client)
            val target = host ?: request.host
            val remote = try {
                ExitConnector.open(exit, target, request.port, UPSTREAM_TIMEOUT_MS)
            } catch (e: IOException) {
                ExitStatusBoard.failure(target, "VPN Tüneli: ${e.message ?: "bağlantı kurulamadı"}")
                return client.close()
            }
            ExitStatusBoard.app(target)
            remote.getOutputStream().apply { if (first.isNotEmpty()) { write(first); flush() } }
            return pipe(client, remote)
        }
        val remote = try {
            ExitConnector.open(exit, request.host, request.port, UPSTREAM_TIMEOUT_MS)
        } catch (e: IOException) {
            ExitStatusBoard.failure(request.host, "VPN Tüneli: ${e.message ?: "bağlantı kurulamadı"}")
            runCatching { out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush() }
            return client.close()
        }
        ExitStatusBoard.app(request.host)
        out.write(OK_REPLY); out.flush()
        pipe(client, remote)
    }

    /**
     * ByeDPI'a SOCKS5 isteğini iletir. [expectReply] ise ByeDPI'ın yanıtı burada
     * tüketilir (istemciye zaten "başarılı" denmişti); değilse yanıt istemciye
     * aynen akar. ByeDPI karşılamayı ayrı bir okuma olarak beklediği için
     * karşılama ile istek ayrı yazılır.
     */
    private fun openByeDpi(request: ByteArray, expectReply: Boolean = false): Socket? {
        val s = Socket()
        return try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(loopback, byeDpiPort), 5_000)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = s.getInputStream()
            s.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
            if (ExitConnector.readExact(input, 2)[1].toInt() != 0) throw IOException("ByeDPI karşılaması reddedildi")
            s.getOutputStream().apply { write(request); flush() }
            if (expectReply) {
                val head = ExitConnector.readExact(input, 4)
                if (head[1].toInt() != 0) throw IOException("ByeDPI bağlanamadı")
                val skip = when (head[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> input.read(); else -> throw IOException() }
                ExitConnector.readExact(input, skip + 2)
            }
            s.soTimeout = 0
            s
        } catch (e: Exception) {
            runCatching { s.close() }
            null
        }
    }

    /** İki yönlü aktarım; bir yön bitince karşı tarafın yazma yönü kapatılır (yarım kapanış). */
    private fun pipe(a: Socket, b: Socket) {
        track(b)
        try {
            a.soTimeout = 0; b.soTimeout = 0
            val other = pool.submit { copy(b.getInputStream(), a.getOutputStream()); runCatching { a.shutdownOutput() } }
            copy(a.getInputStream(), b.getOutputStream())
            runCatching { b.shutdownOutput() }
            runCatching { other.get() }
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
            untrack(b)
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }

    /** Bir SOCKS5 sunucusunun (ByeDPI ya da WireGuard köprüsü) UDP aktarımı. */
    private class UdpUpstream(val control: Socket, val relay: InetSocketAddress, val socket: DatagramSocket)

    private fun openUdpUpstream(port: Int): UdpUpstream {
        val control = track(Socket())
        val up = track(DatagramSocket(InetSocketAddress(loopback, 0)))
        try {
            control.connect(InetSocketAddress(loopback, port), 5_000)
            control.soTimeout = HANDSHAKE_TIMEOUT_MS
            val cin = control.getInputStream()
            control.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
            ExitConnector.readExact(cin, 2)
            control.getOutputStream().apply { write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); flush() }
            val head = ExitConnector.readExact(cin, 4)
            if (head[1].toInt() != 0) throw IOException("UDP desteklenmiyor")
            val relayAddr = when (head[3].toInt()) {
                1 -> InetAddress.getByAddress(ExitConnector.readExact(cin, 4))
                4 -> InetAddress.getByAddress(ExitConnector.readExact(cin, 16))
                else -> throw IOException()
            }.let { if (it.isAnyLocalAddress) loopback else it }
            val pb = ExitConnector.readExact(cin, 2)
            control.soTimeout = 0
            return UdpUpstream(control, InetSocketAddress(relayAddr, ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)), up)
        } catch (e: Exception) {
            runCatching { control.close() }; runCatching { up.close() }
            untrack(control); untrack(up)
            throw e
        }
    }

    /**
     * UDP aktarımı: istemciye kendi yerel UDP soketimizi bildiririz. İlk
     * datagramın hedefiyle bağlantının sahibi (uygulama) belirlenir; VPN Tüneli
     * uygulamasıysa datagramlar WireGuard köprüsüne, değilse ByeDPI'ın UDP
     * aktarımına iletilir. Yurt dışı çıkış etkinken, VPN yolunda olmayan
     * UDP/443 (QUIC) düşürülür (alan adı ilk pakette okunamaz).
     * Birliktelik, denetim TCP bağlantısı kapanana kadar sürer.
     */
    private fun udpAssociate(client: Socket, source: InetSocketAddress?) {
        val local = track(DatagramSocket(InetSocketAddress(loopback, 0)))
        val upstream = AtomicReference<UdpUpstream?>(null)
        try {
            val port = local.localPort
            client.getOutputStream().apply {
                write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())); flush()
            }
            client.soTimeout = 0
            val clientAddr = AtomicReference<InetSocketAddress?>(null)
            pool.execute {
                val buf = ByteArray(65_535)
                val packet = DatagramPacket(buf, buf.size)
                var viaApp = false
                try {
                    while (true) {
                        packet.setData(buf, 0, buf.size)
                        local.receive(packet)
                        clientAddr.set(InetSocketAddress(packet.address, packet.port))
                        var up = upstream.get()
                        if (up == null) {
                            val dst = udpDestination(buf, packet.length) ?: continue
                            viaApp = isAppRoute(true, source, dst.first, dst.second)
                            val exit = routes.appExit
                            up = try {
                                openUdpUpstream(if (viaApp && exit != null) exit.port else byeDpiPort)
                            } catch (e: Exception) {
                                if (viaApp) ExitStatusBoard.failure(dst.first, "VPN Tüneli UDP: ${e.message ?: "kurulamadı"}")
                                break
                            }
                            upstream.set(up)
                            if (viaApp) ExitStatusBoard.app(dst.first)
                            startUdpBackward(up, local, clientAddr)
                            pool.execute {
                                runCatching { while (up.control.getInputStream().read() >= 0) Unit }
                                runCatching { client.close() }
                            }
                        }
                        if (!viaApp && routes.domainActive && udpDestinationPort(buf, packet.length) == 443) { ExitStatusBoard.quic(); continue }
                        up.socket.send(DatagramPacket(buf, 0, packet.length, up.relay))
                    }
                } catch (_: IOException) {
                } catch (_: RejectedExecutionException) {
                }
                runCatching { client.close() }
            }
            // Denetim bağlantısı (istemci tarafı) kapanınca birliktelik biter.
            runCatching { while (client.getInputStream().read() >= 0) Unit }
        } catch (e: Exception) {
            runCatching { client.getOutputStream().apply { write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)); flush() } }
        } finally {
            runCatching { local.close() }
            untrack(local)
            upstream.get()?.let { up ->
                runCatching { up.socket.close() }; runCatching { up.control.close() }
                untrack(up.socket); untrack(up.control)
            }
            runCatching { client.close() }
        }
    }

    private fun startUdpBackward(up: UdpUpstream, local: DatagramSocket, clientAddr: AtomicReference<InetSocketAddress?>) {
        pool.execute {
            val buf = ByteArray(65_535)
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    packet.setData(buf, 0, buf.size)
                    up.socket.receive(packet)
                    val to = clientAddr.get() ?: continue
                    local.send(DatagramPacket(buf, 0, packet.length, to))
                }
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val SNIFF_TIMEOUT_MS = 4_000
        private const val UPSTREAM_TIMEOUT_MS = 10_000
        private val OK_REPLY = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)

        /** SOCKS5 UDP başlığından hedef IP ve port; alan adıysa ya da okunamazsa null. */
        fun udpDestination(b: ByteArray, len: Int): Pair<String, Int>? {
            if (len < 4) return null
            val (addrLen, portAt) = when (b[3].toInt()) {
                1 -> 4 to 8
                4 -> 16 to 20
                else -> return null
            }
            if (len < portAt + 2) return null
            val ip = runCatching { InetAddress.getByAddress(b.copyOfRange(4, 4 + addrLen)).hostAddress }.getOrNull() ?: return null
            return ip to (((b[portAt].toInt() and 0xFF) shl 8) or (b[portAt + 1].toInt() and 0xFF))
        }

        /** SOCKS5 UDP başlığından hedef port; okunamazsa -1. */
        fun udpDestinationPort(b: ByteArray, len: Int): Int {
            if (len < 4) return -1
            val portAt = when (b[3].toInt()) {
                1 -> 8
                4 -> 20
                3 -> 5 + (b[4].toInt() and 0xFF)
                else -> return -1
            }
            if (len < portAt + 2) return -1
            return ((b[portAt].toInt() and 0xFF) shl 8) or (b[portAt + 1].toInt() and 0xFF)
        }
    }
}
