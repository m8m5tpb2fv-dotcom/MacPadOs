package com.macpad.macpad

import android.view.View
import android.widget.TextView

/** MacPad settings window. STUB — replaced by the real implementation. */
class SettingsWindow(host: DesktopHost) : MacWindow(host, ID) {
    companion object {
        const val ID = "settings"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_WALLPAPER = "wallpaper"
        const val SECTION_DOCK = "dock"
        const val SECTION_DESKTOP = "desktop"
        const val SECTION_FINDER = "finder"
        const val SECTION_LAUNCHER = "launcher"
        const val SECTION_KEYBOARD = "keyboard"
        const val SECTION_ABOUT = "about"
    }

    init {
        title = "Настройки MacPad"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.SETTINGS

    override fun onCreateContent(): View = TextView(context)

    fun showSection(section: String) {}
}
