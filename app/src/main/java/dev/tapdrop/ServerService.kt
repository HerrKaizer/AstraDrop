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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            android.util.Log.i("TapDrop", "service: stop action"); stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (http == null) {
            val server = HttpServer(applicationContext) { item ->
                ShareState.received.add(0, item)
                ShareState.notifyChanged()
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            }
            try {
                ShareState.port = server.start(PORT)
                http = server
                ShareState.running = true
                NfcShizuku.set(this, true)
                acquireLocks()
                // the HTTPS copy needs the certificate first, which may mean a download
                Thread {
                    val ssl = TlsCert.context(applicationContext) ?: return@Thread
                    val s = HttpServer(applicationContext, ssl) { item ->
                        ShareState.received.add(0, item)
                        ShareState.notifyChanged()
                    }
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
        val wait = NfcShizuku.setBlockingOff(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, wait)
    }

    override fun onDestroy() {
        http?.stop()
        http = null
        https?.stop()
        https = null
        ShareState.tlsPort = 0
        if (!taskRemoved) NfcShizuku.set(this, false)
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
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = ShareState.items.size
        val got = ShareState.received.size
        val text = buildString {
            append(if (n == 0) "Ready to receive" else "Sharing $n file${if (n == 1) "" else "s"}")
            if (got > 0) append(" · received $got")
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SolDrop is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.tapdrop.STOP"
        private const val CHANNEL = "sharing"
        private const val NOTIF_ID = 1
        private const val PORT = 8765
        private const val TLS_PORT = 8766
    }
}
