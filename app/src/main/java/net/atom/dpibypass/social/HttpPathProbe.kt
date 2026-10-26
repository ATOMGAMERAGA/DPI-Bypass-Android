package net.atom.dpibypass.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

/**
 * Tek bir ağ yolu ölçümü (Android'e bağımlı değil; yerel TLS test sunucusuyla
 * JVM'de sınanır). HEAD isteği atar, yönlendirme izlemez, gövde okumaz; HEAD
 * 405/501 ise boyutu sınırlı tek bir `GET Range: bytes=0-0` yapar.
 *
 * TLS doğrulaması KAPATILMAZ: [sslSocketFactory]/[trustManager] yalnız testte
 * kendi test CA'mızı eklemek içindir; üretim yolu sistem güven deposunu kullanır.
 * [dns] çağıranın sabitlediği, denetlenmiş adresleri döndürür; [socketFactory]
 * doğrudan yol ya da yerel SOCKS (motorun o anki planı) olabilir.
 */
class HttpPathProbe(
    private val dns: (String) -> List<InetAddress>,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Long = 6_000,
    private val readTimeoutMs: Long = 6_000,
    private val callTimeoutMs: Long = 18_000,
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val trustManager: X509TrustManager? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun probe(url: String, route: String): NetworkEvidence {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS).readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String): List<InetAddress> = dns(hostname) })
            .socketFactory(socketFactory)
            .retryOnConnectionFailure(false)
        if (sslSocketFactory != null && trustManager != null) builder.sslSocketFactory(sslSocketFactory, trustManager)
        val client = builder.build()
        try {
            val head = execute(client, Request.Builder().url(url).head().build(), route)
            // HEAD desteklenmiyorsa boyutu sınırlı tek GET (gövde okunmaz, ilk bayt istenir).
            if (head.httpStatus == 405 || head.httpStatus == 501)
                return execute(client, Request.Builder().url(url).get().header("Range", "bytes=0-0").build(), route)
                    .let { it.copy(requests = 2, bytes = it.bytes + head.bytes) }
            return head
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(client: OkHttpClient, request: Request, route: String): NetworkEvidence {
        val call = client.newCall(request)
        return try {
            val received = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
                })
            }
            received.use { response ->
                val bytes = response.headers.toString().toByteArray(Charsets.UTF_8).size.toLong()
                // Doğrulanmış TLS üzerinden gelen her HTTP yanıtı yalnız ağ yolunun açık
                // olduğunu gösterir; içerik/hesap başarısı değildir.
                ProbeClassifier.fromHttp(route, response.code, response.header("Retry-After"), clock(), bytes)
            }
        } catch (e: CancellationException) { call.cancel(); throw e }
        catch (e: Exception) { ProbeClassifier.fromException(route, e).copy(bytes = FAILED_ATTEMPT_BYTES) }
    }

    companion object {
        /**
         * Başarısız bir bağlantı denemesinin TAHMİNİ veri maliyeti (TCP + TLS el
         * sıkışması: ClientHello, sunucu sertifika zinciri). Eskiden her hata 256 KiB
         * (rezervin tamamı) sayılıyordu; engelli hedeflerin çok olduğu çoklu aramada
         * bayt bütçesi gerçek trafik olmadan doluyordu.
         */
        const val FAILED_ATTEMPT_BYTES = 16_384L
    }
}
