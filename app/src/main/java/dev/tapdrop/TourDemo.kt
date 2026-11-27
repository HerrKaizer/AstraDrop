package dev.tapdrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Looping animations drawn in code for the tour and the Auto mode setup (no images, so they follow
 * the theme and stay sharp on any screen). An Android phone with a gold rim, an iPhone, and little
 * photo tiles (a sunset, the sea, green hills) that travel between them.
 */
class TourDemo(ctx: Context, private val kind: Kind, private val p: Look.Palette) : View(ctx) {

    enum class Kind { SEND, TAP, NETWORK, BACK, AUTO, DEV, WIRELESS, PAIR }

    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val light = !Look.isDark(ctx)
    private val gold get() = if (light) p.link else p.accent
    private val ink = if (light) Color.rgb(0x2A, 0x24, 0x1C) else Color.rgb(0xF4, 0xEE, 0xE4)
    private val screenInk = Color.rgb(0xF4, 0xEE, 0xE4)
    private val bold = Look.font(ctx, 680)
    private val medium = Look.font(ctx, 560)

    private val period = when (kind) {
        Kind.SEND -> 4.6f
        Kind.TAP -> 6.4f
        Kind.NETWORK -> 6.0f
        Kind.BACK -> 4.6f
        Kind.AUTO -> 6.0f
        Kind.DEV -> 5.2f
        Kind.WIRELESS -> 4.6f
        Kind.PAIR -> 6.0f
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r1 = RectF()
    private val r2 = RectF()
    private val path = Path()

    // a sunset, the sea, green hills, lavender dusk: sky top, sky bottom, ground
    private val photos = arrayOf(
        intArrayOf(Color.rgb(0xFF, 0x9A, 0x5C), Color.rgb(0xFF, 0xD6, 0x8A), Color.rgb(0x6B, 0x3A, 0x2A)),
        intArrayOf(Color.rgb(0x4F, 0xA8, 0xD8), Color.rgb(0xB8, 0xE4, 0xF0), Color.rgb(0x1E, 0x5F, 0x8C)),
        intArrayOf(Color.rgb(0x8E, 0xC9, 0xF0), Color.rgb(0xE8, 0xF4, 0xD8), Color.rgb(0x4E, 0x8A, 0x3C)),
        intArrayOf(Color.rgb(0x7C, 0x6B, 0xC8), Color.rgb(0xF0, 0xB8, 0xC8), Color.rgb(0x3A, 0x2E, 0x5A)),
    )

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = ((SystemClock.uptimeMillis() - start) / 1000f) % period

        r1.set(0f, 0f, w, h)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            if (light) Color.rgb(0xFB, 0xF7, 0xF0) else Color.rgb(0x16, 0x13, 0x0F),
            if (light) Color.rgb(0xEC, 0xE3, 0xD5) else Color.rgb(0x08, 0x07, 0x06), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, 18 * d, 18 * d, fill)
        fill.shader = RadialGradient(w * 0.5f, h * 0.55f, max(w, h) * 0.6f,
            intArrayOf(alpha(gold, 0.16f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, 18 * d, 18 * d, fill)
        fill.shader = null

        val a = smooth(seg(t, 0f, 0.3f)) * (1f - smooth(seg(t, period - 0.35f, period)))
        c.saveLayerAlpha(0f, 0f, w, h, (a * 255).toInt())
        when (kind) {
            Kind.SEND -> send(c, w, h, t, back = false)
            Kind.BACK -> send(c, w, h, t, back = true)
            Kind.TAP -> tap(c, w, h, t)
            Kind.NETWORK -> network(c, w, h, t)
            Kind.AUTO -> auto(c, w, h, t)
            Kind.DEV -> dev(c, w, h, t)
            Kind.WIRELESS -> wireless(c, w, h, t)
            Kind.PAIR -> pair(c, w, h, t)
        }
        c.restore()
        if (isAttachedToWindow && isShown) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ 1 + 4. photos across

    private fun send(c: Canvas, w: Float, h: Float, t: Float, back: Boolean) {
        val ph = min(h * 0.66f, w * 0.62f)
        val cy = h * 0.5f
        val and = android(c, w * 0.27f, cy, ph)
        val ios = iphone(c, w * 0.73f, cy, ph)
        caption(c, w * 0.27f, cy + ph / 2 + 16 * d, "Android")
        caption(c, w * 0.73f, cy + ph / 2 + 16 * d, "iPhone")
        val from = if (back) ios else and
        val to = if (back) and else ios
        // a gallery on both screens; the receiving one fills as the photos land
        grid(c, from, 4, 1f)
        val landed = (0 until 4).count { t > 0.7f + it * 0.55f + 1.0f }
        grid(c, to, landed, 1f, header = if (back) "AstraDrop" else null)
        for (i in 0 until 4) {
            val f = seg(t, 0.7f + i * 0.55f, 0.7f + i * 0.55f + 1.0f)
            if (f <= 0f || f >= 1f) continue
            val e = easeInOut(f)
            val (sx, sy) = cell(from, i)
            val (ex, ey) = cell(to, i)
            val x = sx + (ex - sx) * e
            val y = sy + (ey - sy) * e - sin(PI.toFloat() * e) * h * 0.22f
            val s = cellSize(from) * (1f + 0.5f * sin(PI.toFloat() * e))
            // soft gold trail
            fill.color = alpha(gold, 0.18f * sin(PI.toFloat() * e))
            c.drawCircle(x, y, s * 0.9f, fill)
            photo(c, x, y, s, i, 1f, (if (back) -1 else 1) * 12f * sin(PI.toFloat() * e))
        }
    }

    // ------------------------------------------------------------------ 2. tap, or scan

    private fun tap(c: Canvas, w: Float, h: Float, t: Float) {
        val ph = min(h * 0.62f, w * 0.6f)
        val scan = t > 3.3f
        val local = if (scan) t - 3.3f else t
        chip(c, w * 0.5f, 14 * d, if (scan) "Or scan the QR code" else "Hold the phones together", if (scan) seg(t, 3.3f, 3.6f) else 1f - seg(t, 3.0f, 3.3f))
        val and = android(c, w * 0.34f, h * 0.56f, ph)
        if (!scan) {
            contentRows(c, and)
            // the iPhone comes in, touches the top of the Android phone, and moves back a little
            val inP = easeOut(seg(local, 0.2f, 1.0f))
            val outP = easeInOut(seg(local, 1.5f, 2.1f))
            val x = w * 1.15f + (w * 0.60f - w * 1.15f) * inP + w * 0.08f * outP
            val y = h * 0.42f - h * 0.03f * outP
            c.save()
            c.rotate(-14f * (1f - outP * 0.6f), x, y)
            val ios = iphone(c, x, y, ph * 0.92f)
            // banner on the iPhone right after the touch
            val b = seg(local, 1.25f, 1.55f) * (1f - seg(local, 2.8f, 3.1f))
            banner(c, ios, "AstraDrop", "Open 4 photos", b)
            c.restore()
            // gold rings from where the phones touch
            val touch = seg(local, 0.95f, 2.2f)
            if (touch > 0f && touch < 1f) {
                for (k in 0..2) {
                    val f = ((touch - k * 0.16f) / 0.7f).coerceIn(0f, 1f)
                    if (f <= 0f || f >= 1f) continue
                    stroke.color = alpha(gold, 0.85f * (1f - f))
                    stroke.strokeWidth = 2.4f * d * (1f - f * 0.5f)
                    c.drawCircle(and.centerX() + and.width() * 0.3f, and.top + and.height() * 0.18f, 10 * d + f * ph * 0.45f, stroke)
                }
            }
        } else {


            // QR on the Android screen, iPhone camera framing it
            val qrS = and.width() * 0.66f
            r2.set(and.centerX() - qrS / 2, and.centerY() - qrS / 2, and.centerX() + qrS / 2, and.centerY() + qrS / 2)
            qr(c, r2)
            val inP = easeOut(seg(local, 0.1f, 0.8f))
            val x = w * 1.15f + (w * 0.70f - w * 1.15f) * inP
            val ios = iphone(c, x, h * 0.56f, ph * 0.92f)
            // viewfinder: a smaller copy of the QR with corner brackets
            fill.color = Color.rgb(0x22, 0x20, 0x1C)
            c.drawRoundRect(ios, ios.width() * 0.12f, ios.width() * 0.12f, fill)
            val vs = ios.width() * 0.5f
            r2.set(ios.centerX() - vs / 2, ios.centerY() - vs / 2, ios.centerX() + vs / 2, ios.centerY() + vs / 2)
            qr(c, r2)
            val lock = seg(local, 1.0f, 1.3f)
            brackets(c, r2, 6 * d * (1f - lock) + 2 * d, if (lock > 0.5f) Color.rgb(0xFF, 0xD6, 0x4A) else screenInk)
            banner(c, ios, "Camera", "Open in Safari", seg(local, 1.4f, 1.7f) * (1f - seg(local, 2.7f, 2.95f)))
        }
    }

    // ------------------------------------------------------------------ 3. same Wi-Fi, or your hotspot

    private fun network(c: Canvas, w: Float, h: Float, t: Float) {
        val ph = min(h * 0.5f, w * 0.46f)
        val cy = h * 0.66f
        val hot = t > 3.0f
        val local = if (hot) t - 3.0f else t
        chip(c, w * 0.5f, 14 * d, if (hot) "Or this phone's hotspot" else "The same Wi-Fi", if (hot) seg(t, 3.0f, 3.3f) else 1f - seg(t, 2.7f, 3.0f))
        val and = android(c, w * 0.27f, cy, ph)
        val ios = iphone(c, w * 0.73f, cy, ph)
        contentRows(c, and, faint = true)
        val dash = local * 40 * d
        if (!hot) {
            // a router above both phones, dashes running up to it from each
            val rx = w * 0.5f
            val ry = h * 0.3f
            val show = seg(local, 0.2f, 0.6f)
            wifi(c, rx, ry, 22 * d, alpha(gold, show))
            dashed(c, and.centerX(), and.top - 6 * d, rx - 14 * d, ry + 14 * d, dash, show)
            dashed(c, ios.centerX(), ios.top - 6 * d, rx + 14 * d, ry + 14 * d, dash, show)
        } else {
            // arcs from the Android phone toward the iPhone
            for (k in 0..2) {
                val f = ((local * 0.8f - k * 0.25f) % 1f + 1f) % 1f
                stroke.color = alpha(gold, 0.8f * (1f - f) * seg(local, 0.1f, 0.4f))
                stroke.strokeWidth = 2.4f * d
                val rr = 14 * d + f * (ios.left - and.right) * 0.95f
                r2.set(and.right - rr, cy - rr, and.right + rr, cy + rr)
                c.drawArc(r2, -35f, 70f, false, stroke)
            }
            // the iPhone joins it once: a little check
            val j = seg(local, 1.2f, 1.5f)
            if (j > 0f) {
                fill.color = alpha(p.ok, j)
                c.drawCircle(ios.right - 2 * d, ios.top + 2 * d, 9 * d * easeBack(j), fill)
                check(c, ios.right - 2 * d, ios.top + 2 * d, 4.5f * d, alpha(Color.BLACK, j))
            }
        }
        caption(c, w * 0.27f, cy + ph / 2 + 16 * d, "Android")
        caption(c, w * 0.73f, cy + ph / 2 + 16 * d, "iPhone")
    }

    // ------------------------------------------------------------------ 5. Auto mode

    private fun auto(c: Canvas, w: Float, h: Float, t: Float) {
        val ph = h * 0.8f
        val and = android(c, w * 0.42f, h * 0.52f, ph)
        val open = seg(t, 0.9f, 1.2f) * (1f - seg(t, 3.9f, 4.3f))
        // home screen: app dots, ours in gold; a finger taps it
        val cols = 3
        for (i in 0 until 9) {
            val cx = and.left + and.width() * (0.22f + (i % cols) * 0.28f)
            val cy = and.top + and.height() * (0.32f + (i / cols) * 0.17f)
            val ours = i == 4
            fill.color = if (ours) gold else alpha(Color.WHITE, 0.16f)
            c.drawRoundRect(cx - 9 * d, cy - 9 * d, cx + 9 * d, cy + 9 * d, 6 * d, 6 * d, fill)
        }
        val tapA = seg(t, 0.4f, 0.6f) * (1f - seg(t, 0.85f, 1.0f))
        if (tapA > 0f) {
            fill.color = alpha(Color.WHITE, 0.45f * tapA)
            c.drawCircle(and.left + and.width() * 0.5f, and.top + and.height() * 0.49f, 13 * d, fill)
        }
        // AstraDrop open: the screen turns into the app
        if (open > 0f) {
            fill.color = alpha(Color.rgb(0x16, 0x12, 0x0C), open)
            c.drawRoundRect(and, and.width() * 0.13f, and.width() * 0.13f, fill)
            fill.color = alpha(gold, 0.85f * open)
            c.drawCircle(and.centerX(), and.top + and.height() * 0.38f, and.width() * 0.17f, fill)
            text.typeface = bold; text.textSize = 11 * d; text.color = alpha(screenInk, open)
            c.drawText(I18n.t("Ready"), and.centerX(), and.top + and.height() * 0.6f, text)
            // swipe up to close
            val sw = seg(t, 3.4f, 3.9f)
            if (sw > 0f && sw < 1f) {
                fill.color = alpha(Color.WHITE, 0.5f * sin(PI.toFloat() * sw))
                c.drawCircle(and.centerX(), and.bottom - and.height() * (0.08f + 0.4f * easeOut(sw)), 11 * d, fill)
            }
        }
        pill(c, and.centerX(), and.top + 3 * d, "NFC on", p.ok, seg(t, 1.3f, 1.7f), seg(t, 2.9f, 3.2f))
        pill(c, and.centerX(), and.top + 3 * d, "NFC off", gold, seg(t, 4.4f, 4.8f), seg(t, 5.4f, 5.7f))
        // the two things Auto mode switches, lit while AstraDrop is open
        val lit = seg(t, 1.5f, 1.9f) * (1f - seg(t, 4.3f, 4.7f))
        bubble(c, w * 0.82f, h * 0.36f, 19 * d, lit, nfc = true)
        bubble(c, w * 0.82f, h * 0.62f, 19 * d, seg(t, 1.9f, 2.3f) * (1f - seg(t, 4.3f, 4.7f)), nfc = false)
        text.typeface = medium; text.textSize = 10.5f * d; text.color = alpha(ink, 0.7f)
        c.drawText(I18n.t("NFC"), w * 0.82f, h * 0.36f + 34 * d, text)
        c.drawText(I18n.t("Hotspot"), w * 0.82f, h * 0.62f + 34 * d, text)
    }

    // ------------------------------------------------------------------ setup: Developer options

    private fun dev(c: Canvas, w: Float, h: Float, t: Float) {
        val s = android(c, w * 0.5f, h * 0.5f, h * 0.86f)
        settingsHeader(c, s, "About phone")
        val rows = listOf("Device name", "Model", "Android version", "Build number")
        rows.forEachIndexed { i, label -> settingsRow(c, s, i, label, highlight = i == 3 && t < 4.0f) }
        // seven taps on Build number
        val taps = ((t - 0.6f) / 0.38f).toInt().coerceIn(0, 7)
        val (rx, ry) = rowCenter(s, 3)
        val phase = ((t - 0.6f) / 0.38f) % 1f
        if (t > 0.6f && taps < 7) {
            fill.color = alpha(Color.WHITE, 0.5f * (1f - phase))
            c.drawCircle(rx, ry, (8 + 8 * phase) * d, fill)
        }
        if (taps in 3..6) toast(c, s, I18n.plural(7 - taps, "%d more tap", "%d more taps"), 1f)
        if (t > 3.3f) toast(c, s, "You're now a developer!", seg(t, 3.3f, 3.5f) * (1f - seg(t, 4.6f, 4.9f)))
    }

    // ------------------------------------------------------------------ setup: Wireless debugging

    private fun wireless(c: Canvas, w: Float, h: Float, t: Float) {
        val s = android(c, w * 0.5f, h * 0.5f, h * 0.86f)
        settingsHeader(c, s, "Developer options")
        settingsRow(c, s, 0, "USB debugging")
        settingsRow(c, s, 1, "Wireless debugging", highlight = true, switchOn = seg(t, 0.9f, 1.1f))
        settingsRow(c, s, 2, "Disable adb timeout")
        val dlg = seg(t, 1.2f, 1.45f) * (1f - seg(t, 2.6f, 2.8f))
        if (dlg > 0f) {
            fill.color = alpha(Color.BLACK, 0.45f * dlg)
            c.drawRoundRect(s, s.width() * 0.13f, s.width() * 0.13f, fill)
            r2.set(s.left + s.width() * 0.08f, s.top + s.height() * 0.36f, s.right - s.width() * 0.08f, s.top + s.height() * 0.64f)
            fill.color = alpha(Color.rgb(0x2A, 0x26, 0x20), dlg)
            c.drawRoundRect(r2, 10 * d, 10 * d, fill)
            text.typeface = medium; text.textSize = 9.5f * d; text.color = alpha(screenInk, dlg)
            c.drawText(I18n.t("Allow on this network?"), r2.centerX(), r2.top + r2.height() * 0.34f, text)
            val bx = r2.centerX()
            val by = r2.bottom - r2.height() * 0.26f
            fill.color = alpha(gold, dlg)
            c.drawRoundRect(bx - 28 * d, by - 10 * d, bx + 28 * d, by + 10 * d, 10 * d, 10 * d, fill)
            text.color = alpha(Color.rgb(0x1E, 0x10, 0x02), dlg); text.typeface = bold
            c.drawText(I18n.t("Allow"), bx, by + 3.5f * d, text)
            val tp = seg(t, 2.0f, 2.4f)
            if (tp > 0f && tp < 1f) {
                fill.color = alpha(Color.WHITE, 0.5f * (1f - tp))
                c.drawCircle(bx, by, (8 + 10 * tp) * d, fill)
            }
        }
        if (t > 0.6f && t < 1.0f) {
            val (rx, ry) = rowCenter(s, 1)
            fill.color = alpha(Color.WHITE, 0.45f)
            c.drawCircle(rx + s.width() * 0.3f, ry, 11 * d, fill)
        }
    }

    // ------------------------------------------------------------------ setup: pairing code

    private fun pair(c: Canvas, w: Float, h: Float, t: Float) {
        val s = android(c, w * 0.5f, h * 0.5f, h * 0.86f)
        settingsHeader(c, s, "Wireless debugging")
        settingsRow(c, s, 0, "Pair with QR code")
        settingsRow(c, s, 1, "Pair with pairing code", highlight = t < 1.2f)
        settingsRow(c, s, 2, "Paired devices")
        if (t > 0.5f && t < 0.9f) {
            val (rx, ry) = rowCenter(s, 1)
            fill.color = alpha(Color.WHITE, 0.45f)
            c.drawCircle(rx, ry, 11 * d, fill)
        }
        val code = "482913"
        val dlg = seg(t, 1.0f, 1.25f) * (1f - seg(t, 5.0f, 5.3f))
        if (dlg > 0f) {
            fill.color = alpha(Color.BLACK, 0.45f * dlg)
            c.drawRoundRect(s, s.width() * 0.13f, s.width() * 0.13f, fill)
            r2.set(s.left + s.width() * 0.08f, s.top + s.height() * 0.42f, s.right - s.width() * 0.08f, s.top + s.height() * 0.66f)
            fill.color = alpha(Color.rgb(0x2A, 0x26, 0x20), dlg)
            c.drawRoundRect(r2, 10 * d, 10 * d, fill)
            text.typeface = medium; text.textSize = 8.5f * d; text.color = alpha(screenInk, 0.7f * dlg)
            c.drawText(I18n.t("Wi-Fi pairing code"), r2.centerX(), r2.top + r2.height() * 0.3f, text)
            text.typeface = bold; text.textSize = 15 * d; text.color = alpha(gold, dlg)
            c.drawText("${code.substring(0, 3)} ${code.substring(3)}", r2.centerX(), r2.top + r2.height() * 0.68f, text)
        }
        // AstraDrop's notification slides down over it; the code goes in
        val n = easeOut(seg(t, 1.8f, 2.2f)) * (1f - seg(t, 4.6f, 4.9f))
        if (n > 0f) {
            val top = s.top + 6 * d - (1f - n) * 60 * d
            r2.set(s.left + 5 * d, top, s.right - 5 * d, top + s.height() * 0.26f)
            fill.color = alpha(Color.rgb(0x38, 0x33, 0x2B), n)
            c.drawRoundRect(r2, 9 * d, 9 * d, fill)
            fill.color = alpha(gold, n)
            c.drawCircle(r2.left + 11 * d, r2.top + 12 * d, 5 * d, fill)
            text.textAlign = Paint.Align.LEFT
            text.typeface = bold; text.textSize = 8.5f * d; text.color = alpha(screenInk, n)
            c.drawText("AstraDrop", r2.left + 20 * d, r2.top + 15 * d, text)
            // the reply field with the digits appearing
            val typed = ((t - 2.5f) / 0.22f).toInt().coerceIn(0, 6)
            val fy = r2.bottom - 16 * d
            fill.color = alpha(Color.WHITE, 0.12f * n)
            c.drawRoundRect(r2.left + 8 * d, fy - 9 * d, r2.right - 8 * d, fy + 9 * d, 9 * d, 9 * d, fill)
            text.typeface = medium; text.textSize = 9.5f * d
            text.color = alpha(if (typed == 0) Color.argb(140, 0xF4, 0xEE, 0xE4) else screenInk, n)
            c.drawText(if (typed == 0) I18n.t("Enter code") else code.substring(0, typed), r2.left + 15 * d, fy + 3.5f * d, text)
            text.textAlign = Paint.Align.CENTER
        }
        val ok = seg(t, 4.2f, 4.5f) * (1f - seg(t, 5.5f, 5.8f))
        if (ok > 0f) pill(c, s.centerX(), s.top + 3 * d, "Paired", p.ok, ok, 0f)
    }

    // ------------------------------------------------------------------ pieces

    /** Android phone body (gold rim, punch hole) centred at (cx, cy) with height [ph]; returns the screen. */
    private fun android(c: Canvas, cx: Float, cy: Float, ph: Float): RectF {
        val pw = ph * 0.48f
        r1.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(r1.left, r1.top, r1.right, r1.bottom, Color.rgb(0x3B, 0x34, 0x2B), Color.rgb(0x16, 0x13, 0x10), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, pw * 0.16f, pw * 0.16f, fill)
        fill.shader = null
        stroke.strokeWidth = 1.3f * d
        stroke.color = alpha(gold, 0.55f)
        c.drawRoundRect(r1, pw * 0.16f, pw * 0.16f, stroke)
        val s = RectF(r1.left + 3.5f * d, r1.top + 3.5f * d, r1.right - 3.5f * d, r1.bottom - 3.5f * d)
        fill.color = Color.rgb(0x0B, 0x0A, 0x09)
        c.drawRoundRect(s, pw * 0.13f, pw * 0.13f, fill)
        fill.color = Color.BLACK
        c.drawCircle(s.centerX(), s.top + 7 * d, 2.6f * d, fill)
        return s
    }

    /** iPhone body (silver rim, Dynamic Island); returns the screen. */
    private fun iphone(c: Canvas, cx: Float, cy: Float, ph: Float): RectF {
        val pw = ph * 0.49f
        r1.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(r1.left, r1.top, r1.right, r1.bottom, Color.rgb(0xD8, 0xD8, 0xDC), Color.rgb(0x8A, 0x8C, 0x92), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, pw * 0.2f, pw * 0.2f, fill)
        fill.shader = null
        val s = RectF(r1.left + 3 * d, r1.top + 3 * d, r1.right - 3 * d, r1.bottom - 3 * d)
        fill.color = Color.rgb(0x10, 0x12, 0x16)
        c.drawRoundRect(s, pw * 0.17f, pw * 0.17f, fill)
        fill.color = Color.BLACK
        c.drawRoundRect(s.centerX() - pw * 0.16f, s.top + 4 * d, s.centerX() + pw * 0.16f, s.top + 4 * d + pw * 0.1f, pw * 0.05f, pw * 0.05f, fill)
        return s
    }

    private fun cellSize(s: RectF) = s.width() * 0.36f
    private fun cell(s: RectF, i: Int): Pair<Float, Float> {
        val cs = cellSize(s)
        val gap = s.width() * 0.06f
        val x0 = s.centerX() - cs / 2 - gap / 2
        val y0 = s.top + s.height() * 0.3f
        return Pair(x0 + (i % 2) * (cs + gap), y0 + (i / 2) * (cs + gap))
    }

    private fun grid(c: Canvas, s: RectF, n: Int, a: Float, header: String? = null) {
        if (header != null) {
            text.typeface = bold; text.textSize = 8.5f * d; text.color = alpha(screenInk, 0.8f * a)
            c.drawText(header, s.centerX(), s.top + s.height() * 0.17f, text)
        }
        val cs = cellSize(s)
        for (i in 0 until 4) {
            val (x, y) = cell(s, i)
            if (i < n) photo(c, x, y, cs, i, a, 0f)
            else {
                fill.color = alpha(Color.WHITE, 0.06f * a)
                c.drawRoundRect(x - cs / 2, y - cs / 2, x + cs / 2, y + cs / 2, cs * 0.18f, cs * 0.18f, fill)
            }
        }
    }

    /** A tiny photo centred at (cx, cy): sky, sun, a hill. */
    private fun photo(c: Canvas, cx: Float, cy: Float, s: Float, kind: Int, a: Float, rot: Float) {
        val col = photos[kind % photos.size]
        c.save()
        c.rotate(rot, cx, cy)
        r2.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        c.save()
        path.reset(); path.addRoundRect(r2, s * 0.18f, s * 0.18f, Path.Direction.CW)
        c.clipPath(path)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, r2.top, 0f, r2.bottom, alpha(col[0], a), alpha(col[1], a), Shader.TileMode.CLAMP)
        c.drawRect(r2, fill)
        fill.shader = null
        fill.color = alpha(Color.rgb(0xFF, 0xF4, 0xD8), 0.95f * a)
        c.drawCircle(r2.left + s * 0.68f, r2.top + s * 0.36f, s * 0.12f, fill)
        fill.color = alpha(col[2], a)
        path.reset()
        path.moveTo(r2.left, r2.bottom)
        path.lineTo(r2.left, r2.top + s * 0.72f)
        path.quadTo(r2.left + s * 0.35f, r2.top + s * 0.48f, r2.left + s * 0.62f, r2.top + s * 0.7f)
        path.quadTo(r2.left + s * 0.82f, r2.top + s * 0.6f, r2.right, r2.top + s * 0.68f)
        path.lineTo(r2.right, r2.bottom)
        path.close()
        c.drawPath(path, fill)
        c.restore()
        stroke.strokeWidth = 1f * d
        stroke.color = alpha(Color.WHITE, 0.5f * a)
        c.drawRoundRect(r2, s * 0.18f, s * 0.18f, stroke)
        c.restore()
    }

    private fun contentRows(c: Canvas, s: RectF, faint: Boolean = false) {
        val a = if (faint) 0.07f else 0.11f
        fill.color = alpha(Color.WHITE, a)
        val x0 = s.left + s.width() * 0.12f
        val x1 = s.right - s.width() * 0.12f
        r2.set(x0, s.top + s.height() * 0.14f, x0 + (x1 - x0) * 0.6f, s.top + s.height() * 0.14f + 5 * d)
        c.drawRoundRect(r2, 3 * d, 3 * d, fill)
        fill.color = alpha(gold, if (faint) 0.12f else 0.22f)
        c.drawCircle(s.centerX(), s.top + s.height() * 0.4f, s.width() * 0.17f, fill)
        fill.color = alpha(Color.WHITE, a)
        for (i in 0..2) {
            val y = s.top + s.height() * (0.62f + i * 0.08f)
            r2.set(x0, y, x1 - (if (i == 2) (x1 - x0) * 0.35f else 0f), y + 4 * d)
            c.drawRoundRect(r2, 2 * d, 2 * d, fill)
        }
    }

    /** iOS-style banner at the top of the iPhone screen. */
    private fun banner(c: Canvas, s: RectF, title: String, line: String, show: Float) {
        if (show <= 0f) return
        val e = easeOut(show)
        val top = s.top + 4 * d + (1f - e) * -30 * d
        r2.set(s.left + 4 * d, top, s.right - 4 * d, top + s.height() * 0.17f)
        fill.color = alpha(Color.rgb(0xF2, 0xF2, 0xF4), 0.97f * show)
        c.drawRoundRect(r2, 8 * d, 8 * d, fill)
        fill.color = alpha(gold, show)
        c.drawRoundRect(r2.left + 5 * d, r2.centerY() - 6 * d, r2.left + 17 * d, r2.centerY() + 6 * d, 3.5f * d, 3.5f * d, fill)
        text.textAlign = Paint.Align.LEFT
        // shrink to fit: translations and narrow phones would otherwise run past the banner
        val room = r2.right - (r2.left + 21 * d) - 5 * d
        val t1 = I18n.t(title)
        val t2 = I18n.t(line)
        text.typeface = bold; text.textSize = 7.5f * d; text.color = alpha(Color.rgb(0x18, 0x17, 0x1C), show)
        fit(t1, room, 7.5f * d)
        c.drawText(t1, r2.left + 21 * d, r2.centerY() - 1.5f * d, text)
        text.typeface = medium; text.color = alpha(Color.rgb(0x55, 0x55, 0x5C), show)
        fit(t2, room, 7.5f * d)
        c.drawText(t2, r2.left + 21 * d, r2.centerY() + 7.5f * d, text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Text size [max] or smaller, so [s] fits in [room]. */
    private fun fit(s: String, room: Float, max: Float) {
        text.textSize = max
        val w = text.measureText(s)
        if (w > room && w > 0f) text.textSize = max * room / w
    }

    private fun qr(c: Canvas, r: RectF) {
        fill.color = Color.WHITE
        c.drawRoundRect(r, 4 * d, 4 * d, fill)
        val n = 11
        val cs = (r.width() - 4 * d) / n
        val x0 = r.left + 2 * d
        val y0 = r.top + 2 * d
        fill.color = Color.rgb(0x14, 0x12, 0x10)
        for (y in 0 until n) for (x in 0 until n) {
            val finder = (x < 3 && y < 3) || (x >= n - 3 && y < 3) || (x < 3 && y >= n - 3)
            if (finder) continue
            if (((x * 7 + y * 13 + x * y) % 5) < 2) c.drawRect(x0 + x * cs, y0 + y * cs, x0 + (x + 1) * cs, y0 + (y + 1) * cs, fill)
        }
        for ((fx, fy) in listOf(0 to 0, n - 3 to 0, 0 to n - 3)) {
            stroke.color = Color.rgb(0x14, 0x12, 0x10); stroke.strokeWidth = cs * 0.8f
            c.drawRect(x0 + fx * cs + cs * 0.4f, y0 + fy * cs + cs * 0.4f, x0 + (fx + 3) * cs - cs * 0.4f, y0 + (fy + 3) * cs - cs * 0.4f, stroke)
        }
    }

    private fun brackets(c: Canvas, r: RectF, out: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 2 * d
        val l = r.width() * 0.22f
        val x0 = r.left - out; val y0 = r.top - out; val x1 = r.right + out; val y1 = r.bottom + out
        c.drawLine(x0, y0, x0 + l, y0, stroke); c.drawLine(x0, y0, x0, y0 + l, stroke)
        c.drawLine(x1, y0, x1 - l, y0, stroke); c.drawLine(x1, y0, x1, y0 + l, stroke)
        c.drawLine(x0, y1, x0 + l, y1, stroke); c.drawLine(x0, y1, x0, y1 - l, stroke)
        c.drawLine(x1, y1, x1 - l, y1, stroke); c.drawLine(x1, y1, x1, y1 - l, stroke)
    }

    private fun wifi(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 3 * d
        for (i in 1..3) {
            val rr = s * 0.4f * i
            r2.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(r2, 225f, 90f, false, stroke)
        }
        fill.color = color
        c.drawCircle(cx, cy, 3 * d, fill)
    }

    private fun dashed(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, phase: Float, a: Float) {
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val step = 9 * d
        var s = phase % step
        stroke.color = alpha(gold, 0.75f * a); stroke.strokeWidth = 2.2f * d
        while (s < len) {
            val f0 = s / len
            val f1 = min(1f, (s + 3.5f * d) / len)
            c.drawLine(x0 + (x1 - x0) * f0, y0 + (y1 - y0) * f0, x0 + (x1 - x0) * f1, y0 + (y1 - y0) * f1, stroke)
            s += step
        }
    }

    private fun caption(c: Canvas, cx: Float, y: Float, label: String) {
        @Suppress("NAME_SHADOWING") val label = I18n.t(label)
        text.typeface = medium; text.textSize = 11 * d; text.color = alpha(ink, 0.65f)
        c.drawText(label, cx, y, text)
    }

    /** A label pill at the top of the scene. */
    private fun chip(c: Canvas, cx: Float, top: Float, label: String, a: Float) {
        if (a <= 0f) return
        @Suppress("NAME_SHADOWING") val label = I18n.t(label)
        text.typeface = medium; text.textSize = 11.5f * d
        fit(label, width - 40 * d, 11.5f * d)
        val tw = text.measureText(label)
        r2.set(cx - tw / 2 - 12 * d, top, cx + tw / 2 + 12 * d, top + 24 * d)
        fill.color = alpha(gold, 0.16f * a)
        c.drawRoundRect(r2, 12 * d, 12 * d, fill)
        stroke.color = alpha(gold, 0.55f * a); stroke.strokeWidth = 1 * d
        c.drawRoundRect(r2, 12 * d, 12 * d, stroke)
        text.color = alpha(gold, a)
        c.drawText(label, cx, top + 16 * d, text)
    }

    /** SolCube's island pill dropping out of the punch hole. */
    private fun pill(c: Canvas, cx: Float, top: Float, label: String, color: Int, inP: Float, outP: Float) {
        val show = easeBack(inP) * (1f - smooth(outP))
        if (show <= 0f) return
        @Suppress("NAME_SHADOWING") val label = I18n.t(label)
        text.typeface = medium; text.textSize = 9.5f * d
        val tw = text.measureText(label)
        val ph = 19 * d
        val pw = tw + ph + 12 * d
        c.save()
        c.translate(cx, top + ph / 2)
        val sc = 0.6f + 0.4f * show.coerceIn(0f, 1.1f)
        c.scale(sc, sc)
        val a = show.coerceIn(0f, 1f)
        r2.set(-pw / 2, -ph / 2, pw / 2, ph / 2)
        fill.color = alpha(Color.BLACK, 0.95f * a)
        c.drawRoundRect(r2, ph / 2, ph / 2, fill)
        val ix = -pw / 2 + ph * 0.55f
        fill.color = alpha(color, a)
        c.drawCircle(ix, 0f, 5.5f * d, fill)
        check(c, ix, 0f, 3f * d, alpha(Color.BLACK, a))
        text.color = alpha(Color.WHITE, a)
        text.textAlign = Paint.Align.LEFT
        c.drawText(label, ix + 9 * d, text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.CENTER
        c.restore()
    }

    private fun bubble(c: Canvas, cx: Float, cy: Float, r: Float, lit: Float, nfc: Boolean) {
        val surface = if (light) Color.BLACK else Color.WHITE
        fill.color = blend(alpha(surface, 0.06f), alpha(gold, 0.24f), lit)
        c.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = 1.3f * d
        stroke.color = blend(alpha(surface, 0.16f), gold, lit)
        c.drawCircle(cx, cy, r, stroke)
        val col = blend(alpha(ink, 0.4f), ink, lit)
        stroke.color = col; stroke.strokeWidth = 2 * d
        if (nfc) {
            for (i in 0..2) {
                val rr = r * (0.2f + i * 0.2f)
                r2.set(cx - r * 0.3f - rr, cy - rr, cx - r * 0.3f + rr, cy + rr)
                c.drawArc(r2, -45f, 90f, false, stroke)
            }
        } else {
            for (i in 1..2) {
                val rr = r * 0.25f * i
                r2.set(cx - rr, cy - rr, cx + rr, cy + rr)
                c.drawArc(r2, 200f, 140f, false, stroke)
                c.drawArc(r2, 20f, 140f, false, stroke)
            }
            fill.color = col
            c.drawCircle(cx, cy, 2.2f * d, fill)
        }
    }

    private fun check(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = max(1.3f * d, s * 0.4f)
        path.reset(); path.moveTo(cx - s, cy); path.lineTo(cx - s * 0.25f, cy + s * 0.7f); path.lineTo(cx + s, cy - s * 0.7f)
        c.drawPath(path, stroke)
    }

    private fun settingsHeader(c: Canvas, s: RectF, title: String) {
        @Suppress("NAME_SHADOWING") val title = I18n.t(title)
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold; text.textSize = 11.5f * d; text.color = screenInk
        c.drawText(title, s.left + s.width() * 0.1f, s.top + s.height() * 0.15f, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun rowCenter(s: RectF, i: Int) = Pair(s.centerX(), s.top + s.height() * (0.27f + i * 0.13f))

    private fun settingsRow(c: Canvas, s: RectF, i: Int, label: String, highlight: Boolean = false, switchOn: Float = -1f) {
        @Suppress("NAME_SHADOWING") val label = I18n.t(label)
        val (_, cy) = rowCenter(s, i)
        r2.set(s.left + s.width() * 0.06f, cy - s.height() * 0.055f, s.right - s.width() * 0.06f, cy + s.height() * 0.055f)
        fill.color = if (highlight) alpha(gold, 0.16f) else alpha(Color.WHITE, 0.05f)
        c.drawRoundRect(r2, 8 * d, 8 * d, fill)
        if (highlight) {
            stroke.color = alpha(gold, 0.6f); stroke.strokeWidth = 1 * d
            c.drawRoundRect(r2, 8 * d, 8 * d, stroke)
        }
        text.textAlign = Paint.Align.LEFT
        text.typeface = medium; text.textSize = 8.5f * d
        text.color = if (highlight) screenInk else alpha(screenInk, 0.6f)
        fit(label, r2.width() - 16 * d - (if (switchOn >= 0f) 30 * d else 0f), 8.5f * d)
        c.drawText(label, r2.left + 8 * d, cy + 3 * d, text)
        text.textAlign = Paint.Align.CENTER
        if (switchOn >= 0f) {
            val sw = 22 * d; val sh = 12 * d
            val x1 = r2.right - 7 * d
            r1.set(x1 - sw, cy - sh / 2, x1, cy + sh / 2)
            fill.color = blend(alpha(Color.WHITE, 0.25f), gold, switchOn)
            c.drawRoundRect(r1, sh / 2, sh / 2, fill)
            fill.color = Color.WHITE
            c.drawCircle(r1.left + sh / 2 + (sw - sh) * switchOn, cy, sh * 0.38f, fill)
        }
    }

    private fun toast(c: Canvas, s: RectF, label: String, a: Float) {
        if (a <= 0f) return
        @Suppress("NAME_SHADOWING") val label = I18n.t(label)
        text.typeface = medium
        fit(label, s.width() - 24 * d, 8.5f * d)
        val tw = text.measureText(label)
        val y = s.bottom - s.height() * 0.12f
        r2.set(s.centerX() - tw / 2 - 9 * d, y - 9 * d, s.centerX() + tw / 2 + 9 * d, y + 9 * d)
        fill.color = alpha(Color.rgb(0x3A, 0x35, 0x2E), a)
        c.drawRoundRect(r2, 9 * d, 9 * d, fill)
        text.color = alpha(screenInk, a)
        c.drawText(label, s.centerX(), y + 3 * d, text)
    }

    // ------------------------------------------------------------------ math

    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun smooth(x: Float) = x * x * (3 - 2 * x)
    private fun easeOut(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)
    private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    private fun easeBack(x: Float): Float {
        if (x <= 0f) return 0f
        val c1 = 1.70158f
        val c3 = c1 + 1
        return 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1)
    }
    private fun alpha(color: Int, a: Float) =
        Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    private fun blend(a: Int, b: Int, f: Float): Int {
        val x = f.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * x).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * x).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * x).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * x).toInt(),
        )
    }
}
