package net.atom.dpibypass.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.atom.dpibypass.R
import net.atom.dpibypass.data.ConnectionState
import net.atom.dpibypass.ui.MainActivity
import net.atom.dpibypass.vpn.ServiceController
import net.atom.dpibypass.vpn.VpnState

/**
 * Hızlı Panel (Quick Settings) tile — tek dokunuşla bağlan/kes.
 *
 * - İkon beyaz/tek renk (ic_qs_tile).
 * - Servis arkada ölmüşse dokunmak onu yeniden başlatır (ServiceController).
 * - VPN izni yoksa uygulamayı açar.
 */
class DpiTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(Dispatchers.Main)
        scope = newScope
        collectJob = newScope.launch {
            VpnState.state.collect { syncTile(it) }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_UNAVAILABLE) {
            openApp()
            return
        }
        // VPN izni gerekiyorsa uygulamayı aç.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        if (VpnState.isRunning()) {
            ServiceController.stop(this)
        } else {
            ServiceController.start(this)
        }
    }

    private fun syncTile(state: ConnectionState) {
        val tile = qsTile ?: return
        val profile = VpnState.profile.value
        when (state) {
            ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitleCompat(profile?.shortLabel() ?: getString(R.string.status_connected))
            }
            ConnectionState.Connecting, ConnectionState.Testing -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitleCompat(getString(R.string.status_connecting))
            }
            ConnectionState.Disconnected, ConnectionState.Failed -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitleCompat(getString(R.string.status_disconnected))
            }
        }
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }

    private fun Tile.subtitleCompat(text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            subtitle = text
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(
                this, 0, intent,
                // Değişmezlik son parametreyle (isMutable = false) istenir; sarmalayıcı
                // FLAG_IMMUTABLE'ı kendisi ekler. Bayrağı burada vermek sözleşmeye aykırıdır.
                PendingIntent.FLAG_UPDATE_CURRENT, false,
            ),
        )
    }
}
