package net.atom.dpibypass.ui.vpn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.atom.dpibypass.ui.AppViewModel

/**
 * Bağlanırken başka bir VPN açıksa gösterilir. Android aynı anda tek VPN
 * çalıştırır; DPI Bypass açılınca diğeri kapanır. VPN Tüneli kuruluysa bu
 * sorun değildir (seçili uygulamalar zaten DPI Bypass içindeki VPN'den gider);
 * değilse kurulum önerilir.
 */
@Composable
fun VpnConflictDialog(
    conflict: AppViewModel.VpnConflict,
    onConnect: () -> Unit,
    onSetup: () -> Unit,
    onDismiss: () -> Unit,
    onNeverAsk: () -> Unit,
) {
    val name = when (conflict.apps.size) {
        0 -> "Başka bir VPN"
        1 -> conflict.apps.first()
        else -> conflict.apps.joinToString(" ya da ")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vpn-conflict"),
        title = { Text(if (conflict.tunnelReady) "$name açık görünüyor" else "$name açık görünüyor — ikisi birden çalışmaz") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android aynı anda yalnızca bir VPN çalıştırır. DPI Bypass bağlanınca $name kapanır.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (conflict.tunnelReady) "Sorun değil: VPN Tüneli kurulu. Seçtiğin uygulamalar DPI Bypass'ın içindeki VPN'den, " +
                        "diğerleri DPI motorundan gidecek."
                    else "İkisini birlikte kullanmak için VPN'in WireGuard dosyasını DPI Bypass'a ekle (VPN Tüneli): seçtiğin " +
                        "uygulamalar VPN'den, diğerleri DPI motorundan gider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (conflict.tunnelReady) TextButton(onClick = onConnect, modifier = Modifier.testTag("vpn-conflict-connect")) { Text("Bağlan") }
            else TextButton(onClick = onSetup, modifier = Modifier.testTag("vpn-conflict-setup")) { Text("VPN Tünelini kur") }
        },
        dismissButton = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (!conflict.tunnelReady) TextButton(onClick = onConnect, modifier = Modifier.testTag("vpn-conflict-anyway")) { Text("Yine de bağlan") }
                TextButton(onClick = onNeverAsk) { Text("Bir daha sorma, bağlan") }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        },
    )
}
