package dev.tapdrop

import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * A ZIP of files stored as they are (photos and videos are already compressed), laid out so its
 * exact size is known before a byte is sent and any part of it can be produced on its own. So
 * the download shows real progress, and an interrupted one can carry on where it stopped
 * (HTTP Range). Each file's checksum is worked out just before its header goes out and kept.
 *
 * Only for archives under 4 GB with up to 65535 files (plain ZIP limits); bigger ones are
 * streamed the old way.
 */
class StoredZip(private val entries: List<Entry>, timeMs: Long) {
    class Entry(val key: String, val name: String, val size: Long, val open: () -> InputStream?)

    private val names = entries.map { it.name.toByteArray(Charsets.UTF_8) }
    private val localOffsets = LongArray(entries.size)
    private val cdOffset: Long
    private val cdSize: Long
    val total: Long
    private val dosTime: Int
    private val dosDate: Int

    init {
        var off = 0L
        for (i in entries.indices) {
            localOffsets[i] = off
            off += 30 + names[i].size + entries[i].size
        }
        cdOffset = off
        cdSize = names.sumOf { 46L + it.size }
        total = cdOffset + cdSize + 22
        val c = Calendar.getInstance().apply { timeInMillis = timeMs }
        dosTime = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        dosDate = ((c.get(Calendar.YEAR) - 1980).coerceAtLeast(0) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
    }

    /** Writes bytes [start]..[end] (inclusive) of the archive; returns how many went out. */
    fun write(out: OutputStream, start: Long, end: Long, onBytes: (Long) -> Unit = {}): Long {
        var sent = 0L
        fun emit(segStart: Long, bytes: ByteArray) {
            val a = maxOf(start, segStart)
            val b = minOf(end + 1, segStart + bytes.size)
            if (a >= b) return
            out.write(bytes, (a - segStart).toInt(), (b - a).toInt())
            sent += b - a
            onBytes(sent)
        }
        for (i in entries.indices) {
            val e = entries[i]
            val hStart = localOffsets[i]
            val dStart = hStart + 30 + names[i].size
            val dEnd = dStart + e.size
            if (dEnd <= start && hStart + 30 + names[i].size <= start) continue
            if (hStart > end) break
            if (hStart + 30 + names[i].size > start) emit(hStart, localHeader(i, crc(e)))
            val a = maxOf(start, dStart)
            val b = minOf(end + 1, dEnd)
            if (a < b) {
                val ins = e.open() ?: throw java.io.FileNotFoundException(e.name)
                ins.use {
                    skipFully(it, a - dStart)
                    val buf = ByteArray(256 * 1024)
                    var left = b - a
                    while (left > 0) {
                        val n = it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw java.io.EOFException(e.name)
                        out.write(buf, 0, n)
                        left -= n
                        sent += n
                        onBytes(sent)
                    }
                }
            }
        }
        if (end >= cdOffset) {
            val cd = java.io.ByteArrayOutputStream()
            for (i in entries.indices) cd.write(centralHeader(i, crc(entries[i])))
            emit(cdOffset, cd.toByteArray())
            emit(cdOffset + cdSize, endRecord())
        }
        return sent
    }

    private fun crc(e: Entry): Long {
        val k = e.key + ":" + e.size
        crcCache[k]?.let { return it }
        val c = CRC32()
        e.open()?.use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
        } ?: throw java.io.FileNotFoundException(e.name)
        crcCache[k] = c.value
        return c.value
    }

    private fun localHeader(i: Int, crc: Long): ByteArray {
        val n = names[i]
        val b = Le(30 + n.size)
        b.u32(0x04034b50); b.u16(10); b.u16(FLAG_UTF8); b.u16(0); b.u16(dosTime); b.u16(dosDate)
        b.u32(crc); b.u32(entries[i].size); b.u32(entries[i].size); b.u16(n.size); b.u16(0); b.bytes(n)
        return b.a
    }

    private fun centralHeader(i: Int, crc: Long): ByteArray {
        val n = names[i]
        val b = Le(46 + n.size)
        b.u32(0x02014b50); b.u16(20); b.u16(10); b.u16(FLAG_UTF8); b.u16(0); b.u16(dosTime); b.u16(dosDate)
        b.u32(crc); b.u32(entries[i].size); b.u32(entries[i].size); b.u16(n.size); b.u16(0); b.u16(0)
        b.u16(0); b.u16(0); b.u32(0); b.u32(localOffsets[i]); b.bytes(n)
        return b.a
    }

    private fun endRecord(): ByteArray {
        val b = Le(22)
        b.u32(0x06054b50); b.u16(0); b.u16(0); b.u16(entries.size); b.u16(entries.size)
        b.u32(cdSize); b.u32(cdOffset); b.u16(0)
        return b.a
    }

    private class Le(size: Int) {
        val a = ByteArray(size)
        private var p = 0
        fun u16(v: Int) { a[p++] = v.toByte(); a[p++] = (v shr 8).toByte() }
        fun u32(v: Long) { for (k in 0 until 4) a[p++] = (v shr (8 * k)).toByte() }
        fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun bytes(b: ByteArray) { b.copyInto(a, p); p += b.size }
    }

    companion object {
        private const val FLAG_UTF8 = 0x0800
        private val crcCache = ConcurrentHashMap<String, Long>()

        /** Whether these sizes fit a plain (not ZIP64) archive. */
        fun fits(names: List<String>, sizes: List<Long>): Boolean {
            if (names.size > 0xFFFF || sizes.any { it < 0 }) return false
            val total = names.indices.sumOf { 76L + 2 * names[it].toByteArray(Charsets.UTF_8).size + sizes[it] } + 22
            return total < 0xFFFFFFFFL
        }

        private fun skipFully(ins: InputStream, n: Long) {
            var left = n
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val s = ins.skip(left)
                if (s > 0) { left -= s; continue }
                val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw java.io.EOFException()
                left -= r
            }
        }
    }
}
