package com.macpad.macpad

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Everything a desktop component (menu bar, Dock, windows, overlays) may use. Implemented by
 * [MainActivity]. Components receive it in their constructor and must not keep references to
 * views of other components.
 */
interface DesktopHost {
    val activity: AppCompatActivity
    val context: Context

    /** Current colours. Components read it when (re)building views; see [applySettings]. */
    val palette: Palette
    val prefs: MacPrefs
    val apps: AppRepository
    val windows: WindowManager
    val glass: GlassSource
    val menus: MacMenu
    val dialogs: DialogLayer

    /** False when the user enabled "reduce motion" or system animations are off. */
    val animationsEnabled: Boolean

    /** System bar / display cutout insets in px (left, top, right, bottom). Read-only. */
    val systemInsets: Rect

    fun toast(text: String)

    /** Launches an app and shows a clear message if it cannot be started. */
    fun launchApp(entry: AppEntry, source: View? = null)

    /**
     * Context menu for an app (open, Dock pin/unpin, desktop add/remove, info). Pass either an
     * [anchor] view or screen coordinates ([screenX], [screenY]) of the touch/click.
     */
    fun showAppContextMenu(entry: AppEntry, anchor: View?, screenX: Int = -1, screenY: Int = -1)

    // ---- MacPad windows & overlays ----------------------------------------------------------

    /** Focuses the last Finder window or opens one; [newWindow] always opens another one. */
    fun openFinder(newWindow: Boolean = false, placeUri: String? = null)
    /** Opens MacPad settings; [section] is one of SettingsWindow.SECTION_*. */
    fun openSettings(section: String? = null)
    fun openAppInfo(entry: AppEntry)
    fun openAbout()
    fun openNotes()
    fun openShortcuts()
    /** Opens the Android system Settings app. */
    fun openSystemSettings()

    fun showLaunchpad()
    fun hideLaunchpad()
    fun toggleLaunchpad()
    fun showSpotlight(query: String = "")
    fun hideSpotlight()
    fun toggleSpotlight()

    // ---- Storage Access Framework -----------------------------------------------------------

    /** ACTION_OPEN_DOCUMENT_TREE. The callback gets null if the user cancelled. */
    fun pickFolder(callback: (Uri?) -> Unit)
    /** ACTION_OPEN_DOCUMENT for the given MIME types. */
    fun pickFile(mimeTypes: Array<String>, callback: (Uri?) -> Unit)
    /** ACTION_CREATE_DOCUMENT. */
    fun createDocument(mimeType: String, suggestedName: String, callback: (Uri?) -> Unit)
    /** Lets the user pick an image and makes it the wallpaper. */
    fun pickWallpaperImage()

    // ---- Shared state changes ----------------------------------------------------------------

    /** Screen point where a window minimizes to (its Dock slot, or the Dock centre). */
    fun dockAnchor(windowId: String?): PointF

    /** Call after changing Dock pins or Dock prefs. */
    fun onDockChanged()

    /** Call after changing desktop shortcut prefs. */
    fun onDesktopChanged()

    /** Pins/unpins an app (by [AppEntry.key]) and refreshes the Dock. */
    fun setPinned(key: String, pinned: Boolean)

    /** Adds/removes a desktop shortcut and refreshes the desktop. */
    fun setOnDesktop(key: String, onDesktop: Boolean)

    /** Re-packs desktop shortcuts alphabetically ("Упорядочить значки"). */
    fun arrangeDesktop()

    /** Re-applies theme, wallpaper, system bars and every component after prefs changed. */
    fun applySettings()

    /** True if MacPad currently holds the Home role. */
    fun isDefaultLauncher(): Boolean

    /** Asks Android to make MacPad the default Home app (RoleManager). */
    fun requestDefaultLauncher()
}
