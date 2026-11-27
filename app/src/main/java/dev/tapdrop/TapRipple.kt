package dev.tapdrop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import java.lang.reflect.Method
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Top-edge champagne-gold NFC tap ripple (adapted from SolCube's RippleView AGSL shader),
 * plus sequential LRA haptics and Vivo's tap chime. Applied directly to MainActivity's root
 * view via RenderEffect.createRuntimeShaderEffect so it refracts the live AstraDrop UI.
 */
class TapRipple(ctx: Context) {

    private val app = ctx.applicationContext
    private val dp = app.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val shader = RuntimeShader(AGSL)

    private val goldPalette = arrayOf(
        floatArrayOf(1.00f, 0.87f, 0.62f), // champagne
        floatArrayOf(1.00f, 0.71f, 0.27f), // warm amber
        floatArrayOf(1.00f, 0.91f, 0.70f), // pale gold
        floatArrayOf(0.96f, 0.62f, 0.14f), // deep honey
        floatArrayOf(1.00f, 0.82f, 0.48f), // sunlit gold
        floatArrayOf(1.00f, 0.95f, 0.80f), // ivory cream
    )

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(audioAttrs)
        .build()
    private val soundId: Int
    @Volatile private var soundLoaded = false
    private val vibrator: Vibrator? = app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val vibratorPro: Method? by lazy {
        val vib = vibrator ?: return@lazy null
        runCatching { vib.javaClass.getMethod("vibratorPro", Int::class.javaPrimitiveType) }
            .recoverCatching { vib.javaClass.getDeclaredMethod("vibratorPro", Int::class.javaPrimitiveType).apply { isAccessible = true } }
            .getOrNull()
    }
    private var loggedPro = false

    private var activeTarget: View? = null
    private var activeTick: Runnable? = null
    private var followHaptic: Runnable? = null
    private var running = false

    init {
        soundPool.setOnLoadCompleteListener { _, id, status ->
            if (id == soundId && status == 0) soundLoaded = true
        }
        soundId = soundPool.load(app, R.raw.nfc_touch, 1)
    }

    /** Plays the champagne-gold top-edge ripple on [target], together with haptics and chime. */
    fun play(target: View) {
        playFeedback()
        cancel(activeTarget ?: target)
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (target.width <= 0 || target.height <= 0 || !target.isAttachedToWindow) return

        val w = target.width.toFloat()
        val h = target.height.toFloat()
        val cx = w * 0.5f
        val cy = 0f
        val far = hypot(cx, h)
        val maxR = far + 160f * dp

        randomizeWash(w, h)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("c", cx, cy)

        running = true
        activeTarget = target
        val start = SystemClock.uptimeMillis()
        var doneAt = 0L

        val tick = object : Runnable {
            override fun run() {
                if (!running || activeTarget !== target || !target.isAttachedToWindow) {
                    if (activeTarget === target) cancel(target)
                    return
                }
                val now = SystemClock.uptimeMillis()
                val t = ((now - start).toFloat() / DURATION_MS).coerceIn(0f, 1f)

                val wt = ((t - 0.05f) / 0.80f).coerceIn(0f, 1f)
                val e = 0.30f * wt + 0.70f * (1f - (1f - wt).pow(2.2f))
                val radius = 10f * dp + e * maxR
                val band = (105f + 115f * e) * dp
                val waveIn = (wt / 0.08f).coerceIn(0f, 1f)
                val pass = ((radius - (far - 0.2f * band)) / (0.7f * band)).coerceIn(0f, 1f)
                val snap = 1f - pass * pass * (3f - 2f * pass)
                val waveDone = pass >= 1f
                val waveOut = (1f - wt).pow(0.6f) * snap

                val amp = minOf(80f * dp, 0.8f * band) * waveIn * waveOut
                val blur = 2.2f * dp * waveIn * waveOut
                val glow = 0.58f * waveIn * waveOut

                val st = (t / 0.45f).coerceIn(0f, 1f)
                val swell = 0.32f * sin(PI.toFloat() * st)
                val swellR = 165f * dp

                val washA = 0.42f * (wt / 0.25f).coerceIn(0f, 1f) * (1f - ((t - 0.70f) / 0.30f).coerceIn(0f, 1f))
                val orbIn = (t / 0.07f).coerceIn(0f, 1f)
                val orbOut = ((t - 0.08f) / 0.16f).coerceIn(0f, 1f)
                val orbA = orbIn * (1f - orbOut * orbOut * (3f - 2f * orbOut))
                val orbR = (145f + 45f * orbIn) * dp * (1f - 0.30f * orbOut)

                if ((waveDone || t >= 1f) && doneAt == 0L) doneAt = now
                val f = if (doneAt == 0L) 0f else ((now - doneAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
                val effectA = 1f - f * f * (3f - 2f * f)

                shader.setFloatUniform("r", radius)
                shader.setFloatUniform("w", band)
                shader.setFloatUniform("amp", amp)
                shader.setFloatUniform("blur", blur)
                shader.setFloatUniform("glowA", glow)
                shader.setFloatUniform("swell", swell)
                shader.setFloatUniform("swellR", swellR)
                shader.setFloatUniform("washA", washA)
                shader.setFloatUniform("orbA", orbA)
                shader.setFloatUniform("orbR", orbR)
                shader.setFloatUniform("t", t)
                shader.setFloatUniform("effectA", effectA)

                target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"))
                if (f < 1f) {
                    target.postOnAnimation(this)
                } else {
                    cancel(target)
                }
            }
        }
        activeTick = tick
        tick.run()
    }

    fun cancel(target: View?) {
        running = false
        val t = target ?: activeTarget
        activeTick?.let { t?.removeCallbacks(it) }
        activeTick = null
        activeTarget = null
        t?.setRenderEffect(null)
    }

    fun release(target: View?) {
        followHaptic?.let { main.removeCallbacks(it) }
        followHaptic = null
        cancel(target)
        soundPool.release()
    }

    private fun playFeedback() {
        if (soundLoaded) {
            soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1f)
        } else {
            runCatching {
                MediaPlayer.create(app, R.raw.nfc_touch, audioAttrs, 0)?.apply {
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { mp, _, _ -> mp.release(); true }
                    start()
                }
            }
        }
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
        val click = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        followHaptic?.let { main.removeCallbacks(it) }
        followHaptic = null
        val proRet = runCatching {
            (vibratorPro?.invoke(vib, 9001) as? Number)?.toLong() ?: -1L
        }.getOrElse { e ->
            if (!loggedPro) {
                loggedPro = true
                Log.w("TapDrop", "tap haptic: vibratorPro(9001) failed: ${e.message}")
            }
            -1L
        }
        if (!loggedPro) {
            loggedPro = true
            Log.i("TapDrop", "tap haptic: vibratorPro(9001)=$proRet")
        }
        if (proRet >= 0L) {
            val r = Runnable { vib.vibrate(click, attrs) }
            followHaptic = r
            main.postDelayed(r, 650L)
        } else {
            vib.vibrate(click, attrs)
        }
    }

    private fun randomizeWash(w: Float, h: Float) {
        val rnd = Random(SystemClock.uptimeMillis())
        val picks = goldPalette.indices.shuffled(rnd).take(4)
        for (i in 0 until 4) {
            val qx = if (i % 2 == 0) 0.25f else 0.75f
            val qy = if (i < 2) 0.25f else 0.75f
            val bx = (qx + rnd.nextFloat() * 0.5f - 0.25f) * w
            val by = (qy + rnd.nextFloat() * 0.5f - 0.25f) * h
            val br = (0.45f + rnd.nextFloat() * 0.35f) * w
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val speed = (0.06f + rnd.nextFloat() * 0.08f) * w
            shader.setFloatUniform("b$i", bx, by, br)
            shader.setFloatUniform("v$i", cos(ang) * speed, sin(ang) * speed)
            val c = goldPalette[picks[i]]
            shader.setFloatUniform("c$i", c[0], c[1], c[2])
        }
        shader.setFloatUniform("hueShift", rnd.nextFloat())
    }

    companion object {
        private const val DURATION_MS = 1150L
        private const val FADE_MS = 160f

        private const val AGSL = """
uniform shader img;
uniform float2 size;
uniform float2 c;
uniform float r;
uniform float w;
uniform float amp;
uniform float blur;
uniform float glowA;
uniform float swell;
uniform float swellR;
uniform float washA;
uniform float orbA;
uniform float orbR;
uniform float effectA;
uniform float t;
uniform float hueShift;
uniform float3 b0; uniform float3 b1; uniform float3 b2; uniform float3 b3;
uniform float2 v0; uniform float2 v1; uniform float2 v2; uniform float2 v3;
uniform float3 c0; uniform float3 c1; uniform float3 c2; uniform float3 c3;

half3 spec(float h) {
    h = fract(h) * 4.0;
    half3 a = half3(1.00, 0.87, 0.62);
    half3 b = half3(1.00, 0.71, 0.27);
    half3 cc = half3(1.00, 0.95, 0.80);
    half3 d = half3(0.96, 0.62, 0.14);
    float f = smoothstep(0.0, 1.0, fract(h));
    if (h < 1.0) return mix(a, b, f);
    if (h < 2.0) return mix(b, cc, f);
    if (h < 3.0) return mix(cc, d, f);
    return mix(d, a, f);
}

float blob(float2 p, float3 b, float2 v) {
    float2 q = p - (b.xy + v * t);
    return exp(-dot(q, q) / (b.z * b.z));
}

half4 wash(float2 p) {
    float w0 = blob(p, b0, v0), w1 = blob(p, b1, v1), w2 = blob(p, b2, v2), w3 = blob(p, b3, v3);
    float s = w0 + w1 + w2 + w3;
    half3 col = (half3(c0) * w0 + half3(c1) * w1 + half3(c2) * w2 + half3(c3) * w3) / half(max(s, 1e-4));
    return half4(col, half(clamp(s, 0.0, 1.0)));
}

half4 main(float2 q) {
    float2 hi = max(size - float2(0.5), float2(0.5));
    half4 baseCol = img.eval(clamp(q, float2(0.5), hi));
    if (effectA <= 0.001) return baseCol;

    float2 p = q;
    float2 d = p - c;
    float dist = length(d);
    float2 dir = dist > 0.001 ? d / dist : float2(0.0, 1.0);
    float x = (dist - r) / w;
    float env = exp(-x * x * 1.4);

    // wave refraction: magnify toward the crest with a trough behind it
    float o = amp * env * (x * 1.3 - 0.45 * sin(x * 3.14159));
    float2 sp = p - dir * o;
    // lens swell at the top-center origin
    float sw = swell * exp(-dist * dist / (swellR * swellR));
    sp -= d * sw;

    float b = blur * env;
    float k = (env * 0.22 * (amp > 0.0 ? 1.0 : 0.0) + sw * 0.25) * 0.28;
    float2 dd = (dir * o + d * sw) * k;
    half3 acc = half3(0.0);
    for (int i = -2; i <= 2; i++) {
        float2 s = sp + dir * (float(i) * b);
        acc.r += img.eval(clamp(s - dd, float2(0.5), hi)).r;
        acc.g += img.eval(clamp(s, float2(0.5), hi)).g;
        acc.b += img.eval(clamp(s + dd, float2(0.5), hi)).b;
    }
    half3 col = acc / 5.0;

    // colour wash riding just behind the crest: a band about two wave-widths deep that moves
    // with it, so the screen behind the wave is clear again (it used to fill everything inside)
    float front = clamp((r + w * 0.6 - dist) / (w * 0.8), 0.0, 1.0);
    float tail = clamp((dist - (r - w * 2.2)) / (w * 1.3), 0.0, 1.0);
    float inside = front * front * (3.0 - 2.0 * front) * tail * tail * (3.0 - 2.0 * tail);
    half4 film = wash(p);
    float wa = washA * inside * float(film.a);
    half lum = dot(col, half3(0.30, 0.59, 0.11));
    half3 lifted = 1.0 - (1.0 - col) * (1.0 - film.rgb * 0.75);
    half3 tinted = col * mix(half3(1.0), film.rgb, 0.85);
    half3 blended = mix(lifted, tinted, smoothstep(0.30, 0.75, lum));
    col = mix(col, blended, half(wa));

    // glassy ring: inner shadow, broad warm-gold rim (visible on light and dark screens)
    float shade = exp(-(x + 0.6) * (x + 0.6) * 3.5);
    col *= half(1.0 - 0.26 * glowA * shade);
    float rim = exp(-(x - 0.15) * (x - 0.15) * 7.0);
    float ang = atan(d.y, d.x) / 6.2831853;
    half3 tint = mix(half3(1.0), spec(ang + hueShift + t * 0.3), 0.55);
    float g = glowA * (0.8 * rim + 0.2 * env);
    half lumR = dot(col, half3(0.30, 0.59, 0.11));
    half3 glowC = col + tint * half(g * 0.55);
    half3 tintC = mix(col, col * tint, half(clamp(g * 1.4, 0.0, 1.0)));
    col = mix(glowC, tintC, smoothstep(0.35, 0.8, lumR));
    col = mix(col, tint, half(clamp(g, 0.0, 1.0)) * 0.25);

    // wide top-bezel luminous bloom centered where the phones touch
    float2 od = float2((p.x - c.x) * 0.60, p.y - c.y);
    float od2 = length(od) / max(orbR, 1.0);
    float ring = exp(-(od2 - 0.9) * (od2 - 0.9) * 6.0);
    half3 hue = spec(atan(od.y, od.x) / 6.2831853 + t * 1.2 + hueShift);
    half3 body = spec(hueShift + 0.25);
    float glowBody = exp(-od2 * od2 * 1.2);
    half3 orbCol = mix(body, hue, half(clamp(ring * 1.5, 0.0, 1.0)));
    half lumO = dot(col, half3(0.30, 0.59, 0.11));
    half3 onDark = col + orbCol * half(glowBody * 0.95);
    half3 onLight = mix(col, col * orbCol, half(clamp(glowBody * 1.3, 0.0, 1.0)));
    half3 withOrb = mix(onDark, onLight, smoothstep(0.35, 0.8, lumO));
    withOrb += half3(1.0) * half(exp(-od2 * od2 * 8.0) * 0.42);
    col = mix(col, withOrb, half(orbA));

    half3 outRgb = mix(baseCol.rgb, min(col, half3(1.0)), half(effectA));
    return half4(outRgb, baseCol.a);
}
"""
    }
}
