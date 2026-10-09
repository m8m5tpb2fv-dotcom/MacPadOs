package com.macpad.macpad

import android.widget.FrameLayout

/**
 * Top menu bar. STUB — replaced by the real implementation.
 *
 * Contract with MainActivity:
 *  - Added at the top of the root; MainActivity sets its height to [barHeightPx] + top inset and
 *    its padding (left/top/right) to the system insets. Lay content out inside the padding.
 */
class MenuBar(private val host: DesktopHost) : FrameLayout(host.context) {
    /** Height of the bar itself in px (excluding the inset padding MainActivity adds). */
    val barHeightPx: Int = context.dp(34)

    /** Palette or prefs (e.g. showDate) changed: rebuild. */
    fun applyPalette() {}

    /** Activity started: register clock/battery/network listeners. */
    fun onStart() {}

    /** Activity stopped: unregister listeners. */
    fun onStop() {}

    /** Windows opened/closed/focused/renamed. */
    fun onWindowsChanged() {}

    /** F10 / Ctrl+F2: open the first menu with keyboard focus. */
    fun openMenuFromKeyboard(): Boolean = false
}
