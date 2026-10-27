package net.atom.dpibypass.vpn

import net.atom.dpibypass.social.ExitConnector
import net.atom.dpibypass.social.ExitMatcher
import net.atom.dpibypass.social.ExitServer
import net.atom.dpibypass.social.ExitStatusBoard
import net.atom.dpibypass.social.Sniff
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * hev-socks5-tunnel ile ByeDPI arasına giren yerel SOCKS5 yönlendirici.
 *
 *   TUN -> hev -> ExitRouter --(platform alan adı)--> yurt dışı proxy -> platform
 *                            \--(diğer her şey)----> ByeDPI -> İnternet
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
    private val server: ExitServer,
    private val matcher: ExitMatcher,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "exit-router").apply { isDaemon = true } }
    private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(loopback, listenPort), 256)
        serverSocket = ss
        ExitStatusBoard.started(server.display)
        pool.execute {
            while (!closed) {
                val client = try { ss.accept() } catch (e: IOException) { break }
                pool.execute { handle(client) }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
        ExitStatusBoard.stopped()
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val out = client.getOutputStream()
            // Karşılama: yöntem listesi okunur, "kimlik doğrulama yok" seçilir.
            val greet = ExitConnector.readExact(input, 2)
            if (greet[0].toInt() != 5) return client.close()
            ExitConnector.readExact(input, greet[1].toInt() and 0xFF)
            out.write(byteArrayOf(5, 0)); out.flush()
            val request = readRequest(input) ?: return client.close()
            when (request.cmd) {
                CMD_CONNECT -> connect(client, request)
                CMD_UDP_ASSOCIATE -> udpAssociate(client)
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

    private fun connect(client: Socket, request: Request) {
        val out = client.getOutputStream()
        if (request.port != 443 && request.port != 80) {
            // Sunucunun önce konuştuğu protokoller olabilir; ilk paket beklenmez.
            val upstream = openByeDpi(request.raw) ?: return client.close()
            ExitStatusBoard.direct()
            return pipe(client, upstream)
        }
        out.write(OK_REPLY); out.flush()
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
        val first = buf.copyOf(len)
        val host = (sniffed as? Sniff.Result.Host)?.name
        if (host != null && matcher.matches(host)) {
            val remote = try {
                ExitConnector.open(server, host, request.port, UPSTREAM_TIMEOUT_MS)
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
        a.soTimeout = 0; b.soTimeout = 0
        val other = pool.submit { copy(b.getInputStream(), a.getOutputStream()); runCatching { a.shutdownOutput() } }
        copy(a.getInputStream(), b.getOutputStream())
        runCatching { b.shutdownOutput() }
        runCatching { other.get() }
        runCatching { a.close() }
        runCatching { b.close() }
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

    /**
     * UDP aktarımı: istemciye kendi yerel UDP soketimizi bildiririz, datagramları
     * ByeDPI'ın UDP aktarımına iletiriz. Hedef portu 443 olanlar (QUIC) düşürülür.
     * Birliktelik, denetim TCP bağlantısı kapanana kadar sürer.
     */
    private fun udpAssociate(client: Socket) {
        val control = Socket()
        val local = DatagramSocket(InetSocketAddress(loopback, 0))
        val up = DatagramSocket(InetSocketAddress(loopback, 0))
        try {
            control.connect(InetSocketAddress(loopback, byeDpiPort), 5_000)
            control.soTimeout = HANDSHAKE_TIMEOUT_MS
            val cin = control.getInputStream()
            control.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
            ExitConnector.readExact(cin, 2)
            control.getOutputStream().apply { write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); flush() }
            val head = ExitConnector.readExact(cin, 4)
            if (head[1].toInt() != 0) throw IOException("ByeDPI UDP desteklemiyor")
            val relayAddr = when (head[3].toInt()) {
                1 -> InetAddress.getByAddress(ExitConnector.readExact(cin, 4))
                4 -> InetAddress.getByAddress(ExitConnector.readExact(cin, 16))
                else -> throw IOException()
            }.let { if (it.isAnyLocalAddress) loopback else it }
            val pb = ExitConnector.readExact(cin, 2)
            val relay = InetSocketAddress(relayAddr, ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF))
            control.soTimeout = 0

            val port = local.localPort
            client.getOutputStream().apply {
                write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())); flush()
            }
            client.soTimeout = 0
            val clientAddr = AtomicReference<InetSocketAddress?>(null)
            pool.execute {
                val buf = ByteArray(65_535)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        packet.setData(buf, 0, buf.size)
                        local.receive(packet)
                        clientAddr.set(InetSocketAddress(packet.address, packet.port))
                        if (udpDestinationPort(buf, packet.length) == 443) { ExitStatusBoard.quic(); continue }
                        up.send(DatagramPacket(buf, 0, packet.length, relay))
                    }
                } catch (_: IOException) {
                }
            }
            pool.execute {
                val buf = ByteArray(65_535)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        packet.setData(buf, 0, buf.size)
                        up.receive(packet)
                        val to = clientAddr.get() ?: continue
                        local.send(DatagramPacket(buf, 0, packet.length, to))
                    }
                } catch (_: IOException) {
                }
            }
            // Denetim bağlantısı (istemci ya da ByeDPI tarafı) kapanınca birliktelik biter.
            val watcher = pool.submit { runCatching { while (control.getInputStream().read() >= 0) Unit }; runCatching { client.close() } }
            runCatching { while (client.getInputStream().read() >= 0) Unit }
            watcher.cancel(true)
        } catch (e: Exception) {
            runCatching { client.getOutputStream().apply { write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)); flush() } }
        } finally {
            runCatching { local.close() }
            runCatching { up.close() }
            runCatching { control.close() }
            runCatching { client.close() }
        }
    }

    companion object {
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val SNIFF_TIMEOUT_MS = 4_000
        private const val UPSTREAM_TIMEOUT_MS = 10_000
        private val OK_REPLY = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)

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
