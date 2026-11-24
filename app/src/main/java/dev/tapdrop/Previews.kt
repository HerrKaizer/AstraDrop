package dev.tapdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone browsers can't show very big photos (a 200 MP camera shot renders as a
 * black slide), so the web viewer shows a screen-sized copy of big photos.
 * Downloads, sharing and ZIPs still hand out the full original.
 */
object Previews {
    /** Photos above this many pixels are shown as a preview in the viewer. */
    const val MAX_FULL_PIXELS = 16_000_000L
    private const val LONG_SIDE = 2560
    private const val KEEP = 8

    private val dims = ConcurrentHashMap<String, Long>()
    private val cache = object : LinkedHashMap<String, ByteArray>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > KEEP
    }

    /** Width × height of an image (read from its header only), or -1 if unknown. */
    fun pixels(ctx: Context, uri: Uri): Long = dims.getOrPut(uri.toString()) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toLong() * o.outHeight else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    fun clear() {
        dims.clear()
        synchronized(cache) { cache.clear() }
    }

    fun get(ctx: Context, uri: Uri): ByteArray? {
        val key = uri.toString()
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val long = maxOf(w, h)
                if (long > LONG_SIDE) d.setTargetSize(w * LONG_SIDE / long, h * LONG_SIDE / long)
            }
        } catch (_: Throwable) {
            return null
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        bmp.recycle()
        val bytes = bos.toByteArray()
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }
}
