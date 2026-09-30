package net.atom.dpibypass.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.CallSplit
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.atom.dpibypass.R
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.OperationMode
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScaffold
import net.atom.dpibypass.ui.design.CardTone
import net.atom.dpibypass.ui.design.ContentMaxWidth
import net.atom.dpibypass.ui.design.DockSpacing
import net.atom.dpibypass.ui.design.IconBubble
import net.atom.dpibypass.ui.design.ListRow
import net.atom.dpibypass.ui.design.LocalChrome
import net.atom.dpibypass.ui.design.LocalTabletLayout
import net.atom.dpibypass.ui.design.PublishScroll
import net.atom.dpibypass.ui.design.RowDivider
import net.atom.dpibypass.ui.design.ScreenPadding
import net.atom.dpibypass.ui.design.TabletContentMaxWidth
import net.atom.dpibypass.ui.design.SectionHeader
import net.atom.dpibypass.ui.design.StatTile
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace
import net.atom.dpibypass.ui.design.connectionColor
import net.atom.dpibypass.ui.design.connectionHeadline
import net.atom.dpibypass.ui.design.connectionSupport
import net.atom.dpibypass.ui.design.entrance
import net.atom.dpibypass.ui.design.formatUptime
import net.atom.dpibypass.ui.design.upperTr
import net.atom.dpibypass.ui.design.rememberEntrance
import net.atom.dpibypass.ui.design.rememberHaptics
import net.atom.dpibypass.ui.nav.Dest

// ---------------------------------------------------------------------------
// Ana ekran.
//
// Bilgi hiyerarşisi bilinçlidir ve yukarıdan aşağı şu soruları sırayla yanıtlar:
//   1. Hangi ağdayım, hangi moddayım?      → üstteki rozetler
//   2. Bağlı mıyım?                        → kahraman daire (en büyük öğe)
//   3. Ne oluyor / ne yapmalıyım?          → düz Türkçe başlık + açıklama
//   4. Ne kadar iyi çalışıyor?             → canlı ölçümler (ISS, strateji, ping, süre)
//   5. Sık yaptığım şeyler nerede?         → hızlı işlem kartları
//   6. Bu uygulama tam olarak ne yapıyor?  → güven kartı (şeffaflık)
//
// Eski ekranda 2. adımdan sonrası yoktu: koca bir daire ve bir satır yazı. Bir
// VPN/erişim aracında kullanıcının SORDUĞU şey "çalışıyor mu, ne kadar hızlı?"
// olduğu için ölçümler artık ilk ekranda.
// ---------------------------------------------------------------------------

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigate: (String) -> Unit,
    onRequestTile: () -> Unit,
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectedSince by viewModel.connectedSince.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()

    // Bağlantı durumu her değiştiğinde ağ yeniden çözülür: Wi-Fi'dan mobile
    // geçmiş olabiliriz ve gösterilen sağlayıcı bayatlamamalı.
    LaunchedEffect(state) { viewModel.refreshNetwork() }

    val scroll = rememberScrollState()
    PublishScroll(scroll)
    val chrome = LocalChrome.current
    val tablet = LocalTabletLayout.current
    val brandFadePx = with(LocalDensity.current) { 56.dp.toPx() }
    val entranceState = rememberEntrance()
    val entrance = { entranceState.value }
    val haptics = rememberHaptics()

    // Canlı süre sayacı: yalnızca bağlıyken çalışır, saniyede bir tik atar.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state, connectedSince) {
        while (state == ConnectionState.Connected && connectedSince > 0L) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptime = if (state == ConnectionState.Connected && connectedSince > 0L) {
        formatUptime(nowMs - connectedSince)
    } else {
        null
    }

    val auto = settings.operationMode == OperationMode.Auto
    val discordOnly = settings.appFilterMode == AppFilterMode.Include &&
        settings.selectedApps == setOf(AppViewModel.DISCORD_PACKAGE)
    val accent = connectionColor(state)

    AppScaffold(title = "DPI Bypass") { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (tablet) TabletContentMaxWidth else ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(padding)
                    .padding(horizontal = ScreenPadding)
                    .padding(bottom = if (tablet) 32.dp else DockSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VSpace(46.dp)

                // 0 — Marka satırı. Kaydırınca solar ve yerini üstteki cam
                // şeritteki küçük başlığa bırakır (devir teslim).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(entrance, 0)
                        .graphicsLayer {
                            val c = chrome.collapse(brandFadePx)
                            alpha = (1f - c * 1.6f).coerceIn(0f, 1f)
                            translationY = -c * 10.dp.toPx()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "DPI Bypass",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "Kişisel, yasal erişim aracı",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                VSpace(20.dp)

                // 1 — Bağlam rozetleri
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(entrance, 0),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TagBadge(if (auto) "OTOMATİK" else "MANUEL", color = MaterialTheme.colorScheme.primary)
                    // Taşıyıcı rozeti: "hangi ağdayım" sorusunun ilk yarısı.
                    // Sağlayıcı adı (ikinci yarısı) hemen yanında.
                    TagBadge(
                        text = network.transport.displayName.upperTr(),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (discordOnly) {
                        TagBadge("YALNIZCA DISCORD", color = MaterialTheme.colorScheme.secondary)
                    }
                }

                VSpace(22.dp)

                // 2 — Kahraman daire
                Box(Modifier.entrance(entrance, 1)) {
                    ConnectOrb(
                        state = state,
                        uptimeText = uptime,
                        onClick = {
                            when (state) {
                                ConnectionState.Connected,
                                ConnectionState.Connecting,
                                ConnectionState.Testing,
                                -> onDisconnect()
                                else -> onConnect()
                            }
                        },
                    )
                }

                VSpace(24.dp)

                // 3 — Durumun düz Türkçe karşılığı
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(entrance, 2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = connectionHeadline(state),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    VSpace(6.dp)
                    Text(
                        text = connectionSupport(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                VSpace(28.dp)

                // 4 — Canlı ölçümler
                AppCard(modifier = Modifier
                    .fillMaxWidth()
                    .entrance(entrance, 3)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Bağlantı özeti",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TagBadge(
                            text = if (state == ConnectionState.Connected) "CANLI" else "BEKLEMEDE",
                            color = accent,
                        )
                    }
                    RowDivider()
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(
                                label = "Sağlayıcı",
                                // Bağlıyken tünelin kullandığı sağlayıcı; değilken
                                // o an bağlı olunan ağdan canlı çözülen sağlayıcı.
                                value = profile?.ispName
                                    ?: network.label(settings.selectedIsp.displayName),
                                icon = Icons.Rounded.Router,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "Strateji",
                                value = profile?.strategyId ?: if (auto) "Oto" else settings.selectedStrategyId,
                                icon = Icons.Rounded.Tune,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(
                                label = "Gecikme",
                                value = profile?.latencyMs?.let { "$it ms" } ?: "—",
                                icon = Icons.Rounded.Speed,
                                valueColor = if (profile?.latencyMs != null) accent else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "Süre",
                                value = uptime ?: "—",
                                icon = Icons.Rounded.Timer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // 5 — Hızlı işlemler
                SectionHeader("Hızlı işlemler", modifier = Modifier.entrance(entrance, 4))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(entrance, 4),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(
                            icon = Icons.Rounded.Tune,
                            title = "Strateji testi",
                            caption = "En hızlısını bul",
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Dest.Mode.route) },
                        )
                        QuickAction(
                            icon = Icons.Rounded.Apps,
                            title = "Uygulamalar",
                            caption = "Tünelden geçenler",
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Dest.Apps.route) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(
                            icon = Icons.Rounded.Forum,
                            title = "Sadece Discord",
                            caption = if (discordOnly) "Açık" else "Tek dokunuşla kur",
                            selected = discordOnly,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptics.confirm()
                                viewModel.enableDiscordOnlyMode()
                            },
                        )
                        QuickAction(
                            icon = Icons.Rounded.Bolt,
                            title = "Hızlı panel",
                            caption = "Kısayol ekle",
                            modifier = Modifier.weight(1f),
                            onClick = onRequestTile,
                        )
                    }
                }

                // 6 — Şeffaflık kartı
                SectionHeader("Bu uygulama ne yapar", modifier = Modifier.entrance(entrance, 5))
                AppCard(modifier = Modifier
                    .fillMaxWidth()
                    .entrance(entrance, 5)) {
                    ListRow(
                        title = "Trafik cihazından çıkmadan düzenlenir",
                        subtitle = "Uzak sunucuya yönlendirme yok; yalnızca ilk paketler yerelde bölünür. " +
                            "Bu yüzden hız düşmez, ping artmaz.",
                        icon = Icons.Rounded.CallSplit,
                    )
                    RowDivider()
                    ListRow(
                        title = "DNS seçtiğiniz sunucuya gider",
                        subtitle = "Tünel açıkken uygulamalara Ayarlar'da seçtiğiniz DNS verilir; " +
                            "uygulamanın kendi sorguları DoH ile şifreli çözülür.",
                        icon = Icons.Rounded.Dns,
                    )
                    RowDivider()
                    ListRow(
                        title = "Trafiğiniz ŞİFRELENMEZ",
                        subtitle = "Bu bir VPN değildir: IP gizlemez, anonimlik sağlamaz. Amaç yalnızca " +
                            "meşru servislere erişimi açmaktır.",
                        icon = Icons.Rounded.Lock,
                        iconTint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Hızlı işlem kartı: büyük dokunma alanı, ikon + iki satır metin. */
@Composable
private fun QuickAction(
    icon: ImageVector,
    title: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    AppCard(
        modifier = modifier,
        tone = if (selected) CardTone.Accent else CardTone.Plain,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBubble(
                icon = icon,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 38.dp,
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
