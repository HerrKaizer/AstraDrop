package dev.tapdrop.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.tapdrop.MainActivity
import dev.tapdrop.R
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Runs only while the user pairs AstraDrop with Wireless debugging. Watches for this phone's
 * "Pair device with pairing code" dialog (mDNS) and pops a notification with a code box, so the
 * 6 digits can be typed without leaving Settings. Then starts the engine.
 */
class PairingService : Service() {

    enum class Phase { IDLE, WAITING, CODE, WORKING, DONE, ERROR }

    companion object {
        private const val CH_QUIET = "pair_status"
        private const val CH_CODE = "pair_code"
        private const val ID_STATUS = 31
        private const val ID_CODE = 32
        private const val KEY = "code"
        private const val TIMEOUT_MS = 600_000L
        private const val ACTION_CODE = "dev.tapdrop.PAIR_CODE"

        @Volatile var phase = Phase.IDLE; private set
        @Volatile var error = ""; private set
        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun publish(p: Phase, e: String = "") {
            phase = p; error = e
            main.post { listeners.forEach { it() } }
        }

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, PairingService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, PairingService::class.java)) }

        internal fun submit(ctx: Context, code: String) {
            runCatching { ctx.startService(Intent(ctx, PairingService::class.java).setAction(ACTION_CODE).putExtra(KEY, code)) }
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var nsd: NsdManager? = null
    private var disc: NsdManager.DiscoveryListener? = null
    @Volatile private var pairPort = 0
    private var prompted = 0
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, dev.tapdrop.I18n.t("Auto mode setup"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CODE, dev.tapdrop.I18n.t("Pairing code"), NotificationManager.IMPORTANCE_HIGH))
        nsd = getSystemService(NsdManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CODE) {
            onCode(intent.getStringExtra(KEY).orEmpty())
            return START_NOT_STICKY
        }
        startForeground(ID_STATUS, status("Waiting for the pairing code",
            "In Wireless debugging, tap “Pair device with pairing code”."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        publish(Phase.WAITING)
        discover()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        disc?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        worker.shutdownNow()
        getSystemService(NotificationManager::class.java).cancel(ID_CODE)
        if (phase != Phase.DONE) publish(Phase.IDLE)
        super.onDestroy()
    }

    private fun discover() {
        if (disc != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) { worker.execute { resolve(s) } }
        }
        disc = l
        runCatching { nsd?.discoverServices(LocalAdb.TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: NsdServiceInfo? = null
        runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) { latch.countDown() }
                override fun onServiceResolved(s: NsdServiceInfo) { out = s; latch.countDown() }
            })
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val s = out ?: return
        if (!LocalAdb.isOwn(s)) return   // another phone on the same Wi-Fi
        pairPort = s.port
        if (prompted != s.port) {
            prompted = s.port
            publish(Phase.CODE)
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, TIMEOUT_MS)
            askCode("Type the pairing code", "Tap “Enter code” and type the 6 digits shown on screen.")
        }
    }

    private fun onCode(raw: String) {
        val code = raw.filter { it.isDigit() }
        if (code.length != 6) { askCode("Enter all 6 digits", "Tap “Enter code” and type the 6 digits shown on screen."); return }
        if (pairPort <= 0) { askCode("Open the pairing code first", "Tap “Pair device with pairing code”, then enter the code."); return }
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(ID_CODE)
        worker.execute {
            publish(Phase.WORKING)
            nm.notify(ID_STATUS, status("Setting up…", "Almost done."))
            if (Engine.pair(this, pairPort, code).isFailure) {
                publish(Phase.ERROR, "That code didn't work. Try again.")
                askCode("That code didn't work", "Tap “Enter code” to try again.")
                return@execute
            }
            if (Engine.launch(this, -1).isFailure) {
                publish(Phase.ERROR, "Paired, but couldn't start. Open AstraDrop and tap Try again.")
                nm.notify(ID_STATUS, status("Couldn't finish", "Open AstraDrop and tap Try again."))
                return@execute
            }
            publish(Phase.DONE)
            val open = PendingIntent.getActivity(this, 2, Intent(this, dev.tapdrop.SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(ID_CODE, Notification.Builder(this, CH_CODE)
                .setSmallIcon(R.drawable.ic_notif_pair)
                .setContentTitle(dev.tapdrop.I18n.t("Auto mode is ready"))
                .setContentText(dev.tapdrop.I18n.t("Tap to go back to AstraDrop."))
                .setContentIntent(open).setAutoCancel(true).build())
            main.post { stopSelf() }
        }
    }

    private fun status(title: String, text: String) = Notification.Builder(this, CH_QUIET)
        .setSmallIcon(R.drawable.ic_notif_pair)
        .setContentTitle(dev.tapdrop.I18n.t(title)).setContentText(dev.tapdrop.I18n.t(text))
        .setOngoing(true).setOnlyAlertOnce(true).build()

    private fun askCode(title: String, text: String) {
        // NO content intent: tapping the notification must not leave Settings (it closes the pairing dialog)
        val pi = PendingIntent.getBroadcast(this, 1, Intent(this, PairingReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif_pair), dev.tapdrop.I18n.t("Enter code"), pi)
            .addRemoteInput(RemoteInput.Builder(KEY).setLabel(dev.tapdrop.I18n.t("6-digit code")).setAllowFreeFormInput(true).build())
            // no "Okay / What code?" chips: Android suggests replies for anything with a reply box
            .setAllowGeneratedReplies(false)
            .build()
        val n = Notification.Builder(this, CH_CODE)
            .setSmallIcon(R.drawable.ic_notif_pair)
            .setContentTitle(dev.tapdrop.I18n.t(title)).setContentText(dev.tapdrop.I18n.t(text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(android.os.Bundle().apply { putBoolean("custom_always_expand", true) })
            .addAction(action)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(ID_CODE)
        nm.notify(ID_CODE, n)
    }
}

/** The code typed into the notification. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("code")?.toString() ?: return
        PairingService.submit(ctx.applicationContext, code)
    }
}
