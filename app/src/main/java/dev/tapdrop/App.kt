package dev.tapdrop

import android.app.Application

/** Starts loading the wallpaper as soon as the process exists, before the screen is built. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        GlassBackdrop.preload(this)
    }
}
