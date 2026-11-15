package net.atom.dpibypass.ui.design

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import net.atom.dpibypass.ui.theme.AuroraTeal
import net.atom.dpibypass.ui.theme.AuroraViolet
import net.atom.dpibypass.ui.theme.BgDarkBottom
import net.atom.dpibypass.ui.theme.BgDarkTop
import net.atom.dpibypass.ui.theme.BgLightBottom
import net.atom.dpibypass.ui.theme.BgLightTop
import net.atom.dpibypass.ui.theme.BrandBlue
import net.atom.dpibypass.ui.theme.BrandCyan
import net.atom.dpibypass.ui.theme.LocalIsDarkTheme
import net.atom.dpibypass.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Ortam (ambient) zemin.
//
// Zemin düz bir blok değil, yavaşça akan ışıktan oluşan bir sahnedir. Üstündeki
// bütün yüzeyler bu sahneyi GERÇEKTEN bulanıklaştırarak yüzer (bkz. Glass.kt).
//
// Sahne iki kez kaydedilir:
//   * ambient katmanı — yalnızca aurora. İçerik kartları bunu örnekler.
//   * shell katmanı   — aurora + tüm ekran içeriği. Dock ve başlık şeridi bunu
//     örnekler, böylece altlarından kayan yazılar bulanıklaşır.
// ---------------------------------------------------------------------------

@Immutable
internal data class AuroraBlob(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val color: Color,
    val ax: Float = 0f,
    val ay: Float = 0f,
    val phase: Float = 0f,
    /** 0 → kendi rengi, 1 → tamamen durum rengi (bağlantı durumuna tepki verir). */
    val accentMix: Float = 0f,
)

private const val TWO_PI = (2.0 * PI).toFloat()

/** Aurora döngüsündeki çizim adımı sayısı (~15 adım/sn). */
private const val AURORA_STEPS = Motion.AURORA_CYCLE_MS / 66f

private fun DrawScope.paintAurora(
    blobs: List<AuroraBlob>,
    baseTop: Color,
    baseBottom: Color,
    dark: Boolean,
    progress: Float,
    accent: Color,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    drawRect(Brush.verticalGradient(listOf(baseTop, baseBottom), startY = 0f, endY = h))

    val minDim = min(w, h)
    val ang = progress * TWO_PI
    blobs.forEach { b ->
        val cx = (b.cx + b.ax * cos(ang + b.phase)) * w
        val cy = (b.cy + b.ay * sin(ang + b.phase)) * h
        val center = Offset(cx, cy)
        val r = b.radius * minDim
        val tinted = if (b.accentMix > 0f) {
            lerp(b.color, accent.copy(alpha = b.color.alpha), b.accentMix)
        } else {
            b.color
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tinted, Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }

    // Kenar karartması (vignette): göz merkeze, yani içeriğe çekilir.
    if (dark) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                center = Offset(w / 2f, h * 0.36f),
                radius = minDim * 1.05f,
            ),
        )
    }
}

// Büyük yumuşak ortam ışıkları + küçük parlak "bokeh" lekeleri. Küçük lekeler cam
// yüzeylerin arkasında bulanıklaşınca kayan ışık dokusunu verir.
private fun darkBlobs(): List<AuroraBlob> = listOf(
    AuroraBlob(0.16f, 0.06f, 0.62f, BrandCyan.copy(alpha = 0.30f), 0.05f, 0.04f, 0.0f, accentMix = 0.75f),
    AuroraBlob(0.90f, 0.14f, 0.55f, BrandBlue.copy(alpha = 0.34f), 0.06f, 0.05f, 1.7f, accentMix = 0.35f),
    AuroraBlob(0.86f, 0.84f, 0.62f, AuroraViolet.copy(alpha = 0.26f), 0.05f, 0.06f, 3.2f),
    AuroraBlob(0.08f, 0.92f, 0.56f, AuroraTeal.copy(alpha = 0.22f), 0.06f, 0.05f, 4.8f),
    AuroraBlob(0.30f, 0.30f, 0.16f, BrandCyan.copy(alpha = 0.40f), 0.16f, 0.11f, 0.8f, accentMix = 0.6f),
    AuroraBlob(0.74f, 0.52f, 0.14f, BrandBlue.copy(alpha = 0.38f), 0.13f, 0.15f, 2.5f),
    AuroraBlob(0.46f, 0.76f, 0.12f, Color.White.copy(alpha = 0.10f), 0.18f, 0.10f, 4.1f),
)

private fun lightBlobs(): List<AuroraBlob> = listOf(
    AuroraBlob(0.16f, 0.06f, 0.60f, BrandCyan.copy(alpha = 0.20f), 0.05f, 0.04f, 0.0f, accentMix = 0.7f),
    AuroraBlob(0.90f, 0.14f, 0.55f, BrandBlue.copy(alpha = 0.18f), 0.06f, 0.05f, 1.7f, accentMix = 0.3f),
    AuroraBlob(0.86f, 0.84f, 0.62f, AuroraViolet.copy(alpha = 0.14f), 0.05f, 0.06f, 3.2f),
    AuroraBlob(0.08f, 0.92f, 0.56f, AuroraTeal.copy(alpha = 0.14f), 0.06f, 0.05f, 4.8f),
    AuroraBlob(0.30f, 0.30f, 0.16f, BrandCyan.copy(alpha = 0.22f), 0.16f, 0.11f, 0.8f, accentMix = 0.5f),
    AuroraBlob(0.74f, 0.52f, 0.14f, BrandBlue.copy(alpha = 0.20f), 0.13f, 0.15f, 2.5f),
    AuroraBlob(0.46f, 0.76f, 0.12f, Color.White.copy(alpha = 0.55f), 0.18f, 0.10f, 4.1f),
)

/**
 * Sistemde animasyonlar kapatılmış mı (Geliştirici seçenekleri / erişilebilirlik)?
 * Kapatılmışsa sonsuz döngülü animasyonlar durur — hem erişilebilirlik hem pil için.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            AndroidSettings.Global.getFloat(
                context.contentResolver,
                AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
}

/**
 * Uygulamanın zemini. [accent] bağlantı durumunun rengidir: zemin, bağlandığında
 * yeşile, hata durumunda kırmızıya doğru YUMUŞAKÇA kayar. Kullanıcı ekranın
 * herhangi bir yerine baksa bile durumu çevresel görüşüyle fark eder.
 *
 * [content] kabuk katmanına KAYDEDİLİR (dock/başlık onun arkasını bulanıklaştırır),
 * [overlay] ise kaydın dışında kalır — kendi kendini örnekleyen bir cam olamaz.
 */
@Composable
fun AmbientBackground(
    accent: Color,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val animated = rememberAnimationsEnabled()
    val tick = rememberFrameTick()
    // Aurora katmanını ekrandaki HER kart, kutucuk ve metin alanı örnekler; bu
    // yüzden dokuya alınır. Kabuk katmanını yalnızca dock ve sihirbaz örnekler,
    // ona ayrıca tam ekran tampon ayırmaya değmez.
    // Yalnızca aurora çizimini içerir (çocuk yok): sürüm sayacı içeriği tam izler.
    val ambient = rememberBackdrop(offscreen = true, selfContained = true)
    val shell = rememberBackdrop()

    val transition = rememberInfiniteTransition(label = "ambient")
    val progressState: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(Motion.AURORA_CYCLE_MS, easing = LinearEasing)),
        label = "ambientProgress",
    )
    // Aurora 26 saniyelik bir döngüde piksel altı hızla akar; her karede (60-120
    // Hz) yeniden çizmek gözle ayırt edilemez ama ekrandaki her cam yüzeyin
    // bulanıklığını da her karede yeniden hesaplatır. Saniyede ~15 adım aynı
    // görüntüyü verir; aradaki karelerde zemin ve camlar yeniden çizilmez.
    val steppedProgress = remember(progressState) {
        derivedStateOf { floor(progressState.value * AURORA_STEPS) / AURORA_STEPS }
    }
    // Durum rengi geçişi: renk animasyonu ASLA taşımaz (effects yayı).
    val accentState = animateColorAsState(
        targetValue = accent,
        animationSpec = Motion.effectsSlow(),
        label = "ambientAccent",
    )

    val baseTop = if (dark) BgDarkTop else BgLightTop
    val baseBottom = if (dark) BgDarkBottom else BgLightBottom
    val blobs = remember(dark) { if (dark) darkBlobs() else lightBlobs() }

    CompositionLocalProvider(
        LocalFrameTick provides tick,
        LocalAmbientBackdrop provides ambient,
        LocalShellBackdrop provides shell,
    ) {
        Box(modifier) {
            Box(Modifier.fillMaxSize().backdropSource(shell)) {
                // Aurora sahnesi: animasyon değerleri YALNIZCA çizim anında okunur,
                // böylece her karede yeniden çizim olur ama yeniden kompozisyon olmaz.
                Box(
                    Modifier
                        .fillMaxSize()
                        // Kendi katmanı: üstteki kabuk yeniden çizilince aurora
                        // gereksiz yere yeniden kaydedilmesin.
                        .graphicsLayer()
                        .backdropSource(ambient)
                        .drawBehind {
                            paintAurora(
                                blobs = blobs,
                                baseTop = baseTop,
                                baseBottom = baseBottom,
                                dark = dark,
                                progress = steppedProgress.value,
                                accent = accentState.value,
                            )
                        },
                )
                content()
            }
            overlay()
        }
    }
}
