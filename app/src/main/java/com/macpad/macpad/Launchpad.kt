package com.macpad.macpad

import android.view.View
import android.widget.FrameLayout

/**
 * Full-screen app catalogue. STUB — replaced by the real implementation.
 * Added to the root as MATCH_PARENT x MATCH_PARENT, initially GONE.
 */
class Launchpad(private val host: DesktopHost) : FrameLayout(host.context) {
    val isShowing: Boolean get() = visibility == View.VISIBLE
    fun show() { visibility = View.VISIBLE }
    fun hide() { visibility = View.GONE }
    fun applyPalette() {}
    fun onAppsChanged() {}
    fun focusSearch() {}
}
