package com.macpad.macpad

import android.view.View
import android.widget.TextView

/** Information about one installed app. STUB — replaced by the real implementation. */
class AppInfoWindow(host: DesktopHost, val appKey: String) : MacWindow(host, "appinfo:$appKey") {
    init {
        title = "Об этом приложении"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.APP_INFO
    override fun onCreateContent(): View = TextView(context)
}

/** "About this device / MacPad". STUB — replaced by the real implementation. */
class AboutWindow(host: DesktopHost) : MacWindow(host, "about") {
    init {
        title = "Об этом устройстве"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.ABOUT
    override fun onCreateContent(): View = TextView(context)
}

/** Keyboard shortcuts reference. STUB — replaced by the real implementation. */
class ShortcutsWindow(host: DesktopHost) : MacWindow(host, "shortcuts") {
    init {
        title = "Сочетания клавиш"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.SHORTCUTS
    override fun onCreateContent(): View = TextView(context)
}
