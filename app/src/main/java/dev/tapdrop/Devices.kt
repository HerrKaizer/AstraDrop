package dev.tapdrop

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phones that have opened the page, each with its own secret (kept in a cookie).
 *
 * The link handed over by a tap or the QR code carries a one-time ticket (`?k=`). The first
 * browser that opens it swaps the ticket for a cookie, and the ticket is used up: the next tap
 * or code carries a new one. So a link seen over someone's shoulder, copied, or left in a
 * browser's history opens nothing; only the phone that was handed it gets in.
 */
object Devices {
    private const val CONNECTED_MS = 8_000L
    private const val HANDOFF_MS = 10 * 60_000L

    class Device(val id: String, val secret: String, val kind: String, val no: Int, @Volatile var approved: Boolean) {
        @Volatile var ip: String? = null
        @Volatile var lastSeen = 0L
        /** Shared files this device has fetched in full (opened, saved or in a downloaded archive). */
        val got: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** The set of files it was last told "you have them all" about (so it's said once). */
        @Volatile var doneFor = ""

        /** "iPhone", "iPhone 2"... in the app's language. */
        fun label(): String = I18n.t(kind) + if (no > 1) " $no" else ""
    }

    private val list = CopyOnWriteArrayList<Device>()
    /** One-time tickets that move an already-joined device to the HTTPS copy of the page. */
    private val handoffs = ConcurrentHashMap<String, Pair<Device, Long>>()

    /** The ticket in the link that the next tap or QR scan hands over. */
    @Volatile var ticket: String = LinkCode.new(); private set

    fun all(): List<Device> = list.toList()
    fun connected(): List<Device> {
        val now = SystemClock.elapsedRealtime()
        return list.filter { it.approved && it.lastSeen > 0 && now - it.lastSeen < CONNECTED_MS }
    }
    fun pending(): List<Device> = list.filter { !it.approved }

    fun bySecret(secret: String?): Device? =
        if (secret.isNullOrEmpty()) null else list.firstOrNull { LinkCode.matches(secret, it.secret) }

    /**
     * Uses up [given] if it is the current ticket (or a handoff ticket) and returns the device it
     * lets in: [current] when that browser already has a cookie, otherwise a new device (waiting
     * for approval when [ask] is on). Null when the ticket isn't, or isn't any more, valid.
     */
    fun join(given: String, userAgent: String?, current: Device?, ask: Boolean): Device? {
        synchronized(this) {
            if (!LinkCode.matches(given, ticket)) return redeemHandoff(given)
            ticket = LinkCode.new()
            val dev = current ?: newDevice(userAgent, ask).also { list.add(it) }
            ShareState.notifyChanged()
            return dev
        }
    }

    /** A one-time ticket for [dev] to open the page's HTTPS copy (a separate site, so no cookie there yet). */
    fun handoff(dev: Device): String {
        val now = SystemClock.elapsedRealtime()
        handoffs.entries.removeIf { now - it.value.second > HANDOFF_MS }
        handoffs.entries.firstOrNull { it.value.first === dev }?.let { return it.key }
        val t = LinkCode.new()
        handoffs[t] = dev to now
        return t
    }

    private fun redeemHandoff(given: String): Device? {
        val key = handoffs.keys.firstOrNull { LinkCode.matches(given, it) } ?: return null
        val (dev, at) = handoffs.remove(key) ?: return null
        if (SystemClock.elapsedRealtime() - at > HANDOFF_MS || dev !in list) return null
        return dev
    }

    fun approve(id: String) {
        list.firstOrNull { it.id == id }?.approved = true
        ShareState.notifyChanged()
    }

    /** Throws the device out: its page stops working until it's handed the link again. */
    fun remove(id: String) {
        list.removeAll { it.id == id }
        handoffs.entries.removeIf { it.value.first.id == id }
        ShareState.notifyChanged()
    }

    /** New link: everyone has to be handed it again. */
    fun clear() {
        synchronized(this) {
            list.clear()
            handoffs.clear()
            ticket = LinkCode.new()
        }
    }

    /** "iPhone", then "iPhone 2" for a second one, and so on. */
    private fun newDevice(userAgent: String?, ask: Boolean): Device {
        val kind = when {
            userAgent == null -> "iPhone"
            userAgent.contains("iPad") -> "iPad"
            userAgent.contains("iPhone") -> "iPhone"
            userAgent.contains("Macintosh") -> "Mac"
            userAgent.contains("Android") -> "Android phone"
            else -> "A device"
        }
        val no = (list.filter { it.kind == kind }.maxOfOrNull { it.no } ?: 0) + 1
        return Device(LinkCode.new(), LinkCode.new(), kind, no, approved = !ask)
    }

    /** How many of the shared files [dev] has, out of how many. */
    fun progress(dev: Device): Pair<Int, Int> {
        val ids = ShareState.items.map { it.id }
        return ids.count { it in dev.got } to ids.size
    }

    /** "2 devices" or the one device's name: who has the page open right now. */
    fun connectedLabel(): String {
        val c = connected()
        return if (c.size == 1) c[0].label() else I18n.plural(c.size, "%d device", "%d devices")
    }
}
