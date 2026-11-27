package dev.tapdrop

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Buttons on AstraDrop's notifications: Copy a received text, Allow or Block a new device. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val notif = intent.getIntExtra(EXTRA_NOTIF, 0)
        when (intent.action) {
            COPY -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AstraDrop", text))
            }
            ALLOW -> intent.getStringExtra(EXTRA_DEVICE)?.let { Devices.approve(it) }
            BLOCK -> intent.getStringExtra(EXTRA_DEVICE)?.let { Devices.remove(it) }
            else -> return
        }
        if (notif != 0) ctx.getSystemService(NotificationManager::class.java).cancel(notif)
    }

    companion object {
        const val COPY = "dev.tapdrop.COPY"
        const val ALLOW = "dev.tapdrop.ALLOW"
        const val BLOCK = "dev.tapdrop.BLOCK"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_NOTIF = "notif"
    }
}

/** The first web link in [text], if any. */
fun linkIn(text: String): String? = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':')
