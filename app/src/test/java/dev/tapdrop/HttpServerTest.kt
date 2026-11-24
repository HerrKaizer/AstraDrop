package dev.tapdrop

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket

class HttpServerTest {
    private lateinit var srv: HttpServer
    private var port = 0

    @Before
    fun setUp() {
        ShareState.items.clear()
        ShareState.running = true
        srv = HttpServer(ContextWrapper(null)) {}
        port = srv.start(0)
    }

    @After
    fun tearDown() = srv.stop()

    private fun exchange(raw: String): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(raw.toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun twoRequestsShareOneConnection() {
        val t = ShareState.token
        val resp = exchange(
            "GET /$t/ HTTP/1.1\r\nHost: x\r\n\r\n" +
                "GET /$t/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
        )
        assertEquals(2, Regex("HTTP/1.1 200 OK").findAll(resp).count())
        assertTrue("first response keeps the connection open", resp.contains("Connection: keep-alive"))
        assertTrue("last response closes it", resp.contains("Connection: close"))
        assertTrue("page is in Russian", resp.contains("Отправить на Android"))
        assertTrue(resp.contains("<html lang=\"ru\">"))
        // keep a copy of the page so it can be looked at
        File("build/tapdrop-index.html").writeText(resp.substringAfter("\r\n\r\n").substringBefore("HTTP/1.1 200 OK"))
    }

    @Test
    fun wrongTokenGetsRussianExpiredPage() {
        val resp = exchange("GET /nope/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(resp.startsWith("HTTP/1.1 404"))
        assertTrue(resp.contains("Ссылка устарела"))
    }

    @Test
    fun zipWithNothingSharedSays404() {
        val resp = exchange("GET /${ShareState.token}/z HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(resp.startsWith("HTTP/1.1 404"))
        assertTrue(resp.contains("Нет файлов"))
    }

    @Test
    fun unreadBodyClosesConnection() {
        val resp = exchange("POST /nope/ HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello")
        assertTrue(resp.contains("Connection: close"))
    }
}
