package dev.tapdrop

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

data class SharedItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    /** For a converted RAW: the original file this JPEG was made from. */
    val orig: Uri? = null,
    val origMime: String? = null,
    /** Already squeezed by [Compressor] (or found not to need it). */
    val compressed: Boolean = false,
)

data class ReceivedItem(val name: String, val uri: Uri, val mime: String)

data class LocalAddr(val iface: String, val ip: String, val kind: String) {
    val label: String get() = "$kind  $ip  ($iface)"
}

/** Process-wide state shared by the UI, the web server and the NFC tag service. */
object ShareState {
    val items = CopyOnWriteArrayList<SharedItem>()
    val received = CopyOnWriteArrayList<ReceivedItem>()

    @Volatile var token: String = newToken()
    @Volatile var port: Int = 0
    /** Port of the HTTPS copy of the server (0 when it isn't running). */
    @Volatile var tlsPort: Int = 0
    @Volatile var host: String? = null
    @Volatile var running: Boolean = false

    private val nextId = AtomicInteger(1)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val tapListeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private const val TAP_DEBOUNCE_NS = 1_500_000_000L
    private var lastTapNs = 0L
    @Volatile var tapCount: Int = 0
        private set

    /** The link handed to the iPhone, or null when nothing is being served. */
    fun url(): String? {
        val h = host ?: return null
        if (!running || port <= 0) return null
        return "http://$h:$port/$token/"
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { main.post { listeners.forEach { it() } } }

    fun addTapListener(l: () -> Unit) { tapListeners.add(l) }
    fun removeTapListener(l: () -> Unit) { tapListeners.remove(l) }

    fun notifyTapped(nowNs: Long = System.nanoTime()) {
        synchronized(this) {
            if (lastTapNs != 0L && nowNs - lastTapNs < TAP_DEBOUNCE_NS) return
            lastTapNs = nowNs
            tapCount++
        }
        main.post { tapListeners.forEach { it() } }
    }

    internal fun resetTapDebounce() {
        synchronized(this) { lastTapNs = 0L }
    }

    fun rotateToken() {
        val old = token
        var t = newToken()
        while (t == old) t = newToken()
        token = t
        notifyChanged()
    }

    fun add(cr: ContentResolver, uri: Uri): Boolean {
        if (items.any { it.uri == uri || it.orig == uri }) return false
        var name: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val finalName = (name ?: uri.lastPathSegment ?: "file").replace('/', '_')
        items.add(SharedItem(nextId.getAndIncrement().toString(), uri, finalName, mime, size))
        return true
    }

    /** A new link code like `pelmeni-babushka`. */
    private fun newToken(): String = LinkWords.pair()

    /** Private IPv4 addresses this phone can be reached on, hotspot first. */
    fun localAddresses(): List<LocalAddr> {
        val out = mutableListOf<LocalAddr>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (listOf("rmnet", "ccmni", "tun", "dummy", "lo", "p2p", "ifb", "v4-").any { name.startsWith(it) }) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    val ip = a.hostAddress ?: continue
                    val kind = when {
                        name == "wlan0" && !ip.endsWith(".1") -> "Wi-Fi"
                        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
                            name.matches(Regex("wlan[1-9].*")) || ip.endsWith(".1") -> "Hotspot"
                        name.startsWith("wlan") -> "Wi-Fi"
                        else -> "Other"
                    }
                    out.add(LocalAddr(name, ip, kind))
                }
            }
        } catch (_: Exception) {
        }
        val order = mapOf("Hotspot" to 0, "Wi-Fi" to 1, "Other" to 2)
        return out.sortedBy { order[it.kind] ?: 3 }
    }
}
