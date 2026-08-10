package net.atom.dpibypass.dns

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS (DoH) sağlayıcıları.
 *
 * KRİTİK: Türk Telekom, Turkcell/Superonline ve Vodafone düz DNS'i ele geçiriyor
 * (hijack). Bu yüzden çoğu "Discord açılmıyor" durumunun asıl sebebi DPI değil
 * DNS'tir. DoH sunucusuna **doğrudan IP ile** bağlanırız (bootstrap); böylece
 * hijack edilen sistem DNS'ine bağımlı kalmayız. IP literaline yapılan TLS'te
 * SNI gönderilmediği için DPI SNI-filtreleyemez.
 */
enum class DohProvider(
    val displayName: String,
    val url: String,
    /**
     * Aynı sağlayıcının DÜZ DNS adresleri. Tünel kurulurken uygulamalara bunlar
     * verilir (`VpnService.Builder.addDnsServer`).
     *
     * İki adres verilir çünkü tek adres yazıldığında o sunucuya erişilemediği anda
     * Android bir sonraki DNS'e — yani ağın kendi (ele geçirilmiş) sunucusuna —
     * düşer. İkinci adres aynı sağlayıcıdan olduğu için seçim korunur.
     *
     * Eskiden bu liste yoktu; adres DoH URL'inden ayrıştırılıyordu ve URL'de
     * ana bilgisayar adı (IP değil) geçtiği anda sessizce 1.1.1.1'e düşülüyordu.
     * Kullanıcının "seçtiğim DNS etki etmiyor" demesinin sebeplerinden biri buydu.
     */
    val dnsServers: List<String>,
) {
    Cloudflare(
        "Cloudflare",
        "https://1.1.1.1/dns-query",
        listOf("1.1.1.1", "1.0.0.1"),
    ),

    // AdGuard'ın filtresiz çifti. (Eskiden bu girdiye 94.140.14.14 yazılıydı; o
    // adres AdGuard'ın REKLAM ENGELLEYEN çiftidir — etiket ile davranış
    // birbirini tutmuyordu.)
    AdGuard(
        "AdGuard (filtresiz)",
        "https://94.140.14.140/dns-query",
        listOf("94.140.14.140", "94.140.14.141"),
    ),

    AdGuardAds(
        "AdGuard (reklam engelli)",
        "https://94.140.14.14/dns-query",
        listOf("94.140.14.14", "94.140.15.15"),
    ),

    Google(
        "Google",
        "https://8.8.8.8/dns-query",
        listOf("8.8.8.8", "8.8.4.4"),
    );

    companion object {
        fun fromName(name: String?): DohProvider =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Cloudflare
    }
}

/**
 * DoH JSON API (RFC 8484 uyumlu sağlayıcıların ?name=&type= JSON arayüzü) ile
 * A/AAAA çözümlemesi yapan hafif çözümleyici.
 */
class DohResolver(
    private val endpointUrl: String = DohProvider.Cloudflare.url,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Verilen host için IP adreslerini döndürür (önce A, boşsa AAAA).
     * Ağ/engel durumunda boş liste döner (çağıran karar verir).
     */
    fun resolve(host: String): List<String> {
        resolveType(host, 1).takeIf { it.isNotEmpty() }?.let { return it } // A
        return resolveType(host, 28) // AAAA
    }

    private fun resolveType(host: String, type: Int): List<String> {
        return try {
            val url = "$endpointUrl?name=$host&type=$type"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return emptyList()
                buildList {
                    for (i in 0 until answers.length()) {
                        val answer = answers.getJSONObject(i)
                        if (answer.optInt("type") == type) {
                            answer.optString("data").takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH çözümleme başarısız ($host): ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "DohResolver"
    }
}
