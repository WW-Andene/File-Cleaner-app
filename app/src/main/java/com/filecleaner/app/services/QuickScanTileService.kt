package com.filecleaner.app.services

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.filecleaner.app.MainActivity

/**
 * Quick Settings tile — tap to open the app and start a scan.
 * Shows storage usage percentage on the tile label.
 */
class QuickScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "com.filecleaner.app.ACTION_QUICK_SCAN"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            ))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Raccoon Scan"

        try {
            val statFs = android.os.StatFs(
                android.os.Environment.getExternalStorageDirectory().absolutePath)
            val usedPct = ((statFs.totalBytes - statFs.freeBytes) * 100 / statFs.totalBytes).toInt()
            tile.subtitle = "$usedPct% used"
            tile.state = if (usedPct > 90) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        } catch (_: Exception) {}

        tile.updateTile()
    }
}
