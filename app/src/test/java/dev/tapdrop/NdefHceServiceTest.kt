package dev.tapdrop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plays the APDU sequence an NFC Forum Type 4 reader (e.g. an iPhone) sends, and decodes the URL. */
class NdefHceServiceTest {
    private val ok = byteArrayOf(0x90.toByte(), 0x00)

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun readerGetsCurrentUrl() {
        ShareState.host = "192.168.43.1"
        ShareState.port = 8765
        ShareState.running = true
        val url = ShareState.url()!!
        val svc = NdefHceService()

        assertArrayEquals(ok, svc.processCommandApdu(hex("00A4040007D276000085010100"), null))
        assertArrayEquals(ok, svc.processCommandApdu(hex("00A4000C02E103"), null))
        val cc = svc.processCommandApdu(hex("00B000000F"), null)
        assertEquals(17, cc.size)
        assertEquals(0xE1.toByte(), cc[9]); assertEquals(0x04.toByte(), cc[10])

        assertArrayEquals(ok, svc.processCommandApdu(hex("00A4000C02E104"), null))
        val nlenResp = svc.processCommandApdu(hex("00B0000002"), null)
        val nlen = ((nlenResp[0].toInt() and 0xFF) shl 8) or (nlenResp[1].toInt() and 0xFF)

        // read the message in 59-byte chunks like a real reader limited by MLe
        val msg = mutableListOf<Byte>()
        var off = 2
        while (msg.size < nlen) {
            val n = minOf(0x3B, nlen - msg.size)
            val r = svc.processCommandApdu(byteArrayOf(0x00, 0xB0.toByte(), (off shr 8).toByte(), off.toByte(), n.toByte()), null)
            assertArrayEquals(ok, r.copyOfRange(r.size - 2, r.size))
            msg += r.copyOfRange(0, r.size - 2).toList()
            off += n
        }
        val m = msg.toByteArray()
        assertEquals(0xD1.toByte(), m[0])          // MB ME SR, TNF well-known
        assertEquals(0x55.toByte(), m[3])          // type 'U'
        assertEquals(0x03.toByte(), m[4])          // "http://" prefix code
        val decoded = "http://" + String(m.copyOfRange(5, m.size), Charsets.UTF_8)
        assertEquals(url, decoded)
        assertEquals(m[2].toInt() and 0xFF, m.size - 4)
    }

    @Test
    fun refusesWhenNotSharing() {
        ShareState.running = false
        val svc = NdefHceService()
        val r = svc.processCommandApdu(hex("00A4040007D276000085010100"), null)
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), r)
    }

    @Test
    fun tapCountIncrementsOncePerReadAndAfterDeactivate() {
        ShareState.host = "192.168.43.1"
        ShareState.port = 8765
        ShareState.running = true
        ShareState.resetTapDebounce()
        val startCount = ShareState.tapCount
        val svc = NdefHceService()

        fun readFullSession() {
            assertArrayEquals(ok, svc.processCommandApdu(hex("00A4040007D276000085010100"), null))
            assertArrayEquals(ok, svc.processCommandApdu(hex("00A4000C02E104"), null))
            val nlenResp = svc.processCommandApdu(hex("00B0000002"), null)
            val nlen = ((nlenResp[0].toInt() and 0xFF) shl 8) or (nlenResp[1].toInt() and 0xFF)
            var off = 2
            var read = 0
            while (read < nlen) {
                val n = minOf(0x3B, nlen - read)
                val r = svc.processCommandApdu(byteArrayOf(0x00, 0xB0.toByte(), (off shr 8).toByte(), off.toByte(), n.toByte()), null)
                assertArrayEquals(ok, r.copyOfRange(r.size - 2, r.size))
                read += n
                off += n
            }
        }

        readFullSession()
        assertEquals(startCount + 1, ShareState.tapCount)

        // Re-reading E104 in the same session must not double-fire
        svc.processCommandApdu(hex("00B0000080"), null)
        assertEquals(startCount + 1, ShareState.tapCount)

        // New session after deactivation + debounce window fires again
        svc.onDeactivated(0)
        ShareState.resetTapDebounce()
        readFullSession()
        assertEquals(startCount + 2, ShareState.tapCount)
    }
}
