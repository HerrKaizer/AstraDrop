package dev.tapdrop

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * HTTPS for a phone on a home network, with nothing to install on the iPhone:
 * local-ip.sh publishes a real Let's Encrypt certificate for *.local-ip.sh, and
 * its DNS maps a name like 192-168-0-103.local-ip.sh to that IP. Safari then sees
 * an ordinary secure site, which unlocks the share sheet's «Save N Images».
 *
 * The certificate's key is public by design, so this only protects against
 * nothing more than casual snooping; it's meant for a home network. Both files
 * are cached and re-downloaded when the certificate is within 20 days of expiry.
 */
object TlsCert {
    private const val BASE = "https://local-ip.sh"
    private const val RENEW_MS = 20L * 24 * 60 * 60 * 1000

    /** Host name that resolves to [ip] (192.168.0.103 -> 192-168-0-103.local-ip.sh). */
    fun hostFor(ip: String) = ip.replace('.', '-') + ".local-ip.sh"

    /** Builds the server's TLS context, downloading the certificate if needed. Call off the main thread. */
    fun context(ctx: Context): SSLContext? = try {
        val dir = File(ctx.filesDir, "tls").apply { mkdirs() }
        val pem = File(dir, "server.pem")
        val key = File(dir, "server.key")
        var chain = if (pem.exists() && key.exists()) certs(pem.readBytes()) else emptyList()
        if (chain.isEmpty() || chain[0].notAfter.time - System.currentTimeMillis() < RENEW_MS) {
            pem.writeBytes(download("$BASE/server.pem"))
            key.writeBytes(download("$BASE/server.key"))
            chain = certs(pem.readBytes())
        }
        val pk = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8(key.readText())))
        val pw = CharArray(0)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", pk, pw, chain.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }
        Log.i("TapDrop", "tls: certificate for ${chain[0].subjectX500Principal.name}, valid until ${chain[0].notAfter}")
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    } catch (t: Throwable) {
        Log.w("TapDrop", "tls: not available", t)
        null
    }

    private fun certs(pem: ByteArray): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem)).map { it as X509Certificate }

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("$url -> ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** "BEGIN RSA PRIVATE KEY" (PKCS#1) or "BEGIN PRIVATE KEY" (PKCS#8) PEM -> PKCS#8 DER. */
    private fun pkcs8(pem: String): ByteArray {
        val isPkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
        val der = Base64.decode(pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""), Base64.DEFAULT)
        if (!isPkcs1) return der
        // PrivateKeyInfo ::= SEQUENCE { version 0, AlgorithmIdentifier rsaEncryption, OCTET STRING pkcs1 }
        val algId = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00)
        val body = byteArrayOf(0x02, 0x01, 0x00) + algId + tlv(0x04, der)
        return tlv(0x30, body)
    }

    private fun tlv(tag: Int, v: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val n = v.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x100 -> { o.write(0x81); o.write(n) }
            n < 0x10000 -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(v)
        return o.toByteArray()
    }
}
