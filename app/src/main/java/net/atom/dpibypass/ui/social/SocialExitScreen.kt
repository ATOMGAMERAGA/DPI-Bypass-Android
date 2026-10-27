package net.atom.dpibypass.ui.social

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.social.Countries
import net.atom.dpibypass.social.ExitPlatform
import net.atom.dpibypass.social.ExitProbe
import net.atom.dpibypass.social.ExitServer
import net.atom.dpibypass.social.ExitStatus
import net.atom.dpibypass.ui.AppViewModel
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScreen
import net.atom.dpibypass.ui.design.AppTextField
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.CardTone
import net.atom.dpibypass.ui.design.IconBubble
import net.atom.dpibypass.ui.design.ListRow
import net.atom.dpibypass.ui.design.RowDivider
import net.atom.dpibypass.ui.design.SectionHeader
import net.atom.dpibypass.ui.design.SelectionCheck
import net.atom.dpibypass.ui.design.StatTile
import net.atom.dpibypass.ui.design.SwitchRow
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace
import net.atom.dpibypass.ui.design.rememberHaptics
import net.atom.dpibypass.ui.theme.LocalStateColors
import net.atom.dpibypass.vpn.AppScope

// ---------------------------------------------------------------------------
// Sosyal Erişim — e-Devlet doğrulaması olmadan sosyal medya.
//
// Ekran tek bir soruyu yanıtlayacak biçimde dizilir: "Instagram (ya da X, TikTok…)
// beni Türkiye'den bağlanıyor sanıyor mu?" Üstteki durum kartı bunun cevabıdır;
// altındaki üç numaralı adım (platformlar → sunucu → bağlan) o cevabı "hayır"a
// çevirmek için gerekenlerin tamamıdır. Eski DPI deneme rehberi, platform hiç
// açılmıyorsa diye en altta "Bağlantı sorunu" olarak durur.
// ---------------------------------------------------------------------------

/** Durum kartının tek cümlelik hâli; saf, birim testli. */
enum class ExitHeadline { OFF, NEED_PLATFORMS, NEED_SERVER, NEED_CONNECT, APPLYING, CHECKING, WORKING, SERVER_IN_TURKEY, SERVER_DOWN }

object ExitView {
    fun headline(
        settings: Settings,
        connection: ConnectionState,
        status: ExitStatus,
        probe: ExitProbe.Result?,
    ): ExitHeadline {
        if (!settings.exitEnabled) return ExitHeadline.OFF
        val hasTargets = settings.exitPlatforms.isNotEmpty() || settings.exitExtraDomains.isNotBlank()
        if (!hasTargets) return ExitHeadline.NEED_PLATFORMS
        if (ExitServer.parse(settings.exitServer) == null) return ExitHeadline.NEED_SERVER
        if (connection != ConnectionState.Connected) return ExitHeadline.NEED_CONNECT
        if (!status.running) return ExitHeadline.APPLYING
        return when {
            probe == null -> ExitHeadline.CHECKING
            !probe.ok -> ExitHeadline.SERVER_DOWN
            probe.inTurkey -> ExitHeadline.SERVER_IN_TURKEY
            else -> ExitHeadline.WORKING
        }
    }

    /** Seçili platformların kurulu olup tünel kapsamı DIŞINDA kalan paketleri. */
    fun outOfScope(settings: Settings, installed: Set<String>): Set<String> =
        settings.exitPlatforms.mapNotNull(ExitPlatform::byId).flatMap { it.packages }
            .filter { it in installed && !AppScope.covers(settings.appFilterMode, settings.selectedApps, settings.vodafoneUnlimitedMode, it) }
            .toSet()

    fun installedPackages(context: Context): Set<String> = ExitPlatform.entries.flatMap { it.packages }
        .filter { runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess }.toSet()
}

@Composable
fun SocialExitScreen(
    viewModel: AppViewModel,
    onConnect: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val status by viewModel.exitStatus.collectAsStateWithLifecycle()
    val probe by viewModel.exitProbe.collectAsStateWithLifecycle()
    val checking by viewModel.exitChecking.collectAsStateWithLifecycle()
    SocialExitContent(settings, connection, status, probe, checking, viewModel, onConnect, onOpenGuide)
}

/** Durumu dışarıdan alan gövde: ekran görüntüsü testleri ayar deposu olmadan çizer. */
@Composable
fun SocialExitContent(
    settings: Settings,
    connection: ConnectionState,
    status: ExitStatus,
    probe: ExitProbe.Result?,
    checking: Boolean,
    viewModel: AppViewModel,
    onConnect: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val installed by produceState(emptySet<String>()) { value = withContext(Dispatchers.IO) { ExitView.installedPackages(context) } }

    // Alan, DataStore okununca bir kez doldurulur; sonra kullanıcının yazdığı esas alınır.
    var serverText by remember { mutableStateOf("") }
    var extraText by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(settings.loaded) {
        if (settings.loaded && !seeded) {
            serverText = settings.exitServer
            extraText = settings.exitExtraDomains
            seeded = true
        }
    }
    val parsed = ExitServer.parse(serverText)
    val headline = ExitView.headline(settings, connection, status, probe)
    val outOfScope = ExitView.outOfScope(settings, installed)

    AppScreen(
        title = "Sosyal Erişim",
        subtitle = "Instagram, X, TikTok ve diğerlerini e-Devlet kimlik doğrulaması istenmeden kullan.",
    ) {
        HeroCard(
            headline = headline,
            settings = settings,
            status = status,
            probe = probe,
            onToggle = { on ->
                haptics.confirm()
                // İlk açılışta boş liste kalmasın: en çok sorulan platformlar önerilir.
                if (on && settings.exitPlatforms.isEmpty()) {
                    listOf(ExitPlatform.INSTAGRAM, ExitPlatform.X, ExitPlatform.TIKTOK, ExitPlatform.YOUTUBE, ExitPlatform.FACEBOOK)
                        .forEach { viewModel.setExitPlatform(it.id, true) }
                }
                viewModel.setExitEnabled(on)
            },
            onConnect = onConnect,
        )

        SectionHeader("Nasıl çalışır")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Platformlar kimlik doğrulamasını, bağlantının geldiği ülkeye bakarak ister. İnternetin Türkiye'den " +
                        "geliyorsa e-Devlet ekranı çıkar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                RouteDiagram(probe?.takeIf { it.ok }?.countryCode)
                Text(
                    "Bu yüzden yalnızca seçtiğin platformların trafiği yurt dışındaki bir sunucudan çıkar; platform seni o " +
                        "ülkeden bağlanıyor görür. Bankacılık, e-Devlet, oyunlar ve diğer her şey eskisi gibi doğrudan gider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 1. Platformlar ----
        SectionHeader("1 · Platformları seç")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Sabit iki sütun: her kutu aynı genişlikte, uzun adlar alt satıra iner.
                ExitPlatform.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            PlatformTile(
                                platform = p,
                                selected = p.id in settings.exitPlatforms,
                                installed = p.packages.any { it in installed },
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptics.select()
                                    viewModel.setExitPlatform(p.id, p.id !in settings.exitPlatforms)
                                },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                settings.exitPlatforms.mapNotNull(ExitPlatform::byId).filter { it.note.isNotEmpty() }.forEach {
                    Text("${it.displayName}: ${it.note}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(outOfScope.isNotEmpty() && settings.exitEnabled) {
                Column {
                    RowDivider()
                    ListRow(
                        title = "Bazı uygulamalar tünelin dışında",
                        subtitle = "Uygulama kapsamın (" + scopeName(settings.appFilterMode) + ") bu platformların uygulamalarını " +
                            "tünele almıyor; trafikleri doğrudan Türkiye'den gider. Dokun: tünele al.",
                        icon = Icons.Rounded.Warning,
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = { viewModel.bringIntoScope(outOfScope) },
                        modifier = Modifier.testTag("exit-scope-fix"),
                    )
                }
            }
        }

        // ---- 2. Sunucu ----
        SectionHeader("2 · Yurt dışı sunucu")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Türkiye dışında çalışan bir SOCKS5 ya da HTTP proxy sunucusunun adresi. Kendi sunucun (ör. Almanya'da bir VPS) " +
                        "ya da güvendiğin bir proxy sağlayıcısı olabilir; bu uygulama sunucu vermez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextField(
                    value = serverText,
                    onValueChange = {
                        serverText = it
                        viewModel.setExitServer(it)
                    },
                    placeholder = "socks5://kullanıcı:parola@sunucu:1080",
                    leadingIcon = Icons.Rounded.Dns,
                    monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.testTag("exit-server"),
                )
                when {
                    serverText.isBlank() -> Text(
                        "Biçimler: socks5://sunucu:port · http://kullanıcı:parola@sunucu:port · sunucu:port:kullanıcı:parola",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    parsed == null -> Text(
                        "Adres anlaşılamadı. Örnek: socks5://ali:gizli@85.10.20.30:1080",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(parsed.display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppButton(
                    text = if (checking) "Sunucu deneniyor…" else "Sunucuyu test et",
                    onClick = { haptics.tick(); viewModel.checkExitServer(serverText) },
                    tone = ButtonTone.Tonal,
                    icon = Icons.Rounded.Refresh,
                    enabled = parsed != null && !checking,
                    modifier = Modifier.fillMaxWidth().testTag("exit-test"),
                )
                probe?.let { ProbeResult(it) }
            }
        }

        // ---- 3. Bağlan ----
        SectionHeader("3 · Bağlan ve platformu aç")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (connection == ConnectionState.Connected) "Tünel açık. Platform uygulamasını tamamen kapatıp yeniden aç; " +
                        "açık kalan eski bağlantılar Türkiye üzerinden kurulmuştu."
                    else "Yurt dışı çıkış tünel açıkken çalışır. Bağlandıktan sonra platform uygulamasını kapatıp yeniden aç.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (connection != ConnectionState.Connected) {
                    AppButton(
                        text = if (connection == ConnectionState.Connecting || connection == ConnectionState.Testing) "Bağlanıyor…" else "Bağlan",
                        onClick = onConnect,
                        icon = Icons.Rounded.Shield,
                        enabled = connection != ConnectionState.Connecting && connection != ConnectionState.Testing,
                        modifier = Modifier.fillMaxWidth().testTag("exit-connect"),
                    )
                }
            }
        }

        // ---- Dürüst sınırlar ----
        SectionHeader("Doğrulama yine çıkarsa")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                title = "Uygulamaya konum izni verme",
                subtitle = "GPS konumu IP'den bağımsızdır. Ayarlar → Uygulamalar → (platform) → İzinler → Konum: İzin verme.",
                icon = Icons.Rounded.LocationOff,
            )
            RowDivider()
            ListRow(
                title = "Oturumu kapatıp yeniden aç",
                subtitle = "Platform ülkeni oturum açarken kaydeder. Tünel açıkken çıkış yap, uygulamanın önbelleğini temizle, tekrar gir.",
                icon = Icons.AutoMirrored.Rounded.Logout,
            )
            RowDivider()
            ListRow(
                title = "Hesap Türkiye'ye bağlıysa",
                subtitle = "Daha önce doğrulama başlattıysan, hesap +90 numaraya bağlıysa ya da SIM/uygulama mağazası bölgen " +
                    "Türkiye ise platform IP'ye bakmadan da isteyebilir. Bunu hiçbir bağlantı ayarı değiştiremez.",
                icon = Icons.Rounded.SimCard,
            )
            RowDivider()
            ListRow(
                title = "Sunucu sahibi neyi görür?",
                subtitle = "Hangi platforma bağlandığını görür; mesaj ve şifre gibi içerik TLS ile şifreli kalır. " +
                    "Güvenmediğin ücretsiz proxy'leri kullanma.",
                icon = Icons.Rounded.Lock,
            )
        }

        SectionHeader("Ek alan adları")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Listede olmayan bir site için alan adlarını virgülle yaz. Alt alan adları da dahil olur.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextField(
                    value = extraText,
                    onValueChange = { extraText = it; viewModel.setExitExtraDomains(it) },
                    placeholder = "ornek.com, cdn.ornek.net",
                    leadingIcon = Icons.Rounded.Language,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }

        SectionHeader("Platform hiç açılmıyor mu?")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                title = "Bağlantı sorunu rehberi",
                subtitle = "Uygulama erişim engeli yüzünden hiç yüklenmiyorsa (e-Devlet ekranı değil, boş sayfa ya da hata), " +
                    "adım adım rehber bu ağda çalışan DPI ayarını bulur.",
                icon = Icons.Rounded.Build,
                onClick = onOpenGuide,
                modifier = Modifier.testTag("exit-open-guide"),
                trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        VSpace(12.dp)
    }
}

private fun scopeName(mode: AppFilterMode) = when (mode) {
    AppFilterMode.All -> "Tümü"
    AppFilterMode.Include -> "Yalnızca seçili"
    AppFilterMode.Exclude -> "Seçili hariç"
}

@Composable
private fun HeroCard(
    headline: ExitHeadline,
    settings: Settings,
    status: ExitStatus,
    probe: ExitProbe.Result?,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val good = LocalStateColors.current.connected
    val (title, body, tint, icon) = when (headline) {
        ExitHeadline.OFF -> Quad("Kapalı", "Platformlar seni Türkiye'den bağlanıyor görür ve e-Devlet doğrulaması isteyebilir.",
            scheme.onSurfaceVariant, Icons.Rounded.Badge)
        ExitHeadline.NEED_PLATFORMS -> Quad("Platform seç", "Hangi uygulamaların yurt dışından bağlanacağını aşağıda seç.", scheme.primary, Icons.Rounded.Public)
        ExitHeadline.NEED_SERVER -> Quad("Sunucu gerekli", "Yurt dışındaki proxy sunucunun adresini 2. adımda yaz.", scheme.primary, Icons.Rounded.Dns)
        ExitHeadline.NEED_CONNECT -> Quad("Hazır — bağlan", "Ayarlar tamam. Tünel açılınca seçili platformlar yurt dışından çıkar.", scheme.primary, Icons.Rounded.Shield)
        ExitHeadline.APPLYING -> Quad("Uygulanıyor…", "Tünel yeni ayarla yeniden kuruluyor.", scheme.primary, Icons.Rounded.Refresh)
        ExitHeadline.CHECKING -> Quad("Çıkış ülkesi ölçülüyor…", "Sunucu üzerinden konum kontrol ediliyor.", scheme.primary, Icons.Rounded.Refresh)
        ExitHeadline.WORKING -> {
            val cc = probe?.countryCode.orEmpty()
            Quad("${Countries.flag(cc)} ${Countries.name(cc)} üzerinden".trim(),
                "Seçili platformlara göre bulunduğun ülke: ${Countries.name(cc)}. Türkiye'ye özel e-Devlet doğrulaması bu bağlantılarda tetiklenmez.",
                good, Icons.Rounded.CheckCircle)
        }
        ExitHeadline.SERVER_IN_TURKEY -> Quad("Sunucu Türkiye'de",
            "Bu sunucu Türkiye IP'si veriyor; platform yine doğrulama ister. Yurt dışında bir sunucu kullan.", scheme.error, Icons.Rounded.Warning)
        ExitHeadline.SERVER_DOWN -> Quad("Sunucuya ulaşılamıyor",
            (probe?.error?.ifBlank { null } ?: "Sunucu yanıt vermiyor") + ". Platform bağlantıları güvenlik için kesildi; Türkiye'den gönderilmedi.",
            scheme.error, Icons.Rounded.ErrorOutline)
    }
    val tone = when (headline) {
        ExitHeadline.WORKING -> CardTone.Accent
        ExitHeadline.SERVER_DOWN, ExitHeadline.SERVER_IN_TURKEY -> CardTone.Danger
        else -> CardTone.Raised
    }
    AppCard(modifier = Modifier.fillMaxWidth().testTag("exit-hero"), tone = tone) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon, tint = tint, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("YURT DIŞI ÇIKIŞ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("exit-headline"))
                }
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (headline == ExitHeadline.WORKING || (status.running && status.viaExit + status.failures > 0)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Yurt dışı", "${status.viaExit}", modifier = Modifier.weight(1f), valueColor = good)
                    StatTile("Kesilen", "${status.failures}", modifier = Modifier.weight(1f),
                        valueColor = if (status.failures > 0) scheme.error else scheme.onSurface)
                }
                Text("Diğer uygulama ve siteler (yerel yoldan): ${status.direct} bağlantı", style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                if (status.lastExitHost.isNotEmpty()) {
                    Text("Son yurt dışı bağlantı: ${status.lastExitHost}", style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (status.lastError.isNotEmpty()) {
                    Text("Son hata: ${status.lastError}", style = MaterialTheme.typography.bodySmall, color = scheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        RowDivider()
        SwitchRow(
            title = "Yurt dışı çıkış",
            subtitle = if (settings.exitEnabled) "${settings.exitPlatforms.size} platform seçili" else "Aç: seçili platformlar yurt dışından bağlansın",
            icon = Icons.Rounded.Public,
            checked = settings.exitEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("exit-switch"),
        )
        if (headline == ExitHeadline.NEED_CONNECT) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                AppButton("Bağlan", onClick = onConnect, icon = Icons.Rounded.Shield, modifier = Modifier.fillMaxWidth().testTag("exit-hero-connect"))
            }
        }
    }
}

private data class Quad(val title: String, val body: String, val tint: Color, val icon: ImageVector)

/** Telefon → sunucu (ülke) → platform: trafiğin yolu tek bakışta. */
@Composable
private fun RouteDiagram(countryCode: String?) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        RouteNode(Icons.Rounded.PhoneAndroid, "Telefonun", "Türkiye")
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        RouteNode(Icons.Rounded.Dns, "Sunucu", countryCode?.let { "${Countries.flag(it)} ${Countries.name(it)}" } ?: "Yurt dışı")
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        RouteNode(Icons.Rounded.Public, "Platform", "Kimlik sormaz")
    }
}

@Composable
private fun RouteNode(icon: ImageVector, title: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        IconBubble(icon, size = 40.dp)
        Spacer(Modifier.size(4.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlatformTile(platform: ExitPlatform, selected: Boolean, installed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
            .testTag("exit-platform-${platform.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(platform.displayName, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (installed) "Yüklü" else "Yüklü değil", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        SelectionCheck(selected)
    }
}

@Composable
private fun ProbeResult(result: ExitProbe.Result) {
    val scheme = MaterialTheme.colorScheme
    val good = LocalStateColors.current.connected
    val (text, color) = when {
        !result.ok -> "Olmadı: ${result.error}" to scheme.error
        result.inTurkey -> "${Countries.flag(result.countryCode)} Bu sunucu Türkiye'den çıkıyor (${result.ip}). Doğrulamayı aşmaz." to scheme.error
        else -> "${Countries.flag(result.countryCode)} Çalışıyor: ${Countries.name(result.countryCode)} · ${result.ip} · ${result.latencyMs} ms" to good
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("exit-probe")) {
        TagBadge(if (result.ok && !result.inTurkey) "TAMAM" else "SORUN", color = color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
    }
}
