package net.atom.dpibypass.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import net.atom.dpibypass.ui.theme.Motion

// ---------------------------------------------------------------------------
// Ekran iskeleti.
//
// Düzen kararı: içerik, üstteki başlık çubuğunun ALTINDAN akar. Kaydırdıkça çubuk
// buzlu cama dönüşür ve küçük başlık yumuşakça belirir. Böylece ekranın üstünde
// ölü bir bant kalmaz ve kullanıcı nerede olduğunu her an bilir.
//
// BAŞLIK DEVİR TESLİMİ. Büyük başlık ile çubuktaki küçük başlık iki ayrı yazı
// gibi davranmaz; TEK bir yazının iki durağıdır:
//   * büyük başlık kaydıkça küçülür, yukarı ve hafifçe sola süzülür, solar;
//   * küçük başlık tam o sırada aşağıdan yukarı gelip aynı boyuta oturur;
//   * cam şerit ve alt kenar çizgisi ayrı bir eğriyle, biraz daha geç belirir.
// Üç öğe farklı hızlarda hareket ettiği için geçiş "kesme" değil "akış" olur.
//
// Kaydırma değeri kompozisyona SIZDIRILMAZ: [ChromeState] içindeki float yalnızca
// graphicsLayer/çizim bloklarında okunur. Böylece kaydırma sırasında tek bir
// yeniden kompozisyon bile olmaz — hareket 120 Hz'de bile pürüzsüz kalır.
// ---------------------------------------------------------------------------

/** Yüzen dock'un altta kapladığı güvenli alan. */
val DockSpacing: Dp = 118.dp

/** İçerik kolonunun büyük ekranlarda genişlemesini sınırlar (okunabilirlik). */
val ContentMaxWidth: Dp = 640.dp
val TabletContentMaxWidth: Dp = 760.dp

val ScreenPadding: Dp = 18.dp

/** Başlığın tamamen daralması için gereken kaydırma mesafesi. */
private val HeaderCollapseDistance: Dp = 72.dp

/**
 * Ekranların kaydırma durumunu kabuk öğeleriyle (başlık şeridi, dock) paylaşan
 * ortak durum. Değerler `MutableFloatState`'tir ve yalnızca çizim aşamasında
 * okunur.
 */
@Stable
class ChromeState {
    /** Aktif ekranın dikey kaydırma miktarı (px). */
    var scrollPx by mutableFloatStateOf(0f)

    fun collapse(distancePx: Float): Float =
        if (distancePx <= 0f) 0f else (scrollPx / distancePx).coerceIn(0f, 1f)
}

val LocalChrome = staticCompositionLocalOf { ChromeState() }
val LocalTabletLayout = staticCompositionLocalOf { false }

/**
 * Kaydırma konumunu kabuğa bağlar. Kompozisyon tetiklemez (snapshotFlow).
 *
 * Ayrıca ekran her açıldığında listeyi EN ÜSTE alır. Önceden sekmeler arası
 * geçişte kaldığınız yer korunuyordu; "Ayarlar'ın dibindeyken Duruma geçip geri
 * dönmek" gibi durumlarda kullanıcı ekranın ortasında bir yerde uyanıyordu.
 * Sekme değiştirmek bir "geri gitme" değil, yeni bir bağlama girmektir — bu
 * yüzden her giriş baştan başlar.
 */
@Composable
fun PublishScroll(scroll: ScrollState) {
    val chrome = LocalChrome.current
    LaunchedEffect(scroll, chrome) {
        chrome.scrollPx = 0f
        scroll.scrollTo(0)
        snapshotFlow { scroll.value }.collect { chrome.scrollPx = it.toFloat() }
    }
}

@Composable
fun PublishScroll(state: LazyListState, headerHeight: Dp = 220.dp) {
    val chrome = LocalChrome.current
    val fallbackPx = with(LocalDensity.current) { headerHeight.toPx() }
    LaunchedEffect(state, chrome, fallbackPx) {
        chrome.scrollPx = 0f
        state.scrollToItem(0)
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // İlk öğeden sonrası için kesin px bilinemez; başlık zaten tamamen
                // daralmıştır, sabit bir "tavan" değeri yeterlidir.
                chrome.scrollPx = if (index > 0) fallbackPx else offset.toFloat()
            }
    }
}

/**
 * Üstte yüzen buzlu cam başlık şeridi + serbest içerik.
 *
 * İçerik, ekranın kendi arka plan katmanına kaydedilir; şerit o katmanı
 * bulanıklaştırır. Yani altından kayan yazılar gerçekten buzlanır.
 */
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val chrome = LocalChrome.current
    val density = LocalDensity.current
    val collapseDistancePx = with(density) { HeaderCollapseDistance.toPx() }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = 52.dp
    val screenBackdrop = rememberBackdrop()
    val ambient = LocalAmbientBackdrop.current

    // Şeridin camı ve alt çizgisi, yazıdan biraz GEÇ gelir: önce yazı yerine
    // oturur, sonra zemin katılaşır. Tersi olsaydı yazı camın içinden "doğuyor"
    // gibi görünürdü.
    val barAlpha = { easeGlass(chrome.collapse(collapseDistancePx)) }
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Box(modifier.fillMaxSize()) {
        // Ekran içeriği kendi katmanına kaydedilir; şerit bu katmanı zeminle
        // BİRLİKTE bulanıklaştırır (zemin alta, içerik üste).
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(screenBackdrop),
        ) {
            content(PaddingValues(top = statusBar + 6.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBar + barHeight),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp),
                level = GlassLevel.Shell,
                backdrop = ambient,
                overlayBackdrop = screenBackdrop,
                borderWidth = 0.dp,
                sheen = false,
                alpha = barAlpha,
            ) {}
            // Şeridin alt kenarındaki saç teli çizgisi: camın bittiği yeri gösterir.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = barAlpha() }
                    .background(hairline),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBar)
                    .height(barHeight)
                    .padding(horizontal = ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            val t = easeTitle(chrome.collapse(collapseDistancePx))
                            alpha = t
                            // Küçük başlık, büyük başlığın bıraktığı yerden gelir:
                            // aşağıdan yukarı + hafif büyüyerek.
                            translationY = (1f - t) * 18.dp.toPx()
                            val s = lerp(0.88f, 1f, t)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                )
                actions()
            }
        }
    }
}

/**
 * Dikey kaydırmalı standart ekran: büyük başlık + içerik. Ekranların çoğu bunu
 * kullanır; böylece kenar boşlukları, maksimum genişlik ve dock boşluğu her
 * yerde birebir aynıdır.
 */
@Composable
fun AppScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val tablet = LocalTabletLayout.current
    val scroll = rememberScrollState()
    PublishScroll(scroll)

    AppScaffold(title = title, modifier = modifier, actions = actions) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (tablet) TabletContentMaxWidth else ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(padding)
                    .padding(horizontal = ScreenPadding)
                    .padding(bottom = if (tablet) 32.dp else DockSpacing),
            ) {
                PageHeader(title = title, subtitle = subtitle)
                content()
            }
        }
    }
}

/**
 * Büyük başlık. Kaydırma ile küçülüp yukarı süzülerek yerini çubuktaki küçük
 * başlığa bırakır — iki başlık asla aynı anda tam görünmez.
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalChrome.current
    val collapseDistancePx = with(LocalDensity.current) { HeaderCollapseDistance.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 46.dp, bottom = 12.dp)
            .graphicsLayer {
                val c = chrome.collapse(collapseDistancePx)
                // Yazı, çubuktaki küçük başlığa doğru "yol alıyor" gibi görünsün:
                // küçülür, yukarı çıkar ve daha erken solar.
                alpha = (1f - c * 1.45f).coerceIn(0f, 1f)
                val s = lerp(1f, 0.86f, easeTitle(c))
                scaleX = s
                scaleY = s
                translationY = -c * 14.dp.toPx()
                transformOrigin = TransformOrigin(0f, 0f)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Yazının eğrisi: erken başlar, geç biter. Büyük başlık solmaya başlar başlamaz
 * küçüğü belirmeye başlasın diye.
 */
internal fun easeTitle(collapse: Float): Float =
    Motion.EmphasizedDecelerate.transform(((collapse - 0.18f) / 0.62f).coerceIn(0f, 1f))

/** Camın eğrisi: yazıdan sonra gelir, son ana kadar tam yoğunlaşmaz. */
internal fun easeGlass(collapse: Float): Float =
    Motion.Emphasized.transform(((collapse - 0.05f) / 0.8f).coerceIn(0f, 1f))

/** Dikey boşluk kısayolu — ekranlarda tekrar eden Spacer'ları sadeleştirir. */
@Composable
fun VSpace(height: Dp) {
    Spacer(Modifier.height(height))
}
