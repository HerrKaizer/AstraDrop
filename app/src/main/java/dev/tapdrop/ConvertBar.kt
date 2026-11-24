package dev.tapdrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/**
 * Overlay for a thumbnail whose DNG is being turned into a JPEG: a dark fade at
 * the bottom with "RAW → JPEG" and a progress bar. While Android decodes the RAW
 * (which reports no progress) the bar slides back and forth; once the JPEG is
 * being written it fills with the real percentage.
 */
class ConvertBar(ctx: Context, private val id: String, private val accent: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val scrim = Paint()
    private val r = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrim.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val p = RawConverter.progress(id) ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, h * 0.45f, w, h, scrim)
        val pad = 8 * d
        val barH = 4 * d
        val barY = h - pad - barH
        val label = if (p < 0) "RAW → JPEG" else "RAW → JPEG  ${(p * 100).toInt()}%"
        c.drawText(label, pad, barY - 6 * d, text)
        r.set(pad, barY, w - pad, barY + barH)
        c.drawRoundRect(r, barH / 2, barH / 2, track)
        val span = w - 2 * pad
        if (p < 0) {
            // sliding segment: there's no real number to show yet
            val t = (SystemClock.uptimeMillis() % 1400) / 1400f
            val seg = span * 0.35f
            val x = pad + (span + seg) * t - seg
            r.set(maxOf(pad, x), barY, minOf(w - pad, x + seg), barY + barH)
        } else {
            r.set(pad, barY, pad + span * p, barY + barH)
        }
        if (r.width() > 0) c.drawRoundRect(r, barH / 2, barH / 2, fill)
        postInvalidateOnAnimation()
    }
}
