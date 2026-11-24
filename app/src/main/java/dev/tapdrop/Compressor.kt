package dev.tapdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * On-request photo compression for the web viewer. The photo keeps its full
 * resolution; it's re-saved as a quality-90 JPEG with only the orientation and
 * colour profile kept, which drops most of the extra data big camera JPEGs carry
 * (near-100% quality, embedded previews, maker notes, HDR layers).
 *
 * Huge photos (200 MP) don't fit in memory as one picture, so the photo is read
 * and written in horizontal strips; that also gives real progress.
 * The shared item is then swapped for the smaller file (same id), so viewing,
 * downloading and ZIPs all hand out the compressed one.
 */
object Compressor {
    private const val QUALITY = 90
    private const val STRIP = 1024 // rows decoded at a time (multiple of 16)
    private const val CHUNK = 64 // rows encoded between progress updates
    private val lock = Any()

    /** Progress of a running compression: when it started and how far (0..1) it got. */
    class Progress {
        /** When the work itself started (0 while still waiting for another photo). */
        @Volatile var startMs = 0L
        @Volatile var done = 0f
    }

    private val running = ConcurrentHashMap<String, Progress>()

    fun progress(id: String): Progress? = running[id]

    /** Photos that can be compressed: still images except GIFs, PNGs and SVGs. */
    fun canCompress(item: SharedItem) =
        !item.compressed && item.mime.startsWith("image/") &&
            !item.mime.contains("gif") && !item.mime.contains("png") && !item.mime.contains("svg")

    /** Compresses [id] and returns the new item, or null if it failed. One photo at a time. */
    fun compress(ctx: Context, id: String): SharedItem? {
        val mine = Progress()
        // a second request for the same photo shares the first one's progress and just waits
        val prog = running.putIfAbsent(id, mine) ?: mine
        try {
            return synchronized(lock) { doCompress(ctx, id, prog) }
        } finally {
            if (prog === mine) running.remove(id)
        }
    }

    private fun doCompress(ctx: Context, id: String, prog: Progress): SharedItem? {
        RawConverter.await(id)
        val item = ShareState.items.find { it.id == id } ?: return null
        if (item.compressed) return item
        if (!canCompress(item)) return null
        val cr = ctx.contentResolver
        val dir = File(ctx.cacheDir, "converted").apply { mkdirs() }
        val name = item.name.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "") + ".jpg"
        val out = File(dir, "${item.id}-c-$name")
        val t0 = SystemClock.elapsedRealtime()
        prog.startMs = t0
        try {
            val orientation = try {
                cr.openInputStream(item.uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
            } catch (_: Exception) { 1 }
            val icc = if (item.mime.contains("jpeg") || item.mime.contains("jpg"))
                cr.openInputStream(item.uri)?.use { iccSegments(it) } ?: emptyList()
            else emptyList()
            @Suppress("DEPRECATION")
            val dec = cr.openInputStream(item.uri)?.use { BitmapRegionDecoder.newInstance(it, false) } ?: return null
            try {
                val w = dec.width
                val h = dec.height
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var strip = dec.decodeRegion(Rect(0, 0, w, minOf(STRIP, h)), opts) ?: return null
                // Pixels are copied as-is, so they must either carry the source's own colour
                // profile (copied above) or be converted to plain sRGB.
                val cs = strip.colorSpace
                if (icc.isEmpty() && cs != null && !cs.isSrgb) {
                    opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                    strip.recycle()
                    strip = dec.decodeRegion(Rect(0, 0, w, minOf(STRIP, h)), opts) ?: return null
                }
                val raw = ByteBuffer.allocate(w * STRIP * 4)
                val px = IntArray(w * STRIP)
                out.outputStream().buffered(1 shl 20).use { os ->
                    val enc = JpegStripEncoder(os, w, h, QUALITY, orientation, icc)
                    var y = 0
                    while (y < h) {
                        val n = minOf(STRIP, h - y)
                        if (y > 0) {
                            strip.recycle()
                            strip = dec.decodeRegion(Rect(0, y, w, y + n), opts) ?: return null
                        }
                        // copyPixelsToBuffer keeps the bitmap's own colour space (getPixels would
                        // convert to sRGB); ARGB_8888 is laid out R,G,B,A in memory.
                        raw.clear()
                        raw.limit(strip.byteCount)
                        strip.copyPixelsToBuffer(raw)
                        val a = raw.array()
                        val sw = strip.width
                        val rb = strip.rowBytes
                        for (r in 0 until n) for (x in 0 until w) {
                            val o = r * rb + minOf(x, sw - 1) * 4
                            px[r * w + x] = (0xFF shl 24) or ((a[o].toInt() and 0xFF) shl 16) or
                                ((a[o + 1].toInt() and 0xFF) shl 8) or (a[o + 2].toInt() and 0xFF)
                        }
                        var r = 0
                        while (r < n) {
                            val k = minOf(CHUNK, n - r)
                            enc.addRows(px, k, r)
                            r += k
                            prog.done = (y + r).toFloat() / h
                        }
                        y += n
                    }
                    strip.recycle()
                }
                Log.i("TapDrop", "compress: ${item.name} ${w}x$h ${item.size} -> ${out.length()} bytes in ${SystemClock.elapsedRealtime() - t0} ms")
            } finally {
                dec.recycle()
            }
        } catch (t: Throwable) {
            Log.w("TapDrop", "compress failed for ${item.name}", t)
            out.delete()
            return null
        }
        val done = if (item.size in 1..out.length()) {
            // already as small as it gets: keep the original, just mark it
            out.delete()
            item.copy(compressed = true)
        } else {
            SharedItem(
                item.id, Uri.fromFile(out), name, "image/jpeg", out.length(),
                orig = item.orig ?: item.uri, origMime = item.origMime ?: item.mime, compressed = true,
            )
        }
        val i = ShareState.items.indexOfFirst { it.id == id }
        if (i < 0) {
            if (done.uri != item.uri) out.delete()
            return null
        }
        ShareState.items[i] = done
        before[id] = item // kept (with its file) so «Вернуть» can bring it back
        ShareState.notifyChanged()
        return done
    }

    /** What each compressed photo was before compression. */
    private val before = ConcurrentHashMap<String, SharedItem>()

    /** Undoes [compress]: the original comes back and the compressed copy is deleted. */
    fun revert(id: String): SharedItem? = synchronized(lock) {
        val prev = before.remove(id) ?: return null
        val i = ShareState.items.indexOfFirst { it.id == id }
        if (i < 0) {
            dropFile(prev)
            return null
        }
        val cur = ShareState.items[i]
        ShareState.items[i] = prev
        if (cur.uri != prev.uri) dropFile(cur)
        ShareState.notifyChanged()
        prev
    }

    /** Called when a photo stops being shared: drops the kept pre-compression file. */
    fun forget(item: SharedItem) {
        val prev = before.remove(item.id) ?: return
        if (prev.uri != item.uri) dropFile(prev)
    }

    /** Deletes a file this app made (converted/compressed copies); never the user's own. */
    private fun dropFile(item: SharedItem) {
        if (item.orig != null && item.uri.scheme == "file") item.uri.path?.let { File(it).delete() }
    }

    /** The APP2 "ICC_PROFILE" segments of a JPEG, each as raw bytes including its marker. */
    private fun iccSegments(input: InputStream): List<ByteArray> {
        val s = DataInputStream(input.buffered())
        if (s.readUnsignedShort() != 0xFFD8) return emptyList()
        val out = mutableListOf<ByteArray>()
        while (true) {
            val marker = s.readUnsignedShort()
            if (marker and 0xFF00 != 0xFF00 || marker == 0xFFDA || marker == 0xFFD9) break
            val len = s.readUnsignedShort()
            if (len < 2) break
            val body = ByteArray(len - 2)
            s.readFully(body)
            if (marker == 0xFFE2 && body.size > 12 && String(body, 0, 11, Charsets.ISO_8859_1) == "ICC_PROFILE") {
                out += byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (len shr 8).toByte(), len.toByte()) + body
            }
            if (marker in 0xFFC0..0xFFCF && marker != 0xFFC4 && marker != 0xFFCC) break // frame header: no APP segments follow
        }
        return out
    }
}
