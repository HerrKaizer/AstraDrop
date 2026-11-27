package dev.tapdrop

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * "AstraDrop network": a private Wi-Fi made by this phone just for the iPhone (Android's
 * local-only hotspot, a public API that works on any phone, no settings or shell). The iPhone
 * joins it by scanning a Wi-Fi QR code. It has no internet; the iPhone keeps using mobile data
 * for that. Android picks a new name and password each time it starts.
 */
object OwnNetwork {
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var ssid: String? = null; private set
    @Volatile var pass: String? = null; private set
    /** Our network's interface (found by comparing addresses before and after it starts). */
    @Volatile var iface: String? = null; private set
    @Volatile var starting = false; private set

    val active get() = reservation != null

    /** [done] gets null on success or a message saying why it failed. Main thread. */
    fun start(ctx: Context, done: (String?) -> Unit) {
        if (active) { done(null); return }
        if (starting) return
        starting = true
        val before = ShareState.localAddresses().map { it.iface }.toSet()
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val c = r.softApConfiguration
                    @Suppress("DEPRECATION") val legacy = c.ssid
                    ssid = c.wifiSsid?.toString()?.removeSurrounding("\"") ?: legacy
                    pass = c.passphrase
                    // its address shows up a moment after it starts
                    fun find(left: Int) {
                        val now = ShareState.localAddresses()
                        val fresh = now.firstOrNull { it.iface !in before }
                        if (fresh != null || left == 0) {
                            iface = fresh?.iface
                            fresh?.let { ShareState.host = it.ip }
                            starting = false
                            ShareState.notifyChanged()
                            done(null)
                        } else main.postDelayed({ find(left - 1) }, 250)
                    }
                    find(16)
                }

                override fun onStopped() { clear(); ShareState.notifyChanged() }

                override fun onFailed(reason: Int) {
                    clear()
                    done(when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> "Turn off your hotspot first, or use it instead."
                        ERROR_TETHERING_DISALLOWED -> "This phone doesn't allow it. Use your hotspot instead."
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel right now. Try again, or use your hotspot."
                        else -> "Couldn't create the network. Check that Wi-Fi is on, then try again."
                    })
                }
            }, main)
        } catch (e: SecurityException) {
            clear(); done("AstraDrop needs the Nearby devices permission for this.")
        } catch (e: Exception) {
            clear(); done("Couldn't create the network: ${e.message}")
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        clear()
        ShareState.notifyChanged()
    }

    private fun clear() {
        reservation = null; ssid = null; pass = null; iface = null; starting = false
    }

    /** Wi-Fi QR text the iPhone camera understands. */
    fun wifiQr(ssid: String, pass: String?): String {
        fun e(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        return if (pass.isNullOrEmpty()) "WIFI:T:nopass;S:${e(ssid)};;" else "WIFI:T:WPA;S:${e(ssid)};P:${e(pass)};;"
    }
}
