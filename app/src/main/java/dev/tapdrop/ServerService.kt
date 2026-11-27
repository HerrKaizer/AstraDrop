package dev.tapdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/** Foreground service that owns the web server so it keeps running in the background. */
class ServerService : Service() {
    private var http: HttpServer? = null
    @Volatile private var https: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var taskRemoved = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var notifKey = ""

    /** Sharing stops by itself after the chosen idle time (Settings → Stop when idle), unless AstraDrop is on screen. */
    private val idleCheck = object : Runnable {
        override fun run() {
            val limit = Idle.minutes(this@ServerService) * 60_000L
            val idle = android.os.SystemClock.elapsedRealtime() - Visits.lastActivity
            if (limit > 0 && !ShareState.uiVisible && !Visits.busy() && idle > limit) {
                android.util.Log.i("TapDrop", "service: idle ${idle / 60_000} min, stopping")
                stopSelf()
                return
            }
            main.postDelayed(this, 60_000)
        }
    }

    /** The notification says who has the page open and how far they are; rebuilt only when that changes. */
    private val refreshNotif: () -> Unit = {
        if (http != null) {
            if (notifState() != notifKey) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            checkDone()
        }
    }

    private fun newServer(ssl: javax.net.ssl.SSLContext?) = HttpServer(
        applicationContext, ssl,
        onAsk = { dev -> main.post { notifyAsk(dev) } },
        onText = { text, dev ->
            History.addText(applicationContext, text, dev.kind)
            main.post { notifyText(text, dev) }
        },
    ) { item, dev ->
        ShareState.received.add(0, item)
        History.addFile(applicationContext, item, dev.kind)
        ShareState.notifyChanged()
    }

    /** What the sharing notification shows, as a key: it's rebuilt when this changes. */
    private fun notifState(): String {
        val tr = Visits.transfers().maxByOrNull { it.total }
        val pct = if (tr != null && tr.total > 0) (tr.done * 20 / tr.total).toInt() else -1
        val devs = Devices.connected().joinToString { it.id + ":" + Devices.progress(it).first }
        return "$devs ${ShareState.items.size} ${ShareState.received.size} $pct ${tr?.name}"
    }

    /**
     * A device has every shared file: say so once (for this set of files) and, when chosen in
     * Settings, stop sharing a little later if nothing else happens.
     */
    private fun checkDone() {
        val ids = ShareState.items.map { it.id }
        if (ids.isEmpty()) return
        val key = ids.joinToString(",")
        for (d in Devices.all()) {
            if (!d.approved || !d.got.containsAll(ids) || d.doneFor == key) continue
            d.doneFor = key
            val nm = getSystemService(NotificationManager::class.java)
            val stop = PendingIntent.getService(this, 3, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(I18n.plural(ids.size, "%s has %d file", "%s has all %d files").replace("%s", d.label()))
                .setContentText(if (DoneStop.on(this)) I18n.t("Sharing stops in a moment.") else I18n.t("You can stop sharing now."))
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), I18n.t("Stop sharing"), stop).build())
                .build()
            nm.notify(NOTIF_DONE, n)
            if (DoneStop.on(this)) {
                main.removeCallbacks(doneStop)
                main.postDelayed(doneStop, DONE_STOP_MS)
            }
        }
    }

    /** Stop when the iPhone has everything (Settings), unless something started moving again. */
    private val doneStop = object : Runnable {
        override fun run() {
            if (Visits.busy() || android.os.SystemClock.elapsedRealtime() - Visits.lastTransfer < DONE_STOP_MS / 2) {
                main.postDelayed(this, 10_000)
                return
            }
            android.util.Log.i("TapDrop", "service: everything downloaded, stopping")
            stopSelf()
        }
    }

    /** Text from the iPhone: shown with Copy (and Open for a link). */
    private fun notifyText(text: String, dev: Devices.Device) {
        val id = NOTIF_TEXT + (text.hashCode() and 0xFFF)
        val copy = PendingIntent.getBroadcast(this, id, Intent(this, ActionReceiver::class.java)
            .setAction(ActionReceiver.COPY).putExtra(ActionReceiver.EXTRA_TEXT, text).putExtra(ActionReceiver.EXTRA_NOTIF, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(I18n.t("Text from %s").format(dev.label()))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), I18n.t("Copy"), copy).build())
        linkIn(text)?.let { link ->
            val open = PendingIntent.getActivity(this, id, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), I18n.t("Open"), open).build())
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    /** A new device waits to be let in (Settings → Ask before a device joins). */
    private fun notifyAsk(dev: Devices.Device) {
        val id = NOTIF_ASK + (dev.id.hashCode() and 0xFFF)
        fun act(action: String, label: String, req: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat), I18n.t(label),
            PendingIntent.getBroadcast(this, req, Intent(this, ActionReceiver::class.java).setAction(action)
                .putExtra(ActionReceiver.EXTRA_DEVICE, dev.id).putExtra(ActionReceiver.EXTRA_NOTIF, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        val n = Notification.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(I18n.t("%s wants to connect").format(dev.label()))
            .setContentText(I18n.t("Allow it to see the shared files and send you files?"))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(act(ActionReceiver.ALLOW, "Allow", id))
            .addAction(act(ActionReceiver.BLOCK, "Block", id + 1))
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            android.util.Log.i("TapDrop", "service: stop action"); stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (http == null) {
            val server = newServer(null)
            try {
                ShareState.port = server.start(PORT)
                http = server
                ShareState.running = true
                AutoNfc.set(this, true)
                acquireLocks()
                // the HTTPS copy needs the certificate first, which may mean a download
                Thread {
                    val ssl = TlsCert.context(applicationContext) ?: return@Thread
                    val s = newServer(ssl)
                    try {
                        ShareState.tlsPort = s.start(TLS_PORT)
                        https = s
                    } catch (e: Exception) {
                        android.util.Log.w("TapDrop", "tls: server failed", e)
                    }
                }.start()
            } catch (e: Exception) {
                ShareState.running = false
                android.util.Log.w("TapDrop", "service: server failed to start", e); stopSelf()
            }
        }
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 60_000)
        ShareState.addListener(refreshNotif)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        ShareState.notifyChanged()
        return START_NOT_STICKY
    }

    /**
     * TapDrop was swiped away from recents: nothing may keep running once the app is
     * closed. Stop the server, drop all cached copies and end the process (which also
     * ends any conversion or compression mid-way).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        http?.stop()
        http = null
        https?.stop()
        https = null
        ShareState.running = false
        ShareState.items.clear()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        android.util.Log.i("TapDrop", "service: task removed")
        OwnNetwork.stop()
        val wait = AutoNfc.offBlocking(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, wait)
    }

    override fun onDestroy() {
        main.removeCallbacks(idleCheck)
        main.removeCallbacks(doneStop)
        ShareState.removeListener(refreshNotif)
        http?.stop()
        http = null
        https?.stop()
        https = null
        ShareState.tlsPort = 0
        if (!taskRemoved) { AutoNfc.set(this, false); OwnNetwork.stop() }
        ShareState.running = false
        ShareState.port = 0
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        ShareState.notifyChanged()
        super.onDestroy()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapDrop:server")
            .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapDrop:server")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CHANNEL_EVENTS) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_EVENTS, I18n.t("Texts and finished transfers"), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun buildNotification(): Notification {
        notifKey = notifState()
        // while connected, look again in a few seconds so "connected" goes away when the page closes
        main.removeCallbacks(connTick)
        if (Visits.connected()) main.postDelayed(connTick, 5_000)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = ShareState.items.size
        val got = ShareState.received.size
        val devs = Devices.connected()
        val text = buildString {
            append(if (n == 0) I18n.t("Ready to receive") else I18n.plural(n, "Sharing %d file", "Sharing %d files"))
            if (got > 0) append(" · ").append(I18n.t("received %d").format(got))
            // "iPhone has 12 of 30" for each phone that has the page open
            if (n > 0) for (d in devs) {
                val (have, all) = Devices.progress(d)
                append(" · ").append(I18n.t("%s has %d of %d").format(d.label(), have, all))
            }
        }
        val title = if (devs.isNotEmpty()) I18n.t("%s connected").format(Devices.connectedLabel()) else I18n.t("AstraDrop is on")
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), I18n.t("Stop"), stop).build())
        // the biggest transfer running, as a bar
        Visits.transfers().maxByOrNull { it.total }?.let { tr ->
            if (tr.total > 0) b.setProgress(1000, (tr.done * 1000 / tr.total).toInt().coerceIn(0, 1000), false)
            b.setSubText((if (tr.up) I18n.t("Receiving %s") else I18n.t("Sending %s")).format(tr.name))
        }
        return b.build()
    }

    private val connTick = Runnable { refreshNotif() }

    companion object {
        const val ACTION_STOP = "dev.tapdrop.STOP"
        private const val CHANNEL = "sharing"
        private const val CHANNEL_EVENTS = "events"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE = 2
        private const val NOTIF_TEXT = 0x10000
        private const val NOTIF_ASK = 0x20000
        private const val DONE_STOP_MS = 30_000L
        private const val PORT = 8765
        private const val TLS_PORT = 8766
    }
}

/** Settings → Ask before a device joins: a new phone waits until it's allowed here. */
object Ask {
    fun on(ctx: android.content.Context) = try {
        ctx.getSharedPreferences("tapdrop", android.content.Context.MODE_PRIVATE).getBoolean("ask_join", false)
    } catch (_: Exception) { false }
    fun set(ctx: android.content.Context, v: Boolean) =
        ctx.getSharedPreferences("tapdrop", android.content.Context.MODE_PRIVATE).edit().putBoolean("ask_join", v).apply()
}

/** Settings → Stop when it has everything: sharing ends once a phone has every shared file. */
object DoneStop {
    fun on(ctx: android.content.Context) =
        ctx.getSharedPreferences("tapdrop", android.content.Context.MODE_PRIVATE).getBoolean("done_stop", false)
    fun set(ctx: android.content.Context, v: Boolean) =
        ctx.getSharedPreferences("tapdrop", android.content.Context.MODE_PRIVATE).edit().putBoolean("done_stop", v).apply()
}

/** Settings → Stop when idle: minutes without the iPhone doing anything (0 = never). */
object Idle {
    val CHOICES = listOf(0, 15, 30, 60)
    fun minutes(ctx: android.content.Context) =
        ctx.getSharedPreferences("tapdrop", android.content.Context.MODE_PRIVATE).getInt("idle_stop", 30)
    fun set(ctx: android.content.Context, m: Int) =
        ctx.getSharedPreferences("tapdrop", android.content.Context.MODE_PRIVATE).edit().putInt("idle_stop", m).apply()
}
