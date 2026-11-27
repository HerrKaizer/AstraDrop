package dev.tapdrop

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the iPhone has sent this phone, kept between sessions (newest first): files
 * (which live in Pictures, Movies or Download under AstraDrop) and texts. Stored as a small
 * JSON file in the app's own storage.
 */
object History {
    private const val MAX = 300

    data class Entry(
        val time: Long,
        /** "f" a file, "t" a text. */
        val kind: String,
        val name: String,
        val uri: Uri?,
        val mime: String,
        val text: String,
        val from: String,
    ) {
        val isText get() = kind == "t"
    }

    private val entries = CopyOnWriteArrayList<Entry>()
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    fun list(ctx: Context): List<Entry> {
        load(ctx)
        return entries.toList()
    }

    fun addFile(ctx: Context, item: ReceivedItem, from: String) =
        add(ctx, Entry(System.currentTimeMillis(), "f", item.name, item.uri, item.mime, "", from))

    fun addText(ctx: Context, text: String, from: String) =
        add(ctx, Entry(System.currentTimeMillis(), "t", "", null, "text/plain", text, from))

    private fun add(ctx: Context, e: Entry) {
        load(ctx)
        entries.add(0, e)
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
        save(ctx)
        ShareState.notifyChanged()
    }

    fun remove(ctx: Context, e: Entry) {
        load(ctx)
        entries.remove(e)
        save(ctx)
        ShareState.notifyChanged()
    }

    fun clear(ctx: Context) {
        load(ctx)
        entries.clear()
        save(ctx)
        ShareState.notifyChanged()
    }

    /** Drops files that were deleted elsewhere (e.g. in Gallery). Slow-ish: call off the main thread. */
    fun prune(ctx: Context) {
        load(ctx)
        val gone = entries.filter { e ->
            val u = e.uri ?: return@filter false
            try {
                ctx.contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.count == 0 } ?: true
            } catch (_: Exception) {
                true
            }
        }
        if (gone.isEmpty()) return
        entries.removeAll(gone.toSet())
        save(ctx)
        ShareState.notifyChanged()
    }

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                entries.add(Entry(
                    o.optLong("time"), o.optString("kind", "f"), o.optString("name"),
                    o.optString("uri").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                    o.optString("mime"), o.optString("text"), o.optString("from"),
                ))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized private fun save(ctx: Context) {
        val a = JSONArray()
        for (e in entries) a.put(JSONObject().apply {
            put("time", e.time); put("kind", e.kind); put("name", e.name)
            put("uri", e.uri?.toString() ?: ""); put("mime", e.mime); put("text", e.text); put("from", e.from)
        })
        try {
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }
}
