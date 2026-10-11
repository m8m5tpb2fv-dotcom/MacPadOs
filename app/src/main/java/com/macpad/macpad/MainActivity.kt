package com.macpad.macpad

import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager as AndroidWindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max

/**
 * MacPad's only activity: the desktop (Home screen). It owns the layers
 * (wallpaper → desktop shortcuts → windows → Dock → menu bar → Launchpad/Spotlight → dialogs),
 * global keyboard shortcuts, system insets and the Storage Access Framework launchers, and
 * implements [DesktopHost] for all components.
 */
class MainActivity : AppCompatActivity(), DesktopHost {

    override val activity: AppCompatActivity get() = this
    override val context: Context get() = this

    override var palette: Palette = Palette.dark()
        private set
    override lateinit var prefs: MacPrefs
        private set
    override lateinit var apps: AppRepository
        private set
    override lateinit var windows: WindowManager
        private set
    override val glass: GlassSource get() = wallpaper
    override lateinit var menus: MacMenu
        private set
    override lateinit var dialogs: DialogLayer
        private set
    override val animationsEnabled: Boolean
        get() = !prefs.reduceMotion && ValueAnimator.areAnimatorsEnabled()
    override val systemInsets = Rect()

    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: WallpaperView
    private lateinit var wallpaper: WallpaperController
    private lateinit var desktop: DesktopIconsLayer
    private lateinit var windowLayer: FrameLayout
    private lateinit var dock: Dock
    private lateinit var menuBar: MenuBar
    private lateinit var launchpad: Launchpad
    private lateinit var spotlight: Spotlight

    private var imeBottom = 0
    private var appliedThemeKey = ""

    // ---- Storage Access Framework / role requests -------------------------------------------

    private var folderCallback: ((Uri?) -> Unit)? = null
    private var fileCallback: ((Uri?) -> Unit)? = null
    private var createCallback: ((Uri?) -> Unit)? = null

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val cb = folderCallback
        folderCallback = null
        cb?.invoke(uri)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = fileCallback
        fileCallback = null
        cb?.invoke(uri)
    }

    /** ACTION_CREATE_DOCUMENT with a MIME type chosen per call (the stock contract fixes it). */
    private val createDocumentContract = object : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.first)
                .putExtra(Intent.EXTRA_TITLE, input.second)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == RESULT_OK) intent?.data else null
    }

    private val createDocumentLauncher = registerForActivityResult(createDocumentContract) { uri ->
        val cb = createCallback
        createCallback = null
        cb?.invoke(uri)
    }

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        toast(if (isDefaultLauncher()) "MacPad теперь главный экран" else "Главный экран не изменён")
        (windows.find(SettingsWindow.ID) as? SettingsWindow)?.rebuild()
    }

    private val appsListener: () -> Unit = {
        launchpad.onAppsChanged()
        spotlight.onAppsChanged()
        dock.onAppsChanged()
        desktop.onAppsChanged()
    }

    private val windowsListener: () -> Unit = {
        menuBar.onWindowsChanged()
        dock.onWindowsChanged()
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MacPrefs(this)
        palette = resolvePalette()
        appliedThemeKey = themeKey()

        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = AndroidWindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        apps = AppRepository(this)
        menus = MacMenu(this)
        buildDesktop()

        wallpaper.onError = { toast(it) }
        wallpaper.apply(force = true)
        wallpaperView.fallbackColor = if (palette.dark) Color.rgb(28, 28, 30) else Color.rgb(222, 222, 228)

        apps.addListener(appsListener)
        windows.addListener(windowsListener)
        apps.start()

        setupBackHandling()
        applySystemBars()
    }

    override fun onStart() {
        super.onStart()
        menuBar.onStart()
    }

    override fun onResume() {
        super.onResume()
        // Apps may have been installed/removed while another app was in front.
        apps.refreshIfChanged()
        if (appliedThemeKey != themeKey()) applySettings()
    }

    override fun onStop() {
        menuBar.onStop()
        menus.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        apps.removeListener(appsListener)
        windows.removeListener(windowsListener)
        apps.shutdown()
        wallpaper.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Orientation/size changes are handled in place (configChanges in the manifest) so open
        // windows survive rotation; only the system theme may need a re-theme.
        if (prefs.themeMode == "system" && appliedThemeKey != themeKey()) applySettings()
        root.post { updateLayout() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // Home pressed while MacPad is already in front: close transient UI.
            menus.dismiss()
            hideSpotlight()
            hideLaunchpad()
        }
    }

    // ---- Layout -------------------------------------------------------------------------------

    private fun buildDesktop() {
        // Children may draw outside their bounds (Dock magnification and hover labels).
        root = FrameLayout(this).apply { clipChildren = false }
        setContentView(root)
        // Created before the components so they may use host.dialogs right away.
        dialogs = DialogLayer(this, root)

        wallpaperView = WallpaperView(this)
        root.addView(wallpaperView, matchParent())
        wallpaper = WallpaperController(this, wallpaperView, prefs)

        desktop = DesktopIconsLayer(this)
        root.addView(desktop, matchParent())

        windowLayer = FrameLayout(this)
        root.addView(windowLayer, matchParent())
        windows = WindowManager(this, windowLayer)

        dock = Dock(this)
        root.addView(
            dock,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )

        menuBar = MenuBar(this)
        root.addView(menuBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, menuBar.barHeightPx, Gravity.TOP))

        launchpad = Launchpad(this).apply { visibility = View.GONE }
        root.addView(launchpad, matchParent())

        spotlight = Spotlight(this).apply { visibility = View.GONE }
        root.addView(spotlight, matchParent())

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            systemInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            imeBottom = ime.bottom
            updateLayout()
            WindowInsets.CONSUMED
        }
        dock.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) root.post { updateLayout() }
        }
        windowLayer.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) windows.onLayerResized()
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Positions the menu bar, Dock and the desktop/window layers around the system insets. */
    private fun updateLayout() {
        val i = systemInsets
        menuBar.setPadding(i.left, i.top, i.right, 0)
        updateLp(menuBar) { height = menuBar.barHeightPx + i.top }
        val dockBottom = i.bottom + dp(8)
        updateLp(dock) {
            bottomMargin = dockBottom
            leftMargin = i.left
            rightMargin = i.right
        }
        val dockReserve = (if (dock.height > 0) dock.height else dp(76)) + dockBottom + dp(6)
        val top = menuBar.barHeightPx + i.top
        updateLp(windowLayer) {
            topMargin = top
            bottomMargin = max(dockReserve, imeBottom)
            leftMargin = i.left
            rightMargin = i.right
        }
        updateLp(desktop) {
            topMargin = top
            bottomMargin = dockReserve
            leftMargin = i.left
            rightMargin = i.right
        }
        dialogs.bottomInset = imeBottom
    }

    private inline fun updateLp(v: View, block: FrameLayout.LayoutParams.() -> Unit) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val before = intArrayOf(lp.width, lp.height, lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
        lp.block()
        val after = intArrayOf(lp.width, lp.height, lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
        if (!before.contentEquals(after)) v.layoutParams = lp
    }

    // ---- Theme & settings ---------------------------------------------------------------------

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun resolvePalette(): Palette = when (prefs.themeMode) {
        "light" -> Palette.light()
        "system" -> if (isSystemNight()) Palette.dark() else Palette.light()
        else -> Palette.dark()
    }

    private fun themeKey(): String = prefs.themeMode + if (prefs.themeMode == "system") isSystemNight().toString() else ""

    override fun applySettings() {
        val newPalette = resolvePalette()
        val paletteChanged = newPalette != palette
        palette = newPalette
        appliedThemeKey = themeKey()
        wallpaper.apply()
        wallpaperView.fallbackColor = if (palette.dark) Color.rgb(28, 28, 30) else Color.rgb(222, 222, 228)
        applySystemBars()
        menus.dismiss()
        menuBar.applyPalette()
        dock.applyPalette()
        desktop.applyPalette()
        launchpad.applyPalette()
        spotlight.applyPalette()
        if (paletteChanged) windows.applyPalette()
        root.post { updateLayout() }
    }

    private fun applySystemBars() {
        val c = window.insetsController ?: return
        if (prefs.hideStatusBar) {
            c.hide(WindowInsets.Type.statusBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsets.Type.statusBars())
        }
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        c.setSystemBarsAppearance(if (palette.dark) 0 else mask, mask)
    }

    // ---- DesktopHost: apps --------------------------------------------------------------------

    override fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun launchApp(entry: AppEntry, source: View?) {
        when (apps.launch(this, entry, source)) {
            LaunchResult.OK -> {
                dock.bounce(entry.key)
                if (launchpad.isShowing) launchpad.hide()
                if (spotlight.isShowing) spotlight.hide()
            }
            LaunchResult.PROFILE_PAUSED -> dialogs.alert(
                "Рабочий профиль приостановлен",
                "Приложение «${entry.label}» находится в приостановленном рабочем профиле. " +
                    "Включите рабочие приложения в настройках Android и повторите попытку.",
            )
            LaunchResult.FAILED -> dialogs.alert(
                "Не удалось открыть «${entry.label}»",
                "У приложения нет доступной для запуска активности, либо оно было отключено или удалено. " +
                    "Список приложений обновлён.",
            )
        }
    }

    override fun showAppContextMenu(entry: AppEntry, anchor: View?, screenX: Int, screenY: Int) {
        val pinned = prefs.isPinned(entry.key)
        val onDesktop = prefs.isOnDesktop(entry.key)
        val items = listOf(
            MenuEntry.Header(entry.label),
            MenuEntry.Item("Открыть", glyph = Glyph.EXTERNAL) { launchApp(entry, anchor) },
            MenuEntry.Separator,
            MenuEntry.Item(if (pinned) "Убрать из Dock" else "Закрепить в Dock", glyph = Glyph.PIN) {
                setPinned(entry.key, !pinned)
            },
            MenuEntry.Item(if (onDesktop) "Убрать с рабочего стола" else "Добавить на рабочий стол", glyph = Glyph.DESKTOP) {
                setOnDesktop(entry.key, !onDesktop)
            },
            MenuEntry.Separator,
            MenuEntry.Item("Об этом приложении", glyph = Glyph.INFO) { openAppInfo(entry) },
            MenuEntry.Item("Сведения в Android", glyph = Glyph.GEAR) {
                if (!apps.openSystemAppInfo(this, entry)) toast("Не удалось открыть сведения о приложении")
            },
        )
        when {
            screenX >= 0 && screenY >= 0 -> menus.showAtScreen(screenX, screenY, items)
            anchor != null -> menus.show(anchor, items)
            else -> {
                val dm = resources.displayMetrics
                menus.showAtScreen(dm.widthPixels / 2 - dp(110), dm.heightPixels / 3, items)
            }
        }
    }

    // ---- DesktopHost: windows -----------------------------------------------------------------

    private fun hideOverlays() {
        menus.dismiss()
        if (spotlight.isShowing) spotlight.hide()
        if (launchpad.isShowing) launchpad.hide()
    }

    override fun openFinder(newWindow: Boolean, placeUri: String?) {
        hideOverlays()
        val existing = windows.windows.filterIsInstance<FinderWindow>().lastOrNull()
        val w = if (!newWindow && existing != null) {
            windows.focus(existing)
            existing
        } else {
            val id = windows.nextId("finder")
            windows.open(id) { FinderWindow(this, id) }
        }
        if (placeUri != null) w.openPlace(placeUri)
    }

    override fun openSettings(section: String?) {
        hideOverlays()
        val w = windows.open(SettingsWindow.ID) { SettingsWindow(this) }
        if (section != null) w.showSection(section)
    }

    override fun openAppInfo(entry: AppEntry) {
        hideOverlays()
        windows.open("appinfo:${entry.key}") { AppInfoWindow(this, entry.key) }
    }

    override fun openAbout() {
        hideOverlays()
        windows.open("about") { AboutWindow(this) }
    }

    override fun openNotes() {
        hideOverlays()
        windows.open("notes") { NotesWindow(this) }
    }

    override fun openShortcuts() {
        hideOverlays()
        windows.open("shortcuts") { ShortcutsWindow(this) }
    }

    override fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("Системные настройки недоступны")
        }
    }

    override fun showLaunchpad() {
        menus.dismiss()
        if (spotlight.isShowing) spotlight.hide()
        if (!launchpad.isShowing) launchpad.show()
    }

    override fun hideLaunchpad() {
        if (launchpad.isShowing) launchpad.hide()
    }

    override fun toggleLaunchpad() {
        if (launchpad.isShowing) hideLaunchpad() else showLaunchpad()
    }

    override fun showSpotlight(query: String) {
        menus.dismiss()
        spotlight.show(query)
    }

    override fun hideSpotlight() {
        if (spotlight.isShowing) spotlight.hide()
    }

    override fun toggleSpotlight() {
        if (spotlight.isShowing) hideSpotlight() else showSpotlight()
    }

    // ---- DesktopHost: storage -----------------------------------------------------------------

    override fun pickFolder(callback: (Uri?) -> Unit) {
        folderCallback = callback
        try {
            openTree.launch(null)
        } catch (_: ActivityNotFoundException) {
            folderCallback = null
            callback(null)
            toast("Выбор папок недоступен на этом устройстве")
        }
    }

    override fun pickFile(mimeTypes: Array<String>, callback: (Uri?) -> Unit) {
        fileCallback = callback
        try {
            openDocument.launch(mimeTypes)
        } catch (_: ActivityNotFoundException) {
            fileCallback = null
            callback(null)
            toast("Выбор файлов недоступен на этом устройстве")
        }
    }

    override fun createDocument(mimeType: String, suggestedName: String, callback: (Uri?) -> Unit) {
        createCallback = callback
        try {
            createDocumentLauncher.launch(mimeType to suggestedName)
        } catch (_: ActivityNotFoundException) {
            createCallback = null
            callback(null)
            toast("Сохранение файлов недоступно на этом устройстве")
        }
    }

    override fun pickWallpaperImage() {
        pickFile(arrayOf("image/*")) { uri ->
            if (uri != null) {
                wallpaper.setCustom(uri)
                (windows.find(SettingsWindow.ID) as? SettingsWindow)?.rebuild()
            }
        }
    }

    // ---- DesktopHost: shared state ------------------------------------------------------------

    override fun dockAnchor(windowId: String?): PointF = dock.anchorForWindow(windowId)

    override fun onDockChanged() {
        dock.refresh()
        root.post { updateLayout() }
    }

    override fun onDesktopChanged() {
        desktop.refresh()
    }

    override fun setPinned(key: String, pinned: Boolean) {
        if (pinned) prefs.pin(key) else prefs.unpin(key)
        onDockChanged()
    }

    override fun setOnDesktop(key: String, onDesktop: Boolean) {
        if (onDesktop) desktop.addApp(key) else desktop.removeApp(key)
    }

    override fun arrangeDesktop() {
        desktop.arrange()
    }

    override fun isDefaultLauncher(): Boolean = try {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    } catch (_: Exception) {
        false
    }

    override fun requestDefaultLauncher() {
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            toast("Откройте Настройки Android → Приложения → Приложения по умолчанию → Главный экран")
        }
    }

    // ---- Keyboard -----------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dialogs.isShowing) {
            if (dialogs.handleKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && handleShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Global shortcuts. Ctrl and Meta (Win/Cmd) are treated alike. Ctrl+Space may be taken by
     * Android for switching keyboard layouts on some devices, so Alt+Space and the Search key
     * open Spotlight too.
     */
    private fun handleShortcut(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val code = e.keyCode
        if ((code == KeyEvent.KEYCODE_SPACE && (ctrl || e.isAltPressed)) || code == KeyEvent.KEYCODE_SEARCH) {
            toggleSpotlight()
            return true
        }
        if (code == KeyEvent.KEYCODE_ESCAPE) return handleEscape(e)
        if (code == KeyEvent.KEYCODE_F4 || (ctrl && e.isShiftPressed && code == KeyEvent.KEYCODE_A)) {
            toggleLaunchpad()
            return true
        }
        if (code == KeyEvent.KEYCODE_F10 || (ctrl && code == KeyEvent.KEYCODE_F2)) {
            hideOverlays()
            return menuBar.openMenuFromKeyboard()
        }
        if (ctrl && code == KeyEvent.KEYCODE_F3) {
            hideOverlays()
            return dock.focusDock()
        }
        if (!ctrl) return false
        when (code) {
            KeyEvent.KEYCODE_COMMA -> {
                openSettings()
                return true
            }
            KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_F -> {
                focusSearch()
                return true
            }
            KeyEvent.KEYCODE_W -> {
                if (spotlight.isShowing || launchpad.isShowing) {
                    hideOverlays()
                    return true
                }
                return windows.closeActive()
            }
            KeyEvent.KEYCODE_M -> return windows.minimizeActive()
            KeyEvent.KEYCODE_N -> if (!e.isShiftPressed) {
                openFinder(newWindow = true)
                return true
            }
            KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_TAB -> return windows.cycle()
            KeyEvent.KEYCODE_SLASH -> {
                openShortcuts()
                return true
            }
        }
        if (!spotlight.isShowing && !launchpad.isShowing) {
            windows.activeWindow?.let { if (it.handleKey(e)) return true }
        }
        return false
    }

    /** Ctrl+L / Ctrl+F: focus the search field of whatever is in front, else open Spotlight. */
    private fun focusSearch() {
        when {
            launchpad.isShowing -> launchpad.focusSearch()
            spotlight.isShowing -> spotlight.show()
            else -> {
                val w = windows.activeWindow
                if (w == null || !w.focusSearchField()) showSpotlight()
            }
        }
    }

    private fun handleEscape(e: KeyEvent): Boolean {
        when {
            menus.isShowing -> menus.dismiss()
            spotlight.isShowing -> hideSpotlight()
            launchpad.isShowing -> hideLaunchpad()
            else -> {
                val w = windows.activeWindow
                if (w != null && w.handleKey(e)) return true
                val f = currentFocus
                if (f != null) {
                    if (f is EditText) hideKeyboard(f)
                    f.clearFocus()
                }
                windows.deactivateAll()
            }
        }
        return true
    }

    private fun hideKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBack()) return
                if (!isDefaultLauncher()) {
                    // Opened as a normal app: Back leaves MacPad.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun handleBack(): Boolean = when {
        dialogs.isShowing -> dialogs.dismissTop()
        menus.isShowing -> {
            menus.dismiss()
            true
        }
        spotlight.isShowing -> {
            hideSpotlight()
            true
        }
        launchpad.isShowing -> {
            hideLaunchpad()
            true
        }
        windows.activeWindow != null -> windows.closeActive()
        else -> false
    }
}
