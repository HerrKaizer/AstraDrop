package dev.tapdrop

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * What's moving right now, from the server's side, and who is looking (see [Devices]). The page
 * asks the phone for changes every 2.5 s while it's on screen, so "connected" = a request in the
 * last few seconds. Also remembers hotspots an iPhone has joined, so it isn't shown their code again.
 */
object Visits {
    /** Last time anything happened (request or transfer): the idle auto-stop counts from here. */
    @Volatile var lastActivity = SystemClock.elapsedRealtime(); private set
    /** Last time bytes moved or a transfer ended. */
    @Volatile var lastTransfer = 0L; private set

    class Transfer(val up: Boolean, val name: String, val total: Long, val dev: Devices.Device?) {
        @Volatile var done = 0L
    }

    private val active = ConcurrentHashMap<Long, Transfer>()
    @Volatile private var lastPost = 0L

    fun connected() = Devices.connected().isNotEmpty()
    fun transfers(): List<Transfer> = active.values.toList()
    fun busy() = active.isNotEmpty()

    fun touch() { lastActivity = SystemClock.elapsedRealtime() }

    /** A request from [dev] (who has the right cookie), coming from [clientIp]. */
    fun seen(ctx: Context, dev: Devices.Device, clientIp: String?) {
        val was = connected()
        val now = SystemClock.elapsedRealtime()
        dev.lastSeen = now
        dev.ip = clientIp
        lastActivity = now
        if (clientIp != null) rememberHotspot(ctx, clientIp)
        if (!was) post(force = true)
    }

    fun begin(up: Boolean, name: String, total: Long, dev: Devices.Device? = null): Transfer {
        val t = Transfer(up, name, total, dev)
        active[Thread.currentThread().id] = t
        touch()
        post(force = true)
        return t
    }

    fun progress(t: Transfer, done: Long) {
        t.done = done
        lastActivity = SystemClock.elapsedRealtime()
        lastTransfer = lastActivity
        post(force = false)
    }

    fun end(t: Transfer) {
        active.values.remove(t)
        lastTransfer = SystemClock.elapsedRealtime()
        touch()
        post(force = true)
    }

    /** At most ~4 screen updates a second while bytes move. */
    private fun post(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPost < 250) return
        lastPost = now
        ShareState.notifyChanged()
    }

    // ------------------------------------------------------------------ hotspots the iPhone knows

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tapdrop", Context.MODE_PRIVATE)

    /** The iPhone reached us through the phone's own hotspot: it has joined (and remembers) it. */
    private fun rememberHotspot(ctx: Context, clientIp: String) {
        val hot = ShareState.localAddresses().firstOrNull { it.kind == "Hotspot" } ?: return
        if (clientIp.substringBeforeLast('.') != hot.ip.substringBeforeLast('.')) return
        val ssid = prefs(ctx).getString("ssid", null)?.takeIf { it.isNotBlank() } ?: return
        if (!joined(ctx, ssid)) prefs(ctx).edit().putBoolean("joined_$ssid", true).apply()
    }

    fun joined(ctx: Context, ssid: String) = prefs(ctx).getBoolean("joined_$ssid", false)
    fun forgetJoined(ctx: Context, ssid: String) { prefs(ctx).edit().remove("joined_$ssid").apply() }
}
