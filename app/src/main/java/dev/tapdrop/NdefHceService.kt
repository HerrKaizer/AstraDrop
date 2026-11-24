package dev.tapdrop

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Emulates an NFC Forum Type 4 Tag whose NDEF message is a single URI record
 * holding the current share link. iPhones read such tags in the background and
 * show a banner that opens the link in Safari.
 *
 * APDU flow from the reader:
 *   SELECT NDEF Tag Application (AID D2760000850101)
 *   SELECT Capability Container file E103, READ BINARY
 *   SELECT NDEF file E104, READ BINARY (2-byte length, then the message)
 */
class NdefHceService : HostApduService() {
    private var appSelected = false
    private var selectedFile: ByteArray? = null
    private var isNdefFile = false
    private var tappedThisSession = false

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        return when (ins) {
            0xA4 -> select(apdu, p1)
            0xB0 -> readBinary(apdu, (p1 shl 8) or p2)
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray, p1: Int): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)

        if (p1 == 0x04) { // select by AID
            isNdefFile = false
            tappedThisSession = false
            return if (data.contentEquals(NDEF_AID) && ShareState.url() != null) {
                appSelected = true
                selectedFile = null
                SW_OK
            } else {
                appSelected = false
                SW_FILE_NOT_FOUND
            }
        }
        if (p1 == 0x00 && appSelected && lc == 2) { // select by file id
            val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            isNdefFile = fid == 0xE104
            selectedFile = when (fid) {
                0xE103 -> CC_FILE
                0xE104 -> ndefFile()
                else -> null
            }
            return if (selectedFile != null) SW_OK else SW_FILE_NOT_FOUND
        }
        return SW_FILE_NOT_FOUND
    }

    private fun readBinary(apdu: ByteArray, offset: Int): ByteArray {
        val file = selectedFile ?: return SW_FILE_NOT_FOUND
        var le = if (apdu.size >= 5) apdu[4].toInt() and 0xFF else 0
        if (le == 0) le = 256
        if (offset > file.size) return SW_WRONG_P1P2
        val end = minOf(file.size, offset + le)
        if (isNdefFile && !tappedThisSession && file.size > 2 && end >= file.size) {
            tappedThisSession = true
            ShareState.notifyTapped()
        }
        return file.copyOfRange(offset, end) + SW_OK
    }

    override fun onDeactivated(reason: Int) {
        appSelected = false
        selectedFile = null
        isNdefFile = false
        tappedThisSession = false
    }

    private fun ndefFile(): ByteArray? {
        val url = ShareState.url() ?: return null
        return ndefFileFor(url)
    }

    companion object {
        private val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val SW_WRONG_P1P2 = byteArrayOf(0x6B, 0x00)
        private val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)

        private const val MAX_NDEF = 0x0400

        /** Capability Container: version 2.0, read-only NDEF file E104 of up to 1 KB. */
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,             // CCLEN
            0x20,                   // mapping version 2.0
            0x00, 0x3B,             // MLe: max bytes per READ BINARY
            0x00, 0x34,             // MLc
            0x04, 0x06,             // NDEF File Control TLV
            0xE1.toByte(), 0x04,    // file id
            (MAX_NDEF shr 8).toByte(), (MAX_NDEF and 0xFF).toByte(),
            0x00,                   // read access: free
            0xFF.toByte(),          // write access: none
        )

        /** NDEF file = 2-byte NLEN + one short URI record. */
        fun ndefFileFor(url: String): ByteArray {
            val prefixes = listOf("http://www." to 0x01, "https://www." to 0x02, "http://" to 0x03, "https://" to 0x04)
            val (prefix, code) = prefixes.firstOrNull { url.startsWith(it.first) } ?: ("" to 0x00)
            val payload = byteArrayOf(code.toByte()) + url.removePrefix(prefix).toByteArray(Charsets.UTF_8)
            require(payload.size < 256) { "URL too long for a short record" }
            // MB=1 ME=1 SR=1 TNF=well-known(1)  -> 0xD1, type length 1, payload length, type 'U'
            val record = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x55) + payload
            return byteArrayOf((record.size shr 8).toByte(), (record.size and 0xFF).toByte()) + record
        }
    }
}
