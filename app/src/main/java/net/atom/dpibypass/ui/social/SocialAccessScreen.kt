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
import androidx.compose.runtime.LaunchedEffect
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
import net.atom.dpibypass.social.Capability
import net.atom.dpibypass.social.Check
import net.atom.dpibypass.social.CheckMode
import net.atom.dpibypass.social.CheckStatus
import net.atom.dpibypass.social.ControlledWebProbe
import net.atom.dpibypass.social.TargetResult
import net.atom.dpibypass.social.TargetStatus
import net.atom.dpibypass.social.TargetStatusRules
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
    !enabled -> "Kapalı. Seçtiğin uygulamalar (YouTube, Instagram, X, TikTok, Discord, Reddit, Facebook, Telegram, WhatsApp, Signal, Twitch, Snapchat ya da özel alan adı) için birlikte çalışan ortak yerel DPI planını arar."
    state.phase == Phase.RECOVERY_REQUIRED -> "Kurtarma gerekli — sayfayı açıp geri almayı yeniden deneyin."
    state.phase in BusyPhases || state.phase == Phase.WAITING_FOR_USER || state.phase == Phase.AUTO_EVALUATING ->
        SocialText.phase(state.phase) + (state.candidate?.let { " · ${it.displayName}" } ?: "")
    state.finishReason != null && state.sessionId.isNotBlank() -> finishLabel(state.finishReason)
    state.plan.strategyId != null -> "Açık · plan ${state.plan.strategyId}" + if (state.plan.installed) " çalışıyor." else " (bu bağlantıda kurulu değil)."
    else -> "Açık · yeni deneme başlatmaya hazır."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialAccessScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenHome: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf("") }
    var urlRestored by rememberSaveable { mutableStateOf(false) }
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

    // Kaydedilmiş bağlantı yalnız ayarlar GERÇEKTEN okunduktan sonra bir kez geri yüklenir.
    LaunchedEffect(settings.loaded) {
        if (settings.loaded && !urlRestored) { if (url.isBlank()) url = settings.socialTargetUrl; urlRestored = true }
    }

    val selected = SocialPlatform.entries.filter { it.id in settings.socialPlatforms }
    val detected = remember(url) { if (url.isBlank()) null else Target.detectPlatform(url) }
    // Odak: bağlantının platformu, yoksa seçimdeki ilk platform.
    val focus = detected?.takeIf { it in selected } ?: selected.firstOrNull()
    val targets = remember(selected, url) {
        selected.mapNotNull { p ->
            when {
                p == SocialPlatform.CUSTOM -> Target.parse(p.id, url)
                p == focus && url.isNotBlank() && detected == p -> Target.parse(p.id, url)
                else -> Target.home(p.id)
            }
        }
    }
    val focusTarget = targets.firstOrNull { it.platformId == focus?.id }
    val urlInvalid = url.isNotBlank() && (focus == SocialPlatform.CUSTOM && focusTarget == null ||
        focus != SocialPlatform.CUSTOM && detected == null && SocialPlatform.CUSTOM !in selected)
    // Otomatik içerik gözlemi yalnızca gözlemcisi olan platformda ve belirli bir içerikte mümkün.
    val autoObserver = focus?.autoObserver == true && focusTarget != null && focusTarget.contentId != Target.HOME_CONTENT
    val mode = settings.socialAccessMode
    val needsMedia = playMedia || problem == "MEDIA_PATH_FAILURE"
    val engineConnected = connection == ConnectionState.Connected
    val installed = remember(selected) {
        selected.associate { p -> p.id to p.packages.filter { pkg -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess }.toSet() }
    }

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
        subtitle = "Seçtiğin uygulamaların birlikte çalıştığı ortak yerel DPI planını arar ve korur. Alpha — her platformda çalışmayabilir.",
        actions = {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Kapat") }
        },
    ) {
        // Ayarlar henüz okunmadıysa varsayılan "kapalı" değer gerçek tercih gibi gösterilmez.
        if (!settings.loaded) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ayarlar yükleniyor…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            return@AppScreen
        }
        if (settings.readError || saveError != null) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                ListRow(
                    title = if (settings.readError) "Ayarlar okunamadı" else "Kaydedilemedi",
                    subtitle = if (settings.readError) "Varsayılanlar gösteriliyor; yaptığın değişiklik kaydedilmeye çalışılacak." else saveError,
                    icon = Icons.Rounded.ErrorOutline,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = viewModel::clearSaveError,
                )
            }
        }

        // ---- Durum ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Sosyal Erişim",
                subtitle = "Açık tutma tercihin ve seçtiğin uygulamalar kalıcıdır. Kapatmak süren denemeyi geri alır; onaylı planı kaldırmaz.",
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
                        if (settings.socialAccessMode == TestMode.AUTO_BETA)
                            "Otomatik: DPI profilleri adayın gerçek yolundan (yerel motor) ağ düzeyinde ölçülür ve diğer seçili uygulamalarda gerileme olup olmadığına bakılır. " +
                                (if (autoObserver) "İçerik, ayrı test penceresinde (doğrudan yol) gözlenir." else "İçerik/uygulama sonucu için onayın istenir.")
                        else "Manuel: her yöntemden sonra uygulamayı/sayfayı açıp sonucu bildirirsin; diğer seçili uygulamalar yine otomatik ölçülür.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!engineConnected) {
                    RowDivider()
                    ListRow(
                        title = "Yerel DPI bağlantısı kapalı",
                        subtitle = "DPI profillerini denemek için önce bağlanın. Bağlantı olmadan yalnız doğrudan ağ yolu ve resmî web yolu denenir.",
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

        // ---- Uygulamalar (çoklu hedef) ----
        SectionHeader("Uygulamalar")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Birlikte çalışması gerekenleri seç. Bulunan plan hepsine birlikte uygulanır ve hepsinde ölçülür.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SocialPlatform.entries.forEach { p ->
                        FilterChip(
                            selected = p in selected,
                            onClick = { viewModel.setSocialPlatform(p.id, p !in selected) },
                            label = { Text(p.displayName) },
                        )
                    }
                }
                AppTextField(
                    value = url,
                    onValueChange = { value ->
                        url = value
                        viewModel.setSocialTargetUrl(value)
                        Target.detectPlatform(value)?.let { p -> if (p !in selected) viewModel.setSocialPlatform(p.id, true) }
                    },
                    placeholder = if (SocialPlatform.CUSTOM in selected) "Alan adı ya da içerik bağlantısı" else "Belirli bir içerik bağlantısı (isteğe bağlı)",
                    trailing = {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { text ->
                                url = Target.extractUrl(text)
                                viewModel.setSocialTargetUrl(url)
                                Target.detectPlatform(url)?.let { p -> if (p !in selected) viewModel.setSocialPlatform(p.id, true) }
                            }
                        }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Yapıştır") }
                    },
                )
                Text(
                    when {
                        selected.isEmpty() -> "En az bir uygulama seç."
                        urlInvalid -> "Bu bağlantı seçili bir platformun içeriği olarak tanınmadı. Resmî bir HTTPS bağlantısı yapıştır" +
                            " (katalogda olmayan servis için \"Özel alan adı\"nı seç)."
                        focusTarget != null && focusTarget.contentId != Target.HOME_CONTENT -> "Odak: ${focus?.displayName} · ${focusTarget.url}"
                        else -> "Bağlantı yoksa her uygulamanın ana adresi ölçülür."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (urlInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selected.forEach { p ->
                val target = targets.firstOrNull { it.platformId == p.id }
                val result = target?.let { state.results[it.key] }
                val packages = installed[p.id].orEmpty()
                val inScope = packages.isEmpty() || packages.any {
                    net.atom.dpibypass.vpn.AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, it)
                }
                RowDivider()
                PlatformRow(
                    platform = p,
                    target = target,
                    result = result,
                    status = TargetStatusRules.of(result, inScope, engineConnected, SocialAccessRuntime.networkUsable()),
                    voiceBlocked = settings.disableQuic,
                    scopeHint = if (inScope) null else scopeHint(settings.appFilterMode, settings.scopeBackupAvailable),
                    onBringIntoScope = { viewModel.bringIntoScope(packages) },
                    onReport = { outcome -> target?.let { t -> SocialAccessRuntime.launch { report(t.key, outcome) } } },
                    reportEnabled = !SocialAccessRuntime.coordinator.active(),
                )
            }
            if (selected.isNotEmpty()) {
                RowDivider()
                Column(Modifier.padding(16.dp)) {
                    AppButton(
                        text = "Seçilileri şimdi kontrol et",
                        modifier = Modifier.fillMaxWidth(),
                        tone = ButtonTone.Tonal,
                        enabled = targets.isNotEmpty() && state.phase !in BusyPhases && !SocialAccessRuntime.coordinator.active(),
                        onClick = { SocialAccessRuntime.launch { check(targets) } },
                    )
                }
            }
        }

        // ---- Plan ----
        state.plan.strategyId?.let { planId ->
            SectionHeader("Etkin plan")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                ListRow(
                    title = "Yerel DPI planı: $planId",
                    subtitle = if (state.plan.installed && engineConnected)
                        "Motorda çalışıyor. Ayar ve ağ değişikliklerinde korunur; sonuçlar kısaca yeniden doğrulanır."
                    else "Kayıtlı ama bu bağlantıda kurulu değil (bağlanırken doğrulanamadı ya da bağlantı kapalı). Yeni bir arama başlatabilirsin.",
                    icon = Icons.Rounded.Public,
                )
                if (state.plan.installed && engineConnected) {
                    RowDivider()
                    Column(Modifier.padding(16.dp)) {
                        AppButton("Planı kaldır", { SocialAccessRuntime.launch { clearPlan() } }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal)
                    }
                }
            }
        }

        // ---- Seçenekler ----
        SectionHeader("Seçenekler")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                title = "Sorun",
                subtitle = problemLabel(problem),
                icon = Icons.Rounded.HelpOutline,
                onClick = { problemPicker = true },
                trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            RowDivider()
            SwitchRow("Resmî tarayıcı yolunu da dene", officialWeb, { officialWeb = it },
                subtitle = "Bağlantı tarayıcıda/uygulamada açılır; sonucu kendin kontrol edersin.")
            if (focus?.autoObserver == true) {
                RowDivider()
                SwitchRow("Herkese açık içerik yeterli", publicGoal, { publicGoal = it; if (!it) controlled = false },
                    subtitle = if (publicGoal) "Oturum açmadan görüntülemek yeterli; hesap akışı sayılmaz."
                    else "Kendi hesabımla açmak istiyorum; aynı hesapla devam ettiğimi kendim doğrularım.")
                if (publicGoal) {
                    RowDivider()
                    SwitchRow("Test penceresinde dene", controlled, { controlled = it },
                        subtitle = "Ayrı, oturumsuz bir WebView açılır (doğrudan yol; DPI profilinin etkisini ölçmez). En fazla 6 sayfa yüklemesi; her gözlem 20 sn.")
                }
            }
            RowDivider()
            SwitchRow("Video oynatmayı da sına", needsMedia, { playMedia = it || problem == "MEDIA_PATH_FAILURE" },
                subtitle = "Yalnızca açılması değil, videonun gerçekten ilerlemesi gerekir.")
            if (mode == TestMode.AUTO_BETA && autoObserver) {
                RowDivider()
                SwitchRow("Ölçülü ağda ek veri kullan", allowMetered, { allowMetered = it },
                    subtitle = "Mobil veride test penceresi yalnız bu izinle açılır; veri miktarı tahminidir.")
            }
        }

        // ---- Başlat ----
        SectionHeader("Başlat")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${focus?.displayName ?: "Odak uygulama"} için sorun şu anda hâlâ var mı?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(baseline == true, { baseline = true }, label = { Text("Var") })
                    FilterChip(baseline == false, { baseline = false }, label = { Text("Yok") })
                    FilterChip(baseline == null, { baseline = null }, label = { Text("Emin değilim") })
                }
                AppButton(
                    text = if (state.sessionId.isNotBlank() && state.phase != Phase.FINISHED) "Yeniden başlat"
                        else if (targets.size > 1) "Ortak yöntem ara (${targets.size} uygulama)" else "Başlat",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.phase in StartablePhases && focusTarget != null && !urlInvalid,
                    onClick = {
                        val t = focusTarget ?: run { error = "Önce bir uygulama seç ya da geçerli bir bağlantı gir."; return@AppButton }
                        error = ""; info = ""
                        val p = focus ?: return@AppButton
                        val anonymous = publicGoal && p.autoObserver
                        val goal = Goal(
                            platformId = p.id,
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
                        val others = targets.filter { it.key != t.key }
                        SocialAccessRuntime.launch { start(goal, t, mode, baseline, metered, allowMetered, catalog, others) }
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
                onKeep = { id -> viewModel.keepSocialStrategy(id) { ok -> if (ok) { error = ""; info = "Kaydedildi: manuel stratejin de $id oldu." } } },
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
                subtitle = "Yalnız bu cihazda tutulur; bağlantı, hesap veya ağ kimliği içermez. Sürümler, plan, hedef başına sonuç ve hata sınıfları.",
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

/** Kapsam dışı uyarısı: neden dışarıda olduğu açıkça yazılır. */
private fun scopeHint(mode: net.atom.dpibypass.data.AppFilterMode, discordBackup: Boolean) = when (mode) {
    net.atom.dpibypass.data.AppFilterMode.Include -> "Uygulamalar \"Yalnızca seçili\" kipinde ve bu uygulama listede değil" +
        (if (discordBackup) " (kurulumdaki \"Sadece Discord\" seçimi)." else ".") + " Trafiği tünele girmiyor."
    net.atom.dpibypass.data.AppFilterMode.Exclude -> "Uygulama \"Seçili hariç\" listesinde; trafiği tünele girmiyor."
    net.atom.dpibypass.data.AppFilterMode.All -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformRow(
    platform: SocialPlatform,
    target: Target?,
    result: TargetResult?,
    status: TargetStatus,
    voiceBlocked: Boolean,
    scopeHint: String?,
    onBringIntoScope: () -> Unit,
    onReport: (String) -> Unit,
    reportEnabled: Boolean,
) {
    val bad = status in setOf(TargetStatus.OUT_OF_SCOPE, TargetStatus.NOT_WORKING, TargetStatus.NETWORK_BLOCKED, TargetStatus.NO_NETWORK)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (platform == SocialPlatform.CUSTOM) target?.key?.removePrefix("custom:") ?: platform.displayName else platform.displayName,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TagBadge(TargetStatusRules.label(status), color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        // Özellikler ayrı gösterilir: ağ yolunun açılması içerik, medya ya da sesin çalıştığını kanıtlamaz.
        Text(
            buildList {
                add("Ağ: " + SocialText.check(result?.network ?: Check(), platform.mode(Capability.NETWORK)))
                if (platform.mode(Capability.CONTENT) != CheckMode.UNSUPPORTED || platform.mode(Capability.NATIVE_APP) != CheckMode.UNSUPPORTED)
                    add("İçerik/uygulama: " + SocialText.check(result?.content ?: Check(), CheckMode.MANUAL.takeIf { platform.mode(Capability.CONTENT) != CheckMode.AUTO } ?: CheckMode.AUTO))
                if (platform.mode(Capability.MEDIA) != CheckMode.UNSUPPORTED) add("Medya: " + if (platform.mode(Capability.MEDIA) == CheckMode.AUTO) "test penceresinde otomatik" else "onayın gerekli")
                if (platform.mode(Capability.VOICE) != CheckMode.UNSUPPORTED) add("Ses (UDP): " + if (voiceBlocked) "kapalı — QUIC/UDP engelleme ayarı açık" else "onayın gerekli")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result?.takeIf { it.network.status != CheckStatus.PASS && it.failureClass.isNotBlank() && !it.stale }?.let {
            Text("Ağ ölçümü: " + SocialText.failure(it.failureClass), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result?.conflict?.takeIf { it.isNotBlank() }?.let {
            Text("Çakışma: \"$it\" bu uygulamayı bozduğu için reddedildi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (platform.note.isNotBlank()) Text(platform.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        scopeHint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onBringIntoScope) { Text("Kapsama ekle") }
        }
        if (result != null && reportEnabled && status != TargetStatus.OUT_OF_SCOPE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onReport("RESOLVED") }) { Text("Çalışıyor") }
                TextButton(onClick = { onReport("PARTIAL") }) { Text("Kısmen") }
                TextButton(onClick = { onReport("STILL_PRESENT") }) { Text("Çalışmıyor") }
                TextButton(onClick = { onReport("NOT_TESTED") }) { Text("Test edemedim") }
            }
        }
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
                    if (state.targets.size > 1) append("${state.targets.size} uygulama · ")
                    append("Yöntem ${state.attempted}")
                    if (plannedMethods > 0) append("/$plannedMethods")
                    append(" · Bağlantı: ${SocialText.technical(state.technicalOutcome)}")
                    if (state.mode == TestMode.AUTO_BETA) append(" · İçerik: ${SocialText.verdict(state.autoVerdict)}")
                    append(" · Test ${state.requests}")
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
            if (state.resourceDisposition == "KEPT_ACTIVE" && kept != null && state.plan.strategyId == kept)
                AppButton("Manuel stratejim de bu olsun", { onKeep(kept) }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal)
            if (state.finishReason == "NETWORK_VERIFIED_AUTO" && state.plan.installed) {
                HorizontalDivider()
                Text("Ağ yolu otomatik doğrulandı. ${state.target?.let { SocialPlatform.byId(it.platformId)?.displayName } ?: "Uygulama"} içinde içerik açılıyor mu?",
                    style = MaterialTheme.typography.labelLarge)
                AppButton("Hedefi aç", onOpenTarget, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Açıldı", { state.target?.let { t -> SocialAccessRuntime.launch { report(t.key, "RESOLVED") } } }, modifier = Modifier.weight(1f))
                    AppButton("Hâlâ açılmıyor", { state.target?.let { t -> SocialAccessRuntime.launch { report(t.key, "STILL_PRESENT") } } },
                        modifier = Modifier.weight(1f), tone = ButtonTone.Tonal)
                }
            }
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
                "1. Birlikte çalışması gereken uygulamaları seç; istersen açılmayan içeriğin bağlantısını yapıştır.",
                "2. Uygulama mevcut tünelde yerel DPI profillerini (ISS'ne göre sıralı) tek tek dener ve her birini seçili BÜTÜN uygulamalarda ölçer.",
                "3. Çalışan bir uygulamayı bozan profil reddedilir. İşe yarayan profil motorun planı olur; ayar ya da ağ değişince kaybolmaz.",
                "Trafik uzak bir sunucuya yönlendirilmez. Plan, tünelden geçen bütün uygulamaları etkiler; giriş, yaş doğrulama ve hesap kısıtları yerel ayarla çözülmez.",
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
    "NETWORK_VERIFIED_AUTO" -> "Ağ yolu otomatik doğrulandı ve plan korunuyor; içerik/uygulama için onayın gerekiyor."
    "ALREADY_ACCESSIBLE" -> "Başlangıçta zaten açıktı; yöntem denenmedi."
    "AUTO_UNAVAILABLE" -> "Bu hedef için otomatik içerik gözlemi yok; Manuel devam edebilirsin."
    "AUTO_INCONCLUSIVE" -> "Otomatik sonuç belirsiz; ertelenen yöntemler Manuel denenebilir."
    "CANDIDATES_EXHAUSTED" -> "Bu turdaki uygun yöntemler bitti."
    "NO_APPLICABLE_METHODS" -> "Bu kapsamda uygulanabilir yöntem bulunmadı."
    "PARTIAL_ACCEPTED" -> "Kısmi erişimi kabul ettin."
    else -> "Deneme durduruldu."
}
