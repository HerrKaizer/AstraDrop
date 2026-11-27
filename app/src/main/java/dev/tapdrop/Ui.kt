package dev.tapdrop

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** SolCube's controls for AstraDrop's screens: glass panes, gold buttons, outlined buttons, labels. */
class Ui(val ctx: Context) {
    val p = Look.palette(ctx)
    val dark = Look.isDark(ctx)
    val density = ctx.resources.displayMetrics.density
    val Int.dp: Int get() = (this * density).toInt()

    val regular get() = Look.font(ctx, 400)
    val medium get() = Look.font(ctx, 560)
    val bold get() = Look.font(ctx, 680)

    /** Plain strings are English keys and come out in the app's language (see I18n); styled text is used as is. */
    fun label(t: CharSequence, size: Float, color: Int = p.fg, weight: Int = 400) = TextView(ctx).apply {
        text = if (t is String) I18n.t(t) else t
        textSize = size
        setTextColor(color)
        typeface = Look.font(ctx, weight)
    }

    fun body(t: CharSequence, size: Float = 14.5f, color: Int = p.sub) = label(t, size, color).apply {
        setLineSpacing(0f, 1.18f)
    }

    /** Liquid glass pane: level 0 on the wallpaper, 1 inside a card, 2 inside a small card. */
    fun glass(radius: Int, tint: Int? = null, level: Int = 1, stroke: Int = 0): Drawable =
        LiquidGlass(radius.toFloat(), dark, density, level = level, tint = tint, stroke = stroke)

    /** Gold glass by night (rim + halo), ink glass by day. */
    fun gold(radius: Int, level: Int = 1): Drawable =
        LiquidGlass(radius.toFloat(), dark, density, level = level, tint = p.fill, shine = p.fillShine,
            shadow = p.halo == 0, halo = p.halo, stroke = p.fillStroke)

    /** Outlined button glass: a visible tint and rim, so it reads as a button even in a pop-up. */
    fun outline(radius: Int, level: Int = 1): Drawable =
        LiquidGlass(radius.toFloat(), dark, density, level = level, tint = p.btn, stroke = p.btnStroke)

    fun ripple(radius: Int): Drawable = RippleDrawable(
        ColorStateList.valueOf(alpha(p.fg, 0.14f)), null,
        GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(Color.WHITE) },
    )

    /**
     * SolCube's pill button. Filled = gold (the main action), else outlined. The ripple is the
     * foreground: as a background it would clip the gold halo to a box.
     */
    fun button(t: CharSequence, filled: Boolean, size: Float = 15f, radius: Int = 24, level: Int = 1, onClick: () -> Unit) =
        label(t, size, if (filled) p.onAccent else p.fg, 640).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(16.dp, 0, 16.dp, 0)
            background = if (filled) gold(radius.dp, level) else outline(radius.dp, level)
            foreground = ripple(radius.dp)
            setOnClickListener { onClick() }
        }

    fun setFilled(v: TextView, filled: Boolean, radius: Int = 24, level: Int = 1) {
        v.setTextColor(if (filled) p.onAccent else p.fg)
        v.background = if (filled) gold(radius.dp, level) else outline(radius.dp, level)
    }

    /** Big card on the wallpaper. */
    fun card(pad: Int = 18) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        background = glass(24.dp, level = 0)
        setPadding(pad.dp, pad.dp, pad.dp, pad.dp)
    }

    fun alpha(color: Int, a: Float) =
        Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    @Suppress("DEPRECATION")
    fun styleBars(a: Activity) {
        val w = a.window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        w.isNavigationBarContrastEnforced = false
        w.decorView.setBackgroundColor(p.base)
        w.setDecorFitsSystemWindows(false)
        val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.insetsController?.setSystemBarsAppearance(if (dark) 0 else f, f)
    }

    /**
     * A pop-up in SolCube's style: title, text, optional content, one or two buttons (the
     * positive one gold). The pane is near solid, so the page behind never shows through the text.
     */
    fun dialog(
        title: CharSequence,
        message: CharSequence? = null,
        content: View? = null,
        positive: String? = null,
        negative: String? = null,
        onNegative: (() -> Unit)? = null,
        onPositive: (() -> Unit)? = null,
    ): Dialog {
        val d = Dialog(ctx)
        val blur = Build.VERSION.SDK_INT >= 31 &&
            ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(22.dp, 22.dp, 22.dp, 18.dp)
            background = glass(30.dp, alpha(p.solid, if (blur) (if (dark) 0.90f else 0.92f) else 0.98f), level = 0)
        }
        root.addView(label(title, 21f, p.fg, 680).apply { letterSpacing = -0.01f },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8.dp })
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        message?.let { inner.addView(body(it, 15f, alpha(p.fg, if (dark) 0.82f else 0.78f))) }
        content?.let {
            inner.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (message != null) topMargin = 14.dp })
        }
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(inner)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (positive != null || negative != null) {
            // side by side only when both labels fit; otherwise stacked, the main action on top
            val pos = positive?.let { I18n.t(it) }
            val neg = negative?.let { I18n.t(it) }
            val paint = android.graphics.Paint().apply { textSize = 14.5f * ctx.resources.displayMetrics.scaledDensity; typeface = Look.font(ctx, 640) }
            val half = (minOf(ctx.resources.displayMetrics.widthPixels - 32.dp, 420.dp) - 44.dp - 10.dp) / 2f - 34.dp
            val stack = pos != null && neg != null && (paint.measureText(pos) > half || paint.measureText(neg) > half)
            val row = LinearLayout(ctx).apply {
                orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            fun lp(first: Boolean) = if (stack) LinearLayout.LayoutParams(MATCH_PARENT, 46.dp).apply { if (!first) topMargin = 10.dp }
                else LinearLayout.LayoutParams(0, 46.dp, 1f).apply { if (!first) leftMargin = 10.dp }
            val posBtn = pos?.let { button(it, true, 14.5f, 22) { d.dismiss(); onPositive?.invoke() } }
            val negBtn = neg?.let { button(it, false, 14.5f, 22) { d.dismiss(); onNegative?.invoke() } }
            if (stack) {
                row.addView(posBtn, lp(true)); row.addView(negBtn, lp(false))
            } else {
                negBtn?.let { row.addView(it, lp(true)) }
                posBtn?.let { row.addView(it, lp(negBtn == null)) }
            }
            root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })
        }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(FrameLayout(ctx).apply {
            clipChildren = false
            addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        d.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            setLayout(minOf(ctx.resources.displayMetrics.widthPixels - 32.dp, 420.dp), WRAP_CONTENT)
            if (blur) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = 20.dp }
            }
            setDimAmount(if (dark) 0.55f else 0.3f)
        }
        d.show()
        d.window?.let { win ->
            val max = (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(win.attributes.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            if (root.measuredHeight > max) win.setLayout(win.attributes.width, max)
        }
        root.alpha = 0f; root.scaleX = 0.94f; root.scaleY = 0.94f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L).setInterpolator(DecelerateInterpolator(2f)).start()
        return d
    }

    /** A list to pick one from (the current one ticked); [onPick] gets its index. */
    fun choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): Dialog {
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4.dp, 0, 4.dp)
            background = glass(20.dp, if (dark) Color.argb(26, 255, 255, 255) else Color.argb(150, 255, 255, 255), level = 1)
        }
        var dlg: Dialog? = null
        options.forEachIndexed { i, o ->
            if (i > 0) list.addView(View(ctx).apply { setBackgroundColor(alpha(p.fg, 0.08f)) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = 14.dp; rightMargin = 14.dp })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                foreground = ripple(14.dp)
                setOnClickListener { dlg?.dismiss(); onPick(i) }
            }
            row.addView(label(o, 15.5f, p.fg, if (i == selected) 640 else 440), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (i == selected) row.addView(label("✓", 15f, p.link, 700))
            list.addView(row)
        }
        dlg = dialog(title, content = list, negative = "Cancel")
        return dlg
    }
}
