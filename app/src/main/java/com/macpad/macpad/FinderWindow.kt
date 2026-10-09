package com.macpad.macpad

import android.view.View
import android.widget.TextView

/** Finder (file manager) window. STUB — replaced by the real implementation. */
class FinderWindow(host: DesktopHost, id: String) : MacWindow(host, id) {
    init {
        title = "Finder"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.FINDER

    override fun onCreateContent(): View = TextView(context)

    /** Navigates to a granted SAF tree URI (one of prefs.finderPlaces). */
    fun openPlace(uri: String) {}
}
