package net.atom.dpibypass.ui.social

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.social.AccessRoutes
import net.atom.dpibypass.social.AccountAge
import net.atom.dpibypass.social.Capability
import net.atom.dpibypass.social.Check
import net.atom.dpibypass.social.CheckMode
import net.atom.dpibypass.social.CheckStatus
import net.atom.dpibypass.social.GateCause
import net.atom.dpibypass.social.Phase
import net.atom.dpibypass.social.RouteStatus
import net.atom.dpibypass.social.RouteVerdict
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.SocialPlatform
import net.atom.dpibypass.social.SocialText
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.social.TargetResult
import net.atom.dpibypass.social.TargetStatus
import net.atom.dpibypass.social.TargetStatusRules
import net.atom.dpibypass.social.TestMode
import net.atom.dpibypass.social.TrialState
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScreen
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
// Sosyal Erişim — Ayarlar girişi, Gelişmiş ayarlar ve Tanılama.
//
// Asıl kullanım rehberdedir ([SocialGuideScreen]). Eski tek-sayfa ekrandaki her
// yetenek burada ulaşılabilir kalır: açma/kapama, kip, uygulamalar, tarayıcı,
// kapsam düzeltmesi, etkin plan (kaldırma, ana ayar yapma), hedef başına durum,
// "seçilileri kontrol et", işlem bazında yol ayrıntıları ve hesap açılış dönemi.
// Her eylemin etkisi yanında yazılır; benzer adlı eylemler (kapat / planı kaldır /
// durdur / rehberden çık) farklı şeyler yapar.
// ---------------------------------------------------------------------------

private val BusyPhases = setOf(Phase.BASELINING, Phase.SELECTING, Phase.PREPARING, Phase.APPLYING,
    Phase.PROBING, Phase.CANCELLING, Phase.ROLLING_BACK)

/** Ayarlar'daki tek satırlık giriş: kısa durum + rehbere geçiş. */
@Composable
fun SocialAccessEntry(viewModel: AppViewModel, onOpen: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    SectionHeader("Sosyal erişim")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = "Bir uygulama açılmıyor mu?",
            subtitle = entrySummary(settings.socialAccessEnabled, settings.socialPrimaryPlatform, state),
            icon = Icons.Rounded.Public,
            onClick = onOpen,
            modifier = Modifier.testTag("social-entry"),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagBadge("Alpha")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

private fun entrySummary(enabled: Boolean, primary: String, state: TrialState): String = when {
    !enabled -> "Adım adım rehber: sorunu seç, bu ağda çalışan bağlantı ayarını birlikte bulalım."
    state.phase == Phase.RECOVERY_REQUIRED -> "Geri alma tamamlanamadı; açıp yeniden dene."
    state.phase in BusyPhases || state.phase == Phase.WAITING_FOR_USER || state.phase == Phase.AUTO_EVALUATING ->
        "Kontrol sürüyor; kaldığın yerden devam edebilirsin."
    state.plan.strategyId != null && state.plan.installed -> "${GuideText.name(primary.ifBlank { null })} için kayıtlı ayar çalışıyor."
    primary.isNotBlank() -> "${GuideText.name(primary)} için rehberi aç."
    else -> "Adım adım rehber: sorunu seç, bu ağda çalışan bağlantı ayarını birlikte bulalım."
}

/**
 * Gelişmiş ayarlar. Rehberden ve sonuç ekranından açılır; geri dönmek rehberin
 * akışını ve cevaplarını korur.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialAdvancedScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenDiagnostics: () -> Unit, onOpenSettings: () -> Unit, onOpenApps: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val routeCtx by viewModel.routeContext.collectAsStateWithLifecycle()
    val ledger by SocialAccessRuntime.routes.ledger.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val device by produceState(DeviceApps()) { value = withContext(Dispatchers.IO) { DeviceApps.load(context) } }
    val engineConnected = connection == ConnectionState.Connected
    var browserPicker by remember { mutableStateOf(false) }
    var confirmClearPlan by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val selected = SocialPlatform.entries.filter { it.id in settings.socialPlatforms }

    AppScreen(
        title = "Gelişmiş ayarlar",
        subtitle = "Sosyal Erişim'in bütün seçenekleri. Rehberdeki cevapların ve süren kontrol korunur.",
        actions = { IconButton(onClick = onBack, modifier = Modifier.testTag("advanced-back")) { Icon(Icons.Rounded.Close, contentDescription = "Geri") } },
    ) {
        if (settings.readError || saveError != null) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                ListRow(title = if (settings.readError) "Ayarlar okunamadı" else "Kaydedilemedi",
                    subtitle = if (settings.readError) "Varsayılanlar gösteriliyor; yaptığın değişiklik kaydedilmeye çalışılacak." else saveError,
                    icon = Icons.Rounded.ErrorOutline, iconTint = MaterialTheme.colorScheme.error, onClick = viewModel::clearSaveError)
            }
        }

        SectionHeader("Durum")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "Sosyal Erişim",
                subtitle = "Kapatmak yalnız süren geçici denemeyi geri alır. Kaydedilmiş çalışan ayar (plan) korunur; onu kaldırmak için aşağıdaki \"Planı kaldır\"ı kullan.",
                icon = Icons.Rounded.Public,
                checked = settings.socialAccessEnabled,
                onCheckedChange = { on -> if (!on && SocialAccessRuntime.coordinator.active()) confirmDisable = true else viewModel.setSocialAccessEnabled(on) },
            )
            RowDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Deneme kipi", style = MaterialTheme.typography.labelLarge)
                SegmentedControl(
                    options = listOf(Segment(TestMode.MANUAL, "Adım adım"), Segment(TestMode.AUTO_BETA, "Otomatik (Beta)")),
                    selected = settings.socialAccessMode,
                    onSelect = viewModel::setSocialAccessMode,
                )
                Text("Burada değiştirmek süren denemeyi de o kipe geçirir. Otomatik kip ağ yolunu ölçer; uygulama içi sonuç için yine onayın istenir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionHeader("Uygulamalar")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Birincil: " + (settings.socialPrimaryPlatform.ifBlank { null }?.let { GuideText.name(it, settings.socialTargetUrl) } ?: "seçilmedi (rehber sorar)"),
                    style = MaterialTheme.typography.labelLarge)
                Text("Bulunan ayar seçili bütün uygulamalara birlikte uygulanır ve hepsinde ölçülür.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SocialPlatform.catalog.forEach { p ->
                        FilterChip(selected = p in selected, onClick = { viewModel.setSocialPlatform(p.id, p !in selected) }, label = { Text(p.displayName) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                if (settings.socialTargetUrl.isNotBlank())
                    Text("Bağlantı: ${settings.socialTargetUrl.take(80)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selected.filter { it != SocialPlatform.CUSTOM }.forEach { p ->
                val target = Target.home(p.id)
                val result = target?.let { state.results[it.key] }
                val pkg = device.packages[p.id]
                val inScope = pkg == null || net.atom.dpibypass.vpn.AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, pkg)
                RowDivider()
                PlatformRow(p, result, TargetStatusRules.of(result, inScope, engineConnected, SocialAccessRuntime.networkUsable()), settings.disableQuic,
                    if (inScope) null else scopeHint(settings.appFilterMode, settings.scopeBackupAvailable)) { pkg?.let { viewModel.bringIntoScope(setOf(it)) } }
            }
            if (selected.isNotEmpty()) {
                RowDivider()
                Column(Modifier.padding(16.dp)) {
                    AppButton("Seçilileri şimdi kontrol et", {
                        val targets = selected.mapNotNull { if (it == SocialPlatform.CUSTOM) Target.parse(it.id, settings.socialTargetUrl) else Target.home(it.id) }
                        SocialAccessRuntime.launch { check(targets) }
                    }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal,
                        enabled = state.phase !in BusyPhases && !SocialAccessRuntime.coordinator.active())
                    Text("Motoru değiştirmeden yalnız ağ yolunu ölçer; uygulama içi sonuç değildir.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionHeader("Tarayıcı")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(title = "Tarayıcı yolu", subtitle = (device.browserLabel(settings.socialBrowserPackage) ?: "Sistem varsayılanı") +
                ". Tarayıcıda alınan sonuç yalnız o tarayıcı için geçerlidir.", icon = Icons.Rounded.Public, onClick = { browserPicker = true })
        }

        state.plan.strategyId?.let { planId ->
            SectionHeader("Kayıtlı ayar (plan)")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                ListRow(title = "Yerel bağlantı ayarı: $planId",
                    subtitle = if (state.plan.installed && engineConnected) "Bağlantıda çalışıyor. Ağ ya da ayar değişince korunur; sonuçlar kısaca yeniden kontrol edilir."
                    else "Kayıtlı ama bu bağlantıda kurulu değil (bağlantı kapalı ya da doğrulanamadı).", icon = Icons.Rounded.Tune)
                RowDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Ana bağlantı ayarım da bu olsun", { viewModel.keepSocialStrategy(planId) { ok -> info = if (ok) "Kaydedildi: ana bağlantı ayarın da $planId oldu." else "" } },
                        modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Tonal)
                    AppButton("Planı kaldır", { confirmClearPlan = true }, modifier = Modifier.fillMaxWidth(), tone = ButtonTone.Danger, enabled = state.plan.strategyId != null)
                    if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        settings.socialPrimaryPlatform.ifBlank { null }?.let { SocialPlatform.byId(it) }?.takeIf { it != SocialPlatform.CUSTOM }?.let { p ->
            SectionHeader("${p.displayName} · işlem bazında yollar")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ctx = routeCtx
                    if (ctx == null) Text("Ağ bilgisi okunuyor…", style = MaterialTheme.typography.bodySmall)
                    else AccessRoutes.actions(p).forEach { a ->
                        val d = AccessRoutes.decide(ledger, p.id, a, ctx)
                        if (d.verdict != RouteVerdict.NOT_TESTED) {
                            Text("${GuideText.actionChoice(a)}: ${verdictLabel(d.verdict)}", style = MaterialTheme.typography.labelLarge)
                            d.surfaces.forEach { s ->
                                Text("• ${s.surface.label}${s.browserPackage?.let { b -> " (" + (device.browserLabel(b) ?: b) + ")" }.orEmpty()}: ${statusLabel(s.status, s.gateCause)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (ctx != null && AccessRoutes.actions(p).all { AccessRoutes.decide(ledger, p.id, it, ctx).verdict == RouteVerdict.NOT_TESTED })
                        Text("Bu bağlantıda henüz bildirim yok.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RowDivider()
                AccountAgeQuestion(p, ledger.accountAge[p.id] ?: AccountAge.UNKNOWN)
            }
        }

        SectionHeader("Bağlantı ayarları")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(title = "DNS, QUIC ve Vodafone ayarları", subtitle = "Ayarlar sekmesinde. Değişince kayıtlı sonuçlar bu bağlantı için yeniden kontrol edilir.",
                icon = Icons.Rounded.Settings, onClick = onOpenSettings)
            RowDivider()
            ListRow(title = "Uygulama filtresi (kapsam)", subtitle = when (settings.appFilterMode) {
                AppFilterMode.All -> "Bütün uygulamalar bağlantıdan geçiyor."
                AppFilterMode.Include -> "Yalnız seçili ${settings.selectedApps.size} uygulama geçiyor."
                AppFilterMode.Exclude -> "Seçili ${settings.selectedApps.size} uygulama hariç."
            }, icon = Icons.Rounded.Route, onClick = onOpenApps)
            if (settings.scopeBackupAvailable) {
                RowDivider()
                ListRow(title = "Önceki uygulama filtresini geri yükle", subtitle = "Kurulumdaki \"Sadece Discord\" seçiminden önceki liste.",
                    icon = Icons.Rounded.Route, onClick = viewModel::restoreScopeBackup)
            }
        }

        SectionHeader("Tanılama")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(title = "Tanılama ve geçmiş", subtitle = "Önizle, dosyaya kaydet ya da geçmişi sil.", icon = Icons.Rounded.Description, onClick = onOpenDiagnostics)
        }
        VSpace(8.dp)
    }

    if (browserPicker) {
        ChoiceDialog(
            title = "Tarayıcı yolu hangi tarayıcıda açılsın?",
            options = listOf("" to "Sistem varsayılanı") + device.browsers.map { it.packageName to it.label },
            selected = settings.socialBrowserPackage,
            onSelect = { viewModel.setSocialBrowserPackage(it); browserPicker = false },
            onDismiss = { browserPicker = false },
        )
    }
    if (confirmClearPlan) {
        AlertDialog(
            onDismissRequest = { confirmClearPlan = false },
            title = { Text("Planı kaldır?") },
            text = { Text("Bağlantı, plan kurulmadan önceki ayarına döner. Sosyal Erişim açık kalır ve bildirimlerin silinmez; yalnız bu ayarla ilgili sonuçlar yeniden kontrol gerektirir.") },
            confirmButton = { TextButton(onClick = { confirmClearPlan = false; SocialAccessRuntime.launch { clearPlan() } }) { Text("Planı kaldır") } },
            dismissButton = { TextButton(onClick = { confirmClearPlan = false }) { Text("Vazgeç") } },
        )
    }
    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text("Sosyal Erişim kapatılsın mı?") },
            text = { Text("Süren deneme durdurulur ve geçici değişiklik geri alınır. Kayıtlı çalışan ayar (plan) korunur.") },
            confirmButton = { TextButton(onClick = { confirmDisable = false; viewModel.setSocialAccessEnabled(false) }) { Text("Kapat") } },
            dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text("Vazgeç") } },
        )
    }
}

/** Hesap açılış dönemi: ilk kurulumu engellemez; yalnız geçiş süresi etkisini ayırmak için. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountAgeQuestion(p: SocialPlatform, age: AccountAge) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bu hesap ne zaman açıldı? (isteğe bağlı; hesap bilgisi saklanmaz)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(AccountAge.BEFORE_REGULATION to "1 Kasım 2026'dan önce", AccountAge.AFTER_REGULATION to "Sonra", AccountAge.UNKNOWN to "Bilmiyorum").forEach { (value, label) ->
                FilterChip(age == value, { scope.launch { failed = !withContext(Dispatchers.IO) { SocialAccessRuntime.routes.setAccountAge(p.id, value) } } },
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (failed) Text("Kaydedilemedi; tekrar dene.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        AccessRoutes.transitionNote(age, AccessRoutes.today(System.currentTimeMillis()))?.let {
            Text("$it (Kaynak 26.10.2026'da okundu; uygulama her platformda farklı olabilir.)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Kapsam dışı uyarısı: neden dışarıda olduğu açıkça yazılır. */
private fun scopeHint(mode: AppFilterMode, discordBackup: Boolean) = when (mode) {
    AppFilterMode.Include -> "\"Yalnızca seçili\" kipindesin ve bu uygulama listede değil" +
        (if (discordBackup) " (kurulumdaki \"Sadece Discord\" seçimi)." else ".") + " Trafiği bağlantıdan geçmiyor."
    AppFilterMode.Exclude -> "Uygulama \"Seçili hariç\" listesinde; trafiği bağlantıdan geçmiyor."
    AppFilterMode.All -> null
}

@Composable
private fun PlatformRow(platform: SocialPlatform, result: TargetResult?, status: TargetStatus, voiceBlocked: Boolean, scopeHint: String?, onBringIntoScope: () -> Unit) {
    val bad = status in setOf(TargetStatus.OUT_OF_SCOPE, TargetStatus.NOT_WORKING, TargetStatus.NETWORK_BLOCKED, TargetStatus.NO_NETWORK)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(platform.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TagBadge(TargetStatusRules.label(status), color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        // Özellikler ayrı: ağ yolunun açılması içerik, medya ya da sesin çalıştığını kanıtlamaz.
        Text(
            buildList {
                add("Ağ: " + SocialText.check(result?.network ?: Check(), platform.mode(Capability.NETWORK)))
                if (platform.mode(Capability.CONTENT) != CheckMode.UNSUPPORTED || platform.mode(Capability.NATIVE_APP) != CheckMode.UNSUPPORTED)
                    add("Uygulama/içerik: " + SocialText.check(result?.content ?: Check(), CheckMode.MANUAL.takeIf { platform.mode(Capability.CONTENT) != CheckMode.AUTO } ?: CheckMode.AUTO))
                if (platform.mode(Capability.VOICE) != CheckMode.UNSUPPORTED) add("Ses: " + if (voiceBlocked) "kapalı — QUIC/UDP engelleme açık" else "onayın gerekli")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result?.takeIf { it.network.status != CheckStatus.PASS && it.failureClass.isNotBlank() && !it.stale }?.let {
            Text("Ağ ölçümü: " + SocialText.failure(it.failureClass), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result?.conflict?.takeIf { it.isNotBlank() }?.let {
            Text("\"$it\" bu uygulamayı bozduğu için seçilmedi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        scopeHint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onBringIntoScope, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bağlantıya ekle") }
        }
    }
}

private fun verdictLabel(v: RouteVerdict) = when (v) {
    RouteVerdict.VERIFIED_ROUTE -> "ayrı zamanlarda doğrulandı"
    RouteVerdict.TENTATIVE_ROUTE -> "ilk denemede çalıştı / kısmen"
    RouteVerdict.ALL_SURFACES_GATED -> "desteklenen bütün yollarda doğrulama istendi"
    RouteVerdict.NO_WORKING_ROUTE -> "çalışan yol bildirilmedi"
    RouteVerdict.NEEDS_REVERIFICATION -> "ağ ya da ayar değişti; yeniden kontrol"
    RouteVerdict.NOT_TESTED -> "denenmedi"
}

private fun statusLabel(s: RouteStatus, cause: GateCause?) = when (s) {
    RouteStatus.VERIFIED -> "doğrulandı"
    RouteStatus.SINGLE_CONFIRMATION -> "ilk denemede çalıştı"
    RouteStatus.PARTIAL -> "kısmen"
    RouteStatus.NOT_WORKING -> "çalışmadı"
    RouteStatus.PLATFORM_GATE -> when (cause) {
        GateCause.CHECK_ADDRESS_REACHABLE -> "doğrulama istedi (kontrol adresine ulaşılıyordu)"
        GateCause.NETWORK_PROBLEM_SUSPECTED -> "doğrulama/yükleme ekranı (bağlantı hatası ölçüldü)"
        GateCause.NETWORK_UNCERTAIN -> "doğrulama istedi (ağ ölçümü sonuçsuz)"
        else -> "doğrulama istedi (ağ ölçülemedi)"
    }
    RouteStatus.UNSURE -> "emin değil / hesap teyidi yok"
    RouteStatus.PREVIOUS_SESSION -> "önceki oturumda bildirildi; yeniden kontrol"
    RouteStatus.NOT_TESTED -> "denenmedi"
}

/**
 * Tanılama: yalnız bu cihazda tutulur; bağlantı, hesap veya ağ kimliği içermez.
 * Dosyaya kaydetme gerçekten yazılmadıysa "kaydedildi" denmez.
 */
@Composable
fun SocialDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
        if (destination != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val out = requireNotNull(context.contentResolver.openOutputStream(destination)) { "NO_STREAM" }
                    out.use { it.write(SocialAccessRuntime.adapter.diagnosticPreview().toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            error = !ok
            message = if (ok) "Tanılama dosyası kaydedildi." else "Tanılama dosyası kaydedilemedi; hedef açılamadı."
        }
    }
    AppScreen(
        title = "Tanılama",
        subtitle = "Yalnız bu cihazda tutulur. Bağlantı adresi, hesap ya da ağ kimliği içermez.",
        actions = { IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Geri") } },
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(title = "Tanılama raporunu önizle", subtitle = "Sürümler, kayıtlı ayar, hedef başına sonuç ve hata sınıfları.",
                icon = Icons.Rounded.Description, onClick = { scope.launch { preview = withContext(Dispatchers.IO) { SocialAccessRuntime.adapter.diagnosticPreview() } } })
            RowDivider()
            ListRow(title = "Geçmişi sil", subtitle = "Etkin kurtarma, bekleme süresi ve kayıtlı ayar korunur.", icon = Icons.Rounded.DeleteOutline,
                onClick = { confirmClear = true })
        }
        if (SocialAccessRuntime.routes.recoveredUnreadable) {
            VSpace(8.dp)
            AppCard(modifier = Modifier.fillMaxWidth()) {
                ListRow(title = "Önceki yol kaydı okunamadı",
                    subtitle = if (SocialAccessRuntime.routes.writeBlocked) "Yedeklenemediği için üzerine yazılmıyor; yeni bildirimler kaydedilemez."
                    else "Dosya \"social-access-routes.unreadable.json\" olarak yedeklendi; silinmedi.",
                    icon = Icons.Rounded.ErrorOutline, iconTint = MaterialTheme.colorScheme.error)
            }
        }
        if (message.isNotBlank()) {
            VSpace(8.dp)
            Text(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        VSpace(8.dp)
    }
    preview?.let { report ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Yerel tanılama önizlemesi") },
            text = { Text(report.take(20_000), style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { preview = null; save.launch("social-alpha-diagnostics.json") }) { Text("Dosyaya kaydet") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Kapat") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Geçmiş silinsin mi?") },
            text = { Text("Tanı geçmişi silinir. Etkin kurtarma kaydı, bekleme süreleri, kayıtlı ayar ve yol bildirimlerin korunur.") },
            confirmButton = { TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { SocialAccessRuntime.adapter.clearHistory() }.isSuccess }
                    error = !ok; message = if (ok) "Tanı geçmişi silindi." else "Geçmiş silinemedi."
                }
            }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } },
        )
    }
}

