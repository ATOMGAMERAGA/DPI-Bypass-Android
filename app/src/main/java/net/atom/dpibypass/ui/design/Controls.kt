package net.atom.dpibypass.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.atom.dpibypass.ui.theme.LocalStateColors
import net.atom.dpibypass.ui.theme.Motion
import net.atom.dpibypass.ui.theme.PillShape
import kotlin.math.abs
import kotlin.math.min

// ---------------------------------------------------------------------------
// Etkileşim öğeleri.
//
// Ortak kural: her dokunuş ÜÇ kanaldan birden yanıt verir — görsel (basılınca
// küçülme), renk (durum geçişi) ve dokunsal (haptik). Bir uygulamanın "pahalı"
// hissettirmesinin en büyük sebebi budur; tek başına renk paleti yetmez.
// ---------------------------------------------------------------------------

/** Basılıyken hafif küçülme — dokunuşun fiziksel karşılığı. */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.965f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.spatialFast(),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

enum class ButtonTone { Primary, Tonal, Ghost, Danger }

/**
 * Uygulamanın tek buton bileşeni. Görsel ağırlık [tone] ile seçilir; böylece bir
 * ekranda birden fazla "birincil" buton olmaz ve göz nereye basacağını bilir.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    // Büyük yazı boyutunda uzun etiket kesilmesin diye rehber sayfaları satır sayısını artırır.
    maxLines: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    val stateColors = LocalStateColors.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    val background: Brush = when (tone) {
        ButtonTone.Primary -> stateColors.brandGradient()
        ButtonTone.Tonal -> SolidColor(scheme.surfaceContainerHighest)
        ButtonTone.Ghost -> SolidColor(Color.Transparent)
        ButtonTone.Danger -> SolidColor(scheme.error.copy(alpha = 0.18f))
    }
    val contentColor = when (tone) {
        ButtonTone.Primary -> Color.White
        ButtonTone.Tonal -> scheme.onSurface
        ButtonTone.Ghost -> scheme.onSurfaceVariant
        ButtonTone.Danger -> scheme.error
    }
    // Etkin/etkisiz geçişi ani değil: düğme "sönerek" devre dışı kalır, böylece
    // neden tıklanamadığı (ör. test sürüyor) gözle takip edilebilir.
    val enabledAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.42f,
        animationSpec = Motion.effectsDefault(),
        label = "buttonEnabled",
    )

    Box(
        modifier = modifier
            .pressScale(interaction)
            .heightIn(min = 52.dp)
            .graphicsLayer { alpha = enabledAlpha }
            .clip(PillShape)
            .background(background, PillShape)
            .then(
                if (tone == ButtonTone.Ghost) {
                    Modifier.border(1.dp, scheme.outline, PillShape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.select()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(19.dp))
            }
            // Etiket değişince (ör. "Testi başlat" → "Test sürüyor…") yazı
            // çaprazlama geçer; düğme aynı düğme olarak kalır.
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (fadeIn(Motion.effectsDefault()) + scaleIn(initialScale = 0.92f)) togetherWith
                        (fadeOut(Motion.effectsFast()) + scaleOut(targetScale = 1.06f))
                },
                contentAlignment = Alignment.Center,
                label = "buttonLabel",
            ) { current ->
                Text(
                    text = current,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Segment seçeneği. */
data class Segment<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * Segment denetimi (Otomatik/Manuel, Tümü/Seçili/Hariç…).
 *
 * Seçim göstergesi anında yer değiştirmez; yay fiziğiyle KAYAR. Kullanıcı gözüyle
 * hareketi takip ettiği için "hangi sekmedeydim" sorusu hiç oluşmaz.
 */
@Composable
fun <T> SegmentedControl(
    options: List<Segment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val stateColors = LocalStateColors.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current

    var widthPx by remember { mutableStateOf(0) }
    val index = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val itemWidthPx = if (options.isEmpty()) 0f else widthPx.toFloat() / options.size

    val indicatorX = animateFloatAsState(
        targetValue = itemWidthPx * index,
        animationSpec = Motion.indicator(),
        label = "segmentIndicator",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .onSizeChanged { widthPx = it.width }
            .glass(
                shape = PillShape,
                level = GlassLevel.Inset,
                tintColor = scheme.surfaceContainer,
            ),
    ) {
        if (widthPx > 0) {
            Box(
                modifier = Modifier
                    .width(with(density) { itemWidthPx.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = indicatorX.value
                        // Gösterge hedefe koşarken yatayda esner: hangi yöne
                        // gittiği, nereye varacağı hareketin kendisinden okunur.
                        val stretch = min(
                            abs(itemWidthPx * index - indicatorX.value) / itemWidthPx.coerceAtLeast(1f),
                            1f,
                        )
                        scaleX = 1f + 0.10f * stretch
                        scaleY = 1f - 0.08f * stretch
                    }
                    .padding(4.dp)
                    .clip(PillShape)
                    .background(stateColors.brandGradient(), PillShape),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { option ->
                val isSelected = option.value == selected
                val color by animateColorAsState(
                    targetValue = if (isSelected) Color.White else scheme.onSurfaceVariant,
                    animationSpec = Motion.effectsDefault(),
                    label = "segmentLabel",
                )
                val emphasis = animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = Motion.spatialDefault(),
                    label = "segmentEmphasis",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(PillShape)
                        .clickable {
                            if (!isSelected) {
                                haptics.select()
                                onSelect(option.value)
                            }
                        }
                        .graphicsLayer {
                            // Seçili seçenek bir tık büyür: gösterge kaymadan önce
                            // bile hangi seçeneğin aktif olduğu belli olur.
                            val s = 1f + 0.05f * emphasis.value
                            scaleX = s
                            scaleY = s
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (option.icon != null) {
                        Icon(option.icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Durum rozeti: küçük renkli etiket (BAĞLI, OTO, P2 …). Hem rengi hem metni
 * canlı veriden gelir, o yüzden ikisi de animasyonlu değişir — rozet "yerinde
 * güncellenir", kaybolup yeniden belirmez.
 */
@Composable
fun TagBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    filled: Boolean = false,
) {
    val animated by animateColorAsState(color, Motion.effectsDefault(), label = "badgeColor")
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(if (filled) animated else animated.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(Motion.effectsDefault()) + scaleIn(initialScale = 0.86f)) togetherWith
                    (fadeOut(Motion.effectsFast()) + scaleOut(targetScale = 1.1f))
            },
            contentAlignment = Alignment.Center,
            label = "badgeText",
        ) { current ->
            Text(
                text = current,
                style = MaterialTheme.typography.labelSmall,
                color = if (filled) Color.White else animated,
                maxLines = 1,
            )
        }
    }
}

/**
 * Seçim işareti. Kutu dolarken işaret hafifçe dönerek yerine oturur: "tık"
 * hissini veren şey, iki hareketin (dolma + dönme) aynı anda bitmesidir.
 */
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fill = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.bouncy(),
        label = "checkFill",
    )
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primary else Color.Transparent,
        animationSpec = Motion.effectsDefault(),
        label = "checkContainer",
    )
    val ring by animateColorAsState(
        targetValue = if (selected) Color.Transparent else scheme.outline,
        animationSpec = Motion.effectsDefault(),
        label = "checkRing",
    )
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(container)
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    val f = fill.value
                    alpha = f
                    scaleX = f
                    scaleY = f
                    rotationZ = (1f - f) * -45f
                },
        )
    }
}

/**
 * Metin alanı. Material'ın hazır alanı yerine elle çizilir: odaklanınca kenarlık
 * aksan rengine yay ile geçer, yükseklik ve köşe yarıçapı kart diliyle aynıdır.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) scheme.primary else scheme.onSurface.copy(alpha = 0.14f),
        animationSpec = Motion.effectsDefault(),
        label = "fieldBorder",
    )
    // Odaklanınca kenarlık hem renk değiştirir hem KALINLAŞIR: iki sinyal
    // birlikte, tek başına renge göre çok daha okunur bir odak göstergesidir.
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 1.8.dp else 1.dp,
        animationSpec = Motion.spatialFast(),
        label = "fieldBorderWidth",
    )

    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = scheme.onSurface,
        fontFamily = if (monospace) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily,
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = textStyle,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = keyboardOptions,
        interactionSource = interaction,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .glass(
                        shape = MaterialTheme.shapes.medium,
                        level = GlassLevel.Inset,
                        tintColor = scheme.surfaceContainerHigh,
                        borderColor = borderColor,
                        borderWidth = borderWidth,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(11.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
        },
    )
}

/**
 * Seçim diyaloğu (ISS, DoH sağlayıcı…). Sistem menüsü yerine uygulamanın kendi
 * kart dilinde çizilir; uzun listelerde kaydırılır ve seçili satır işaretlenir.
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Diyalog AYRI bir pencerede çizilir; ana pencerenin arka plan katmanını
    // örnekleyemez. Bu yüzden burada cam yerine tam opak yüzey kullanılır —
    // sahte bir saydamlık, gerçek camın yanında ucuz durur.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.spatialDefault(),
        label = "dialogAppear",
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = appear
                    val s = 0.92f + 0.08f * appear
                    scaleX = s
                    scaleY = s
                    translationY = (1f - appear) * 18.dp.toPx()
                }
                .clip(MaterialTheme.shapes.extraLarge)
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outline, MaterialTheme.shapes.extraLarge)
                .padding(vertical = 20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    val rowInteraction = remember(value) { MutableInteractionSource() }
                    val labelColor by animateColorAsState(
                        targetValue = if (isSelected) scheme.primary else scheme.onSurface,
                        animationSpec = Motion.effectsDefault(),
                        label = "dialogRow",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(rowInteraction, pressedScale = 0.98f)
                            .clickable(interactionSource = rowInteraction, indication = null) {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = labelColor,
                            modifier = Modifier.weight(1f),
                        )
                        AnimatedVisibility(
                            visible = isSelected,
                            enter = scaleIn(animationSpec = Motion.bouncy()) + fadeIn(Motion.effectsFast()),
                            exit = scaleOut(animationSpec = Motion.spatialFast()) + fadeOut(Motion.effectsFast()),
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
