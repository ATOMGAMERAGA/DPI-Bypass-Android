package net.atom.dpibypass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.atom.dpibypass.dns.DnsPlan
import net.atom.dpibypass.dns.DnsPlanner
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.isp.Isp
import net.atom.dpibypass.util.DeviceInfo
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dpi_settings")

/** Tüm kullanıcı ayarlarının anlık görüntüsü. */
data class Settings(
    val operationMode: OperationMode = OperationMode.Auto,
    val selectedIsp: Isp = Isp.Other,
    val selectedStrategyId: String = "P1",
    val advancedEnabled: Boolean = false,
    val advancedArgs: String = "",
    val dohProvider: DohProvider = DohProvider.Cloudflare,
    val customDohUrl: String = "",
    val appFilterMode: AppFilterMode = AppFilterMode.All,
    val selectedApps: Set<String> = emptySet(),
    val autoConnectOnBoot: Boolean = false,
    val theme: ThemePref = ThemePref.System,
    val haptics: Boolean = true,
    // Opt-in: UDP'yi (QUIC/HTTP-3 dahil) tünelde bırakma; varsayılan kapalı ki
    // DNS ve sesli görüşme çalışsın. Bkz. Bölüm 14.
    val disableQuic: Boolean = false,
    val extraBlockedHosts: String = "",
    // Açılıştaki kurulum sihirbazı tamamlandı mı? (false → ilk açılış akışı gösterilir.)
    val onboardingDone: Boolean = false,
    // Samsung + tuşlu navbar cihazlarda: tünel açıkken kalıcı VPN durum göstergesi
    // (bildirim kalıcı/kapatılamaz). Yalnızca Samsung'da ayarlarda görünür.
    val samsungVpnIndicator: Boolean = false,
) {
    /**
     * Etkin DoH endpoint URL'i (özel varsa o, yoksa seçili sağlayıcı).
     *
     * Ayrıştırma [DnsPlanner]'a bırakılır: özel alana çıplak bir IP ("9.9.9.9")
     * ya da yolsuz bir ad ("dns.adguard.com") yazıldığında eskiden buradan
     * doğrudan o metin dönüyor ve hiçbir DoH sorgusu çözülemiyordu.
     */
    fun effectiveDohUrl(): String = DnsPlanner.dohUrl(dohProvider, customDohUrl)

    /**
     * Ağ erişimi olmadan hesaplanan DNS planı — arayüz "hangi DNS gerçekten
     * uygulanacak" sorusunu bununla yanıtlar. Bağlanırken servis aynı planı ad
     * çözümleyicisiyle birlikte yeniden hesaplar.
     */
    fun dnsPreview(): DnsPlan = DnsPlanner.plan(dohProvider, customDohUrl)
}

/**
 * Bir ağda kazanmış strateji ve kaydedildiği an.
 *
 * [isFresh] penceresi bilinçli olarak uzun: strateji bağlanırken zaten CANLI
 * doğrulanıyor, yani "eski" bir profil de gerçekten çalıştığı doğrulanmadan
 * kullanılmıyor. Pencere, "çalışıyor ama artık en iyisi değil" ihtimali için
 * ara sıra yeniden yarıştırmaya yarar.
 */
data class NetworkProfile(val strategyId: String, val savedAtMs: Long) {
    fun isFresh(nowMs: Long, windowMs: Long = FRESH_WINDOW_MS): Boolean =
        savedAtMs > 0L && nowMs - savedAtMs in 0..windowMs

    companion object {
        const val FRESH_WINDOW_MS = 12L * 60 * 60 * 1000
    }
}

private const val KEY_STRATEGY = "id"
private const val KEY_SAVED_AT = "at"

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings = Settings(
        operationMode = OperationMode.fromName(this[Keys.OPERATION_MODE]),
        selectedIsp = Isp.fromId(this[Keys.SELECTED_ISP]),
        selectedStrategyId = this[Keys.SELECTED_STRATEGY] ?: "P1",
        advancedEnabled = this[Keys.ADVANCED_ENABLED] ?: false,
        advancedArgs = this[Keys.ADVANCED_ARGS] ?: "",
        dohProvider = DohProvider.fromName(this[Keys.DOH_PROVIDER]),
        customDohUrl = this[Keys.CUSTOM_DOH_URL] ?: "",
        appFilterMode = AppFilterMode.fromName(this[Keys.APP_FILTER_MODE]),
        selectedApps = this[Keys.SELECTED_APPS] ?: emptySet(),
        autoConnectOnBoot = this[Keys.AUTO_CONNECT_BOOT] ?: false,
        theme = ThemePref.fromName(this[Keys.THEME]),
        haptics = this[Keys.HAPTICS] ?: true,
        // Varsayılan KAPALI: UDP tünelde kalır ki DNS ve Discord sesli sohbeti
        // (UDP/RTP ses akışı) çalışsın. Açılırsa UDP düşer ve sesli sohbet susar.
        disableQuic = this[Keys.DISABLE_QUIC] ?: false,
        extraBlockedHosts = this[Keys.EXTRA_BLOCKED_HOSTS] ?: "",
        onboardingDone = this[Keys.ONBOARDING_DONE] ?: false,
        // Samsung'da VARSAYILAN AÇIK: ayar zaten yalnızca Samsung'da görünüyor ve
        // Now Bar göstergesi bu cihazlarda beklenen davranış. Kullanıcı kapatana
        // kadar canlı gösterge istenir.
        samsungVpnIndicator = this[Keys.SAMSUNG_VPN_INDICATOR] ?: DeviceInfo.isSamsung(),
    )

    suspend fun setOperationMode(mode: OperationMode) = put(Keys.OPERATION_MODE, mode.name)
    suspend fun setSelectedIsp(isp: Isp) = put(Keys.SELECTED_ISP, isp.name)
    suspend fun setSelectedStrategy(id: String) = put(Keys.SELECTED_STRATEGY, id)
    suspend fun setAdvancedEnabled(enabled: Boolean) = put(Keys.ADVANCED_ENABLED, enabled)
    suspend fun setAdvancedArgs(args: String) = put(Keys.ADVANCED_ARGS, args)
    suspend fun setDohProvider(provider: DohProvider) = put(Keys.DOH_PROVIDER, provider.name)
    suspend fun setCustomDohUrl(url: String) = put(Keys.CUSTOM_DOH_URL, url)
    suspend fun setAppFilterMode(mode: AppFilterMode) = put(Keys.APP_FILTER_MODE, mode.name)
    suspend fun setSelectedApps(apps: Set<String>) =
        context.dataStore.edit { it[Keys.SELECTED_APPS] = apps }
    suspend fun setAutoConnectOnBoot(v: Boolean) = put(Keys.AUTO_CONNECT_BOOT, v)
    suspend fun setTheme(t: ThemePref) = put(Keys.THEME, t.name)
    suspend fun setHaptics(v: Boolean) = put(Keys.HAPTICS, v)
    suspend fun setDisableQuic(v: Boolean) = put(Keys.DISABLE_QUIC, v)
    suspend fun setExtraBlockedHosts(v: String) = put(Keys.EXTRA_BLOCKED_HOSTS, v)
    suspend fun setOnboardingDone(v: Boolean) = put(Keys.ONBOARDING_DONE, v)
    suspend fun setSamsungVpnIndicator(v: Boolean) = put(Keys.SAMSUNG_VPN_INDICATOR, v)

    /**
     * Ağ profili: bir ağ anahtarına (taşıyıcı + ISS) o ağda KAZANMIŞ stratejiyi
     * bağlar. Bağlanırken önce bu strateji canlı olarak doğrulanır; çalışıyorsa
     * yarışa hiç girilmez (bkz. DpiVpnService.resolvePlan). Zaman damgası da
     * saklanır: eskiyen profil, "hâlâ en iyisi mi" diye yeniden yarıştırılır.
     *
     * JSON olarak saklanır. Eski sürüm değeri düz metin (yalnızca strateji kimliği)
     * yazıyordu; okuma o biçimi de kabul eder.
     */
    suspend fun saveNetworkProfile(
        networkKey: String,
        strategyId: String,
        savedAtMs: Long = System.currentTimeMillis(),
    ) {
        context.dataStore.edit { prefs ->
            val json = JSONObject(prefs[Keys.NETWORK_PROFILES] ?: "{}")
            json.put(
                networkKey,
                JSONObject().put(KEY_STRATEGY, strategyId).put(KEY_SAVED_AT, savedAtMs),
            )
            prefs[Keys.NETWORK_PROFILES] = json.toString()
        }
    }

    suspend fun networkProfile(networkKey: String): NetworkProfile? {
        val prefs = context.dataStore.data.first()
        val json = runCatching { JSONObject(prefs[Keys.NETWORK_PROFILES] ?: "{}") }
            .getOrElse { return null }
        return when (val entry = json.opt(networkKey)) {
            is JSONObject -> {
                val id = entry.optString(KEY_STRATEGY).takeIf { it.isNotBlank() } ?: return null
                NetworkProfile(id, entry.optLong(KEY_SAVED_AT, 0L))
            }
            // Eski biçim: yalnızca strateji kimliği. Zamanı bilinmediği için
            // "çok eski" sayılır; bir kez yeniden yarıştırılır ve yeni biçime geçer.
            is String -> entry.takeIf { it.isNotBlank() }?.let { NetworkProfile(it, 0L) }
            else -> null
        }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) =
        context.dataStore.edit { it[key] = value }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) =
        context.dataStore.edit { it[key] = value }

    private object Keys {
        val OPERATION_MODE = stringPreferencesKey("operation_mode")
        val SELECTED_ISP = stringPreferencesKey("selected_isp")
        val SELECTED_STRATEGY = stringPreferencesKey("selected_strategy")
        val ADVANCED_ENABLED = booleanPreferencesKey("advanced_enabled")
        val ADVANCED_ARGS = stringPreferencesKey("advanced_args")
        val DOH_PROVIDER = stringPreferencesKey("doh_provider")
        val CUSTOM_DOH_URL = stringPreferencesKey("custom_doh_url")
        val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
        val SELECTED_APPS = stringSetPreferencesKey("selected_apps")
        val AUTO_CONNECT_BOOT = booleanPreferencesKey("auto_connect_boot")
        val THEME = stringPreferencesKey("theme")
        val HAPTICS = booleanPreferencesKey("haptics")
        val DISABLE_QUIC = booleanPreferencesKey("disable_quic")
        val EXTRA_BLOCKED_HOSTS = stringPreferencesKey("extra_blocked_hosts")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SAMSUNG_VPN_INDICATOR = booleanPreferencesKey("samsung_vpn_indicator")
        val NETWORK_PROFILES = stringPreferencesKey("network_profiles")
    }
}
