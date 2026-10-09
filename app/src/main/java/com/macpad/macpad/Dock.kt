package com.macpad.macpad

import android.graphics.PointF
import android.widget.FrameLayout

/**
 * The Dock. STUB — replaced by the real implementation.
 *
 * Contract with MainActivity:
 *  - Added to the root with WRAP_CONTENT size, gravity BOTTOM|CENTER_HORIZONTAL and a bottom margin
 *    for the navigation bar. Must not be wider than its parent minus 24dp.
 */
class Dock(private val host: DesktopHost) : FrameLayout(host.context) {
    /** Palette or Dock prefs changed: rebuild. */
    fun applyPalette() {}

    /** Pins changed: rebuild items. */
    fun refresh() {}

    fun onAppsChanged() {}

    fun onWindowsChanged() {}

    /** Screen position (px) a window minimizes into / restores from. */
    fun anchorForWindow(windowId: String?): PointF {
        val dm = context.resources.displayMetrics
        return PointF(dm.widthPixels / 2f, dm.heightPixels.toFloat())
    }

    /** Ctrl+F3: move keyboard focus into the Dock. */
    fun focusDock(): Boolean = false

    /** Launch feedback for the app with [key]. */
    fun bounce(key: String) {}
}
