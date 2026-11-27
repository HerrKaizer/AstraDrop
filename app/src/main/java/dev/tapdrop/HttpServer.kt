package dev.tapdrop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal HTTP/1.1 server with keep-alive. Every URL lives under /<token>/ so only
 * someone who received the link (by NFC tap or QR) can reach the files.
 *
 *   GET  /<token>/               page listing the shared files + upload form (in the chosen language)
 *   GET  /<token>/f/<id>/<name>  the file (Range supported; ?dl=1 forces download)
 *   GET  /<token>/t/<id>         WebP thumbnail (pre-made by ThumbStore)
 *   GET  /<token>/p/<id>         WebP screen-sized preview of a big photo (Previews)
 *   POST /<token>/u?n=&m=        raw upload body, saved to the phone's gallery/downloads
 */
class HttpServer(
    private val ctx: Context,
    /** When set, this server speaks HTTPS with it (see [TlsCert]). */
    private val tls: javax.net.ssl.SSLContext? = null,
    /** A new device opened the page and waits to be allowed (Settings → Ask before a device joins). */
    private val onAsk: (Devices.Device) -> Unit = {},
    /** The iPhone sent text or a link. */
    private val onText: (String, Devices.Device) -> Unit = { _, _ -> },
    private val onUpload: (ReceivedItem, Devices.Device) -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    /** Whether the current response may leave the connection open for the next request. */
    private val keepAlive = ThreadLocal<Boolean>()
    private val acceptGzip = ThreadLocal<Boolean>()
    private val clientIp = ThreadLocal<String?>()

    fun start(preferredPort: Int): Int {
        val s = bind(preferredPort) ?: bind(0) ?: throw IOException("Could not open a port")
        server = s
        Thread({ acceptLoop(s) }, "tapdrop-accept").start()
        return s.localPort
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    private fun bind(port: Int): ServerSocket? = try {
        (tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
    } catch (_: IOException) {
        null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            try { pool.execute { handle(c) } } catch (_: Exception) { c.close() }
        }
    }

    // ---------------------------------------------------------------- connection handling

    private fun handle(sock: Socket) {
        sock.use {
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 20_000
                clientIp.set(sock.inetAddress?.hostAddress)
                val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
                val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                var served = 0
                while (served < MAX_REQUESTS_PER_CONNECTION) {
                    val requestLine = try { readLine(input) } catch (_: SocketTimeoutException) { null } ?: break
                    if (requestLine.isEmpty()) continue
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) return
                    served++
                    val clientClose = headers["connection"]?.contains("close", ignoreCase = true) == true ||
                        parts.getOrNull(2) == "HTTP/1.0"
                    keepAlive.set(!clientClose && served < MAX_REQUESTS_PER_CONNECTION)
                    acceptGzip.set(headers["accept-encoding"]?.contains("gzip", ignoreCase = true) == true)

                    val method = parts[0].uppercase()
                    val target = parts[1]
                    route(method, target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers, input, out)
                    out.flush()
                    if (keepAlive.get() != true) break
                }
            } catch (_: Exception) {
                // client went away mid-transfer; nothing to do
            }
        }
    }

    private fun route(
        method: String, path: String, query: Map<String, String>, headers: Map<String, String>,
        input: InputStream, out: OutputStream,
    ) {
        val segs = path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
        reqLang.set(I18n.webLang(headers["accept-language"]))
        val tokenOk = segs.isNotEmpty() && LinkCode.matches(segs[0], ShareState.token)
        val dev = if (tokenOk) Devices.bySecret(cookie(headers["cookie"], COOKIE)) else null
        val isUpload = method == "POST" && segs.size == 2 && tokenOk && dev?.approved == true && (segs[1] == "u" || segs[1] == "x")
        val hasBody = (headers["content-length"]?.toLongOrNull() ?: 0L) > 0L || headers.containsKey("transfer-encoding")
        if (hasBody && !isUpload) keepAlive.set(false) // body won't be read, so this connection can't be reused
        val isHead = method == "HEAD"
        // the app icon for the page header: not secret, so no link code needed (the expired page shows it too)
        if (segs.size == 1 && segs[0] == "icon.png" && method == "GET") { serveIcon(out); return }
        if (!tokenOk) {
            sendText(out, 404, "text/html; charset=utf-8", expiredPage())
            return
        }
        val page = "/${ShareState.token}/"
        // a tap or QR code: swap its one-time ticket for this browser's cookie, then drop it from the address
        val ticket = query["k"]
        if (segs.size == 1 && ticket != null && (method == "GET" || isHead)) {
            val joined = Devices.join(ticket, headers["user-agent"], dev, Ask.on(ctx))
            if (joined == null && dev == null) {
                sendText(out, 403, "text/html; charset=utf-8", expiredPage())
                return
            }
            val h = mutableListOf("Location" to page, "Content-Length" to "0", "Cache-Control" to "no-store")
            if (joined != null && joined !== dev) {
                h += "Set-Cookie" to "$COOKIE=${joined.secret}; Path=$page; Max-Age=86400; HttpOnly; SameSite=Lax" + if (tls != null) "; Secure" else ""
                if (!joined.approved) onAsk(joined)
            }
            head(out, 302, h)
            return
        }
        if (segs.size == 1 && !path.endsWith("/")) {
            head(out, 302, listOf("Location" to page, "Content-Length" to "0"))
            return
        }
        if (dev == null) {
            if (segs.size == 1) sendText(out, 403, "text/html; charset=utf-8", expiredPage(), isHead)
            else sendText(out, 403, "text/plain; charset=utf-8", "gone")
            return
        }
        if (!dev.approved) {
            when {
                segs.size == 2 && segs[1] == "ids" -> sendText(out, 200, "text/plain", "wait")
                segs.size == 1 -> sendText(out, 200, "text/html; charset=utf-8", waitPage(dev), isHead)
                else -> sendText(out, 403, "text/plain; charset=utf-8", "wait")
            }
            return
        }
        Visits.seen(ctx, dev, clientIp.get())
        when {
            segs.size == 1 && (method == "GET" || isHead) -> sendText(out, 200, "text/html; charset=utf-8", indexPage(headers["host"], dev), isHead)
            segs.size >= 3 && segs[1] == "f" && (method == "GET" || isHead) -> serveFile(segs[2], query, headers, out, isHead, dev)
            segs.size == 3 && segs[1] == "t" && method == "GET" -> serveThumb(segs[2], out)
            segs.size == 3 && segs[1] == "p" && method == "GET" -> servePreview(segs[2], out)
            segs.size == 2 && segs[1] == "u" && method == "POST" -> receiveUpload(query, headers, input, out, dev)
            segs.size == 2 && segs[1] == "x" && method == "POST" -> receiveText(headers, input, out, dev)
            segs.size == 2 && segs[1] == "z" && (method == "GET" || isHead) -> serveZip(query, headers, out, isHead, dev)
            segs.size == 3 && segs[1] == "c" && method == "POST" -> compress(segs[2], out)
            segs.size == 3 && segs[1] == "cp" && method == "GET" -> compressProgress(segs[2], out)
            segs.size == 3 && segs[1] == "r" && method == "POST" -> revert(segs[2], out)
            // which files are shared, so an open page can notice files added or removed on the phone
            segs.size == 2 && segs[1] == "ids" && method == "GET" -> sendText(out, 200, "text/plain", listSignature())
            else -> sendText(out, 404, "text/plain; charset=utf-8", w("Not found"))
        }
    }

    // ---------------------------------------------------------------- files

    private fun serveFile(id: String, query: Map<String, String>, headers: Map<String, String>, out: OutputStream, isHead: Boolean, dev: Devices.Device) {
        RawConverter.await(id) // a DNG still being turned into JPEG
        val item = ShareState.items.find { it.id == id }
        if (item == null) {
            sendText(out, 404, "text/plain; charset=utf-8", w("This file isn't available anymore."))
            return
        }
        val total = item.size
        var start = 0L
        var end = total - 1
        var partial = false
        val range = headers["range"]
        if (range != null && total > 0 && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            try {
                if (a.isEmpty()) {
                    start = maxOf(0L, total - b.toLong())
                } else {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                }
            } catch (_: NumberFormatException) {
                start = 0; end = total - 1
            }
            if (start > end || start >= total) {
                head(out, 416, listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                return
            }
            partial = true
        }
        val length = if (total >= 0) end - start + 1 else -1L
        if (length < 0) keepAlive.set(false) // unknown size: the end of the body is marked by closing
        val disposition = if (query["dl"] == "1") "attachment" else "inline"
        val h = mutableListOf(
            "Content-Type" to item.mime,
            "Accept-Ranges" to if (total >= 0) "bytes" else "none",
            "Content-Disposition" to "$disposition; filename*=UTF-8''${encode(item.name)}",
            "Cache-Control" to "no-store",
        )
        if (length >= 0) h += "Content-Length" to length.toString()
        if (partial) h += "Content-Range" to "bytes $start-$end/$total"
        head(out, if (partial) 206 else 200, h)
        if (isHead) return
        // whole-file reads are what the iPhone saves; range reads (video scrubbing) aren't shown
        val tr = if (!partial || start == 0L) Visits.begin(false, item.name, length, dev) else null
        val sent = try { openAt(item.uri, start).use { copy(it, out, length, tr) } } finally { tr?.let { Visits.end(it) } }
        if (length >= 0 && sent != length) keepAlive.set(false)
        // the whole file reached the iPhone (it can save it from here): count it as had
        else if (start == 0L && (total < 0 || end == total - 1)) gotIt(dev, listOf(item.id))
    }

    private fun openAt(uri: Uri, start: Long): InputStream {
        if (start > 0) {
            var fis: ParcelFileDescriptor.AutoCloseInputStream? = null
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    fis.channel.position(start)
                    return fis
                }
            } catch (_: Exception) {
                try { fis?.close() } catch (_: IOException) {}
            }
        }
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        var skip = start
        while (skip > 0) {
            val n = ins.skip(skip)
            if (n <= 0) break
            skip -= n
        }
        return ins
    }

    /**
     * All shared files (or just ?ids=1,4,7) as one ZIP, streamed without compression
     * (photos and videos are already compressed). On the iPhone it lands in Files as a
     * single download; tapping it unpacks a folder whose contents can all be saved to
     * Photos in one go.
     */
    private fun serveZip(query: Map<String, String>, headers: Map<String, String>, out: OutputStream, isHead: Boolean, dev: Devices.Device) {
        val ids = query["ids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ShareState.items.filter { ids == null || it.id in ids }.forEach { RawConverter.await(it.id) }
        val chosen = ShareState.items.filter { ids == null || it.id in ids }
        if (chosen.isEmpty()) {
            sendText(out, 404, "text/plain; charset=utf-8", w("No files to download."))
            return
        }
        val fileName = if (ids == null) "AstraDrop.zip" else "AstraDrop-liked.zip"
        val used = HashSet<String>()
        val entryNames = chosen.map { item ->
            var entry = item.name
            var n = 1
            while (!used.add(entry.lowercase())) {
                val dot = item.name.lastIndexOf('.')
                entry = if (dot > 0) "${item.name.substring(0, dot)} ($n)${item.name.substring(dot)}" else "${item.name} ($n)"
                n++
            }
            entry
        }
        if (!StoredZip.fits(entryNames, chosen.map { it.size })) {
            streamZip(chosen, entryNames, fileName, out, isHead, dev)
            return
        }
        val zip = StoredZip(chosen.mapIndexed { i, item ->
            StoredZip.Entry(item.uri.toString(), entryNames[i], item.size) { ctx.contentResolver.openInputStream(item.uri) }
        }, ShareState.startedAt)
        val total = zip.total
        // same files, same bytes: a resumed download may carry on from where it stopped
        val etag = "\"" + Integer.toHexString((ShareState.token + chosen.joinToString { it.id + ":" + it.uri + ":" + it.size }).hashCode()) + "\""
        var start = 0L
        var end = total - 1
        val range = headers["range"]
        val ifRange = headers["if-range"]
        val partial = range != null && range.startsWith("bytes=") && (ifRange == null || ifRange == etag)
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',').trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            try {
                if (a.isEmpty()) start = maxOf(0L, total - b.toLong())
                else { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1) }
            } catch (_: NumberFormatException) { start = 0; end = total - 1 }
            if (start > end || start >= total) {
                head(out, 416, listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                return
            }
        }
        val length = end - start + 1
        val h = mutableListOf(
            "Content-Type" to "application/zip",
            "Content-Disposition" to "attachment; filename=\"$fileName\"",
            "Cache-Control" to "no-store",
            "Accept-Ranges" to "bytes",
            "ETag" to etag,
            "Content-Length" to length.toString(),
        )
        if (partial) h += "Content-Range" to "bytes $start-$end/$total"
        head(out, if (partial) 206 else 200, h)
        if (isHead) return
        val tr = Visits.begin(false, fileName, total, dev)
        tr.done = start
        val sent = try {
            zip.write(out, start, end) { n -> Visits.progress(tr, start + n) }
        } catch (e: Exception) {
            keepAlive.set(false); -1L
        } finally {
            Visits.end(tr)
        }
        if (sent != length) keepAlive.set(false)
        else if (end == total - 1) gotIt(dev, chosen.map { it.id })
    }

    /** Archives too big for [StoredZip]: streamed as ZIP64, so no size up front and no resuming. */
    private fun streamZip(chosen: List<SharedItem>, names: List<String>, fileName: String, out: OutputStream, isHead: Boolean, dev: Devices.Device) {
        keepAlive.set(false) // size isn't known up front, so the end is marked by closing
        head(out, 200, listOf(
            "Content-Type" to "application/zip",
            "Content-Disposition" to "attachment; filename=\"$fileName\"",
            "Cache-Control" to "no-store",
        ))
        if (isHead) return
        val tr = Visits.begin(false, fileName, chosen.sumOf { maxOf(0L, it.size) }, dev)
        var done = 0L
        try {
            val zos = ZipOutputStream(out)
            zos.setLevel(Deflater.NO_COMPRESSION)
            chosen.forEachIndexed { i, item ->
                zos.putNextEntry(ZipEntry(names[i]))
                ctx.contentResolver.openInputStream(item.uri)?.use { ins ->
                    val base = done
                    done += copy(ins, zos, -1, null) { n -> Visits.progress(tr, base + n) }
                }
                zos.closeEntry()
            }
            zos.finish()
            gotIt(dev, chosen.map { it.id })
        } finally {
            Visits.end(tr)
        }
    }

    /** [dev] has these shared files in full now. */
    private fun gotIt(dev: Devices.Device, ids: List<String>) {
        if (dev.got.addAll(ids)) ShareState.notifyChanged()
    }

    /** POST /<token>/c/<id>: compress one photo (see [Compressor]) and report its new size. */
    private fun compress(id: String, out: OutputStream) {
        val done = Compressor.compress(ctx, id)
        val body = if (done == null) "{\"ok\":false}"
        else "{\"ok\":true,\"s\":\"${fmtSize(done.size)}\",\"n\":\"${esc(done.name).replace("\\", "\\\\")}\"}"
        sendText(out, 200, "application/json", body)
    }

    /** GET /<token>/p/<id>: screen-sized JPEG of a big photo for the viewer (see [Previews]). */
    /** POST /<token>/r/<id>: undo a compression and report the original's size. */
    private fun revert(id: String, out: OutputStream) {
        val prev = Compressor.revert(id)
        val body = if (prev == null) "{\"ok\":false}"
        else "{\"ok\":true,\"s\":\"${fmtSize(prev.size)}\",\"n\":\"${esc(prev.name).replace("\\", "\\\\")}\"}"
        sendText(out, 200, "application/json", body)
    }

    /**
     * GET /<token>/cp/<id>: how far a running compression is. `p` is 0..1 (-1 when
     * nothing is running), `eta` the seconds left, measured from the speed so far.
     */
    private fun compressProgress(id: String, out: OutputStream) {
        val pr = Compressor.progress(id)
        val body = when {
            pr == null -> "{\"p\":-1}"
            pr.startMs == 0L -> "{\"p\":0,\"wait\":true}"
            else -> {
                val p = pr.done
                val secs = (android.os.SystemClock.elapsedRealtime() - pr.startMs) / 1000.0
                val eta = if (p >= 0.02f) ", \"eta\":${Math.round(secs * (1 - p) / p)}" else ""
                String.format(Locale.US, "{\"p\":%.4f%s}", p, eta)
            }
        }
        sendText(out, 200, "application/json", body)
    }

    private fun servePreview(id: String, out: OutputStream) {
        RawConverter.await(id)
        val item = ShareState.items.find { it.id == id }
        val bytes = item?.let { Previews.get(ctx, it.uri) }
        if (bytes == null) {
            sendText(out, 404, "text/plain", "")
            return
        }
        head(out, 200, listOf("Content-Type" to Previews.MIME, "Content-Length" to bytes.size.toString(), "Cache-Control" to "private, max-age=3600"))
        out.write(bytes)
    }

    private fun serveThumb(id: String, out: OutputStream) {
        val item = ShareState.items.find { it.id == id }
        // a DNG's own thumbnail can come out sideways, so converted RAWs use their JPEG
        val thumb = item?.let {
            val fromOrig = it.orig != null && it.origMime?.contains("dng", ignoreCase = true) != true
            if (fromOrig) ThumbStore.get(ctx, it.orig!!, it.origMime ?: it.mime)
            else ThumbStore.get(ctx, it.uri, it.mime)
        }
        if (thumb == null || thumb.bytes.isEmpty()) {
            sendText(out, 404, "text/plain", "")
            return
        }
        val cache = if (RawConverter.isConverting(id)) "no-store" else "private, max-age=3600"
        head(out, 200, listOf("Content-Type" to thumb.mime, "Content-Length" to thumb.bytes.size.toString(), "Cache-Control" to cache))
        out.write(thumb.bytes)
    }

    // ---------------------------------------------------------------- uploads (iPhone -> this phone)

    private fun receiveUpload(query: Map<String, String>, headers: Map<String, String>, input: InputStream, out: OutputStream, dev: Devices.Device) {
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null) {
            keepAlive.set(false)
            sendText(out, 411, "text/plain", "Content-Length required")
            return
        }
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
        }
        val rawName = query["n"]?.takeIf { it.isNotBlank() } ?: "upload-${System.currentTimeMillis()}"
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").take(150)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = query["m"]?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"

        val (collection, relPath) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/AstraDrop"
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/AstraDrop"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/AstraDrop"
        }
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = cr.insert(collection, values)
        if (dest == null) {
            keepAlive.set(false)
            sendText(out, 500, "text/plain", "Could not create file on phone")
            return
        }
        val copied = try {
            val tr = Visits.begin(true, name, length, dev)
            try { cr.openOutputStream(dest)?.use { copy(input, it, length, tr) } ?: -1L } finally { Visits.end(tr) }
        } catch (_: Exception) {
            -1L
        }
        if (copied != length) {
            cr.delete(dest, null, null)
            keepAlive.set(false)
            sendText(out, 500, "text/plain", "Upload interrupted")
            return
        }
        cr.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        onUpload(ReceivedItem(name, dest, mime), dev)
        sendText(out, 200, "application/json", "{\"ok\":true}")
    }

    /** POST /<token>/x: text or a link typed on the iPhone (UTF-8 body). */
    private fun receiveText(headers: Map<String, String>, input: InputStream, out: OutputStream, dev: Devices.Device) {
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length > ShareState.MAX_TEXT * 4L) {
            keepAlive.set(false)
            sendText(out, 413, "text/plain", "Too long")
            return
        }
        val buf = ByteArray(length.toInt())
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        if (got != buf.size) {
            keepAlive.set(false)
            sendText(out, 400, "text/plain", "Interrupted")
            return
        }
        val text = String(buf, Charsets.UTF_8).trim().take(ShareState.MAX_TEXT)
        if (text.isNotEmpty()) onText(text, dev)
        sendText(out, 200, "application/json", "{\"ok\":true}")
    }

    // ---------------------------------------------------------------- pages

    /** The launcher art scaled down once (96 px, PNG) and kept. */
    private fun serveIcon(out: OutputStream) {
        val png = iconPng ?: try {
            val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_launcher_art)
            // the art has a wide black margin: keep the middle (cube and rings) so it reads at this size
            val m = (src.width * 0.17f).toInt()
            val mid = Bitmap.createBitmap(src, m, m, src.width - 2 * m, src.height - 2 * m)
            val small = Bitmap.createScaledBitmap(mid, 120, 120, true)
            ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { iconPng = it }
        } catch (_: Exception) { null }
        if (png == null) { sendText(out, 404, "text/plain", ""); return }
        head(out, 200, listOf("Content-Type" to "image/png", "Content-Length" to png.size.toString(), "Cache-Control" to "public, max-age=86400"))
        out.write(png)
    }

    /** The app's mark (gold cube in open rings, like its icon), the name, and "Connected" with a live dot. */
    private fun top(connected: Boolean) = "<header class=\"top\">$MARK<h1>AstraDrop</h1>" +
        (if (connected) "<span class=\"chip\"><i></i>${w("Connected")}</span>" else "") + "</header>"

    /** Texts sent to the iPhone, each with Copy (and links tappable). */
    private fun textsHtml(): String {
        val texts = ShareState.texts.toList()
        if (texts.isEmpty()) return ""
        val sb = StringBuilder("<div class=\"row\"><h2>${w("Text")}</h2></div><div class=\"txts\">")
        for (t in texts) {
            val shown = esc(t.text).replace(Regex("https?://[^\\s<]+")) { m -> "<a href=\"${m.value}\" target=\"_blank\" rel=\"noopener\">${m.value}</a>" }
            sb.append("<div class=\"tx glass\"><div class=\"tv\">$shown</div><textarea hidden>${esc(t.text)}</textarea>")
                .append("<button class=\"btn soft\" onclick=\"cp(this)\">${w("Copy")}</button></div>")
        }
        return sb.append("</div>").toString()
    }

    /** Shown to a new device until it's allowed on the Android phone. */
    private fun waitPage(dev: Devices.Device): String = """
<!doctype html><html lang="${pageLang()}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>AstraDrop</title><style>$CSS</style></head>
<body><div class="wrap">${top(false)}<div class="up glass"><h2 class="msg">${w("Waiting for the Android phone")}</h2><p class="sub">${w("Tap Allow on the Android phone to open the files.")}</p></div></div>
<script>setInterval(function(){fetch('ids',{cache:'no-store'}).then(function(r){if(r.status==403){location.reload();return}return r.text().then(function(t){if(t!='wait')location.reload()})}).catch(function(){})},1500)</script></body></html>

""".trimIndent()

    private fun indexPage(host: String?, dev: Devices.Device): String {
        val lang = pageLang()
        val items = ShareState.items.toList()
        // the HTTPS twin of this page (see TlsCert), for the IP the iPhone reached us on; a
        // separate site, so it gets its own one-time ticket for this device
        val ip = host?.substringBefore(':')?.takeIf { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }
        val tlsBase = if (ip != null && ShareState.tlsPort > 0 && tls == null) "https://${TlsCert.hostFor(ip)}:${ShareState.tlsPort}/${ShareState.token}/" else ""
        val tlsUrl = if (tlsBase.isNotEmpty()) tlsBase + "?k=" + Devices.handoff(dev) else ""
        val cards = StringBuilder()
        var mediaIdx = 0
        for (it in items) {
            // a DNG still being converted is already listed under its JPEG name
            val converting = RawConverter.isConverting(it.id)
            val n = esc(if (converting) it.name.replace(Regex("\\.dng$", RegexOption.IGNORE_CASE), "") + ".jpg" else it.name)
            val href = "f/${it.id}/${encode(it.name)}"
            val isVideo = it.mime.startsWith("video/")
            val isMedia = isVideo || it.mime.startsWith("image/")
            val kind = when { isVideo -> "v"; isMedia -> "p"; else -> "f" }
            // too big for a phone browser to show: the viewer gets a screen-sized copy
            val px = if (kind == "p" && !converting) Previews.pixels(ctx, it.uri).coerceAtLeast(0) else 0L
            val big = kind == "p" && (converting || px > Previews.MAX_FULL_PIXELS)
            val tv = if (converting) "?v=0" else "?v=1"
            val loadAttr = if (mediaIdx < 6) "fetchpriority=\"high\"" else "loading=\"lazy\""
            val thumb = if (isMedia) "<img src=\"t/${it.id}$tv\" alt=\"\" $loadAttr decoding=\"async\" onerror=\"this.remove()\">" else ""
            // photos and videos open the swipe viewer; other files download to Files
            val open = if (isMedia) "onclick=\"return of(${mediaIdx})\"" else ""
            // tapping a photo/video thumbnail opens the viewer, so only other files get a button
            val act = if (isMedia) ""
            else "<div class=\"act\"><a class=\"btn soft\" href=\"$href?dl=1\" download=\"$n\">${w("Save")}</a></div>"
            cards.append(
                """
                <div class="card glass" data-id="${it.id}" data-kind="$kind" data-src="$href" data-n="$n" data-s="${if (converting) "" else fmtSize(it.size)}" data-c="${if (it.compressed) 1 else 0}" data-cc="${if (Compressor.canCompress(it) || it.compressed) 1 else 0}" data-big="${if (big) 1 else 0}" data-px="$px">
                  <a class="thumb" href="$href" $open>$thumb<span class="ph">${if (isVideo) "▶" else ext(it.name)}</span>${if (isVideo) "<span class=\"badge\">▶</span>" else ""}<span class="lk">$HEART</span></a>
                  <div class="meta"><div class="name">$n</div><div class="sub">${if (converting) "RAW → JPEG" else fmtSize(it.size)}</div></div>
                  $act
                </div>
                """.trimIndent()
            )
            if (isMedia) mediaIdx++
        }
        val count = items.size
        val hint = if (mediaIdx > 0) "<p class=\"hint\">${w("Tap a photo to open it and mark it ♥")}</p>" else ""
        val list = if (items.isEmpty() && ShareState.texts.isEmpty())
            "<p class=\"empty\">${w("Nothing is being sent from the Android phone right now. You can still send files to it below.")}</p>"
        else if (items.isEmpty()) ""
        else
            """
            <div class="row"><h2 id="cnt">${esc(I18n.pluralIn(lang, count, "%d file", "%d files"))}</h2><span class="rowb"><button id="sall" class="lnk" onclick="selAll()" hidden>${w("All")}</button><button id="selb" class="lnk" onclick="selMode()">${w("Select")}</button></span></div>
            <div class="bulk"><button class="btn insec" onclick="zipAll()">${w("All to Files")}</button><button id="lb" class="btn soft insec" onclick="zipLiked()" disabled>${w("All ♥ to Files")}</button><button class="btn sec" onclick="toPhotos(C.filter(isMedia))">${w("All to Photos")}</button><button id="lp" class="btn soft sec" onclick="toPhotos(C.filter(function(c){return L[c.dataset.id]&&isMedia(c)}))" disabled>${w("All ♥ to Photos")}</button></div>
            $hint
            <div class="grid">$cards</div>
            <details class="tip"><summary>${w("How to save")}</summary><p>${w("<b>Photos and videos:</b> tap one and swipe up or down. ♥ marks it, so you can save just the marked ones.")}<br>
            ${w("<b>Big photos:</b> in the viewer, Compress makes a smaller copy (same size in pixels) before you save it; Original brings the full file back.")}<br>
            <span class="sec">${w("<b>To Photos:</b> “All to Photos”, or Select → “To Photos”, then Save → <i>Save Images</i>. One photo: touch and hold it → <i>Save to Photos</i>.")}</span>
            <span class="insec">${w("<b>To Photos:</b> touch and hold a photo → <i>Save to Photos</i>.")}<br>
            ${w("<b>Everything to Photos at once:</b> “All to Files” downloads one archive. Open it in Files, then in the folder Select → Select All → Share → <i>Save</i>.")}</span></p></details>
            """.trimIndent()
        val feed = if (items.isEmpty()) "" else feedHtml()
        return """
<!doctype html><html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<title>AstraDrop</title>
<style>$CSS</style></head><body>
<script>try{if(window.isSecureContext&&navigator.canShare&&navigator.canShare({files:[new File(['x'],'a.jpg',{type:'image/jpeg'})]}))document.body.classList.add('secure')}catch(e){}</script>
<div class="wrap">
${top(true)}
${textsHtml()}
$list
<div class="up glass"><h2>${w("Send to Android")}</h2>
<label class="drop" for="f"><span class="plus">$PLUS</span><span><b>${w("Choose files")}</b><span class="sub">${w("Choose photos, videos or files: they're saved on the other phone.")}</span></span></label>
<input id="f" type="file" multiple onchange="up(this.files)">
<div class="bar" id="bar"><div id="fill"></div></div><div id="ustat"></div>
<div class="txrow"><textarea id="tx" rows="1" placeholder="${w("Text or a link")}" oninput="this.style.height='auto';this.style.height=Math.min(this.scrollHeight,160)+'px'"></textarea>
<button class="send" id="txb" onclick="sendTx()" aria-label="${w("Send text")}">$ARROW</button></div><span id="txs"></span></div>
</div>
<script>${jsDict(lang)}</script>
$feed
<script>
var IDS='${listSignature()}',stale=false,TLS='$tlsUrl',TLSB='$tlsBase';
// Over HTTPS, Safari can save many photos to Photos at once. The HTTPS address needs
// internet to look up its name, so switch only when it actually answers.
if(TLS&&location.protocol=='http:'){var ac=new AbortController();setTimeout(function(){ac.abort()},3000);
fetch(TLSB+'ids',{mode:'no-cors',cache:'no-store',signal:ac.signal}).then(function(){location.replace(TLS)}).catch(function(){})}
function typing(){var t=document.getElementById('tx');return !!t&&(!!t.value||document.activeElement==t)}
function canReload(){var v=document.getElementById('fv');return !window.BUSY&&(!v||v.hidden)&&!typing()}
function fresh(){if(!canReload()){stale=true;return}location.reload()}
// a device removed on the Android phone (or a new link) gets 403: reload shows "tap again"
setInterval(function(){if(document.hidden)return;if(stale){if(canReload())location.reload();return}
fetch('ids',{cache:'no-store'}).then(function(r){if(r.status==403){location.reload();return null}return r.ok?r.text():null})
.then(function(t){if(t!=null&&t!=IDS)fresh()}).catch(function(){})},2500);
function sendTx(){var t=document.getElementById('tx'),s=document.getElementById('txs'),b=document.getElementById('txb'),v=t.value.trim();if(!v)return;b.disabled=true;
fetch('x',{method:'POST',body:v,headers:{'Content-Type':'text/plain;charset=utf-8'}}).then(function(r){if(!r.ok)throw 0;t.value='';t.blur();s.textContent=tf('Sent ✓')})
.catch(function(){s.textContent=tf('Couldn\'t send. Try again.')}).then(function(){b.disabled=false;setTimeout(function(){s.textContent=''},2500)})}
function cp(b){var t=b.parentNode.querySelector('textarea').value;function ok(){b.textContent=tf('Copied ✓');setTimeout(function(){b.textContent=tf('Copy')},1800)}
function fb(){var a=document.createElement('textarea');a.value=t;a.setAttribute('readonly','');a.style.position='fixed';a.style.opacity='0';document.body.appendChild(a);
a.select();a.setSelectionRange(0,t.length);try{if(document.execCommand('copy'))ok()}catch(e){}a.remove()}
if(navigator.clipboard&&window.isSecureContext)navigator.clipboard.writeText(t).then(ok,fb);else fb()}
async function up(files){var bar=document.getElementById('bar'),fill=document.getElementById('fill'),st=document.getElementById('ustat');
bar.style.display='block';var ok=0;for(var i=0;i<files.length;i++){var f=files[i];st.textContent=tf('Sending %1 of %2: %3',i+1,files.length,f.name);
try{await new Promise(function(res,rej){var x=new XMLHttpRequest();x.open('POST','u?n='+encodeURIComponent(f.name)+'&m='+encodeURIComponent(f.type||''));
x.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=(100*e.loaded/e.total)+'%'};x.onload=function(){x.status==200?res():rej()};x.onerror=rej;x.send(f)});ok++}catch(e){}}
fill.style.width='100%';st.textContent=ok==files.length?(pn(ok,'Sent %d file|Sent %d files')+' ✓'):tf('Sent %1 of %2. Some failed: try again.',ok,files.length);document.getElementById('f').value=''}
</script></body></html>
""".trimIndent()
    }

    /**
     * What an open page compares to notice it's out of date: the shared ids, with
     * "~" on DNGs still being converted (so the page refreshes when one finishes).
     * Compressing doesn't change it; the page updates itself for that.
     */
    private fun listSignature() =
        ShareState.items.joinToString(",") { it.id + if (RawConverter.isConverting(it.id)) "~" else "" } +
            ShareState.texts.joinToString("") { "," + it.id }

    private fun expiredPage(): String = """
<!doctype html><html lang="${pageLang()}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>AstraDrop</title><style>$CSS</style></head>
<body><div class="wrap">${top(false)}<div class="up glass"><h2 class="msg">${w("This link has expired")}</h2><p class="sub">${w("Tap the phone again (or scan its code) to get the new link.")}</p></div></div></body></html>
""".trimIndent()

    // ---------------------------------------------------------------- page language

    /** The language of the page being answered (set per request from the setting and the browser). */
    private val reqLang = ThreadLocal<String>()
    private fun pageLang(): String = reqLang.get() ?: I18n.webLang(null)
    private fun w(en: String) = I18n.tr(pageLang(), en)

    /**
     * Every text the page's scripts show, in the page's language, plus tf() (fills %1, %2...) and
     * pn() (plural forms "one|other", or "one|few|many" for Russian and Polish).
     */
    private fun jsDict(lang: String): String {
        val o = org.json.JSONObject()
        for (k in JS_KEYS) o.put(k, I18n.tr(lang, k))
        val rule = when (lang) {
            "ru" -> "var m=n%10,h=n%100;i=m==1&&h!=11?0:(m>=2&&m<=4&&(h<12||h>14)?1:2)"
            "pl" -> "var m=n%10,h=n%100;i=n==1?0:(m>=2&&m<=4&&(h<12||h>14)?1:2)"
            "zh" -> "i=0"
            "fr", "pt" -> "i=n<2?0:1"
            else -> "i=n==1?0:1"
        }
        return "var T=$o;function tf(k){var s=T[k]||k;for(var i=1;i<arguments.length;i++)s=s.split('%'+i).join(arguments[i]);return s}" +
            "function pn(n,k){var f=(T[k]||k).split('|'),i=0;$rule;return f[Math.min(i,f.length-1)].split('%d').join(n)}"
    }

    /**
     * Full-screen, TikTok-style viewer: swipe up/down between photos and videos
     * (CSS scroll-snap, so it moves with the finger natively), with a heart
     * above the Compress bar on the left. Hearts are kept in localStorage
     * per link and shown on the grid thumbnails.
     */
    private fun feedHtml() = """
<div id="fv" class="feed" hidden>
<div id="fs" class="fs"></div>
<button class="fx" onclick="cf()" aria-label="${w("Close")}">✕</button>
<div class="fc" id="fc"></div>
<div class="side">
<button id="bl" class="sb" onclick="tl()" aria-label="${w("Like")}">$HEART</button>
</div>
<div id="fp" class="fp" hidden></div>
<div class="fb"><span id="fsz"></span><button id="bc" class="cb" onclick="cmp()">${w("Compress")}</button><button id="br" class="cb rv" onclick="rev()" hidden>${w("Original")}</button></div>
<div id="ft" class="toast" hidden></div>
</div>
<div id="selbar" class="pill" hidden><button id="sl" class="cb lkb" onclick="selLike()">♥ ${w("Like")}</button><button id="sc" class="cb" onclick="selCompress()">${w("Compress")}</button><button id="sp" class="cb sec" onclick="selPhotos()">${w("To Photos")}</button></div>
<div id="svp" class="pill" hidden><span id="svt" class="pt"></span><button id="svb" class="cb" onclick="svGo()" disabled>${w("Save")}</button><button class="cb x" onclick="svStop()" aria-label="${w("Cancel")}">✕</button></div>
<div id="bp" class="pill txt" hidden></div>
<script>
var C=[].slice.call(document.querySelectorAll('.card[data-id]'));
var M=C.filter(function(c){return c.dataset.kind!='f'}).map(function(c){return {id:c.dataset.id,k:c.dataset.kind,src:c.dataset.src,n:c.dataset.n,s:c.dataset.s,c:c.dataset.c=='1',cc:c.dataset.cc=='1',big:c.dataset.big=='1'}});
function vsrc(m){return m.big?'p/'+m.id:m.src}var bigHinted=false;
var TK='td-likes-'+location.pathname.split('/')[1],L={},cur=0,built=false,hinted=false,tt=0,raf=0;
try{L=JSON.parse(localStorage.getItem(TK)||'{}')}catch(e){L={}}
function E(id){return document.getElementById(id)}
function saveL(){try{localStorage.setItem(TK,JSON.stringify(L))}catch(e){}}
function paint(){var n=0;C.forEach(function(c){var on=!!L[c.dataset.id];c.classList.toggle('liked',on);if(on)n++});
var b=E('lb');if(b){b.disabled=n==0;b.textContent=tf('All ♥ to Files')+(n?' ('+n+')':'')}
var lm=C.filter(function(c){return L[c.dataset.id]&&c.dataset.kind!='f'}).length;
b=E('lp');if(b){b.disabled=lm==0;b.textContent=tf('All ♥ to Photos')+(lm?' ('+lm+')':'')}}
function build(){E('fs').innerHTML=M.map(function(m,i){return '<div class="sl" data-i="'+i+'">'+(m.k=='v'
?'<video playsinline controls preload="none" data-src="'+m.src+'"></video>':'<img alt="" data-src="'+vsrc(m)+'">')+'</div>'}).join('');
E('fs').addEventListener('scroll',function(){if(raf)return;raf=requestAnimationFrame(function(){raf=0;
var fs=E('fs'),i=Math.round(fs.scrollTop/Math.max(1,fs.clientHeight));if(i!=cur)setCur(i)})},{passive:true});built=true}
function load(i){var ch=E('fs').children;for(var j=0;j<ch.length;j++){var el=ch[j].firstChild;if(!el)continue;
if(Math.abs(j-i)<=1){if(!el.getAttribute('src')&&el.dataset.src)el.src=el.dataset.src}
else if(el.getAttribute('src')&&!M[j].busy){el.removeAttribute('src');if(el.tagName=='VIDEO')el.load()}}}
function setCur(i){if(i<0||i>=M.length)return;cur=i;load(i);E('fc').textContent=(i+1)+' / '+M.length;var m=M[i];E('fsz').textContent=m.s||'—';
if(m.big&&!bigHinted&&E('ft').hidden){bigHinted=true;toast(tf('Big photo: you see a smaller copy here. Saving it from the list keeps the original.'))}
E('fp').hidden=!m.busy;if(m.busy)E('fp').textContent=m.pt||tf('Preparing');
var bc=E('bc');bc.hidden=!m.cc;bc.disabled=m.c||!!m.busy;bc.textContent=m.busy?(m.rv?tf('Restoring'):tf('Compressing')):(m.c?tf('Compressed ✓'):tf('Compress'));
var br=E('br');br.hidden=!m.c||!!m.busy;
E('bl').classList.toggle('on',!!L[M[i].id]);
[].forEach.call(E('fs').querySelectorAll('video'),function(v){if(+v.parentNode.dataset.i!=i)v.pause()})}
function of(i){if(!M.length)return false;if(!built)build();E('fv').hidden=false;document.documentElement.classList.add('lock');
if(!hinted){hinted=true;toast(tf('Swipe up or down. To save a photo to Photos, touch and hold it.'))}
var fs=E('fs');fs.scrollTop=i*fs.clientHeight;setCur(i);return false}
function cf(){E('fv').hidden=true;document.documentElement.classList.remove('lock');if(typeof stale!='undefined'&&stale&&!window.BUSY){location.reload();return}
[].forEach.call(E('fs').querySelectorAll('video'),function(v){v.pause()});paint()}
function tl(){var id=M[cur].id;if(L[id])delete L[id];else L[id]=1;saveL();var b=E('bl');b.classList.toggle('on',!!L[id]);
b.classList.remove('pop');void b.offsetWidth;b.classList.add('pop');paint()}
function toast(t){var f=E('ft');f.textContent=t;f.hidden=false;clearTimeout(tt);tt=setTimeout(function(){f.hidden=true},3800)}
function dur(s){return s<60?tf('%1 s',s):tf('%1 min %2 s',Math.floor(s/60),s%60)}
function cmp(){var i=cur,m=M[i];if(m.c||m.busy)return;m.busy=true;m.pt='';var sl=E('fs').children[i];sl.classList.add('blur');setCur(i);
var pt=setInterval(function(){fetch('cp/'+m.id).then(function(r){return r.json()}).then(function(j){if(!m.busy)return;
m.pt=j.wait?tf('Waiting'):(j.p<=0?'':Math.floor(j.p*100)+'%'+(j.eta!=null?' · '+tf('about %1 left',dur(j.eta)):''));
if(cur==i){E('fp').hidden=false;E('fp').textContent=m.pt||tf('Preparing')}}).catch(function(){})},700);
var old=m.s;function end(){clearInterval(pt);m.busy=false;m.pt='';sl.classList.remove('blur');if(cur==i)setCur(i)}
fetch('c/'+m.id,{method:'POST'}).then(function(r){return r.json()}).then(function(j){if(!j.ok)throw 0;
m.s=j.s;m.c=true;if(j.n)m.n=j.n;var card=C.filter(function(c){return c.dataset.id==m.id})[0];
if(card){card.dataset.s=j.s;card.dataset.c='1';var sb=card.querySelector('.sub');if(sb)sb.textContent=j.s;var nm=card.querySelector('.name');if(nm&&j.n)nm.innerHTML=j.n}
var el=sl.firstChild;if(m.k!='p'||!el){end();return}var im=new Image();im.onload=im.onerror=function(){el.src=im.src;end()};
im.src=vsrc(m)+'?v='+Date.now();if(old&&old!=j.s)toast(old+' → '+j.s)}).catch(function(){end();toast(tf('Couldn\'t compress the photo.'))})}
function rev(){var i=cur,m=M[i];if(!m.c||m.busy)return;m.busy=true;m.rv=true;m.pt=tf('Bringing back the original');var sl=E('fs').children[i];sl.classList.add('blur');setCur(i);
function end(){m.busy=false;m.rv=false;m.pt='';sl.classList.remove('blur');if(cur==i)setCur(i)}
fetch('r/'+m.id,{method:'POST'}).then(function(r){return r.json()}).then(function(j){if(!j.ok)throw 0;
m.s=j.s;m.c=false;if(j.n)m.n=j.n;var card=C.filter(function(c){return c.dataset.id==m.id})[0];
if(card){card.dataset.s=j.s;card.dataset.c='0';var sb=card.querySelector('.sub');if(sb)sb.textContent=j.s;var nm=card.querySelector('.name');if(nm&&j.n)nm.innerHTML=j.n}
toast(tf('Original: %1',j.s));var el=sl.firstChild;if(m.k!='p'||!el){end();return}var im=new Image();im.onload=im.onerror=function(){el.src=im.src;end()};
im.src=vsrc(m)+'?v='+Date.now()}).catch(function(){end();toast(tf('Couldn\'t bring back the original.'))})}
// ---- select mode: tap thumbnails to pick them, then like or compress them all
var SEL=null,CNT=E('cnt').textContent;
function picked(){return C.filter(function(c){return SEL&&SEL[c.dataset.id]})}
function canSq(c){return c.dataset.cc=='1'&&c.dataset.c!='1'&&!c.classList.contains('busy')}
function selMode(){if(window.BUSY)return;SEL=SEL?null:{};document.body.classList.toggle('selecting',!!SEL);selPaint()}
function selAll(){var all=picked().length==C.length;C.forEach(function(c){if(all)delete SEL[c.dataset.id];else SEL[c.dataset.id]=1});selPaint()}
function selPaint(){var p=picked(),n=p.length;C.forEach(function(c){c.classList.toggle('picked',!!(SEL&&SEL[c.dataset.id]))});
E('selb').textContent=SEL?tf('Cancel'):tf('Select');E('sall').hidden=!SEL;E('sall').textContent=n==C.length?tf('Select none'):tf('All');
E('selbar').hidden=!SEL;document.body.classList.toggle('haspill',!!SEL||!!window.BUSY);
E('cnt').textContent=SEL?(n?tf('Selected: %1',n):tf('Choose files')):CNT;
var allL=n>0&&p.every(function(c){return L[c.dataset.id]});E('sl').disabled=!n;E('sl').textContent=allL?'♥ '+tf('Unlike'):'♥ '+tf('Like');
E('sc').disabled=!p.some(canSq);E('sp').disabled=!p.some(isMedia)}
document.querySelector('.grid').addEventListener('click',function(e){if(!SEL)return;var c=e.target.closest('.card');if(!c)return;
e.preventDefault();e.stopPropagation();var id=c.dataset.id;if(SEL[id])delete SEL[id];else SEL[id]=1;selPaint()},true);
function selLike(){var p=picked();if(!p.length)return;var allL=p.every(function(c){return L[c.dataset.id]});
p.forEach(function(c){if(allL)delete L[c.dataset.id];else L[c.dataset.id]=1});saveL();paint();selMode()}
function selCompress(){var p=picked().filter(canSq);if(!p.length)return;selMode();batch(p)}
function cardDone(id,j){var m=M.filter(function(x){return x.id==id})[0];if(m){m.s=j.s;m.c=true;if(j.n)m.n=j.n}
var c=C.filter(function(x){return x.dataset.id==id})[0];if(!c)return;c.dataset.s=j.s;c.dataset.c='1';
var sb=c.querySelector('.sub');if(sb)sb.textContent=j.s;var nm=c.querySelector('.name');if(nm&&j.n)nm.innerHTML=j.n}
// Compresses the cards one by one. Overall progress weighs each photo by its pixel count and uses the
// phone's live progress for the current one, so the percentage and time left are measured, not guessed.
async function batch(cards){window.BUSY=true;document.body.classList.add('haspill');
var w=cards.map(function(c){return +c.dataset.px||1e7}),total=w.reduce(function(a,b){return a+b},0),done=0,t0=Date.now(),ok=0,bp=E('bp');
cards.forEach(function(c){c.classList.add('busy');var t=c.querySelector('.thumb');if(!t.querySelector('.tb')){var b=document.createElement('span');b.className='tb';b.innerHTML='<i></i>';t.appendChild(b)}});
function show(i,cur){var f=Math.min(1,(done+w[i]*Math.max(0,cur))/total),s=(Date.now()-t0)/1000;
bp.hidden=false;bp.textContent=tf('Compressing %1 of %2',i+1,cards.length)+' · '+Math.floor(f*100)+'%'+(f>=0.02?' · '+tf('about %1 left',dur(Math.round(s*(1-f)/f))):'')}
for(var i=0;i<cards.length;i++){var c=cards[i],id=c.dataset.id,bar=c.querySelector('.tb i');show(i,0);
var poll=setInterval((function(i,id,bar){return function(){fetch('cp/'+id).then(function(r){return r.json()}).then(function(j){
if(j.p>0){bar.style.width=(j.p*100)+'%';show(i,j.p)}}).catch(function(){})}})(i,id,bar),600);
try{var r=await fetch('c/'+id,{method:'POST'}),j=await r.json();if(j.ok){cardDone(id,j);ok++}}catch(e){}
clearInterval(poll);done+=w[i];c.classList.remove('busy');var tb=c.querySelector('.tb');if(tb)tb.remove()}
bp.textContent=ok==cards.length?pn(ok,'Done: %d photo compressed|Done: %d photos compressed'):tf('Compressed %1 of %2. The others couldn\'t be compressed.',ok,cards.length);
window.BUSY=false;setTimeout(function(){bp.hidden=true;document.body.classList.toggle('haspill',!!SEL);if(stale&&E('fv').hidden)location.reload()},2600)}
// ---- straight to Photos (HTTPS only): load the files, then a tap opens the share sheet with
// «Save N Images». Safari keeps every loaded file in memory, so big sets go in batches of ≤400 MB.
var SV=null;
if(window.isSecureContext&&navigator.canShare&&navigator.canShare({files:[new File(['x'],'a.jpg',{type:'image/jpeg'})]}))document.body.classList.add('secure');
function isMedia(c){return c.dataset.kind!='f'}
function selPhotos(){var p=picked().filter(isMedia);if(!p.length)return;selMode();toPhotos(p)}
function toPhotos(cards){if(SV||window.BUSY||!cards.length)return;SV={cards:cards,i:0,files:[],from:0,stop:false};
document.body.classList.add('haspill');E('svp').hidden=false;E('svb').hidden=false;svLoad()}
async function svLoad(){var s=SV,n=s.cards.length,bytes=0;s.files=[];s.from=s.i;E('svb').disabled=true;E('svb').textContent=tf('Save');
while(s.i<n&&!s.stop){var c=s.cards[s.i];E('svt').textContent=tf('Loading %1 of %2',s.i+1,n);
try{var b=await (await fetch(c.dataset.src,{cache:'no-store'})).blob();
if(s.files.length&&bytes+b.size>400e6)break;
s.files.push(new File([b],c.dataset.n,{type:b.type}));bytes+=b.size}catch(e){}
s.i++}
if(s.stop||SV!==s)return;var k=s.files.length;
E('svt').textContent=n>k?tf('%1–%2 of %3',s.from+1,s.i,n):'';
E('svb').textContent=tf('Save %1 to Photos',k);E('svb').disabled=!k}
async function svGo(){var s=SV;if(!s||!s.files.length)return;
try{await navigator.share({files:s.files})}catch(e){if(e.name!='AbortError')E('svt').textContent=tf('Error: %1',e.message);return}
if(s.i<s.cards.length)svLoad();else{E('svt').textContent=tf('Done ✓');E('svb').hidden=true;setTimeout(function(){if(SV===s)svStop()},2200)}}
function svStop(){if(SV)SV.stop=true;SV=null;E('svp').hidden=true;document.body.classList.toggle('haspill',!!SEL||!!window.BUSY)}
function zipAll(){location.href='z'}
function zipLiked(){var ids=C.filter(function(c){return L[c.dataset.id]}).map(function(c){return c.dataset.id});if(!ids.length)return;
if(ids.length==1){var c=C.filter(function(x){return x.dataset.id==ids[0]})[0];location.href=c.dataset.src+'?dl=1';return}
location.href='z?ids='+ids.join(',')}
paint();
</script>
""".trimIndent()

    // ---------------------------------------------------------------- helpers

    private fun head(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (keepAlive.get() == true) {
            sb.append("Connection: keep-alive\r\nKeep-Alive: timeout=20\r\n\r\n")
        } else {
            sb.append("Connection: close\r\n\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendText(out: OutputStream, code: Int, type: String, body: String, headOnly: Boolean = false) {
        val raw = body.toByteArray(Charsets.UTF_8)
        val gz = acceptGzip.get() == true && raw.size >= 512
        val bytes = if (gz) gzip(raw) else raw
        val h = mutableListOf("Content-Type" to type, "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store")
        if (gz) {
            h += "Content-Encoding" to "gzip"
            h += "Vary" to "Accept-Encoding"
        }
        head(out, code, h)
        if (!headOnly) out.write(bytes)
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(raw.size / 3)
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    /** Copies [limit] bytes (or everything when limit < 0). Returns bytes copied. */
    private fun copy(input: InputStream, out: OutputStream, limit: Long, tr: Visits.Transfer? = null, onBytes: ((Long) -> Unit)? = null): Long {
        val buf = ByteArray(256 * 1024)
        var left = limit
        var total = 0L
        while (left != 0L) {
            val want = if (left < 0) buf.size else minOf(buf.size.toLong(), left).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            tr?.let { Visits.progress(it, total) }
            onBytes?.invoke(total)
            if (left > 0) left -= n
        }
        return total
    }

    private fun readLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
            if (bos.size() > 16 * 1024) return null
        }
        return bos.toString("ISO-8859-1")
    }

    /** The value of cookie [name] in a Cookie header. */
    private fun cookie(header: String?, name: String): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun ext(name: String) = esc(name.substringAfterLast('.', "file").take(5))

    private fun fmtSize(b: Long): String = when {
        b < 0 -> ""
        b < 1000 -> "$b " + w("B")
        b < 1_000_000 -> String.format(loc(), "%.0f ", b / 1000.0) + w("KB")
        b < 1_000_000_000L -> String.format(loc(), "%.1f ", b / 1e6) + w("MB")
        else -> String.format(loc(), "%.2f ", b / 1e9) + w("GB")
    }

    private fun loc(): Locale = Locale.forLanguageTag(pageLang())

    private fun reason(code: Int) = when (code) {
        100 -> "Continue"; 200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"; 304 -> "Not Modified"
        404 -> "Not Found"; 411 -> "Length Required"; 416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    companion object {
        private const val MAX_REQUESTS_PER_CONNECTION = 200
        /** Holds this browser's device secret (see [Devices]). */
        private const val COOKIE = "ad"

        fun ruPlural(n: Int, one: String, few: String, many: String): String {
            val m = n % 10
            val h = n % 100
            return when {
                m == 1 && h != 11 -> one
                m in 2..4 && h !in 12..14 -> few
                else -> many
            }
        }

        /** The heart used both in the viewer and on liked thumbnails. */
        private const val HEART = """<svg viewBox="0 0 24 24"><path d="M12 20.5s-7.4-4.5-9.4-9C1.2 8.4 3.1 4.8 6.7 4.8c2.1 0 3.5 1.1 5.3 3.1 1.8-2 3.2-3.1 5.3-3.1 3.6 0 5.5 3.6 4.1 6.7-2 4.5-9.4 9-9.4 9z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>"""

        /** The app icon, drawn small: a gold cube glowing inside two open rings on obsidian. */
        /** The app icon, served small by [serveIcon]. */
        private const val MARK = """<img class="mark" src="/icon.png?v=2" alt="">"""
        @Volatile private var iconPng: ByteArray? = null
        private const val PLUS = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>"""
        private const val ARROW = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V6M6 11.5L12 5.5l6 6" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>"""

        /** Every text the page's scripts show (see jsDict); the page sends their translations along. */
        private val JS_KEYS = listOf(
            "All ♥ to Files", "All ♥ to Photos", "Big photo: you see a smaller copy here. Saving it from the list keeps the original.",
            "Preparing", "Restoring", "Compressing", "Compressed ✓", "Compress",
            "Swipe up or down. To save a photo to Photos, touch and hold it.", 
            "%1 s", "%1 min %2 s", "Waiting", "about %1 left",
            "Couldn't compress the photo.", "Bringing back the original", "Original: %1", "Couldn't bring back the original.",
            "Cancel", "Select", "Select none", "All", "Selected: %1", "Choose files", "Unlike", "Like",
            "Compressing %1 of %2", "Done: %d photo compressed|Done: %d photos compressed",
            "Compressed %1 of %2. The others couldn't be compressed.", "Save", "Loading %1 of %2", "%1–%2 of %3",
            "Save %1 to Photos", "Error: %1", "Done ✓", "Sending %1 of %2: %3", "Sent %d file|Sent %d files",
            "Sent %1 of %2. Some failed: try again.",
            "Sent ✓", "Couldn't send. Try again.", "Copied ✓", "Copy",
        )

        /**
         * Same AstraDrop look as the app: dark obsidian + champagne gold / light porcelain + ink,
         * over soft gold glows (no wallpaper: it looked blurry). Cards and panes are frosted glass.
         */
        private val CSS = """
:root{--base:#F6F2EC;--g1:#F0D3A2;--g2:#E9DCC8;--g3:#F3D6AA;
--glass:rgba(255,255,255,.46);--sheen:rgba(255,255,255,.64);--edge:rgba(255,255,255,.84);
--rim-hi:rgba(255,255,255,.92);--rim-lo:rgba(255,255,255,.45);--shadow:rgba(40,30,20,.07);--tile:rgba(255,255,255,.5);
--fg:#18171C;--sub:#5F5A55;--link:#A86210;
--pbg:linear-gradient(180deg,#2C2620,#14110D);--pfg:#FFE2A8;--pedge:rgba(255,255,255,.16);
--pshadow:inset 0 1px 0 rgba(255,255,255,.22),0 4px 14px rgba(30,24,18,.22);
--sbg:linear-gradient(180deg,rgba(255,255,255,.7),rgba(255,255,255,.48));--sfg:#A86210;--sedge:rgba(255,255,255,.88);
--bar:linear-gradient(90deg,#A86210,#D98A2B);--sel:#A86210}
@media (prefers-color-scheme:dark){:root{--base:#040302;--g1:#3A230C;--g2:#1C140B;--g3:#2E1B08;
--glass:rgba(21,22,28,.48);--sheen:rgba(255,255,255,.09);--edge:rgba(255,255,255,.14);
--rim-hi:rgba(255,255,255,.22);--rim-lo:rgba(255,255,255,.07);--shadow:rgba(0,0,0,.34);--tile:rgba(255,255,255,.06);
--fg:#F5F1EA;--sub:#BCB3A6;--link:#FFB645;
--pbg:linear-gradient(180deg,#FFDF9E,#E08C24);--pfg:#1E1002;--pedge:rgba(255,228,154,.82);
--pshadow:0 0 18px rgba(255,158,26,.36),inset 0 1px 0 rgba(255,255,255,.55);
--sbg:linear-gradient(180deg,rgba(255,255,255,.12),rgba(21,22,28,.54));--sfg:#FFB645;--sedge:rgba(255,255,255,.18);
--bar:linear-gradient(90deg,#E08C24,#FFDF9E);--sel:#E08C24}}
*{box-sizing:border-box}
html{background:var(--base)}
body{margin:0;color:var(--fg);font:16px -apple-system,"SF Pro Display","SF Pro Text",system-ui,sans-serif;-webkit-font-smoothing:antialiased;
padding:max(20px,env(safe-area-inset-top)) 16px max(40px,env(safe-area-inset-bottom));min-height:100vh}
body::before{content:"";position:fixed;inset:0;z-index:-1;background:
radial-gradient(circle 85vw at 10% 6%,var(--g1) 0%,transparent 100%),
radial-gradient(circle 80vw at 100% 34%,var(--g2) 0%,transparent 100%),
radial-gradient(circle 90vw at 10% 78%,var(--g3) 0%,transparent 100%),var(--base)}
.wrap{max-width:720px;margin:0 auto}
h1{font-size:36px;font-weight:700;letter-spacing:-.025em;margin:4px 4px 16px}h2{font-size:17px;font-weight:600;margin:0}
.glass{background:linear-gradient(var(--sheen),transparent 52%),var(--glass);border:1px solid var(--edge);border-radius:22px;
box-shadow:inset 0 1px 0 var(--rim-hi),inset 0 -1px 0 var(--rim-lo),0 4px 18px var(--shadow)}
.up{-webkit-backdrop-filter:blur(24px) saturate(160%);backdrop-filter:blur(24px) saturate(160%);padding:18px;margin-top:20px}
.row{display:flex;justify-content:space-between;align-items:center;margin:8px 4px 12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}
.card{overflow:hidden;display:flex;flex-direction:column;-webkit-backdrop-filter:blur(18px) saturate(170%);backdrop-filter:blur(18px) saturate(170%)}
.thumb{position:relative;display:block;aspect-ratio:1;background:var(--tile);margin:8px 8px 0;border-radius:14px;overflow:hidden}
.thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;z-index:1}
.ph{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--sub);font-weight:600;text-transform:uppercase}
.badge{position:absolute;z-index:2;left:8px;bottom:8px;background:rgba(0,0,0,.55);color:#fff;border-radius:99px;padding:2px 8px;font-size:12px}
.meta{padding:8px 12px 0}.name{font-size:13px;word-break:break-all;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.sub{font-size:13px;color:var(--sub);margin:4px 0 0;line-height:1.4}
.btn{display:inline-block;background:var(--pbg);color:var(--pfg);border:1px solid var(--pedge);border-radius:999px;padding:10px 18px;
box-shadow:var(--pshadow);font:600 15px -apple-system,system-ui,sans-serif;text-decoration:none;text-align:center;cursor:pointer;transition:transform .12s ease}
.btn.soft{background:var(--sbg);color:var(--sfg);border:1px solid var(--sedge);box-shadow:inset 0 1px 0 var(--rim-hi),0 2px 12px var(--shadow);
-webkit-backdrop-filter:blur(20px) saturate(160%);backdrop-filter:blur(20px) saturate(160%)}
.btn:active,.sb:active,.cb:active{transform:scale(.97)}
.hint{color:var(--sub);font-size:14px;margin:0 4px 12px}
.card .meta:last-child{padding-bottom:12px}
.up .btn{margin-top:14px}.act{padding:10px 10px 12px}.act .btn{display:block}
.bulk{display:flex;flex-wrap:wrap;gap:8px;margin:0 4px 14px}.btn[disabled]{opacity:.45;box-shadow:none}
.lk{position:absolute;z-index:3;right:7px;top:7px;width:32px;height:32px;border-radius:50%;background:rgba(0,0,0,.5);color:#FF3B5C;
display:none;align-items:center;justify-content:center}
.lk svg{width:19px;height:19px}.lk path{fill:currentColor}
.rowb{display:flex;gap:18px}.lnk{background:none;border:0;padding:4px 0;color:var(--link);font:600 16px -apple-system,system-ui,sans-serif;cursor:pointer}
.lnk[hidden]{display:none}
.thumb img{transition:filter .5s ease,transform .2s ease}
body.selecting .thumb::after{content:"";position:absolute;z-index:4;left:7px;top:7px;width:24px;height:24px;border-radius:50%;
border:2px solid #fff;background:rgba(0,0,0,.25);box-shadow:0 1px 3px rgba(0,0,0,.35);box-sizing:border-box}
body.selecting .card.picked .thumb::after{background:var(--sel) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 12.5l4.5 4.5L19 7.5' fill='none' stroke='white' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") center/15px no-repeat}
body.selecting .card.picked .thumb img{transform:scale(.92)}
body.selecting .card{cursor:pointer}
.card.busy .thumb img{filter:blur(8px) brightness(.9)}
.tb{position:absolute;z-index:4;left:10px;right:10px;bottom:10px;height:4px;border-radius:2px;background:rgba(255,255,255,.35);overflow:hidden}
.tb i{display:block;height:100%;width:0;background:#fff;border-radius:2px;transition:width .5s ease}
body.haspill{padding-bottom:110px}
.pill{position:fixed;z-index:15;left:50%;transform:translateX(-50%);bottom:calc(env(safe-area-inset-bottom) + 20px);display:flex;gap:8px;padding:5px;
border-radius:999px;white-space:nowrap;color:#fff;border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.62);
-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);box-shadow:0 4px 18px rgba(0,0,0,.3)}
.pill[hidden]{display:none}
.pill.txt{padding:0 18px;height:44px;align-items:center;font:600 15px -apple-system,system-ui;font-variant-numeric:tabular-nums}
.pill .cb{height:38px;padding:0 18px;font-size:15px}
.cb.lkb{background:rgba(255,255,255,.92);color:#FF3B5C;border:0;box-shadow:none}
.sec{display:none}body.secure .sec{display:inline}body.secure .btn.sec,body.secure .cb.sec{display:inline-block}body.secure .insec{display:none}
.pill{max-width:calc(100vw - 24px)}
.pill .pt{align-self:center;padding:0 6px 0 12px;font:600 15px -apple-system,system-ui;font-variant-numeric:tabular-nums;
min-width:0;overflow:hidden;text-overflow:ellipsis}.pill .pt:empty{display:none}
.pill .cb.x{width:38px;padding:0;background:rgba(255,255,255,.16);color:#fff;border:0;box-shadow:none}
.pill .cb:disabled{background:rgba(255,255,255,.14);color:rgba(255,255,255,.5);border-color:transparent;box-shadow:none}
.card.liked .lk{display:flex}
html.lock,html.lock body{overflow:hidden}
.feed{position:fixed;inset:0;z-index:20;background:#000;color:#fff}.feed[hidden]{display:none}
.fs{position:absolute;inset:0;overflow-y:scroll;scroll-snap-type:y mandatory;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;scrollbar-width:none}
.fs::-webkit-scrollbar{display:none}
.sl{height:100%;padding:6px 0;scroll-snap-align:start;scroll-snap-stop:always;display:flex;align-items:center;justify-content:center;overflow:hidden}
.sl img,.sl video{max-width:100%;max-height:100%;object-fit:contain;-webkit-touch-callout:default}
.sl img{border-radius:12px}
.sl video{width:100%}
.fx{position:absolute;z-index:2;top:max(14px,env(safe-area-inset-top));left:14px;width:40px;height:40px;border-radius:50%;
border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.5);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);color:#fff;font-size:17px}
.fc{position:absolute;z-index:1;top:max(14px,env(safe-area-inset-top));left:50%;transform:translateX(-50%);height:40px;padding:0 16px;
display:flex;align-items:center;gap:8px;white-space:nowrap;border-radius:999px;font:600 15px -apple-system,system-ui;pointer-events:none;
border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.5);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);box-shadow:0 2px 10px rgba(0,0,0,.25)}
.fb{position:absolute;z-index:2;left:16px;bottom:calc(env(safe-area-inset-bottom) + 24px);height:44px;padding:0 5px 0 16px;
display:flex;align-items:center;gap:12px;border-radius:999px;font:600 15px -apple-system,system-ui;
border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.5);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);box-shadow:0 2px 10px rgba(0,0,0,.25)}
.fp{position:absolute;z-index:2;top:50%;left:50%;transform:translate(-50%,-50%);padding:10px 18px;border-radius:999px;white-space:nowrap;
font:600 17px -apple-system,system-ui;font-variant-numeric:tabular-nums;pointer-events:none;
border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.5);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);box-shadow:0 2px 10px rgba(0,0,0,.25)}
.fp[hidden]{display:none}
.cb{white-space:nowrap;flex:none;height:34px;padding:0 14px;border-radius:999px;border:1px solid rgba(255,228,154,.75);
background:linear-gradient(180deg,#FFDF9E,#E08C24);color:#1E1002;box-shadow:0 0 14px rgba(255,158,26,.28),inset 0 1px 0 rgba(255,255,255,.45);
font:600 14px -apple-system,system-ui;transition:transform .12s ease}
.cb[hidden]{display:none}.fb:not(:has(.cb:not([hidden]))){padding-right:16px}
.cb.rv{background:rgba(255,255,255,.92);color:#1E1002;border:0;box-shadow:none;margin-left:-6px}
.cb:disabled{background:rgba(255,255,255,.14);color:rgba(255,255,255,.55);border-color:transparent;box-shadow:none}
.sl img{transition:filter .6s ease}.sl.blur img{filter:blur(22px) brightness(.85)}
.side{position:absolute;z-index:2;left:16px;bottom:calc(env(safe-area-inset-bottom) + 82px);display:flex;flex-direction:column;gap:16px}
.sb{width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;padding:0;
border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.5);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);
box-shadow:0 2px 10px rgba(0,0,0,.25);transition:transform .12s ease}
.sb svg{width:26px;height:26px;filter:drop-shadow(0 1px 2px rgba(0,0,0,.4))}
#bl.on{color:#FF3B5C}#bl.on path{fill:currentColor}
#bl.pop{animation:pop .35s ease}@keyframes pop{40%{transform:scale(1.3)}100%{transform:scale(1)}}
.toast{position:absolute;z-index:2;left:80px;right:16px;bottom:calc(env(safe-area-inset-bottom) + 82px);padding:12px 14px;border-radius:16px;
background:rgba(20,20,22,.72);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);font-size:14px;line-height:1.4}
.toast[hidden]{display:none}
.tip{color:var(--sub);font-size:13px;line-height:1.55;margin:16px 4px 0}.empty{color:var(--sub);margin:4px}
.up input{display:none}.bar{height:6px;background:var(--tile);border-radius:3px;margin-top:14px;overflow:hidden;display:none}
.bar div{height:100%;width:0;background:var(--bar)}#ustat{font-size:13px;color:var(--sub);margin-top:8px}
.txts{display:flex;flex-direction:column;gap:10px;margin:0 0 20px}.tx{padding:14px 14px 12px;-webkit-backdrop-filter:blur(18px) saturate(170%);backdrop-filter:blur(18px) saturate(170%)}
.tv{white-space:pre-wrap;word-break:break-word;font-size:15px;line-height:1.45;max-height:220px;overflow:auto;margin-bottom:10px}.tv a{color:var(--link)}
#tx{display:block;width:100%;margin-top:18px;padding:12px 14px;border-radius:16px;border:1px solid var(--edge);background:var(--tile);color:var(--fg);font:16px -apple-system,system-ui,sans-serif;resize:vertical}
#txs{display:block;font-size:13px;color:var(--sub);margin:8px 4px 0}#txs:empty{display:none}
/* ---- look: the app's mark, quiet section labels, photo-first grid */
.top{display:flex;align-items:center;gap:12px;margin:6px 2px 22px}
.mark{width:44px;height:44px;flex:none;border-radius:11px;box-shadow:0 4px 14px rgba(224,140,36,.25)}
.top h1{font-size:26px;letter-spacing:-.02em;margin:0;flex:1}
.chip{display:flex;align-items:center;gap:7px;padding:6px 12px 6px 10px;border-radius:999px;font-size:13px;font-weight:600;color:var(--sub);
background:var(--glass);border:1px solid var(--edge);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px)}
.chip i{width:8px;height:8px;border-radius:50%;background:#34C759;box-shadow:0 0 0 0 rgba(52,199,89,.5);animation:live 2.4s ease-out infinite}
@keyframes live{0%{box-shadow:0 0 0 0 rgba(52,199,89,.55)}70%,100%{box-shadow:0 0 0 7px rgba(52,199,89,0)}}
@media (prefers-reduced-motion:reduce){.chip i{animation:none}}
h2{font-size:13px;font-weight:650;letter-spacing:.07em;text-transform:uppercase;color:var(--sub)}
.row{margin:4px 4px 12px}.lnk{font-size:15px}
.bulk{gap:6px;padding:5px;border-radius:999px;margin:0 0 14px;background:var(--glass);border:1px solid var(--edge);
-webkit-backdrop-filter:blur(20px) saturate(160%);backdrop-filter:blur(20px) saturate(160%);box-shadow:inset 0 1px 0 var(--rim-hi),0 4px 18px var(--shadow)}
.bulk .btn{flex:1;padding:10px 12px;font-size:14px;white-space:nowrap}
.bulk .btn.soft{background:transparent;border:1.5px solid var(--link);color:var(--link);box-shadow:none;-webkit-backdrop-filter:none;backdrop-filter:none}
.bulk .btn.soft[disabled]{opacity:.4}
.hint{font-size:13px;margin:0 6px 10px}
.grid{grid-template-columns:repeat(3,1fr);gap:6px}
@media (min-width:600px){.grid{grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}}
.card[data-kind=p],.card[data-kind=v]{position:relative;background:none;border:0;box-shadow:none;border-radius:16px;-webkit-backdrop-filter:none;backdrop-filter:none}
.card[data-kind=p] .thumb,.card[data-kind=v] .thumb{margin:0;border-radius:16px}
.card[data-kind=p] .name,.card[data-kind=v] .name{display:none}
.card[data-kind=p] .meta,.card[data-kind=v] .meta{position:absolute;z-index:3;right:6px;bottom:6px;padding:0;pointer-events:none}
.card[data-kind=p] .sub,.card[data-kind=v] .sub{margin:0;padding:2px 7px;border-radius:99px;font-size:11px;font-weight:600;color:#fff;
background:rgba(0,0,0,.45);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px)}
.card[data-kind=p] .sub:empty,.card[data-kind=v] .sub:empty{display:none}
.card[data-kind=f]{grid-column:span 3}@media (min-width:600px){.card[data-kind=f]{grid-column:auto}}
.card[data-kind=f]{flex-direction:row;align-items:center}.card[data-kind=f] .thumb{width:56px;flex:none;margin:8px}
.card[data-kind=f] .meta{flex:1;padding:8px 4px}.card[data-kind=f] .act{padding:8px 12px}
.badge{font-size:11px;padding:2px 7px}
.tip{margin:14px 4px 0}.tip summary{cursor:pointer;font-weight:600;color:var(--link);list-style:none}.tip summary::-webkit-details-marker{display:none}
.tip summary::after{content:" ›";display:inline-block;transition:transform .2s}.tip[open] summary::after{transform:rotate(90deg)}.tip p{margin:8px 0 0}
.up{padding:16px;margin-top:22px}.up h2{margin:2px 4px 12px}
.drop{display:flex;align-items:center;gap:14px;padding:14px;border-radius:18px;cursor:pointer;border:1.5px dashed var(--edge);border:1.5px dashed color-mix(in srgb,var(--link) 55%,transparent);
background:color-mix(in srgb,var(--link) 7%,transparent);transition:transform .12s ease}.drop:active{transform:scale(.98)}
.drop b{display:block;font-size:16px;color:var(--fg)}.drop .sub{display:block;margin:2px 0 0}
.plus{width:46px;height:46px;flex:none;border-radius:14px;display:flex;align-items:center;justify-content:center;background:var(--pbg);color:var(--pfg);box-shadow:var(--pshadow)}
.plus svg{width:28px;height:28px}.send svg{width:24px;height:24px}.plus svg path,.send svg path{stroke-width:2.6}
.txrow{display:flex;align-items:flex-end;gap:8px;margin-top:12px;padding:5px 5px 5px 14px;border-radius:24px;background:var(--tile);border:1px solid var(--edge)}
#tx{flex:1;width:auto;margin:0;padding:9px 0;border:0;border-radius:0;background:none;resize:none;outline:none;max-height:160px;line-height:1.35}
.send{width:38px;height:38px;flex:none;border-radius:50%;border:0;padding:0;display:flex;align-items:center;justify-content:center;
background:var(--pbg);color:var(--pfg);box-shadow:var(--pshadow);cursor:pointer;transition:transform .12s ease}.send:active{transform:scale(.94)}.send:disabled{opacity:.5}
.tx .btn{padding:8px 16px;font-size:14px}
.empty{font-size:15px;line-height:1.45;margin:0 6px 4px}
.up h2.msg{font-size:20px;font-weight:650;letter-spacing:-.01em;text-transform:none;color:var(--fg);margin:2px 2px 6px}
""".trimIndent()
    }
}
