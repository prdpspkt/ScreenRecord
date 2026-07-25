package bid.prabidhibid.androidscreenrecorder

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that toggles screen recording. Tapping it while idle launches the app to
 * run the capture-consent flow; tapping it while recording stops the service directly.
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenRecordService.isRunning) {
            val stop = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(stop)
        } else {
            val launch = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_RECORDING, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch)
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val running = ScreenRecordService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) "Recording" else "Record screen"
        tile.updateTile()
    }
}
