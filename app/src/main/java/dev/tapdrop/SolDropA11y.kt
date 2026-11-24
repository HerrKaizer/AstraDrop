package dev.tapdrop

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Same as SolCube's SolCubeA11y: exists only so the "NFC on / off" pill can use
 * TYPE_ACCESSIBILITY_OVERLAY, the one window type an app may draw ABOVE the status bar (an app
 * overlay sits under it, and the notification icons drew over the pill). It does not read window
 * content or react to events. SolDrop switches it on through Shizuku with the NFC toggle.
 */
class SolDropA11y : AccessibilityService() {
    companion object {
        @Volatile var instance: SolDropA11y? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
