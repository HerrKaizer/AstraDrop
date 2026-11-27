package dev.tapdrop

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/**
 * AstraDrop's look, shared by every screen and pop-up: SolCube's "Sol" palette (night = the icon's
 * black with an amber-gold glow on buttons and links; day = porcelain with ink buttons and amber
 * links) and SolCube's Light / System / Dark setting.
 */
object Look {
    data class Palette(
        val base: Int, val glows: List<Int>, val solid: Int,
        val glass: Int, val sheen: Int, val stroke: Int, val tile: Int, val chip: Int, val field: Int,
        val fg: Int, val sub: Int, val accent: Int, val accent2: Int, val warn: Int, val danger: Int, val ok: Int,
        /** Text/icon accent, text on filled buttons, filled-button rim (0 = none) and halo (0 = none). */
        val link: Int, val onAccent: Int, val fillStroke: Int = 0, val halo: Int = 0,
        /** Filled-button glass tint and highlight strength. */
        val fill: Int, val fillShine: Float,
        /** Outlined (secondary) buttons: glass tint and rim, readable in pop-ups too. */
        val btn: Int, val btnStroke: Int,
    )

    const val THEME_LIGHT = 0
    const val THEME_SYSTEM = 1
    const val THEME_DARK = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun themeMode(ctx: Context): Int = prefs(ctx).getInt("theme_mode", THEME_SYSTEM)
    fun setThemeMode(ctx: Context, mode: Int) { prefs(ctx).edit().putInt("theme_mode", mode.coerceIn(0, 2)).apply() }

    fun isDark(ctx: Context): Boolean = when (themeMode(ctx)) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)

    fun palette(ctx: Context) = if (isDark(ctx)) Palette(
        base = Color.rgb(0x04, 0x03, 0x02),
        glows = listOf(Color.rgb(0x1E, 0x12, 0x05), Color.rgb(0x12, 0x0C, 0x06), Color.rgb(0x1A, 0x10, 0x04), Color.rgb(0x0E, 0x0B, 0x08)),
        solid = Color.rgb(0x11, 0x0E, 0x0A),
        glass = white(0.055f), sheen = white(0.04f), stroke = white(0.10f),
        tile = white(0.06f), chip = white(0.05f), field = white(0.07f),
        fg = Color.rgb(0xF5, 0xF1, 0xEA), sub = Color.rgb(0xBC, 0xB3, 0xA6),
        accent = Color.rgb(0xFF, 0xDF, 0x9E), accent2 = Color.rgb(0xE0, 0x8C, 0x24),
        warn = Color.rgb(0xFF, 0x86, 0x5E), danger = Color.rgb(0xFF, 0x5F, 0x57), ok = Color.rgb(0x5E, 0xE0, 0x82),
        link = Color.rgb(0xFF, 0xB6, 0x45), onAccent = Color.rgb(0x1E, 0x10, 0x02),
        fillStroke = Color.argb(210, 0xFF, 0xE4, 0x9A), halo = Color.argb(185, 0xFF, 0x9E, 0x1A),
        fill = Color.argb(250, 0xF4, 0xB2, 0x4C), fillShine = 1.1f,
        btn = Color.argb(40, 255, 255, 255), btnStroke = Color.argb(70, 0xFF, 0xE4, 0x9A),
    ) else Palette(
        base = Color.rgb(0xF6, 0xF2, 0xEC),
        glows = listOf(Color.rgb(0xEE, 0xD6, 0xAE), Color.rgb(0xE6, 0xDC, 0xCC), Color.rgb(0xF2, 0xD9, 0xB4), Color.rgb(0xE2, 0xDA, 0xD0)),
        solid = Color.rgb(0xFB, 0xF8, 0xF3),
        glass = white(0.58f), sheen = white(0.5f), stroke = white(0.9f),
        tile = white(0.55f), chip = white(0.45f), field = white(0.65f),
        fg = Color.rgb(0x18, 0x17, 0x1C), sub = Color.rgb(0x5F, 0x5A, 0x55),
        accent = Color.rgb(0x2A, 0x25, 0x20), accent2 = Color.rgb(0x12, 0x0F, 0x0C),
        warn = Color.rgb(0xB8, 0x3E, 0x1C), danger = Color.rgb(0xC6, 0x2A, 0x22), ok = Color.rgb(0x16, 0x8F, 0x3E),
        link = Color.rgb(0xA8, 0x62, 0x10), onAccent = Color.rgb(0xFF, 0xE2, 0xA8),
        fill = Color.argb(242, 0x1E, 0x1A, 0x16), fillShine = 0.37f,
        btn = Color.argb(175, 255, 255, 255), btnStroke = Color.argb(46, 0x2A, 0x20, 0x14),
    )

    private val fonts = HashMap<Int, Typeface>()
    fun font(ctx: Context, weight: Int): Typeface = fonts.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "fonts/GoogleSansFlex.ttf").setFontVariationSettings("'wght' $weight").build()
        } catch (_: Exception) { null } ?: Typeface.DEFAULT
    }
}
