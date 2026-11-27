package dev.tapdrop

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: opens AstraDrop ready to share. With Auto mode it also turns on NFC (as
 * opening AstraDrop always does) and the phone's hotspot, so the iPhone can tap right away.
 */
class QuickTile : TileService() {
    private val listener: () -> Unit = { update() }

    override fun onStartListening() {
        super.onStartListening()
        ShareState.addListener(listener)
        update()
    }

    override fun onStopListening() {
        ShareState.removeListener(listener)
        super.onStopListening()
    }

    private fun update() {
        val t = qsTile ?: return
        t.label = "AstraDrop"
        t.subtitle = I18n.t(if (ShareState.running) "Sharing" else "Tap to share")
        t.state = if (ShareState.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_QUICK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 5, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
