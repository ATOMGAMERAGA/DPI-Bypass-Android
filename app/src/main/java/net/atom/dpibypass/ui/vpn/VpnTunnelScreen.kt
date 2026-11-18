package net.atom.dpibypass.ui.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.Countries
import net.atom.dpibypass.social.ExitProbe
import net.atom.dpibypass.ui.AppEntry
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.apps.AppRow
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScreen
import net.atom.dpibypass.ui.design.AppTextField
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.CardTone
import net.atom.dpibypass.ui.design.IconBubble
import net.atom.dpibypass.ui.design.ListRow
import net.atom.dpibypass.ui.design.RowDivider
import net.atom.dpibypass.ui.design.SectionHeader
import net.atom.dpibypass.ui.design.StatTile
import net.atom.dpibypass.ui.design.SwitchRow
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace
import net.atom.dpibypass.ui.design.rememberHaptics
import net.atom.dpibypass.ui.theme.LocalStateColors
import net.atom.dpibypass.vpn.AppScope
import net.atom.dpibypass.vpn.VpnTunnelConfig
import net.atom.dpibypass.wg.WgStatus
import net.atom.dpibypass.wg.WireGuardConfig

// ---------------------------------------------------------------------------
// VPN Tüneli — Windscribe / Proton VPN, DPI Bypass ile AYNI ANDA.
//
// Android aynı anda yalnızca bir VPN uygulaması çalıştırır; Windscribe açıkken
// DPI Bypass'ı açmak onu kapatır. Çözüm: sağlayıcının verdiği WireGuard
// yapılandırması DPI Bypass'ın kendi tünelinde çalışır. Tek tünel, üç yol:
//   VPN uygulamaları → WireGuard → sağlayıcı (yurt dışı)
//   DPI kapsamındakiler → yerel DPI motoru
//   diğerleri → doğrudan (tünel dışı)
// ---------------------------------------------------------------------------

enum class VpnHeadline { NO_ENGINE, NEED_CONFIG, OFF, NEED_ROUTES, NEED_CONNECT, STARTING, CHECKING, WORKING, IN_TURKEY, FAILED }

object VpnView {
    const val PROTON_URL = "https://account.protonvpn.com/downloads"
    const val WINDSCRIBE_URL = "https://windscribe.com/getconfig/wireguard"

    fun headline(
        settings: Settings,
        engineAvailable: Boolean,
        connection: ConnectionState,
        status: WgStatus,
        probe: ExitProbe.Result?,
    ): VpnHeadline {
        if (!engineAvailable) return VpnHeadline.NO_ENGINE
        if (WireGuardConfig.parseOrNull(settings.vpnConfig) == null) return VpnHeadline.NEED_CONFIG
        if (!settings.vpnTunnelEnabled) return VpnHeadline.OFF
        if (!VpnTunnelConfig.bridgeNeeded(settings)) return VpnHeadline.NEED_ROUTES
        if (connection != ConnectionState.Connected) return VpnHeadline.NEED_CONNECT
        if (!status.running) return if (status.error.isNotEmpty()) VpnHeadline.FAILED else VpnHeadline.STARTING
        return when {
            probe == null -> VpnHeadline.CHECKING
            !probe.ok -> VpnHeadline.FAILED
            probe.inTurkey -> VpnHeadline.IN_TURKEY
            else -> VpnHeadline.WORKING
        }
    }

    /** Uygulama listesi: VPN'e seçilenler başta; arama yoksa sistem uygulamaları gizlenir. */
    fun visibleApps(apps: List<AppEntry>, selected: Set<String>, query: String, limit: Int = 60): List<AppEntry> {
        val q = query.trim().lowercase()
        val base = if (q.isEmpty()) apps.filter { !it.system || it.packageName in selected }
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        return base.sortedWith(compareByDescending<AppEntry> { it.packageName in selected }.thenBy { it.label.lowercase() }).take(limit)
    }

    fun formatBytes(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
        else -> "$n B"
    }
}

@Composable
fun VpnTunnelScreen(viewModel: AppViewModel, onBack: () -> Unit, onConnect: () -> Unit, onOpenApps: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val status by viewModel.vpnStatus.collectAsStateWithLifecycle()
    val probe by viewModel.vpnProbe.collectAsStateWithLifecycle()
    val checking by viewModel.vpnChecking.collectAsStateWithLifecycle()
    val importMessage by viewModel.vpnImportMessage.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshVpnStatus()
            delay(3_000)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importVpnConfig)
    }

    val config = WireGuardConfig.parseOrNull(settings.vpnConfig)
    val headline = VpnView.headline(settings, viewModel.vpnBridgeAvailable, connection, status, probe)
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    AppScreen(
        title = "VPN Tüneli",
        subtitle = "Windscribe ya da Proton VPN'i DPI Bypass ile aynı anda kullan.",
        actions = { IconButton(onClick = onBack, modifier = Modifier.testTag("vpn-back")) { Icon(Icons.Rounded.Close, contentDescription = "Geri") } },
    ) {
        VpnHero(
            headline = headline,
            settings = settings,
            config = config,
            status = status,
            probe = probe,
            connection = connection,
            onToggle = { on -> haptics.confirm(); viewModel.setVpnTunnelEnabled(on) },
            onConnect = onConnect,
        )

        SectionHeader("Nasıl çalışır")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Android aynı anda yalnızca bir VPN çalıştırır: Windscribe açıkken DPI Bypass'ı açarsan Windscribe kapanır. " +
                        "Bu yüzden VPN'in WireGuard dosyası DPI Bypass'ın içinde çalışır. Tek tünel, üç yol:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                RouteLine(Icons.Rounded.VpnKey, "Seçtiğin uygulamalar", "VPN'den çıkar (${config?.provider?.label ?: "Windscribe / Proton"})", LocalStateColors.current.connected)
                RouteLine(Icons.Rounded.Shield, "Uygulamalar sekmesindeki kapsam", "DPI motorundan geçer (" + scopeName(settings.appFilterMode) + ")", MaterialTheme.colorScheme.primary)
                RouteLine(Icons.Rounded.PhoneAndroid, "Diğer her şey", "Doğrudan gider; banka, e-Devlet, oyunlar etkilenmez", MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Windscribe ya da Proton uygulamasını açmana gerek yok; yalnız bir kez yapılandırma dosyasını al.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 1. Yapılandırma ----
        SectionHeader("1 · WireGuard dosyasını al")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ProviderRow(
                name = "Proton VPN",
                badge = "ÜCRETSİZ",
                steps = "account.protonvpn.com → Downloads → WireGuard configuration → platform: Android, " +
                    "ücretsiz bir sunucu seç → Create → Download. Ücretsiz hesapta da olur.",
                onOpen = { open(context, VpnView.PROTON_URL) },
                tag = "vpn-proton",
            )
            RowDivider()
            ProviderRow(
                name = "Windscribe",
                badge = "PRO",
                steps = "windscribe.com → Hesabım → Config Generator → WireGuard → konum ve port (443) seç → Download Config. " +
                    "Windscribe dosyayı yalnız Pro ya da Build-a-Plan hesaplara veriyor.",
                onOpen = { open(context, VpnView.WINDSCRIBE_URL) },
                tag = "vpn-windscribe",
            )
            RowDivider()
            ListRow(
                title = "Başka bir WireGuard sağlayıcısı",
                subtitle = "Mullvad, IVPN, Surfshark ya da kendi WireGuard sunucun: .conf dosyası veren her sağlayıcı olur.",
                icon = Icons.Rounded.Public,
            )
        }

        // ---- 2. İçe aktar ----
        SectionHeader("2 · DPI Bypass'a ekle")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (config != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.VpnKey, tint = LocalStateColors.current.connected, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(config.provider.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(config.endpointDisplay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { haptics.tick(); viewModel.removeVpnConfig() }, modifier = Modifier.testTag("vpn-remove")) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Yapılandırmayı kaldır", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("İndirdiğin .conf dosyasını seç ya da içeriğini kopyalayıp yapıştır.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = "Dosya seç",
                        onClick = { haptics.tick(); runCatching { picker.launch(arrayOf("*/*")) } },
                        icon = Icons.Rounded.FileOpen,
                        tone = if (config == null) ButtonTone.Primary else ButtonTone.Tonal,
                        modifier = Modifier.weight(1f).testTag("vpn-import-file"),
                    )
                    AppButton(
                        text = "Yapıştır",
                        onClick = {
                            haptics.tick()
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.contains("[Interface]", ignoreCase = true)) viewModel.saveVpnConfig(clip)
                            else { pasteOpen = true; pasteText = clip }
                        },
                        icon = Icons.Rounded.ContentPaste,
                        tone = ButtonTone.Tonal,
                        modifier = Modifier.weight(1f).testTag("vpn-import-paste"),
                    )
                }
                AnimatedVisibility(pasteOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            placeholder = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …",
                            singleLine = false,
                            monospace = true,
                            modifier = Modifier.testTag("vpn-paste-field"),
                        )
                        AppButton("Kaydet", onClick = { viewModel.saveVpnConfig(pasteText); if (WireGuardConfig.parseOrNull(pasteText) != null) { pasteOpen = false; pasteText = "" } },
                            enabled = pasteText.isNotBlank(), modifier = Modifier.fillMaxWidth())
                    }
                }
                importMessage?.let {
                    val ok = it.startsWith("Eklendi")
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (ok) LocalStateColors.current.connected else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("vpn-import-message"))
                }
                if (config != null) {
                    AppButton(
                        text = if (checking) "VPN deneniyor…" else "VPN'i test et",
                        onClick = { haptics.tick(); viewModel.testVpn() },
                        tone = ButtonTone.Tonal,
                        icon = Icons.Rounded.Refresh,
                        enabled = !checking && viewModel.vpnBridgeAvailable,
                        modifier = Modifier.fillMaxWidth().testTag("vpn-test"),
                    )
                    probe?.let { ProbeLine(it) }
                }
            }
        }

        // ---- 3. Uygulamalar ----
        SectionHeader("3 · VPN'den geçecek uygulamalar", trailing = {
            if (settings.vpnApps.isNotEmpty()) TagBadge("${settings.vpnApps.size} SEÇİLİ")
        })
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Seçtiğin uygulamaların BÜTÜN trafiği (mesajlar, video, sesli görüşme) VPN'den çıkar. DPI kapsamın " +
                        "(\"Yalnızca seçili\", \"Seçili hariç\") ne olursa olsun bu uygulamalar tünele alınır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextField(value = query, onValueChange = { query = it }, placeholder = "Uygulama ara", leadingIcon = Icons.Rounded.Search)
                val visible = VpnView.visibleApps(apps, settings.vpnApps, query)
                if (apps.isEmpty()) {
                    Text("Uygulamalar yükleniyor…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                visible.forEach { app ->
                    val route = AppScope.route(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, app.packageName, settings.vpnApps)
                    AppRow(
                        app = app,
                        checked = app.packageName in settings.vpnApps,
                        onToggle = { haptics.select(); viewModel.setVpnApp(app.packageName, it) },
                        badge = when (route) {
                            AppScope.Route.Vpn -> "VPN"
                            AppScope.Route.Dpi -> "DPI"
                            AppScope.Route.Direct -> "DOĞRUDAN"
                        },
                        modifier = Modifier.testTag("vpn-app-${app.packageName}"),
                    )
                }
                if (query.isBlank() && apps.size > visible.size) {
                    Text("Sistem uygulamaları ve daha fazlası için ara.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ListRow(
                    title = "DPI kapsamını düzenle",
                    subtitle = "Hangi uygulamaların DPI motorundan geçeceği Uygulamalar sekmesinde.",
                    icon = Icons.Rounded.Apps,
                    onClick = onOpenApps,
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }

        // ---- Sosyal Erişim ----
        SectionHeader("Sosyal Erişim")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Sosyal platformları da VPN'den çıkar",
                subtitle = "Sosyal Erişim'de seçili platformlar (Instagram, X, TikTok…) hangi uygulamadan ya da tarayıcıdan açılırsa " +
                    "açılsın VPN'den gider; e-Devlet doğrulaması için kendi sunucun gerekmez.",
                icon = Icons.Rounded.Public,
                checked = settings.exitEnabled && settings.exitViaVpn,
                onCheckedChange = { on ->
                    viewModel.setExitViaVpn(on)
                    if (on) viewModel.setExitEnabled(true)
                },
                modifier = Modifier.testTag("vpn-social-switch"),
            )
        }

        SectionHeader("Bilmen gerekenler")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Başka VPN açıkken uyar",
                subtitle = "Bağlanırken Windscribe ya da Proton uygulaması açıksa önce sor.",
                icon = Icons.Rounded.NotificationsActive,
                checked = settings.vpnConflictAsk,
                onCheckedChange = viewModel::setVpnConflictAsk,
            )
            RowDivider()
            ListRow(
                title = "Hız ve kota",
                subtitle = "VPN uygulamaları sağlayıcının hızına ve ücretsiz kota sınırına tabidir (Windscribe ücretsiz: aylık sınır; " +
                    "Proton ücretsiz: sınırsız ama yavaş olabilir). DPI uygulamaları etkilenmez.",
                icon = Icons.Rounded.Speed,
            )
            RowDivider()
            ListRow(
                title = "Anahtarın telefonda kalır",
                subtitle = "WireGuard özel anahtarı yalnızca bu cihazda saklanır, yedeklere girmez. Sağlayıcı, VPN'den giden " +
                    "uygulamaların bağlandığı adresleri görebilir; içerik TLS ile şifreli kalır.",
                icon = Icons.Rounded.Lock,
            )
        }
        VSpace(12.dp)
    }
}

private fun scopeName(mode: AppFilterMode) = when (mode) {
    AppFilterMode.All -> "tüm uygulamalar"
    AppFilterMode.Include -> "yalnızca seçililer"
    AppFilterMode.Exclude -> "seçililer hariç"
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun RouteLine(icon: ImageVector, title: String, caption: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint = tint, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProviderRow(name: String, badge: String, steps: String, onOpen: () -> Unit, tag: String) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            TagBadge(badge)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "$name sitesini aç", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Text(steps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProbeLine(result: ExitProbe.Result) {
    val scheme = MaterialTheme.colorScheme
    val good = LocalStateColors.current.connected
    val (text, color) = when {
        !result.ok -> "Olmadı: ${result.error}" to scheme.error
        result.inTurkey -> "${Countries.flag(result.countryCode)} Bu VPN konumu Türkiye'den çıkıyor (${result.ip}). Başka bir ülke seç." to scheme.error
        else -> "${Countries.flag(result.countryCode)} Çalışıyor: ${Countries.name(result.countryCode)} · ${result.ip} · ${result.latencyMs} ms" to good
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("vpn-probe")) {
        TagBadge(if (result.ok && !result.inTurkey) "TAMAM" else "SORUN", color = color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
    }
}

@Composable
private fun VpnHero(
    headline: VpnHeadline,
    settings: Settings,
    config: WireGuardConfig?,
    status: WgStatus,
    probe: ExitProbe.Result?,
    connection: ConnectionState,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val good = LocalStateColors.current.connected
    val provider = config?.provider?.label ?: "VPN"
    data class H(val title: String, val body: String, val tint: Color, val icon: ImageVector)
    val h = when (headline) {
        VpnHeadline.NO_ENGINE -> H("Bu derlemede yok", "WireGuard motoru bu APK'ya girmemiş. GitHub Releases'teki resmî sürümü kur.", scheme.error, Icons.Rounded.ErrorOutline)
        VpnHeadline.NEED_CONFIG -> H("Yapılandırma gerekli", "Proton VPN (ücretsiz) ya da Windscribe'dan WireGuard dosyasını al ve aşağıdan ekle.", scheme.primary, Icons.Rounded.VpnKey)
        VpnHeadline.OFF -> H("Kapalı", "$provider eklendi ama kapalı. Aç: seçtiğin uygulamalar VPN'den gitsin.", scheme.onSurfaceVariant, Icons.Rounded.VpnKey)
        VpnHeadline.NEED_ROUTES -> H("Uygulama seç", "VPN'den geçecek uygulamaları 3. adımda seç ya da Sosyal Erişim platformlarını VPN'e bağla.", scheme.primary, Icons.Rounded.Apps)
        VpnHeadline.NEED_CONNECT -> H(
            if (connection == ConnectionState.Connecting || connection == ConnectionState.Testing) "Bağlanıyor…" else "Hazır — bağlanayım mı?",
            "DPI Bypass kapalı. Bağlanınca $provider, DPI motoruyla birlikte tek tünelde açılır.", scheme.primary, Icons.Rounded.Shield,
        )
        VpnHeadline.STARTING -> H("VPN açılıyor…", "WireGuard tüneli kuruluyor.", scheme.primary, Icons.Rounded.Refresh)
        VpnHeadline.CHECKING -> H("Çıkış ülkesi ölçülüyor…", "VPN üzerinden konum kontrol ediliyor.", scheme.primary, Icons.Rounded.Refresh)
        VpnHeadline.WORKING -> {
            val cc = probe?.countryCode.orEmpty()
            H("${Countries.flag(cc)} ${Countries.name(cc)} üzerinden".trim(),
                "${settings.vpnApps.size} uygulama $provider'dan çıkıyor; DPI motoru da açık.", good, Icons.Rounded.CheckCircle)
        }
        VpnHeadline.IN_TURKEY -> H("VPN Türkiye'den çıkıyor", "Seçtiğin konum Türkiye IP'si veriyor. Sağlayıcıdan başka ülke için dosya indir.", scheme.error, Icons.Rounded.Warning)
        VpnHeadline.FAILED -> H("VPN'e ulaşılamıyor",
            (status.error.ifBlank { null } ?: probe?.error?.ifBlank { null } ?: "Sunucu yanıt vermiyor") +
                ". VPN uygulamalarının bağlantıları güvenlik için kesildi; Türkiye'den gönderilmedi.", scheme.error, Icons.Rounded.ErrorOutline)
    }
    val tone = when (headline) {
        VpnHeadline.WORKING -> CardTone.Accent
        VpnHeadline.FAILED, VpnHeadline.IN_TURKEY, VpnHeadline.NO_ENGINE -> CardTone.Danger
        else -> CardTone.Raised
    }
    AppCard(modifier = Modifier.fillMaxWidth().testTag("vpn-hero"), tone = tone) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(h.icon, tint = h.tint, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("VPN TÜNELİ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(h.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("vpn-headline"))
                }
            }
            Text(h.body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (status.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("İndirilen", VpnView.formatBytes(status.rxBytes), modifier = Modifier.weight(1f))
                    StatTile("Gönderilen", VpnView.formatBytes(status.txBytes), modifier = Modifier.weight(1f))
                }
                Text(
                    if (status.handshakeFresh()) "WireGuard el sıkışması güncel." else "Henüz el sıkışma yok; ilk bağlantıda kurulur.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
        if (config != null) {
            RowDivider()
            SwitchRow(
                title = "VPN Tüneli",
                subtitle = if (settings.vpnTunnelEnabled) "${config.provider.label} · ${settings.vpnApps.size} uygulama" else "Aç: seçili uygulamalar VPN'den gitsin",
                icon = Icons.Rounded.VpnKey,
                checked = settings.vpnTunnelEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("vpn-switch"),
            )
        }
        if (headline == VpnHeadline.NEED_CONNECT && connection != ConnectionState.Connecting && connection != ConnectionState.Testing) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                AppButton("Bağlan", onClick = onConnect, icon = Icons.Rounded.Shield, modifier = Modifier.fillMaxWidth().testTag("vpn-connect"))
            }
        }
    }
}
