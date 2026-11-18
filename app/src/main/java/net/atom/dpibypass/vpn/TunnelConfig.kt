package net.atom.dpibypass.vpn

import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.ExitMatcher
import net.atom.dpibypass.social.ExitServer
import net.atom.dpibypass.wg.WgEngine
import net.atom.dpibypass.wg.WireGuardConfig

/**
 * TUN'u etkileyen ayarlar. Servis yalnızca bunlar değişince tüneli yeniden kurar;
 * tema, titreşim, Sosyal Erişim tercihleri gibi görsel/UI ayarları bu kümede
 * değildir ve çalışan motoru ya da Sosyal Erişim planını yeniden başlatmaz.
 */
data class TunnelConfig(
    val provider: String,
    val customDoh: String,
    val disableQuic: Boolean,
    val vodafoneMode: Boolean,
    val appFilterMode: AppFilterMode,
    val selectedApps: Set<String>,
    /** Yurt dışı çıkış etkin ve geçerliyse sunucu + alan adları; değilse null. */
    val exit: ExitConfig? = null,
    /** VPN Tüneli (WireGuard) etkin ve geçerliyse yapılandırma + uygulamalar; değilse null. */
    val vpn: VpnTunnelConfig? = null,
) {
    companion object {
        fun from(settings: Settings) = TunnelConfig(
            settings.dohProvider.name,
            settings.customDohUrl.trim(),
            settings.disableQuic,
            settings.vodafoneUnlimitedMode,
            settings.appFilterMode,
            settings.selectedApps,
            ExitConfig.from(settings),
            VpnTunnelConfig.from(settings),
        )
    }
}

/** Yurt dışı çıkışın tünele etkisi: sunucu ve yurt dışından çıkacak alan adları. */
data class ExitConfig(
    val server: ExitServer,
    val platforms: Set<String>,
    val extraDomains: String,
    /** Çıkış, VPN Tüneli'nin WireGuard köprüsü mü (kendi proxy sunucusu yerine)? */
    val viaVpn: Boolean = false,
) {
    val matcher: ExitMatcher get() = ExitMatcher.of(platforms, extraDomains)

    companion object {
        /**
         * Kapalıysa, sunucu geçersizse ya da hiçbir alan adı seçilmediyse null: tünel eskisi gibi çalışır.
         * Kaynak "VPN Tüneli" ise VPN Tüneli de geçerli olmalı (kendi sunucusu gerekmez).
         */
        fun from(settings: Settings): ExitConfig? {
            if (!settings.exitEnabled) return null
            val server = if (settings.exitViaVpn) {
                if (VpnTunnelConfig.from(settings) == null) return null
                WgEngine.server
            } else {
                ExitServer.parse(settings.exitServer) ?: return null
            }
            val config = ExitConfig(server, settings.exitPlatforms, settings.exitExtraDomains.trim(), settings.exitViaVpn)
            return config.takeUnless { it.matcher.isEmpty }
        }
    }
}

/**
 * VPN Tüneli: Windscribe / Proton VPN'in WireGuard yapılandırması DPI Bypass'ın
 * kendi tünelinde çalışır. [apps] uygulamaların bütün trafiği oradan çıkar;
 * diğer kapsamdaki uygulamalar DPI motorundan, kapsam dışı olanlar doğrudan gider.
 */
data class VpnTunnelConfig(val config: WireGuardConfig, val apps: Set<String>) {
    companion object {
        fun from(settings: Settings): VpnTunnelConfig? {
            if (!settings.vpnTunnelEnabled) return null
            val config = WireGuardConfig.parseOrNull(settings.vpnConfig) ?: return null
            return VpnTunnelConfig(config, settings.vpnApps)
        }

        /** WireGuard köprüsü çalışmalı mı? (uygulama seçilmiş ya da Sosyal Erişim çıkışı VPN'den) */
        fun bridgeNeeded(settings: Settings): Boolean {
            val vpn = from(settings) ?: return false
            return vpn.apps.isNotEmpty() || ExitConfig.from(settings)?.viaVpn == true
        }
    }
}

/**
 * Android'in uygulama kapsamı kuralları için saf karar.
 *
 * Android'de `addAllowedApplication` hiç çağrılmazsa VPN TÜM uygulamalara uygulanır.
 * "Yalnızca seçili" kipinde liste boşsa ya da listedeki paketlerin hepsi
 * kaldırılmışsa bu, kullanıcının beklediğinin tersidir (ve kendi paketimizi de
 * tünele sokup döngü yaratır). Bu durumda tünel kurulmaz; neden açıkça bildirilir.
 */
sealed interface ScopeDecision {
    /** Tüm uygulamalar; [disallowed] hariç (kendi paketimiz daima burada). */
    data class AllExcept(val disallowed: List<String>) : ScopeDecision
    /** Yalnızca [allowed] tünelde. */
    data class Only(val allowed: List<String>) : ScopeDecision
    /** "Yalnızca seçili" kipinde kurulu, seçili uygulama yok. */
    data object EmptyInclude : ScopeDecision
}

object AppScope {
    /**
     * [forced]: VPN Tüneli uygulamaları. DPI kapsamı ne olursa olsun tünele
     * GİRMELİDİR (yönlendirici onları içeride WireGuard'a ayırır): "Yalnızca
     * seçili" listesine eklenir, "Seçili hariç" listesinden çıkarılır.
     */
    fun decide(
        mode: AppFilterMode,
        selected: Set<String>,
        vodafoneMode: Boolean,
        self: String,
        forced: Set<String> = emptySet(),
        installed: (String) -> Boolean,
    ): ScopeDecision = when (VodafoneModePolicy.filterMode(mode, vodafoneMode)) {
        AppFilterMode.All -> ScopeDecision.AllExcept(listOf(self))
        AppFilterMode.Include -> (selected + forced).filter { it != self && installed(it) }.sorted()
            .let { if (it.isEmpty()) ScopeDecision.EmptyInclude else ScopeDecision.Only(it) }
        AppFilterMode.Exclude -> ScopeDecision.AllExcept(listOf(self) + (selected - forced).filter { it != self && installed(it) }.sorted())
    }

    /** [pkg] etkin kapsamda (tünelde) mi? Vodafone modunun önceliği uygulanır. */
    fun covers(mode: AppFilterMode, selected: Set<String>, vodafoneMode: Boolean, pkg: String, forced: Set<String> = emptySet()): Boolean =
        pkg in forced || when (VodafoneModePolicy.filterMode(mode, vodafoneMode)) {
            AppFilterMode.All -> true
            AppFilterMode.Include -> pkg in selected
            AppFilterMode.Exclude -> pkg !in selected
        }

    /** Uygulama hangi yoldan gider? (arayüzde gösterilir) */
    enum class Route { Vpn, Dpi, Direct }

    fun route(mode: AppFilterMode, selected: Set<String>, vodafoneMode: Boolean, pkg: String, vpnApps: Set<String>): Route = when {
        pkg in vpnApps -> Route.Vpn
        covers(mode, selected, vodafoneMode, pkg) -> Route.Dpi
        else -> Route.Direct
    }
}
