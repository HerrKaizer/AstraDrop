package dev.tapdrop

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tapdrop.engine.Engine
import dev.tapdrop.engine.PairingService

/**
 * Auto mode's one-time setup, one step per screen. Each step ticks itself off as soon as Android
 * reports it done (checked whenever the user comes back, and every second while on screen), so
 * the user never has to say "I did it". Pairing shows what's happening live.
 */
class SetupActivity : Activity() {

    private enum class Step { INTRO, DEV, DEBUG, PAIR, DONE }

    private lateinit var ui: Ui
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)
    private lateinit var host: FrameLayout
    private lateinit var counter: TextView
    private var shown: Step? = null
    private var started = false
    private var status: TextView? = null
    private var statusDot: View? = null
    private var primary: TextView? = null
    private var secondary: TextView? = null
    private val listener: () -> Unit = { refresh() }
    private val tick = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Look.isDark(this)) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleBars(this)
        started = savedInstanceState?.getBoolean("started") ?: Engine.devOptionsOn(this)
        setContentView(build())
        refresh()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("started", started)
    }

    override fun onResume() {
        super.onResume()
        PairingService.addListener(listener)
        AutoNfc.addListener(listener)
        refresh()
        main.postDelayed(tick, 1000)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        PairingService.removeListener(listener)
        AutoNfc.removeListener(listener)
        super.onPause()
    }

    private fun step(): Step = when {
        Engine.isPaired(this) && Engine.devOptionsOn(this) -> Step.DONE
        !started -> Step.INTRO
        !Engine.devOptionsOn(this) -> Step.DEV
        !Engine.wirelessDebuggingOn(this) -> Step.DEBUG
        else -> Step.PAIR
    }

    private fun refresh() {
        val s = step()
        if (s != shown) {
            val dir = if (shown == null || s.ordinal > shown!!.ordinal) 1 else -1
            show(s, dir, animate = shown != null)
            if (s == Step.DONE) { if (!AutoNfc.enabled(this)) AutoNfc.setEnabled(this, true) else AutoNfc.ensureEngine(this) }
        }
        renderStatus(s)
    }

    // ------------------------------------------------------------------ layout

    private fun build(): View {
        val p = ui.p
        val root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(GlassBackdrop(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.label("✕", 15f, p.fg, 640).apply {
            gravity = Gravity.CENTER
            background = ui.outline(18.dp, level = 0)
            foreground = ui.ripple(18.dp)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { rightMargin = 12.dp })
        top.addView(ui.label("Auto mode setup", 19f, p.fg, 680).apply { letterSpacing = -0.01f })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        counter = ui.label("", 11.5f, p.sub, 560).apply {
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            background = ui.glass(12.dp, level = 1)
        }
        top.addView(counter)
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 18.dp })
        host = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        col.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(18.dp, sys.top + 12.dp, 18.dp, sys.bottom + 18.dp)
            insets
        }
        return root
    }

    private class Content(
        val title1: String, val title2: String, val text: String, val demo: TourDemo.Kind?,
        val primary: String, val onPrimary: () -> Unit,
        val secondary: String? = null, val onSecondary: (() -> Unit)? = null,
    )

    private fun content(s: Step): Content = when (s) {
        Step.INTRO -> Content("Less tapping,", "every time.",
            "Auto mode switches NFC on when you open AstraDrop and off when you close it, and turns on your hotspot with one tap.\n\nAndroid only lets apps do that through Wireless debugging, a developer feature built into the phone. You set it up once (about two minutes). Nothing leaves your phone.",
            TourDemo.Kind.AUTO, "Start", { started = true; refresh() }, "Not now", { finish() })
        Step.DEV -> Content("Step 1:", "turn on Developer options.",
            "Open About phone and tap **Build number** seven times, until it says you're a developer. On some phones it's under Software information or Version. Then come back here.",
            TourDemo.Kind.DEV, "Open About phone", { Nav.aboutPhone(this) })
        Step.DEBUG -> Content("Step 2:", "turn on Wireless debugging.",
            "In Developer options, switch on **Wireless debugging** and tap **Allow** for this Wi-Fi. Then come back here.",
            TourDemo.Kind.WIRELESS, "Open Wireless debugging", { Nav.wirelessDebugging(this) },
            "Open Wi-Fi settings", { Nav.wifi(this) })
        Step.PAIR -> Content("Step 3:", "pair AstraDrop.",
            "Tap **Start pairing**, then **Pair device with pairing code** on the page that opens. A notification from AstraDrop drops down: tap **Enter code** and type the six digits you see. You don't need to come back.",
            TourDemo.Kind.PAIR, "Start pairing", { PairingService.start(this); Nav.wirelessDebugging(this) })
        Step.DONE -> Content("All set.", "Auto mode is on.",
            "From now on NFC turns on when you open AstraDrop and off when you close it. Tap **Hotspot** on the main screen to turn on your hotspot.\n\nIf you switch Wireless debugging off, AstraDrop turns it back on by itself the next time (on Wi-Fi).",
            TourDemo.Kind.AUTO, "Done", { finish() })
    }

    private fun show(s: Step, dir: Int, animate: Boolean) {
        shown = s
        val n = when (s) { Step.DEV -> 1; Step.DEBUG -> 2; Step.PAIR -> 3; else -> 0 }
        counter.text = if (n > 0) I18n.t("Step %d of 3").format(n) else I18n.t(if (s == Step.DONE) "Done" else "One-time")
        val c = content(s)
        val p = ui.p
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(ui.label(c.title1, 26f, p.fg, 680).apply { letterSpacing = -0.02f })
            addView(ui.label(c.title2, 26f, if (ui.dark) p.accent else p.link, 680).apply { letterSpacing = -0.02f },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                background = ui.glass(22.dp, level = 0)
                addView(ui.body(bold(I18n.t(c.text)), 15f))
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 12.dp, 0, 0)
                }
                statusDot = View(context)
                row.addView(statusDot, LinearLayout.LayoutParams(9.dp, 9.dp).apply { rightMargin = 9.dp })
                status = ui.body("", 14f, p.link)
                row.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(row)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
            if (c.demo != null) addView(FrameLayout(context).apply {
                background = ui.glass(24.dp, level = 0)
                setPadding(7.dp, 7.dp, 7.dp, 7.dp)
                addView(TourDemo(context, c.demo, p).apply {
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18.dp.toFloat()) }
                    }
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 14.dp })
            else addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            secondary = c.secondary?.let { t ->
                ui.button(t, filled = false, size = 15f, radius = 26, level = 0) { c.onSecondary?.invoke() }.also {
                    addView(it, LinearLayout.LayoutParams(MATCH_PARENT, 50.dp).apply { bottomMargin = 10.dp })
                }
            }
            primary = ui.button(c.primary, filled = true, size = 16f, radius = 26, level = 0) { c.onPrimary() }.also {
                addView(it, LinearLayout.LayoutParams(MATCH_PARENT, 52.dp))
            }
        }
        val out = host.getChildAt(0)
        host.removeAllViews()
        host.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (animate && out != null) {
            v.alpha = 0f
            v.translationX = (dir * 46.dp).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(280L).setInterpolator(interp).start()
        }
    }

    /** Live status under the step's text: what Android reports right now. */
    private fun renderStatus(s: Step) {
        val st = status ?: return
        val p = ui.p
        val phase = PairingService.phase
        var color = p.link
        val text = when (s) {
            Step.INTRO -> ""
            Step.DEV -> "Waiting for Developer options…"
            Step.DEBUG -> if (!Engine.isOnWifi(this)) {
                color = p.warn
                "Connect this phone to Wi-Fi first: Android only allows Wireless debugging on Wi-Fi."
            } else "Waiting for Wireless debugging…"
            Step.PAIR -> when (phase) {
                PairingService.Phase.WAITING -> "Waiting for “Pair device with pairing code”…"
                PairingService.Phase.CODE -> "Now type the code into AstraDrop's notification (pull down the notifications if you don't see it)."
                PairingService.Phase.WORKING -> "Pairing…"
                PairingService.Phase.ERROR -> { color = p.warn; PairingService.error.ifEmpty { "Something went wrong. Tap Start pairing to try again." } }
                else -> "Ready when you are."
            }
            Step.DONE -> when {
                AutoNfc.engineUp -> { color = p.ok; "Running" }
                AutoNfc.lastStart == Engine.Start.NO_WIFI -> { color = p.warn; "Starts the next time this phone is on Wi-Fi." }
                AutoNfc.lastStart == Engine.Start.FAILED || AutoNfc.lastStart == Engine.Start.WD_OFF ->
                    { color = p.warn; "Paired, but it didn't start yet. It tries again when you open AstraDrop." }
                else -> "Starting…"
            }
        }
        val shownText = I18n.t(text)
        if (st.text.toString() != shownText) st.text = shownText
        st.setTextColor(color)
        statusDot?.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        (st.parent as View).visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (s == Step.DEBUG) secondary?.visibility = if (Engine.isOnWifi(this)) View.GONE else View.VISIBLE
        if (s == Step.PAIR) primary?.text = I18n.t(if (phase == PairingService.Phase.ERROR) "Try again" else "Start pairing")
    }

    private fun bold(raw: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", i + 2)
                if (end > i) {
                    val st = sb.length
                    sb.append(raw.substring(i + 2, end))
                    sb.setSpan(android.text.style.ForegroundColorSpan(ui.p.fg), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(android.text.style.TypefaceSpan(Look.font(this, 640)), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            sb.append(raw[i]); i++
        }
        return sb
    }
}
