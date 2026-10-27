package net.atom.dpibypass.vpn

import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.ExitMatcher
import net.atom.dpibypass.social.ExitServer

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
        )
    }
}

/** Yurt dışı çıkışın tünele etkisi: sunucu ve yurt dışından çıkacak alan adları. */
data class ExitConfig(val server: ExitServer, val platforms: Set<String>, val extraDomains: String) {
    val matcher: ExitMatcher get() = ExitMatcher.of(platforms, extraDomains)

    companion object {
        /** Kapalıysa, sunucu geçersizse ya da hiçbir alan adı seçilmediyse null: tünel eskisi gibi çalışır. */
        fun from(settings: Settings): ExitConfig? {
            if (!settings.exitEnabled) return null
            val server = ExitServer.parse(settings.exitServer) ?: return null
            val config = ExitConfig(server, settings.exitPlatforms, settings.exitExtraDomains.trim())
            return config.takeUnless { it.matcher.isEmpty }
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
    fun decide(
        mode: AppFilterMode,
        selected: Set<String>,
        vodafoneMode: Boolean,
        self: String,
        installed: (String) -> Boolean,
    ): ScopeDecision = when (VodafoneModePolicy.filterMode(mode, vodafoneMode)) {
        AppFilterMode.All -> ScopeDecision.AllExcept(listOf(self))
        AppFilterMode.Include -> selected.filter { it != self && installed(it) }.sorted()
            .let { if (it.isEmpty()) ScopeDecision.EmptyInclude else ScopeDecision.Only(it) }
        AppFilterMode.Exclude -> ScopeDecision.AllExcept(listOf(self) + selected.filter { it != self && installed(it) }.sorted())
    }

    /** [pkg] etkin kapsamda (tünelde) mi? Vodafone modunun önceliği uygulanır. */
    fun covers(mode: AppFilterMode, selected: Set<String>, vodafoneMode: Boolean, pkg: String): Boolean =
        when (VodafoneModePolicy.filterMode(mode, vodafoneMode)) {
            AppFilterMode.All -> true
            AppFilterMode.Include -> pkg in selected
            AppFilterMode.Exclude -> pkg !in selected
        }
}
