package dev.tapdrop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run tour: what AstraDrop does, in five swipeable slides with animations drawn in code
 * (TourDemo). Written for someone who only knows "it moves files between Android and iPhone".
 */
class TourActivity : Activity() {

    companion object {
        private const val KEY = "tour_seen"
        fun seen(ctx: Context) = ctx.getSharedPreferences("tapdrop", MODE_PRIVATE).getBoolean(KEY, false)
        private fun setSeen(ctx: Context) = ctx.getSharedPreferences("tapdrop", MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    private class Slide(val badge: String, val title1: String, val title2: String, val demo: TourDemo.Kind, val text: String)

    private val slides = listOf(
        Slide("WELCOME", "Send photos", "to any iPhone.", TourDemo.Kind.SEND,
            "Full quality, straight from this phone. The iPhone needs **no app** and nothing goes through the cloud: it just opens a page in Safari. Big photos can be **compressed** there before saving."),
        Slide("CONNECT", "Tap the phones together,", "or scan a code.", TourDemo.Kind.TAP,
            "Hold the iPhone to the back of this phone and a banner pops up on it. Tapping uses **NFC**. No NFC? Scanning the **QR code** with the iPhone Camera always works."),
        Slide("NETWORK", "Same Wi-Fi,", "or your hotspot.", TourDemo.Kind.NETWORK,
            "Files go directly between the two phones, so both need to be on **one network**: the same Wi-Fi, or this phone's hotspot. Away from Wi-Fi? One tap on **Hotspot** gives the iPhone a code to join."),
        Slide("BOTH WAYS", "The iPhone can", "send photos back.", TourDemo.Kind.BACK,
            "On the same page the iPhone can pick photos and videos to send you. They land in your **Gallery**, in an AstraDrop album."),
        Slide("OPTIONAL", "Auto mode:", "less tapping.", TourDemo.Kind.AUTO,
            "AstraDrop can switch **NFC** on when you open it and off when you close it, and turn on your **hotspot** with one tap. It takes a one-time setup of about two minutes."),
    )

    private lateinit var ui: Ui
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)
    private lateinit var host: FrameLayout
    private lateinit var counter: TextView
    private lateinit var backBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var setupBtn: TextView
    private val dots = mutableListOf<View>()
    private var current: View? = null
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Look.isDark(this)) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleBars(this)
        setContentView(build())
        show(0, 1, animate = false)
    }

    @Deprecated("framework back handling")
    override fun onBackPressed() {
        if (index > 0) show(index - 1, -1) else finishTour(setup = false)
    }

    private fun finishTour(setup: Boolean) {
        setSeen(this)
        if (setup) startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun build(): View {
        val p = ui.p
        val root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(GlassBackdrop(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 8.dp.toFloat()) }
            }
        }, LinearLayout.LayoutParams(28.dp, 28.dp).apply { rightMargin = 9.dp })
        top.addView(ui.label("AstraDrop", 20f, p.fg, 680).apply { letterSpacing = -0.02f })
        counter = ui.label("", 11.5f, p.sub, 560).apply {
            setPadding(9.dp, 3.dp, 9.dp, 3.dp)
            background = ui.glass(11.dp, level = 1)
        }
        top.addView(counter, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ui.label("Skip", 13f, p.sub, 640).apply {
            setPadding(13.dp, 6.dp, 13.dp, 6.dp)
            background = ui.glass(15.dp, level = 1)
            foreground = ui.ripple(15.dp)
            setOnClickListener { finishTour(setup = false) }
        })
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 14.dp })

        host = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        swipe(host)
        col.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        dots.clear()
        for (i in slides.indices) {
            val dot = View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat() }
                setOnClickListener { if (i != index) show(i, if (i > index) 1 else -1) }
            }
            dots += dot
            dotsRow.addView(dot, LinearLayout.LayoutParams(7.dp, 6.dp).apply { if (i > 0) leftMargin = 6.dp })
        }
        col.addView(dotsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp; bottomMargin = 14.dp })

        setupBtn = ui.button("Set up Auto mode", filled = false, size = 15f, radius = 26, level = 0) { finishTour(setup = true) }
        col.addView(setupBtn, LinearLayout.LayoutParams(MATCH_PARENT, 50.dp).apply { bottomMargin = 10.dp })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        backBtn = ui.label("←", 18f, p.fg, 640).apply {
            gravity = Gravity.CENTER
            background = ui.outline(26.dp, level = 0)
            foreground = ui.ripple(26.dp)
            setOnClickListener { if (index > 0) show(index - 1, -1) }
        }
        row.addView(backBtn, LinearLayout.LayoutParams(56.dp, 52.dp).apply { rightMargin = 10.dp })
        nextBtn = ui.button("Next", filled = true, size = 16f, radius = 26, level = 0) {
            if (index < slides.lastIndex) show(index + 1, 1) else finishTour(setup = false)
        }
        row.addView(nextBtn, LinearLayout.LayoutParams(0, 52.dp, 1f))
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(18.dp, sys.top + 12.dp, 18.dp, sys.bottom + 18.dp)
            insets
        }
        return root
    }

    private fun slideView(i: Int): View {
        val s = slides[i]
        val p = ui.p
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(ui.label("•  " + I18n.t(s.badge), 11.5f, p.link, 640).apply {
                letterSpacing = 0.06f
                setPadding(10.dp, 4.dp, 10.dp, 4.dp)
                background = ui.glass(12.dp, ui.alpha(p.link, 0.16f), level = 1, stroke = ui.alpha(p.link, 0.5f))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 8.dp })
            addView(ui.label(s.title1, 26f, p.fg, 680).apply { letterSpacing = -0.02f })
            addView(ui.label(s.title2, 26f, if (ui.dark) p.accent else p.link, 680).apply { letterSpacing = -0.02f },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                background = ui.glass(22.dp, level = 0)
                addView(ui.body(rich(I18n.t(s.text)), 15f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
            addView(FrameLayout(context).apply {
                background = ui.glass(24.dp, level = 0)
                setPadding(7.dp, 7.dp, 7.dp, 7.dp)
                addView(TourDemo(context, s.demo, p).apply {
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18.dp.toFloat()) }
                    }
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    /** **bold** spans in the text colour. */
    private fun rich(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", i + 2)
                if (end > i) {
                    val st = sb.length
                    sb.append(raw.substring(i + 2, end))
                    sb.setSpan(ForegroundColorSpan(ui.p.fg), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(android.text.style.TypefaceSpan(Look.font(this, 640)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            sb.append(raw[i]); i++
        }
        return sb
    }

    private fun swipe(stage: View) {
        var downX = 0f; var downY = 0f; var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        stage.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    if (!dragging && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(ev.y - downY)) dragging = true
                    if (dragging) current?.let {
                        it.animate().cancel()
                        it.translationX = dx * 0.35f
                        it.alpha = (1f - kotlin.math.abs(dx) / (stage.width.coerceAtLeast(1) * 1.4f)).coerceIn(0.45f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.x - downX
                    when {
                        dragging && dx < -48.dp && index < slides.lastIndex -> show(index + 1, 1)
                        dragging && dx > 48.dp && index > 0 -> show(index - 1, -1)
                        else -> current?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(180L)?.start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun show(target: Int, dir: Int, animate: Boolean = true) {
        index = target.coerceIn(0, slides.lastIndex)
        counter.text = "${index + 1} / ${slides.size}"
        backBtn.visibility = if (index > 0) View.VISIBLE else View.GONE
        val last = index == slides.lastIndex
        nextBtn.text = I18n.t(if (last) "Start using AstraDrop" else "Next")
        setupBtn.visibility = if (last) View.VISIBLE else View.GONE
        for (i in dots.indices) {
            val act = i == index
            (dots[i].background as GradientDrawable).setColor(if (act) ui.p.fill else ui.alpha(ui.p.sub, 0.34f))
            dots[i].layoutParams = (dots[i].layoutParams as LinearLayout.LayoutParams).apply { width = if (act) 20.dp else 7.dp }
        }
        val out = current
        for (i in host.childCount - 1 downTo 0) {
            val c = host.getChildAt(i)
            c.animate().cancel()
            if (c !== out) host.removeViewAt(i)
        }
        val inc = slideView(index)
        current = inc
        host.addView(inc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (!animate || out == null) { out?.let { host.removeView(it) }; return }
        inc.alpha = 0f
        inc.translationX = (dir * 46.dp).toFloat()
        inc.animate().alpha(1f).translationX(0f).setDuration(260L).setInterpolator(interp).start()
        out.animate().alpha(0f).translationX((-dir * 46.dp).toFloat()).setDuration(190L).setInterpolator(interp)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { host.removeView(out) }
            }).start()
    }
}
