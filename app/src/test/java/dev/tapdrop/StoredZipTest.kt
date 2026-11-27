package dev.tapdrop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

class StoredZipTest {
    private val files = listOf(
        "IMG_1.jpg" to ByteArray(300_000) { (it * 31).toByte() },
        "Фото 2.heic" to ByteArray(12_345) { (it * 7 + 3).toByte() },
        "empty.txt" to ByteArray(0),
    )

    private fun zip() = StoredZip(files.map { (n, b) -> StoredZip.Entry("test:$n", n, b.size.toLong()) { ByteArrayInputStream(b) } }, 1_700_000_000_000L)

    private fun bytes(z: StoredZip, start: Long = 0, end: Long = z.total - 1): ByteArray {
        val out = ByteArrayOutputStream()
        val n = z.write(out, start, end)
        assertEquals(end - start + 1, n)
        return out.toByteArray()
    }

    @Test
    fun sizeKnownUpFrontAndArchiveOpens() {
        val z = zip()
        val all = bytes(z)
        assertEquals(z.total, all.size.toLong())
        val f = File.createTempFile("astra", ".zip").apply { writeBytes(all); deleteOnExit() }
        ZipFile(f).use { zf ->
            for ((n, b) in files) {
                val e = zf.getEntry(n)
                assertEquals(b.size.toLong(), e.size)
                assertArrayEquals(b, zf.getInputStream(e).readBytes())
            }
        }
    }

    @Test
    fun anyPartMatchesTheWhole() {
        val z = zip()
        val all = bytes(z)
        for ((a, b) in listOf(0L to 10L, 25L to 300_100L, 150_000L to z.total - 1, z.total - 30 to z.total - 1, 300_040L to 300_060L)) {
            assertArrayEquals("bytes $a-$b", all.copyOfRange(a.toInt(), b.toInt() + 1), bytes(z, a, b))
        }
    }

    @Test
    fun tooBigFallsBack() {
        assertEquals(false, StoredZip.fits(listOf("a"), listOf(5_000_000_000L)))
        assertEquals(true, StoredZip.fits(listOf("a", "b"), listOf(1_000_000L, 2_000_000_000L)))
    }
}
