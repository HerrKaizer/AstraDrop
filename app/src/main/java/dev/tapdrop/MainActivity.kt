package dev.tapdrop

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.IntentFilter
import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import rikka.shizuku.Shizuku
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

    private data class Palette(
        val base: Int, val glows: List<Int>, val solid: Int,
        val glass: Int, val sheen: Int, val stroke: Int, val tile: Int, val chip: Int, val field: Int,
        val fg: Int, val sub: Int, val accent: Int, val accent2: Int, val warn: Int, val danger: Int,
        /** Text/icon accent, text on filled buttons, filled-button rim (0 = none) and halo (0 = none). */
        val link: Int, val onAccent: Int, val fillStroke: Int = 0, val halo: Int = 0,
        /** Filled-button glass tint and highlight strength. */
        val fill: Int, val fillShine: Float,
    )

    private val fontRegular: Typeface by lazy { googleSans(400) }
    private val fontMedium: Typeface by lazy { googleSans(560) }
    private val fontBold: Typeface by lazy { googleSans(680) }

    private fun googleSans(weight: Int): Typeface = try {
        Typeface.Builder(assets, "fonts/GoogleSansFlex.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build() ?: Typeface.DEFAULT
    } catch (_: Exception) {
        Typeface.DEFAULT
    }

    private enum class State { READY, OFF, NO_NET, NO_NFC, NFC_OFF }

    private lateinit var p: Palette
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
    private lateinit var nfcAction: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var linkView: TextView
    private lateinit var sendTitle: TextView
    private lateinit var sendGrid: LinearLayout
    private lateinit var sendHint: TextView
    private lateinit var clearBtn: TextView
    private lateinit var recvTitle: TextView
    private lateinit var recvGrid: LinearLayout
    private lateinit var recvHint: TextView
    private lateinit var hotspotBody: LinearLayout
    private lateinit var chevron: ChevronBadgeView
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private var pulseAnim: AnimatorSet? = null
    private lateinit var scrollView: ScrollView
    private lateinit var hotCard: LinearLayout
    private lateinit var nfcToggle: TextView
    private lateinit var nfcToggleNote: TextView
    private lateinit var rootFrame: FrameLayout
    private lateinit var tapRipple: TapRipple
    private var iconTint = 0
    private var iconKick: AnimatorSet? = null
    private val tapListener: () -> Unit = { triggerTapEffect() }

    /** NFC switched on or off anywhere (quick settings, Shizuku): update the hero right away. */
    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = render()
    }
    private val shizukuPerm = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            renderNfcToggle()
            if (result == PackageManager.PERMISSION_GRANTED && ShareState.running) NfcShizuku.set(this, true)
        }
    }
    private val shizukuUp = Shizuku.OnBinderReceivedListener { runOnUiThread { renderNfcToggle() } }
    private val shizukuDown = Shizuku.OnBinderDeadListener { runOnUiThread { renderNfcToggle() } }

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
        super.onCreate(savedInstanceState)
        // a fresh start shares nothing yet, so anything cached is left over from last time
        if (ShareState.items.isEmpty()) Thread { cacheDir.listFiles()?.forEach { it.deleteRecursively() } }.start()
        p = palette()
        thumbs = Thumbs(this)
        nfc = NfcAdapter.getDefaultAdapter(this)
        tapRipple = TapRipple(this)
        actionBar?.hide()
        styleSystemBars()
        rootFrame = buildUi()
        setContentView(rootFrame)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        handleShareIntent(intent)
        Shizuku.addRequestPermissionResultListener(shizukuPerm)
        Shizuku.addBinderReceivedListenerSticky(shizukuUp)
        Shizuku.addBinderDeadListener(shizukuDown)
    }

    override fun onDestroy() {
        iconKick?.cancel()
        tapRipple.release(rootFrame)
        Shizuku.removeRequestPermissionResultListener(shizukuPerm)
        Shizuku.removeBinderReceivedListener(shizukuUp)
        Shizuku.removeBinderDeadListener(shizukuDown)
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
        renderNfcToggle()
    }

    override fun onPause() {
        try { unregisterReceiver(nfcReceiver) } catch (_: Exception) {}
        setPreferredTag(false)
        ShareState.removeTapListener(tapListener)
        ShareState.removeListener(listener)
        tapRipple.cancel(rootFrame)
        setPulse(false)
        super.onPause()
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
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
            else -> return
        }
        intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { if (it !in uris) uris += it } }
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

    private fun render() {
        val s = state()
        val tint = when (s) {
            State.READY -> p.link
            State.OFF -> p.sub
            else -> p.warn
        }
        heroTitle.text = when (s) {
            State.READY -> "Ready to tap"
            State.OFF -> "Sharing is off"
            State.NO_NET -> "No network"
            State.NO_NFC -> "No NFC on this phone"
            State.NFC_OFF -> "NFC is off"
        }
        heroSub.text = when (s) {
            State.READY -> "Hold an iPhone against the back of this phone, then tap the banner on the iPhone."
            State.OFF -> "Turn sharing on to let an iPhone tap in."
            State.NO_NET -> "Join a Wi-Fi network or turn on your hotspot."
            State.NO_NFC -> "Use the link QR instead."
            State.NFC_OFF -> "Turn NFC on to share by tapping."
        }
        // own glass disc: gold with the same rim and glow as the filled buttons when ready to tap,
        // otherwise clear glass tinted by the state. Rebuilt only when that changes (new glass = shader compile)
        val ready = s == State.READY
        icon.imageTintList = ColorStateList.valueOf(if (ready) p.onAccent else tint)
        val discKey = if (ready) 1 else tint
        if (iconTint != discKey) {
            iconTint = discKey
            iconBg.background = if (ready) accentGradient(32.dp) else glass(32.dp, alpha(tint, if (isDark()) 0.16f else 0.12f))
            // the gold disc glows instead (an elevation shadow under the halo looked dirty)
            iconBg.elevation = if (ready && p.halo != 0) 0f else if (isDark()) 4.dp.toFloat() else 5.dp.toFloat()
        }
        pulse.background = oval(alpha(tint, 0.35f))
        setPulse(s == State.READY)
        nfcAction.visibility = if (s == State.NFC_OFF) View.VISIBLE else View.GONE

        if (ShareState.running) {
            primaryBtn.text = "Stop sharing"
            primaryBtn.setTextColor(p.danger)
            primaryBtn.background = ripple(glass(28.dp, alpha(p.danger, if (isDark()) 0.16f else 0.10f)), 28.dp)
            primaryBtn.foreground = null
        } else {
            primaryBtn.text = "Start sharing"
            primaryBtn.setTextColor(p.onAccent)
            primaryBtn.background = accentGradient(28.dp)
            primaryBtn.foreground = ripple(null, 28.dp)   // as an overlay: a ripple background would clip the halo
        }

        renderChips()
        linkView.text = ShareState.url() ?: ""
        linkView.visibility = if (ShareState.url() != null) View.VISIBLE else View.GONE

        renderSendGrid()
        renderRecvGrid()
    }

    private fun renderChips() {
        chipsRow.removeAllViews()
        if (addrs.isEmpty()) {
            chipsRow.addView(chip("No network", selected = false, warn = true) { refreshNetworks(); render() })
        }
        for (a in addrs) {
            chipsRow.addView(chip("${a.kind}  ${a.ip}", a.ip == ShareState.host) {
                ShareState.host = a.ip
                ShareState.notifyChanged()
            })
        }
        chipsRow.addView(chip("Refresh", selected = false) {
            refreshNetworks()
            render()
            toast("Networks refreshed")
        }.apply {
            val ic = getDrawable(R.drawable.ic_refresh)!!.mutate().apply { setTint(p.fg); setBounds(0, 0, 17.dp, 17.dp) }
            setCompoundDrawablesRelative(ic, null, null, null)
            compoundDrawablePadding = 7.dp
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, paddingTop, 15.dp, paddingBottom)
        })
    }

    private fun renderSendGrid() {
        val items = ShareState.items.toList()
        sendTitle.text = if (items.isEmpty()) "Sending" else "Sending  ·  ${items.size}"
        clearBtn.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        sendHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val tiles = items.map { item ->
            mediaTile(item.orig ?: item.uri, item.origMime ?: item.mime, item.name, onRemove = {
                ShareState.items.removeAll { it.id == item.id }
                ThumbStore.forget(item.uri)
                RawConverter.forget(item)
                Compressor.forget(item)
                ShareState.notifyChanged()
            }, rawId = item.id, size = item.size, onClick = { openViewer(item.orig ?: item.uri, item.origMime ?: item.mime) })
        } + addTile()
        fillGrid(sendGrid, tiles)
    }

    private fun renderRecvGrid() {
        val got = ShareState.received.toList()
        recvTitle.text = if (got.isEmpty()) "Received" else "Received  ·  ${got.size}"
        recvHint.visibility = if (got.isEmpty()) View.VISIBLE else View.GONE
        recvGrid.visibility = if (got.isEmpty()) View.GONE else View.VISIBLE
        fillGrid(recvGrid, got.map { r -> mediaTile(r.uri, r.mime, r.name, null) { openViewer(r.uri, r.mime) } })
    }

    private fun openViewer(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            toast("No app can open this file")
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

    private fun showQrDialog(text: String, title: String, message: String) {
        val bmp = try { qrBitmap(text) } catch (e: Exception) { toast("QR failed: ${e.message}"); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        box.addView(label(title, 21f, p.fg).apply { typeface = fontBold })
        box.addView(label(message, 15f, p.sub).apply { setPadding(0, 6.dp, 0, 0); setLineSpacing(0f, 1.15f) })
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            background = rounded(Color.WHITE, 18.dp)
        }
        box.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })
        val dialog = AlertDialog.Builder(this).setView(box).create()
        box.addView(pillButton("Done", filled = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(MATCH_PARENT, 50.dp).apply { topMargin = 18.dp })
        dialog.show()
        styleGlassDialog(dialog)
    }

    /** Frosted dialog: blurs the screen behind it where the device supports window blur (Android 12+). */
    private fun styleGlassDialog(dialog: AlertDialog) {
        val w = dialog.window ?: return
        var blur = false
        if (Build.VERSION.SDK_INT >= 31) {
            blur = getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled
            if (blur) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = 24.dp }
                w.setBackgroundBlurRadius(40.dp)
            }
        }
        // without real blur, a more opaque pane keeps the text readable
        val fill = alpha(p.solid, if (blur) (if (isDark()) 0.55f else 0.62f) else 0.96f)
        w.setBackgroundDrawable(glass(28.dp, fill, level = 0))
        w.setDimAmount(if (isDark()) 0.45f else 0.18f)
    }

    private fun showWifiQr() {
        val ssid = ssidEdit.text.toString()
        val pass = passEdit.text.toString()
        if (ssid.isBlank()) {
            toast("Enter your hotspot name first")
            return
        }
        getSharedPreferences("tapdrop", MODE_PRIVATE).edit().putString("ssid", ssid).putString("pass", pass).apply()
        fun e(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        val text = if (pass.isEmpty()) "WIFI:T:nopass;S:${e(ssid)};;" else "WIFI:T:WPA;S:${e(ssid)};P:${e(pass)};;"
        showQrDialog(text, "Join \"$ssid\"", "On the iPhone, open the Camera and point it at this code. It only needs to do this once.")
    }

    private fun openHotspotSettings() {
        val tries = listOf(
            // vivo: Settings → More connections → Personal hotspot
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$VivoTetherSettingsActivity")),
            Intent("com.android.settings.WIFI_TETHER_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (t in tries) {
            try { startActivity(t); return } catch (_: Exception) {}
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // same title as SolCube: 42sp bold, pinned over the page (it floats up a little and the
        // page blurs under it as you scroll); see pinnedBackdropLayout
        val title = label("SolDrop", 42f, p.fg).apply {
            typeface = fontBold
            letterSpacing = -0.02f
        }

        // ---- hero / status
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

        primaryBtn = label("", 17f, p.onAccent, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                if (ShareState.running) stopServer() else {
                    userStopped = false
                    refreshNetworks()
                    startServer()
                }
            }
        }
        hero.addView(primaryBtn, LinearLayout.LayoutParams(MATCH_PARENT, 56.dp).apply { topMargin = 20.dp })

        // small glass pills (SolCube style): readable over any part of the wallpaper, one line each
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
        }
        nfcAction = smallPill("Turn on NFC") { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        actions.addView(nfcAction, rowPillParams(first = true))
        actions.addView(smallPill("QR code") {
            val u = ShareState.url()
            if (u == null) toast("Start sharing first")
            else showQrDialog(u, "Link QR", "If the tap doesn't bring up a banner, scan this with the iPhone Camera instead.")
        }, rowPillParams())
        actions.addView(smallPill("New link") {
            ShareState.rotateToken()
            toast("New link made. The old one no longer works.")
        }, rowPillParams())
        for (i in 0 until actions.childCount) actions.getChildAt(i).setPadding(6.dp, 0, 6.dp, 0)
        hero.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16.dp })
        // SolCube's first card sits 100dp lower than its title gap (its "Can't find a feature?" pill row)
        root.addView(hero, cardParams().apply { topMargin = 100.dp })

        // ---- network
        val net = card()
        net.addView(sectionTitle("iPhone connects via"))
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)   // room for the gold chip's halo (the scroller clips this row to its bounds)
        }
        // the row carries the halo room as padding; negative margins keep the chips where they were
        net.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chipsRow)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 10.dp - 14.dp; bottomMargin = -14.dp; leftMargin = -16.dp; rightMargin = -16.dp
        })
        linkView = label("", 13f, p.sub).apply {
            setPadding(2.dp, 12.dp, 0, 0)
            setOnClickListener {
                val u = ShareState.url() ?: return@setOnClickListener
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SolDrop link", u))
                toast("Link copied")
            }
        }
        net.addView(linkView)
        root.addView(net, cardParams())

        // ---- sending
        val send = card()
        val sendHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        sendTitle = sectionTitle("Sending")
        sendHead.addView(sendTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sendHead.addView(smallPill("+ Files") { pickFiles() }, pillParams())
        clearBtn = smallPill("Clear") {
            ShareState.items.forEach { ThumbStore.forget(it.uri); RawConverter.forget(it); Compressor.forget(it) }
            ShareState.items.clear()
            clearCache()
            ShareState.notifyChanged()
        }
        sendHead.addView(clearBtn, pillParams())
        send.addView(sendHead)
        sendHint = label("Add photos here, or share them from Gallery with “Send to iPhone”. The iPhone can send you files even if this is empty.", 14f, p.sub).apply {
            setPadding(0, 8.dp, 0, 4.dp); setLineSpacing(0f, 1.15f)
        }
        send.addView(sendHint)
        sendGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        send.addView(sendGrid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        root.addView(send, cardParams())

        // ---- received
        val recv = card()
        recvTitle = sectionTitle("Received")
        recv.addView(recvTitle)
        recvHint = label("Photos and videos from the iPhone show up here, and in your Gallery under SolDrop.", 14f, p.sub).apply {
            setPadding(0, 4.dp, 0, 0); setLineSpacing(0f, 1.15f)
        }
        recv.addView(recvHint)
        recvGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recv.addView(recvGrid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        root.addView(recv, cardParams())

        // ---- hotspot (collapsible)
        val hot = card()
        hotCard = hot
        val hotHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                val show = hotspotBody.visibility != View.VISIBLE
                hotspotBody.visibility = if (show) View.VISIBLE else View.GONE
                chevron.setOpen(show, animate = true)
                if (show) {
                    // once the opened card has been laid out, bring all of it into view
                    hotCard.post {
                        val target = hotCard.bottom + 16.dp - (scrollView.height - scrollView.paddingTop - scrollView.paddingBottom)
                        if (target > scrollView.scrollY) scrollView.smoothScrollTo(0, target)
                    }
                }
            }
        }
        hotHead.addView(sectionTitle("Not on the same Wi-Fi?"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        chevron = ChevronBadgeView(false).apply { setOnClickListener { hotHead.performClick() } }
        hotHead.addView(chevron, LinearLayout.LayoutParams(30.dp, 30.dp).apply { leftMargin = 8.dp })
        hot.addView(hotHead)
        hotspotBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        hotspotBody.addView(label("Turn on your hotspot. The first time, the iPhone joins by scanning a Wi-Fi QR code. After that it reconnects by itself, so it's just: hotspot on, tap.", 14f, p.sub).apply {
            setPadding(0, 6.dp, 0, 12.dp); setLineSpacing(0f, 1.15f)
        })
        val prefs = getSharedPreferences("tapdrop", MODE_PRIVATE)
        ssidEdit = field("Hotspot name").apply { setText(prefs.getString("ssid", "")) }
        passEdit = field("Hotspot password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        hotspotBody.addView(ssidEdit, LinearLayout.LayoutParams(MATCH_PARENT, 50.dp))
        hotspotBody.addView(passEdit, LinearLayout.LayoutParams(MATCH_PARENT, 50.dp).apply { topMargin = 8.dp })
        val hotBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hotBtns.addView(pillButton("Show Wi-Fi QR", filled = true) { showWifiQr() }, LinearLayout.LayoutParams(0, 48.dp, 1f))
        hotBtns.addView(pillButton("Hotspot settings", filled = false) { openHotspotSettings() },
            LinearLayout.LayoutParams(0, 48.dp, 1f).apply { leftMargin = 10.dp })
        hotspotBody.addView(hotBtns, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        hot.addView(hotspotBody)
        root.addView(hot, cardParams())

        // ---- NFC through Shizuku (optional, off by default)
        val auto = card()
        val autoHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val autoText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        autoText.addView(sectionTitle("Turn NFC on for me"))
        autoText.addView(label("Through Shizuku: NFC turns on when sharing starts, and off when it stops or SolDrop is swiped away.", 14f, p.sub).apply {
            setPadding(0, 4.dp, 0, 0); setLineSpacing(0f, 1.15f)
        })
        autoHead.addView(autoText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nfcToggle = label("", 15f, p.fg, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { toggleShizukuNfc() }
        }
        autoHead.addView(nfcToggle, LinearLayout.LayoutParams(76.dp, 40.dp).apply { leftMargin = 12.dp })
        auto.addView(autoHead)
        nfcToggleNote = label("", 13.5f, p.warn).apply {
            setPadding(0, 10.dp, 0, 0); setLineSpacing(0f, 1.15f)
            setOnClickListener { fixShizuku() }
        }
        auto.addView(nfcToggleNote)
        root.addView(auto, cardParams())

        // on its own glass pill so it reads over the wallpaper
        root.addView(FrameLayout(this).apply {
            addView(label("Keep SolDrop open on screen while the iPhone taps.", 12.5f, p.sub).apply {
                gravity = Gravity.CENTER
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
                background = glass(16.dp, level = 0)
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        root.addView(label("SolDrop $ver", 12f, p.sub).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })

        // SolCube's numbers (its build(): title 22dp in, 28dp down, 22dp above the page)
        return pinnedBackdropLayout(
            titleView = title,
            body = root,
            titleLeftPx = 22.dp,
            titleTopPx = 28.dp,
            titleBottomGapPx = 22.dp,
            bodySidePadPx = 16.dp,
            bodyBottomPadPx = 24.dp,
            blurBelowTitlePx = 12.dp,
            fadeBelowTitlePx = 0,
            liftPx = 18.dp,
            liftStartPx = 44.dp,
            liftSpanPx = 110.dp,
        )
    }

    // ================================================================== NFC through Shizuku

    private fun renderNfcToggle() {
        if (!::nfcToggle.isInitialized) return
        val on = NfcShizuku.enabled(this)
        if (nfcToggle.tag != on) {
            nfcToggle.tag = on
            nfcToggle.text = if (on) "On" else "Off"
            nfcToggle.setTextColor(if (on) p.onAccent else p.fg)
            nfcToggle.background = if (on) accentGradient(20.dp) else glass(20.dp)
            nfcToggle.foreground = ripple(null, 20.dp)
        }
        val note = if (!on) "" else when (NfcShizuku.state(this)) {
            NfcShizuku.State.READY -> if (Island.a11yEnabled(this) || NfcShizuku.solCubeInstalled(this)) "" else "Tap to switch on “SolDrop NFC pill” in Accessibility, so “NFC on” and “NFC off” show at the top of the screen."
            NfcShizuku.State.NOT_INSTALLED -> "Shizuku isn't installed. Get it from Google Play, then start it."
            NfcShizuku.State.NOT_RUNNING -> "Shizuku isn't running (it stops on every reboot). Tap to open it and start it."
            NfcShizuku.State.NO_PERMISSION -> "Tap to allow SolDrop in Shizuku."
        }
        nfcToggleNote.text = note
        nfcToggleNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleShizukuNfc() {
        val on = !NfcShizuku.enabled(this)
        NfcShizuku.setEnabled(this, on)
        if (on) {
            if (NfcShizuku.state(this) == NfcShizuku.State.READY) {
                if (ShareState.running) NfcShizuku.set(this, true)
            } else fixShizuku()
        } else {
            NfcShizuku.disarm(this)
        }
        renderNfcToggle()
    }

    private fun fixShizuku() {
        when (NfcShizuku.state(this)) {
            NfcShizuku.State.NO_PERMISSION -> try { Shizuku.requestPermission(1) } catch (_: Throwable) {}
            NfcShizuku.State.NOT_RUNNING ->
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
            NfcShizuku.State.NOT_INSTALLED -> try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
            } catch (_: Exception) { toast("Shizuku isn't installed") }
            NfcShizuku.State.READY -> if (!Island.a11yEnabled(this)) openA11ySettings()
        }
    }

    /** Straight to SolDrop's page in Accessibility (vivo/Android won't let Shizuku switch a new app's service on). */
    private fun openA11ySettings() {
        val cn = ComponentName(this, SolDropA11y::class.java).flattenToString()
        try {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ================================================================== pinned title (from SolCube)

    /**
     * Pinned title above a scrolling body over [GlassBackdrop]; SolCube's pinnedBackdropLayout.
     * Child order is fixed as [GlassBackdrop, scrollWrap, titleView] so [LiquidGlass] finds the backdrop.
     */
    private fun pinnedBackdropLayout(
        titleView: TextView,
        body: LinearLayout,
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

        return FrameLayout(this).apply {
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

    private fun addTile(): View {
        val tile = SquareFrame(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14.dp.toFloat()
                setStroke(2.dp, alpha(p.link, 0.6f), 6.dp.toFloat(), 4.dp.toFloat())
                setColor(p.tile)
            }
            foreground = ripple(null, 14.dp)
            setOnClickListener { pickMedia() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(label("+", 30f, p.link).apply { gravity = Gravity.CENTER })
        col.addView(label("Photos", 13f, p.link, bold = true).apply { gravity = Gravity.CENTER })
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

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
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

    private fun pillButton(t: String, filled: Boolean, onClick: () -> Unit) =
        label(t, 15f, if (filled) p.onAccent else p.link, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // ripple as an overlay: wrapped around the background it would clip the gold halo to a box
            background = if (filled) accentGradient(24.dp) else glass(24.dp)
            foreground = ripple(null, 24.dp)
            setOnClickListener { onClick() }
        }

    private fun chip(t: String, selected: Boolean, warn: Boolean = false, onClick: () -> Unit) =
        label(t, 14f, if (selected) p.onAccent else if (warn) p.warn else p.fg, bold = selected).apply {
            setPadding(14.dp, 9.dp, 14.dp, 9.dp)
            background = if (selected) accentGradient(20.dp) else glass(20.dp)
            foreground = ripple(null, 20.dp)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = 8.dp }
        }

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

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ================================================================== theme

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)

    // "Sol" palette, shared with SolCube: night = the icon's black with its amber-gold glow on
    // buttons and links; day = porcelain with ink buttons and amber links.
    private fun palette() = if (isDark()) Palette(
        base = Color.rgb(0x04, 0x03, 0x02),
        glows = listOf(Color.rgb(0x1E, 0x12, 0x05), Color.rgb(0x12, 0x0C, 0x06), Color.rgb(0x1A, 0x10, 0x04), Color.rgb(0x0E, 0x0B, 0x08)),
        solid = Color.rgb(0x11, 0x0E, 0x0A),
        glass = white(0.055f), sheen = white(0.04f), stroke = white(0.10f),
        tile = white(0.06f), chip = white(0.05f), field = white(0.07f),
        fg = Color.rgb(0xF5, 0xF1, 0xEA), sub = Color.rgb(0xBC, 0xB3, 0xA6),
        accent = Color.rgb(0xFF, 0xDF, 0x9E), accent2 = Color.rgb(0xE0, 0x8C, 0x24),
        warn = Color.rgb(0xFF, 0x86, 0x5E), danger = Color.rgb(0xFF, 0x5F, 0x57),
        link = Color.rgb(0xFF, 0xB6, 0x45), onAccent = Color.rgb(0x1E, 0x10, 0x02),
        fillStroke = Color.argb(210, 0xFF, 0xE4, 0x9A), halo = Color.argb(185, 0xFF, 0x9E, 0x1A),
        fill = Color.argb(250, 0xF4, 0xB2, 0x4C), fillShine = 1.1f,
    ) else Palette(
        base = Color.rgb(0xF6, 0xF2, 0xEC),
        glows = listOf(Color.rgb(0xEE, 0xD6, 0xAE), Color.rgb(0xE6, 0xDC, 0xCC), Color.rgb(0xF2, 0xD9, 0xB4), Color.rgb(0xE2, 0xDA, 0xD0)),
        solid = Color.rgb(0xFB, 0xF8, 0xF3),
        glass = white(0.58f), sheen = white(0.5f), stroke = white(0.9f),
        tile = white(0.55f), chip = white(0.45f), field = white(0.65f),
        fg = Color.rgb(0x18, 0x17, 0x1C), sub = Color.rgb(0x5F, 0x5A, 0x55),
        accent = Color.rgb(0x2A, 0x25, 0x20), accent2 = Color.rgb(0x12, 0x0F, 0x0C),
        warn = Color.rgb(0xB8, 0x3E, 0x1C), danger = Color.rgb(0xC6, 0x2A, 0x22),
        link = Color.rgb(0xA8, 0x62, 0x10), onAccent = Color.rgb(0xFF, 0xE2, 0xA8),
        fill = Color.argb(242, 0x1E, 0x1A, 0x16), fillShine = 0.37f,
    )

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.decorView.setBackgroundColor(p.base)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (!isDark()) {
            if (Build.VERSION.SDK_INT >= 30) {
                val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(f, f)
            } else {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    companion object {
        private const val REQ_MEDIA = 1
        private const val REQ_FILES = 2
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
