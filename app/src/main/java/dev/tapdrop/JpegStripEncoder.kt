package dev.tapdrop

import java.io.OutputStream

/**
 * Baseline JPEG encoder (4:2:0, standard tables) that takes the picture in
 * horizontal strips, so huge photos never have to be held in memory at once.
 * Pixels are ARGB ints. Feed strips top to bottom with [addRows]; the row count
 * of every strip except the last must be a multiple of 16.
 */
class JpegStripEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    quality: Int,
    /** EXIF orientation (1..8) to record; 1 writes no EXIF. */
    private val orientation: Int = 1,
    /** Raw APP2 ICC_PROFILE segments (marker included) to copy into the file. */
    private val iccSegments: List<ByteArray> = emptyList(),
) {
    private val yTable = IntArray(64)
    private val uvTable = IntArray(64)
    private val fdtblY = FloatArray(64)
    private val fdtblUV = FloatArray(64)
    private val ydcHt = huff(DC_LUM_COUNTS, DC_LUM_VALUES)
    private val yacHt = huff(AC_LUM_COUNTS, AC_LUM_VALUES)
    private val uvdcHt = huff(DC_CHR_COUNTS, DC_CHR_VALUES)
    private val uvacHt = huff(AC_CHR_COUNTS, AC_CHR_VALUES)

    private var dcY = 0
    private var dcU = 0
    private var dcV = 0
    private var rowsDone = 0

    private var bitBuf = 0L
    private var bitCnt = 0

    private val blk = FloatArray(64)
    private val du = IntArray(64)
    private val yB = Array(4) { FloatArray(64) }
    private val uB = FloatArray(64)
    private val vB = FloatArray(64)

    init {
        require(width in 1..65535 && height in 1..65535)
        val q = quality.coerceIn(1, 100)
        val sf = if (q < 50) 5000 / q else 200 - q * 2
        for (i in 0 until 64) {
            yTable[ZIGZAG[i]] = ((Y_QT[i] * sf + 50) / 100).coerceIn(1, 255)
            uvTable[ZIGZAG[i]] = ((UV_QT[i] * sf + 50) / 100).coerceIn(1, 255)
        }
        var k = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            fdtblY[k] = (1.0 / (yTable[ZIGZAG[k]] * AASF[row] * AASF[col] * 8.0)).toFloat()
            fdtblUV[k] = (1.0 / (uvTable[ZIGZAG[k]] * AASF[row] * AASF[col] * 8.0)).toFloat()
            k++
        }
        writeHeaders()
    }

    /**
     * Encodes [rows] rows of [pixels] (row-major, [width] per row), starting at
     * row [firstRow] of the array.
     */
    fun addRows(pixels: IntArray, rows: Int, firstRow: Int = 0) {
        require(rows > 0 && rowsDone + rows <= height)
        require(rows % 16 == 0 || rowsDone + rows == height) { "strip height must be a multiple of 16" }
        var y0 = 0
        while (y0 < rows) {
            for (x0 in 0 until width step 16) mcu(pixels, firstRow, rows, x0, y0)
            y0 += 16
        }
        rowsDone += rows
        if (rowsDone == height) finish()
    }

    private fun mcu(px: IntArray, firstRow: Int, rows: Int, x0: Int, y0: Int) {
        for (b in 0 until 4) { java.util.Arrays.fill(yB[b], 0f) }
        java.util.Arrays.fill(uB, 0f)
        java.util.Arrays.fill(vB, 0f)
        for (dy in 0 until 16) {
            val y = minOf(y0 + dy, rows - 1)
            val base = (firstRow + y) * width
            for (dx in 0 until 16) {
                val x = minOf(x0 + dx, width - 1)
                val p = px[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                val b = (dy shr 3) * 2 + (dx shr 3)
                yB[b][(dy and 7) * 8 + (dx and 7)] = 0.299f * r + 0.587f * g + 0.114f * bl - 128f
                val ci = (dy shr 1) * 8 + (dx shr 1)
                uB[ci] += -0.16874f * r - 0.33126f * g + 0.5f * bl
                vB[ci] += 0.5f * r - 0.41869f * g - 0.08131f * bl
            }
        }
        for (i in 0 until 64) { uB[i] *= 0.25f; vB[i] *= 0.25f }
        for (b in 0 until 4) dcY = block(yB[b], fdtblY, dcY, ydcHt, yacHt)
        dcU = block(uB, fdtblUV, dcU, uvdcHt, uvacHt)
        dcV = block(vB, fdtblUV, dcV, uvdcHt, uvacHt)
    }

    /** Forward DCT (AAN), quantize, Huffman-code one block. Returns the new DC predictor. */
    private fun block(src: FloatArray, fdtbl: FloatArray, dc: Int, dcHt: Array<IntArray>, acHt: Array<IntArray>): Int {
        val d = blk
        System.arraycopy(src, 0, d, 0, 64)
        for (pass in 0 until 2) {
            val step = if (pass == 0) 1 else 8
            val stride = if (pass == 0) 8 else 1
            for (i in 0 until 8) {
                val o = i * stride
                val d0 = d[o]; val d1 = d[o + step]; val d2 = d[o + 2 * step]; val d3 = d[o + 3 * step]
                val d4 = d[o + 4 * step]; val d5 = d[o + 5 * step]; val d6 = d[o + 6 * step]; val d7 = d[o + 7 * step]
                val t0 = d0 + d7; val t7 = d0 - d7; val t1 = d1 + d6; val t6 = d1 - d6
                val t2 = d2 + d5; val t5 = d2 - d5; val t3 = d3 + d4; val t4 = d3 - d4
                var t10 = t0 + t3; val t13 = t0 - t3; var t11 = t1 + t2; var t12 = t1 - t2
                d[o] = t10 + t11
                d[o + 4 * step] = t10 - t11
                val z1 = (t12 + t13) * 0.70710677f
                d[o + 2 * step] = t13 + z1
                d[o + 6 * step] = t13 - z1
                t10 = t4 + t5; t11 = t5 + t6; t12 = t6 + t7
                val z5 = (t10 - t12) * 0.38268343f
                val z2 = 0.5411961f * t10 + z5
                val z4 = 1.306563f * t12 + z5
                val z3 = t11 * 0.70710677f
                val z11 = t7 + z3; val z13 = t7 - z3
                d[o + 5 * step] = z13 + z2
                d[o + 3 * step] = z13 - z2
                d[o + step] = z11 + z4
                d[o + 7 * step] = z11 - z4
            }
        }
        for (i in 0 until 64) {
            val v = d[i] * fdtbl[i]
            du[ZIGZAG[i]] = if (v > 0f) (v + 0.5f).toInt() else (v - 0.5f).toInt()
        }
        val diff = du[0] - dc
        if (diff == 0) bits(dcHt[0]) else { val c = cat(diff); bits(dcHt[c]); bits(valBits(diff, c), c) }
        var end = 63
        while (end > 0 && du[end] == 0) end--
        if (end == 0) { bits(acHt[0x00]); return du[0] }
        var i = 1
        while (i <= end) {
            val start = i
            while (du[i] == 0 && i <= end) i++
            var zeros = i - start
            if (zeros >= 16) {
                repeat(zeros shr 4) { bits(acHt[0xF0]) }
                zeros = zeros and 0xF
            }
            val c = cat(du[i])
            bits(acHt[(zeros shl 4) + c])
            bits(valBits(du[i], c), c)
            i++
        }
        if (end != 63) bits(acHt[0x00])
        return du[0]
    }

    private fun cat(v: Int) = 32 - Integer.numberOfLeadingZeros(if (v < 0) -v else v)
    private fun valBits(v: Int, c: Int) = if (v > 0) v else v + (1 shl c) - 1

    private fun bits(code: IntArray) = bits(code[0], code[1])

    private fun bits(value: Int, len: Int) {
        bitBuf = (bitBuf shl len) or (value.toLong() and ((1L shl len) - 1))
        bitCnt += len
        while (bitCnt >= 8) {
            val b = ((bitBuf shr (bitCnt - 8)) and 0xFF).toInt()
            out.write(b)
            if (b == 0xFF) out.write(0)
            bitCnt -= 8
        }
    }

    private fun finish() {
        if (bitCnt > 0) bits((1 shl (8 - bitCnt)) - 1, 8 - bitCnt) // pad with 1s
        out.write(0xFF); out.write(0xD9)
        out.flush()
    }

    private fun w16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }

    private fun writeHeaders() {
        w16(0xFFD8)
        if (orientation in 2..8) {
            w16(0xFFE1); w16(34)
            out.write("Exif".toByteArray()); out.write(0); out.write(0)
            out.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8)) // big-endian TIFF, IFD at 8
            w16(1) // one entry
            w16(0x0112); w16(3); w16(0); w16(1); w16(orientation); w16(0) // Orientation, SHORT, count 1
            w16(0); w16(0) // no next IFD
        } else if (iccSegments.isEmpty()) {
            w16(0xFFE0); w16(16)
            out.write("JFIF".toByteArray()); out.write(0)
            out.write(1); out.write(1); out.write(0); w16(1); w16(1); out.write(0); out.write(0)
        }
        iccSegments.forEach { out.write(it) }
        w16(0xFFDB); w16(132)
        out.write(0); for (i in 0 until 64) out.write(yTable[i])
        out.write(1); for (i in 0 until 64) out.write(uvTable[i])
        w16(0xFFC0); w16(17); out.write(8); w16(height); w16(width); out.write(3)
        out.write(1); out.write(0x22); out.write(0)
        out.write(2); out.write(0x11); out.write(1)
        out.write(3); out.write(0x11); out.write(1)
        val tables = listOf(
            0x00 to (DC_LUM_COUNTS to DC_LUM_VALUES), 0x10 to (AC_LUM_COUNTS to AC_LUM_VALUES),
            0x01 to (DC_CHR_COUNTS to DC_CHR_VALUES), 0x11 to (AC_CHR_COUNTS to AC_CHR_VALUES),
        )
        w16(0xFFC4); w16(2 + tables.sumOf { 17 + it.second.second.size })
        for ((id, t) in tables) {
            out.write(id)
            t.first.forEach { out.write(it) }
            t.second.forEach { out.write(it) }
        }
        w16(0xFFDA); w16(12); out.write(3)
        out.write(1); out.write(0x00); out.write(2); out.write(0x11); out.write(3); out.write(0x11)
        out.write(0); out.write(0x3F); out.write(0)
    }

    companion object {
        /** Natural (row-major) index -> position in zig-zag order. */
        val ZIGZAG = intArrayOf(
            0, 1, 5, 6, 14, 15, 27, 28, 2, 4, 7, 13, 16, 26, 29, 42,
            3, 8, 12, 17, 25, 30, 41, 43, 9, 11, 18, 24, 31, 40, 44, 53,
            10, 19, 23, 32, 39, 45, 52, 54, 20, 22, 33, 38, 46, 51, 55, 60,
            21, 34, 37, 47, 50, 56, 59, 61, 35, 36, 48, 49, 57, 58, 62, 63,
        )
        val Y_QT = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
        )
        val UV_QT = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
        )
        private val AASF = doubleArrayOf(1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379)

        val DC_LUM_COUNTS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        val DC_LUM_VALUES = IntArray(12) { it }
        val DC_CHR_COUNTS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        val DC_CHR_VALUES = IntArray(12) { it }
        val AC_LUM_COUNTS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        val AC_LUM_VALUES = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa,
        )
        val AC_CHR_COUNTS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        val AC_CHR_VALUES = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa,
        )

        /** Symbol -> [code, length], built from the per-length counts (lengths 1..16). */
        private fun huff(counts: IntArray, values: IntArray): Array<IntArray> {
            val t = Array(256) { intArrayOf(0, 0) }
            var code = 0
            var pos = 0
            for (len in 1..16) {
                repeat(counts[len - 1]) {
                    t[values[pos]] = intArrayOf(code, len)
                    pos++
                    code++
                }
                code = code shl 1
            }
            return t
        }
    }
}
