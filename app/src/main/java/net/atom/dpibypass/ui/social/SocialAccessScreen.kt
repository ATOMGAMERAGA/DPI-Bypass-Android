package net.atom.dpibypass.ui.social

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.social.ControlledWebProbe
import net.atom.dpibypass.social.Goal
import net.atom.dpibypass.social.Phase
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.SocialPlatform
import net.atom.dpibypass.social.SocialText
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.social.TestMode
import net.atom.dpibypass.social.TrialState
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
import net.atom.dpibypass.ui.design.SwitchRow
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace

// ---------------------------------------------------------------------------
// Sosyal Erişim Deneyleri — Alpha.
//
// Eskiden Ayarlar'ın en tepesinde TEK, çok uzun bir kart idi. Buzlu cam her karede
// kartın tamamını bulanıklaştırdığı için o kart ekranı kasıyordu; WebView test
// penceresi de ayarlar listesinin içinde dönüyordu. Artık kendi sayfası var ve
// içerik ekran boyundan kısa, ayrı kartlara bölündü: Durum · Hedef · Seçenekler ·
// Başlat · Deneme · Test penceresi · Tanılama. Ayarlar'da yalnızca özet satırı
// ([SocialAccessEntry]) kalır.
// ---------------------------------------------------------------------------

private val StartablePhases = setOf(Phase.DISABLED, Phase.IDLE, Phase.FINISHED, Phase.PAUSED)
private val BusyPhases = setOf(Phase.BASELINING, Phase.SELECTING, Phase.PREPARING, Phase.APPLYING,
    Phase.PROBING, Phase.CANCELLING, Phase.ROLLING_BACK)
private val Problems = listOf("NETWORK_UNREACHABLE", "POSSIBLE_THROTTLING", "MEDIA_PATH_FAILURE",
    "AGE_OR_ID_VERIFICATION_REQUIRED", "BOT_CHALLENGE", "UNKNOWN")

/** Ayarlar'daki tek satırlık giriş: durum özeti + sayfaya geçiş. */
@Composable
fun SocialAccessEntry(viewModel: AppViewModel, onOpen: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    SectionHeader("Sosyal erişim")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = "Sosyal Erişim Deneyleri",
            subtitle = entrySummary(settings.socialAccessEnabled, state),
            icon = Icons.Rounded.Public,
            onClick = onOpen,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagBadge("Alpha")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

private fun entrySummary(enabled: Boolean, state: TrialState): String = when {
    !enabled -> "Kapalı. Açılmayan YouTube, Instagram, X, TikTok, Discord, Reddit ve Facebook içerikleri için uygun yöntemleri sırayla dener."
    state.phase == Phase.RECOVERY_REQUIRED -> "Kurtarma gerekli — sayfayı açıp geri almayı yeniden deneyin."
    state.phase in BusyPhases || state.phase == Phase.WAITING_FOR_USER || state.phase == Phase.AUTO_EVALUATING ->
        SocialText.phase(state.phase) + (state.candidate?.let { " · ${it.displayName}" } ?: "")
    state.finishReason != null && state.sessionId.isNotBlank() -> finishLabel(state.finishReason)
    else -> "Açık · yeni deneme başlatmaya hazır."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialAccessScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenHome: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var platformId by rememberSaveable { mutableStateOf(SocialPlatform.YOUTUBE.id) }
    var url by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf("UNKNOWN") }
    var publicGoal by rememberSaveable { mutableStateOf(false) }
    var officialWeb by rememberSaveable { mutableStateOf(false) }
    var controlled by rememberSaveable { mutableStateOf(false) }
    var playMedia by rememberSaveable { mutableStateOf(false) }
    var baseline by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var allowMetered by rememberSaveable { mutableStateOf(false) }
    var plannedMethods by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var problemPicker by remember { mutableStateOf(false) }
    var diagnosticPreview by remember { mutableStateOf<String?>(null) }

    val platform = SocialPlatform.byId(platformId) ?: SocialPlatform.YOUTUBE
    val target = remember(platformId, url) { if (url.isBlank()) Target.home(platformId) else Target.parse(platformId, url) }
    // Otomatik gözlem yalnızca gözlemcisi olan platformda ve belirli bir içerikte mümkün.
    val autoPossible = platform.autoObserver && target != null && target.contentId != Target.HOME_CONTENT
    val mode = if (autoPossible) settings.socialAccessMode else TestMode.MANUAL
    val needsMedia = playMedia || problem == "MEDIA_PATH_FAILURE"

    val saveDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
        if (destination != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination)?.use {
                        it.write(SocialAccessRuntime.adapter.diagnosticPreview().toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            if (ok) info = "Tanılama dosyası kaydedildi." else error = "Tanılama dosyası kaydedilemedi."
        }
    }

    AppScreen(
        title = "Sosyal Erişim",
        subtitle = "Açılmayan bir içerik için uygun yöntemleri sırayla dener. Alpha — her platformda çalışmayabilir.",
        actions = {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Kapat") }
        },
    ) {
        // ---- Durum ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Deneyleri etkinleştir",
                subtitle = "Her denemeden sonra sonucu sen bildirirsin; işe yarayan yöntem korunur, diğerleri geri alınır.",
                icon = Icons.Rounded.Public,
                checked = settings.socialAccessEnabled,
                onCheckedChange = viewModel::setSocialAccessEnabled,
            )
            if (settings.socialAccessEnabled) {
                RowDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentedControl(
                        options = listOf(Segment(TestMode.MANUAL, "Manuel"), Segment(TestMode.AUTO_BETA, "Otomatik (Beta)")),
                        selected = settings.socialAccessMode,
                        onSelect = viewModel::setSocialAccessMode,
                    )
                    Text(
                        if (settings.socialAccessMode == TestMode.AUTO_BETA && !autoPossible)
                            "${platform.displayName} için otomatik içerik gözlemi yok (ya da belirli bir bağlantı girilmedi); bu tur Manuel çalışır."
                        else if (settings.socialAccessMode == TestMode.AUTO_BETA)
                            "Herkese açık içerik ayrı bir test penceresinde gözlenir; native uygulamadaki hesabın sonucu değildir."
                        else "Her yöntemden sonra uygulamayı/sayfayı açıp sonucu bildirirsin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connection != ConnectionState.Connected) {
                    RowDivider()
                    ListRow(
                        title = "Yerel DPI bağlantısı kapalı",
                        subtitle = "DPI profillerini denemek için önce bağlanın. Bağlantı olmadan yalnızca resmî web yolu denenir.",
                        icon = Icons.Rounded.ErrorOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = onOpenHome,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }

        if (!settings.socialAccessEnabled) {
            HowItWorks()
            return@AppScreen
        }

        // ---- Hedef ----
        SectionHeader("Hedef")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SocialPlatform.entries.forEach { p ->
                        FilterChip(
                            selected = p == platform,
                            onClick = { if (p != platform) { platformId = p.id; url = "" } },
                            label = { Text(p.displayName) },
                        )
                    }
                }
                AppTextField(
                    value = url,
                    onValueChange = { value ->
                        url = value
                        Target.detectPlatform(value)?.let { platformId = it.id }
                    },
                    placeholder = "${platform.displayName} bağlantısı (isteğe bağlı)",
                    trailing = {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { text ->
                                url = Target.extractUrl(text)
                                Target.detectPlatform(url)?.let { platformId = it.id }
                            }
                        }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Yapıştır") }
                    },
                )
                Text(
                    when {
                        url.isBlank() -> "Boş bırakırsan ${platform.displayName} ana sayfası test edilir. Paylaş → Bağlantıyı kopyala ile belirli bir içerik de verebilirsin."
                        target == null -> "Bu bağlantı ${platform.displayName} içeriği olarak tanınmadı. Resmî bir HTTPS bağlantısı yapıştır."
                        else -> "Tanındı: ${target.url}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (url.isNotBlank() && target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            ListRow(
                title = "Sorun",
                subtitle = problemLabel(problem),
                icon = Icons.Rounded.HelpOutline,
                onClick = { problemPicker = true },
                trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }

        // ---- Seçenekler ----
        SectionHeader("Seçenekler")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow("Resmî tarayıcı yolunu da dene", officialWeb, { officialWeb = it },
                subtitle = "Bağlantı tarayıcıda/uygulamada açılır; sonucu kendin kontrol edersin.")
            if (platform.autoObserver) {
                RowDivider()
                SwitchRow("Herkese açık içerik yeterli", publicGoal, { publicGoal = it; if (!it) controlled = false },
                    subtitle = if (publicGoal) "Oturum açmadan görüntülemek yeterli; hesap akışı sayılmaz."
                    else "Kendi hesabımla açmak istiyorum; aynı hesapla devam ettiğimi kendim doğrularım.")
                if (publicGoal) {
                    RowDivider()
                    SwitchRow("Test penceresinde dene", controlled, { controlled = it },
                        subtitle = "Ayrı, oturumsuz bir WebView açılır. En fazla 6 sayfa yüklemesi; her gözlem 20 sn.")
                }
            }
            RowDivider()
            SwitchRow("Video oynatmayı da sına", needsMedia, { playMedia = it || problem == "MEDIA_PATH_FAILURE" },
                subtitle = "Yalnızca açılması değil, videonun gerçekten ilerlemesi gerekir.")
            if (mode == TestMode.AUTO_BETA) {
                RowDivider()
                SwitchRow("Ölçülü ağda ek veri kullan", allowMetered, { allowMetered = it },
                    subtitle = "Mobil veride test penceresi yalnız bu izinle açılır; veri miktarı tahminidir.")
            }
        }

        // ---- Başlat ----
        SectionHeader("Başlat")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sorun şu anda hâlâ var mı?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(baseline == true, { baseline = true }, label = { Text("Var") })
                    FilterChip(baseline == false, { baseline = false }, label = { Text("Yok") })
                    FilterChip(baseline == null, { baseline = null }, label = { Text("Emin değilim") })
                }
                AppButton(
                    text = if (state.sessionId.isNotBlank() && state.phase != Phase.FINISHED) "Yeniden başlat" else "Başlat",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.phase in StartablePhases && target != null,
                    onClick = {
                        val t = target ?: run { error = "Önce geçerli bir bağlantı gir ya da alanı boş bırak."; return@AppButton }
                        error = ""; info = ""
                        val anonymous = publicGoal && platform.autoObserver
                        val goal = Goal(
                            platformId = platform.id,
                            problemClass = problem,
                            identityContext = if (anonymous) "ANONYMOUS" else "SAME_ACCOUNT",
                            allowedSurfaces = buildSet {
                                add("OFFICIAL_NATIVE_APP")
                                if (officialWeb) add("OFFICIAL_WEB")
                                if (controlled && anonymous) add("CONTROLLED_WEB_VIEW")
                            },
                            requiredCapabilities = if (needsMedia) setOf("PLAY_MEDIA") else setOf("VIEW_SELECTED_CONTENT"),
                            publicFallbackAllowed = anonymous,
                        )
                        val catalog = SocialAccessRuntime.catalog(goal, viewModel.socialIspFamily())
                        plannedMethods = catalog.size
                        SocialAccessRuntime.adapter.target = t
                        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
                        SocialAccessRuntime.launch { start(goal, t, mode, baseline, metered, allowMetered, catalog) }
                    },
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- Deneme ----
        if (state.sessionId.isNotBlank() || state.phase == Phase.RECOVERY_REQUIRED) {
            SectionHeader("Deneme")
            TrialCard(
                state = state,
                plannedMethods = plannedMethods,
                onOpenTarget = { state.target?.let { openUrl(context, it.url) } },
                onKeep = { id -> viewModel.keepSocialStrategy(id); info = "Kaydedildi: bu ağdaki sonraki bağlantılar bu profille açılır." },
                onManual = { viewModel.setSocialAccessMode(TestMode.MANUAL) },
            )
        }

        // ---- Test penceresi ----
        if (state.candidate?.surface == "CONTROLLED_WEB_VIEW" && state.resourceDisposition in setOf("TRIAL_ACTIVE", "KEPT_ACTIVE")) {
            SectionHeader("Test penceresi")
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                ControlledWebProbe(state, Modifier.fillMaxWidth().height(360.dp))
            }
        }

        // ---- Tanılama ----
        SectionHeader("Tanılama")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                title = "Tanılama raporunu önizle",
                subtitle = "Yalnız bu cihazda tutulur; bağlantı, hesap veya ağ kimliği içermez.",
                icon = Icons.Rounded.Description,
                onClick = {
                    scope.launch {
                        diagnosticPreview = withContext(Dispatchers.IO) { SocialAccessRuntime.adapter.diagnosticPreview() }
                    }
                },
            )
            RowDivider()
            ListRow(
                title = "Geçmişi sil",
                subtitle = "Etkin kurtarma ve bekleme bilgisi korunur.",
                icon = Icons.Rounded.DeleteOutline,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { SocialAccessRuntime.adapter.clearHistory() }
                        error = ""; info = "Tanı geçmişi silindi."
                    }
                },
            )
        }
        VSpace(8.dp)
    }

    if (problemPicker) {
        ChoiceDialog(
            title = "Sorun ne?",
            options = Problems.map { it to problemLabel(it) },
            selected = problem,
            onSelect = { problem = it; problemPicker = false },
            onDismiss = { problemPicker = false },
        )
    }
    diagnosticPreview?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnosticPreview = null },
            title = { Text("Yerel tanılama önizlemesi") },
            text = {
                Text(
                    report.take(20_000),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { diagnosticPreview = null; saveDiagnostics.launch("social-alpha-diagnostics.json") }) { Text("Dosyaya kaydet") }
            },
            dismissButton = { TextButton(onClick = { diagnosticPreview = null }) { Text("Kapat") } },
        )
    }
}

@Composable
private fun TrialCard(
    state: TrialState,
    plannedMethods: Int,
    onOpenTarget: () -> Unit,
    onKeep: (String) -> Unit,
    onManual: () -> Unit,
) {
    val coordinator = SocialAccessRuntime.coordinator
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagBadge(
                    SocialText.phase(state.phase),
                    color = if (state.phase == Phase.RECOVERY_REQUIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    state.candidate?.displayName ?: state.target?.let { SocialPlatform.byId(it.platformId)?.displayName } ?: "Mevcut durum",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.phase in BusyPhases) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (plannedMethods > 0) {
                LinearProgressIndicator(
                    progress = { (state.attempted.toFloat() / plannedMethods).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
            SocialText.pause(state.pauseReason)?.takeIf { state.phase == Phase.PAUSED }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                buildString {
                    append("Yöntem ${state.attempted}")
                    if (plannedMethods > 0) append("/$plannedMethods")
                    append(" · Bağlantı: ${SocialText.technical(state.technicalOutcome)}")
                    if (state.mode == TestMode.AUTO_BETA) append(" · İçerik: ${SocialText.verdict(state.autoVerdict)}")
                    append(" · Test ${state.requests}/24")
                    if (state.rendererLoads > 0) append(" · Sayfa ${state.rendererLoads}/6")
                    if (state.deferred.isNotEmpty()) append(" · Ertelenen ${state.deferred.size}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.finishReason?.let { Text(finishLabel(it), style = MaterialTheme.typography.labelLarge) }

            if (state.phase == Phase.WAITING_FOR_USER) {
                HorizontalDivider()
                Text("Şimdi uygulamayı ya da sayfayı açıp kontrol et. İçerik açıldı mı?", style = MaterialTheme.typography.labelLarge)
                AppButton("Hedefi aç", onOpenTarget, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Açıldı", { SocialAccessRuntime.launch { feedback(state.feedback("RESOLVED")) } }, modifier = Modifier.weight(1f))
                    AppButton("Hâlâ açılmıyor", { SocialAccessRuntime.launch { feedback(state.feedback("STILL_PRESENT")) } },
                        modifier = Modifier.weight(1f), tone = ButtonTone.Tonal)
                }
                Row {
                    TextButton(onClick = { SocialAccessRuntime.launch { feedback(state.feedback("PARTIAL")) } }) { Text("Kısmen çalışıyor") }
                    TextButton(onClick = { SocialAccessRuntime.launch { feedback(state.feedback("NOT_TESTED")) } }) { Text("Test edemedim") }
                }
                if (state.userOutcome == "PARTIAL")
                    TextButton(onClick = { SocialAccessRuntime.launch { feedback(state.feedback("PARTIAL_ACCEPTED")) } }) { Text("Böyle kullan") }
            }
            if (state.phase in setOf(Phase.AUTO_EVALUATING, Phase.PROBING) || state.phase == Phase.PAUSED && state.pauseReason == "USER_ACTION_REQUIRED")
                AppButton("Açıldı, burada dur", { SocialAccessRuntime.launch { feedback(state.feedback("RESOLVED")) } }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal)
            val kept = state.candidate?.strategyId
            if (state.resourceDisposition == "KEPT_ACTIVE" && kept != null)
                AppButton("Bu ağ için varsayılan yap", { onKeep(kept) }, modifier = Modifier.fillMaxWidth())
            if (state.mode == TestMode.AUTO_BETA && (state.phase != Phase.FINISHED || state.deferred.isNotEmpty())) TextButton(onClick = onManual) { Text("Manuel devam et") }
            if (state.finishReason == "RESOLVED_AUTO_OBSERVED") TextButton(onClick = { SocialAccessRuntime.launch { correctAutoPass() } }) { Text("Bende hâlâ sorun var") }
            if (state.phase == Phase.PAUSED && state.pauseReason in setOf("BUDGET_LIMIT", "USER_ACTION_REQUIRED"))
                TextButton(onClick = { SocialAccessRuntime.launch { resume() } }) { Text(if (state.pauseReason == "BUDGET_LIMIT") "Kalanları dene" else "Yaptım, devam et") }
            if (state.phase == Phase.PAUSED && state.pauseReason == "PERMISSION_REQUIRED")
                TextButton(onClick = { SocialAccessRuntime.launch { resume() } }) { Text("Bağlandım, devam et") }
            if (state.phase == Phase.RECOVERY_REQUIRED)
                AppButton("Geri almayı yeniden dene", { SocialAccessRuntime.launch { retryRecovery() } }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Danger)
            else if (coordinator.active())
                AppButton("Durdur ve geri al", { SocialAccessRuntime.launch { stop() } }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Danger)
        }
    }
}

@Composable
private fun HowItWorks() {
    SectionHeader("Nasıl çalışır?")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "1. Platformu seç, istersen açılmayan içeriğin bağlantısını yapıştır.",
                "2. Uygulama mevcut tünelde yerel DPI profillerini (ISS'ne göre sıralı) tek tek dener.",
                "3. Her denemeden sonra içeriği açıp sonucu bildirirsin; işe yarayan profil korunur, diğerleri geri alınır.",
                "Trafik uzak bir sunucuya yönlendirilmez. Profil, tünelden geçen bütün seçili uygulamaları etkiler.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

private fun problemLabel(value: String) = when (value) {
    "NETWORK_UNREACHABLE" -> "Site/uygulama hiç açılmıyor"
    "POSSIBLE_THROTTLING" -> "Açılıyor ama çok yavaş"
    "MEDIA_PATH_FAILURE" -> "Sayfa açılıyor, video/fotoğraf yüklenmiyor"
    "AGE_OR_ID_VERIFICATION_REQUIRED" -> "Yaş veya kimlik doğrulaması isteniyor"
    "BOT_CHALLENGE" -> "Bot doğrulaması isteniyor"
    else -> "Emin değilim"
}

private fun finishLabel(value: String) = when (value) {
    "RESOLVED_USER_REPORTED" -> "Erişim bildirdin; çalışan yöntem korunuyor."
    "RESOLVED_AUTO_OBSERVED" -> "Test penceresinde çalıştı; kapsam yalnız test penceresi."
    "ALREADY_ACCESSIBLE" -> "Başlangıçta zaten açıktı; yöntem denenmedi."
    "AUTO_UNAVAILABLE" -> "Bu hedef için otomatik içerik gözlemi yok; Manuel devam edebilirsin."
    "AUTO_INCONCLUSIVE" -> "Otomatik sonuç belirsiz; ertelenen yöntemler Manuel denenebilir."
    "CANDIDATES_EXHAUSTED" -> "Bu turdaki uygun yöntemler bitti."
    "NO_APPLICABLE_METHODS" -> "Bu kapsamda uygulanabilir yöntem bulunmadı."
    "PARTIAL_ACCEPTED" -> "Kısmi erişimi kabul ettin."
    else -> "Deneme durduruldu."
}
