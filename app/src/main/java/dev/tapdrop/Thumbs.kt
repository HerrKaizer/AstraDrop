package dev.tapdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Puts thumbnails from [ThumbStore] into ImageViews, off the main thread, with a decoded-bitmap cache. */
class Thumbs(context: Context) {
    private val ctx = context.applicationContext
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val durations = HashMap<String, Long>()
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun clear() = cache.evictAll()

    fun load(uri: Uri, mime: String, target: ImageView, onDuration: ((Long) -> Unit)? = null) {
        val key = uri.toString()
        target.tag = key
        cache.get(key)?.let { bmp ->
            target.setImageBitmap(bmp)
            durations[key]?.let { onDuration?.invoke(it) }
            return
        }
        target.setImageDrawable(null)
        pool.execute {
            val t = ThumbStore.get(ctx, uri, mime)
            val bmp = t?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
            main.post {
                if (bmp != null) cache.put(key, bmp)
                val dur = t?.durationMs ?: -1L
                if (dur >= 0) durations[key] = dur
                if (target.tag == key) {
                    if (bmp != null) target.setImageBitmap(bmp)
                    if (dur >= 0) onDuration?.invoke(dur)
                }
            }
        }
    }
}
