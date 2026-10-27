package net.atom.dpibypass.social

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Yurt dışı sunucu üzerinden [host]:[port]'a TCP bağlantısı açar (SOCKS5 ya da HTTP CONNECT). */
object ExitConnector {
    class ExitException(message: String) : IOException(message)

    /**
     * Ad çözümlemesi SUNUCUDA yapılır (SOCKS5 alan adı / CONNECT ad:port): platform
     * hangi ülkeye yakın sunucuyu vereceğine çıkış ülkesine göre karar verir ve
     * Türkiye'deki DNS'in cevabı hiç kullanılmaz.
     */
    fun open(server: ExitServer, host: String, port: Int, timeoutMs: Int = 10_000): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(server.host, server.port), timeoutMs)
            socket.soTimeout = timeoutMs
            when (server.kind) {
                ExitServer.Kind.SOCKS5 -> socks5(socket, server, host, port)
                ExitServer.Kind.HTTP -> httpConnect(socket, server, host, port)
            }
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is ExitException) e else ExitException(describe(e))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Sunucu zamanında yanıt vermedi"
        is java.net.ConnectException -> "Sunucuya bağlanılamadı (adres/port yanlış ya da sunucu kapalı)"
        is java.net.UnknownHostException -> "Sunucu adı çözülemedi"
        else -> "Sunucu bağlantısı koptu"
    }

    private fun socks5(s: Socket, server: ExitServer, host: String, port: Int) {
        val out = s.getOutputStream()
        val input = s.getInputStream()
        out.write(if (server.hasAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val method = readExact(input, 2)
        if (method[0].toInt() != 5) throw ExitException("Bu bir SOCKS5 sunucusu değil")
        when (method[1].toInt() and 0xFF) {
            0 -> Unit
            2 -> {
                val u = server.username.toByteArray()
                val p = server.password.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                if (readExact(input, 2)[1].toInt() != 0) throw ExitException("Kullanıcı adı ya da parola kabul edilmedi")
            }
            else -> throw ExitException(if (server.hasAuth) "Sunucu bu kimlik doğrulama yöntemini kabul etmiyor" else "Sunucu kullanıcı adı ve parola istiyor")
        }
        val name = host.toByteArray(Charsets.US_ASCII)
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
        val head = readExact(input, 4)
        val rep = head[1].toInt() and 0xFF
        if (rep != 0) throw ExitException(when (rep) {
            2 -> "Sunucu bu bağlantıya izin vermiyor"
            3, 4 -> "Sunucu hedefe ulaşamadı"
            5 -> "Hedef bağlantıyı reddetti"
            else -> "Sunucu bağlantıyı kuramadı (SOCKS kodu $rep)"
        })
        val skip = when (head[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> input.read().also { if (it < 0) throw ExitException("Sunucu yanıtı yarım kaldı") }
            else -> throw ExitException("Sunucu yanıtı anlaşılamadı")
        }
        readExact(input, skip + 2)
    }

    private fun httpConnect(s: Socket, server: ExitServer, host: String, port: Int) {
        val target = if (':' in host) "[$host]:$port" else "$host:$port"
        val request = buildString {
            append("CONNECT $target HTTP/1.1\r\nHost: $target\r\n")
            if (server.hasAuth) {
                val token = Base64.getEncoder().encodeToString("${server.username}:${server.password}".toByteArray())
                append("Proxy-Authorization: Basic $token\r\n")
            }
            append("\r\n")
        }
        s.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
        // Başlıklar bayt bayt okunur: tünelin ilk baytları tüketilmesin.
        val input = s.getInputStream()
        val head = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw ExitException("Sunucu yanıtı yarım kaldı")
            head.write(c)
            val b = head.toByteArray()
            if (b.size >= 4 && b[b.size - 4] == '\r'.code.toByte() && b[b.size - 3] == '\n'.code.toByte() &&
                b[b.size - 2] == '\r'.code.toByte() && b[b.size - 1] == '\n'.code.toByte()) break
            if (b.size > 8192) throw ExitException("Sunucu yanıtı anlaşılamadı")
        }
        val status = head.toString("ISO-8859-1").substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull()
        if (status != 200) throw ExitException(when (status) {
            407 -> "Kullanıcı adı ya da parola kabul edilmedi"
            403 -> "Sunucu bu bağlantıya izin vermiyor"
            null -> "Bu bir HTTP proxy sunucusu değil"
            else -> "Sunucu bağlantıyı kuramadı (HTTP $status)"
        })
    }

    fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw ExitException("Sunucu bağlantıyı kapattı")
            off += r
        }
        return buf
    }
}

/** Sunucunun gerçekten çalıştığını ve hangi ülkeden çıktığını ölçer. */
object ExitProbe {
    data class Result(val ok: Boolean, val countryCode: String = "", val ip: String = "", val latencyMs: Long = 0, val error: String = "") {
        val inTurkey: Boolean get() = countryCode.equals("TR", ignoreCase = true)
    }

    private const val TRACE_HOST = "www.cloudflare.com"

    /**
     * Sunucu üzerinden Cloudflare'in /cdn-cgi/trace sayfasını açar; bu sayfa isteğin
     * geldiği IP'yi ve ülkesini (`loc=`) döndürür. Platformlar da ülkeyi IP'den
     * çıkardığı için bu, "platform beni nerede sanacak" sorusunun en yakın ölçüsüdür.
     */
    fun check(server: ExitServer, timeoutMs: Int = 12_000): Result {
        val started = System.nanoTime()
        return try {
            ExitConnector.open(server, TRACE_HOST, 443, timeoutMs).use { raw ->
                raw.soTimeout = timeoutMs
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, TRACE_HOST, 443, true) as SSLSocket).use { tls ->
                    tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(TRACE_HOST)) }
                    tls.startHandshake()
                    tls.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_HOST\r\nConnection: close\r\nUser-Agent: dpibypass-exit-check\r\n\r\n".toByteArray())
                    tls.outputStream.flush()
                    val body = tls.inputStream.readBytes().toString(Charsets.UTF_8)
                    val fields = body.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
                    val loc = fields["loc"].orEmpty()
                    if (loc.isEmpty()) Result(false, error = "Sunucu bağlandı ama konum okunamadı")
                    else Result(true, loc, fields["ip"].orEmpty(), (System.nanoTime() - started) / 1_000_000)
                }
            }
        } catch (e: ExitConnector.ExitException) {
            Result(false, error = e.message.orEmpty())
        } catch (e: Exception) {
            Result(false, error = "Sunucu üzerinden güvenli bağlantı kurulamadı")
        }
    }
}

/** Çalışan yönlendiricinin canlı sayaçları; arayüz "gerçekten yurt dışından mı gidiyor?" sorusunu buradan yanıtlar. */
data class ExitStatus(
    val running: Boolean = false,
    val server: String = "",
    val viaExit: Int = 0,
    val direct: Int = 0,
    val failures: Int = 0,
    val quicBlocked: Int = 0,
    val lastExitHost: String = "",
    val lastError: String = "",
    val lastErrorAtMs: Long = 0,
)

object ExitStatusBoard {
    private val _status = MutableStateFlow(ExitStatus())
    val status: StateFlow<ExitStatus> = _status.asStateFlow()

    /** Son ölçülen çıkış ülkesi (test düğmesi ya da bağlanırken yapılan kontrol). */
    private val _probe = MutableStateFlow<ExitProbe.Result?>(null)
    val probe: StateFlow<ExitProbe.Result?> = _probe.asStateFlow()

    fun started(server: String) = _status.update { ExitStatus(running = true, server = server) }
    fun stopped() = _status.update { it.copy(running = false) }
    fun exit(host: String) = _status.update { it.copy(viaExit = it.viaExit + 1, lastExitHost = host) }
    fun direct() = _status.update { it.copy(direct = it.direct + 1) }
    fun quic() = _status.update { it.copy(quicBlocked = it.quicBlocked + 1) }
    fun failure(host: String, reason: String) = _status.update {
        it.copy(failures = it.failures + 1, lastError = "$host: $reason", lastErrorAtMs = System.currentTimeMillis())
    }
    fun probed(result: ExitProbe.Result?) { _probe.value = result }
}
