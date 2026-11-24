package dev.tapdrop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random
import kotlin.math.log10
import kotlin.math.sin

/**
 * Checks the encoder against the JDK's own JPEG decoder and standard tables.
 * javax.imageio isn't on the Android compile classpath, so it's reached by
 * reflection (the unit tests run on a full JDK).
 */
class JpegStripEncoderTest {
    private class Decoded(val img: Any) {
        val width get() = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val height get() = img.javaClass.getMethod("getHeight").invoke(img) as Int
        private val getRGB = img.javaClass.getMethod("getRGB", Int::class.java, Int::class.java)
        fun rgb(x: Int, y: Int) = getRGB.invoke(img, x, y) as Int
    }

    private fun decode(jpg: ByteArray): Decoded {
        val io = Class.forName("javax.imageio.ImageIO")
        val img = io.getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(jpg))
        return Decoded(img!!)
    }

    private fun picture(w: Int, h: Int): IntArray {
        val rnd = Random(1)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (128 + 100 * sin(x / 37.0)).toInt() + rnd.nextInt(9) - 4
            val g = y * 255 / h
            val b = (x xor y) and 0xFF
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g shl 8) or b
        }
    }

    private fun encode(px: IntArray, w: Int, h: Int, strip: Int, orientation: Int = 1): ByteArray {
        val bos = ByteArrayOutputStream()
        val enc = JpegStripEncoder(bos, w, h, 90, orientation)
        var y = 0
        while (y < h) {
            val n = minOf(strip, h - y)
            enc.addRows(px.copyOfRange(y * w, (y + n) * w), n)
            y += n
        }
        return bos.toByteArray()
    }

    @Test
    fun tablesAreTheStandardOnes() {
        val ht = Class.forName("javax.imageio.plugins.jpeg.JPEGHuffmanTable")
        fun table(name: String) = ht.getField(name).get(null)
        fun lengths(name: String) = (ht.getMethod("getLengths").invoke(table(name)) as ShortArray).map { it.toInt() }.toIntArray()
        fun values(name: String) = (ht.getMethod("getValues").invoke(table(name)) as ShortArray).map { it.toInt() and 0xFF }.toIntArray()
        assertArrayEquals(lengths("StdDCLuminance"), JpegStripEncoder.DC_LUM_COUNTS)
        assertArrayEquals(values("StdDCLuminance"), JpegStripEncoder.DC_LUM_VALUES)
        assertArrayEquals(lengths("StdACLuminance"), JpegStripEncoder.AC_LUM_COUNTS)
        assertArrayEquals(values("StdACLuminance"), JpegStripEncoder.AC_LUM_VALUES)
        assertArrayEquals(lengths("StdDCChrominance"), JpegStripEncoder.DC_CHR_COUNTS)
        assertArrayEquals(values("StdDCChrominance"), JpegStripEncoder.DC_CHR_VALUES)
        assertArrayEquals(lengths("StdACChrominance"), JpegStripEncoder.AC_CHR_COUNTS)
        assertArrayEquals(values("StdACChrominance"), JpegStripEncoder.AC_CHR_VALUES)
        // both are in natural (row-major) order
        val qt = Class.forName("javax.imageio.plugins.jpeg.JPEGQTable")
        fun q(name: String) = qt.getMethod("getTable").invoke(qt.getField(name).get(null)) as IntArray
        assertArrayEquals(q("K1Luminance"), JpegStripEncoder.Y_QT)
        assertArrayEquals(q("K2Chrominance"), JpegStripEncoder.UV_QT)
    }

    @Test
    fun decodesBackCloseToTheOriginal() {
        // odd sizes exercise edge padding; strips of 48 rows exercise strip joins
        val w = 333
        val h = 251
        val px = picture(w, h)
        val img = decode(encode(px, w, h, strip = 48))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        var se = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val a = px[y * w + x]
            val b = img.rgb(x, y)
            for (s in intArrayOf(16, 8, 0)) {
                val d = ((a shr s) and 0xFF) - ((b shr s) and 0xFF)
                se += (d * d).toDouble()
            }
        }
        val psnr = 10 * log10(255.0 * 255.0 / (se / (w * h * 3)))
        assertTrue("PSNR too low: $psnr", psnr > 30)
    }

    @Test
    fun stripSizeDoesNotChangeTheOutput() {
        val w = 120
        val h = 100
        val px = picture(w, h)
        assertArrayEquals(encode(px, w, h, strip = 16), encode(px, w, h, strip = 100))
    }

    @Test
    fun orientationIsWrittenAsExif() {
        val jpg = encode(picture(64, 32), 64, 32, strip = 32, orientation = 6)
        assertEquals(0xE1, jpg[3].toInt() and 0xFF)
        assertEquals(64, decode(jpg).width)
    }
}
