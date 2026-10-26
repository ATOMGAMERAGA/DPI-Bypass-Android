package net.atom.dpibypass.ui.social

import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.atom.dpibypass.social.GuideStage
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.ContentMaxWidth

/** Sayfanın ikincil eylemi (geri/yardım/iptal dışında). */
data class GuideAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val tag: String = "")

/**
 * Rehber sayfası: üstte aşama, kısa başlık, en fazla iki cümle açıklama, içerik
 * ve altta TEK belirgin ana eylem. Ana eylem sistem çubuklarının ve klavyenin
 * üstünde durur; içerik kaydırılabilir olduğu için büyük yazıda da görünür kalır.
 */
@Composable
fun GuidePage(
    stage: GuideStage,
    title: String,
    body: String? = null,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    onHelp: () -> Unit,
    primary: GuideAction? = null,
    primaryTone: ButtonTone = ButtonTone.Primary,
    secondary: List<GuideAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .testTag("guide-page"),
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("guide-back")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri")
            } else Spacer(Modifier.width(48.dp))
            StageIndicator(stage, Modifier.weight(1f))
            IconButton(onClick = onHelp) { Icon(Icons.Rounded.HelpOutline, contentDescription = "Yardım") }
            IconButton(onClick = onClose, modifier = Modifier.testTag("guide-close")) { Icon(Icons.Rounded.Close, contentDescription = "Rehberden çık") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() }.testTag("guide-title"))
                if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
                Spacer(Modifier.size(8.dp))
            }
        }
        if (primary != null || secondary.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), tonalElevation = 2.dp) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        primary?.let {
                            AppButton(it.label, it.onClick, modifier = Modifier.fillMaxWidth().testTag(it.tag.ifBlank { "guide-primary" }),
                                tone = primaryTone, enabled = it.enabled, maxLines = 3)
                        }
                        secondary.forEach { a ->
                            TextButton(onClick = a.onClick, enabled = a.enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .then(if (a.tag.isNotBlank()) Modifier.testTag(a.tag) else Modifier)) {
                                Text(a.label, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hazırlık · Kontrol · Sonuç. Koşullu akışta yanlış "3/8" sayacı üretmez. */
@Composable
fun StageIndicator(stage: GuideStage, modifier: Modifier = Modifier) {
    val all = GuideStage.entries
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Aşama ${stage.ordinal + 1}/${all.size}: ${stage.label}" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        all.forEachIndexed { i, s ->
            val current = s == stage
            val done = s.ordinal < stage.ordinal
            Text(
                s.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    current -> MaterialTheme.colorScheme.primary
                    done -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            if (i < all.lastIndex) Text("  ·  ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Büyük, dikey seçenek satırı (en az 56 dp). [selected] null değilse radyo
 * düğmesi gibi okunur. Dar ekranda yan yana sıkışmaz.
 */
@Composable
fun OptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    tag: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    val isSelected = selected == true
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) scheme.primaryContainer else scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (isSelected) scheme.primary else scheme.outlineVariant),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .selectable(selected = isSelected, enabled = enabled, role = if (selected != null) Role.RadioButton else Role.Button, onClick = onClick)
            .then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) (if (isSelected) scheme.onPrimaryContainer else scheme.onSurface) else scheme.onSurfaceVariant)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            when (selected) {
                true -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = scheme.primary)
                false -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = scheme.onSurfaceVariant)
                null -> Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CheckOption(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, leading: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainerHigh, border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).selectable(checked, role = Role.Checkbox) { onChange(!checked) },
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}

/** Kısa bilgi/uyarı kutusu. */
@Composable
fun Note(text: String, error: Boolean = false, icon: ImageVector? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = if (error) scheme.errorContainer else scheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, tint = if (error) scheme.onErrorContainer else scheme.onSecondaryContainer); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) scheme.onErrorContainer else scheme.onSecondaryContainer)
        }
    }
}

/** Küçük durum etiketi ("İlk denemede çalıştı", "Senin bildirimin"). */
@Composable
fun Tag(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Teknik bilgi yalnız burada; temel akışta görünmez. */
@Composable
fun Details(lines: List<String>) {
    if (lines.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Ayrıntılar")
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (open) lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val iconCache = LruCache<String, ImageBitmap>(32)

/** Kurulu uygulamanın ikonu (ana iş parçacığı dışında çözülür); yoksa baş harf. */
@Composable
fun AppAvatar(label: String, packageName: String?) {
    val context = LocalContext.current
    val icon = remember(packageName) { mutableStateOf(packageName?.let { iconCache.get(it) }) }
    LaunchedEffect(packageName) {
        val pkg = packageName ?: return@LaunchedEffect
        if (icon.value == null) icon.value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()?.also { iconCache.put(pkg, it) }
        }
    }
    val bitmap = icon.value
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Belirsiz süre: sahte yüzde ya da bitiş süresi göstermez. */
@Composable
fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

