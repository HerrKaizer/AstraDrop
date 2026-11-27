package dev.tapdrop

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Process
import dev.tapdrop.engine.Engine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional "Auto mode" (needs the engine): NFC goes on when sharing starts and back off when it
 * stops or AstraDrop is closed, and the Hotspot chip can switch on the phone's own hotspot; each
 * goes back off only if AstraDrop was the one that turned it on. The engine also watches
 * AstraDrop's process, so both still go off if the system kills the app.
 */
object AutoNfc {
    private const val KEY = "auto_nfc"
    private val worker = Executors.newSingleThreadExecutor()   // in order: a quick stop-start must end with NFC on
    @Volatile private var weTurnedOn = false
    /** NFC is being switched on right now (the status card says so instead of asking). */
    @Volatile var switchingOn = false; private set
    @Volatile var lastStart: Engine.Start? = null; private set
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = android.os.Handler(android.os.Looper.getMainLooper()).post { listeners.forEach { it() } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tapdrop", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean(KEY, false)
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, on).apply()
        if (on) { if (ShareState.running) set(ctx, true) else ensureEngine(ctx) } else set(ctx, false, force = true)
    }

    /** Set up = paired once; whether the engine is up right now is [engineUp]. */
    fun setUp(ctx: Context) = Engine.isPaired(ctx)
    @Volatile var engineUp = false; private set

    /** Starts the engine in the background if Automatic NFC is on (e.g. when AstraDrop opens). */
    fun ensureEngine(ctx: Context) {
        if (!enabled(ctx) || !setUp(ctx)) return
        val app = ctx.applicationContext
        worker.execute { startEngine(app) }
    }

    private fun startEngine(app: Context): Boolean {
        val r = Engine.start(app)
        lastStart = r
        engineUp = r == Engine.Start.OK
        changed()
        return engineUp
    }

    /** Sharing started ([on]) or stopped. */
    fun set(ctx: Context, on: Boolean, force: Boolean = false) {
        if (!force && !enabled(ctx)) return
        val app = ctx.applicationContext
        worker.execute {
            if (NfcAdapter.getDefaultAdapter(app) == null) return@execute
            if (on) {
                if (!ShareState.running || !startEngine(app)) return@execute
                switchingOn = true; changed()
                val r = try { Engine.cmd(app, "NFC 1", 15_000) } finally { switchingOn = false; changed() }
                if (r == "switched") {
                    weTurnedOn = true
                    Engine.cmd(app, "WATCH ${Process.myPid()} ${if (solCubeInstalled(app)) 1 else 0}")
                    pill(app, "NFC on")
                }
            } else {
                Engine.cmd(app, "UNWATCH")
                hotspotOffLocked(app)
                if (!weTurnedOn) return@execute
                weTurnedOn = false
                if (Engine.cmd(app, "NFC 0", 15_000) == "switched") pill(app, "NFC off")
            }
        }
    }

    /**
     * The Tap tile: NFC on or off right now, by hand. Switched on this way it still goes back
     * off when AstraDrop closes; switched off, AstraDrop leaves it off until the next start.
     */
    fun nfcNow(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        worker.execute {
            if (!startEngine(app)) { changed(); return@execute }
            if (on) { switchingOn = true; changed() }
            val r = try { Engine.cmd(app, if (on) "NFC 1" else "NFC 0", 15_000) } finally { switchingOn = false; changed() }
            if (r == "switched") {
                weTurnedOn = on
                if (on) Engine.cmd(app, "WATCH ${Process.myPid()} ${if (solCubeInstalled(app)) 1 else 0}")
                pill(app, if (on) "NFC on" else "NFC off")
            }
        }
    }

    /** The connected Wi-Fi's name through the engine (apps need location access for it). Off the main thread. */
    fun wifiName(ctx: Context): String? {
        if (!engineUp) return null
        val r = Engine.cmd(ctx.applicationContext, "WIFINAME", 2_000) ?: return null
        return runCatching { String(java.util.Base64.getDecoder().decode(r)) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ hotspot

    /** The phone's own hotspot: name, password (sec 0 = open) and whether it's on. */
    data class Hotspot(val on: Boolean, val sec: Int, val ssid: String, val pass: String)

    @Volatile private var weTurnedHotspot = false
    @Volatile var hotspotBusy = false; private set

    /** Reads the hotspot's name and password through the engine (null without it). Off the main thread. */
    fun hotspotInfo(ctx: Context): Hotspot? {
        val r = Engine.cmd(ctx.applicationContext, "HOTINFO", 4_000) ?: return null
        val a = r.split(" ")
        if (a.size < 4) return null
        val dec = java.util.Base64.getDecoder()
        return runCatching {
            Hotspot(a[0] == "on", a[1].toInt(), String(dec.decode(a[2])), String(dec.decode(a[3])))
        }.getOrNull()
    }

    /**
     * Switches the phone's own hotspot on through the engine; [done] gets its details on the main
     * thread, or null with a reason. It goes back off when sharing stops or AstraDrop closes.
     */
    fun hotspotOn(ctx: Context, done: (Hotspot?, String?) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        hotspotBusy = true; changed()
        worker.execute {
            var info: Hotspot? = null
            var err: String? = null
            try {
                if (!startEngine(app)) { err = I18n.t("Auto mode isn't running right now"); return@execute }
                val r = Engine.cmd(app, "HOTSPOT 1", 15_000)
                when {
                    r == "switched" -> {
                        weTurnedHotspot = true
                        Engine.cmd(app, "WATCH ${Process.myPid()} ${if (solCubeInstalled(app)) 1 else 0}")
                    }
                    r == "same" -> {}
                    else -> err = I18n.t("Android didn't turn the hotspot on") + (r?.removePrefix("failed")?.let { " ($it)" } ?: "")
                }
                if (err == null) info = hotspotInfo(app)
            } finally {
                hotspotBusy = false
                main.post { done(info, err); changed() }
            }
        }
    }

    /** Back to Wi-Fi: the hotspot goes off now, whoever switched it on. [done] runs on the main thread. */
    fun hotspotOff(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        worker.execute {
            weTurnedHotspot = false
            val r = if (startEngine(app)) Engine.cmd(app, "HOTSPOT 0", 10_000) else null
            if (r == "switched") pill(app, "Hotspot off")
            main.post { done(); changed() }
        }
    }

    private fun hotspotOffLocked(app: Context) {
        if (!weTurnedHotspot) return
        weTurnedHotspot = false
        Engine.cmd(app, "HOTSPOT 0", 10_000)
    }

    /**
     * AstraDrop is being closed: NFC off now (up to 4.5 s). Returns how long to keep the process
     * alive so the "NFC off" pill can finish (0 when SolCube draws it or nothing was switched).
     */
    fun offBlocking(ctx: Context): Long {
        if (!weTurnedOn && !weTurnedHotspot) return 0L
        val app = ctx.applicationContext
        return try {
            worker.submit<Long> {
                Engine.cmd(app, "UNWATCH")
                var keep = 0L
                if (weTurnedOn) {
                    weTurnedOn = false
                    if (Engine.cmd(app, "NFC 0", 4_000) == "switched" && pill(app, "NFC off")) keep = Island.SHOWN_MS
                }
                // the engine finishes switching the hotspot off on its own: just hand it the command
                if (weTurnedHotspot) { weTurnedHotspot = false; Engine.cmd(app, "HOTSPOT 0", 800) }
                keep
            }.get(4500, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { 0L }
    }

    /**
     * "NFC on / off" at the top of the screen. Our own accessibility service draws it when it's on;
     * otherwise SolCube's (same pill, usually already allowed) if installed; otherwise our overlay.
     * Returns true if AstraDrop drew it itself.
     */
    private fun pill(ctx: Context, text: String): Boolean {
        if (SolDropA11y.instance == null && solCubeInstalled(ctx) && Engine.cmd(ctx, "PILL ${I18n.t(text)}") != null) return false
        Island.show(ctx, I18n.t(text), true)
        return true
    }

    fun solCubeInstalled(ctx: Context) = try {
        ctx.packageManager.getPackageInfo("dev.suite", 0); true
    } catch (_: Exception) { false }
}
