package dev.tapdrop.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * App side of AstraDrop's SolEngine (see [AstraDaemon]): pairs once with this phone's Wireless
 * debugging, starts the engine over it, then talks to it on 127.0.0.1. Standard Android settings and
 * services only, so it works the same on any phone with Wireless debugging (Android 11+).
 * Same design as SolCube's engine but fully separate (own key, port, process name), so both apps can
 * have one running at the same time.
 */
object Engine {
    private const val TAG = "AstraDrop.adb"
    private const val PREF = "engine"
    private val lock = Any()
    @Volatile private var manager: Manager? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isPaired(ctx: Context) = prefs(ctx).getBoolean("paired", false)
    private fun setPaired(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("paired", v).commit()

    fun secret(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("secret", null)?.let { return it }
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val s = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("secret", s).commit()
        return s
    }

    fun isOnWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun devOptionsOn(ctx: Context) = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

    fun wirelessDebuggingOn(ctx: Context) = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    private fun hasWss(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Android forgets a pairing after 7 days without a connection unless this is 0. */
    private fun keepPairing(ctx: Context) {
        if (hasWss(ctx)) runCatching { Settings.Global.putLong(ctx.contentResolver, "adb_allowed_connection_time", 0L) }
    }

    // ------------------------------------------------------------------ talking to the engine

    /** Sends one command; the engine's reply without "ok " (null = no engine / refused). Off the main thread. */
    fun cmd(ctx: Context, command: String, timeoutMs: Int = 3000): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", AstraDaemon.PORT), 300)
            s.soTimeout = timeoutMs
            val r = s.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val nonce = r.readLine() ?: return null
            val mac = AstraDaemon.mac(secret(ctx), "$nonce $command")
            s.getOutputStream().write("$command $mac\n".toByteArray(StandardCharsets.UTF_8))
            val reply = r.readLine() ?: return null
            if (reply == "ok") "" else if (reply.startsWith("ok ")) reply.substring(3) else null
        }
    }.getOrNull()

    private fun versionOf(ctx: Context) = AstraDaemon.versionOf(ctx.applicationInfo.sourceDir)

    /** True when an engine from THIS install answers. */
    fun running(ctx: Context): Boolean = cmd(ctx, "PING", 1500) == versionOf(ctx).toString()

    // ------------------------------------------------------------------ starting

    enum class Start { OK, NOT_PAIRED, NO_WIFI, DEV_OFF, WD_OFF, FAILED }

    /**
     * Starts the engine if it isn't running (blocking, off the main thread):
     * Wireless debugging on → connect with our saved key and launch; off → switch it on (allowed
     * once the engine granted us WRITE_SECURE_SETTINGS), connect, leave it on. Never switches it off.
     */
    fun start(ctx: Context): Start {
        val app = ctx.applicationContext
        if (running(app)) return Start.OK
        if (!isPaired(app)) return Start.NOT_PAIRED
        if (!isOnWifi(app)) return Start.NO_WIFI
        if (!devOptionsOn(app)) return Start.DEV_OFF
        if (!wirelessDebuggingOn(app)) {
            if (!hasWss(app)) return Start.WD_OFF
            runCatching {
                Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
            }
            Thread.sleep(1200)
            if (!wirelessDebuggingOn(app)) return Start.WD_OFF
        }
        keepPairing(app)
        var r = launch(app, -1)
        if (r.isFailure && !isAuthFailure(r.exceptionOrNull())) { Thread.sleep(1500); r = launch(app, -1) }   // mDNS can lag
        if (r.isSuccess) return Start.OK
        if (isAuthFailure(r.exceptionOrNull())) { setPaired(app, false); return Start.NOT_PAIRED }
        return Start.FAILED
    }

    /** Pairs with this phone's own pairing endpoint (127.0.0.1:[port]) using the 6-digit [code]. */
    fun pair(ctx: Context, port: Int, code: String): Result<Unit> = synchronized(lock) {
        runCatching {
            val m = manager(ctx)
            m.setTimeout(12, TimeUnit.SECONDS)
            if (!m.pair(LocalAdb.HOST, port, code.trim())) error("rejected")
            setPaired(ctx, true)
            Log.i(TAG, "paired")
            Unit
        }
    }

    /** Connects to Wireless debugging and starts the engine from our APK; waits until it answers. */
    fun launch(ctx: Context, port: Int): Result<Unit> = synchronized(lock) {
        val app = ctx.applicationContext
        runCatching {
            if (running(app)) return@runCatching
            val m = manager(app)
            m.setTimeout(10, TimeUnit.SECONDS)
            if (m.isConnected) runCatching { m.disconnect() }
            val p = if (port > 0 && LocalAdb.isPortInUse(port)) port else LocalAdb.findPort(app, LocalAdb.TLS_CONNECT, 8000)
            if (p <= 0 || !m.connect(LocalAdb.HOST, p)) error("no Wireless debugging")
            setPaired(app, true)
            try {
                val apk = app.applicationInfo.sourceDir
                val cmd = "CLASSPATH='$apk' setsid app_process /system/bin --nice-name=${AstraDaemon.NAME} " +
                    "${AstraDaemon::class.java.name} ${secret(app)} '$apk' </dev/null >/dev/null 2>&1 &\nexit\n"
                m.openStream("shell:").use { st ->
                    st.write(cmd.toByteArray(StandardCharsets.UTF_8), 0, cmd.length)
                    st.flush()
                    val buf = ByteArray(512)
                    val until = System.currentTimeMillis() + 1500
                    while (!st.isClosed && System.currentTimeMillis() < until) {
                        if (st.available() > 0) { if (st.read(buf, 0, buf.size) < 0) break } else Thread.sleep(50)
                    }
                }
            } finally {
                runCatching { m.disconnect() }
            }
            val until = System.currentTimeMillis() + 6000
            while (!running(app) && System.currentTimeMillis() < until) Thread.sleep(150)
            if (!running(app)) error("engine didn't answer")
            Thread.sleep(300)   // its pm grant runs right after it starts listening
            keepPairing(app)
            Unit
        }
    }

    private fun isAuthFailure(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            if (e is javax.net.ssl.SSLHandshakeException) return true
            val m = e.message ?: ""
            if (m.contains("CERTIFICATE", true) || m.contains("not authorized", true)) return true
            e = e.cause
        }
        return false
    }

    private fun manager(ctx: Context): Manager = synchronized(lock) {
        manager ?: Manager(ctx.applicationContext).also { manager = it }
    }

    // ------------------------------------------------------------------ identity (own RSA key + cert)

    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val (k, c) = identity(ctx)
            key = k; cert = c
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "AstraDrop"
    }

    private fun identity(ctx: Context): Pair<PrivateKey, Certificate> {
        val dir = ctx.noBackupFilesDir
        val keyFile = File(dir, "adb_key.der")
        val certFile = File(dir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                return k to c
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val der = selfSigned(kp.public.encoded, kp.private)
        val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        runCatching { keyFile.writeBytes(kp.private.encoded); certFile.writeBytes(der) }
        return kp.private to c
    }

    /** Minimal DER encoder for a self-signed SHA256withRSA X.509 v3 certificate (CN=AstraDrop). */
    private fun selfSigned(spki: ByteArray, priv: PrivateKey): ByteArray {
        val serial = ByteArray(8).also { SecureRandom().nextBytes(it) }
        serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()
        val alg = tlv(0x30, tlv(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) + byteArrayOf(0x05, 0x00))
        val name = tlv(0x30, tlv(0x31, tlv(0x30, tlv(0x06, byteArrayOf(0x55, 0x04, 0x03)) + tlv(0x0C, "AstraDrop".toByteArray()))))
        val now = System.currentTimeMillis() - 86_400_000L
        val validity = tlv(0x30, time(now) + time(now + 25L * 365 * 86_400_000L))
        val tbs = tlv(0x30, tlv(0xA0, tlv(0x02, byteArrayOf(0x02))) + tlv(0x02, serial) + alg + name + validity + name + spki)
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(priv); update(tbs); sign() }
        return tlv(0x30, tbs + alg + tlv(0x03, byteArrayOf(0) + sig))
    }

    private fun time(ms: Long): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, fmt.format(Date(ms)).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n <= 0xFF -> { out.write(0x81); out.write(n) }
            n <= 0xFFFF -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write((n ushr 16) and 0xFF); out.write((n ushr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
