package com.macpad.macpad

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/** A shortcut placed on the desktop grid. [key] is an [AppEntry.key]. */
data class DesktopItem(val key: String, val col: Int, val row: Int)

/** A folder the user granted through the Storage Access Framework. */
data class FinderPlace(val uri: String, val name: String)

/**
 * All persistent MacPad settings, stored in SharedPreferences.
 * Components change a value here and then call [DesktopHost.applySettings] (or a more specific
 * refresh such as [DesktopHost.onDockChanged]) so the UI picks it up.
 */
class MacPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("macpad_prefs", Context.MODE_PRIVATE)

    // ---- Appearance -------------------------------------------------------------------------

    /** "dark", "light" or "system". */
    var themeMode: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()

    /** One of [Wallpapers.catalog] ids, [Wallpapers.SYSTEM] or [Wallpapers.CUSTOM]. */
    var wallpaper: String
        get() = sp.getString("wallpaper", Wallpapers.DEFAULT) ?: Wallpapers.DEFAULT
        set(v) = sp.edit().putString("wallpaper", v).apply()

    /** content:// URI of the user-picked wallpaper image (persisted read permission). */
    var customWallpaperUri: String?
        get() = sp.getString("wallpaper_uri", null)
        set(v) = sp.edit().putString("wallpaper_uri", v).apply()

    /** Icon size for Launchpad and desktop shortcuts: "small", "medium" or "large". */
    var iconSize: String
        get() = sp.getString("icon_size", "medium") ?: "medium"
        set(v) = sp.edit().putString("icon_size", v).apply()

    /** Hide the Android status bar (the MacPad menu bar shows clock and battery instead). */
    var hideStatusBar: Boolean
        get() = sp.getBoolean("hide_status_bar", true)
        set(v) = sp.edit().putBoolean("hide_status_bar", v).apply()

    /** Show the date next to the clock in the menu bar. */
    var showDate: Boolean
        get() = sp.getBoolean("show_date", true)
        set(v) = sp.edit().putBoolean("show_date", v).apply()

    /** Disable decorative animations (window zoom, Dock magnification springs, etc). */
    var reduceMotion: Boolean
        get() = sp.getBoolean("reduce_motion", false)
        set(v) = sp.edit().putBoolean("reduce_motion", v).apply()

    // ---- Dock -------------------------------------------------------------------------------

    /** Ordered [AppEntry.key]s pinned to the Dock. */
    var dockPinned: List<String>
        get() = readStringList("dock_pinned")
        set(v) = writeStringList("dock_pinned", v)

    /** False until the Dock has filled in its default pins on first launch. */
    var dockPinnedInitialized: Boolean
        get() = sp.getBoolean("dock_pinned_init", false)
        set(v) = sp.edit().putBoolean("dock_pinned_init", v).apply()

    /** Dock icon size in dp (40..80). */
    var dockIconSizeDp: Int
        get() = sp.getInt("dock_icon_dp", 56).coerceIn(40, 80)
        set(v) = sp.edit().putInt("dock_icon_dp", v.coerceIn(40, 80)).apply()

    /** Magnify Dock icons under the mouse pointer. */
    var dockMagnify: Boolean
        get() = sp.getBoolean("dock_magnify", true)
        set(v) = sp.edit().putBoolean("dock_magnify", v).apply()

    /** Show app names under Dock icons. */
    var dockShowLabels: Boolean
        get() = sp.getBoolean("dock_labels", false)
        set(v) = sp.edit().putBoolean("dock_labels", v).apply()

    fun isPinned(key: String) = dockPinned.contains(key)

    fun pin(key: String) {
        val list = dockPinned
        if (!list.contains(key)) dockPinned = list + key
    }

    fun unpin(key: String) {
        dockPinned = dockPinned.filter { it != key }
    }

    // ---- Desktop ----------------------------------------------------------------------------

    var desktopIconsEnabled: Boolean
        get() = sp.getBoolean("desktop_icons", true)
        set(v) = sp.edit().putBoolean("desktop_icons", v).apply()

    var desktopItems: List<DesktopItem>
        get() {
            val raw = sp.getString("desktop_items", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val k = o.optString("k")
                    if (k.isEmpty()) null else DesktopItem(k, o.optInt("c"), o.optInt("r"))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("k", it.key).put("c", it.col).put("r", it.row)) }
            sp.edit().putString("desktop_items", arr.toString()).apply()
        }

    fun isOnDesktop(key: String) = desktopItems.any { it.key == key }

    // ---- Finder -----------------------------------------------------------------------------

    var finderShowHidden: Boolean
        get() = sp.getBoolean("finder_hidden", false)
        set(v) = sp.edit().putBoolean("finder_hidden", v).apply()

    /** "list" or "grid". */
    var finderViewMode: String
        get() = sp.getString("finder_view", "list") ?: "list"
        set(v) = sp.edit().putString("finder_view", v).apply()

    /** "name", "date", "size" or "kind". */
    var finderSortBy: String
        get() = sp.getString("finder_sort", "name") ?: "name"
        set(v) = sp.edit().putString("finder_sort", v).apply()

    var finderSortDescending: Boolean
        get() = sp.getBoolean("finder_sort_desc", false)
        set(v) = sp.edit().putBoolean("finder_sort_desc", v).apply()

    /** Folders granted through ACTION_OPEN_DOCUMENT_TREE, shown in the Finder sidebar. */
    var finderPlaces: List<FinderPlace>
        get() {
            val raw = sp.getString("finder_places", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val u = o.optString("u")
                    if (u.isEmpty()) null else FinderPlace(u, o.optString("n", u))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("u", it.uri).put("n", it.name)) }
            sp.edit().putString("finder_places", arr.toString()).apply()
        }

    // ---- Notes ------------------------------------------------------------------------------

    var notesText: String
        get() = sp.getString("notes_text", "") ?: ""
        set(v) = sp.edit().putString("notes_text", v).apply()

    // ---- Windows ----------------------------------------------------------------------------

    /** Last bounds (in px, relative to the window layer) of windows with the given bounds key. */
    fun windowBounds(key: String): Rect? {
        val raw = sp.getString("win.$key", null) ?: return null
        val p = raw.split(',').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return null
        return Rect(p[0], p[1], p[0] + p[2], p[1] + p[3])
    }

    fun setWindowBounds(key: String, r: Rect) {
        sp.edit().putString("win.$key", "${r.left},${r.top},${r.width()},${r.height()}").apply()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun readStringList(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.distinct().forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
