package dev.tapdrop

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Process
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional: turns NFC on when sharing starts and off when it stops (or SolDrop is swiped away),
 * via OriginOS Settings' AppFunction (with `svc nfc` fallback) through Shizuku, backed by a
 * detached shell PID watchdog in case the OS force-kills SolDrop on swipe-away. Off by default.
 */
object NfcShizuku {
    const val KEY = "shizuku_nfc"
    private const val TAG = "TapDrop"
    private const val WATCH_FILE = "/data/local/tmp/tapdrop_nfc_watch"
    private const val WATCH_PID_FILE = "/data/local/tmp/tapdrop_nfc_watch.pid"
    private val worker = Executors.newSingleThreadExecutor()
    private val genCounter = AtomicLong(0L)

    @Volatile
    private var a11yTried = false

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    internal val WATCHDOG_LAUNCHER: String = """
        APP_PID="${'$'}1"
        GEN="${'$'}2"
        SOLCUBE="${'$'}3"
        BODY="${'$'}4"
        WATCH="$WATCH_FILE"
        PIDF="$WATCH_PID_FILE"
        if [ -r "${'$'}PIDF" ]; then
            IFS= read -r OLD < "${'$'}PIDF" || true
            if [ -n "${'$'}OLD" ] && [ -r "/proc/${'$'}OLD/cmdline" ]; then
                case "${'$'}(tr '\0' ' ' < "/proc/${'$'}OLD/cmdline" 2>/dev/null)" in
                    *tapdrop_nfc_watch*) kill "${'$'}OLD" 2>/dev/null || true ;;
                esac
            fi
        fi
        printf '%s %s\n' "${'$'}APP_PID" "${'$'}GEN" > "${'$'}WATCH.tmp" && mv -f "${'$'}WATCH.tmp" "${'$'}WATCH"
        setsid /system/bin/sh -c "${'$'}BODY" tapdrop_nfc_watch "${'$'}APP_PID" "${'$'}GEN" "${'$'}SOLCUBE" </dev/null >/dev/null 2>&1 &
    """.trimIndent().replace("\r\n", "\n").replace("\r", "")

    internal val WATCHDOG_BODY: String = """
        APP_PID="${'$'}1"
        GEN="${'$'}2"
        SOLCUBE="${'$'}3"
        WATCH="$WATCH_FILE"
        PIDF="$WATCH_PID_FILE"
        TOKEN="${'$'}APP_PID ${'$'}GEN"
        cur=""
        [ -r "${'$'}WATCH" ] && IFS= read -r cur < "${'$'}WATCH" || true
        [ "${'$'}cur" = "${'$'}TOKEN" ] || exit 0
        printf '%s\n' "${'$'}${'$'}" > "${'$'}PIDF"
        while [ -d "/proc/${'$'}APP_PID" ]; do
            cur=""
            [ -r "${'$'}WATCH" ] && IFS= read -r cur < "${'$'}WATCH" || true
            if [ "${'$'}cur" != "${'$'}TOKEN" ]; then
                [ -e "${'$'}WATCH" ] || rm -f "${'$'}PIDF"
                exit 0
            fi
            sleep 0.4
        done
        cur=""
        [ -r "${'$'}WATCH" ] && IFS= read -r cur < "${'$'}WATCH" || true
        if [ "${'$'}cur" != "${'$'}TOKEN" ]; then
            [ -e "${'$'}WATCH" ] || rm -f "${'$'}PIDF"
            exit 0
        fi
        rm -f "${'$'}WATCH" "${'$'}PIDF"
        nfc_state() {
            dumpsys nfc 2>/dev/null | while IFS= read -r l; do
                case "${'$'}l" in
                    *mState=*)
                        s="${'$'}{l#*mState=}"
                        s=${'$'}(echo ${'$'}s)
                        printf '%s' "${'$'}s"
                        break
                        ;;
                esac
            done
        }
        nfc_settled() {
            i=0
            s=""
            while [ "${'$'}i" -lt 35 ]; do
                s="${'$'}(nfc_state)"
                case "${'$'}s" in
                    on|off) printf '%s' "${'$'}s"; return 0 ;;
                esac
                sleep 0.15
                i=${'$'}((i + 1))
            done
            printf '%s' "${'$'}s"
        }
        st="${'$'}(nfc_settled)"
        if [ "${'$'}st" = "off" ]; then
            sleep 0.5
            st="${'$'}(nfc_settled)"
        fi
        if [ "${'$'}st" = "off" ]; then
            /system/bin/log -t TapDrop "watchdog: pid ${'$'}APP_PID gone, NFC already off"
            exit 0
        fi
        if [ -r "${'$'}WATCH" ]; then
            /system/bin/log -t TapDrop "watchdog: new session active, aborting NFC off"
            exit 0
        fi
        /system/bin/log -t TapDrop "watchdog: pid ${'$'}APP_PID gone (state=${'$'}st), turning NFC off"
        a=0
        while [ "${'$'}a" -lt 3 ]; do
            [ -r "${'$'}WATCH" ] && exit 0
            out="${'$'}(cmd app_function execute-app-function --package com.android.settings --function setDeviceStateItem --parameters '{"setDeviceStateItemParams":{"key":"vivo_nfc_settings_screen/nfc_toggle","value":"false","requestInitiatedWhileUnlocked":true}}' 2>&1 || true)"
            compact="${'$'}(printf '%s' "${'$'}out" | tr -d ' \t\r\n')"
            case "${'$'}compact" in
                *'"isSuccessful":[true]'*) ;;
                *) break ;;
            esac
            st="${'$'}(nfc_settled)"
            [ "${'$'}st" = "off" ] && break
            a=${'$'}((a + 1))
        done
        if [ "${'$'}st" != "off" ]; then
            [ -r "${'$'}WATCH" ] && exit 0
            /system/bin/log -t TapDrop "watchdog: using svc nfc disable"
            svc nfc disable >/dev/null 2>&1 || true
            st="${'$'}(nfc_settled)"
        fi
        if [ "${'$'}st" = "off" ]; then
            /system/bin/log -t TapDrop "watchdog: NFC turned off"
            if [ "${'$'}SOLCUBE" = "1" ] && [ ! -r "${'$'}WATCH" ]; then
                am broadcast -n dev.suite/.IslandReceiver --es text "NFC off" --ez ok true >/dev/null 2>&1 || true
            fi
        else
            /system/bin/log -t TapDrop "watchdog: failed to turn NFC off (state=${'$'}st)"
        fi
    """.trimIndent().replace("\r\n", "\n").replace("\r", "")

    private val DISARM_SCRIPT: String = """
        WATCH="$WATCH_FILE"
        PIDF="$WATCH_PID_FILE"
        rm -f "${'$'}WATCH"
        if [ -r "${'$'}PIDF" ]; then
            IFS= read -r OLD < "${'$'}PIDF" || true
            if [ -n "${'$'}OLD" ] && [ -r "/proc/${'$'}OLD/cmdline" ]; then
                case "${'$'}(tr '\0' ' ' < "/proc/${'$'}OLD/cmdline" 2>/dev/null)" in
                    *tapdrop_nfc_watch*) kill "${'$'}OLD" 2>/dev/null || true ;;
                esac
            fi
            rm -f "${'$'}PIDF"
        fi
    """.trimIndent().replace("\r\n", "\n").replace("\r", "")

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun enabled(ctx: Context) = ctx.getSharedPreferences("tapdrop", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("tapdrop", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    fun state(ctx: Context): State = try {
        when {
            !Shizuku.pingBinder() ->
                if (installed(ctx)) State.NOT_RUNNING else State.NOT_INSTALLED
            Shizuku.isPreV11() -> State.NO_PERMISSION
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    } catch (_: Throwable) {
        if (installed(ctx)) State.NOT_RUNNING else State.NOT_INSTALLED
    }

    private fun installed(ctx: Context) = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (_: Exception) { false }

    /** Turns NFC on or off if the toggle is on and Shizuku is ready. Runs on a background thread. */
    fun set(ctx: Context, on: Boolean) {
        if (!enabled(ctx)) return
        val app = ctx.applicationContext
        // Shizuku's binder reaches a fresh process a moment after it starts, and sharing starts as
        // SolDrop opens: wait for it instead of finding "not running" and doing nothing
        // one worker, in order: a quick stop-then-start must not end with NFC off
        whenBinder { if (state(app) == State.READY) worker.execute { run(app, on) } }
    }

    /** Disarms any active shell watchdog (e.g. when the user disables the NFC toggle). */
    fun disarm(ctx: Context) {
        val app = ctx.applicationContext
        whenBinder { if (state(app) == State.READY) worker.execute { disarmWatchdogShell() } }
    }

    private fun whenBinder(block: () -> Unit) {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { block(); return }
        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                block()
            }
        })
    }

    /**
     * Turns NFC off synchronously on the serialized Shizuku worker (up to 4.5 s): for
     * onTaskRemoved, where the process is about to die. Returns how long to keep the process alive
     * so an in-process pill can finish animating (0 if SolCube handles it or on timeout).
     */
    fun setBlockingOff(ctx: Context): Long {
        if (!enabled(ctx) || state(ctx) != State.READY) return 0L
        val app = ctx.applicationContext
        return try {
            val localPill = worker.submit<Boolean> { run(app, false) }.get(4500L, TimeUnit.MILLISECONDS)
            if (localPill) Island.SHOWN_MS else 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** Runs the full enable/disable step; returns true only when the local in-process pill was used. */
    private fun run(ctx: Context, on: Boolean): Boolean {
        if (NfcAdapter.getDefaultAdapter(ctx) == null) {
            if (!on) disarmWatchdogShell()
            return false
        }
        return if (on) {
            if (!ShareState.running) return false
            ensureA11y(ctx)
            armWatchdogShell(ctx)
            if (setNfcShell(true)) pill(ctx, "NFC on")
            false
        } else {
            ensureA11y(ctx)
            val switched = setNfcShell(false)
            val localPill = if (switched) pill(ctx, "NFC off") else false
            disarmWatchdogShell()
            localPill
        }
    }

    /** Extracts the raw `mState=` value from `dumpsys nfc` output (`on`, `off`, `turning on`, etc.). */
    internal fun parseNfcState(dumpsysOutput: String): String {
        for (line in dumpsysOutput.lineSequence()) {
            val idx = line.indexOf("mState=")
            if (idx >= 0) return line.substring(idx + 7).trim()
        }
        return ""
    }

    /** Returns true when `cmd app_function` JSON output reports `"isSuccessful": [true]`. */
    internal fun isAppFunctionSuccess(output: String?): Boolean =
        output != null && output.replace(Regex("\\s+"), "").contains("\"isSuccessful\":[true]")

    private fun nfcRawState(): String = parseNfcState(shellOut("dumpsys", "nfc").second)

    private fun nfcSettled(maxMs: Long = 5000L): String {
        val end = System.currentTimeMillis() + maxMs
        while (true) {
            val s = nfcRawState()
            if (s == "on" || s == "off" || System.currentTimeMillis() > end) return s
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return s
            }
        }
    }

    private fun setVivoNfc(on: Boolean): Boolean {
        val (code, out) = shellOut(
            "cmd", "app_function", "execute-app-function",
            "--package", "com.android.settings",
            "--function", "setDeviceStateItem",
            "--parameters",
            "{\"setDeviceStateItemParams\":{\"key\":\"vivo_nfc_settings_screen/nfc_toggle\",\"value\":\"$on\",\"requestInitiatedWhileUnlocked\":true}}",
        )
        if (code == 0 && isAppFunctionSuccess(out)) return true
        Log.i(TAG, "nfc $on: settings app_function unavailable (${out.trim().replace(Regex("\\s+"), " ")})")
        return false
    }

    private fun setNfcShell(on: Boolean): Boolean {
        val want = if (on) "on" else "off"
        if (nfcSettled(5000L) == want) return false
        for (attempt in 0 until 3) {
            if (!setVivoNfc(on)) break
            if (nfcSettled(5000L) == want) return true
            Log.i(TAG, "nfc $want: didn't stick, retrying (${attempt + 1})")
        }
        Log.i(TAG, "nfc $want: using svc")
        shell("svc", "nfc", if (on) "enable" else "disable")
        return nfcSettled(5000L) == want
    }

    private fun armWatchdogShell(ctx: Context) {
        val pid = Process.myPid().toString()
        val gen = "${System.currentTimeMillis()}_${genCounter.incrementAndGet()}"
        val useSolCube = if (solCubeInstalled(ctx)) "1" else "0"
        shellOut("/system/bin/sh", "-c", WATCHDOG_LAUNCHER, "_", pid, gen, useSolCube, WATCHDOG_BODY)
    }

    private fun disarmWatchdogShell() {
        shellOut("/system/bin/sh", "-c", DISARM_SCRIPT, "_")
    }

    /**
     * "NFC on / off" at the top of the screen. SolDrop's own service draws it when it's connected.
     * Otherwise SolCube draws it (if installed): its pill is the same and its accessibility service is
     * already on; its IslandReceiver only takes broadcasts from the shell, which Shizuku is.
     * vivo refuses to switch SolDrop's own service on from the shell (checked 2026-11-21: the write
     * to enabled_accessibility_services is undone at once, SolCube's goes through).
     * Returns true if the local in-process overlay [Island.show] was used.
     */
    private fun pill(ctx: Context, text: String): Boolean {
        if (SolDropA11y.instance == null && solCubeInstalled(ctx) &&
            shell("am", "broadcast", "-n", "dev.suite/.IslandReceiver", "--es", "text", text, "--ez", "ok", "true")
        ) {
            return false
        }
        Island.show(ctx, text, true)
        return true
    }

    fun solCubeInstalled(ctx: Context) = try {
        ctx.packageManager.getPackageInfo("dev.suite", 0); true
    } catch (_: Exception) { false }

    /**
     * The pill draws in SolDropA11y's window, the only one above the status bar. Shizuku (shell)
     * may switch accessibility services on, like SolCube keeps its own on; the other services in
     * the list stay as they are.
     */
    fun ensureA11y(ctx: Context) {
        if (Island.a11yEnabled(ctx) || solCubeInstalled(ctx) || a11yTried) return
        a11yTried = true
        val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
        val list = cur.split(':').filter { it.isNotBlank() } + Island.A11Y
        shell("settings", "put", "secure", "enabled_accessibility_services", list.joinToString(":"))
        shell("settings", "put", "secure", "accessibility_enabled", "1")
    }

    private fun shell(vararg cmd: String): Boolean = shellOut(*cmd).first == 0

    private fun shellOut(vararg cmd: String): Pair<Int, String> = try {
        // Shizuku.newProcess is private in API 13 but still there; it's the simplest way to run
        // shell commands without a UserService
        val p = newProcessMethod.invoke(null, arrayOf(*cmd), null, null) as java.lang.Process
        val errBuf = StringBuilder()
        val errThread = Thread {
            try {
                BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        errBuf.appendLine(line)
                    }
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        val outBuf = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                outBuf.appendLine(line)
            }
        }
        errThread.join(1000)
        val code = p.waitFor()
        try { p.outputStream.close() } catch (_: Throwable) {}
        val label = if (cmd.size >= 3 && cmd[1] == "-c") "${cmd[0]} -c <script>" else cmd.joinToString(" ")
        if (cmd.firstOrNull() != "dumpsys") Log.i(TAG, "$label -> $code")
        code to (outBuf.toString() + errBuf.toString())
    } catch (e: Throwable) {
        val label = if (cmd.size >= 3 && cmd[1] == "-c") "${cmd[0]} -c <script>" else cmd.joinToString(" ")
        Log.w(TAG, "Shizuku: $label failed", e)
        -1 to ""
    }
}
