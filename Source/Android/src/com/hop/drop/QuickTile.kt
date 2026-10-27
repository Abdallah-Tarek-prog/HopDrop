package com.hop.drop

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The "HopDrop" tile in Quick Settings: pull down, tap it, and a small sheet sends the clipboard, the latest photo or
 * chosen files to a paired device (QuickSendActivity).
 */
class QuickTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val devices = (application as HopApp).book().all()
        tile.state = if (devices.isEmpty()) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        tile.label = "HopDrop"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = Language.text(this, if (devices.isEmpty()) R.string.quick_pair_tile else R.string.quick_send)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, QuickSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
