package net.atom.dpibypass.ui.settings

import android.net.ConnectivityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.atom.dpibypass.social.*
import net.atom.dpibypass.social.Target
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.*

@Composable
fun SocialAccessCard(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by SocialAccessRuntime.coordinator.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var platform by remember { mutableStateOf("youtube") }
    var problem by remember { mutableStateOf("UNKNOWN") }
    var url by remember { mutableStateOf("") }
    var publicGoal by remember { mutableStateOf(false) }
    var officialWeb by remember { mutableStateOf(false) }
    var controlled by remember { mutableStateOf(false) }
    var playMedia by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<Boolean?>(null) }
    var allowMetered by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var diagnosticPreview by remember { mutableStateOf<String?>(null) }
    val saveDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { destination ->
        if (destination != null) runCatching {
            context.contentResolver.openOutputStream(destination)?.use { it.write(SocialAccessRuntime.adapter.diagnosticPreview().toByteArray(Charsets.UTF_8)) }
        }.onFailure { error = "Tanılama dosyası kaydedilemedi." }
    }
    LaunchedEffect(settings.socialAccessEnabled) {
        SocialAccessRuntime.coordinator.enabled(settings.socialAccessEnabled)
        SocialAccessRuntime.observeNetworks(settings.socialAccessEnabled)
    }
    SectionHeader("Sosyal Erişim Deneyleri — Alpha")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(title = "Sosyal Erişim Deneyleri — Alpha", checked = settings.socialAccessEnabled,
            onCheckedChange = viewModel::setSocialAccessEnabled,
            subtitle = "Uygun yöntemleri sırayla dener. Her denemeden sonra sonucu sen bildirirsin. Her platformda çalışmayabilir.")
        if (settings.socialAccessEnabled) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.socialAccessMode == TestMode.MANUAL, onClick = { viewModel.setSocialAccessMode(TestMode.MANUAL) }, label = { Text("Manuel Test") })
                FilterChip(selected = settings.socialAccessMode == TestMode.AUTO_BETA, onClick = { viewModel.setSocialAccessMode(TestMode.AUTO_BETA) }, label = { Text("Otomatik Test — Beta") })
            }
            Text("Bu cihazda bağlantı testi otomatik; native uygulamadaki hesap ekranını kendin kontrol etmen gerekir. Yerel DPI hattı Android VPN izni ve simgesi kullanır; uzak çıkış kullanmaz.", style = MaterialTheme.typography.bodySmall)
            Text("DPI profili mevcut tünelde seçili bütün uygulamaları etkiler; yalnız bu siteye veya sekmeye ayrılmış değildir. Mevcut uygulama filtresi değiştirilmez.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(platform == "youtube", { platform = "youtube"; url = "" }, label = { Text("YouTube") })
                FilterChip(platform == "instagram", { platform = "instagram"; url = "" }, label = { Text("Instagram") })
            }
            var problemMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { problemMenu = true }) { Text(problemLabel(problem)) }
                DropdownMenu(expanded = problemMenu, onDismissRequest = { problemMenu = false }) {
                    listOf("NETWORK_UNREACHABLE", "AGE_OR_ID_VERIFICATION_REQUIRED", "BOT_CHALLENGE", "MEDIA_PATH_FAILURE", "POSSIBLE_THROTTLING", "UNKNOWN").forEach { p ->
                        DropdownMenuItem(text = { Text(problemLabel(p)) }, onClick = { problem = p; problemMenu = false })
                    }
                }
            }
            AppTextField(url, { url = it }, placeholder = "Seçtiğiniz video/gönderinin HTTPS bağlantısı")
            SwitchRow("Herkese açık içeriği görüntülemek", publicGoal, { publicGoal = it; if (!it) controlled = false },
                subtitle = if (publicGoal) "Anonim kapsam; hesap akışı veya özel içerik başarısı sayılmaz." else "Mevcut hesabımla seçtiğim içeriği açmak; aynı hesapla devam ettiğimi kendim doğrularım.")
            SwitchRow("Resmî tarayıcı yoluna izin ver", officialWeb, { officialWeb = it }, subtitle = "Ana oturuma dokunmadan resmî HTTPS bağlantısı açılır; çıkan uygulamayı kendiniz kontrol edin.")
            if (publicGoal) SwitchRow("Test penceresinde herkese açık içerik", controlled, { controlled = it },
                subtitle = "Ayrı WebView oturumu; native uygulamanın onarıldığını göstermez. Medya verisi tahminidir; 6 yükleme ve her gözlem için 20 saniye sınırı vardır.")
            SwitchRow("Seçilen medyayı oynatmayı sınayın", playMedia || problem == "MEDIA_PATH_FAILURE", { playMedia = it || problem == "MEDIA_PATH_FAILURE" }, subtitle = "Hazır olması yeterli değildir; doğru medyada doğal ilerleme gerekir.")
            if (settings.socialAccessMode == TestMode.AUTO_BETA) SwitchRow("Bu tur ölçülü ağda renderer verisine izin ver", allowMetered, { allowMetered = it },
                subtitle = "Ek medya verisi tahminidir; kesin 20 MiB garantisi yoktur. İzin olmadan yalnız bağlantı tanısı yapılır.")
            Text("Sorun şu anda bu içerikte/hesapta hâlâ var mı?", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(baseline == true, { baseline = true }, label = { Text("Var") })
                FilterChip(baseline == false, { baseline = false }, label = { Text("Yok") })
                FilterChip(baseline == null, { baseline = null }, label = { Text("Emin değilim") })
            }
            AppButton("Başlat", onClick = {
                val target = Target.parse(platform, url)
                if (target == null) error = "Resmî YouTube video veya Instagram gönderi/reel HTTPS bağlantısı seçin."
                else {
                    error = ""
                    val goal = Goal(platform, problem, if (publicGoal) "ANONYMOUS" else "SAME_ACCOUNT",
                        buildSet { add("OFFICIAL_NATIVE_APP"); if (officialWeb) add("OFFICIAL_WEB"); if (controlled && publicGoal) add("CONTROLLED_WEB_VIEW") },
                        if (playMedia || problem == "MEDIA_PATH_FAILURE") setOf("PLAY_MEDIA") else setOf("VIEW_SELECTED_CONTENT"), publicGoal)
                    SocialAccessRuntime.adapter.target = target
                    val metered = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
                    SocialAccessRuntime.launch { start(goal, target, settings.socialAccessMode, baseline, metered, allowMetered, SocialAccessRuntime.catalog(goal)) }
                }
            }, enabled = state.phase in setOf(Phase.DISABLED, Phase.IDLE, Phase.FINISHED, Phase.PAUSED))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (state.sessionId.isNotBlank() || state.phase == Phase.RECOVERY_REQUIRED) {
                HorizontalDivider()
                Text(state.candidate?.displayName ?: "Mevcut durum", style = MaterialTheme.typography.titleSmall)
                Text(state.message)
                Text("Bağlantı: ${state.technicalOutcome} · İçerik: ${state.autoVerdict} · Hesap: manuel · ${state.attempted} yöntem · ${state.requests}/24 prob · ${state.rendererLoads}/6 sayfa", style = MaterialTheme.typography.bodySmall)
                state.finishReason?.let { Text(finishLabel(it), style = MaterialTheme.typography.labelLarge) }
                if (state.phase == Phase.WAITING_FOR_USER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Hâlâ var", { SocialAccessRuntime.launch { feedback(state.feedback("STILL_PRESENT")) } }, tone = ButtonTone.Tonal)
                        AppButton("Yok, açıldı", { SocialAccessRuntime.launch { feedback(state.feedback("RESOLVED")) } })
                    }
                    TextButton(onClick = { SocialAccessRuntime.launch { feedback(state.feedback("NOT_TESTED")) } }) { Text("Test edemedim") }
                    TextButton(onClick = { SocialAccessRuntime.launch { feedback(state.feedback("PARTIAL")) } }) { Text("Bir kısmı çalışıyor") }
                    if (state.userOutcome == "PARTIAL") TextButton(onClick = { SocialAccessRuntime.launch { feedback(state.feedback("PARTIAL_ACCEPTED")) } }) { Text("Böyle kullan") }
                }
                if (state.phase in setOf(Phase.AUTO_EVALUATING, Phase.PROBING) || state.phase == Phase.PAUSED && state.pauseReason == "USER_ACTION_REQUIRED")
                    AppButton("Yok, açıldı", { SocialAccessRuntime.launch { feedback(state.feedback("RESOLVED")) } })
                if (state.mode == TestMode.AUTO_BETA) TextButton(onClick = { viewModel.setSocialAccessMode(TestMode.MANUAL) }) { Text("Manuel devam et") }
                if (state.finishReason == "RESOLVED_AUTO_OBSERVED") TextButton(onClick = { SocialAccessRuntime.launch { correctAutoPass() } }) { Text("Bende hâlâ sorun var") }
                if (state.phase == Phase.PAUSED && state.pauseReason in setOf("BUDGET_LIMIT", "USER_ACTION_REQUIRED"))
                    TextButton(onClick = { SocialAccessRuntime.launch { resume() } }) { Text(if (state.pauseReason == "BUDGET_LIMIT") "Kalanları dene" else "Yaptım, devam et") }
                if (state.phase == Phase.PAUSED && state.pauseReason == "PERMISSION_REQUIRED")
                    TextButton(onClick = { SocialAccessRuntime.launch { resume() } }) { Text("Yerel DPI bağlantısını kurdum, devam et") }
                AppButton("Durdur / geri al", { SocialAccessRuntime.launch { stop() } }, tone = ButtonTone.Danger)
            }
            if (state.candidate?.surface == "CONTROLLED_WEB_VIEW" && state.resourceDisposition in setOf("TRIAL_ACTIVE", "KEPT_ACTIVE"))
                ControlledWebProbe(state, Modifier.fillMaxWidth().height(360.dp))
            TextButton(onClick = { diagnosticPreview = SocialAccessRuntime.adapter.diagnosticPreview() }) { Text("Tanılama raporunu önizle") }
            TextButton(onClick = { SocialAccessRuntime.adapter.clearHistory(); error = "Tanı geçmişi silindi; etkin kurtarma ve bekleme bilgisi korundu." }) { Text("Geçmişi sil") }
        }
    }
    diagnosticPreview?.let { report ->
        AlertDialog(onDismissRequest = { diagnosticPreview = null }, title = { Text("Yerel tanılama önizlemesi") },
            text = { Text(report.take(6000)) }, confirmButton = {
                TextButton(onClick = { diagnosticPreview = null; saveDiagnostics.launch("social-alpha-diagnostics.json") }) { Text("Dosyaya kaydet") }
            }, dismissButton = { TextButton(onClick = { diagnosticPreview = null }) { Text("Kapat") } })
    }
}

private fun problemLabel(value: String) = when (value) {
    "NETWORK_UNREACHABLE" -> "Site/uygulama açılmıyor"
    "AGE_OR_ID_VERIFICATION_REQUIRED" -> "Yaş veya kimlik doğrulaması isteniyor"
    "BOT_CHALLENGE" -> "Bot doğrulaması isteniyor"
    "MEDIA_PATH_FAILURE" -> "İçerik açılıyor ama medya çalışmıyor"
    "POSSIBLE_THROTTLING" -> "Yavaşlık"
    else -> "Sorun: emin değilim"
}
private fun finishLabel(value: String) = when (value) {
    "RESOLVED_USER_REPORTED" -> "Kullanıcı erişim bildirdi; çalışan deneme korunuyor."
    "RESOLVED_AUTO_OBSERVED" -> "Seçilen işlem otomatik testte çalıştı; kapsam yalnız test penceresi."
    "ALREADY_ACCESSIBLE" -> "Başlangıçta zaten açıktı; aday başarısı yazılmadı."
    "AUTO_UNAVAILABLE" -> "Bu hedef için otomatik içerik gözlemi yok; manuel devam edebilirsiniz."
    "AUTO_INCONCLUSIVE" -> "Otomatik sonuç belirsiz; ertelenen yöntemler manuel değerlendirilebilir."
    "CANDIDATES_EXHAUSTED" -> "Bu turdaki uygun yöntemler bitti."
    "NO_APPLICABLE_METHODS" -> "Bu kapsamda uygulanabilir yöntem bulunmadı."
    "PARTIAL_ACCEPTED" -> "Kısmi erişim kullanıcı tarafından kabul edildi."
    else -> "Deneme durduruldu."
}
