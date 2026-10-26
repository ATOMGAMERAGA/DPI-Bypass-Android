package net.atom.dpibypass.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import net.atom.dpibypass.ui.design.GlassLevel
import net.atom.dpibypass.ui.design.GlassSurface
import net.atom.dpibypass.ui.design.LocalChrome
import net.atom.dpibypass.ui.design.LocalShellBackdrop
import net.atom.dpibypass.ui.design.rememberHaptics
import net.atom.dpibypass.ui.theme.LocalStateColors
import net.atom.dpibypass.ui.theme.Motion
import net.atom.dpibypass.ui.theme.PillShape
import kotlin.math.abs
import kotlin.math.min

enum class Dest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector,
) {
    Home("home", "Durum", Icons.Outlined.Shield, Icons.Rounded.Shield),
    Mode("mode", "Strateji", Icons.Outlined.Tune, Icons.Rounded.Tune),
    Apps("apps", "Uygulamalar", Icons.Outlined.Apps, Icons.Rounded.Apps),
    Settings("settings", "Ayarlar", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/** Dock'ta yer almayan alt sayfalar: Ayarlar'dan açılır, gezinmede Ayarlar seçili kalır. */
const val SocialRoute = "social"
const val SocialAdvancedRoute = "social/advanced"
const val SocialDiagnosticsRoute = "social/diagnostics"

/** Bir rotanın dock/raydaki sekmesi; alt sayfalar ait oldukları sekmeyi gösterir. */
fun tabRouteFor(route: String?): String = when (route) {
    null -> Dest.Home.route
    SocialRoute, SocialAdvancedRoute, SocialDiagnosticsRoute -> Dest.Settings.route
    else -> route
}

/**
 * Rehber tam ekran bir görev akışıdır: alttaki ana eylem düğmesi dock'un altında
 * kalmasın diye dock bu sayfada gizlenir (geri ve kapat her zaman üstte).
 */
fun showsDock(route: String?): Boolean = route != SocialRoute

// Dock'un iki durağı. Aradaki geçiş yayla sürülür; ARADA SÜREKLİ BİR DEĞER YOKTUR
// (nedeni aşağıda, "CAM NEDEN ÖLÇEKLENMEZ" başlığında).
private val ItemWidth = 62.dp
private val ItemHeight = 48.dp
private val DockPadding = 7.dp
private val IconSize = 23.dp

private val CompactItemWidth = 54.dp
private val CompactItemHeight = 42.dp
private val CompactDockPadding = 6.dp
private val CompactIconSize = 21.dp

/** Dock'un daralmaya geçtiği kaydırma eşiği. */
private val CompactThreshold = 56.dp

/**
 * Yüzen dock.
 *
 * Tasarım kararları:
 *
 *  * SADECE İKON. Dört sekmelik bir gezinmede etiket, ikonun anlattığından
 *    fazlasını söylemez; ama dock'un yüksekliğini iki katına çıkarır ve ekranın
 *    dibinde kalın bir bant bırakır. Etiketler kaldırıldı, ikonlar büyütüldü ve
 *    seçili/seçili değil ayrımı DOLU-BOŞ ikon çiftiyle yapıldı (Ayarlar ile
 *    Strateji ikonları artık karışmıyor). Erişilebilirlik için her hedefin adı
 *    contentDescription olarak korunuyor.
 *
 *  * GÖSTERGE AKAR, ATLAMAZ. Seçim göstergesi hedefe yayla gider ve giderken
 *    hareket yönünde UZAYIP dikeyde basılır (squash & stretch). Bu, hızlı
 *    sekmelerde bile gözün göstergeyi kaybetmemesini sağlayan klasik animasyon
 *    hilesidir. Duracağı yere yaklaşınca kendi biçimine geri döner.
 *
 *  * GÖSTERGE DOCK'UN ŞEKLİNİ KONUŞUR. Dock tam hap (pill) biçimindedir; içindeki
 *    gösterge de öyledir. Eşmerkezli köşe kuralı: iç yüzeyin yarıçapı = dış
 *    yarıçap − aradaki boşluk. Burada (48+2·7)/2 − 7 = 24 = ItemHeight/2, yani
 *    tam hap. Önceki %42'lik yarıçap bu eşitliği tutturmuyordu; gösterge hap
 *    biçimli dock'un içinde "köşeli bir dikdörtgen" gibi duruyordu — üstelik
 *    yatay esneme (scaleX) köşeleri elipsleştirdiği için uyumsuzluk hareket
 *    sırasında daha da göze batıyordu. Hap biçiminde esneme, köşe bozulması
 *    değil, doğal bir squash & stretch olarak okunur.
 *
 *  * DOCK EKRANA TEPKİ VERİR — AMA CAM ÖLÇEKLENMEZ. Sayfa aşağı kaydırıldıkça
 *    dock küçülür. Bu küçülme artık `graphicsLayer` ölçeği ile DEĞİL, gerçek
 *    yerleşim ölçüleriyle (ikon/kutu/boşluk) yapılıyor.
 *
 *    CAM NEDEN ÖLÇEKLENMEZ: buzlu cam, arkasındaki katmanın kendi konumuna denk
 *    gelen bölümünü örnekleyip bulanıklaştırır (bkz. Glass.kt). Örnekleme,
 *    yüzeyin YERLEŞİM konumuna ve boyutuna göre yapılır. `graphicsLayer` ile
 *    ölçeklenen bir yüzeyde ekrandaki gerçek alan ile örneklenen alan birbirini
 *    tutmaz: bulanık görüntü de dock ile birlikte küçülür, arkasındaki gerçek
 *    içerikle hizası kayar. Sonuç, camın "camlığını" kaybedip düz bir tül gibi
 *    görünmesidir. Üstüne bir de katman opaklığı düşürülüyordu (alpha) — yani
 *    dock küçüldükçe hem hizasını hem yoğunluğunu kaybediyordu. İkisi de
 *    kaldırıldı: dock her boyutta TAM yoğunlukta buzlu camdır.
 *
 *    Yerleşim değişimi yalnızca eşik geçilirken (birkaç yüz ms) yeniden
 *    kompozisyon üretir; kaydırmanın kendisi hâlâ bedava.
 */
@Composable
fun BottomDock(navController: NavController, modifier: Modifier = Modifier) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = tabRouteFor(backStack?.destination?.route)
    val haptics = rememberHaptics()
    val chrome = LocalChrome.current
    val shell = LocalShellBackdrop.current
    val density = LocalDensity.current

    val thresholdPx = with(density) { CompactThreshold.toPx() }
    // derivedStateOf: kaydırma her karede değişir ama SONUÇ (compact) yalnızca
    // eşik geçilirken değişir — yeniden kompozisyon da yalnızca o an olur.
    val compact by remember(chrome, thresholdPx) {
        derivedStateOf { chrome.scrollPx > thresholdPx }
    }

    val itemWidth by animateDpAsState(
        targetValue = if (compact) CompactItemWidth else ItemWidth,
        animationSpec = Motion.spatialSlow(),
        label = "dockItemWidth",
    )
    val itemHeight by animateDpAsState(
        targetValue = if (compact) CompactItemHeight else ItemHeight,
        animationSpec = Motion.spatialSlow(),
        label = "dockItemHeight",
    )
    val dockPadding by animateDpAsState(
        targetValue = if (compact) CompactDockPadding else DockPadding,
        animationSpec = Motion.spatialSlow(),
        label = "dockPadding",
    )
    val iconSize by animateDpAsState(
        targetValue = if (compact) CompactIconSize else IconSize,
        animationSpec = Motion.spatialSlow(),
        label = "dockIconSize",
    )

    val itemWidthPx = with(density) { itemWidth.toPx() }

    val selectedIndex = Dest.entries.indexOfFirst { it.route == current }.coerceAtLeast(0)
    val target = selectedIndex.toFloat()
    val indicator = animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.indicator(),
        label = "dockIndicator",
    )

    GlassSurface(
        modifier = modifier,
        shape = PillShape,
        level = GlassLevel.Shell,
        backdrop = shell,
        borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
    ) {
        Box(Modifier.padding(dockPadding)) {
            DockIndicator(
                position = { indicator.value },
                target = target,
                itemWidthPx = itemWidthPx,
                width = itemWidth,
                height = itemHeight,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dest.entries.forEachIndexed { index, dest ->
                    DockItem(
                        dest = dest,
                        selected = index == selectedIndex,
                        width = itemWidth,
                        height = itemHeight,
                        iconSize = iconSize,
                        onClick = {
                            if (index == selectedIndex) {
                                haptics.tick()
                            } else {
                                haptics.select()
                                navController.navigate(dest.route) {
                                    popUpTo(Dest.Home.route) { saveState = false }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Kayan seçim göstergesi. Hedefe olan uzaklık, hareket süresince şekle
 * dönüştürülür: uzakken yatayda uzar/dikeyde basılır, yaklaşınca toparlanır.
 * Biçim tam haptır; dock ile eşmerkezlidir.
 */
@Composable
private fun DockIndicator(
    position: () -> Float,
    target: Float,
    itemWidthPx: Float,
    width: Dp,
    height: Dp,
) {
    val gradient = LocalStateColors.current.brandGradient()
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayer {
                val p = position()
                translationX = p * itemWidthPx
                // Hızın görsel karşılığı: kalan yol ne kadar uzunsa o kadar esner.
                val stretch = min(abs(target - p), 1f)
                scaleX = 1f + 0.26f * stretch
                scaleY = 1f - 0.18f * stretch
            }
            .clip(PillShape)
            .background(gradient, PillShape),
    )
}

@Composable
private fun DockItem(
    dest: Dest,
    selected: Boolean,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Tek bir "seçilmişlik" değeri; hem ikon değişimini hem büyümeyi sürer.
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.spatialDefault(),
        label = "dockSelection",
    )
    val press = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = Motion.spatialFast(),
        label = "dockPress",
    )
    val idleTint by animateColorAsState(
        targetValue = scheme.onSurfaceVariant,
        animationSpec = Motion.effectsDefault(),
        label = "dockIdleTint",
    )

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = dest.label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                val s = selection.value
                val scale = (1f + 0.14f * s) * (1f - 0.10f * press.value)
                scaleX = scale
                scaleY = scale
                // Seçili ikon bir tık yükselir: göstergenin içinde "oturmuş" durur.
                translationY = -1.5f * s * density
            },
            contentAlignment = Alignment.Center,
        ) {
            // Boş (outlined) ikon: seçim arttıkça söner.
            Icon(
                imageVector = dest.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { alpha = 1f - selection.value },
                tint = idleTint,
            )
            // Dolu ikon: seçim arttıkça belirir.
            Icon(
                imageVector = dest.iconSelected,
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { alpha = selection.value },
                tint = Color.White,
            )
        }
    }
}
