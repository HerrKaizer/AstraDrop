package dev.tapdrop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Shortcuts into Settings, each with fallbacks for phones that lack the exact page. */
object Nav {
    private fun first(ctx: Context, vararg tries: Intent) {
        for (t in tries) {
            try { ctx.startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }

    fun settings(ctx: Context, action: String) = first(ctx, Intent(action), Intent(Settings.ACTION_SETTINGS))

    /** The NFC page (it always has the switch; vivo's NFC panel comes up empty). */
    fun nfc(ctx: Context) = first(ctx, Intent(Settings.ACTION_NFC_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))

    // the page, not the panel: vivo ships its panels empty
    fun wifi(ctx: Context) = first(ctx, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))

    fun hotspot(ctx: Context) = first(ctx,
        // vivo: Settings → More connections → Personal hotspot
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$VivoTetherSettingsActivity")),
        Intent("com.android.settings.WIFI_TETHER_SETTINGS"),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    /**
     * Straight to the Wireless debugging page: Settings opens it for a Quick Settings tile long-press
     * (vivo through a gateway activity, AOSP through the tile preferences action). Otherwise
     * Developer options with the row highlighted, otherwise Developer options.
     */
    fun wirelessDebugging(ctx: Context) {
        val s = "com.android.settings"
        fun tile(cls: String) = Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(s, cls))
        first(ctx,
            tile("$s.development.qstile.AdbWirelessDebuggingDevelopmentTile").setClassName(s, "$s.qstile.QSTileLongPressGatewayActivity"),
            tile("$s.development.qstile.DevelopmentTiles\$WirelessDebugging").setPackage(s),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /** About phone, with the Build number row highlighted where Settings supports it. */
    fun aboutPhone(ctx: Context) = first(ctx,
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).putExtra(":settings:fragment_args_key", "build_number"),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** AstraDrop's page in Accessibility. */
    fun accessibility(ctx: Context) = first(ctx,
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(ctx, SolDropA11y::class.java).flattenToString()),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
}
