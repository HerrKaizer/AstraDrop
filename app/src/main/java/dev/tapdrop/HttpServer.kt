package dev.tapdrop

import android.content.ContentValues
import android.content.Context
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal HTTP/1.1 server with keep-alive. Every URL lives under /<token>/ so only
 * someone who received the link (by NFC tap or QR) can reach the files.
 *
 *   GET  /<token>/               page listing the shared files + upload form (Russian)
 *   GET  /<token>/f/<id>/<name>  the file (Range supported; ?dl=1 forces download)
 *   GET  /<token>/t/<id>         JPEG thumbnail (pre-made by ThumbStore)
 *   POST /<token>/u?n=&m=        raw upload body, saved to the phone's gallery/downloads
 */
class HttpServer(
    private val ctx: Context,
    /** When set, this server speaks HTTPS with it (see [TlsCert]). */
    private val tls: javax.net.ssl.SSLContext? = null,
    private val onUpload: (ReceivedItem) -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    /** Whether the current response may leave the connection open for the next request. */
    private val keepAlive = ThreadLocal<Boolean>()

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
        val isUpload = method == "POST" && segs.size == 2 && segs[0] == ShareState.token && segs[1] == "u"
        val hasBody = (headers["content-length"]?.toLongOrNull() ?: 0L) > 0L || headers.containsKey("transfer-encoding")
        if (hasBody && !isUpload) keepAlive.set(false) // body won't be read, so this connection can't be reused
        if (segs.isEmpty() || segs[0] != ShareState.token) {
            sendText(out, 404, "text/html; charset=utf-8", expiredPage())
            return
        }
        if (segs.size == 1 && !path.endsWith("/")) {
            head(out, 302, listOf("Location" to "/${ShareState.token}/", "Content-Length" to "0"))
            return
        }
        val isHead = method == "HEAD"
        when {
            segs.size == 1 && (method == "GET" || isHead) -> sendText(out, 200, "text/html; charset=utf-8", indexPage(headers["host"]), isHead)
            segs.size >= 3 && segs[1] == "f" && (method == "GET" || isHead) -> serveFile(segs[2], query, headers, out, isHead)
            segs.size == 3 && segs[1] == "t" && method == "GET" -> serveThumb(segs[2], out)
            segs.size == 3 && segs[1] == "p" && method == "GET" -> servePreview(segs[2], out)
            segs.size == 2 && segs[1] == "u" && method == "POST" -> receiveUpload(query, headers, input, out)
            segs.size == 2 && segs[1] == "z" && method == "GET" -> serveZip(query, out)
            segs.size == 3 && segs[1] == "c" && method == "POST" -> compress(segs[2], out)
            segs.size == 3 && segs[1] == "cp" && method == "GET" -> compressProgress(segs[2], out)
            segs.size == 3 && segs[1] == "r" && method == "POST" -> revert(segs[2], out)
            // which files are shared, so an open page can notice files added or removed on the phone
            segs.size == 2 && segs[1] == "ids" && method == "GET" -> sendText(out, 200, "text/plain", listSignature())
            else -> sendText(out, 404, "text/plain; charset=utf-8", "Не найдено")
        }
    }

    // ---------------------------------------------------------------- files

    private fun serveFile(id: String, query: Map<String, String>, headers: Map<String, String>, out: OutputStream, isHead: Boolean) {
        RawConverter.await(id) // a DNG still being turned into JPEG
        val item = ShareState.items.find { it.id == id }
        if (item == null) {
            sendText(out, 404, "text/plain; charset=utf-8", "Этот файл больше не доступен.")
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
        val sent = openAt(item.uri, start).use { copy(it, out, length) }
        if (length >= 0 && sent != length) keepAlive.set(false)
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
    private fun serveZip(query: Map<String, String>, out: OutputStream) {
        val ids = query["ids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ShareState.items.filter { ids == null || it.id in ids }.forEach { RawConverter.await(it.id) }
        val chosen = ShareState.items.filter { ids == null || it.id in ids }
        if (chosen.isEmpty()) {
            sendText(out, 404, "text/plain; charset=utf-8", "Нет файлов для скачивания.")
            return
        }
        val fileName = if (ids == null) "SolDrop.zip" else "SolDrop-liked.zip"
        keepAlive.set(false) // size isn't known up front, so the end is marked by closing
        head(out, 200, listOf(
            "Content-Type" to "application/zip",
            "Content-Disposition" to "attachment; filename=\"$fileName\"",
            "Cache-Control" to "no-store",
        ))
        val zos = ZipOutputStream(out)
        zos.setLevel(Deflater.NO_COMPRESSION)
        val used = HashSet<String>()
        for (item in chosen) {
            var entry = item.name
            var n = 1
            while (!used.add(entry.lowercase())) {
                val dot = item.name.lastIndexOf('.')
                entry = if (dot > 0) "${item.name.substring(0, dot)} ($n)${item.name.substring(dot)}" else "${item.name} ($n)"
                n++
            }
            zos.putNextEntry(ZipEntry(entry))
            ctx.contentResolver.openInputStream(item.uri)?.use { copy(it, zos, -1) }
            zos.closeEntry()
        }
        zos.finish()
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
        head(out, 200, listOf("Content-Type" to "image/jpeg", "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store"))
        out.write(bytes)
    }

    private fun serveThumb(id: String, out: OutputStream) {
        val item = ShareState.items.find { it.id == id }
        // a DNG's own thumbnail can come out sideways, so converted RAWs use their JPEG
        val bytes = item?.let {
            val fromOrig = it.orig != null && it.origMime?.contains("dng", ignoreCase = true) != true
            if (fromOrig) ThumbStore.get(ctx, it.orig!!, it.origMime ?: it.mime)?.jpeg
            else ThumbStore.get(ctx, it.uri, it.mime)?.jpeg
        }
        if (bytes == null || bytes.isEmpty()) {
            sendText(out, 404, "text/plain", "")
            return
        }
        head(out, 200, listOf("Content-Type" to "image/jpeg", "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store"))
        out.write(bytes)
    }

    // ---------------------------------------------------------------- uploads (iPhone -> this phone)

    private fun receiveUpload(query: Map<String, String>, headers: Map<String, String>, input: InputStream, out: OutputStream) {
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
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/SolDrop"
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/SolDrop"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/SolDrop"
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
            cr.openOutputStream(dest)?.use { copy(input, it, length) } ?: -1L
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
        onUpload(ReceivedItem(name, dest, mime))
        sendText(out, 200, "application/json", "{\"ok\":true}")
    }

    // ---------------------------------------------------------------- pages

    private fun indexPage(host: String?): String {
        val items = ShareState.items.toList()
        // the HTTPS twin of this page (see TlsCert), for the IP the iPhone reached us on
        val ip = host?.substringBefore(':')?.takeIf { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }
        val tlsUrl = if (ip != null && ShareState.tlsPort > 0) "https://${TlsCert.hostFor(ip)}:${ShareState.tlsPort}/${ShareState.token}/" else ""
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
            val thumb = if (isMedia) "<img src=\"t/${it.id}\" alt=\"\" decoding=\"async\" onerror=\"this.remove()\">" else ""
            // photos and videos open the swipe viewer; other files download to Files
            val open = if (isMedia) "onclick=\"return of(${mediaIdx})\"" else ""
            // tapping a photo/video thumbnail opens the viewer, so only other files get a button
            val act = if (isMedia) ""
            else "<div class=\"act\"><a class=\"btn soft\" href=\"$href?dl=1\" download=\"$n\">Сохранить</a></div>"
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
        val hint = if (mediaIdx > 0) "<p class=\"hint\">Нажмите на фото, чтобы открыть его и отметить ♥</p>" else ""
        val list = if (items.isEmpty())
            "<p class=\"empty\">С Android-телефона сейчас ничего не отправляется. Но вы можете отправить на него файлы ниже.</p>"
        else
            """
            <div class="row"><h2 id="cnt">$count ${ruPlural(count, "файл", "файла", "файлов")}</h2><span class="rowb"><button id="sall" class="lnk" onclick="selAll()" hidden>Все</button><button id="selb" class="lnk" onclick="selMode()">Выбрать</button></span></div>
            <div class="bulk"><button class="btn soft insec" onclick="zipAll()">Все в «Файлы»</button><button id="lb" class="btn soft insec" onclick="zipLiked()" disabled>Все ♥ в «Файлы»</button><button class="btn soft sec" onclick="toPhotos(C.filter(isMedia))">Все в «Фото»</button><button id="lp" class="btn soft sec" onclick="toPhotos(C.filter(function(c){return L[c.dataset.id]&&isMedia(c)}))" disabled>Все ♥ в «Фото»</button></div>
            $hint
            <div class="grid">$cards</div>
            <p class="tip"><b>Фото и видео:</b> нажмите на файл и листайте вверх-вниз. ♥ — отметить, ⬇ — скачать в «Файлы», ⤴ — поделиться.<br>
            <span class="sec"><b>В «Фото»:</b> «Все в «Фото»» или «Выбрать» → «В «Фото»», затем «Сохранить» → <i>Сохранить изображения</i>. Одно фото — ⤴ в просмотре.</span>
            <span class="insec"><b>В «Фото»:</b> нажмите и удерживайте фото → <i>Сохранить в Фото</i>.<br>
            <b>Всё сразу в «Фото»:</b> «Все в «Файлы»» скачивает один архив. Откройте его в «Файлах», затем в папке «Выбрать» → «Выбрать все» → «Поделиться» → <i>Сохранить</i>.</span></p>
            $FEED_HTML
            """.trimIndent()
        return """
<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<title>SolDrop</title>
<style>$CSS</style></head><body>
<script>try{if(window.isSecureContext&&navigator.canShare&&navigator.canShare({files:[new File(['x'],'a.jpg',{type:'image/jpeg'})]}))document.body.classList.add('secure')}catch(e){}</script>
<h1>SolDrop</h1>
$list
<div class="up glass"><h2>Отправить на Android</h2>
<p class="sub">Выберите фото, видео или файлы — они сохранятся на другом телефоне.</p>
<label class="btn" for="f">Выбрать файлы</label><input id="f" type="file" multiple onchange="up(this.files)">
<div class="bar" id="bar"><div id="fill"></div></div><div id="ustat"></div></div>
<script>
function pl(n,a,b,c){var m=n%10,h=n%100;return m==1&&h!=11?a:(m>=2&&m<=4&&(h<12||h>14)?b:c)}
var IDS='${listSignature()}',stale=false,TLS='$tlsUrl';
// Over HTTPS, Safari can save many photos to Photos at once. The HTTPS address needs
// internet to look up its name, so switch only when it actually answers.
if(TLS&&location.protocol=='http:'){var ac=new AbortController();setTimeout(function(){ac.abort()},3000);
fetch(TLS+'ids',{mode:'no-cors',cache:'no-store',signal:ac.signal}).then(function(){location.replace(TLS)}).catch(function(){})}
function fresh(){var v=document.getElementById('fv');if(window.BUSY||(v&&!v.hidden)){stale=true;return}location.reload()}
setInterval(function(){if(document.hidden||stale)return;fetch('ids',{cache:'no-store'}).then(function(r){return r.ok?r.text():null})
.then(function(t){if(t!=null&&t!=IDS)fresh()}).catch(function(){})},2500);
async function up(files){var bar=document.getElementById('bar'),fill=document.getElementById('fill'),st=document.getElementById('ustat');
bar.style.display='block';var ok=0;for(var i=0;i<files.length;i++){var f=files[i];st.textContent='Отправка '+(i+1)+' из '+files.length+': '+f.name;
try{await new Promise(function(res,rej){var x=new XMLHttpRequest();x.open('POST','u?n='+encodeURIComponent(f.name)+'&m='+encodeURIComponent(f.type||''));
x.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=(100*e.loaded/e.total)+'%'};x.onload=function(){x.status==200?res():rej()};x.onerror=rej;x.send(f)});ok++}catch(e){}}
fill.style.width='100%';st.textContent=ok==files.length?('Отправлено: '+ok+' '+pl(ok,'файл','файла','файлов')+' ✓'):('Отправлено '+ok+' из '+files.length+'. Часть не удалась — попробуйте ещё раз.');document.getElementById('f').value=''}
</script></body></html>
""".trimIndent()
    }

    /**
     * What an open page compares to notice it's out of date: the shared ids, with
     * "~" on DNGs still being converted (so the page refreshes when one finishes).
     * Compressing doesn't change it; the page updates itself for that.
     */
    private fun listSignature() =
        ShareState.items.joinToString(",") { it.id + if (RawConverter.isConverting(it.id)) "~" else "" }

    private fun expiredPage(): String = """
<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>SolDrop</title><style>$CSS</style></head>
<body><div class="up glass"><h2>Ссылка устарела</h2><p class="sub">Коснитесь телефона ещё раз, чтобы получить новую ссылку.</p></div></body></html>
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
        val bytes = body.toByteArray(Charsets.UTF_8)
        head(out, code, listOf("Content-Type" to type, "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store"))
        if (!headOnly) out.write(bytes)
    }

    /** Copies [limit] bytes (or everything when limit < 0). Returns bytes copied. */
    private fun copy(input: InputStream, out: OutputStream, limit: Long): Long {
        val buf = ByteArray(256 * 1024)
        var left = limit
        var total = 0L
        while (left != 0L) {
            val want = if (left < 0) buf.size else minOf(buf.size.toLong(), left).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
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
        b < 1000 -> "$b Б"
        b < 1_000_000 -> String.format(RU, "%.0f КБ", b / 1000.0)
        b < 1_000_000_000L -> String.format(RU, "%.1f МБ", b / 1e6)
        else -> String.format(RU, "%.2f ГБ", b / 1e9)
    }

    private fun reason(code: Int) = when (code) {
        100 -> "Continue"; 200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"
        404 -> "Not Found"; 411 -> "Length Required"; 416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    companion object {
        private const val MAX_REQUESTS_PER_CONNECTION = 200
        private val RU: Locale = Locale.forLanguageTag("ru")

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

        /**
         * Full-screen, TikTok-style viewer: swipe up/down between photos and videos
         * (CSS scroll-snap, so it moves with the finger natively), with heart,
         * download and share buttons on the side. Hearts are kept in localStorage
         * per link and shown on the grid thumbnails.
         */
        private val FEED_HTML = """
<div id="fv" class="feed" hidden>
<div id="fs" class="fs"></div>
<button class="fx" onclick="cf()" aria-label="Закрыть">✕</button>
<div class="fc" id="fc"></div>
<div class="side">
<button id="bl" class="sb" onclick="tl()" aria-label="Нравится">$HEART</button>
<a id="bd" class="sb" href="#" download aria-label="Скачать"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg></a>
<button id="bs" class="sb" onclick="sh()" aria-label="Поделиться"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12M8 7l4-4 4 4M7 11H5v10h14V11h-2"/></svg></button>
</div>
<div id="fp" class="fp" hidden></div>
<div class="fb"><span id="fsz"></span><button id="bc" class="cb" onclick="cmp()">Сжать</button><button id="br" class="cb rv" onclick="rev()" hidden>Вернуть</button></div>
<div id="ft" class="toast" hidden></div>
</div>
<div id="selbar" class="pill" hidden><button id="sl" class="cb lkb" onclick="selLike()">♥ Нравится</button><button id="sc" class="cb" onclick="selCompress()">Сжать</button><button id="sp" class="cb sec" onclick="selPhotos()">В «Фото»</button></div>
<div id="svp" class="pill" hidden><span id="svt" class="pt"></span><button id="svb" class="cb" onclick="svGo()" disabled>Сохранить</button><button class="cb x" onclick="svStop()" aria-label="Отмена">✕</button></div>
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
var b=E('lb');if(b){b.disabled=n==0;b.textContent='Все ♥ в «Файлы»'+(n?' ('+n+')':'')}
var lm=C.filter(function(c){return L[c.dataset.id]&&c.dataset.kind!='f'}).length;
b=E('lp');if(b){b.disabled=lm==0;b.textContent='Все ♥ в «Фото»'+(lm?' ('+lm+')':'')}}
function build(){E('fs').innerHTML=M.map(function(m,i){return '<div class="sl" data-i="'+i+'">'+(m.k=='v'
?'<video playsinline controls preload="none" data-src="'+m.src+'"></video>':'<img alt="" data-src="'+vsrc(m)+'">')+'</div>'}).join('');
E('fs').addEventListener('scroll',function(){if(raf)return;raf=requestAnimationFrame(function(){raf=0;
var fs=E('fs'),i=Math.round(fs.scrollTop/Math.max(1,fs.clientHeight));if(i!=cur)setCur(i)})},{passive:true});built=true}
function load(i){var ch=E('fs').children;for(var j=0;j<ch.length;j++){var el=ch[j].firstChild;if(!el)continue;
if(Math.abs(j-i)<=1){if(!el.getAttribute('src')&&el.dataset.src)el.src=el.dataset.src}
else if(el.getAttribute('src')&&!M[j].busy){el.removeAttribute('src');if(el.tagName=='VIDEO')el.load()}}}
function setCur(i){if(i<0||i>=M.length)return;cur=i;load(i);E('fc').textContent=(i+1)+' / '+M.length;var m=M[i];E('fsz').textContent=m.s||'—';
if(m.big&&!bigHinted&&E('ft').hidden){bigHinted=true;toast('Большое фото: здесь показана уменьшенная копия. Оригинал — кнопка ⬇.')}
E('fp').hidden=!m.busy;if(m.busy)E('fp').textContent=m.pt||'Подготовка';
var bc=E('bc');bc.hidden=!m.cc;bc.disabled=m.c||!!m.busy;bc.textContent=m.busy?(m.rv?'Возврат':'Сжатие'):(m.c?'Сжато ✓':'Сжать');
var br=E('br');br.hidden=!m.c||!!m.busy;
E('bl').classList.toggle('on',!!L[M[i].id]);var d=E('bd');d.href=M[i].src+'?dl=1';d.setAttribute('download',M[i].n);
[].forEach.call(E('fs').querySelectorAll('video'),function(v){if(+v.parentNode.dataset.i!=i)v.pause()})}
function of(i){if(!M.length)return false;if(!built)build();E('fv').hidden=false;document.documentElement.classList.add('lock');
if(!hinted){hinted=true;toast('Листайте вверх-вниз. Чтобы сохранить фото в «Фото» — нажмите и удерживайте его.')}
var fs=E('fs');fs.scrollTop=i*fs.clientHeight;setCur(i);return false}
function cf(){E('fv').hidden=true;document.documentElement.classList.remove('lock');if(typeof stale!='undefined'&&stale&&!window.BUSY){location.reload();return}
[].forEach.call(E('fs').querySelectorAll('video'),function(v){v.pause()});paint()}
function tl(){var id=M[cur].id;if(L[id])delete L[id];else L[id]=1;saveL();var b=E('bl');b.classList.toggle('on',!!L[id]);
b.classList.remove('pop');void b.offsetWidth;b.classList.add('pop');paint()}
function toast(t){var f=E('ft');f.textContent=t;f.hidden=false;clearTimeout(tt);tt=setTimeout(function(){f.hidden=true},3800)}
// Safari only opens the share sheet right after a tap; a big photo can take longer than that
// to load, so the loaded file is kept and the next tap on ⤴ shares it at once.
var LASTF=null;
function sh(){var m=M[cur];
if(window.isSecureContext&&navigator.share&&navigator.canShare){
if(LASTF&&LASTF.id==m.id&&LASTF.s==m.s){navigator.share({files:[LASTF.f]}).catch(function(){});return}
toast('Загрузка фото…');fetch(m.src,{cache:'no-store'}).then(function(r){return r.blob()}).then(function(b){
LASTF={id:m.id,s:m.s,f:new File([b],m.n,{type:b.type})};return navigator.share({files:[LASTF.f]})}).then(function(){E('ft').hidden=true})
.catch(function(e){if(e&&e.name=='NotAllowedError')toast('Фото загружено — нажмите ⤴ ещё раз, затем «Сохранить изображение».');else E('ft').hidden=true});return}
if(m.k=='v'){toast('Видео откроется отдельно — там нажмите «Поделиться» → «Сохранить видео».');setTimeout(function(){location.href=m.src},1600)}
else toast('Нажмите и удерживайте фото → «Поделиться…» или «Сохранить в Фото».')}
function dur(s){return s<60?s+' с':Math.floor(s/60)+' мин '+(s%60)+' с'}
function cmp(){var i=cur,m=M[i];if(m.c||m.busy)return;m.busy=true;m.pt='';var sl=E('fs').children[i];sl.classList.add('blur');setCur(i);
var pt=setInterval(function(){fetch('cp/'+m.id).then(function(r){return r.json()}).then(function(j){if(!m.busy)return;
m.pt=j.wait?'В очереди':(j.p<=0?'':Math.floor(j.p*100)+'%'+(j.eta!=null?' · ещё ~'+dur(j.eta):''));
if(cur==i){E('fp').hidden=false;E('fp').textContent=m.pt||'Подготовка'}}).catch(function(){})},700);
var old=m.s;function end(){clearInterval(pt);m.busy=false;m.pt='';sl.classList.remove('blur');if(cur==i)setCur(i)}
fetch('c/'+m.id,{method:'POST'}).then(function(r){return r.json()}).then(function(j){if(!j.ok)throw 0;
m.s=j.s;m.c=true;if(j.n)m.n=j.n;var card=C.filter(function(c){return c.dataset.id==m.id})[0];
if(card){card.dataset.s=j.s;card.dataset.c='1';var sb=card.querySelector('.sub');if(sb)sb.textContent=j.s;var nm=card.querySelector('.name');if(nm&&j.n)nm.innerHTML=j.n}
var el=sl.firstChild;if(m.k!='p'||!el){end();return}var im=new Image();im.onload=im.onerror=function(){el.src=im.src;end()};
im.src=vsrc(m)+'?v='+Date.now();if(old&&old!=j.s)toast(old+' → '+j.s)}).catch(function(){end();toast('Не удалось сжать фото.')})}
function rev(){var i=cur,m=M[i];if(!m.c||m.busy)return;m.busy=true;m.rv=true;m.pt='Возврат оригинала';var sl=E('fs').children[i];sl.classList.add('blur');setCur(i);
function end(){m.busy=false;m.rv=false;m.pt='';sl.classList.remove('blur');if(cur==i)setCur(i)}
fetch('r/'+m.id,{method:'POST'}).then(function(r){return r.json()}).then(function(j){if(!j.ok)throw 0;
m.s=j.s;m.c=false;if(j.n)m.n=j.n;var card=C.filter(function(c){return c.dataset.id==m.id})[0];
if(card){card.dataset.s=j.s;card.dataset.c='0';var sb=card.querySelector('.sub');if(sb)sb.textContent=j.s;var nm=card.querySelector('.name');if(nm&&j.n)nm.innerHTML=j.n}
toast('Оригинал: '+j.s);var el=sl.firstChild;if(m.k!='p'||!el){end();return}var im=new Image();im.onload=im.onerror=function(){el.src=im.src;end()};
im.src=vsrc(m)+'?v='+Date.now()}).catch(function(){end();toast('Не удалось вернуть оригинал.')})}
// ---- select mode: tap thumbnails to pick them, then like or compress them all
var SEL=null,CNT=E('cnt').textContent;
function picked(){return C.filter(function(c){return SEL&&SEL[c.dataset.id]})}
function canSq(c){return c.dataset.cc=='1'&&c.dataset.c!='1'&&!c.classList.contains('busy')}
function selMode(){if(window.BUSY)return;SEL=SEL?null:{};document.body.classList.toggle('selecting',!!SEL);selPaint()}
function selAll(){var all=picked().length==C.length;C.forEach(function(c){if(all)delete SEL[c.dataset.id];else SEL[c.dataset.id]=1});selPaint()}
function selPaint(){var p=picked(),n=p.length;C.forEach(function(c){c.classList.toggle('picked',!!(SEL&&SEL[c.dataset.id]))});
E('selb').textContent=SEL?'Отмена':'Выбрать';E('sall').hidden=!SEL;E('sall').textContent=n==C.length?'Снять все':'Все';
E('selbar').hidden=!SEL;document.body.classList.toggle('haspill',!!SEL||!!window.BUSY);
E('cnt').textContent=SEL?(n?'Выбрано: '+n:'Выберите файлы'):CNT;
var allL=n>0&&p.every(function(c){return L[c.dataset.id]});E('sl').disabled=!n;E('sl').textContent=allL?'♥ Убрать':'♥ Нравится';
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
bp.hidden=false;bp.textContent='Сжатие '+(i+1)+' из '+cards.length+' · '+Math.floor(f*100)+'%'+(f>=0.02?' · ещё ~'+dur(Math.round(s*(1-f)/f)):'')}
for(var i=0;i<cards.length;i++){var c=cards[i],id=c.dataset.id,bar=c.querySelector('.tb i');show(i,0);
var poll=setInterval((function(i,id,bar){return function(){fetch('cp/'+id).then(function(r){return r.json()}).then(function(j){
if(j.p>0){bar.style.width=(j.p*100)+'%';show(i,j.p)}}).catch(function(){})}})(i,id,bar),600);
try{var r=await fetch('c/'+id,{method:'POST'}),j=await r.json();if(j.ok){cardDone(id,j);ok++}}catch(e){}
clearInterval(poll);done+=w[i];c.classList.remove('busy');var tb=c.querySelector('.tb');if(tb)tb.remove()}
bp.textContent=ok==cards.length?'Готово: сжато '+ok+' '+pl(ok,'фото','фото','фото'):'Сжато '+ok+' из '+cards.length+'. Остальные не удалось сжать.';
window.BUSY=false;setTimeout(function(){bp.hidden=true;document.body.classList.toggle('haspill',!!SEL);if(stale&&E('fv').hidden)location.reload()},2600)}
// ---- straight to Photos (HTTPS only): load the files, then a tap opens the share sheet with
// «Save N Images». Safari keeps every loaded file in memory, so big sets go in batches of ≤400 MB.
var SV=null;
if(window.isSecureContext&&navigator.canShare&&navigator.canShare({files:[new File(['x'],'a.jpg',{type:'image/jpeg'})]}))document.body.classList.add('secure');
function isMedia(c){return c.dataset.kind!='f'}
function selPhotos(){var p=picked().filter(isMedia);if(!p.length)return;selMode();toPhotos(p)}
function toPhotos(cards){if(SV||window.BUSY||!cards.length)return;SV={cards:cards,i:0,files:[],from:0,stop:false};
document.body.classList.add('haspill');E('svp').hidden=false;E('svb').hidden=false;svLoad()}
async function svLoad(){var s=SV,n=s.cards.length,bytes=0;s.files=[];s.from=s.i;E('svb').disabled=true;E('svb').textContent='Сохранить';
while(s.i<n&&!s.stop){var c=s.cards[s.i];E('svt').textContent='Загрузка '+(s.i+1)+' из '+n;
try{var b=await (await fetch(c.dataset.src,{cache:'no-store'})).blob();
if(s.files.length&&bytes+b.size>400e6)break;
s.files.push(new File([b],c.dataset.n,{type:b.type}));bytes+=b.size}catch(e){}
s.i++}
if(s.stop||SV!==s)return;var k=s.files.length;
E('svt').textContent=n>k?(s.from+1)+'–'+s.i+' из '+n:'';
E('svb').textContent='Сохранить '+k+' в «Фото»';E('svb').disabled=!k}
async function svGo(){var s=SV;if(!s||!s.files.length)return;
try{await navigator.share({files:s.files})}catch(e){if(e.name!='AbortError')E('svt').textContent='Ошибка: '+e.message;return}
if(s.i<s.cards.length)svLoad();else{E('svt').textContent='Готово ✓';E('svb').hidden=true;setTimeout(function(){if(SV===s)svStop()},2200)}}
function svStop(){if(SV)SV.stop=true;SV=null;E('svp').hidden=true;document.body.classList.toggle('haspill',!!SEL||!!window.BUSY)}
function zipAll(){location.href='z'}
function zipLiked(){var ids=C.filter(function(c){return L[c.dataset.id]}).map(function(c){return c.dataset.id});if(!ids.length)return;
if(ids.length==1){var c=C.filter(function(x){return x.dataset.id==ids[0]})[0];location.href=c.dataset.src+'?dl=1';return}
location.href='z?ids='+ids.join(',')}
paint();
</script>
""".trimIndent()

        /**
         * Same look as the app: warm wallpaper glows behind frosted-glass panes.
         * Only the larger panes use backdrop blur; the many file cards are plain
         * translucent so long lists scroll smoothly on the iPhone.
         */
        private val CSS = """
:root{--base:#F3EEEA;--g1:#DA8C4C;--g2:#95A0D6;--g3:#C98352;--g4:#A9ADCA;
--glass:rgba(255,255,255,.55);--sheen:rgba(255,255,255,.45);--edge:rgba(255,255,255,.85);--tile:rgba(255,255,255,.55);
--fg:#101322;--sub:#585F72;--a1:#1F6FEB;--a2:#6A5BF5;--sbg:rgba(255,255,255,.55);--sfg:#1F6FEB}
@media (prefers-color-scheme:dark){:root{--base:#050304;--g1:#A86930;--g2:#5D6AA8;--g3:#8A5A38;--g4:#3A4578;
--glass:rgba(255,255,255,.07);--sheen:rgba(255,255,255,.07);--edge:rgba(255,255,255,.14);--tile:rgba(255,255,255,.07);
--fg:#F3F5FA;--sub:#A3A9B8;--a1:#5B95FF;--a2:#8E7BFF;--sbg:linear-gradient(rgba(255,255,255,.26),rgba(255,255,255,.12));--sfg:#9CC0FF}}
*{box-sizing:border-box}
html{background:var(--base)}
body{margin:0;color:var(--fg);font:16px -apple-system,"SF Pro Text",system-ui,sans-serif;-webkit-font-smoothing:antialiased;
padding:max(20px,env(safe-area-inset-top)) 16px max(40px,env(safe-area-inset-bottom));min-height:100vh}
body::before{content:"";position:fixed;inset:0;z-index:-1;background:
radial-gradient(circle 85vw at 10% 6%,var(--g1) 0%,transparent 100%),
radial-gradient(circle 80vw at 100% 32%,var(--g2) 0%,transparent 100%),
radial-gradient(circle 90vw at 5% 68%,var(--g3) 0%,transparent 100%),
radial-gradient(circle 85vw at 90% 98%,var(--g4) 0%,transparent 100%),var(--base)}
h1{font-size:30px;font-weight:700;letter-spacing:-.01em;margin:4px 4px 14px}h2{font-size:17px;font-weight:600;margin:0}
.glass{background:linear-gradient(var(--sheen),transparent 55%),var(--glass);border:1px solid var(--edge);border-radius:22px}
.up{-webkit-backdrop-filter:blur(24px) saturate(160%);backdrop-filter:blur(24px) saturate(160%);padding:18px;margin-top:20px}
.row{display:flex;justify-content:space-between;align-items:center;margin:8px 4px 12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}
.card{overflow:hidden;display:flex;flex-direction:column}
.thumb{position:relative;display:block;aspect-ratio:1;background:var(--tile);margin:8px 8px 0;border-radius:14px;overflow:hidden}
.thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;z-index:1}
.ph{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--sub);font-weight:600;text-transform:uppercase}
.badge{position:absolute;z-index:2;left:8px;bottom:8px;background:rgba(0,0,0,.55);color:#fff;border-radius:99px;padding:2px 8px;font-size:12px}
.meta{padding:8px 12px 0}.name{font-size:13px;word-break:break-all;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.sub{font-size:13px;color:var(--sub);margin:4px 0 0;line-height:1.4}
.btn{display:inline-block;background:linear-gradient(135deg,var(--a1),var(--a2));color:#fff;border:0;border-radius:999px;padding:10px 18px;
font:600 15px -apple-system,system-ui,sans-serif;text-decoration:none;text-align:center;cursor:pointer}
.btn.soft{background:var(--sbg);color:var(--sfg);border:1px solid var(--edge);-webkit-backdrop-filter:blur(20px) saturate(160%);backdrop-filter:blur(20px) saturate(160%)}
@media (prefers-color-scheme:dark){.btn.soft{border-color:rgba(255,255,255,.2);box-shadow:0 2px 12px rgba(0,0,0,.25)}}
.hint{color:var(--sub);font-size:14px;margin:0 4px 12px}
.card .meta:last-child{padding-bottom:12px}
.up .btn{margin-top:14px}.act{padding:10px 10px 12px}.act .btn{display:block}
.bulk{display:flex;flex-wrap:wrap;gap:8px;margin:0 4px 14px}.btn[disabled]{opacity:.45}
.lk{position:absolute;z-index:3;right:7px;top:7px;width:32px;height:32px;border-radius:50%;background:rgba(0,0,0,.5);color:#FF3B5C;
display:none;align-items:center;justify-content:center}
.lk svg{width:19px;height:19px}.lk path{fill:currentColor}
.rowb{display:flex;gap:18px}.lnk{background:none;border:0;padding:4px 0;color:var(--a1);font:600 16px -apple-system,system-ui,sans-serif;cursor:pointer}
.lnk[hidden]{display:none}
.thumb img{transition:filter .5s ease,transform .2s ease}
body.selecting .thumb::after{content:"";position:absolute;z-index:4;left:7px;top:7px;width:24px;height:24px;border-radius:50%;
border:2px solid #fff;background:rgba(0,0,0,.25);box-shadow:0 1px 3px rgba(0,0,0,.35);box-sizing:border-box}
body.selecting .card.picked .thumb::after{background:var(--a1) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 12.5l4.5 4.5L19 7.5' fill='none' stroke='white' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") center/15px no-repeat}
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
.cb.lkb{background:rgba(255,255,255,.92);color:#FF3B5C}
.sec{display:none}body.secure .sec{display:inline}body.secure .btn.sec,body.secure .cb.sec{display:inline-block}body.secure .insec{display:none}
.pill{max-width:calc(100vw - 24px)}
.pill .pt{align-self:center;padding:0 6px 0 12px;font:600 15px -apple-system,system-ui;font-variant-numeric:tabular-nums;
min-width:0;overflow:hidden;text-overflow:ellipsis}.pill .pt:empty{display:none}
.pill .cb.x{width:38px;padding:0;background:rgba(255,255,255,.16)}
.pill .cb:disabled{background:rgba(255,255,255,.14);color:rgba(255,255,255,.5)}
.card.liked .lk{display:flex}
html.lock,html.lock body{overflow:hidden}
.feed{position:fixed;inset:0;z-index:20;background:#000;color:#fff}.feed[hidden]{display:none}
.fs{position:absolute;inset:0;overflow-y:scroll;scroll-snap-type:y mandatory;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;scrollbar-width:none}
.fs::-webkit-scrollbar{display:none}
.sl{height:100%;scroll-snap-align:start;scroll-snap-stop:always;display:flex;align-items:center;justify-content:center;overflow:hidden}
.sl img,.sl video{max-width:100%;max-height:100%;object-fit:contain;-webkit-touch-callout:default}
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
.cb{white-space:nowrap;flex:none;height:34px;padding:0 14px;border-radius:999px;border:0;background:linear-gradient(135deg,#5B95FF,#8E7BFF);color:#fff;font:600 14px -apple-system,system-ui}
.cb[hidden]{display:none}.fb:not(:has(.cb:not([hidden]))){padding-right:16px}
.cb.rv{background:rgba(255,255,255,.92);color:#1F6FEB;margin-left:-6px}
.cb:disabled{background:rgba(255,255,255,.14);color:rgba(255,255,255,.55)}
.sl img{transition:filter .6s ease}.sl.blur img{filter:blur(22px) brightness(.85)}
.side{position:absolute;z-index:2;right:12px;bottom:calc(env(safe-area-inset-bottom) + 110px);display:flex;flex-direction:column;gap:16px}
.sb{width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;padding:0;
border:1px solid rgba(255,255,255,.2);background:rgba(18,18,22,.5);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);
box-shadow:0 2px 10px rgba(0,0,0,.25)}
.sb svg{width:26px;height:26px;filter:drop-shadow(0 1px 2px rgba(0,0,0,.4))}
#bl.on{color:#FF3B5C}#bl.on path{fill:currentColor}
#bl.pop{animation:pop .35s ease}@keyframes pop{40%{transform:scale(1.3)}100%{transform:scale(1)}}
.toast{position:absolute;z-index:2;left:16px;right:80px;bottom:calc(env(safe-area-inset-bottom) + 84px);padding:12px 14px;border-radius:16px;
background:rgba(20,20,22,.72);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);font-size:14px;line-height:1.4}
.toast[hidden]{display:none}
.tip{color:var(--sub);font-size:13px;line-height:1.55;margin:16px 4px 0}.empty{color:var(--sub);margin:4px}
.up input{display:none}.bar{height:6px;background:var(--tile);border-radius:3px;margin-top:14px;overflow:hidden;display:none}
.bar div{height:100%;width:0;background:linear-gradient(90deg,var(--a1),var(--a2))}#ustat{font-size:13px;color:var(--sub);margin-top:8px}
""".trimIndent()
    }
}
