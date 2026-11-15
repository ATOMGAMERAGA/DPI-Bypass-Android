package net.atom.dpibypass.isp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Şu an gerçekten hangi taşıyıcı üzerinden internete çıkıyoruz? */
enum class Transport {
    WiFi,
    Cellular,
    Ethernet,
    Unknown;

    val displayName: String
        get() = when (this) {
            WiFi -> "Wi-Fi"
            Cellular -> "Mobil veri"
            Ethernet -> "Kablolu"
            Unknown -> "Bilinmiyor"
        }
}

/**
 * ISS parmak izi — kesin değil, sadece strateji havuzunu önceliklendirmek için
 * ipucu. Nihai karar canlı testtedir (StrategyTester).
 *
 * ---------------------------------------------------------------------------
 * ÖNEMLİ DÜZELTME: SIM ≠ İNTERNET SAĞLAYICISI
 * ---------------------------------------------------------------------------
 * Eski davranış "önce SIM'e bak, olmazsa ASN'ye bak" idi. SIM takılı her
 * telefonda SIM okuması BAŞARILI olduğu için, ev Wi-Fi'ında bir Türk Telekom
 * modemine bağlıyken bile uygulama "Vodafone Mobil" diyordu — yani gerçekte
 * kullanılan ağı hiç sormuyordu. Yanlış ISS, yanlış strateji sıralaması demek;
 * yani sadece kozmetik bir hata değil, testin daha yavaş sonuçlanması demek.
 *
 * Yeni davranış: ÖNCE AKTİF TAŞIYICIYA bakılır.
 *   * Wi-Fi / kablolu  → yalnızca dış IP'nin ASN/org adı kullanılır. SIM'e hiç
 *     bakılmaz; o an SIM'in internetle bir ilgisi yoktur.
 *   * Mobil veri       → SIM (MCC+MNC) birincil, ASN yedek.
 *   * Bilinmiyor       → ASN birincil, SIM yedek.
 *
 * Tünel açıkken "aktif ağ" VPN'in kendisidir; bu yüzden VPN taşıyıcısı görülünce
 * altta yatan gerçek ağ aranır.
 */
class IspDetector(private val context: Context) {

    private val client: OkHttpClient get() = sharedClient

    /** O an internete çıkılan taşıyıcı. VPN aktifse alttaki gerçek ağa bakar. */
    fun activeTransport(): Transport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Transport.Unknown
        val caps = underlyingCapabilities(cm) ?: return Transport.Unknown
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WiFi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            else -> Transport.Unknown
        }
    }

    /**
     * Aktif ağın yetenekleri. Aktif ağ bir VPN ise (yani kendi tünelimiz açıksa)
     * VPN olmayan, internet yeteneği olan ilk ağa düşülür — yoksa "Wi-Fi'dayım
     * ama tünel açık" durumunda taşıyıcı hep VPN görünürdü.
     */
    private fun underlyingCapabilities(cm: ConnectivityManager): NetworkCapabilities? {
        val active: Network? = cm.activeNetwork
        val activeCaps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return activeCaps
        }
        val networks = runCatching { cm.allNetworks }.getOrDefault(emptyArray())
        return networks
            .asSequence()
            .mapNotNull { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .firstOrNull()
            ?: activeCaps
    }

    /** SIM operatöründen (MCC+MNC) ISS tahmini. YALNIZCA mobil veride anlamlıdır. */
    fun detectFromSim(): Isp? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val operator = tm?.simOperator // MCC+MNC, ör. "28601"
            Log.d(TAG, "simOperator=$operator")
            Isp.fromSimCode(operator)
        } catch (e: Exception) {
            Log.w(TAG, "SIM operatörü okunamadı: ${e.message}")
            null
        }
    }

    /**
     * Wi-Fi/ev interneti için dış IP'nin ASN/org adını çözer. Engel/başarısızlıkta
     * null döner (çağıran doğrudan canlı teste düşebilir).
     */
    suspend fun detectFromAsn(): Isp? = withContext(Dispatchers.IO) {
        val org = fetchOrg() ?: return@withContext null
        Log.d(TAG, "ASN org=$org")
        Isp.fromAsnOrg(org)
    }

    /**
     * En iyi tahmin — o an gerçekten bağlı olunan ağa göre.
     */
    suspend fun bestGuess(): Isp? {
        val transport = activeTransport()
        Log.d(TAG, "activeTransport=$transport")
        return when (transport) {
            // Kablosuz/kablolu ağda SIM'in hiçbir söz hakkı yok.
            Transport.WiFi, Transport.Ethernet -> detectFromAsn()
            Transport.Cellular -> detectFromSim() ?: detectFromAsn()
            Transport.Unknown -> detectFromAsn() ?: detectFromSim()
        }
    }

    private fun fetchOrg(): String? {
        // ipinfo.io/org → "AS9121 Turk Telekom" gibi bir dize döner.
        return try {
            val request = Request.Builder()
                .url("https://ipinfo.io/org")
                .header("Accept", "text/plain")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ASN lookup başarısız: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "IspDetector"

        private val sharedClient: OkHttpClient by lazy {
            net.atom.dpibypass.util.Http.base.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Ağ profillerinin anahtarı: taşıyıcı + ISS. SSID kullanılmaz — okumak
         * konum izni ister ve bu uygulamanın hiçbir yerinde konum istemiyoruz.
         * Taşıyıcı + ISS ikilisi, "evdeki Türk Telekom" ile "dışarıdaki Turkcell
         * mobil"i ayırmaya zaten yeter; DPI davranışı da bu ikiliye bağlıdır.
         */
        fun networkKey(transport: Transport, isp: Isp): String = "${transport.name}:${isp.name}"
    }
}
