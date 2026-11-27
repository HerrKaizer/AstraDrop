package dev.tapdrop

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.IntentFilter
import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import dev.tapdrop.engine.Engine
import dev.tapdrop.engine.PairingService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import java.util.Locale
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    private val fontRegular: Typeface get() = Look.font(this, 400)
    private val fontMedium: Typeface get() = Look.font(this, 560)
    private val fontBold: Typeface get() = Look.font(this, 680)

    private enum class State { READY, OFF, NO_NET, NO_NFC, NFC_OFF }

    private lateinit var p: Look.Palette
    private lateinit var ui: Ui
    private lateinit var thumbs: Thumbs
    private var nfc: NfcAdapter? = null
    private var userStopped = false
    private var addrs: List<LocalAddr> = emptyList()
    private val listener: () -> Unit = { render() }

    private lateinit var iconWrap: FrameLayout
    private lateinit var iconBg: View
    private lateinit var pulse: View
    private lateinit var icon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var primaryBtn: TextView
    private lateinit var tapWay: WayTile
    private lateinit var scanWay: WayTile
    private lateinit var wifiTile: WayTile
    private lateinit var hotTile: WayTile
    private lateinit var sendTitle: TextView
    private lateinit var sendGrid: LinearLayout
    private lateinit var sendHint: TextView
    private lateinit var clearBtn: TextView
    private lateinit var recvTitle: TextView
    private lateinit var recvGrid: LinearLayout
    private lateinit var recvHint: TextView
    private var pulseAnim: AnimatorSet? = null
    private lateinit var scrollView: ScrollView
    /** Shown section (0 Share, 1 Settings), each one's scroll position, and a way to switch from code. */
    private var activeTab = 0
    private val pageScrollY = IntArray(2)
    private var showTab: (Int) -> Unit = {}
    private lateinit var autoBtn: TextView
    private lateinit var autoNote: TextView
    private lateinit var autoText: TextView
    private lateinit var netHint: TextView
    private lateinit var transferBox: LinearLayout
    private lateinit var transferText: TextView
    private lateinit var transferBar: android.widget.ProgressBar
    private lateinit var devBox: LinearLayout
    private var devKey = ""
    private var historyAllBtn: TextView? = null
    private var historyClearBtn: TextView? = null
    /** Devices already asked about in a dialog here (so each is asked once). */
    private val asked = HashSet<String>()
    private var wifiName: String? = null
    private var visitShown = false
    private lateinit var rootFrame: FrameLayout
    private lateinit var tapRipple: TapRipple
    private var iconTint = 0
    private var iconKick: AnimatorSet? = null
    private val tapListener: () -> Unit = { triggerTapEffect() }

    /** NFC switched on or off anywhere (quick settings, Automatic NFC): update the hero right away. */
    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = render()
    }
    private val autoListener: () -> Unit = { render(); renderAuto() }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    // networks come and go (hotspot on, Wi-Fi joined): look again every few seconds while on screen
    private val netTick = object : Runnable {
        override fun run() {
            val before = addrs
            refreshNetworks()
            if (before != addrs) { ShareState.notifyChanged() }
            // the Wi-Fi's name comes from the engine (apps need location access for it)
            if (AutoNfc.engineUp) Thread {
                val n = AutoNfc.wifiName(this@MainActivity)
                if (n != wifiName) main.post { wifiName = n; renderNetworks() }
            }.start()
            // "connected" ends a few seconds after the iPhone's last request
            if (visitShown != Visits.connected()) render()
            main.postDelayed(this, 3000)
        }
    }

    // ================================================================== lifecycle

    /**
     * Deletes everything TapDrop keeps in its cache (converted DNGs, compressed
     * copies, in-memory thumbnails and previews). Only call when nothing is shared.
     */
    private fun clearCache() {
        ThumbStore.clear()
        Previews.clear()
        thumbs.clear()
        Thread { cacheDir.listFiles()?.forEach { it.deleteRecursively() } }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Look.isDark(this)) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        // a fresh start shares nothing yet, so anything cached is left over from last time
        if (ShareState.items.isEmpty()) Thread { cacheDir.listFiles()?.forEach { it.deleteRecursively() } }.start()
        p = ui.p
        thumbs = Thumbs(this)
        nfc = NfcAdapter.getDefaultAdapter(this)
        tapRipple = TapRipple(this)
        actionBar?.hide()
        styleSystemBars()
        rootFrame = buildUi()
        setContentView(rootFrame)
        // first run: the tour first, the notification permission once it's done (see onResume)
        if (savedInstanceState == null && !TourActivity.seen(this)) startActivity(Intent(this, TourActivity::class.java))
        handleShareIntent(intent)
        AutoNfc.addListener(autoListener)
        PairingService.addListener(autoListener)
    }

    override fun onDestroy() {
        iconKick?.cancel()
        tapRipple.release(rootFrame)
        AutoNfc.removeListener(autoListener)
        PairingService.removeListener(autoListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ShareState.addListener(listener)
        ShareState.addTapListener(tapListener)
        refreshNetworks()
        if (!userStopped) startServer()
        setPreferredTag(true)
        registerReceiver(nfcReceiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), RECEIVER_EXPORTED)
        render()
        renderAuto()
        AutoNfc.ensureEngine(this)
        askNotifications()
        ShareState.uiVisible = true
        Visits.touch()
        main.postDelayed(netTick, 3000)
    }

    override fun onPause() {
        ShareState.uiVisible = false
        Visits.touch()
        main.removeCallbacks(netTick)
        try { unregisterReceiver(nfcReceiver) } catch (_: Exception) {}
        setPreferredTag(false)
        ShareState.removeTapListener(tapListener)
        ShareState.removeListener(listener)
        tapRipple.cancel(rootFrame)
        setPulse(false)
        super.onPause()
    }

    /** Notifications keep sharing running in the background; asked once, after the tour. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !TourActivity.seen(this)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val pr = getSharedPreferences("tapdrop", MODE_PRIVATE)
        if (pr.getBoolean("notif_asked", false)) return
        pr.edit().putBoolean("notif_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }

    /** While TapDrop is on screen, our tag wins over other apps that emulate the same AID (e.g. Bada). */
    private fun setPreferredTag(on: Boolean) {
        val adapter = nfc ?: return
        try {
            val ce = CardEmulation.getInstance(adapter)
            if (on) ce.setPreferredService(this, ComponentName(this, NdefHceService::class.java))
            else ce.unsetPreferredService(this)
        } catch (_: Exception) {
        }
    }

    // ================================================================== incoming files

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_QUICK -> { intent.action = Intent.ACTION_MAIN; quickStart(); return }
        }
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
            else -> return
        }
        intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { if (it !in uris) uris += it } }
        // text or a link shared from another app (no file with it)
        if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { addText(it) }
            intent.action = Intent.ACTION_MAIN
            return
        }
        addUris(uris, persist = false)
        intent.action = Intent.ACTION_MAIN
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun addUris(uris: List<Uri>, persist: Boolean) {
        var added = 0
        for (u in uris) {
            if (persist) {
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            if (ShareState.add(contentResolver, u)) {
                added++
                ShareState.items.lastOrNull()?.let {
                    ThumbStore.prefetch(this, it.uri, it.mime)
                    RawConverter.start(this, it)
                }
            }
        }
        if (added > 0) {
            userStopped = false
            startServer()
        }
        render()
    }

    private fun addText(text: String) {
        if (ShareState.addText(text)) {
            userStopped = false
            startServer()
        }
        render()
    }

    /** The Text tile: type or paste something for the iPhone (starts with what's on the clipboard). */
    private fun askText() {
        val clip = try {
            getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        val box = android.widget.EditText(this).apply {
            setText(clip ?: "")
            hint = t("Text or a link")
            textSize = 16f
            setTextColor(p.fg)
            setHintTextColor(p.sub)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = rounded(p.tile, 14.dp)
            selectAll()
        }
        ui.dialog(t("Send text"), t("It shows up on the iPhone's page with a Copy button."), content = box,
            positive = "Send", negative = "Cancel", onPositive = { addText(box.text.toString()) })
    }

    @Deprecated("framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris += it } }
        data.data?.let { if (it !in uris) uris += it }
        addUris(uris, persist = requestCode == REQ_FILES)
    }

    private fun pickMedia() {
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, REQ_MEDIA)
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(i, REQ_FILES)
    }

    // ================================================================== server control

    private fun startServer() {
        val i = Intent(this, ServerService::class.java)
        val items = ShareState.items.toList()
        if (items.isNotEmpty()) {
            val clip = ClipData.newRawUri("files", items[0].uri)
            items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            toast("Couldn't start sharing: ${e.message}")
        }
    }

    private fun stopServer() {
        android.util.Log.i("TapDrop", "stopServer", Throwable("caller"))
        userStopped = true
        stopService(Intent(this, ServerService::class.java))
    }

    private fun refreshNetworks() {
        addrs = ShareState.localAddresses()
        val current = ShareState.host
        if (current == null || addrs.none { it.ip == current }) ShareState.host = addrs.firstOrNull()?.ip
    }

    private fun state(): State {
        val a = nfc
        return when {
            !ShareState.running -> State.OFF
            ShareState.host == null -> State.NO_NET
            a == null -> State.NO_NFC
            !a.isEnabled -> State.NFC_OFF
            else -> State.READY
        }
    }

    // ================================================================== rendering

    private fun t(s: String) = I18n.t(s)

    private fun render() {
        val s = state()
        val tint = when (s) {
            State.READY, State.NFC_OFF, State.NO_NFC -> p.link
            State.OFF -> p.sub
            State.NO_NET -> p.warn
        }
        val autoOn = AutoNfc.switchingOn
        val visit = Visits.connected() && ShareState.running
        visitShown = visit
        heroTitle.text = when {
            visit -> t("%s connected").format(Devices.connectedLabel())
            s == State.READY -> t("Ready")
            s == State.NFC_OFF || s == State.NO_NFC -> t("Ready to scan")
            s == State.NO_NET -> t("Connect to a network")
            else -> t("Sharing is paused")
        }
        heroSub.text = when {
            visit && Devices.connected().size > 1 -> t("The page is open on several devices. Files you add here show up there by themselves.")
            visit -> t("The page is open on the %s. Files you add here show up there by themselves.").format(Devices.connectedLabel())
            s == State.READY -> t("Hold the iPhone to the back of this phone, or let it scan the code.")
            s == State.NFC_OFF -> if (autoOn) t("Turning NFC on…") else t("The iPhone can scan the code. To tap instead, turn on NFC.")
            s == State.NO_NFC -> t("This phone has no NFC, so the iPhone scans the code.")
            s == State.NO_NET -> t("Both phones need to be on the same Wi-Fi, or on this phone's hotspot (below).")
            else -> t("The iPhone can't open your page until sharing starts again.")
        }
        // own glass disc: gold with the same rim and glow as the filled buttons when ready,
        // otherwise clear glass tinted by the state. Rebuilt only when that changes (new glass = shader compile)
        val ready = s == State.READY || s == State.NFC_OFF || s == State.NO_NFC
        icon.imageTintList = ColorStateList.valueOf(if (ready) p.onAccent else tint)
        val discKey = if (ready) 1 else tint
        if (iconTint != discKey) {
            iconTint = discKey
            iconBg.background = if (ready) accentGradient(32.dp) else glass(32.dp, alpha(tint, if (isDark()) 0.16f else 0.12f))
            iconBg.elevation = if (ready && p.halo != 0) 0f else if (isDark()) 4.dp.toFloat() else 5.dp.toFloat()
        }
        pulse.background = oval(alpha(tint, 0.35f))
        setPulse(s == State.READY && !visit)

        primaryBtn.visibility = if (s == State.OFF) View.VISIBLE else View.GONE
        renderTransfers()
        renderDevices()
        askPending()

        // the two ways to connect: always shown, greyed when they can't work right now
        val net = s != State.NO_NET && s != State.OFF
        val engine = AutoNfc.enabled(this) && AutoNfc.setUp(this)
        tapWay.set(
            when {
                nfc == null -> t("Not on this phone")
                autoOn -> t("Turning NFC on…")
                s == State.NFC_OFF -> t("NFC off · Tap to turn on")
                engine -> t("NFC on · Tap to turn off")
                else -> t("NFC on · Hold phones together")
            },
            active = net && nfc?.isEnabled == true, warn = s == State.NFC_OFF && !autoOn, dim = nfc == null || nfc?.isEnabled != true,
        )
        scanWay.set(t("QR code of the link"), active = net, warn = false, dim = !net)

        renderNetworks()
        renderSendGrid()
        renderRecvGrid()
        settingRefresh.forEach { it() }
    }

    /** What's moving right now, under the status: "Sending IMG_1234.jpg · 45 %" with a bar. */
    private fun renderTransfers() {
        val list = Visits.transfers()
        if (list.isEmpty()) { transferBox.visibility = View.GONE; return }
        transferBox.visibility = View.VISIBLE
        val tr = list.maxByOrNull { it.total } ?: return
        val f = if (tr.total > 0) (tr.done.toFloat() / tr.total).coerceIn(0f, 1f) else 0f
        val what = if (tr.up) t("Receiving %s") else t("Sending %s")
        val more = if (list.size > 1) "  +" + (list.size - 1) else ""
        transferText.text = what.format(tr.name) + more + (if (tr.total > 0) "  ·  ${(f * 100).toInt()} %" else "")
        transferBar.progress = (f * 1000).toInt()
    }

    /**
     * Each phone with the page open: "iPhone · has 12 of 30" and a Remove pill (it then has
     * to be tapped or scan again). Rebuilt only when something in it changes.
     */
    private fun renderDevices() {
        val devs = if (ShareState.running) Devices.connected() else emptyList()
        val key = devs.joinToString { d -> d.id + ":" + Devices.progress(d) }
        if (key == devKey) return
        devKey = key
        devBox.removeAllViews()
        devBox.visibility = if (devs.isEmpty()) View.GONE else View.VISIBLE
        devs.forEachIndexed { i, d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                clipChildren = false; clipToPadding = false
            }
            val (have, all) = Devices.progress(d)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = d.label(); textSize = 15f; setTextColor(p.fg); typeface = fontMedium
            })
            col.addView(TextView(this).apply {
                text = when {
                    all == 0 -> t("Has the page open")
                    have >= all -> I18n.plural(all, "Has %d file", "Has all %d files")
                    else -> t("Has %d of %d files").format(have, all)
                }
                textSize = 13f; setTextColor(if (all > 0 && have >= all) p.link else p.sub); typeface = fontRegular
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(smallPill("Remove") {
                ui.dialog(t("Remove %s?").format(d.label()), t("Its page stops working. To connect again, it taps or scans again."),
                    positive = "Remove", negative = "Cancel", onPositive = { Devices.remove(d.id); render() })
            }, pillParams(first = true))
            devBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = 10.dp })
        }
    }

    /** A new phone waiting to be let in (Settings → Ask before a device joins): ask here too. */
    private fun askPending() {
        val d = Devices.pending().firstOrNull { it.id !in asked } ?: return
        asked += d.id
        ui.dialog(t("%s wants to connect").format(d.label()), t("Allow it to see the shared files and send you files?"),
            positive = "Allow", negative = "Block",
            onPositive = { Devices.approve(d.id); cancelAsk(d) },
            onNegative = { Devices.remove(d.id); cancelAsk(d) })
    }

    private fun cancelAsk(d: Devices.Device) {
        getSystemService(android.app.NotificationManager::class.java).cancel(0x20000 + (d.id.hashCode() and 0xFFF))
    }

    private fun onPrimary() {
        userStopped = false; refreshNetworks(); startServer()
    }

    /** Tap tile: switches NFC on or off (Auto mode does it right here; otherwise the NFC page opens). */
    private fun onTap() {
        val a = nfc
        when {
            a == null -> toast(t("This phone has no NFC. Use Scan instead."))
            AutoNfc.enabled(this) && AutoNfc.setUp(this) -> { AutoNfc.nfcNow(this, !a.isEnabled); render() }
            else -> Nav.nfc(this)
        }
    }

    /** Long press on Tap: how tapping works. */
    private fun tapHelp() {
        ui.dialog(t("How to tap"),
            t("1.  Unlock the iPhone.\n2.  Hold the top of the iPhone against the back of this phone, near the camera, for a second.\n3.  Tap the banner that appears on the iPhone.\n\nNothing happens? The iPhone may have NFC reading switched off; use Scan instead."),
            positive = t("Got it"))
    }

    private fun onScan() {
        val u = ShareState.url()
        when {
            state() == State.NO_NET -> toast(t("Connect both phones to one network first"))
            u == null -> toast(t("Sharing hasn't started yet"))
            else -> showQrDialog(u, t("Scan with the iPhone"),
                t("This code holds the link to the shared files. Open the Camera on the iPhone, point it at the code and tap the yellow link."),
                live = { ShareState.url() })
        }
    }

    private fun hotspotOn() = addrs.any { it.kind == "Hotspot" || it.kind == "AstraDrop network" }

    private fun renderNetworks() {
        val wifi = addrs.firstOrNull { it.kind == "Wi-Fi" }
        val hot = addrs.firstOrNull { it.kind == "Hotspot" || it.kind == "AstraDrop network" }
        val cur = addrs.firstOrNull { it.ip == ShareState.host }
        val engine = AutoNfc.enabled(this) && AutoNfc.setUp(this)
        wifiTile.set(if (wifi != null) wifiName ?: t("Connected") else t("Not connected · Tap to join"),
            active = wifi != null && cur == wifi, warn = false, dim = wifi == null)
        val hotName = if (OwnNetwork.active) OwnNetwork.ssid else savedHotspot()?.first
        val known = hotName != null && !OwnNetwork.active && Visits.joined(this, hotName)
        hotTile.set(
            when {
                AutoNfc.hotspotBusy || OwnNetwork.starting -> t("Turning on…")
                hot != null -> (hotName ?: t("On")) + if (known) " · " + t("iPhone knows it") else ""
                engine -> t("Off · Tap to turn on")
                else -> t("Off · Tap to set up")
            },
            active = hot != null && cur == hot, warn = false, dim = hot == null && !AutoNfc.hotspotBusy,
        )
        // anything else (Ethernet, VPN...) only when it's the one in use
        val other = cur?.takeIf { it.kind == "Other" }
        netHint.text = when {
            cur == null -> t("Join a Wi-Fi network, or tap Hotspot.")
            cur == hot && OwnNetwork.active -> t("The iPhone joins “%s” by scanning its Wi-Fi code (tap Hotspot).").format(OwnNetwork.ssid)
            cur == hot && known -> t("The iPhone has joined “%s” before and connects by itself.").format(hotName)
            cur == hot -> if (hotName != null) t("The iPhone must be connected to “%s”. Tap Hotspot for a code it can scan.").format(hotName)
                else t("The iPhone must be connected to this phone's hotspot. Tap Hotspot for a code it can scan.")
            cur == wifi -> t("The iPhone must be on the same Wi-Fi as this phone.")
            other != null -> t("Sharing over %s. The iPhone must be on the same network.").format(other.iface)
            else -> t("The iPhone must be on the same network as this phone.")
        }
    }

    private fun onWifiTile() {
        val wifi = addrs.firstOrNull { it.kind == "Wi-Fi" }
        if (wifi == null) { Nav.wifi(this); return }
        ShareState.host = wifi.ip
        ShareState.notifyChanged()
        // back on Wi-Fi: the hotspot (or quick network) isn't needed any more
        if (OwnNetwork.active) OwnNetwork.stop()
        if (hotspotOn() && AutoNfc.enabled(this) && AutoNfc.setUp(this)) AutoNfc.hotspotOff(this) { refreshNetworks(); render() }
    }

    /** Use the hotspot once it has an address (that takes a moment after switching on). */
    private fun preferHotspot(tries: Int = 20) {
        refreshNetworks()
        val hot = addrs.firstOrNull { it.kind == "Hotspot" || it.kind == "AstraDrop network" }
        if (hot != null) { ShareState.host = hot.ip; ShareState.notifyChanged(); return }
        if (tries > 0) main.postDelayed({ preferHotspot(tries - 1) }, 500)
    }

    /**
     * Hotspot tile. Auto mode: switch on the phone's own hotspot and show its Wi-Fi code (the iPhone
     * remembers it, so the code is needed only once). Without it: a code for a hotspot that's
     * already on, or AstraDrop's quick network (new name and password every time).
     */
    private fun onHotspotTile(forceCode: Boolean = false) {
        val engine = AutoNfc.enabled(this) && AutoNfc.setUp(this)
        when {
            AutoNfc.hotspotBusy || OwnNetwork.starting -> {}
            OwnNetwork.active -> showOwnNetQr()
            hotspotOn() -> {
                preferHotspot(0)
                val saved = savedHotspot()
                when {
                    saved == null -> if (engine) Thread {
                        val info = AutoNfc.hotspotInfo(this)
                        main.post { if (info != null) { saveHotspot(info.ssid, info.pass); offerHotspotCode(info.ssid, info.pass, forceCode) } else askHotspotDetails() }
                    }.start() else askHotspotDetails()
                    else -> offerHotspotCode(saved.first, saved.second, forceCode)
                }
            }
            engine -> {
                AutoNfc.hotspotOn(this) { info, err ->
                    if (info == null) {
                        toast(err ?: t("Couldn't turn the hotspot on"))
                        render()
                        return@hotspotOn
                    }
                    saveHotspot(info.ssid, info.pass)
                    if (!ShareState.running) { userStopped = false; startServer() }
                    preferHotspot()
                    offerHotspotCode(info.ssid, info.pass, forceCode)
                }
                render()
            }
            else -> ui.dialog(t("Use a hotspot"),
                t("The iPhone can join this phone instead of Wi-Fi.") + "\n\n" +
                    t("Quick network: AstraDrop makes one just for the iPhone, nothing to type. Android picks a new name and password each time, so the iPhone scans a code every time.") + "\n\n" +
                    t("Your own hotspot: the iPhone remembers it after scanning once. Turn it on in Settings and come back. Auto mode can turn it on for you."),
                positive = t("Quick network"), negative = t("Hotspot settings"),
                onNegative = { Nav.hotspot(this) }, onPositive = { startOwnNetwork() })
        }
    }

    /** An iPhone that joined this hotspot before connects by itself: no code unless asked (long press). */
    private fun offerHotspotCode(ssid: String, pass: String, force: Boolean) {
        if (!force && Visits.joined(this, ssid)) {
            toast(t("The iPhone joins “%s” by itself. Long-press Hotspot for its code.").format(ssid))
            return
        }
        showHotspotQr(ssid, pass)
    }

    private fun savedHotspot(): Pair<String, String>? {
        val pr = getSharedPreferences("tapdrop", MODE_PRIVATE)
        val ssid = pr.getString("ssid", null)?.takeIf { it.isNotBlank() } ?: return null
        return ssid to (pr.getString("pass", "") ?: "")
    }

    private fun saveHotspot(ssid: String, pass: String) {
        val old = savedHotspot()
        // a new password means the iPhone has to scan again
        if (old != null && (old.first != ssid || old.second != pass)) Visits.forgetJoined(this, old.first)
        getSharedPreferences("tapdrop", MODE_PRIVATE).edit().putString("ssid", ssid).putString("pass", pass).apply()
    }

    /** Without Auto mode Android doesn't tell apps the hotspot's password: ask once. */
    private fun askHotspotDetails() {
        val saved = savedHotspot()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = field(t("Hotspot name")).apply { setText(saved?.first ?: "") }
        val pass = field(t("Hotspot password")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(saved?.second ?: "")
        }
        box.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, 50.dp))
        box.addView(pass, LinearLayout.LayoutParams(MATCH_PARENT, 50.dp).apply { topMargin = 8.dp })
        ui.dialog(t("Your hotspot"),
            t("Enter its name and password once (you'll find them in Settings → Hotspot). AstraDrop turns them into a code the iPhone can scan."),
            content = box, positive = t("Show code"), negative = t("Hotspot settings"),
            onNegative = { Nav.hotspot(this) },
            onPositive = {
                val s = name.text.toString().trim()
                if (s.isEmpty()) toast(t("Enter the hotspot name first"))
                else { saveHotspot(s, pass.text.toString()); showHotspotQr(s, pass.text.toString()) }
            })
    }

    private fun showHotspotQr(ssid: String, pass: String) {
        showQrDialog(OwnNetwork.wifiQr(ssid, pass), t("Join “%s”").format(ssid),
            t("On the iPhone, open the Camera, point it at this code and tap Join. It remembers this hotspot, so next time it joins by itself."),
            negative = t("Edit"), onNegative = { askHotspotDetails() }.takeIf { !(AutoNfc.enabled(this) && AutoNfc.setUp(this)) })
    }

    private fun startOwnNetwork() {
        if (OwnNetwork.active || OwnNetwork.starting) return
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), REQ_NEARBY)
            return
        }
        OwnNetwork.start(this) { err ->
            if (err != null) toast(t(err))
            else {
                if (!ShareState.running) { userStopped = false; startServer() }
                preferHotspot()
                OwnNetwork.ssid?.let { showOwnNetQr() }
            }
            render()
        }
        render()
    }

    private fun showOwnNetQr() {
        val ssid = OwnNetwork.ssid ?: return
        showQrDialog(OwnNetwork.wifiQr(ssid, OwnNetwork.pass), t("Join “%s”").format(ssid),
            t("On the iPhone, open the Camera, point it at this code and tap Join. Then tap Scan or hold the phones together."),
            negative = t("Turn off"), onNegative = { OwnNetwork.stop(); main.postDelayed({ refreshNetworks(); render() }, 500) })
    }

    /** Quick Settings tile: ready to share; with Auto mode the hotspot goes on too (NFC already does). */
    private fun quickStart() {
        showTab(0)
        userStopped = false
        startServer()
        if (AutoNfc.enabled(this) && AutoNfc.setUp(this) && !hotspotOn()) main.postDelayed({ onHotspotTile() }, 300)
    }

    @Deprecated("framework permission API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_NEARBY -> if (ok) startOwnNetwork() else toast(t("AstraDrop needs Nearby devices to create a network"))
        }
    }

    private fun renderSendGrid() {
        val items = ShareState.items.toList()
        val texts = ShareState.texts.toList()
        val n = items.size + texts.size
        sendTitle.text = if (n == 0) t("To the iPhone") else t("To the iPhone") + "  ·  $n"
        clearBtn.visibility = if (n == 0) View.GONE else View.VISIBLE
        sendHint.visibility = if (n == 0) View.VISIBLE else View.GONE
        val textTiles = texts.map { st ->
            textTile(st.text, onRemove = { ShareState.texts.removeAll { it.id == st.id }; ShareState.notifyChanged() }) { showText(st.text, null) }
        }
        val tiles = textTiles + items.map { item ->
            mediaTile(item.orig ?: item.uri, item.origMime ?: item.mime, item.name, onRemove = {
                ShareState.items.removeAll { it.id == item.id }
                ThumbStore.forget(item.uri)
                RawConverter.forget(item)
                Compressor.forget(item)
                ShareState.notifyChanged()
            }, rawId = item.id, size = item.size, onClick = { openViewer(item.orig ?: item.uri, item.origMime ?: item.mime) })
        } + addTile(t("Photos")) { pickMedia() } + addTile(t("Files")) { pickFiles() } + addTile(t("Text")) { askText() }
        fillGrid(sendGrid, tiles)
    }

    private var recvKey = ""
    private var pruned = false

    /** Everything the iPhone has sent (kept between sessions): the newest 9 here, the rest under All. */
    private fun renderRecvGrid() {
        if (!pruned) { pruned = true; Thread { History.prune(applicationContext) }.start() }
        val got = History.list(this)
        val key = got.size.toString() + ":" + got.firstOrNull()?.time
        if (key == recvKey) return
        recvKey = key
        recvTitle.text = if (got.isEmpty()) t("From the iPhone") else t("From the iPhone") + "  ·  ${got.size}"
        recvHint.visibility = if (got.isEmpty()) View.VISIBLE else View.GONE
        recvGrid.visibility = if (got.isEmpty()) View.GONE else View.VISIBLE
        historyAllBtn?.visibility = if (got.size > RECV_SHOWN) View.VISIBLE else View.GONE
        historyClearBtn?.visibility = if (got.isEmpty()) View.GONE else View.VISIBLE
        fillGrid(recvGrid, got.take(RECV_SHOWN).map { historyTile(it) })
    }

    private fun historyTile(e: History.Entry): View {
        val tile = if (e.isText) textTile(e.text, null) { showText(e.text, e) }
        else mediaTile(e.uri!!, e.mime, e.name, null) { openViewer(e.uri, e.mime) }
        tile.setOnLongClickListener { historyMenu(e); true }
        return tile
    }

    /** Long press on something received: open it, share it on, or take it off the list. */
    private fun historyMenu(e: History.Entry) {
        val opts = listOf(t("Open"), t("Share"), t("Remove from the list"))
        ui.choose(if (e.isText) t("Text") else e.name, opts, -1) { i ->
            when (i) {
                0 -> if (e.isText) showText(e.text, e) else openViewer(e.uri!!, e.mime)
                1 -> {
                    val send = Intent(Intent.ACTION_SEND)
                    if (e.isText) send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.text)
                    else send.setType(e.mime).putExtra(Intent.EXTRA_STREAM, e.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(Intent.createChooser(send, null)) } catch (_: Exception) {}
                }
                2 -> History.remove(this, e)
            }
        }
    }

    /** All received, newest first, as a list. */
    private fun showHistory() {
        val got = History.list(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for (e in got) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4.dp, 10.dp, 4.dp, 10.dp)
                background = ripple(null, 12.dp)
                setOnClickListener { dlg?.dismiss(); if (e.isText) showText(e.text, e) else openViewer(e.uri!!, e.mime) }
                setOnLongClickListener { dlg?.dismiss(); historyMenu(e); true }
            }
            row.addView(TextView(this).apply {
                text = if (e.isText) e.text.replace('\n', ' ') else e.name
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 15f; setTextColor(p.fg); typeface = fontMedium
            })
            row.addView(TextView(this).apply {
                text = fmt.format(java.util.Date(e.time)) + (if (e.from.isNotEmpty()) "  ·  " + t(e.from) else "")
                textSize = 12.5f; setTextColor(p.sub); typeface = fontRegular
            })
            list.addView(row)
        }
        dlg = ui.dialog(t("Received"), null, content = list, positive = "Done", negative = "Clear the list", onNegative = { askClearHistory() })
    }

    /** Empties the received list (the files themselves stay in Gallery and Downloads). */
    private fun askClearHistory() {
        ui.dialog(t("Clear the list?"), t("The files stay in your Gallery and Downloads; only this list is emptied."),
            positive = "Clear", negative = "Cancel", onPositive = { History.clear(this) })
    }

    /** A text: shown in full, with Copy (and Open for a link). */
    private fun showText(text: String, e: History.Entry?) {
        val body = TextView(this).apply {
            this.text = text
            textSize = 15.5f; setTextColor(p.fg); typeface = fontRegular
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
        }
        val link = linkIn(text)
        ui.dialog(if (e != null) t("Text from %s").format(t(e.from.ifEmpty { "iPhone" })) else t("Text"), null, content = body,
            positive = "Copy", negative = if (link != null) "Open" else "Close",
            onPositive = {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AstraDrop", text))
                toast(t("Copied"))
            },
            onNegative = { if (link != null) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: Exception) {} })
    }

    /** A square tile showing the start of a text. */
    private fun textTile(text: String, onRemove: (() -> Unit)?, onClick: () -> Unit): View {
        val tile = SquareFrame(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14.dp.toFloat()
                setColor(p.tile)
                setStroke(1.dp, p.stroke)
            }
            clipToOutline = true
            foreground = ripple(null, 14.dp)
            setOnClickListener { onClick() }
        }
        tile.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f; setTextColor(p.fg); typeface = fontRegular
            maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tile.addView(label("Aa", 11f, Color.WHITE, bold = true).apply {
            background = rounded(Color.argb(150, 0, 0, 0), 10.dp)
            setPadding(7.dp, 2.dp, 7.dp, 2.dp)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(6.dp, 0, 0, 6.dp) })
        if (onRemove != null) {
            tile.addView(label("✕", 12f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = oval(Color.argb(150, 0, 0, 0))
                setOnClickListener { onRemove() }
            }, FrameLayout.LayoutParams(26.dp, 26.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 6.dp, 6.dp, 0) })
        }
        return tile
    }

    private fun openViewer(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            toast(t("No app can open this file"))
        }
    }

    private fun setPulse(on: Boolean) {
        if (on && pulseAnim == null) {
            pulse.visibility = View.VISIBLE
            fun anim(prop: String, vararg v: Float) = ObjectAnimator.ofFloat(pulse, prop, *v).apply {
                repeatCount = ValueAnimator.INFINITE
                duration = 1800
            }
            pulseAnim = AnimatorSet().apply {
                playTogether(anim("scaleX", 1f, 1.5f), anim("scaleY", 1f, 1.5f), anim("alpha", 0.9f, 0f))
                interpolator = DecelerateInterpolator()
                start()
            }
        } else if (!on) {
            pulseAnim?.cancel()
            pulseAnim = null
            pulse.visibility = View.INVISIBLE
        }
    }

    // ================================================================== QR

    private fun qrBitmap(text: String): Bitmap {
        val size = 720
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * [live]: the link changes once a phone has used it (each one works once), so the code is
     * redrawn, and the dialog closes when a new phone has joined with it.
     */
    private fun showQrDialog(text: String, title: String, message: String, negative: String? = null, onNegative: (() -> Unit)? = null, live: (() -> String?)? = null) {
        val bmp = try { qrBitmap(text) } catch (e: Exception) { toast("QR failed: ${e.message}"); return }
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            background = rounded(Color.WHITE, 18.dp)
        }
        val d = ui.dialog(title, message, content = img, positive = "Done", negative = negative.takeIf { onNegative != null }, onNegative = onNegative)
        if (live != null) {
            var shown = text
            val before = Devices.all().size
            val l: () -> Unit = {
                val u = live()
                if (Devices.all().size > before) d.dismiss()
                else if (u != null && u != shown) {
                    shown = u
                    try { img.setImageBitmap(qrBitmap(u)) } catch (_: Exception) {}
                }
            }
            ShareState.addListener(l)
            d.setOnDismissListener { ShareState.removeListener(l) }
        }
    }

    // ================================================================== layout

    private fun triggerTapEffect() {
        tapRipple.play(rootFrame)
        iconKick?.cancel()
        val up = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(iconWrap, "scaleX", 1f, 1.15f),
                ObjectAnimator.ofFloat(iconWrap, "scaleY", 1f, 1.15f),
            )
            duration = 110
            interpolator = DecelerateInterpolator()
        }
        val down = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(iconWrap, "scaleX", 1.15f, 1f),
                ObjectAnimator.ofFloat(iconWrap, "scaleY", 1.15f, 1f),
            )
            duration = 230
            interpolator = android.view.animation.OvershootInterpolator(2.2f)
        }
        iconKick = AnimatorSet().apply {
            playSequentially(up, down)
            start()
        }
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply {
            // pages lie over each other, so the incoming one shows at once while it slides in
            clipChildren = false
            clipToPadding = false
        }
        // SolCube's sections: the bottom pill switches between Share and Settings
        val sharePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val setPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        root.addView(sharePage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(setPage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // same title as SolCube: 42sp bold, pinned over the page (it floats up a little and the
        // page blurs under it as you scroll); see pinnedBackdropLayout
        val title = label("AstraDrop", 42f, p.fg).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        }

        // ---- status: what's going on, and the two ways the iPhone connects
        val hero = card().apply { clipChildren = false; clipToPadding = false }
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        iconWrap = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setOnClickListener { triggerTapEffect() }
        }
        pulse = View(this).apply { visibility = View.INVISIBLE }
        iconBg = View(this).apply {
            // a raised glass disc like SolCube's chevron badges
            elevation = if (isDark()) 4.dp.toFloat() else 5.dp.toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height); o.alpha = 1f }
            }
            outlineAmbientShadowColor = if (isDark()) Color.argb(120, 0, 0, 0) else Color.argb(85, 30, 24, 18)
            outlineSpotShadowColor = if (isDark()) Color.argb(230, 0, 0, 0) else Color.argb(185, 30, 24, 18)
        }
        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_tap); scaleType = ImageView.ScaleType.CENTER
            outlineProvider = null; elevation = 6.dp.toFloat()   // above the raised disc, no shadow of its own
        }
        iconWrap.addView(pulse, FrameLayout.LayoutParams(64.dp, 64.dp))
        iconWrap.addView(iconBg, FrameLayout.LayoutParams(64.dp, 64.dp))
        iconWrap.addView(icon, FrameLayout.LayoutParams(64.dp, 64.dp))
        heroRow.addView(iconWrap, LinearLayout.LayoutParams(64.dp, 64.dp))
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 0, 0, 0) }
        heroTitle = label("", 21f, p.fg).apply { typeface = fontBold }
        heroSub = label("", 14.5f, p.sub).apply { setPadding(0, 3.dp, 0, 0); setLineSpacing(0f, 1.15f) }
        heroText.addView(heroTitle)
        heroText.addView(heroSub)
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hero.addView(heroRow)

        // what's moving right now (only while something is)
        transferBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        transferText = label("", 13.5f, p.fg, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        transferBox.addView(transferText)
        transferBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(p.link)
            progressBackgroundTintList = ColorStateList.valueOf(alpha(p.fg, 0.15f))
        }
        transferBox.addView(transferBar, LinearLayout.LayoutParams(MATCH_PARENT, 6.dp).apply { topMargin = 6.dp })
        hero.addView(transferBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        hero.addView(devBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })

        primaryBtn = ui.button("Start sharing", filled = true, size = 17f, radius = 28) { onPrimary() }
        hero.addView(primaryBtn, LinearLayout.LayoutParams(MATCH_PARENT, 54.dp).apply { topMargin = 18.dp })

        val ways = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        tapWay = WayTile(Glyph.TAP, "Tap") { onTap() }
        scanWay = WayTile(Glyph.QR, "Scan") { onScan() }
        tapWay.setOnLongClickListener { tapHelp(); true }
        ways.addView(tapWay, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        ways.addView(scanWay, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = 10.dp })
        hero.addView(ways, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })

        // small glass pills (SolCube style): readable over any part of the wallpaper, one line each
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
        }
        actions.addView(smallPill("Copy link") {
            val u = ShareState.url()
            if (u == null) toast("Sharing hasn't started yet") else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AstraDrop link", u))
                toast(t("Link copied. It opens once, on one phone."))
            }
        }, rowPillParams(first = true))
        actions.addView(smallPill("New link") {
            ui.dialog("Make a new link?", "Phones that have the current link can't open it anymore. Anyone who should still have access taps or scans again.",
                positive = "New link", negative = "Cancel", onPositive = {
                    ShareState.rotateToken()
                    toast("New link made")
                })
        }, rowPillParams())
        hero.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        // SolCube's first card sits 100dp lower than its title gap (its "Can't find a feature?" pill row)
        sharePage.addView(hero, cardParams().apply { topMargin = 100.dp })

        // ---- network
        val net = card()
        net.addView(sectionTitle("Both phones on one network"))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        wifiTile = WayTile(Glyph.WIFI, "Wi-Fi") { onWifiTile() }
        hotTile = WayTile(Glyph.HOTSPOT, "Hotspot") { onHotspotTile() }
        hotTile.setOnLongClickListener { onHotspotTile(forceCode = true); true }
        tiles.addView(wifiTile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        tiles.addView(hotTile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = 10.dp })
        net.addView(tiles, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        netHint = label("", 14f, p.sub).apply { setPadding(2.dp, 12.dp, 0, 0); setLineSpacing(0f, 1.15f) }
        net.addView(netHint)
        sharePage.addView(net, cardParams())

        // ---- to the iPhone
        val send = card()
        val sendHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        sendTitle = sectionTitle("To the iPhone")
        sendHead.addView(sendTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clearBtn = smallPill("Remove all") {
            ShareState.items.forEach { ThumbStore.forget(it.uri); RawConverter.forget(it); Compressor.forget(it) }
            ShareState.items.clear()
            clearCache()
            ShareState.texts.clear()
            ShareState.notifyChanged()
        }
        sendHead.addView(clearBtn, pillParams(first = true))
        send.addView(sendHead)
        sendHint = label("Add photos or files, or share them from Gallery with “Send to iPhone”. On the iPhone, big photos can be shrunk to a smaller copy before saving (Compress). The iPhone can send you files even when this is empty.", 14f, p.sub).apply {
            setPadding(0, 8.dp, 0, 4.dp); setLineSpacing(0f, 1.15f)
        }
        send.addView(sendHint)
        sendGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        send.addView(sendGrid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        sharePage.addView(send, cardParams())

        // ---- from the iPhone
        val recv = card()
        recvTitle = sectionTitle("From the iPhone")
        val recvHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        recvHead.addView(recvTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        historyAllBtn = smallPill("Show all") { showHistory() }.also { it.visibility = View.GONE }
        recvHead.addView(historyAllBtn, pillParams(first = true))
        historyClearBtn = smallPill("Clear") { askClearHistory() }.also { it.visibility = View.GONE }
        recvHead.addView(historyClearBtn, pillParams())
        recv.addView(recvHead)
        recvHint = label("Photos, videos, files and texts the iPhone sends show up here (photos also in your Gallery under AstraDrop). Touch and hold one for more.", 14f, p.sub).apply {
            setPadding(0, 4.dp, 0, 0); setLineSpacing(0f, 1.15f)
        }
        recv.addView(recvHint)
        recvGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recv.addView(recvGrid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        sharePage.addView(recv, cardParams())

        // ---- Auto mode (optional extra, needs the one-time engine setup)
        val auto = card()
        val autoHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        val autoCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(sectionTitle("Auto mode"))
        titleRow.addView(label("EXTRA", 10.5f, p.link, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(8.dp, 3.dp, 8.dp, 3.dp)
            background = rounded(alpha(p.link, 0.14f), 10.dp)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp })
        autoCol.addView(titleRow)
        autoText = label("", 14f, p.sub).apply { setPadding(0, 4.dp, 0, 0); setLineSpacing(0f, 1.15f) }
        autoCol.addView(autoText)
        autoHead.addView(autoCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        autoBtn = ui.button("", filled = true, size = 15f, radius = 20) { onAutoButton() }
        autoHead.addView(autoBtn, LinearLayout.LayoutParams(WRAP_CONTENT, 40.dp).apply { leftMargin = 12.dp })
        auto.addView(autoHead)
        autoNote = label("", 13.5f, p.warn).apply {
            setPadding(0, 10.dp, 0, 0); setLineSpacing(0f, 1.15f)
            setOnClickListener { onAutoNote() }
        }
        auto.addView(autoNote)
        setPage.addView(auto, cardParams().apply { topMargin = 100.dp })

        // ---- look & help
        val look = card()
        val themeHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        themeHead.addView(sectionTitle("Theme"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val modeNames = arrayOf(t("Light"), t("Follows the system"), t("Dark"))
        val themeVal = label(modeNames[Look.themeMode(this)], 13f, p.sub)
        themeHead.addView(themeVal)
        look.addView(themeHead)
        look.addView(ThemeSlider(Look.themeMode(this)) { mode ->
            themeVal.text = modeNames[mode]
            if (mode != Look.themeMode(this)) {
                val wasDark = isDark()
                Look.setThemeMode(this, mode)
                // let the thumb finish sliding, then rebuild the screen in the new colours
                if (Look.isDark(this) != wasDark) main.postDelayed({ recreate() }, 260)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 44.dp).apply { topMargin = 12.dp })
        val helpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        helpRow.addView(smallPill("Replay the tour") { startActivity(Intent(this, TourActivity::class.java)) }, rowPillParams(first = true))
        helpRow.addView(smallPill("Auto mode setup") { startActivity(Intent(this, SetupActivity::class.java)) }, rowPillParams())
        look.addView(settingRow(t("App language"), { I18n.name(I18n.saved(this)) }) {
            ui.choose(t("App language"), I18n.CHOICES.map { I18n.name(it) }, I18n.CHOICES.indexOf(I18n.saved(this))) { i ->
                I18n.setApp(this, I18n.CHOICES[i]); recreate()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })
        look.addView(settingRow(t("iPhone page language"), { I18n.webName(I18n.savedWeb(this)) }) {
            val opts = I18n.WEB_CHOICES
            ui.choose(t("iPhone page language"), opts.map { I18n.webName(it) }, opts.indexOf(I18n.savedWeb(this))) { i ->
                I18n.setWeb(this, opts[i]); render()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        look.addView(settingRow(t("Stop sharing when idle"), { idleName(Idle.minutes(this)) }) {
            ui.choose(t("Stop sharing when idle"), Idle.CHOICES.map { idleName(it) }, Idle.CHOICES.indexOf(Idle.minutes(this))) { i ->
                Idle.set(this, Idle.CHOICES[i]); render()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        look.addView(label("After this long with nothing happening, sharing stops by itself (never while AstraDrop is on screen).", 12.5f, p.sub).apply {
            setPadding(2.dp, 6.dp, 0, 0); setLineSpacing(0f, 1.12f)
        })
        fun onOff(v: Boolean) = if (v) t("On") else t("Off")
        look.addView(settingRow(t("Stop when it has everything"), { onOff(DoneStop.on(this)) }) {
            ui.choose(t("Stop when it has everything"), listOf(t("On"), t("Off")), if (DoneStop.on(this)) 0 else 1) { i ->
                DoneStop.set(this, i == 0); render()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        look.addView(label("Sharing stops by itself once the iPhone has every shared file.", 12.5f, p.sub).apply {
            setPadding(2.dp, 6.dp, 0, 0); setLineSpacing(0f, 1.12f)
        })
        look.addView(settingRow(t("Ask before a device joins"), { onOff(Ask.on(this)) }) {
            ui.choose(t("Ask before a device joins"), listOf(t("On"), t("Off")), if (Ask.on(this)) 0 else 1) { i ->
                Ask.set(this, i == 0); render()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        look.addView(label("Each tap or code works once, for one phone. With this on, you also allow each new phone here before it sees anything.", 12.5f, p.sub).apply {
            setPadding(2.dp, 6.dp, 0, 0); setLineSpacing(0f, 1.12f)
        })
        look.addView(helpRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16.dp })
        setPage.addView(look, cardParams())

        // on its own glass pill so it reads over the wallpaper
        sharePage.addView(FrameLayout(this).apply {
            addView(label("Keep AstraDrop open while the iPhone connects. Swipe it away to stop sharing.", 12.5f, p.sub).apply {
                gravity = Gravity.CENTER
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
                background = glass(16.dp, level = 0)
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        setPage.addView(label("AstraDrop $ver", 12f, p.sub).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })

        // ---- sections: SolCube's bottom pill and its slide between pages
        val pages = listOf(sharePage, setPage)
        val tabs = listOf(
            BottomTab("share", t("Share"), BottomNavPill.ICON_SHARE),
            BottomTab("settings", t("Settings"), BottomNavPill.ICON_SETTINGS),
        )
        activeTab = getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0).coerceIn(0, pages.lastIndex)
        pages.forEachIndexed { i, v -> v.visibility = if (i == activeTab) View.VISIBLE else View.GONE }
        val pill = BottomNavPill(this, p, fontMedium, isDark())
        val pillLp = FrameLayout.LayoutParams(236.dp, 54.dp).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 14.dp
        }
        var tabAnim: ValueAnimator? = null
        val tabInterp = android.view.animation.PathInterpolator(0.16f, 1f, 0.30f, 1f)
        fun resetPage(v: View) { v.translationX = 0f; v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f }

        fun selectTab(newIdx: Int, animate: Boolean = true) {
            val target = newIdx.coerceIn(0, pages.lastIndex)
            if (target == activeTab) return
            val oldIdx = activeTab
            pageScrollY[oldIdx] = scrollView.scrollY
            activeTab = target
            getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", target).apply()
            tabAnim?.cancel()
            tabAnim = null
            pages.forEach { it.animate().cancel() }
            val outPage = pages[oldIdx]
            val inPage = pages[target]
            inPage.visibility = View.VISIBLE
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
                outPage.visibility = View.GONE
                resetPage(outPage); resetPage(inPage)
            } else {
                // the old page slides out under the new one (both laid over each other meanwhile)
                val dir = if (target > oldIdx) 1f else -1f
                val travel = 48.dp.toFloat()
                outPage.visibility = View.VISIBLE
                var cancelled = false
                tabAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 440L
                    interpolator = tabInterp
                    addUpdateListener { a ->
                        val f = a.animatedValue as Float
                        inPage.translationX = dir * travel * (1f - f)
                        inPage.alpha = f
                        val inScale = 0.97f + 0.03f * f
                        inPage.scaleX = inScale; inPage.scaleY = inScale
                        val outF = (f * 1.15f).coerceIn(0f, 1f)
                        outPage.translationX = -dir * travel * 0.7f * outF
                        outPage.alpha = 1f - outF
                        val outScale = 1f - 0.03f * outF
                        outPage.scaleX = outScale; outPage.scaleY = outScale
                        pill.invalidateBackdrop()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!cancelled) {
                                outPage.visibility = View.GONE
                                resetPage(outPage); resetPage(inPage)
                                tabAnim = null
                                pill.invalidateBackdrop()
                            }
                        }
                    })
                    start()
                }
            }
            scrollView.post {
                scrollView.scrollTo(0, pageScrollY[target])
                pill.invalidateBackdrop()
            }
        }
        pill.bind(tabs, activeTab) { idx -> selectTab(idx) }
        showTab = { idx -> selectTab(idx); pill.select(idx) }

        // SolCube's numbers (its build(): title 22dp in, 28dp down, 22dp above the page)
        val screen = pinnedBackdropLayout(
            titleView = title,
            body = root,
            titleLeftPx = 22.dp,
            titleTopPx = 28.dp,
            titleBottomGapPx = 22.dp,
            bodySidePadPx = 16.dp,
            bodyBottomPadPx = 92.dp,
            blurBelowTitlePx = 12.dp,
            fadeBelowTitlePx = 0,
            liftPx = 18.dp,
            liftStartPx = 44.dp,
            liftSpanPx = 110.dp,
            container = SwipeTabFrame(
                canSwipeAt = { ev -> pill.height == 0 || ev.y < pill.top },
                onSwipe = { delta ->
                    val next = (activeTab + delta).coerceIn(0, pages.lastIndex)
                    if (next != activeTab) { selectTab(next); pill.select(next) }
                },
            ),
            onScrollChange = { pill.invalidateBackdrop() },
            onLayersReady = { bd, sw ->
                bd.register(pill)
                pill.setBackdropSources { listOf(bd, sw) }
            },
            onBottomInset = { b ->
                val want = b + 14.dp
                if (pillLp.bottomMargin != want) { pillLp.bottomMargin = want; pill.layoutParams = pillLp }
            },
        )
        screen.addView(pill, pillLp)
        return screen
    }

    /** SolCube's sideways swipe between sections (not over the bottom pill itself). */
    private inner class SwipeTabFrame(
        private val canSwipeAt: (android.view.MotionEvent) -> Boolean,
        private val onSwipe: (delta: Int) -> Unit,
    ) : FrameLayout(this) {
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private val minSwipePx = 52.dp.toFloat()

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { tracking = canSwipeAt(ev); downX = ev.x; downY = ev.y }
                android.view.MotionEvent.ACTION_MOVE -> if (tracking) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (kotlin.math.abs(dx) > minSwipePx && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.8f) return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> tracking = false
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_UP -> if (tracking) {
                    tracking = false
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (kotlin.math.abs(dx) > minSwipePx && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) onSwipe(if (dx < 0f) 1 else -1)
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> tracking = false
            }
            return super.onTouchEvent(ev)
        }
    }

    // ================================================================== way & network tiles

    private enum class Glyph { TAP, QR, WIFI, HOTSPOT }

    /** Small line icons drawn in code, in the tile's text colour. */
    private inner class GlyphView(private val g: Glyph) : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = 2.dp.toFloat()
        }
        private val r = android.graphics.RectF()
        var color = p.fg
            set(v) { field = v; invalidate() }

        override fun onDraw(c: android.graphics.Canvas) {
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            paint.color = color
            paint.style = android.graphics.Paint.Style.STROKE
            when (g) {
                Glyph.TAP -> for (i in 0..2) {
                    val rr = s * (0.16f + i * 0.15f)
                    r.set(cx - s * 0.22f - rr, cy - rr, cx - s * 0.22f + rr, cy + rr)
                    c.drawArc(r, -50f, 100f, false, paint)
                }
                Glyph.QR -> {
                    // three finder squares with their centre dots, and a few data modules
                    val u = s * 0.11f
                    val o = s * 0.44f
                    for ((x, y) in listOf(-1 to -1, 1 to -1, -1 to 1)) {
                        val fx = cx + x * (o - u * 1.5f)
                        val fy = cy + y * (o - u * 1.5f)
                        paint.style = android.graphics.Paint.Style.STROKE
                        r.set(fx - u * 1.5f, fy - u * 1.5f, fx + u * 1.5f, fy + u * 1.5f)
                        c.drawRoundRect(r, u * 0.6f, u * 0.6f, paint)
                        paint.style = android.graphics.Paint.Style.FILL
                        c.drawRect(fx - u * 0.5f, fy - u * 0.5f, fx + u * 0.5f, fy + u * 0.5f, paint)
                    }
                    paint.style = android.graphics.Paint.Style.FILL
                    for ((mx, my) in listOf(1 to 1, 3 to 1, 2 to 2, 1 to 3, 3 to 3)) {
                        val x0 = cx + (mx - 1) * u * 1.1f
                        val y0 = cy + (my - 1) * u * 1.1f
                        c.drawRect(x0, y0, x0 + u * 0.9f, y0 + u * 0.9f, paint)
                    }
                }
                Glyph.WIFI -> {
                    for (i in 1..3) {
                        val rr = s * 0.15f * i
                        r.set(cx - rr, cy + s * 0.22f - rr, cx + rr, cy + s * 0.22f + rr)
                        c.drawArc(r, 225f, 90f, false, paint)
                    }
                    paint.style = android.graphics.Paint.Style.FILL
                    c.drawCircle(cx, cy + s * 0.22f, 2.dp.toFloat(), paint)
                }
                Glyph.HOTSPOT -> {
                    for (i in 1..2) {
                        val rr = s * 0.17f * i
                        r.set(cx - rr, cy - rr, cx + rr, cy + rr)
                        c.drawArc(r, 205f, 130f, false, paint)
                        c.drawArc(r, 25f, 130f, false, paint)
                    }
                    paint.style = android.graphics.Paint.Style.FILL
                    c.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, paint)
                }
            }
        }
    }

    /**
     * A glass tile with an icon, a name and one line of state. Never hidden: greyed out when it
     * can't work right now (still tappable: it then says or opens what's missing); a gold rim
     * when it's the one in use.
     */
    private inner class WayTile(g: Glyph, name: String, onClick: () -> Unit) : LinearLayout(this) {
        private val glyph = GlyphView(g)
        private val line = label("", 12.5f, p.sub).apply { maxLines = 2; setLineSpacing(0f, 1.1f) }
        private var key = ""

        init {
            orientation = VERTICAL
            setPadding(14.dp, 12.dp, 12.dp, 12.dp)
            clipChildren = false
            clipToPadding = false
            addView(glyph, LayoutParams(26.dp, 26.dp))
            addView(label(name, 15.5f, p.fg, bold = true), LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            addView(line, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 1.dp })
            foreground = ripple(null, 18.dp)
            setOnClickListener { onClick() }
            set("", active = false, warn = false, dim = false)
        }

        fun set(text: String, active: Boolean, warn: Boolean, dim: Boolean) {
            if (line.text.toString() != text) line.text = text
            line.setTextColor(if (warn) p.warn else p.sub)
            glyph.color = if (active) p.link else p.fg
            alpha = if (dim) 0.5f else 1f
            val k = "$active"
            if (k != key) {
                key = k
                // new glass only when the rim changes (each pane compiles a shader)
                background = if (active) ui.glass(18.dp, ui.alpha(p.link, if (isDark()) 0.10f else 0.08f), level = 1, stroke = ui.alpha(p.link, 0.7f))
                    else ui.glass(18.dp, level = 1, stroke = p.btnStroke)
            }
        }
    }

    // ================================================================== theme switch (SolCube's)

    /** Light / System / Dark: a sliding gold thumb you can tap or drag. */
    private inner class ThemeSlider(initial: Int, private val onPick: (Int) -> Unit) : View(this) {
        private val labels = arrayOf(t("Light"), t("System"), t("Dark"))
        private val dd = resources.displayMetrics.density
        private var mode = initial.coerceIn(0, 2)
        private var pos = mode.toFloat()
        private var anim: ValueAnimator? = null
        private val thumb = android.graphics.RectF()
        private val thumbPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = p.fill
            if (p.halo != 0) setShadowLayer(8f * dd, 0f, 0f, p.halo)
            else setShadowLayer(4f * dd, 0f, 1.5f * dd, alpha(Color.BLACK, 0.18f))
        }
        private val rimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.2f * dd
            color = if (p.fillStroke != 0) p.fillStroke else alpha(Color.WHITE, 0.45f)
        }
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 13.5f * dd
            typeface = fontMedium
        }
        private var downX = 0f
        private var dragging = false
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        init {
            background = ui.glass(22.dp, level = 1, stroke = p.btnStroke)
        }

        override fun onDraw(c: android.graphics.Canvas) {
            super.onDraw(c)
            val pad = 4f * dd
            val seg = ((width - 2 * pad) / 3f).coerceAtLeast(1f)
            val th = (height - 2 * pad).coerceAtLeast(1f)
            thumb.set(pad + pos * seg, pad, pad + pos * seg + seg, pad + th)
            c.drawRoundRect(thumb, th / 2, th / 2, thumbPaint)
            val i = rimPaint.strokeWidth / 2
            thumb.inset(i, i)
            c.drawRoundRect(thumb, th / 2 - i, th / 2 - i, rimPaint)
            val fm = textPaint.fontMetrics
            val base = height / 2f - (fm.ascent + fm.descent) / 2
            for (k in labels.indices) {
                val w = (1f - kotlin.math.abs(pos - k)).coerceIn(0f, 1f)
                textPaint.color = mix(p.sub, p.onAccent, w)
                c.drawText(labels[k], pad + (k + 0.5f) * seg, base, textPaint)
            }
        }

        private fun mix(a: Int, b: Int, t: Float) = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            val pad = 4f * dd
            val seg = ((width - 2 * pad) / 3f).coerceAtLeast(1f)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; dragging = false; anim?.cancel(); parent?.requestDisallowInterceptTouchEvent(true) }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && kotlin.math.abs(e.x - downX) > slop) dragging = true
                    if (dragging) { pos = ((e.x - pad) / seg - 0.5f).coerceIn(0f, 2f); invalidate() }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val target = if (dragging) kotlin.math.round(pos).toInt() else ((e.x - pad) / seg).toInt().coerceIn(0, 2)
                    pick(target)
                }
                android.view.MotionEvent.ACTION_CANCEL -> pick(mode)
            }
            return true
        }

        private fun pick(m: Int) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(pos, m.toFloat()).apply {
                duration = 240
                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
            if (m != mode) {
                mode = m
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            onPick(m)
        }
    }

    // ================================================================== Auto mode

    private fun renderAuto() {
        if (!::autoBtn.isInitialized) return
        val setUp = AutoNfc.setUp(this)
        val on = setUp && AutoNfc.enabled(this)
        autoText.text = if (setUp) t("NFC turns on when you open AstraDrop and off when you close it. Tap Hotspot to turn on your hotspot.")
            else t("NFC turns on when you open AstraDrop and off when you close it, and Hotspot turns on your hotspot. One-time setup, about 2 minutes.")
        val key = if (!setUp) "setup" else if (on) "on" else "off"
        if (autoBtn.tag != key) {
            autoBtn.tag = key
            autoBtn.text = when (key) { "setup" -> t("Set up"); "on" -> t("On"); else -> t("Off") }
            ui.setFilled(autoBtn, key != "off", radius = 20)
        }
        val note = if (!on) "" else when (AutoNfc.lastStart) {
            Engine.Start.NO_WIFI -> "Starts the next time this phone is on Wi-Fi (Android only allows it there). Until then, tap Tap to turn on NFC yourself."
            Engine.Start.NOT_PAIRED -> "The pairing was removed. Tap to set it up again."
            Engine.Start.DEV_OFF -> "Developer options were turned off. Tap to set it up again."
            Engine.Start.WD_OFF, Engine.Start.FAILED -> "Couldn't start. Tap to try again."
            else -> if (Island.a11yEnabled(this) || AutoNfc.solCubeInstalled(this)) ""
                else "Optional: tap to allow the “NFC on / off” pill at the top of the screen (Accessibility)."
        }
        autoNote.text = t(note)
        autoNote.setTextColor(if (note.startsWith("Optional")) p.sub else p.warn)
        autoNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onAutoButton() {
        if (!AutoNfc.setUp(this)) { startActivity(Intent(this, SetupActivity::class.java)); return }
        AutoNfc.setEnabled(this, !AutoNfc.enabled(this))
        renderAuto()
        render()
    }

    private fun onAutoNote() {
        when (AutoNfc.lastStart) {
            Engine.Start.NOT_PAIRED, Engine.Start.DEV_OFF -> startActivity(Intent(this, SetupActivity::class.java))
            Engine.Start.WD_OFF, Engine.Start.FAILED -> { AutoNfc.ensureEngine(this); toast("Starting…") }
            Engine.Start.NO_WIFI -> {}
            else -> Nav.accessibility(this)
        }
    }

    // ================================================================== pinned title (from SolCube)

    /**
     * Pinned title above a scrolling body over [GlassBackdrop]; SolCube's pinnedBackdropLayout.
     * Child order is fixed as [GlassBackdrop, scrollWrap, titleView] so [LiquidGlass] finds the backdrop.
     */
    private fun pinnedBackdropLayout(
        titleView: TextView,
        body: android.view.ViewGroup,
        titleLeftPx: Int,
        titleTopPx: Int,
        titleBottomGapPx: Int,
        bodySidePadPx: Int,
        bodyBottomPadPx: Int,
        blurBelowTitlePx: Int,
        fadeBelowTitlePx: Int,
        liftPx: Int,
        liftStartPx: Int,
        liftSpanPx: Int,
        container: FrameLayout = FrameLayout(this),
        onScrollChange: () -> Unit = {},
        onLayersReady: (GlassBackdrop, View) -> Unit = { _, _ -> },
        onBottomInset: (Int) -> Unit = {},
    ): FrameLayout {
        titleView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        var titleH = titleView.measuredHeight
        var topInset = 0
        var bottomInset = 0
        var lastKey = ""
        var headerBlur: RenderEffect? = null
        var appliedBlur: RenderEffect? = null
        var targetLiftY = 0f
        var currentLiftY = 0f
        var liftAnimRunning = false
        var liftLastNs = 0L

        val liftFrame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (!liftAnimRunning || !titleView.isAttachedToWindow) {
                    liftAnimRunning = false
                    return
                }
                if (liftLastNs == 0L) liftLastNs = now
                val dt = ((now - liftLastNs) / 1e9f).coerceIn(0.001f, 0.05f)
                liftLastNs = now
                val diff = targetLiftY - currentLiftY
                if (kotlin.math.abs(diff) < 0.15f) {
                    currentLiftY = targetLiftY
                    titleView.translationY = currentLiftY
                    liftAnimRunning = false
                } else {
                    currentLiftY += diff * (1f - kotlin.math.exp(-dt * 5.0f))
                    titleView.translationY = currentLiftY
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        titleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                liftAnimRunning = false
                android.view.Choreographer.getInstance().removeFrameCallback(liftFrame)
            }
        })

        val bd = GlassBackdrop(this)
        val scroll = BounceScrollView(this).also { scrollView = it }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
        }
        val scrollWrap = HeaderBlurFrame(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val titleLp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = titleLeftPx
            topMargin = titleTopPx
        }

        fun syncLayout() {
            body.setPadding(bodySidePadPx, topInset + titleTopPx + titleH + titleBottomGapPx, bodySidePadPx, bodyBottomPadPx + bottomInset)
            val wantTop = topInset + titleTopPx
            if (titleLp.topMargin != wantTop) {
                titleLp.topMargin = wantTop
                titleView.layoutParams = titleLp
            }
            val key = "$topInset:$bottomInset:$titleH"
            if (key != lastKey) {
                lastKey = key
                val liftedBottom = (topInset + titleTopPx - liftPx + titleH).toFloat()
                val yTop = (topInset + 4.dp).toFloat()
                val yEnd = liftedBottom + blurBelowTitlePx
                headerBlur = buildHeaderBlurEffect(yTop, yEnd, liftedBottom + fadeBelowTitlePx)
                scrollWrap.stripBottom = kotlin.math.ceil(yEnd).toInt()
                if (scroll.scrollY > 0) {
                    appliedBlur = headerBlur
                    scrollWrap.effect = headerBlur
                }
            }
        }

        syncLayout()
        titleView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && h != titleH) {
                titleH = h
                syncLayout()
            }
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            bd.setScroll(y, scroll)
            onScrollChange()
            val want = if (y > 0) headerBlur else null
            if (appliedBlur !== want) {
                appliedBlur = want
                scrollWrap.effect = want
            }
            val lu = ((y - liftStartPx).toFloat() / liftSpanPx.coerceAtLeast(1)).coerceIn(0f, 1f)
            val ease = lu * lu * lu * (lu * (lu * 6f - 15f) + 10f)
            targetLiftY = -liftPx * ease
            if (!ValueAnimator.areAnimatorsEnabled()) {
                currentLiftY = targetLiftY
                titleView.translationY = currentLiftY
            } else if (!liftAnimRunning && kotlin.math.abs(targetLiftY - currentLiftY) >= 0.15f) {
                liftAnimRunning = true
                liftLastNs = 0L
                android.view.Choreographer.getInstance().postFrameCallback(liftFrame)
            }
        }

        onLayersReady(bd, scrollWrap)
        return container.apply {
            addView(bd, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scrollWrap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(titleView, titleLp)
            setOnApplyWindowInsetsListener { _, insets ->
                val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                val t = sys.top
                val b = maxOf(sys.bottom, ime.bottom)
                if (t != topInset || b != bottomInset) {
                    topInset = t
                    bottomInset = b
                    syncLayout()
                    onBottomInset(b)
                }
                insets
            }
        }
    }

    /** SolCube's header blur: sharp at the page, three blur bands getting stronger toward the top. */
    private fun buildHeaderBlurEffect(yTop: Float, yEnd: Float, fadeStart: Float): RenderEffect {
        val d = resources.displayMetrics.density
        val dark = isDark()
        val vr = if (dark) 0x12 / 255f else 0.957f
        val vg = if (dark) 0x0D / 255f else 0.933f
        val vb = if (dark) 0x07 / 255f else 0.902f
        fun bandMask(band: Float): RenderEffect {
            val sh = RuntimeShader(HEADER_BLUR_MASK_SRC).apply {
                setFloatUniform("yTop", yTop)
                setFloatUniform("yEnd", yEnd)
                setFloatUniform("fadeStart", fadeStart)
                setFloatUniform("band", band)
                setFloatUniform("passes", 3f)
                setFloatUniform("veil", vr, vg, vb)
            }
            return RenderEffect.createRuntimeShaderEffect(sh, "src")
        }
        val b0 = bandMask(0f)
        val b1 = RenderEffect.createChainEffect(bandMask(1f), RenderEffect.createBlurEffect(5.5f * d, 5.5f * d, Shader.TileMode.CLAMP))
        val b2 = RenderEffect.createChainEffect(bandMask(2f), RenderEffect.createBlurEffect(11.5f * d, 11.5f * d, Shader.TileMode.CLAMP))
        val b3 = RenderEffect.createChainEffect(bandMask(3f), RenderEffect.createBlurEffect(18f * d, 18f * d, Shader.TileMode.CLAMP))
        val low = RenderEffect.createBlendModeEffect(b0, b1, BlendMode.PLUS)
        val high = RenderEffect.createBlendModeEffect(b2, b3, BlendMode.PLUS)
        return RenderEffect.createBlendModeEffect(low, high, BlendMode.PLUS)
    }

    // ================================================================== tiles & grid

    private fun fillGrid(grid: LinearLayout, tiles: List<View>) {
        grid.removeAllViews()
        val cols = 3
        tiles.chunked(cols).forEachIndexed { r, rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val v = rowTiles.getOrNull(i) ?: View(this)
                row.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = 8.dp })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (r > 0) topMargin = 8.dp })
        }
    }

    /** "5.8 MB" style size, in the same 1000-based units the phone's own apps use. */
    private fun fmtBytes(b: Long): String = when {
        b < 1000 -> "$b B"
        b < 1_000_000 -> String.format(Locale.US, "%.0f KB", b / 1e3)
        b < 1_000_000_000 -> String.format(Locale.US, if (b < 10_000_000) "%.1f MB" else "%.0f MB", b / 1e6)
        else -> String.format(Locale.US, "%.2f GB", b / 1e9)
    }

    private fun mediaTile(uri: Uri, mime: String, name: String, onRemove: (() -> Unit)?, rawId: String? = null, size: Long = -1, onClick: () -> Unit): View {
        val tile = SquareFrame(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14.dp.toFloat()
                setColor(p.tile)
                setStroke(1.dp, p.stroke)
            }
            clipToOutline = true
            foreground = ripple(null, 14.dp)
            setOnClickListener { onClick() }
        }
        tile.addView(label(name.substringAfterLast('.', "file").take(4).uppercase(), 13f, p.sub, bold = true).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        tile.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        var badge: TextView? = null
        if (mime.startsWith("video/")) {
            badge = label("▶", 11.5f, Color.WHITE, bold = true).apply {
                background = rounded(Color.argb(150, 0, 0, 0), 10.dp)
                setPadding(7.dp, 2.dp, 7.dp, 2.dp)
            }
            tile.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(6.dp, 0, 0, 6.dp)
            })
        }
        thumbs.load(uri, mime, img) { ms -> badge?.text = "▶ ${fmtDuration(ms)}" }
        if (rawId != null && RawConverter.progress(rawId) != null) {
            tile.addView(ConvertBar(this, rawId, p.link), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else if (size >= 0) {
            val sz = label(fmtBytes(size), 11f, Color.WHITE, bold = true).apply {
                background = rounded(Color.argb(150, 0, 0, 0), 10.dp)
                setPadding(7.dp, 2.dp, 7.dp, 2.dp)
            }
            tile.addView(sz, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, 6.dp, 6.dp)
            })
        }

        if (onRemove != null) {
            val x = label("✕", 12f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = oval(Color.argb(150, 0, 0, 0))
                setOnClickListener { onRemove() }
            }
            tile.addView(x, FrameLayout.LayoutParams(26.dp, 26.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 6.dp, 6.dp, 0) })
        }
        return tile
    }

    private fun addTile(what: String, onClick: () -> Unit): View {
        val tile = SquareFrame(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14.dp.toFloat()
                setStroke(2.dp, alpha(p.link, 0.6f), 6.dp.toFloat(), 4.dp.toFloat())
                setColor(p.tile)
            }
            foreground = ripple(null, 14.dp)
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(label("+", 30f, p.link).apply { gravity = Gravity.CENTER })
        col.addView(label(what, 13f, p.link, bold = true).apply { gravity = Gravity.CENTER })
        tile.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return tile
    }

    // ================================================================== small widgets

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        background = glass(24.dp, level = 0)
        setPadding(18.dp, 18.dp, 18.dp, 18.dp)
    }

    /** Liquid glass pane (see LiquidGlass): [level] 0 on the wallpaper, 1 inside a card. Null tint = clear glass. */
    private fun glass(radius: Int, tint: Int? = null, level: Int = 1): Drawable =
        LiquidGlass(radius.toFloat(), isDark(), resources.displayMetrics.density, level = level, tint = tint)

    /** Filled buttons: champagne-gold glass with a gold rim and halo by night, ink glass by day. */
    private fun accentGradient(radius: Int): Drawable =
        LiquidGlass(radius.toFloat(), isDark(), resources.displayMetrics.density, level = 1,
            tint = p.fill, shine = p.fillShine, shadow = p.halo == 0, halo = p.halo, stroke = p.fillStroke)

    private fun cardParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12.dp }

    private fun sectionTitle(t: String) = label(t, 17f, p.fg, bold = true)

    private val settingRefresh = mutableListOf<() -> Unit>()

    /** A settings line: name on the left, current value in a glass pill on the right. */
    private fun settingRow(name: String, value: () -> String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        row.addView(label(name, 15f, p.fg, bold = true).apply { setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val v = smallPill(value()) { onClick() }
        row.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, 34.dp).apply { leftMargin = 10.dp })
        settingRefresh += { val s = value(); if (v.text.toString() != s) v.text = s }
        return row
    }

    private fun idleName(m: Int) = if (m == 0) t("Never") else if (m < 60) t("After %d min").format(m) else t("After 1 hour")

    /** A text in the app's language (English text is the key, see I18n). */
    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = I18n.t(t)
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontMedium else fontRegular
    }

    /** Small glass pill with an amber label (SolCube's "Can't find a feature?" pill). */
    private fun smallPill(t: String, onClick: () -> Unit) = label(t, 13.5f, p.link, bold = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(14.dp, 0, 14.dp, 0)
        background = ripple(glass(16.dp), 16.dp)
        setOnClickListener { onClick() }
    }

    /** Equal-width pills sharing a row; tight side padding so the longest label still fits. */
    private fun rowPillParams(first: Boolean = false) =
        LinearLayout.LayoutParams(0, 32.dp, 1f).apply { if (!first) leftMargin = 8.dp }

    private fun pillParams(first: Boolean = false) =
        LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { if (!first) leftMargin = 8.dp }

    /** SolCube's collapse badge: a round glass button with a drawn chevron that turns over. */
    inner class ChevronBadgeView(open: Boolean) : View(this) {
        private var arrowAngle = if (open) 180f else 0f
        private var rotAnim: android.animation.ValueAnimator? = null
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.dp.toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = alpha(p.fg, 0.8f)
        }
        private val path = android.graphics.Path()

        init {
            val dark = isDark()
            background = ripple(glass(15.dp, if (dark) Color.argb(26, 255, 255, 255) else Color.argb(70, 255, 255, 255)), 15.dp)
            elevation = if (dark) 4.dp.toFloat() else 5.dp.toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                    o.alpha = 1f
                }
            }
            outlineAmbientShadowColor = if (dark) Color.argb(120, 0, 0, 0) else Color.argb(85, 30, 24, 18)
            outlineSpotShadowColor = if (dark) Color.argb(230, 0, 0, 0) else Color.argb(185, 30, 24, 18)
        }

        fun setOpen(open: Boolean, animate: Boolean, duration: Long = 480L) {
            rotAnim?.cancel()
            val target = if (open) 180f else 0f
            if (!animate || arrowAngle == target) { arrowAngle = target; invalidate(); return }
            rotAnim = android.animation.ValueAnimator.ofFloat(arrowAngle, target).apply {
                this.duration = duration
                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { arrowAngle = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val halfW = 5.5f * resources.displayMetrics.density
            val halfH = 2.75f * resources.displayMetrics.density
            path.reset()
            path.moveTo(cx - halfW, cy - halfH)
            path.lineTo(cx, cy + halfH)
            path.lineTo(cx + halfW, cy - halfH)
            canvas.save()
            canvas.rotate(arrowAngle, cx, cy)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    private fun textButton(t: String, onClick: () -> Unit) = label(t, 14.5f, p.link, bold = true).apply {
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = ripple(null, 18.dp)
        setOnClickListener { onClick() }
    }

    /** SolCube's pill button (see Ui.button): gold when [filled], else outlined. */
    private fun pillButton(t: String, filled: Boolean, onClick: () -> Unit) = ui.button(t, filled, onClick = onClick)

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        textSize = 15f
        setTextColor(p.fg)
        setHintTextColor(p.sub)
        typeface = fontRegular
        background = GradientDrawable().apply {
            cornerRadius = 14.dp.toFloat()
            setColor(p.field)
            setStroke(1.dp, p.stroke)
        }
        setPadding(14.dp, 0, 14.dp, 0)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ripple(content: Drawable?, radius: Int) =
        RippleDrawable(ColorStateList.valueOf(alpha(p.fg, 0.12f)), content, rounded(Color.WHITE, radius))

    private fun alpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(t: String) = Toast.makeText(this, I18n.t(t), Toast.LENGTH_SHORT).show()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ================================================================== theme

    private fun isDark() = Look.isDark(this)

    private fun styleSystemBars() = ui.styleBars(this)

    companion object {
        private const val RECV_SHOWN = 9
        private const val REQ_MEDIA = 1
        private const val REQ_FILES = 2
        private const val REQ_NEARBY = 11
        const val ACTION_QUICK = "dev.tapdrop.QUICK"
        private const val HEADER_BLUR_MASK_SRC = """
uniform shader src;
uniform float yTop;
uniform float yEnd;
uniform float fadeStart;
uniform float band;
uniform float passes;
uniform float3 veil;

half4 main(float2 fc) {
    float u = clamp((yEnd - fc.y) / max(yEnd - yTop, 1.0), 0.0, 1.0);
    float t = u * (0.85 + 0.15 * u);
    float s = t * 3.0;
    float w = 0.0;
    if (passes < 1.5) {
        // one blur pass: sharp (band 0) fading into the single blurred copy (band 1)
        w = band < 0.5 ? 1.0 - t : t;
    } else if (band < 0.5) {
        w = clamp(1.0 - s, 0.0, 1.0);
    } else if (band < 1.5) {
        w = max(0.0, 1.0 - abs(s - 1.0));
    } else if (band < 2.5) {
        w = max(0.0, 1.0 - abs(s - 2.0));
    } else {
        w = clamp(s - 2.0, 0.0, 1.0);
    }
    if (w <= 0.0) return half4(0.0);
    half4 c = src.eval(fc);
    float fu = clamp((fadeStart - fc.y) / max(fadeStart, 1.0), 0.0, 1.0);
    float soft = 0.25 * fu * fu * (3.0 - 2.0 * fu);
    half3 rgb = mix(c.rgb, half3(veil) * c.a, half(soft));
    return half4(rgb, c.a) * half(w);
}
"""
    }
}

/** A FrameLayout that is always as tall as it is wide. */
class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
