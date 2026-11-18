package net.atom.dpibypass.wg

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.atom.dpibypass.social.ExitProbe
import net.atom.dpibypass.social.ExitServer
import java.net.Inet4Address
import java.net.InetAddress

/** libwgbridge.so (Go: wireguard-go + gVisor netstack) JNI girişi. */
object WgNative {
    /** Bu derlemede köprü var mı? (Go olmadan yapılan yerel derlemelerde yoktur.) */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("wgbridge") }.onFailure { Log.w("WgNative", "libwgbridge yok: ${it.message}") }.isSuccess
    }

    /** Hata yoksa boş metin döner. */
    @JvmStatic external fun nativeStart(uapi: String, addresses: String, dns: String, mtu: Int, listen: String): String
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeStatus(): String
}

/** Köprünün canlı durumu; arayüz "VPN gerçekten bağlı mı?" sorusunu buradan yanıtlar. */
data class WgStatus(
    val running: Boolean = false,
    val summary: String = "",
    /** Son başarılı el sıkışmanın zamanı (epoch sn); 0 = henüz yok. */
    val handshakeSec: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val error: String = "",
) {
    /** WireGuard el sıkışması 3 dakikadan yeniyse tünel canlı sayılır. */
    fun handshakeFresh(nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        handshakeSec > 0 && nowSec - handshakeSec < 180

    companion object {
        fun parse(raw: String, base: WgStatus): WgStatus {
            if (raw.isBlank()) return base.copy(running = false)
            val fields = raw.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
            fields["error"]?.let { return base.copy(error = it) }
            return base.copy(
                running = true,
                handshakeSec = fields["handshake"]?.toLongOrNull() ?: 0,
                rxBytes = fields["rx"]?.toLongOrNull() ?: 0,
                txBytes = fields["tx"]?.toLongOrNull() ?: 0,
            )
        }
    }
}

/**
 * Süreçteki TEK WireGuard köprüsünü yönetir. Hem VPN servisi hem de ayarlar
 * ekranındaki "Test et" kullanır; aynı yapılandırma zaten çalışıyorsa yeniden
 * kurulmaz. Dinleme adresi daima 127.0.0.1:[PORT].
 */
object WgEngine {
    const val PORT = 1082
    private const val TAG = "WgEngine"

    /** ExitRouter / ExitProbe için yerel SOCKS5 adresi. */
    val server: ExitServer = ExitServer(ExitServer.Kind.SOCKS5, "127.0.0.1", PORT)

    private val _status = MutableStateFlow(WgStatus())
    val status: StateFlow<WgStatus> = _status.asStateFlow()

    /** Tünel üzerinden ölçülen çıkış ülkesi (bağlanınca ve "Test et" ile). */
    private val _probe = MutableStateFlow<ExitProbe.Result?>(null)
    val probe: StateFlow<ExitProbe.Result?> = _probe.asStateFlow()
    fun probed(result: ExitProbe.Result?) { _probe.value = result }

    private var runningKey: String? = null
    private val holders = mutableSetOf<String>()

    const val OWNER_SERVICE = "service"
    const val OWNER_TEST = "test"

    /**
     * Köprüyü başlatır (IO iş parçacığında çağrılmalı: ad çözümlemesi yapar).
     * [resolve], sağlayıcının uç nokta adını çözer (DoH tercih edilir).
     * Başarısızsa kullanıcıya gösterilecek Türkçe hata döner; başarılıysa null.
     */
    @Synchronized
    fun acquire(owner: String, config: WireGuardConfig, resolve: (String) -> List<String>): String? {
        if (!WgNative.available) return fail(config, "Bu derlemede WireGuard motoru yok. Resmî sürümü (GitHub Releases) kur.")
        val ip = endpointIp(config.endpointHost, resolve)
            ?: return fail(config, "Sunucu adı çözülemedi: ${config.endpointHost}. İnternet bağlantını kontrol et ya da başka bir konum için dosya indir.")
        val uapi = config.toUapi(ip)
        val key = uapi + config.addresses + config.dns + config.mtu
        if (runningKey == key) {
            holders += owner
            return null
        }
        // Başka bir yapılandırma çalışıyorsa yalnız servis (tünelin sahibi) değiştirebilir.
        if (runningKey != null && owner != OWNER_SERVICE && holders.any { it != owner }) {
            return "VPN Tüneli şu an başka bir yapılandırmayla çalışıyor; değişiklik bağlantıya birkaç saniye içinde uygulanır."
        }
        val err = runCatching {
            WgNative.nativeStart(uapi, config.addresses.joinToString(","), config.dns.joinToString(","), config.mtu, "127.0.0.1:$PORT")
        }.getOrElse { it.message ?: "native error" }
        if (err.isNotEmpty()) {
            Log.w(TAG, "WireGuard başlatılamadı: $err")
            runningKey = null; holders.clear()
            return fail(config, describe(err))
        }
        runningKey = key
        holders += owner
        _status.value = WgStatus(running = true, summary = config.summary)
        return null
    }

    @Synchronized
    fun release(owner: String) {
        holders -= owner
        if (holders.isNotEmpty()) return
        if (runningKey != null && WgNative.available) runCatching { WgNative.nativeStop() }
        runningKey = null
        _status.update { WgStatus(summary = it.summary) }
    }

    /** Canlı sayaçları yeniler (el sıkışma zamanı, bayt sayıları). */
    @Synchronized
    fun refresh(): WgStatus {
        if (runningKey == null || !WgNative.available) return _status.value
        val raw = runCatching { WgNative.nativeStatus() }.getOrDefault("")
        return WgStatus.parse(raw, _status.value).also { _status.value = it }
    }

    val isRunning: Boolean @Synchronized get() = runningKey != null

    private fun fail(config: WireGuardConfig, message: String): String {
        _status.value = WgStatus(running = false, summary = config.summary, error = message)
        return message
    }

    private fun describe(err: String): String = when {
        "listen" in err -> "Yerel port ($PORT) kullanımda; uygulamayı kapatıp yeniden aç."
        "config" in err -> "Yapılandırma WireGuard tarafından reddedildi. Dosyayı sağlayıcıdan yeniden indir."
        "address" in err -> "Yapılandırmadaki Address satırı geçersiz."
        else -> "WireGuard başlatılamadı ($err)."
    }

    internal fun endpointIp(host: String, resolve: (String) -> List<String>): String? {
        if (WireGuardConfig.validIp(host)) return host
        val viaDoh = runCatching { resolve(host) }.getOrDefault(emptyList()).filter { WireGuardConfig.validIp(it) }
        viaDoh.firstOrNull { '.' in it && ':' !in it }?.let { return it }
        viaDoh.firstOrNull()?.let { return it }
        // DoH erişilemezse sistem çözümleyicisi (DPI Bypass kendi tünelinin dışında).
        return runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?.sortedBy { if (it is Inet4Address) 0 else 1 }?.firstOrNull()?.hostAddress
    }
}
