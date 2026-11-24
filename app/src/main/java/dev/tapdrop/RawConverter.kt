package dev.tapdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * iPhones can't open DNG (RAW) photos from a web page, so each shared DNG is
 * turned into a JPEG in the background as soon as it's added. When it's done,
 * the shared item is swapped for the JPEG (same id), so the page, downloads and
 * ZIPs all hand out the JPEG. Requests that arrive mid-conversion wait for it.
 */
object RawConverter {
    /**
     * Sizes tried, biggest first (0 = full size). Android won't decode one picture
     * over ~537 MB, and the RAW decoder needs a lot of working memory on top, so a
     * 200 MP RAW has to come out smaller; the first size that fits wins.
     */
    private val SIZES = longArrayOf(0, 130_000_000, 100_000_000, 80_000_000, 64_000_000, 50_000_000, 24_000_000)
    private const val QUALITY = 92
    private const val CHUNK = 64

    private val pool = Executors.newSingleThreadExecutor()
    private val jobs = ConcurrentHashMap<String, Future<SharedItem?>>()
    private val progress = ConcurrentHashMap<String, Float>()

    fun isRaw(item: SharedItem) =
        item.mime.contains("dng", ignoreCase = true) || item.name.endsWith(".dng", ignoreCase = true)

    /**
     * How far the conversion of [id] is: null when none is running (or queued),
     * -1 while the RAW is being decoded (Android gives no progress for that), then
     * 0..1 while the JPEG is written.
     */
    fun progress(id: String): Float? = progress[id]

    fun start(ctx: Context, item: SharedItem) {
        if (!isRaw(item)) return
        val app = ctx.applicationContext
        progress[item.id] = -1f
        jobs[item.id] = pool.submit(Callable {
            try {
                val done = convert(app, item) ?: return@Callable null
                val i = ShareState.items.indexOfFirst { it.id == item.id }
                if (i < 0) {
                    done.uri.path?.let { File(it).delete() }
                    return@Callable null
                }
                ShareState.items[i] = done
                done
            } finally {
                progress.remove(item.id)
                ShareState.notifyChanged()
            }
        })
    }

    fun isConverting(id: String) = jobs[id]?.isDone == false

    /** Waits (up to 3 minutes) for a pending conversion of [id]; no-op otherwise. */
    fun await(id: String) {
        try { jobs[id]?.get(3, TimeUnit.MINUTES) } catch (_: Exception) {}
    }

    /** Drops the JPEG made for [item], if any. */
    fun forget(item: SharedItem) {
        jobs.remove(item.id)?.cancel(false)
        progress.remove(item.id)
        if (item.orig != null) item.uri.path?.let { File(it).delete() }
    }

    private fun convert(ctx: Context, item: SharedItem): SharedItem? {
        val t0 = SystemClock.elapsedRealtime()
        // Full size first, through routes that aren't held to ImageDecoder's ~537 MB
        // cap for ordinary bitmaps (that cap, not the phone's RAM, is what stops a
        // 200 MP picture): shared memory, then BitmapFactory.
        var bmp: Bitmap? = decode(ctx, item.uri, 0, ImageDecoder.ALLOCATOR_SHARED_MEMORY)
        var upright = bmp != null
        if (bmp == null) {
            bmp = decodeFactory(ctx, item.uri)
            upright = false // BitmapFactory never applies the EXIF rotation
        }
        if (bmp == null) {
            for (max in SIZES) {
                bmp = decode(ctx, item.uri, max, ImageDecoder.ALLOCATOR_SOFTWARE)
                if (bmp != null) { upright = max == 0L; break }
            }
        }
        if (bmp == null) return null
        // A decode that didn't rotate the picture gets the DNG's rotation recorded in
        // the JPEG instead (no second copy of the pixels needed).
        val orientation = if (upright) 1 else try {
            ctx.contentResolver.openInputStream(item.uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (_: Exception) { 1 }
        Log.i("TapDrop", "raw: ${item.name} decoded ${bmp.width}x${bmp.height} ${bmp.config} (orientation $orientation) in ${SystemClock.elapsedRealtime() - t0} ms")
        val dir = File(ctx.cacheDir, "converted").apply { mkdirs() }
        val name = item.name.replace(Regex("\\.dng$", RegexOption.IGNORE_CASE), "") + ".jpg"
        val out = File(dir, "${item.id}-$name")
        return try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * CHUNK)
            progress[item.id] = 0f
            out.outputStream().buffered(1 shl 20).use { os ->
                val enc = JpegStripEncoder(os, w, h, QUALITY, orientation)
                var y = 0
                while (y < h) {
                    val n = minOf(CHUNK, h - y)
                    bmp.getPixels(px, 0, w, 0, y, w, n)
                    enc.addRows(px, n)
                    y += n
                    progress[item.id] = y.toFloat() / h
                }
            }
            Log.i("TapDrop", "raw: ${item.name} -> ${out.length()} bytes in ${SystemClock.elapsedRealtime() - t0} ms")
            SharedItem(item.id, Uri.fromFile(out), name, "image/jpeg", out.length(), orig = item.uri, origMime = item.mime)
        } catch (t: Throwable) {
            Log.w("TapDrop", "raw: encode failed", t)
            out.delete()
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Full-size decode with BitmapFactory (no EXIF rotation applied). */
    private fun decodeFactory(ctx: Context, uri: Uri): Bitmap? = try {
        val o = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, o) }
            .also { if (it == null) Log.w("TapDrop", "raw: BitmapFactory returned nothing") }
    } catch (t: Throwable) {
        Log.w("TapDrop", "raw: BitmapFactory failed: $t")
        null
    }

    /** Decodes the image in sRGB; [maxPixels] > 0 asks for a scaled-down version. */
    private fun decode(ctx: Context, uri: Uri, maxPixels: Long, allocator: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = allocator
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width.toLong()
            val h = info.size.height.toLong()
            if (maxPixels > 0 && w * h > maxPixels) {
                val f = sqrt(maxPixels.toDouble() / (w * h))
                d.setTargetSize((w * f).toInt(), (h * f).toInt())
            }
        }
    } catch (t: Throwable) {
        Log.w("TapDrop", "raw: decode at max $maxPixels px (allocator $allocator) failed: ${t.message}")
        null
    }
}
