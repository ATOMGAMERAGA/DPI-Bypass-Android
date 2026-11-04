package net.atom.dpibypass.ui.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import net.atom.dpibypass.privacy.Claim
import net.atom.dpibypass.privacy.Evidence
import net.atom.dpibypass.privacy.PRIVACY_CHECKED_ON
import net.atom.dpibypass.privacy.PlatformGuide
import net.atom.dpibypass.privacy.PlatformId
import net.atom.dpibypass.privacy.PrivacyCatalog
import net.atom.dpibypass.privacy.PrivacyLinks
import net.atom.dpibypass.privacy.SettingsPath
import net.atom.dpibypass.privacy.StatusRow
import net.atom.dpibypass.ui.design.AppButton
import net.atom.dpibypass.ui.design.AppCard
import net.atom.dpibypass.ui.design.AppScreen
import net.atom.dpibypass.ui.design.ButtonTone
import net.atom.dpibypass.ui.design.CardTone
import net.atom.dpibypass.ui.design.ListRow
import net.atom.dpibypass.ui.design.RowDivider
import net.atom.dpibypass.ui.design.SectionHeader
import net.atom.dpibypass.ui.design.TagBadge
import net.atom.dpibypass.ui.design.VSpace

// ---------------------------------------------------------------------------
// Gizlilik Yardımcısı.
//
// Yalnızca okunur bir rehberdir: hiçbir alan girdi almaz, hiçbir şey kaydedilmez,
// ağ isteği yapılmaz, VPN/tünel ayarına dokunulmaz. Tek dış eylem, katalogdaki
// resmî bir adresi [PrivacyLinks] üzerinden açmaktır. Platform seçimi yalnız ekran
// durumudur (yeniden çizimde korunur, diske yazılmaz).
// ---------------------------------------------------------------------------

@Composable
fun PrivacyHelperScreen(
    onBack: () -> Unit,
    openLink: ((String) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val open: (String) -> Boolean = openLink ?: { url -> PrivacyLinks.open(context, url) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var failedUrl by remember { mutableStateOf<String?>(null) }
    val selected = selectedName?.let { name -> PlatformId.entries.firstOrNull { it.name == name } }
    val tryOpen: (String) -> Unit = { url -> failedUrl = if (open(url)) null else url }

    BackHandler(enabled = selected != null) { selectedName = null }

    AppScreen(
        title = selected?.let { "Gizlilik · ${it.label}" } ?: "Gizlilik Yardımcısı",
        subtitle = "Yaş doğrulamada hangi veri kime gider? Kaynaklar $PRIVACY_CHECKED_ON tarihinde kontrol edildi.",
        actions = {
            if (selected != null) {
                IconButton(onClick = { selectedName = null }, modifier = Modifier.testTag("privacy-back")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Platform listesine dön")
                }
            }
            IconButton(onClick = onBack, modifier = Modifier.testTag("privacy-close")) {
                Icon(Icons.Rounded.Close, contentDescription = "Kapat")
            }
        },
    ) {
        failedUrl?.let { url ->
            AppCard(modifier = Modifier.fillMaxWidth().testTag("privacy-link-error"), tone = CardTone.Danger, contentPadding = CardPadding) {
                Text(
                    "Bağlantı açılamadı. Bu cihazda uygun tarayıcı ya da uygulama yok veya adres izinli listede değil. Adresi kendin yazabilirsin: $url",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            VSpace(8.dp)
        }
        if (selected == null) Overview(onSelect = { selectedName = it.name }, open = tryOpen)
        else PlatformDetail(PrivacyCatalog.platform(selected), open = tryOpen)
        VSpace(8.dp)
    }
}

private val CardPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)

@Composable
private fun Overview(onSelect: (PlatformId) -> Unit, open: (String) -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().testTag("privacy-intro"), tone = CardTone.Accent, contentPadding = CardPadding) {
        Text("Bu yardımcı ne yapar?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        VSpace(6.dp)
        Text(
            "Yalnız bu cihazda çalışır ve internete bağlanmaz. Kimlik bilgisi, şifre, yaş belirteci ya da oturum bilgisi istemez, " +
                "kaydetmez, loglamaz. VPN veya proxy kullanmaz, DPI bağlantını değiştirmez ve platformların yaş/erişim kontrollerini " +
                "atlatmaz. Bağlantılar yalnız resmî sayfaları açar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader("Önce bunlara dikkat")
    ClaimCard(PrivacyCatalog.minimalDataTips, open, tag = "privacy-tips")

    SectionHeader("Yönetmelik ne istiyor?")
    ClaimCard(PrivacyCatalog.regulation, open, tag = "privacy-regulation")

    SectionHeader("Platformlar")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        PrivacyCatalog.platforms.forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            val observed = p.rows.map { it.observed.evidence }.distinct()
            ListRow(
                title = p.id.label,
                subtitle = "Türkiye belirteç adımı: ${observed.joinToString(" / ") { it.label }} · veri akışı, itiraz yolu, gizlilik ayarları",
                icon = Icons.Rounded.PrivacyTip,
                onClick = { onSelect(p.id) },
                modifier = Modifier.testTag("privacy-platform-${p.id.name}"),
                trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

@Composable
private fun PlatformDetail(p: PlatformGuide, open: (String) -> Unit) {
    SectionHeader("Yönetmelik ne istiyor, platformda ne var?")
    p.rows.forEach { row ->
        StatusCard(p, row, open)
        VSpace(8.dp)
    }

    SectionHeader("Doğrulamada veri kime gider?")
    Text(
        "e-Devlet belirteci yolu (yönetmeliğe göre, her platformda aynı):",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
    ClaimCard(PrivacyCatalog.tokenFlow, open, tag = "privacy-tokenflow")
    VSpace(8.dp)
    Text(
        "${p.id.genitive} kendi yaş kontrolleri (platform isterse):",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
    ClaimCard(p.dataFlow, open, tag = "privacy-dataflow")

    SectionHeader("e-Devlet gerektirmeyen seçenek")
    ClaimCard(listOf(p.nonEdevletOption ?: p.nonEdevletNote), open, tag = "privacy-nonedevlet")

    SectionHeader("Yaş yanlış tespit edildiyse")
    ClaimCard(listOf(PrivacyCatalog.regulation.first { it.text.startsWith("İtiraz") }) + p.appeal, open, tag = "privacy-appeal")

    SectionHeader("Gizlilik ayarların")
    p.settings.forEachIndexed { i, s ->
        SettingsCard(p, i, s, open)
        VSpace(8.dp)
    }

    SectionHeader("Resmî yardım bağlantıları")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        p.helpLinks.forEachIndexed { i, src ->
            if (i > 0) RowDivider()
            ListRow(
                title = src.title,
                subtitle = "${hostOf(src.url)} · kontrol: ${src.checkedOn}",
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = { open(src.url) },
                modifier = Modifier.testTag("privacy-help-${p.id.name}-$i"),
            )
        }
    }
}

@Composable
private fun StatusCard(p: PlatformGuide, row: StatusRow, open: (String) -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth().testTag("privacy-row-${p.id.name}-${row.account.name}-${row.surface.name}"),
        contentPadding = CardPadding,
    ) {
        Text("${row.account.label} · ${row.surface.label}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        VSpace(8.dp)
        Label("Yönetmeliğin istediği")
        ClaimBody(row.required, open)
        VSpace(10.dp)
        Label("Platformda fiilen görülen")
        ClaimBody(row.observed, open)
    }
}

@Composable
private fun SettingsCard(p: PlatformGuide, index: Int, s: SettingsPath, open: (String) -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().testTag("privacy-settings-${p.id.name}-$index"), contentPadding = CardPadding) {
        Text(s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        VSpace(6.dp)
        Label("Mobil uygulamada (elle)")
        s.appSteps.forEachIndexed { i, step ->
            Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            "Menü adları uygulama sürümüne göre değişebilir. Güvenilir bir doğrudan uygulama bağlantısı olmadığı için uygulamayı kendin açıp bu adımları izle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.webUrl?.let { url ->
            VSpace(10.dp)
            AppButton(
                text = "Web ayar sayfasını aç",
                onClick = { open(url) },
                tone = ButtonTone.Tonal,
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.fillMaxWidth().testTag("privacy-web-settings-${p.id.name}-$index"),
            )
            Text(
                "${hostOf(url)} · oturum açmanı isteyebilir; şifreni yalnız bu resmî sayfaya gir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VSpace(8.dp)
        ClaimBody(s.evidence, open)
    }
}

@Composable
private fun ClaimCard(claims: List<Claim>, open: (String) -> Unit, tag: String) {
    AppCard(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        claims.forEachIndexed { i, c ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) { ClaimBody(c, open) }
        }
    }
}

@Composable
private fun ClaimBody(c: Claim, open: (String) -> Unit) {
    Text(c.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    VSpace(6.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TagBadge(c.evidence.label, color = evidenceColor(c.evidence))
    }
    c.sources.forEach { src ->
        Text(
            text = "Kaynak: ${src.title} · kontrol: ${src.checkedOn}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(role = Role.Button, onClickLabel = "Kaynağı aç") { open(src.url) },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VSpace(2.dp)
}

@Composable
private fun evidenceColor(e: Evidence): Color = when (e) {
    Evidence.VERIFIED -> MaterialTheme.colorScheme.primary
    Evidence.PARTIAL -> MaterialTheme.colorScheme.tertiary
    Evidence.UNVERIFIED -> MaterialTheme.colorScheme.error
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url
