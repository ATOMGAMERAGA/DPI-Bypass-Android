package net.atom.dpibypass.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.atom.dpibypass.BuildConfig
import net.atom.dpibypass.R
import net.atom.dpibypass.data.ThemePref
import net.atom.dpibypass.dns.DnsPlan
import net.atom.dpibypass.dns.DohProvider
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScreen
import net.atom.dpibypass.ui.design.AppTextField
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.ChoiceDialog
import net.atom.dpibypass.ui.design.ListRow
import net.atom.dpibypass.ui.design.RowDivider
import net.atom.dpibypass.ui.design.SectionHeader
import net.atom.dpibypass.ui.design.Segment
import net.atom.dpibypass.ui.design.SegmentedControl
import net.atom.dpibypass.ui.design.StatTile
import net.atom.dpibypass.ui.design.SwitchRow
import net.atom.dpibypass.ui.design.VSpace
import net.atom.dpibypass.util.AppUsage
import net.atom.dpibypass.util.DeviceInfo
import net.atom.dpibypass.util.NotificationUtils

// ---------------------------------------------------------------------------
// Ayarlar.
//
// Her satırın solunda tonlanmış bir ikon var: uzun bir ayar listesinde göz
// ikonlara tutunarak arar, metni baştan sona okumaz. Bölümler tek bir yuvarlak
// blokta gruplanır (One UI "focus block") ve ince ayraçlarla bölünür.
//
// Açıklamalar teknik ama SONUÇ ODAKLI yazıldı: "UDP'yi düşür" değil, "açarsanız
// Discord'da ses gitmez" — kullanıcı ne olacağını bilerek karar verir.
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(viewModel: AppViewModel, onRequestTile: () -> Unit = {}) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeDns by viewModel.activeDnsServers.collectAsStateWithLifecycle()
    val vodafoneActive by viewModel.vodafoneModeActive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dohPickerOpen by remember { mutableStateOf(false) }

    AppScreen(
        title = "Ayarlar",
        subtitle = "Bağlantı davranışı, görünüm ve cihaz kısayolları.",
    ) {
        // ---- Hızlı erişim ----
        SectionHeader("Hızlı erişim")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                title = "${DeviceInfo.quickPanelName()}'e kısayol ekle",
                subtitle = "WiFi/Bluetooth kutucuklarının olduğu yere tek dokunuşla aç/kapat " +
                    "düğmesi ekler; uygulamayı açmadan bağlanırsınız.",
                icon = Icons.Rounded.Bolt,
                onClick = onRequestTile,
                trailing = {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (DeviceInfo.isSamsung()) {
                RowDivider()
                SwitchRow(
                    title = "Now Bar'da göster",
                    subtitle = "Tünel açıkken kilit ekranındaki Now Bar'da ve durum çubuğunda canlı " +
                        "bir gösterge tutar; oradan tek dokunuşla kesebilirsiniz.",
                    icon = Icons.Rounded.Notifications,
                    checked = settings.samsungVpnIndicator,
                    onCheckedChange = viewModel::setSamsungVpnIndicator,
                )
                AnimatedVisibility(visible = settings.samsungVpnIndicator) {
                    Column {
                        RowDivider()
                        ListRow(
                            title = "Now Bar görünmüyor mu?",
                            subtitle = nowBarHelpText(
                                remember { NotificationUtils.liveUpdateState(context) }
                            ),
                            icon = Icons.Rounded.HelpOutline,
                            onClick = { openNotificationSettings(context) },
                            trailing = {
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }

        // ---- Bağlantı ----
        SectionHeader("Bağlantı")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Cihaz açılınca otomatik bağlan",
                subtitle = "Yeniden başlatmadan sonra tünel kendiliğinden kurulur.",
                icon = Icons.Rounded.PowerSettingsNew,
                checked = settings.autoConnectOnBoot,
                onCheckedChange = viewModel::setAutoConnectOnBoot,
            )
            RowDivider()
            SwitchRow(
                title = "Vodafone Sınırsız Modu",
                subtitle = when {
                    vodafoneActive -> "Etkin: tüm telefon uygulamalarının IPv4 çıkışı TTL 65 ile gönderiliyor."
                    settings.vodafoneUnlimitedMode -> "Açık: DPI bağlantısı kurulunca etkinleşir. Vodafone hotspot'una bağlanan telefonda kullanın."
                    else -> "Vodafone hotspot'una bağlanan rootsuz telefonda paylaşım TTL'ini düzeltir. DPI bağlantısı gerekir; uygulama seçimi bu modu sınırlamaz."
                },
                icon = Icons.Rounded.WifiTethering,
                checked = settings.vodafoneUnlimitedMode,
                onCheckedChange = viewModel::setVodafoneUnlimitedMode,
            )
            RowDivider()
            SwitchRow(
                title = "UDP/QUIC'i tünelde düşür",
                subtitle = "Kapalı tutun. Açarsanız Discord'da sesli sohbet çalışmaz ve " +
                    "seçtiğiniz DNS'e ulaşılamaz (DNS sorguları da UDP'dir); yalnızca " +
                    "QUIC yüzünden takılan nadir durumlar için vardır.",
                icon = Icons.Rounded.Block,
                checked = settings.disableQuic,
                onCheckedChange = viewModel::setDisableQuic,
            )
        }

        // ---- DNS ----
        SectionHeader("DNS")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                title = "DNS sağlayıcı",
                subtitle = settings.dohProvider.displayName,
                icon = Icons.Rounded.Dns,
                onClick = { dohPickerOpen = true },
                trailing = {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            RowDivider()
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "Seçtiğiniz sunucu iki yere birden uygulanır: uygulamanın kendi " +
                        "sorguları DoH ile (HTTPS üzerinden, IP'ye doğrudan bağlanarak) çözülür, " +
                        "tünel açıldığında da cihazdaki uygulamalara bu sunucunun adresleri " +
                        "verilir. Aşağıya kendi adresinizi yazabilirsiniz: tam DoH adresi " +
                        "(https://…/dns-query), yalnızca alan adı ya da düz IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(12.dp)
                AppTextField(
                    value = settings.customDohUrl,
                    onValueChange = viewModel::setCustomDohUrl,
                    placeholder = "https://1.1.1.1/dns-query · dns.adguard.com · 9.9.9.9",
                )
                VSpace(12.dp)
                DnsEffectNote(
                    preview = remember(settings.dohProvider, settings.customDohUrl) {
                        settings.dnsPreview()
                    },
                    active = activeDns,
                )
            }
        }

        // ---- Ek alan adları ----
        SectionHeader("Ek engellenen alan adları")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "Otomatik testte bu alan adları da denenir. Virgülle ayırın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(12.dp)
                AppTextField(
                    value = settings.extraBlockedHosts,
                    onValueChange = viewModel::setExtraBlockedHosts,
                    placeholder = "discord.com, media.discordapp.net",
                    singleLine = false,
                )
            }
        }

        // ---- Görünüm ----
        SectionHeader("Görünüm ve his")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SegmentedControl(
                    options = listOf(
                        Segment(ThemePref.System, "Sistem", Icons.Rounded.BrightnessAuto),
                        Segment(ThemePref.Light, "Açık", Icons.Rounded.LightMode),
                        Segment(ThemePref.Dark, "Koyu", Icons.Rounded.DarkMode),
                    ),
                    selected = settings.theme,
                    onSelect = viewModel::setTheme,
                )
            }
            RowDivider()
            SwitchRow(
                title = "Dokunsal geri bildirim",
                subtitle = "Bağlanma, seçim ve sekme değişimlerinde hafif titreşim.",
                icon = Icons.Rounded.Vibration,
                checked = settings.haptics,
                onCheckedChange = viewModel::setHaptics,
            )
        }

        // ---- Pil ----
        SectionHeader("Pil")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Arka planda kesintisiz çalışsın",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                VSpace(8.dp)
                Text(
                    text = "Pil optimizasyonunu kapatın. Samsung cihazlarda ayrıca Ayarlar → Pil → " +
                        "Arka plan kullanım limitleri'nden uygulamayı \"Sınırsız\" yapın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(14.dp)
                AppButton(
                    text = "Pil optimizasyonunu kapat",
                    onClick = {
                        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    tone = ButtonTone.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- Bilgi ----
        SectionHeader("Bilgi")
        UsageCard()

        // ---- Hakkında ----
        SectionHeader("Hakkında")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = "DPI Bypass",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Sürüm ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "Bu araç kişisel ve yasal erişim içindir. Trafiğinizi ŞİFRELEMEZ, IP'nizi " +
                        "gizlemez; yalnızca DPI'ın SNI/Host okumasını bozar ve DNS sorgularını " +
                        "seçtiğiniz sunucuya yönlendirir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(8.dp)
                Text(
                    text = "Lisans GPL-3.0 · Bileşenler: ByeDPI (hufrea), hev-socks5-tunnel (heiher) · " +
                        "Referans mimari: ByeDPIAndroid (dovecoteescapee).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            ListRow(
                title = "Kurulum sihirbazını tekrar aç",
                subtitle = "Karşılama adımlarını baştan gösterir.",
                icon = Icons.Rounded.RestartAlt,
                onClick = { viewModel.setOnboardingDone(false) },
                trailing = {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        VSpace(8.dp)
    }

    if (dohPickerOpen) {
        ChoiceDialog(
            title = "DNS sağlayıcı",
            options = DohProvider.entries.map { it to it.displayName },
            selected = settings.dohProvider,
            onSelect = viewModel::setDohProvider,
            onDismiss = { dohPickerOpen = false },
        )
    }
}

/**
 * "Seçtiğim DNS gerçekten uygulanıyor mu?" — bu sorunun tek satırlık, tahminsiz
 * yanıtı.
 *
 * [preview] ayarlardan hesaplanan plandır (ağ gerektirmez); [active] ise servisin
 * tünel kurulurken GERÇEKTEN yazdığı adreslerdir. İkisini ayrı göstermek bilinçli:
 * kullanıcı bir ayarı değiştirdiğinde ne olacağını da, o an ne olduğunu da görür.
 * Yazılan adres uygulanamıyorsa (geçersiz ya da çözülemiyor) sebebi burada söylenir
 * — eskiden sessizce Cloudflare'a düşülüyordu.
 */
@Composable
private fun DnsEffectNote(preview: DnsPlan, active: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        preview.warning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }
        Text(
            text = if (preview.pendingHost != null) {
                // Ad çözümü ağ ister; burada yapılmaz. "Çözülemedi" demek yanlış
                // olurdu — henüz bakılmadı.
                "Uygulamalara verilecek: ${preview.pendingHost} adresinin IP'si " +
                    "(bağlanırken çözülür, olmazsa ${preview.servers.joinToString(", ")})"
            } else {
                "Uygulamalara verilecek: ${preview.servers.joinToString(", ")}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (active.isNotEmpty()) {
            Text(
                text = if (active == preview.servers || preview.pendingHost != null) {
                    // pendingHost varsa beklenen adresler burada bilinemez; "farklı"
                    // demek yanlış olurdu.
                    "Şu an etkin: ${active.joinToString(", ")}"
                } else {
                    // Tünel açıkken yapılan değişiklik saniyeler içinde uygulanır;
                    // bu satır o aradaki farkı gizlemez.
                    "Şu an etkin: ${active.joinToString(", ")} · yeni ayar birazdan uygulanacak"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bilgi bölümü.
//
// "Bu uygulama ne kadar kaynak yiyor?" sorusunun DÜRÜST yanıtı. Android, bir
// uygulamanın pil yüzdesini genel bir API ile vermez — o rakamı yalnızca sistem
// hesaplar. O yüzden burada uydurma bir yüzde yerine gerçekten ölçülebilen
// şeyler var: bu uygulamanın UID'sine yazılan ağ trafiği (tünel trafiği dâhil),
// sürecin harcadığı işlemci süresi ve pil optimizasyonu durumu. Yüzdelik pay
// isteyen kullanıcı tek dokunuşla sistemin kendi ekranına gider.
// ---------------------------------------------------------------------------

@Composable
private fun UsageCard() {
    val context = LocalContext.current
    val usage = rememberUsage()
    val traffic = usage.traffic

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "İndirilen",
                    value = AppUsage.formatBytes(traffic.rxBytes),
                    icon = Icons.Rounded.Download,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Gönderilen",
                    value = AppUsage.formatBytes(traffic.txBytes),
                    icon = Icons.Rounded.Upload,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Toplam veri",
                    value = AppUsage.formatBytes(traffic.totalBytes),
                    icon = Icons.Rounded.DataUsage,
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "İşlemci süresi",
                    value = AppUsage.formatDuration(usage.cpuTimeMs),
                    icon = Icons.Rounded.Memory,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        RowDivider()
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = if (traffic.supported) {
                    "Veri sayaçları cihazın son açılışından beri bu uygulamaya (tünel trafiği " +
                        "dâhil) yazılan baytları gösterir; cihaz yeniden başlayınca sıfırlanır."
                } else {
                    "Bu cihazda uygulama başına veri sayacı okunamıyor."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowDivider()
        ListRow(
            title = "Pil",
            subtitle = buildString {
                if (usage.batteryPercent >= 0) {
                    append("Cihaz pili %${usage.batteryPercent}")
                    if (usage.charging) append(" (şarj oluyor)")
                    append(". ")
                }
                append(
                    if (usage.unrestricted) {
                        "Uygulama pil optimizasyonundan muaf — tünel arka planda kesilmez."
                    } else {
                        "Uygulama pil optimizasyonuna tabi; tünel arka planda kesilebilir."
                    },
                )
                append(" Yüzdelik pil payını Android yalnızca kendi ekranında hesaplar; ")
                append("dokunarak oraya gidin.")
            },
            icon = Icons.Rounded.BatteryStd,
            iconTint = if (usage.unrestricted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            onClick = { openAppDetails(context) },
            trailing = {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        RowDivider()
        ListRow(
            title = "Cihaz ve sürüm",
            subtitle = "${AppUsage.deviceSummary()}\nDPI Bypass ${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.VERSION_CODE}) · ${AppUsage.abiSummary()}",
            icon = Icons.Rounded.PhoneAndroid,
        )
    }
}

/** İki saniyede bir tazelenen kaynak kullanımı anlık görüntüsü. */
@Composable
private fun rememberUsage(): AppUsage.Snapshot {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AppUsage.snapshot(context)) }
    LaunchedEffect(context) {
        while (true) {
            snapshot = AppUsage.snapshot(context)
            delay(2000)
        }
    }
    return snapshot
}

/**
 * Yardım metni tahmin etmez: sistemin o anki cevabını söyler. Kullanıcı "neden
 * çıkmıyor" sorusunu ayar ayar dolaşmadan, tek satırda yanıtlar.
 */
private fun nowBarHelpText(state: NotificationUtils.LiveUpdateState): String = when (state) {
    NotificationUtils.LiveUpdateState.Unsupported ->
        "Bu Android sürümünde canlı bildirim (Now Bar) mekanizması yok; bildirim " +
            "normal şekilde görünmeye devam eder."

    NotificationUtils.LiveUpdateState.NotificationsOff ->
        "Uygulamanın bildirimleri KAPALI — bu hâlde Now Bar'da hiçbir şey çıkamaz. " +
            "Açmak için dokunun."

    NotificationUtils.LiveUpdateState.Blocked ->
        "Sistem bu uygulama için canlı bildirimleri kapatmış. Ayarlar → Bildirimler → " +
            "Canlı bildirimler listesinden açın."

    NotificationUtils.LiveUpdateState.Ready ->
        "Sistem hazır. Uygulama, Now Bar listesine ilk kez BAĞLANDIKTAN sonra düşer " +
            "(sistem, canlı bildirim gönderen uygulamaları o an tanır). Sonra One UI'da " +
            "Ayarlar → Kilit ekranı ve AOD → Now bar bölümünden açık olduğundan emin olun."
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: android.content.Context) {
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
}
