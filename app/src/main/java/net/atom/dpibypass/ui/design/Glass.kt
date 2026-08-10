package net.atom.dpibypass.ui.design

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.atom.dpibypass.ui.theme.LocalIsDarkTheme
import kotlin.math.ceil

// ---------------------------------------------------------------------------
// FROSTED GLASS (buzlu cam) motoru.
//
// Eski yaklaşım "yarı saydam renk" idi: yüzeyin altındaki her şey olduğu gibi,
// keskin biçimde sızıyordu. Bu cam değil, tüldür. Gerçek buzlu cam üç şeyin
// birlikte olmasını ister:
//
//   1. ARKA PLANIN KENDİSİ bulanıklaşmalı (backdrop blur) — yüzeyin arkasındaki
//      VE ÇEVRESİNDEKİ piksellerin gerçekten örneklenip dağıtılması gerekir.
//      "Çevresindeki" kısmı isteğe bağlı bir incelik değil, şarttır: bkz.
//      [blurPadding].
//   2. Bulanıklığın ardından renk doygunluğu yükselmeli — yoksa bulanıklık
//      arkadaki her şeyi griye çeken bir ortalamaya döner (bkz. [saturation]).
//   3. Üstüne ince bir renk tonu (tint) binmeli — camın kendi rengi.
//   4. Üst kenarda parlama (specular sheen) + saç teli kalınlığında kenarlık
//      olmalı — camın KALINLIĞINI gösteren şey budur.
//
// Compose'da (1) yerleşik değildir; CSS'teki backdrop-filter'ın karşılığı yoktur.
// Çözüm Compose 1.7'nin GraphicsLayer API'si: arka plan içeriği bir katmana
// KAYDEDİLİR (record), sonra her cam yüzey o katmanın yalnızca kendi arkasına
// denk gelen bölümünü kendi katmanına çizip üzerine BlurEffect uygular.
//
// İki ayrı arka plan katmanı vardır ve hangisinin kullanılacağı yüzeyin
// hiyerarşideki yerine bağlıdır:
//
//   * [LocalAmbientBackdrop] — yalnızca aurora zemini. İÇERİK AKIŞINDAKİ yüzeyler
//     (kartlar, ölçüm kutucukları, metin alanları, kahraman daire) bunu kullanır;
//     çünkü onların arkasında gerçekten sadece zemin vardır.
//   * [LocalShellBackdrop] — zemin + tüm ekran içeriği. YÜZEN kabuk öğeleri
//     (dock, üst başlık şeridi) bunu kullanır; böylece altlarından kayan yazılar
//     gerçekten bulanıklaşır.
//
// Kural: bir yüzey, kendisini de içeren bir katmanı örnekleyemez (sonsuz döngü).
// Bu yüzden dock ve overlay'ler kayıt alanının DIŞINDA durur.
// ---------------------------------------------------------------------------

/**
 * Bir arka plan kayıt noktası. [layer] her karede yeniden kaydedilir; [origin]
 * kaydın pencere içindeki sol-üst köşesidir (cam yüzeyler kendi konumlarını buna
 * göre öteler).
 *
 * Alanlar yalnızca ÇİZİM sırasında okunur/yazılır — bilerek `State` değildir,
 * aksi hâlde her karede yeniden kompozisyon tetiklenirdi.
 */
@Stable
class BackdropState internal constructor(internal val layer: GraphicsLayer) {
    internal var origin: Offset = Offset.Zero
    internal val hasContent: Boolean
        get() = layer.size.width > 0 && layer.size.height > 0
}

/**
 * [offscreen] katmanı ekran dışı bir dokuya (texture) çeker. Bir katmanı ÇOK
 * SAYIDA cam yüzey örnekliyorsa bu şarttır: aksi hâlde aurora sahnesinin çizim
 * listesi her yüzey için baştan işletilir ve maliyet yüzey sayısıyla çarpılır.
 * Dokuya alınınca her örnekleme tek bir doku kopyasına iner.
 *
 * Karşılığında tam ekran boyutunda bir tampon ayrılır; bu yüzden yalnızca
 * gerçekten çok örneklenen katman için açılır.
 */
@Composable
fun rememberBackdrop(offscreen: Boolean = false): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer, offscreen) {
        if (offscreen) layer.compositingStrategy = CompositingStrategy.Offscreen
        BackdropState(layer)
    }
}

val LocalAmbientBackdrop = staticCompositionLocalOf<BackdropState?> { null }
val LocalShellBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/**
 * Kare sayacı. Cam yüzeyler çizim sırasında bunu okur; böylece arkalarındaki
 * içerik değiştiğinde (kaydırma, animasyon) yeniden çizilirler. Compose'da bir
 * çizim düğümü, başka bir düğümün içeriği değişti diye kendiliğinden geçersiz
 * olmaz — bağ bu sayaçla kurulur.
 */
val LocalFrameTick = staticCompositionLocalOf<State<Long>> { mutableStateOf(0L) }

@Composable
internal fun rememberFrameTick(): State<Long> {
    val tick = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { tick.value = it }
        }
    }
    return tick
}

/** Bu alt ağacın çizimini [state] katmanına kaydeder (ve ekrana da çizer). */
fun Modifier.backdropSource(state: BackdropState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.origin = it.positionInWindow() }
        .drawWithContent {
            // `record` DrawScope üzerinde GraphicsLayer'a tanımlı bir üye
            // uzantıdır: alıcı katmanın kendisidir.
            state.layer.record { this@drawWithContent.drawContent() }
            drawLayer(state.layer)
        }
}

/** Donanımsal RenderEffect (dolayısıyla gerçek bulanıklık) var mı? */
internal val supportsBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private val FullyOpaque: () -> Float = { 1f }

/**
 * Cam yoğunluğu. Tek tek alfa sayıları ekranlara dağılmasın diye camın "kalınlığı"
 * burada isimlendirilir — böylece tüm uygulamada aynı üç malzeme kullanılır.
 */
enum class GlassLevel {
    /** İçerik kartları — okunabilirlik önce gelir, cam en yoğun olan. */
    Card,

    /** Kart içindeki iç yüzeyler (ölçüm kutucuğu, metin alanı) — daha ince. */
    Inset,

    /** Yüzen kabuk (dock, başlık şeridi) — en güçlü bulanıklık, orta yoğunluk. */
    Shell,

    /** Kahraman daire — en şeffaf; arkasındaki ışık görünsün diye. */
    Hero,
}

// Yarıçap doğrudan maliyettir (bulanıklık örnekleme alanıyla büyür). Kabuk
// öğeleri az sayıda olduğu için cömert; aynı anda onlarca tanesi görünebilen
// içerik kartları daha ölçülü.
internal fun GlassLevel.blurRadius(): Dp = when (this) {
    GlassLevel.Card -> 22.dp
    GlassLevel.Inset -> 16.dp
    GlassLevel.Shell -> 36.dp
    GlassLevel.Hero -> 30.dp
}

// Bulanıklık artık yüzeyin DIŞINI de örneklediği için (bkz. [blurPadding]) tonun
// okunabilirlik adına kalın tutulmasına gerek kalmadı. Eski değerler camı tüle
// çeviriyordu: arkadaki ışık geçmiyor, yalnızca donuk bir yarı saydam blok
// görünüyordu. Yeni değerler camı inceltir; okunabilirlik bulanıklığın kendisi
// ve üstteki parlamayla korunur.
internal fun GlassLevel.tintAlpha(dark: Boolean): Float = when (this) {
    GlassLevel.Card -> if (dark) 0.50f else 0.68f
    GlassLevel.Inset -> if (dark) 0.36f else 0.56f
    GlassLevel.Shell -> if (dark) 0.38f else 0.56f
    GlassLevel.Hero -> if (dark) 0.28f else 0.46f
}

/**
 * Renk doygunluğu çarpanı.
 *
 * Gerçek buzlu cam, arkasındaki rengi yalnızca dağıtmaz; yoğunlaştırır da. Bunun
 * sebebi fiziksel: ışık cam içinde saçılırken aynı renk defalarca örneklenir.
 * Bu yüzden iOS/One UI'ın cam malzemeleri de bulanıklığın ardından doygunluk
 * yükseltir. Doygunluk olmadan bulanıklık, arkadaki her şeyi griye çeken bir
 * ortalamaya dönüşür — "cam" hissini asıl bozan buydu.
 */
internal fun GlassLevel.saturation(): Float = when (this) {
    GlassLevel.Shell -> 1.85f
    GlassLevel.Hero -> 1.55f
    else -> 1.40f
}

/**
 * Bulanıklığın yüzeyin dışından örneklemesi gereken pay.
 *
 * BU SATIR, CAMIN CAM GÖRÜNMESİNİN ŞARTIDIR. Bulanıklık, bir pikselin değerini
 * KOMŞULARINDAN hesaplar. Kayıt yalnızca yüzeyin kendi dikdörtgeni kadar
 * yapılırsa, kenardaki piksellerin komşusu yoktur; TileMode.Clamp kenar pikselini
 * kopyalayarak doldurur. 48 dp yüksekliğindeki bir dock'ta 36 dp yarıçaplı
 * bulanıklık için bu, neredeyse TÜM alanın uydurma veriden gelmesi demektir:
 * sonuç dikey olarak sıvanmış, tek renge yakın bir yayvanlıktır — yani düz bir
 * yarı saydam blok. Camın altından geçen içeriğin dokusu hiç görünmez.
 *
 * Çözüm, kaydı her yönde bulanıklık desteği kadar büyütmek: yüzeyin arkasındaki
 * gerçek pikseller kadar ÇEVRESİNDEKİ gerçek pikseller de kayda girer.
 * Skia'nın Gauss desteği ~1.73·yarıçap; 1.8 ile emniyetli tarafta kalınır.
 */
private fun blurPadding(radiusPx: Float): Int = ceil(radiusPx * 1.8f).toInt()

/**
 * Bulanıklık + doygunluk zinciri. İkisi TEK RenderEffect'te birleşir; ayrı
 * geçişler ek bir tam boy tampon demek olurdu.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun frostEffect(radiusPx: Float, saturation: Float): RenderEffect {
    val blur = android.graphics.RenderEffect.createBlurEffect(
        radiusPx,
        radiusPx,
        android.graphics.Shader.TileMode.CLAMP,
    )
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
    return android.graphics.RenderEffect
        .createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix), blur)
        .asComposeRenderEffect()
}

/**
 * Buzlu cam yüzey modifikatörü.
 *
 * [backdrop] null ise (ya da cihaz bulanıklık desteklemiyorsa) cam daha opak bir
 * renge düşer: bulanıklık yoksa saydamlık okunabilirliği bozar, o yüzden takas
 * bilinçlidir.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    level: GlassLevel = GlassLevel.Card,
    backdrop: BackdropState? = LocalAmbientBackdrop.current,
    overlayBackdrop: BackdropState? = null,
    tintColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    sheen: Boolean = true,
    alpha: () -> Float = FullyOpaque,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val tick = LocalFrameTick.current
    val blurLayer = rememberGraphicsLayer()

    val radiusPx = with(density) { level.blurRadius().toPx() }
    val saturation = level.saturation()
    val effect: RenderEffect? = remember(radiusPx, saturation) {
        // Sürüm denetimi burada AÇIKÇA yazılır; `supportsBlur` üzerinden geçen
        // dolaylı denetimi lint izleyemiyor.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            frostEffect(radiusPx, saturation)
        } else {
            null
        }
    }
    val padPx = remember(radiusPx) { blurPadding(radiusPx) }

    val live = supportsBlur && (backdrop != null || overlayBackdrop != null)
    val baseAlpha = level.tintAlpha(dark)
    // Bulanıklık yoksa cam yoğunlaşır; yoksa arkadaki keskin metin "hayalet" gibi sızar.
    val tint = tintColor.copy(alpha = if (live) baseAlpha else (baseAlpha + 0.42f).coerceAtMost(0.97f))
    // Kayıt alanının dışına taşan pay (ekran kenarları) saydam kalırdı ve
    // bulanıklık o boşluğu içeri çekip camı kenarlarda soldururdu. Taban camın
    // kendi rengiyle doldurulur: dışarısı, zaten üstüne binecek tonun aynısıdır.
    val baseFill = tintColor.copy(alpha = 1f)

    val onSurface = MaterialTheme.colorScheme.onSurface
    val stroke = borderColor ?: onSurface.copy(alpha = if (dark) 0.16f else 0.12f)
    val borderBrush = remember(stroke) {
        Brush.verticalGradient(listOf(stroke, stroke.copy(alpha = stroke.alpha * 0.28f)))
    }
    // Üst kenardaki ışık: camın kalınlığını hissettiren tek detay. Üç durak,
    // tek durağın verdiği "beyaz şerit" izlenimini kırar — parlama tepeden
    // başlar, hızla söner, gövdenin ortasında biter.
    val sheenBrush = remember(dark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.16f else 0.50f),
            0.32f to Color.White.copy(alpha = if (dark) 0.035f else 0.11f),
            1f to Color.Transparent,
        )
    }
    val strokePx = with(density) { borderWidth.toPx() }
    val position = remember { PositionHolder() }

    return this
        .clip(shape)
        .onGloballyPositioned { position.value = it.positionInWindow() }
        .drawWithCache {
            // Kenarlık, şeklin kendi konturundan çizilir: köşe yarıçapı ne olursa
            // olsun cam kenarı gövdeyle birebir örtüşür.
            val outline = if (strokePx > 0f) shape.createOutline(size, layoutDirection, this) else null
            onDrawBehind {
                // Kare bağımlılığı: arkadaki içerik değiştiğinde bu çizim de yenilensin.
                tick.value

                val a = alpha().coerceIn(0f, 1f)
                if (a <= 0.004f) return@onDrawBehind

                val base = backdrop?.takeIf { it.hasContent }
                val over = overlayBackdrop?.takeIf { it.hasContent }
                if (live && (base != null || over != null) && size.minDimension > 0f) {
                    // Kayıt, yüzeyden her yöne [padPx] kadar TAŞAR; bulanıklık
                    // böylece gerçek komşu piksellerden beslenir (bkz. blurPadding).
                    // Çizerken aynı pay geri ötelenir, taşan kısmı da dıştaki
                    // clip(shape) kırpar.
                    val pad = padPx.toFloat()
                    blurLayer.record(
                        size = IntSize(
                            ceil(size.width).toInt() + padPx * 2,
                            ceil(size.height).toInt() + padPx * 2,
                        ),
                    ) {
                        drawRect(baseFill)
                        // İki katman TEK bulanıklık geçişinde birleştirilir: alta zemin,
                        // üstüne içerik. Ayrı ayrı bulanıklaştırmak hem pahalı olur hem
                        // de kenarlarda çift halka bırakırdı.
                        base?.let {
                            translate(
                                left = pad - (position.value.x - it.origin.x),
                                top = pad - (position.value.y - it.origin.y),
                            ) { drawLayer(it.layer) }
                        }
                        over?.let {
                            translate(
                                left = pad - (position.value.x - it.origin.x),
                                top = pad - (position.value.y - it.origin.y),
                            ) { drawLayer(it.layer) }
                        }
                    }
                    blurLayer.renderEffect = effect
                    blurLayer.alpha = a
                    translate(left = -pad, top = -pad) { drawLayer(blurLayer) }
                }
                drawRect(tint, alpha = a)
                if (sheen) drawRect(sheenBrush, alpha = a)
                if (outline != null) {
                    drawOutline(outline, borderBrush, alpha = a, style = Stroke(strokePx))
                }
            }
        }
}

/** Çizim sırasında okunan konum — `State` değil, çünkü kompozisyonu tetiklememeli. */
internal class PositionHolder {
    var value: Offset = Offset.Zero
}

/**
 * Kutu biçimindeki buzlu cam yüzey. Modifikatör sürümünün ([glass]) hazır kabı.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    level: GlassLevel = GlassLevel.Shell,
    backdrop: BackdropState? = LocalAmbientBackdrop.current,
    overlayBackdrop: BackdropState? = null,
    tintColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    sheen: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glass(
            shape = shape,
            level = level,
            backdrop = backdrop,
            overlayBackdrop = overlayBackdrop,
            tintColor = tintColor,
            borderColor = borderColor,
            borderWidth = borderWidth,
            sheen = sheen,
            alpha = alpha,
        ),
        content = content,
    )
}
