package net.atom.dpibypass.ui.apps

import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.ui.AppEntry
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScaffold
import net.atom.dpibypass.ui.design.AppTextField
import net.atom.dpibypass.ui.design.ContentMaxWidth
import net.atom.dpibypass.ui.design.TabletContentMaxWidth
import net.atom.dpibypass.ui.design.LocalTabletLayout
import net.atom.dpibypass.ui.design.DockSpacing
import net.atom.dpibypass.ui.design.PageHeader
import net.atom.dpibypass.ui.design.PublishScroll
import net.atom.dpibypass.ui.design.ScreenPadding
import net.atom.dpibypass.ui.design.SelectionCheck
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace
import net.atom.dpibypass.ui.design.Segment
import net.atom.dpibypass.ui.design.SegmentedControl
import net.atom.dpibypass.ui.design.glass
import net.atom.dpibypass.ui.design.GlassLevel
import net.atom.dpibypass.ui.design.pressScale
import net.atom.dpibypass.ui.design.rememberHaptics
import net.atom.dpibypass.ui.theme.Motion

// ---------------------------------------------------------------------------
// Uygulama ayırma (split tunneling) ekranı.
//
// Eski listede yalnızca ad + paket adı vardı; yüzlerce satır aynı görünüyordu.
// İki değişiklik listeyi taranabilir yapar:
//   * GERÇEK UYGULAMA İKONLARI. İkon, metinden çok daha hızlı tanınır; kullanıcı
//     Discord'u okumadan görür. İkonlar arka planda yüklenir ve önbelleğe alınır.
//   * SEÇİLİLER EN ÜSTTE. Ne seçtiğini görmek için 300 satır kaydırmak gerekmez.
// Ayrıca üstte canlı bir özet (kaç uygulama seçili) ve tek dokunuşla temizleme var.
// ---------------------------------------------------------------------------

private val FAVORITES = setOf(
    "com.discord", "com.android.chrome", "org.mozilla.firefox",
    "com.roblox.client", "com.brave.browser",
)

@Composable
fun AppsScreen(viewModel: AppViewModel) {
    val tablet = LocalTabletLayout.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }

    val listState = rememberLazyListState()
    PublishScroll(listState)
    val haptics = rememberHaptics()

    val listEnabled = settings.appFilterMode != AppFilterMode.All
    val selected = settings.selectedApps
    val filtered = remember(apps, query, selected) {
        val q = query.trim().lowercase()
        val base = if (q.isBlank()) {
            apps
        } else {
            apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        base.sortedWith(
            compareByDescending<AppEntry> { it.packageName in selected }
                .thenByDescending { it.packageName in FAVORITES }
                .thenBy { it.label.lowercase() },
        )
    }

    AppScaffold(title = "Uygulamalar") { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = if (tablet) TabletContentMaxWidth else ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    start = ScreenPadding,
                    end = ScreenPadding,
                    bottom = if (tablet) 32.dp else DockSpacing,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    PageHeader(
                        title = "Uygulamalar",
                        subtitle = "Hangi uygulamaların tünelden geçeceğini seçin. Seçmezseniz tümü geçer.",
                    )
                }

                item(key = "filter") {
                    SegmentedControl(
                        options = listOf(
                            Segment(AppFilterMode.All, "Tümü", Icons.Rounded.SelectAll),
                            Segment(AppFilterMode.Include, "Seçili", Icons.Rounded.DoneAll),
                            Segment(AppFilterMode.Exclude, "Hariç", Icons.Rounded.FilterAlt),
                        ),
                        selected = settings.appFilterMode,
                        onSelect = viewModel::setAppFilterMode,
                    )
                }

                if (settings.vodafoneUnlimitedMode) {
                    item(key = "vodafone-scope") {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text(
                                    text = "Vodafone Sınırsız Modu açık",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "DPI bağlıyken bu mod tüm telefon uygulamalarını kapsar. " +
                                        "Buradaki seçimlerin saklanır; modu kapatınca yeniden uygulanır.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (!listEnabled) {
                    item(key = "all-info") {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text(
                                    text = "Tüm uygulamalar tünelden geçiyor",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                VSpace(6.dp)
                                Text(
                                    text = "Yalnızca belirli uygulamaların (ör. Discord) geçmesini istiyorsanız " +
                                        "\"Seçili\", belirli uygulamaların dışarıda kalmasını istiyorsanız " +
                                        "\"Hariç\" moduna geçin.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    item(key = "search") {
                        AppTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "Uygulama ara",
                            leadingIcon = Icons.Rounded.Search,
                            trailing = {
                                if (query.isNotEmpty()) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Aramayı temizle",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { query = "" },
                                    )
                                }
                            },
                        )
                    }

                    item(key = "summary") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when (settings.appFilterMode) {
                                    AppFilterMode.Include -> "Yalnızca işaretliler tünelde"
                                    AppFilterMode.Exclude -> "İşaretliler tünel dışında"
                                    AppFilterMode.All -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected.isNotEmpty()) {
                                TagBadge("${selected.size} SEÇİLİ")
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Temizle",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .clickable {
                                            haptics.select()
                                            viewModel.clearSelectedApps()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    VSpace(10.dp)
                                    Text(
                                        text = if (apps.isEmpty()) "Uygulamalar yükleniyor…" else "Eşleşen uygulama yok",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in selected,
                            onToggle = {
                                haptics.select()
                                viewModel.toggleApp(app.packageName, it)
                            },
                            // Seçilenler listenin başına taşınır; satır ışınlanmak
                            // yerine yeni yerine kayar, göz onu takip eder.
                            modifier = Modifier.animateItem(
                                fadeInSpec = Motion.effectsDefault(),
                                placementSpec = Motion.spatialDefault(),
                                fadeOutSpec = Motion.effectsFast(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val tint by animateColorAsState(
        targetValue = if (checked) lerp(scheme.surface, scheme.primary, 0.26f) else scheme.surface,
        animationSpec = Motion.effectsDefault(),
        label = "appRowTint",
    )
    val border by animateColorAsState(
        targetValue = if (checked) scheme.primary.copy(alpha = 0.45f) else scheme.onSurface.copy(alpha = 0.10f),
        animationSpec = Motion.effectsDefault(),
        label = "appRowBorder",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .glass(
                shape = MaterialTheme.shapes.medium,
                level = GlassLevel.Card,
                tintColor = tint,
                borderColor = border,
            )
            .clickable(interactionSource = interaction, indication = null) { onToggle(!checked) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, label = app.label)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectionCheck(selected = checked)
    }
}

/** Uygulama ikonu — yüklenene kadar baş harfli yer tutucu gösterilir. */
@Composable
private fun AppIcon(packageName: String, label: String) {
    val bitmap = rememberAppIcon(packageName)
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Basit ikon önbelleği: liste hızlı kaydırılırken aynı ikon defalarca çözülmesin.
private val iconCache = LruCache<String, ImageBitmap>(180)

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager
                        .getApplicationIcon(packageName)
                        .toBitmap(width = 96, height = 96)
                        .asImageBitmap()
                }.getOrNull()?.also { iconCache.put(packageName, it) }
            }
        }
    }.value
}
