package com.macpad.macpad

import android.widget.FrameLayout

/**
 * Desktop shortcuts layer. STUB — replaced by the real implementation.
 *
 * Contract with MainActivity:
 *  - Added below the window layer, filling the area between the menu bar and the Dock.
 *  - A tap on an empty spot must call host.windows.deactivateAll().
 */
class DesktopIconsLayer(private val host: DesktopHost) : FrameLayout(host.context) {
    fun applyPalette() {}
    fun refresh() {}
    fun onAppsChanged() {}
    fun addApp(key: String) {}
    fun removeApp(key: String) {}
    fun arrange() {}
    fun focusFirstIcon(): Boolean = false
}
