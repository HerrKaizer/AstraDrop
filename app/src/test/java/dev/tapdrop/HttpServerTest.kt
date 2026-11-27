package dev.tapdrop

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.util.zip.GZIPInputStream

class HttpServerTest {
    private lateinit var srv: HttpServer
    private var port = 0
    private val texts = mutableListOf<String>()
    private var cookie = ""

    @Before
    fun setUp() {
        ShareState.items.clear()
        ShareState.texts.clear()
        Devices.clear()
        ShareState.running = true
        srv = HttpServer(ContextWrapper(null), onText = { t, _ -> texts += t }) { _, _ -> }
        port = srv.start(0)
        cookie = join()
    }

    @After
    fun tearDown() = srv.stop()

    private fun exchangeRaw(raw: ByteArray): ByteArray = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(raw)
        s.getInputStream().readBytes()
    }

    private fun exchangeRaw(raw: String): ByteArray = exchangeRaw(raw.toByteArray())
    private fun exchange(raw: String): String = exchangeRaw(raw).toString(Charsets.UTF_8)

    /** Opens the link with its one-time ticket, as a tap or scan does; returns the cookie header line to send. */
    private fun join(ticket: String = Devices.ticket): String {
        val r = exchange("GET /${ShareState.token}/?k=$ticket HTTP/1.1\r\nHost: x\r\nUser-Agent: iPhone\r\nConnection: close\r\n\r\n")
        assertTrue(r, r.startsWith("HTTP/1.1 302"))
        val c = Regex("Set-Cookie: ([^;\r\n]+)").find(r)?.groupValues?.get(1) ?: return ""
        return "Cookie: $c\r\n"
    }

    @Test
    fun twoRequestsShareOneConnection() {
        val t = ShareState.token
        val resp = exchange(
            "GET /$t/ HTTP/1.1\r\nHost: x\r\n$cookie\r\n" +
                "GET /$t/ HTTP/1.1\r\nHost: x\r\n${cookie}Connection: close\r\n\r\n"
        )
        assertEquals(2, Regex("HTTP/1.1 200 OK").findAll(resp).count())
        assertTrue("first response keeps the connection open", resp.contains("Connection: keep-alive"))
        assertTrue("last response closes it", resp.contains("Connection: close"))
        assertTrue("page text comes from the translations (English without them)", resp.contains("Send to Android"))
        assertTrue(resp.contains("<html lang=\"en\">"))
        assertTrue(resp.contains("<title>AstraDrop</title>"))
        assertTrue(resp.contains("<h1>AstraDrop</h1>"))
        // keep a copy of the page so it can be looked at
        File("build/tapdrop-index.html").writeText(resp.substringAfter("\r\n\r\n").substringBefore("HTTP/1.1 200 OK"))
    }

    @Test
    fun wrongTokenGetsExpiredPage() {
        val resp = exchange("GET /nope/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(resp.startsWith("HTTP/1.1 404"))
        assertTrue(resp.contains("This link has expired"))
        assertTrue(resp.contains("<title>AstraDrop</title>"))
    }

    @Test
    fun withoutTheCookieNothingOpens() {
        val t = ShareState.token
        val page = exchange("GET /$t/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(page.startsWith("HTTP/1.1 403"))
        assertTrue(page.contains("This link has expired"))
        val ids = exchange("GET /$t/ids HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(ids.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun aTicketWorksOnce() {
        val used = Devices.ticket
        val c = join(used)
        assertTrue("a fresh ticket lets a second phone in", c.isNotEmpty())
        assertTrue("the ticket changes once used", Devices.ticket != used)
        val again = exchange("GET /${ShareState.token}/?k=$used HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue("a used ticket opens nothing", again.startsWith("HTTP/1.1 403"))
        assertEquals(2, Devices.all().size)
    }

    @Test
    fun removedDeviceIsLockedOut() {
        Devices.remove(Devices.all().first().id)
        val r = exchange("GET /${ShareState.token}/ids HTTP/1.1\r\nHost: x\r\n${cookie}Connection: close\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun zipWithNothingSharedSays404() {
        val resp = exchange("GET /${ShareState.token}/z HTTP/1.1\r\nHost: x\r\n${cookie}Connection: close\r\n\r\n")
        assertTrue(resp.startsWith("HTTP/1.1 404"))
        assertTrue(resp.contains("No files to download"))
    }

    @Test
    fun textFromTheIphoneArrives() {
        val body = "Привет https://example.com".toByteArray(Charsets.UTF_8)
        val head = "POST /${ShareState.token}/x HTTP/1.1\r\nHost: x\r\n${cookie}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val r = exchangeRaw(head.toByteArray() + body).toString(Charsets.UTF_8)
        assertTrue(r, r.startsWith("HTTP/1.1 200"))
        assertEquals(listOf("Привет https://example.com"), texts)
    }

    @Test
    fun textForTheIphoneIsOnThePage() {
        ShareState.addText("see https://example.com/a?b=1 <now>")
        val r = exchange("GET /${ShareState.token}/ HTTP/1.1\r\nHost: x\r\n${cookie}Connection: close\r\n\r\n")
        assertTrue(r.contains("<a href=\"https://example.com/a?b=1\""))
        assertTrue("text is escaped", r.contains("&lt;now&gt;") && !r.contains("<now>"))
        assertTrue(r.contains("onclick=\"cp(this)\""))
    }

    @Test
    fun unreadBodyClosesConnection() {
        val resp = exchange("POST /nope/ HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello")
        assertTrue(resp.contains("Connection: close"))
    }

    @Test
    fun gzipCompressesIndexPage() {
        val t = ShareState.token
        val plain = exchange("GET /$t/ HTTP/1.1\r\nHost: x\r\n${cookie}Connection: close\r\n\r\n")
        val raw = exchangeRaw("GET /$t/ HTTP/1.1\r\nHost: x\r\n${cookie}Accept-Encoding: gzip, deflate\r\nConnection: close\r\n\r\n")
        val sep = raw.indices.first { i ->
            i + 3 < raw.size && raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
        }
        val hdr = raw.copyOfRange(0, sep).toString(Charsets.UTF_8)
        val body = raw.copyOfRange(sep + 4, raw.size)
        val plainBody = plain.substringAfter("\r\n\r\n").toByteArray(Charsets.UTF_8)
        assertTrue(hdr.contains("Content-Encoding: gzip"))
        assertTrue("gzip body (${body.size}) should be < 55% of plain (${plainBody.size})", body.size * 100 < plainBody.size * 55)
        val decoded = GZIPInputStream(ByteArrayInputStream(body)).readBytes().toString(Charsets.UTF_8)
        assertTrue(decoded.contains("<title>AstraDrop</title>"))
        assertTrue(decoded.contains("<h1>AstraDrop</h1>"))
        assertTrue("no wallpaper any more", !decoded.contains("/w/d"))
    }

    @Test
    fun wallpaperRouteIsGone() {
        val r = exchange("GET /w/d HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(!r.startsWith("HTTP/1.1 200") && !r.startsWith("HTTP/1.1 304"))
    }
}
