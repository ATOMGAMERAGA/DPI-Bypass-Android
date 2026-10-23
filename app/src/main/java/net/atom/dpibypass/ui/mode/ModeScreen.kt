package net.atom.dpibypass.ui.mode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.atom.dpibypass.data.OperationMode
import net.atom.dpibypass.isp.Isp
import net.atom.dpibypass.strategy.StrategyPool
import net.atom.dpibypass.strategy.StrategyTestResult
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScreen
import net.atom.dpibypass.ui.design.AppTextField
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.CardTone
import net.atom.dpibypass.ui.design.ChoiceDialog
import net.atom.dpibypass.ui.design.ListRow
import net.atom.dpibypass.ui.design.RowDivider
import net.atom.dpibypass.ui.design.SectionHeader
import net.atom.dpibypass.ui.design.Segment
import net.atom.dpibypass.ui.design.SegmentedControl
import net.atom.dpibypass.ui.design.SelectionCheck
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace
import net.atom.dpibypass.ui.theme.LocalStateColors
import net.atom.dpibypass.ui.theme.Motion
import net.atom.dpibypass.ui.theme.NumericSmall
import net.atom.dpibypass.ui.theme.PillShape

// ---------------------------------------------------------------------------
// Strateji ekranı.
//
// Buradaki asıl tasarım sorunu şuydu: "strateji" kullanıcı için soyut bir kavram.
// Çözüm, seçimi görünür kanıta bağlamak — canlı test sonuçları artık ekranın
// birinci sınıf içeriği: her preset için ✓/✗, ölçülen gecikme ve diğerlerine
// göre uzunluğu değişen bir çubuk. Kullanıcı "hangisi daha iyi" sorusunu
// okumadan, bakarak yanıtlıyor.
// ---------------------------------------------------------------------------

@Composable
fun ModeScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val results by viewModel.testResults.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val winner by viewModel.testWinner.collectAsStateWithLifecycle()
    val canTest by viewModel.canRunTest.collectAsStateWithLifecycle()

    var ispPickerOpen by remember { mutableStateOf(false) }
    val auto = settings.operationMode == OperationMode.Auto

    AppScreen(
        title = "Strateji",
        subtitle = "Sağlayıcınızın DPI'ını aşan yöntemi otomatik bulun ya da kendiniz seçin.",
    ) {
        SegmentedControl(
            options = listOf(
                Segment(OperationMode.Auto, "Otomatik", Icons.Rounded.AutoAwesome),
                Segment(OperationMode.Manual, "Manuel", Icons.Rounded.Terminal),
            ),
            selected = settings.operationMode,
            onSelect = viewModel::setOperationMode,
        )

        VSpace(16.dp)

        AnimatedVisibility(
            visible = auto,
            enter = fadeIn(animationSpec = Motion.effectsDefault()) + expandVertically(),
            exit = fadeOut(animationSpec = Motion.effectsFast()) + shrinkVertically(),
        ) {
            AppCard(tone = CardTone.Accent, modifier = Modifier.fillMaxWidth()) {
                ListRow(
                    title = "Otomatik seçim açık",
                    subtitle = "Bu ağda en son kazanan strateji hatırlanır: bağlanırken önce O denenir, " +
                        "çalışıyorsa bağlantı saniyesinde kurulur. Çalışmıyorsa (ya da profil eskiyse) " +
                        "havuzun tamamı ölçülür ve çalışanlar arasından EN DÜŞÜK gecikmeli olan seçilir.",
                    icon = Icons.Rounded.AutoAwesome,
                )
            }
        }

        AnimatedVisibility(
            visible = !auto,
            enter = fadeIn(animationSpec = Motion.effectsDefault()) + expandVertically(),
            exit = fadeOut(animationSpec = Motion.effectsFast()) + shrinkVertically(),
        ) {
            Column {
                SectionHeader("Servis sağlayıcı")
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    ListRow(
                        title = "Sağlayıcı",
                        subtitle = settings.selectedIsp.displayName,
                        icon = Icons.Rounded.Router,
                        onClick = { ispPickerOpen = true },
                        trailing = {
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                SectionHeader("Hazır stratejiler")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StrategyPool.all.forEach { strategy ->
                        val selected = settings.selectedStrategyId == strategy.id && !settings.advancedEnabled
                        PresetCard(
                            id = strategy.id,
                            name = strategy.name,
                            note = strategy.note,
                            selected = selected,
                            onClick = { viewModel.setSelectedStrategy(strategy.id) },
                        )
                    }
                }

                SectionHeader("Gelişmiş")
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    ListRow(
                        title = "Serbest ByeDPI argümanı",
                        subtitle = "Açıkken preset yerine aşağıdaki argümanlar kullanılır.",
                        icon = Icons.Rounded.Terminal,
                        trailing = {
                            SelectionCheck(selected = settings.advancedEnabled)
                        },
                        onClick = { viewModel.setAdvancedEnabled(!settings.advancedEnabled) },
                    )
                    AnimatedVisibility(
                        visible = settings.advancedEnabled,
                        enter = expandVertically(Motion.spatialDefault()) + fadeIn(Motion.effectsDefault()),
                        exit = shrinkVertically(Motion.spatialFast()) + fadeOut(Motion.effectsFast()),
                    ) {
                        Column {
                            RowDivider()
                            Box(Modifier.padding(16.dp)) {
                                AppTextField(
                                    value = settings.advancedArgs,
                                    onValueChange = viewModel::setAdvancedArgs,
                                    placeholder = "--split 2 --disorder 3 --fake -1 --ttl 5",
                                    singleLine = false,
                                    monospace = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionHeader(
            title = "Canlı test",
            trailing = {
                if (testing) TagBadge("SÜRÜYOR", color = LocalStateColors.current.busy)
            },
        )
        LiveTestCard(
            testing = testing,
            canTest = canTest,
            results = results,
            winner = winner,
            onStart = viewModel::runStrategyTest,
        )
    }

    if (ispPickerOpen) {
        ChoiceDialog(
            title = "Servis sağlayıcı",
            options = Isp.entries.map { it to it.displayName },
            selected = settings.selectedIsp,
            onSelect = viewModel::setSelectedIsp,
            onDismiss = { ispPickerOpen = false },
        )
    }
}

@Composable
private fun PresetCard(
    id: String,
    name: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val stateColors = LocalStateColors.current
    // Seçim tek bir yayla sürülür: rozet büyür, gradyan belirir, yazı beyazlar.
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.spatialDefault(),
        label = "presetSelection",
    )
    val badgeText by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.effectsDefault(),
        label = "presetBadgeText",
    )
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (selected) CardTone.Accent else CardTone.Plain,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        val s = 1f + 0.08f * selection.value
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Gradyan üstte, opaklığı seçimle akıyor: renk "sıçramaz".
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = selection.value }
                        .background(stateColors.brandGradient(), CircleShape),
                )
                Text(
                    text = id,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = badgeText,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            SelectionCheck(selected = selected)
        }
    }
}

@Composable
private fun LiveTestCard(
    testing: Boolean,
    canTest: Boolean,
    results: List<StrategyTestResult>,
    winner: StrategyTestResult?,
    onStart: () -> Unit,
) {
    val stateColors = LocalStateColors.current
    val done = results.count { it.state != StrategyTestResult.State.Pending && it.state != StrategyTestResult.State.Testing }
    val total = results.size.coerceAtLeast(1)
    val progress by animateFloatAsState(
        targetValue = if (results.isEmpty()) 0f else done.toFloat() / total,
        animationSpec = Motion.spatialDefault(),
        label = "testProgress",
    )
    // Çubuk uzunlukları en yavaş başarılı sonuca göre ölçeklenir: kısa çubuk =
    // hızlı strateji. Sayıyı okumadan karşılaştırma yapılabilir.
    val slowest = results.mapNotNull { it.latencyMs }.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Stratejileri şimdi dene",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            VSpace(4.dp)
            Text(
                // Tünel açıkken test yapılamamasının teknik sebebi (tek native
                // motor örneği) kullanıcıya SONUÇ olarak anlatılır.
                text = if (!canTest) {
                    "Test, tünel kapalıyken yapılır: ölçüm motoru ile tünel motoru aynıdır, " +
                        "ikisi aynı anda çalışamaz. Önce bağlantıyı kesin."
                } else {
                    "VPN kurmadan, DoH ile çözüp gerçek TLS el sıkışması yaparak ölçer. " +
                        "İnternetiniz bu sırada kesilmez."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(14.dp)
            AppButton(
                text = when {
                    testing -> "Test sürüyor…"
                    !canTest -> "Tünel açıkken test edilemez"
                    else -> "Testi başlat"
                },
                onClick = { if (!testing && canTest) onStart() },
                tone = if (testing) ButtonTone.Tonal else ButtonTone.Primary,
                icon = Icons.Rounded.Science,
                enabled = !testing && canTest,
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(
                visible = results.isNotEmpty(),
                enter = expandVertically(Motion.spatialDefault()) + fadeIn(Motion.effectsDefault()),
                exit = shrinkVertically(Motion.spatialFast()) + fadeOut(Motion.effectsFast()),
            ) {
                Column {
                    VSpace(16.dp)
                    // İlerleme çubuğu. Test sürerken çubuğun ucunda yumuşak bir
                    // nabız atar: sayı değişmese bile "çalışıyor" bilgisi akar.
                    val pulse = rememberInfiniteTransition(label = "testPulse")
                        .animateFloat(
                            initialValue = 0.55f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(900, easing = LinearEasing),
                                RepeatMode.Reverse,
                            ),
                            label = "testPulseAlpha",
                        )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(PillShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .graphicsLayer { alpha = if (testing) pulse.value else 1f }
                                .clip(PillShape)
                                .background(stateColors.brandGradient()),
                        )
                    }
                    VSpace(12.dp)
                    results.forEach { result ->
                        TestResultRow(result = result, slowest = slowest)
                    }
                }
            }

            AnimatedVisibility(
                visible = winner != null,
                enter = expandVertically(Motion.spatialDefault()) + fadeIn(Motion.effectsDefault()) +
                    scaleIn(initialScale = 0.94f, animationSpec = Motion.bouncy()),
                exit = shrinkVertically(Motion.spatialFast()) + fadeOut(Motion.effectsFast()),
            ) {
                Column {
                    VSpace(14.dp)
                    AppCard(tone = CardTone.Accent, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.EmojiEvents,
                                contentDescription = null,
                                tint = stateColors.connected,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Kazanan: ${winner?.strategy?.id} · ${winner?.strategy?.name}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${winner?.let { net.atom.dpibypass.strategy.StrategyScore.coverageLabel(it) } ?: ""} · en yavaş TLS el sıkışması ${winner?.latencyMs ?: "?"} ms" +
                                        (if (winner?.fullCoverage == false) " — tam kapsama değil." else "") + " Bu strateji kaydedildi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultRow(result: StrategyTestResult, slowest: Long) {
    val stateColors = LocalStateColors.current
    val scheme = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(
        targetValue = result.latencyMs?.let { (it.toFloat() / slowest).coerceIn(0.08f, 1f) } ?: 0f,
        animationSpec = Motion.spatialSlow(),
        label = "latencyBar",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = result.strategy.id,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            modifier = Modifier.width(34.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = result.strategy.name,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VSpace(5.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(PillShape)
                    .background(scheme.surfaceContainerHighest),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .clip(PillShape)
                            .background(stateColors.connected),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(62.dp), contentAlignment = Alignment.CenterEnd) {
            when (result.state) {
                StrategyTestResult.State.Testing -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = stateColors.busy,
                )

                StrategyTestResult.State.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${result.latencyMs ?: "?"} ms",
                        style = NumericSmall,
                        color = stateColors.connected,
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Başarılı",
                        tint = stateColors.connected,
                        modifier = Modifier.size(15.dp),
                    )
                }

                StrategyTestResult.State.Failed -> Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Başarısız",
                    tint = scheme.error,
                    modifier = Modifier.size(16.dp),
                )

                StrategyTestResult.State.Pending -> Text(
                    text = "—",
                    style = NumericSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
