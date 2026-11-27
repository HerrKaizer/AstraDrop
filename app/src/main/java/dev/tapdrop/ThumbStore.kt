package dev.tapdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A ready-made thumbnail: WebP bytes plus video length (or -1). */
class Thumb(val bytes: ByteArray, val durationMs: Long, val mime: String = "image/webp")

/**
 * One thumbnail per file, shared by the app's grid and the web page. Thumbnails
 * are made in the background as soon as a file is added, so by the time an
 * iPhone opens the page they're already waiting in memory.
 */
object ThumbStore {
    private const val PX = 384
    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<String, Future<Thumb?>>()

    fun prefetch(ctx: Context, uri: Uri, mime: String) {
        future(ctx.applicationContext, uri, mime)
    }

    /** Blocks until the thumbnail is ready (or gives up). Call off the main thread. */
    fun get(ctx: Context, uri: Uri, mime: String): Thumb? = try {
        future(ctx.applicationContext, uri, mime).get(30, TimeUnit.SECONDS)
    } catch (_: Exception) {
        null
    }

    fun forget(uri: Uri) {
        jobs.remove(uri.toString())
    }

    fun clear() = jobs.clear()

    private fun future(ctx: Context, uri: Uri, mime: String): Future<Thumb?> =
        jobs.computeIfAbsent(uri.toString()) { pool.submit(Callable { make(ctx, uri, mime) }) }

    private fun make(ctx: Context, uri: Uri, mime: String): Thumb? {
        val isVideo = mime.startsWith("video/")
        var bmp: Bitmap? = try {
            ctx.contentResolver.loadThumbnail(uri, Size(PX, PX), null)
        } catch (_: Exception) {
            null
        }
        var dur = -1L
        try {
            if (isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    if (bmp == null) bmp = r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PX, PX)
                } finally {
                    r.release()
                }
            } else if (bmp == null && mime.startsWith("image/")) {
                bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.setTargetSampleSize(maxOf(1, minOf(info.size.width, info.size.height) / PX))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        } catch (_: Exception) {
        }
        val b = bmp ?: return null
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 74, bos)
        return Thumb(bos.toByteArray(), dur)
    }
}
