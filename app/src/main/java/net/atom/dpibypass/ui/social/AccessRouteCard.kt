package net.atom.dpibypass.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.AccessAction
import net.atom.dpibypass.social.AccessLauncher
import net.atom.dpibypass.social.AccessRoutes
import net.atom.dpibypass.social.AccessSurface
import net.atom.dpibypass.social.AccountAge
import net.atom.dpibypass.social.AccountContext
import net.atom.dpibypass.social.GateCause
import net.atom.dpibypass.social.RouteContext
import net.atom.dpibypass.social.RouteOutcome
import net.atom.dpibypass.social.RouteRejection
import net.atom.dpibypass.social.RouteStatus
import net.atom.dpibypass.social.RouteVerdict
import net.atom.dpibypass.social.SocialAccessRuntime
import net.atom.dpibypass.social.SocialPlatform
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.ChoiceDialog
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.vpn.AppScope

/**
 * İşlem bazında doğrulanmış erişim yolu. Seçilen işlem için resmî uygulamayı ya da
 * AÇIKÇA seçilen tarayıcıyı açar; dönüşte kısa sonuç ister ve bunu ağ/profil/süreç
 * bağlamıyla kaydeder. Güncel kanıtı olan yüzeyi tek dokunuşla yeniden açar.
 * Sunucudaki yaş/kimlik doğrulamasını değiştirmez.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessRouteCard(
    platform: SocialPlatform,
    url: String,
    settings: Settings,
    routeContext: RouteContext?,
    engineConnected: Boolean,
    onChooseBrowser: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ledger by SocialAccessRuntime.routes.ledger.collectAsStateWithLifecycle()
    val actions = remember(platform) { AccessRoutes.actions(platform) }
    if (actions.isEmpty()) return
    var actionName by rememberSaveable(platform.id) { mutableStateOf(actions.first().name) }
    val action = AccessAction.parse(actionName)?.takeIf { it in actions } ?: actions.first()
    // Açılan yüzey ve açıldığı ANDAKİ bağlam: dönüşte bağlam değiştiyse bildirim eski bağlama yazılmaz.
    var openedSurface by rememberSaveable(platform.id) { mutableStateOf<String?>(null) }
    var openedBrowser by rememberSaveable(platform.id) { mutableStateOf<String?>(null) }
    var openedContext by rememberSaveable(platform.id) { mutableStateOf<String?>(null) }
    var outcome by rememberSaveable(platform.id) { mutableStateOf<String?>(null) }
    var account by rememberSaveable(platform.id) { mutableStateOf<String?>(null) }
    var fresh by rememberSaveable(platform.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember(platform.id) { mutableStateOf("") }
    var browserPicker by remember { mutableStateOf(false) }
    var browsers by remember { mutableStateOf<List<AccessLauncher.Browser>>(emptyList()) }
    var appPackage by remember(platform) { mutableStateOf<String?>(null) }
    var defaultBrowser by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(platform) {
        withContext(Dispatchers.IO) {
            appPackage = AccessLauncher.installedPackage(context, platform)
            browsers = AccessLauncher.browsers(context)
            defaultBrowser = AccessLauncher.defaultBrowser(context)
        }
    }
    val browser = settings.socialBrowserPackage.takeIf { b -> browsers.any { it.packageName == b } }
        ?: defaultBrowser ?: browsers.firstOrNull()?.packageName
    val browserLabel = browsers.firstOrNull { it.packageName == browser }?.label
    fun inTunnel(pkg: String?) = engineConnected && pkg != null &&
        AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, pkg)

    fun open(surface: AccessSurface) {
        val ctx = routeContext ?: return
        val result = when (surface) {
            AccessSurface.OFFICIAL_APP -> AccessLauncher.openInApp(context, platform, url)
            AccessSurface.OFFICIAL_WEB -> AccessLauncher.openInBrowser(context, browser, url)
        }
        message = when (result) {
            AccessLauncher.LaunchResult.OPENED -> {
                openedSurface = surface.name; openedBrowser = if (surface == AccessSurface.OFFICIAL_WEB) browser else null
                openedContext = listOf(ctx.networkKey, ctx.profileKey, ctx.processId).joinToString(SEP); outcome = null; account = null; fresh = false
                ""
            }
            AccessLauncher.LaunchResult.NOT_INSTALLED -> "${platform.displayName} uygulaması kurulu değil."
            AccessLauncher.LaunchResult.NO_BROWSER -> "Açılabilecek bir tarayıcı bulunamadı; bir tarayıcı seç."
            AccessLauncher.LaunchResult.FAILED -> "Açılamadı."
        }
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${platform.displayName} · işlem bazında erişim yolu", style = MaterialTheme.typography.titleSmall)
            Text("Hangi işlemin resmî uygulamada mı, resmî webde mi bu ağda gerçekten çalıştığını senin bildirimlerinle tutar. " +
                "Sunucudaki yaş/kimlik doğrulamasını değiştirmez; ekran gizlemez, çerez ya da tarayıcı kimliği değiştirmez.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEach { a -> FilterChip(a == action, { actionName = a.name; openedSurface = null }, label = { Text(a.label) }) }
            }

            if (routeContext == null) {
                Text("Ağ bilgisi okunuyor…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val decision = AccessRoutes.decide(ledger, platform.id, action, routeContext)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagBadge(verdictLabel(decision.verdict), color = when (decision.verdict) {
                    RouteVerdict.VERIFIED_ROUTE, RouteVerdict.TENTATIVE_ROUTE -> MaterialTheme.colorScheme.primary
                    RouteVerdict.ALL_SURFACES_GATED, RouteVerdict.NO_WORKING_ROUTE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            }
            Text(decision.explanation, style = MaterialTheme.typography.bodySmall)
            decision.surfaces.forEach { s ->
                Text("• ${s.surface.label}${s.browserPackage?.let { b -> " (" + (browsers.firstOrNull { it.packageName == b }?.label ?: b) + ")" }.orEmpty()}: " +
                    statusLabel(s.status, s.gateCause) + if (s.routePath == "DIRECT") " · tünel dışı" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            decision.recommended?.let { rec ->
                AppButton("Bu yolla aç: ${rec.surface.label}", { open(rec.surface) }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Uygulamada aç", { open(AccessSurface.OFFICIAL_APP) }, modifier = Modifier.weight(1f), tone = ButtonTone.Tonal, enabled = appPackage != null)
                AppButton("Tarayıcıda aç", { open(AccessSurface.OFFICIAL_WEB) }, modifier = Modifier.weight(1f), tone = ButtonTone.Tonal, enabled = browser != null)
            }
            TextButton(onClick = { browserPicker = true }) {
                Text("Tarayıcı: " + (browserLabel ?: "yok") + if (settings.socialBrowserPackage.isBlank()) " (varsayılan) · değiştir" else " · değiştir")
            }
            if (appPackage != null && !inTunnel(appPackage))
                Text("Uygulama şu an tünelde değil (bağlantı kapalı ya da kapsam dışı): uygulamada denediğin sonuç DPI planının etkisi sayılmaz.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            val surface = AccessSurface.parse(openedSurface)
            if (surface != null) {
                HorizontalDivider()
                Text("${surface.label} açıldı. Dönüşte: \"${action.label}\" nasıldı?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteOutcome.entries.forEach { o -> FilterChip(outcome == o.name, { outcome = o.name }, label = { Text(o.label) }) }
                }
                Text("Hesap", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountContext.entries.forEach { a -> FilterChip(account == a.name, { account = a.name }, label = { Text(a.label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(fresh, { fresh = it })
                    Text("Uygulamayı yeniden başlattım ve yeni bir içerik denedim (verileri silmeden)", style = MaterialTheme.typography.bodySmall)
                }
                AppButton(if (saving) "Kaydediliyor ve ağ ölçülüyor…" else "Bildirimi kaydet", {
                    val o = RouteOutcome.parse(outcome) ?: return@AppButton
                    val acc = AccountContext.parse(account) ?: AccountContext.UNKNOWN
                    val opened = openedContext?.split(SEP)?.takeIf { it.size == 3 }?.let { RouteContext(it[0], it[1], it[2]) } ?: return@AppButton
                    val pkg = if (surface == AccessSurface.OFFICIAL_WEB) openedBrowser else appPackage
                    saving = true
                    scope.launch {
                        // Bildirim açılış anındaki bağlamla yapılır; şimdiki bağlam farklıysa reddedilir.
                        val rejection = runCatching {
                            if (opened != routeContext) RouteRejection.CONTEXT_CHANGED
                            else SocialAccessRuntime.recordRoute(platform, action, surface, openedBrowser, acc, o, fresh, inTunnel(pkg), opened)
                        }.getOrElse { RouteRejection.CONTEXT_CHANGED }
                        saving = false
                        message = rejectionText(rejection)
                        if (rejection == null) openedSurface = null
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = outcome != null && !saving)
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

            HorizontalDivider()
            Text("Bu hesap ne zaman açıldı? (geçiş süresini ayırmak için; hesap bilgisi saklanmaz)", style = MaterialTheme.typography.labelMedium)
            val age = ledger.accountAge[platform.id] ?: AccountAge.UNKNOWN
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(AccountAge.BEFORE_REGULATION to "1 Kasım 2026'dan önce", AccountAge.AFTER_REGULATION to "Sonra", AccountAge.UNKNOWN to "Bilmiyorum").forEach { (value, label) ->
                    FilterChip(age == value, { scope.launch(Dispatchers.IO) { SocialAccessRuntime.routes.setAccountAge(platform.id, value) } }, label = { Text(label) })
                }
            }
            AccessRoutes.transitionNote(age, AccessRoutes.today(System.currentTimeMillis()))?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (browserPicker) {
        ChoiceDialog(
            title = "Resmî web hangi tarayıcıda açılsın?",
            options = listOf("" to "Sistem varsayılanı") + browsers.map { it.packageName to it.label },
            selected = settings.socialBrowserPackage,
            onSelect = { onChooseBrowser(it); browserPicker = false },
            onDismiss = { browserPicker = false },
        )
    }
}

private const val SEP = "\u0001"

private fun verdictLabel(v: RouteVerdict) = when (v) {
    RouteVerdict.VERIFIED_ROUTE -> "Doğrulanmış yol"
    RouteVerdict.TENTATIVE_ROUTE -> "Kısmen çalışıyor / tek onay"
    RouteVerdict.ALL_SURFACES_GATED -> "Platform doğrulama istiyor"
    RouteVerdict.NO_WORKING_ROUTE -> "Çalışan yol yok"
    RouteVerdict.NEEDS_REVERIFICATION -> "Ağ değişti, yeniden kontrol gerekiyor"
    RouteVerdict.NOT_TESTED -> "Denenmedi"
}

private fun statusLabel(s: RouteStatus, cause: GateCause?) = when (s) {
    RouteStatus.VERIFIED -> "doğrulandı"
    RouteStatus.SINGLE_CONFIRMATION -> "tek onay"
    RouteStatus.PARTIAL -> "kısmen"
    RouteStatus.NOT_WORKING -> "çalışmıyor"
    RouteStatus.PLATFORM_GATE -> when (cause) {
        GateCause.SERVER_REQUIREMENT_NETWORK_OPEN -> "doğrulama istiyor (ağ açıktı: sunucu şartı)"
        GateCause.NETWORK_PROBLEM_SUSPECTED -> "doğrulama/yükleme ekranı (ağ kapalıydı: ağ sorunu olabilir)"
        else -> "doğrulama istiyor (ağ ölçülemedi)"
    }
    RouteStatus.UNSURE -> "emin değil / hesap teyidi yok"
    RouteStatus.PREVIOUS_SESSION -> "önceki oturumda bildirildi; yeniden kontrol"
    RouteStatus.NOT_TESTED -> "denenmedi"
}

private fun rejectionText(r: RouteRejection?) = when (r) {
    null -> "Kaydedildi (kullanıcı bildirimi; otomatik kanıt değildir)."
    RouteRejection.STALE_UI -> "Bu ekran eski bir sürüme ait; sayfayı yeniden açıp tekrar bildir."
    RouteRejection.CONTEXT_CHANGED -> "Ağ ya da profil bu arada değişti; bildirim eski bağlama yazılmadı. Yeniden aç ve tekrar dene."
    RouteRejection.BROWSER_REQUIRED -> "Önce bir tarayıcı seç."
    RouteRejection.ACTION_NOT_SUPPORTED, RouteRejection.UNKNOWN_PLATFORM -> "Bu işlem bu platform için desteklenmiyor."
    RouteRejection.FUTURE_TIME -> "Cihaz saati tutarsız; bildirim kaydedilmedi."
}
