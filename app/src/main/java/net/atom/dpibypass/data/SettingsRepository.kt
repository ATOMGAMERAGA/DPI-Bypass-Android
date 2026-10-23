package net.atom.dpibypass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.atom.dpibypass.dns.DnsPlan
import net.atom.dpibypass.dns.DnsPlanner
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.isp.Isp
import net.atom.dpibypass.util.DeviceInfo
import org.json.JSONObject
import java.io.IOException

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
    val vodafoneUnlimitedMode: Boolean = false,
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
    val socialAccessEnabled: Boolean = false,
    val socialAccessMode: net.atom.dpibypass.social.TestMode = net.atom.dpibypass.social.TestMode.MANUAL,
    // Sosyal Erişim'de birlikte çalışması istenen platformlar (kimlikler) ve
    // isteğe bağlı belirli içerik bağlantısı. Süreç yeniden başlayınca korunur.
    val socialPlatforms: Set<String> = emptySet(),
    val socialTargetUrl: String = "",
    // "Sadece Discord" öncesindeki uygulama kapsamı yedeği var mı? (geri alınabilir)
    val scopeBackupAvailable: Boolean = false,
    /**
     * Değer DataStore'dan GERÇEKTEN okundu mu? `stateIn` başlangıç değeri olan
     * varsayılan `Settings()` için false'tur: arayüz o ilk değeri kullanıcının
     * kapalı/boş tercihi gibi uygulamamalı.
     */
    val loaded: Boolean = false,
    /** Ayar dosyası okunamadı; varsayılanlar gösteriliyor (sessizce değil). */
    val readError: Boolean = false,
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

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val samsungDefault: () -> Boolean = DeviceInfo::isSamsung,
) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    /**
     * Okuma hatasında akış çökmez ve sessizce "her şey kapalı" da denmez:
     * varsayılanlar [Settings.readError] işaretiyle yayınlanır.
     */
    val settings: Flow<Settings> = store.data
        .map { p -> p.toSettings() }
        .catch { e -> if (e is IOException) emit(emptyPreferences().toSettings().copy(readError = true)) else throw e }

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
        vodafoneUnlimitedMode = this[Keys.VODAFONE_UNLIMITED_MODE] ?: false,
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
        samsungVpnIndicator = this[Keys.SAMSUNG_VPN_INDICATOR] ?: samsungDefault(),
        socialAccessEnabled = runCatching { this[Keys.SOCIAL_ENABLED] }.getOrNull() ?: false,
        socialAccessMode = net.atom.dpibypass.social.TestMode.parse(runCatching { this[Keys.SOCIAL_MODE] }.getOrNull()),
        socialPlatforms = runCatching { this[Keys.SOCIAL_PLATFORMS] }.getOrNull()
            ?.filter { net.atom.dpibypass.social.SocialPlatform.byId(it) != null }?.toSet() ?: emptySet(),
        socialTargetUrl = runCatching { this[Keys.SOCIAL_TARGET_URL] }.getOrNull() ?: "",
        scopeBackupAvailable = this[Keys.SCOPE_BACKUP_MODE] != null,
        loaded = true,
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
        store.edit { it[Keys.SELECTED_APPS] = apps }

    /**
     * Seçili uygulama listesini DataStore İÇİNDE okuyup-değiştirip yazar. Eskiden
     * arayüz eski bir StateFlow anlık görüntüsünden yeni kümeyi kurup tümünü
     * yazıyordu; hızlı art arda iki dokunuşta biri kayboluyordu (lost update).
     */
    suspend fun updateSelectedApps(transform: (Set<String>) -> Set<String>) =
        store.edit { it[Keys.SELECTED_APPS] = transform(it[Keys.SELECTED_APPS] ?: emptySet()) }

    /**
     * "Sadece Discord": kapsam ve liste TEK yazımda değişir; önceki kapsam
     * yedeklenir ki kullanıcının kendi listesi kalıcı olarak silinmesin.
     * Zaten Sadece Discord ise yedek ezilmez.
     */
    suspend fun enableOnlyApps(apps: Set<String>) = store.edit { p ->
        val mode = AppFilterMode.fromName(p[Keys.APP_FILTER_MODE])
        val current = p[Keys.SELECTED_APPS] ?: emptySet()
        if (!(mode == AppFilterMode.Include && current == apps) && p[Keys.SCOPE_BACKUP_MODE] == null) {
            p[Keys.SCOPE_BACKUP_MODE] = mode.name
            p[Keys.SCOPE_BACKUP_APPS] = current
        }
        p[Keys.OPERATION_MODE] = OperationMode.Auto.name
        p[Keys.APP_FILTER_MODE] = AppFilterMode.Include.name
        p[Keys.SELECTED_APPS] = apps
    }

    /** [enableOnlyApps] öncesindeki kapsamı geri yükler; yedek yoksa bir şey yapmaz. */
    suspend fun restoreScopeBackup() = store.edit { p ->
        val mode = p[Keys.SCOPE_BACKUP_MODE] ?: return@edit
        p[Keys.APP_FILTER_MODE] = mode
        p[Keys.SELECTED_APPS] = p[Keys.SCOPE_BACKUP_APPS] ?: emptySet()
        p.remove(Keys.SCOPE_BACKUP_MODE); p.remove(Keys.SCOPE_BACKUP_APPS)
    }

    /**
     * Paketleri etkin kapsama alır (tek adımlı düzeltme): "Yalnızca seçili"de
     * listeye ekler, "Seçili hariç"te listeden çıkarır, "Tümü"nde dokunmaz.
     */
    suspend fun bringIntoScope(packages: Set<String>) = store.edit { p ->
        val current = p[Keys.SELECTED_APPS] ?: emptySet()
        when (AppFilterMode.fromName(p[Keys.APP_FILTER_MODE])) {
            AppFilterMode.Include -> p[Keys.SELECTED_APPS] = current + packages
            AppFilterMode.Exclude -> p[Keys.SELECTED_APPS] = current - packages
            AppFilterMode.All -> Unit
        }
    }
    suspend fun setAutoConnectOnBoot(v: Boolean) = put(Keys.AUTO_CONNECT_BOOT, v)
    suspend fun setVodafoneUnlimitedMode(v: Boolean) = put(Keys.VODAFONE_UNLIMITED_MODE, v)
    suspend fun setTheme(t: ThemePref) = put(Keys.THEME, t.name)
    suspend fun setHaptics(v: Boolean) = put(Keys.HAPTICS, v)
    suspend fun setDisableQuic(v: Boolean) = put(Keys.DISABLE_QUIC, v)
    suspend fun setExtraBlockedHosts(v: String) = put(Keys.EXTRA_BLOCKED_HOSTS, v)
    suspend fun setOnboardingDone(v: Boolean) = put(Keys.ONBOARDING_DONE, v)
    suspend fun setSamsungVpnIndicator(v: Boolean) = put(Keys.SAMSUNG_VPN_INDICATOR, v)
    suspend fun setSocialAccessEnabled(v: Boolean) = put(Keys.SOCIAL_ENABLED, v)
    suspend fun setSocialAccessMode(v: net.atom.dpibypass.social.TestMode) = put(Keys.SOCIAL_MODE, v.name)
    suspend fun updateSocialPlatforms(transform: (Set<String>) -> Set<String>) =
        store.edit { it[Keys.SOCIAL_PLATFORMS] = transform(it[Keys.SOCIAL_PLATFORMS] ?: emptySet()) }
    suspend fun setSocialTargetUrl(v: String) = put(Keys.SOCIAL_TARGET_URL, v.take(2048))

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
        store.edit { prefs ->
            val json = JSONObject(prefs[Keys.NETWORK_PROFILES] ?: "{}")
            json.put(
                networkKey,
                JSONObject().put(KEY_STRATEGY, strategyId).put(KEY_SAVED_AT, savedAtMs),
            )
            prefs[Keys.NETWORK_PROFILES] = json.toString()
        }
    }

    suspend fun networkProfile(networkKey: String): NetworkProfile? {
        val prefs = store.data.first()
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
        store.edit { it[key] = value }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) =
        store.edit { it[key] = value }

    private object Keys {
        val SOCIAL_ENABLED = booleanPreferencesKey("social_access_enabled")
        val SOCIAL_MODE = stringPreferencesKey("social_access_mode")
        val SOCIAL_PLATFORMS = stringSetPreferencesKey("social_access_platforms")
        val SOCIAL_TARGET_URL = stringPreferencesKey("social_access_target_url")
        val SCOPE_BACKUP_MODE = stringPreferencesKey("scope_backup_mode")
        val SCOPE_BACKUP_APPS = stringSetPreferencesKey("scope_backup_apps")
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
        val VODAFONE_UNLIMITED_MODE = booleanPreferencesKey("vodafone_unlimited_mode")
        val THEME = stringPreferencesKey("theme")
        val HAPTICS = booleanPreferencesKey("haptics")
        val DISABLE_QUIC = booleanPreferencesKey("disable_quic")
        val EXTRA_BLOCKED_HOSTS = stringPreferencesKey("extra_blocked_hosts")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SAMSUNG_VPN_INDICATOR = booleanPreferencesKey("samsung_vpn_indicator")
        val NETWORK_PROFILES = stringPreferencesKey("network_profiles")
    }
}
