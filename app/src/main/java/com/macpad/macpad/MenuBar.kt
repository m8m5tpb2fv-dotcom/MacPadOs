package com.macpad.macpad

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The macOS-style menu bar at the top of the desktop.
 *
 * Left: the MacPad (logo) menu, the bold name of the active window ("Рабочий стол" when none)
 * and the Файл / Правка / Вид / Окно / Переход / Справка menus. Right: live network and battery
 * status, a Spotlight button and the clock. Every status item opens a small menu with details
 * and a shortcut to the matching Android settings.
 *
 * Contract with [MainActivity]: it sets the bar's height to [barHeightPx] + the top system inset
 * and its padding (left/top/right) to the system insets. The frosted background covers the whole
 * view; the content is laid out inside the padding.
 *
 * Keyboard: F10 / Ctrl+F2 open the MacPad menu ([openMenuFromKeyboard]); inside a menu
 * Left/Right switch to the neighbouring menu (wrapping). Titles are focusable and open their
 * menu with Enter, Space or Down. Mouse: right-click on a title opens its menu, right-click on
 * an empty part of the bar shows the bar's own options.
 *
 * When the bar is too narrow it progressively drops the date, then the "Переход" and "Справка"
 * titles, then the battery percentage.
 */
class MenuBar(private val host: DesktopHost) : FrameLayout(host.context) {

    /** Height of the bar itself in px (excluding the inset padding MainActivity adds). */
    val barHeightPx: Int = context.dp(34)

    /** Drop-down menus in bar order. [APP] is opened by the logo and by the active-window name. */
    private enum class BarMenu(val title: String) {
        APP("MacPad"),
        FILE("Файл"),
        EDIT("Правка"),
        VIEW("Вид"),
        WINDOW("Окно"),
        GO("Переход"),
        HELP("Справка"),
    }

    /** Transport of the default network, as shown by the status icon. */
    private enum class NetKind(val glyph: Glyph, val label: String) {
        NONE(Glyph.WIFI_OFF, ""),
        WIFI(Glyph.WIFI, "Wi-Fi"),
        ETHERNET(Glyph.ETHERNET, "Ethernet"),
        CELLULAR(Glyph.CELLULAR, "Мобильная сеть"),
        BLUETOOTH(Glyph.ETHERNET, "Bluetooth-модем"),
        USB(Glyph.ETHERNET, "USB-модем"),
        VPN(Glyph.LOCK, "VPN"),
        OTHER(Glyph.ETHERNET, "Сеть"),
    }

    private val main = Handler(Looper.getMainLooper())
    private val glassBackground = GlassDrawable(this, host.glass, host.palette.menuBarTint, host.palette.glassTintStrong, 0f)
    private val hairlinePaint = Paint()
    private val fitRunnable = Runnable { fitContent() }

    /** Content-provider calls (file type, folder grants). Its single thread exits when idle. */
    private val io: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "MacPad-MenuBar").apply { isDaemon = true }
        }
    }

    // ---- Views (recreated by build()) -------------------------------------------------------

    private var bar: LinearLayout? = null
    private lateinit var logoView: ImageView
    private lateinit var appNameView: TextView
    private val titleViews = EnumMap<BarMenu, TextView>(BarMenu::class.java)
    private lateinit var networkView: ImageView
    private lateinit var batteryView: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var batteryDrawable: BatteryDrawable
    private lateinit var clockView: TextView

    // ---- Live status ------------------------------------------------------------------------

    private var started = false
    private var timeRegistered = false
    private var batteryRegistered = false
    private var netCallback: NetworkWatcher? = null
    private var currentNetwork: Network? = null
    private var netKind = NetKind.NONE
    private var netValidated = false
    private var batteryPresent = true
    private var batteryLevel = -1
    private var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
    private var batteryPlugged = 0
    private var clockFull = ""
    private var clockShort = ""

    // ---- Layout & menu state ----------------------------------------------------------------

    /** 0 = everything, 1 = no date, 2 = also no "Переход"/"Справка", 3 = also no battery %. */
    private var compactLevel = 0
    private var appNameMaxPx = context.dp(APP_NAME_MAX_DP)
    private var lastAvailable = -1
    private var menuToken = 0
    private var highlighted: View? = null
    private var shownBarMenu: BarMenu? = null
    private var lastDismissedAnchor: View? = null
    private var lastDismissAt = 0L
    private var pointerX = -1
    private var pointerY = -1

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBatteryChanged(intent)
    }

    init {
        background = glassBackground
        // Empty parts of the bar: right-click (mouse) or long press (touch) show the bar options.
        setOnContextClickListener {
            showBarOptions()
            true
        }
        setOnLongClickListener {
            showBarOptions()
            true
        }
        updateClockStrings()
        build()
    }

    // ---- Public API -------------------------------------------------------------------------

    /** Palette or prefs (e.g. showDate) changed: rebuild. */
    fun applyPalette() {
        if (highlighted != null) host.menus.dismiss()
        clearHighlight()
        updateClockStrings()
        build()
    }

    /** Activity started: register clock/battery/network listeners. */
    fun onStart() {
        if (started) return
        started = true
        val ctx = context
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ctx.registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            timeRegistered = true
        } catch (_: Exception) {
            timeRegistered = false
        }
        updateClock()
        try {
            val sticky = ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            batteryRegistered = true
            if (sticky != null) onBatteryChanged(sticky)
        } catch (_: Exception) {
            batteryRegistered = false
        }
        registerNetwork()
    }

    /** Activity stopped: unregister listeners. */
    fun onStop() {
        if (!started) return
        started = false
        if (timeRegistered) {
            timeRegistered = false
            try {
                context.unregisterReceiver(timeReceiver)
            } catch (_: Exception) {
            }
        }
        if (batteryRegistered) {
            batteryRegistered = false
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }
        }
        unregisterNetwork()
    }

    /** Windows opened/closed/focused/renamed: refresh the bold active-window name. */
    fun onWindowsChanged() {
        val title = appTitle()
        if (appNameView.text.toString() == title) return
        appNameView.text = title
        appNameView.contentDescription = "$title — меню MacPad"
        scheduleFit()
    }

    /** F10 / Ctrl+F2: open the first menu with keyboard focus. */
    fun openMenuFromKeyboard(): Boolean {
        openMenu(BarMenu.APP, logoView, keyboard = true)
        return host.menus.isShowing
    }

    // ---- View construction ------------------------------------------------------------------

    private fun build() {
        val p = host.palette
        val focusedTag = findFocus()?.tag
        removeAllViews()
        titleViews.clear()
        glassBackground.tintColor = p.menuBarTint
        glassBackground.fallbackTint = p.glassTintStrong
        glassBackground.invalidateSelf()
        hairlinePaint.color = withAlpha(p.separator, Color.alpha(p.separator) * 3 / 5)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(EDGE_PADDING_DP), 0, dp(EDGE_PADDING_DP), 0)
        }

        // Left: logo, active-window name, menu titles.
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logoView = glyphItem(
            GlyphDrawable(Glyph.LOGO, p.text, strokeScale = 1.1f, intrinsicPx = dp(18)),
            "Меню MacPad (F10)",
            TAG_LOGO,
        )
        wireMenuAnchor(logoView, BarMenu.APP) { v, keyboard -> openMenu(BarMenu.APP, v, keyboard) }
        left.addView(logoView, LinearLayout.LayoutParams(dp(LOGO_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))

        val title = appTitle()
        appNameView = textItem(title, 600, TAG_APP).apply {
            maxWidth = appNameMaxPx
            contentDescription = "$title — меню MacPad"
        }
        wireMenuAnchor(appNameView, BarMenu.APP) { v, keyboard -> openMenu(BarMenu.APP, v, keyboard) }
        left.addView(appNameView, fullHeight())

        for (m in BarMenu.entries) {
            if (m == BarMenu.APP) continue
            val tv = textItem(m.title, 400, TAG_MENU_PREFIX + m.name)
            wireMenuAnchor(tv, m) { v, keyboard -> openMenu(m, v, keyboard) }
            if (m in COLLAPSIBLE && compactLevel >= 2) tv.visibility = View.GONE
            titleViews[m] = tv
            left.addView(tv, fullHeight())
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Right: network, battery, Spotlight, clock.
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        networkView = glyphItem(null, "", TAG_NETWORK)
        wireMenuAnchor(networkView, null) { v, keyboard -> showMenu(v, networkMenu(), keyboard, null) }
        right.addView(networkView, LinearLayout.LayoutParams(dp(ICON_ITEM_DP), ViewGroup.LayoutParams.MATCH_PARENT))

        batteryDrawable = BatteryDrawable(batteryLevel.coerceAtLeast(0), isCharging(), p.text, p.destructive, p.success)
        batteryText = context.label("", 12.5f, p.text, 400)
        val batteryIcon = ImageView(context).apply {
            setImageDrawable(batteryDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        batteryView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(BATTERY_PADDING_DP), 0, dp(BATTERY_PADDING_DP), 0)
            styleItem(this, TAG_BATTERY)
            addView(
                batteryText,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(BATTERY_TEXT_GAP_DP)
                },
            )
            addView(batteryIcon, LinearLayout.LayoutParams(dp(BATTERY_ICON_W_DP), dp(BATTERY_ICON_H_DP)))
        }
        wireMenuAnchor(batteryView, null) { v, keyboard -> showMenu(v, batteryMenu(), keyboard, null) }
        right.addView(batteryView, fullHeight())

        val search = glyphItem(
            GlyphDrawable(Glyph.SEARCH, p.text, strokeScale = 1.1f, intrinsicPx = dp(16)),
            "Spotlight (Ctrl+Space)",
            TAG_SEARCH,
        ).apply {
            setOnClickListener { host.toggleSpotlight() }
            setOnContextClickListener {
                host.toggleSpotlight()
                true
            }
        }
        right.addView(search, LinearLayout.LayoutParams(dp(ICON_ITEM_DP), ViewGroup.LayoutParams.MATCH_PARENT))

        clockView = textItem(if (compactLevel >= 1) clockShort else clockFull, 500, TAG_CLOCK)
        wireMenuAnchor(clockView, null) { v, keyboard -> showMenu(v, clockMenu(), keyboard, null) }
        right.addView(clockView, fullHeight())

        row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bar = row

        refreshNetworkView()
        refreshBatteryView()
        refreshClockView()
        lastAvailable = -1 // re-fit after the next layout pass
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    private fun fullHeight() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Focusable, clickable bar item with hover/pressed/open highlight and a keyboard focus ring. */
    private fun styleItem(v: View, tag: String) {
        val p = host.palette
        val inset = dp(ITEM_INSET_DP)
        v.tag = tag
        v.isFocusable = true
        v.isClickable = true
        // Full-height touch target; the highlight itself is inset like on macOS.
        v.background = InsetDrawable(context.stateBackground(p, dpf(6f), selected = p.pressed), 0, inset, 0, inset)
    }

    private fun textItem(text: String, weight: Int, tag: String): TextView =
        context.label(text, 14f, host.palette.text, weight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(TEXT_PADDING_DP), 0, dp(TEXT_PADDING_DP), 0)
            styleItem(this, tag)
        }

    private fun glyphItem(drawable: Drawable?, description: String, tag: String): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(drawable)
            if (description.isNotEmpty()) {
                contentDescription = description
                tooltipText = description
            }
            styleItem(this, tag)
        }

    /**
     * Click, right-click, Enter/Space/Down open the item's menu. For main menus, hovering the
     * title while another bar menu is open switches to it.
     */
    private fun wireMenuAnchor(v: View, menu: BarMenu?, open: (View, Boolean) -> Unit) {
        v.setOnClickListener { anchor -> onAnchorClick(anchor) { open(anchor, false) } }
        v.setOnContextClickListener { anchor ->
            open(anchor, false)
            true
        }
        v.setOnKeyListener { anchor, keyCode, event ->
            if (keyCode !in OPEN_KEYS || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) open(anchor, true)
            true
        }
        if (menu != null) {
            v.setOnHoverListener { anchor, event ->
                if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER && shownBarMenu != null &&
                    shownBarMenu != menu && host.menus.isShowing
                ) {
                    open(anchor, false)
                }
                false
            }
        }
    }

    // ---- Menus ------------------------------------------------------------------------------

    private fun onAnchorClick(anchor: View, open: () -> Unit) {
        if (highlighted === anchor && host.menus.isShowing) {
            host.menus.dismiss()
            return
        }
        // The tap that just closed this item's menu (outside touch) must not reopen it.
        if (lastDismissedAnchor === anchor && SystemClock.uptimeMillis() - lastDismissAt < REOPEN_GUARD_MS) return
        open()
    }

    private fun anchorFor(menu: BarMenu): View? = if (menu == BarMenu.APP) logoView else titleViews[menu]

    private fun openMenu(menu: BarMenu, anchor: View, keyboard: Boolean) {
        val entries = when (menu) {
            BarMenu.APP -> appMenu()
            BarMenu.FILE -> fileMenu()
            BarMenu.EDIT -> editMenu()
            BarMenu.VIEW -> viewMenu()
            BarMenu.WINDOW -> windowMenu()
            BarMenu.GO -> goMenu()
            BarMenu.HELP -> helpMenu()
        }
        showMenu(anchor, entries, keyboard, menu)
    }

    private fun showMenu(anchor: View, entries: List<MenuEntry>, keyboard: Boolean, menu: BarMenu?) {
        if (!anchor.isAttachedToWindow) return
        clearHighlight()
        val token = ++menuToken
        highlighted = anchor
        shownBarMenu = menu
        anchor.isSelected = true
        val horizontal: ((Int) -> Unit)? = if (menu == null) null else { dir -> switchMenuLater(menu, dir) }
        host.menus.show(
            anchor,
            entries,
            yOffset = dp(MENU_GAP_DP),
            keyboard = keyboard,
            onHorizontalKey = horizontal,
            onDismiss = { onMenuDismissed(token, anchor) },
            touchModal = false,
        )
        if (token == menuToken && !host.menus.isShowing) clearHighlight()
    }

    private fun onMenuDismissed(token: Int, anchor: View) {
        if (token != menuToken) return
        lastDismissedAnchor = anchor
        lastDismissAt = SystemClock.uptimeMillis()
        clearHighlight()
    }

    private fun clearHighlight() {
        highlighted?.isSelected = false
        highlighted = null
        shownBarMenu = null
    }

    /** Left/Right inside a bar menu: open the neighbouring visible menu (wrapping). */
    private fun switchMenuLater(from: BarMenu, dir: Int) {
        main.post {
            if (!isAttachedToWindow) return@post
            val order = BarMenu.entries.filter { anchorFor(it)?.visibility == View.VISIBLE }
            if (order.isEmpty()) return@post
            val i = order.indexOf(from).coerceAtLeast(0)
            val next = order[(i + dir + order.size) % order.size]
            anchorFor(next)?.let { openMenu(next, it, keyboard = true) }
        }
    }

    private fun appMenu(): List<MenuEntry> = listOf(
        MenuEntry.Item("Об этом устройстве") { host.openAbout() },
        MenuEntry.Separator,
        MenuEntry.Item("Настройки MacPad…", shortcut = "Ctrl+,") { host.openSettings() },
        MenuEntry.Item("Обои…") { host.openSettings(SettingsWindow.SECTION_WALLPAPER) },
        MenuEntry.Item("Системные настройки Android…") { host.openSystemSettings() },
        MenuEntry.Separator,
        MenuEntry.Item("Сделать MacPad главным экраном…", enabled = !host.isDefaultLauncher()) { host.requestDefaultLauncher() },
        MenuEntry.Item("Обновить список приложений") {
            host.apps.reload()
            host.toast("Список приложений обновляется")
        },
    )

    private fun fileMenu(): List<MenuEntry> {
        val wm = host.windows
        return listOf(
            MenuEntry.Item("Новое окно Finder", shortcut = "Ctrl+N") { host.openFinder(newWindow = true) },
            MenuEntry.Item("Открыть файл…") { openFile() },
            // Notes is a single document, so this opens it rather than promising a new note.
            MenuEntry.Item("Открыть Заметки") { host.openNotes() },
            MenuEntry.Separator,
            MenuEntry.Item("Закрыть окно", shortcut = "Ctrl+W", enabled = wm.activeWindow != null) { wm.closeActive() },
            MenuEntry.Item("Закрыть все окна", enabled = wm.windows.isNotEmpty()) { wm.closeAll() },
        )
    }

    /** Edit commands act on the text field that had focus when the menu was opened. */
    private fun editMenu(): List<MenuEntry> {
        val target = (host.activity.currentFocus as? EditText)?.takeIf { it.isEnabled && it.isAttachedToWindow }
        val hasText = (target?.length() ?: 0) > 0
        val secret = target?.transformationMethod is PasswordTransformationMethod
        val selection = target?.hasSelection() == true
        val canPaste = target != null && clipboardHasContent()

        fun command(title: String, shortcut: String, enabled: Boolean, block: (EditText) -> Unit) =
            MenuEntry.Item(title, shortcut = shortcut, enabled = target != null && enabled) {
                target?.let { runEditCommand(it, block) }
            }

        return listOf(
            command("Отменить", "Ctrl+Z", true) { sendUndo(it) },
            MenuEntry.Separator,
            command("Вырезать", "Ctrl+X", selection && !secret) { it.onTextContextMenuItem(android.R.id.cut) },
            command("Копировать", "Ctrl+C", selection && !secret) { it.onTextContextMenuItem(android.R.id.copy) },
            command("Вставить", "Ctrl+V", canPaste) { it.onTextContextMenuItem(android.R.id.paste) },
            command("Выделить всё", "Ctrl+A", hasText) { it.onTextContextMenuItem(android.R.id.selectAll) },
        )
    }

    private fun viewMenu(): List<MenuEntry> {
        val prefs = host.prefs
        val theme = prefs.themeMode
        val size = prefs.iconSize
        return listOf(
            MenuEntry.Item("Тёмная тема", checked = theme == "dark") { setTheme("dark") },
            MenuEntry.Item("Светлая тема", checked = theme == "light") { setTheme("light") },
            MenuEntry.Item("Как в системе", checked = theme == "system") { setTheme("system") },
            MenuEntry.Separator,
            MenuEntry.Item("Значки: мелкие", checked = size == "small") { setIconSize("small") },
            MenuEntry.Item("Значки: средние", checked = size == "medium") { setIconSize("medium") },
            MenuEntry.Item("Значки: крупные", checked = size == "large") { setIconSize("large") },
            MenuEntry.Separator,
            MenuEntry.Item("Значки на рабочем столе", checked = prefs.desktopIconsEnabled) {
                prefs.desktopIconsEnabled = !prefs.desktopIconsEnabled
                host.onDesktopChanged()
            },
            MenuEntry.Item("Дата в строке меню", checked = prefs.showDate) { toggleDate() },
            MenuEntry.Item("Скрывать строку состояния Android", checked = prefs.hideStatusBar) { toggleStatusBar() },
        )
    }

    private fun windowMenu(): List<MenuEntry> {
        val wm = host.windows
        val active = wm.activeWindow
        val list = mutableListOf<MenuEntry>(
            MenuEntry.Item("Свернуть", shortcut = "Ctrl+M", enabled = active != null) { wm.minimizeActive() },
            MenuEntry.Item(
                if (active?.isMaximized == true) "Восстановить размер" else "Развернуть",
                enabled = active?.resizable == true,
            ) { active?.let { wm.toggleMaximize(it) } },
            MenuEntry.Item("Следующее окно", shortcut = "Ctrl+`", enabled = wm.visibleWindows.size > 1) { wm.cycle() },
            MenuEntry.Separator,
        )
        val all = wm.windows.sortedWith(compareBy<MacWindow, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.windowId })
        if (all.isEmpty()) {
            list += MenuEntry.Item("Нет открытых окон", enabled = false)
        } else {
            for (w in all) {
                val name = w.title.ifBlank { "Без названия" } + if (w.isMinimized) " — свёрнуто" else ""
                list += MenuEntry.Item(name, glyph = Glyph.WINDOW, checked = w === active) { wm.focus(w) }
            }
        }
        return list
    }

    private fun goMenu(): List<MenuEntry> {
        val list = mutableListOf<MenuEntry>(
            MenuEntry.Item("Приложения", shortcut = "F4") { host.showLaunchpad() },
            MenuEntry.Item("Поиск", shortcut = "Ctrl+Space") { host.showSpotlight() },
            MenuEntry.Item("Finder") { host.openFinder() },
            MenuEntry.Separator,
        )
        for (place in host.prefs.finderPlaces) {
            list += MenuEntry.Item(place.name.ifBlank { "Папка" }, glyph = Glyph.FOLDER) { host.openFinder(placeUri = place.uri) }
        }
        list += MenuEntry.Item("Добавить папку…", glyph = Glyph.PLUS) { addFolder() }
        return list
    }

    private fun helpMenu(): List<MenuEntry> = listOf(
        MenuEntry.Item("Сочетания клавиш", shortcut = "Ctrl+/") { host.openShortcuts() },
        MenuEntry.Item("Об этом устройстве") { host.openAbout() },
    )

    private fun networkMenu(): List<MenuEntry> {
        val list = mutableListOf<MenuEntry>(MenuEntry.Header(networkStatusText()))
        if (netKind != NetKind.NONE && !netValidated) list += MenuEntry.Header("Нет доступа к интернету")
        list += MenuEntry.Separator
        list += MenuEntry.Item("Настройки интернета…") {
            launchFirst(
                "Настройки сети недоступны",
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
        }
        return list
    }

    private fun batteryMenu(): List<MenuEntry> {
        val list = mutableListOf<MenuEntry>()
        if (batteryShown()) {
            list += MenuEntry.Header("Аккумулятор: ${percentText()}")
            list += MenuEntry.Header(batteryStateText())
            chargeTimeText()?.let { list += MenuEntry.Header(it) }
        } else {
            list += MenuEntry.Header("Аккумулятор не обнаружен")
        }
        if (isPowerSaveOn()) list += MenuEntry.Header("Режим энергосбережения включён")
        list += MenuEntry.Separator
        list += MenuEntry.Item("Настройки аккумулятора…") {
            launchFirst(
                "Настройки аккумулятора недоступны",
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            )
        }
        return list
    }

    private fun clockMenu(): List<MenuEntry> = listOf(
        MenuEntry.Header(formatNow(FULL_DATE_SKELETON)),
        MenuEntry.Separator,
        MenuEntry.Item("Будильники и таймеры…") { openAlarms() },
        MenuEntry.Item("Дата и время…") {
            launchFirst("Настройки даты и времени недоступны", Intent(Settings.ACTION_DATE_SETTINGS))
        },
    )

    /** Right-click / long press on an empty part of the bar. */
    private fun showBarOptions() {
        val prefs = host.prefs
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = if (pointerX >= 0) pointerX else loc[0] + width / 2
        host.menus.showAtScreen(
            x,
            loc[1] + height + dp(MENU_GAP_DP),
            listOf(
                MenuEntry.Item("Дата в строке меню", checked = prefs.showDate) { toggleDate() },
                MenuEntry.Item("Скрывать строку состояния Android", checked = prefs.hideStatusBar) { toggleStatusBar() },
                MenuEntry.Separator,
                MenuEntry.Item("Настройки MacPad…", shortcut = "Ctrl+,") { host.openSettings() },
            ),
        )
    }

    // ---- Menu actions -----------------------------------------------------------------------

    private fun setTheme(mode: String) {
        if (host.prefs.themeMode == mode) return
        host.prefs.themeMode = mode
        host.applySettings()
    }

    private fun setIconSize(size: String) {
        if (host.prefs.iconSize == size) return
        host.prefs.iconSize = size
        host.applySettings()
    }

    private fun toggleDate() {
        host.prefs.showDate = !host.prefs.showDate
        applyPalette()
    }

    private fun toggleStatusBar() {
        host.prefs.hideStatusBar = !host.prefs.hideStatusBar
        host.applySettings()
    }

    private fun openFile() {
        host.pickFile(arrayOf("*/*")) { uri -> if (uri != null) openWithChooser(uri) }
    }

    /** Resolves the MIME type off the main thread, then offers the apps that can open the file. */
    private fun openWithChooser(uri: Uri) {
        val appContext = context.applicationContext
        io.execute {
            val type = try {
                appContext.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (!isAttachedToWindow || host.activity.isFinishing) return@post
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, type ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    host.activity.startActivity(
                        Intent.createChooser(view, "Открыть с помощью").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: ActivityNotFoundException) {
                    host.toast("Нет приложения для открытия этого файла")
                } catch (_: Exception) {
                    host.toast("Не удалось открыть файл")
                }
            }
        }
    }

    /** Grants a folder through the Storage Access Framework, adds it to Finder and opens it. */
    private fun addFolder() {
        host.pickFolder { uri ->
            if (uri == null) return@pickFolder
            val appContext = context.applicationContext
            val prefs = host.prefs
            io.execute {
                val place = try {
                    FinderPlaces.add(appContext, prefs, uri)
                } catch (_: Exception) {
                    null
                }
                main.post {
                    if (!isAttachedToWindow || host.activity.isFinishing) return@post
                    if (place == null) host.toast("Не удалось добавить папку") else host.openFinder(placeUri = place.uri)
                }
            }
        }
    }

    private fun openAlarms() {
        if (tryStart(Intent(AlarmClock.ACTION_SHOW_ALARMS))) return
        // Some clock apps protect ACTION_SHOW_ALARMS with the SET_ALARM permission: open the app.
        val launch = try {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(AlarmClock.ACTION_SHOW_ALARMS), PackageManager.ResolveInfoFlags.of(0))
                .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        } catch (_: Exception) {
            null
        }
        if (launch == null || !tryStart(launch)) host.toast("Будильники недоступны")
    }

    private fun tryStart(intent: Intent): Boolean = try {
        host.activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** Starts the first intent that works, otherwise shows [failMessage]. */
    private fun launchFirst(failMessage: String, vararg intents: Intent) {
        for (intent in intents) if (tryStart(intent)) return
        host.toast(failMessage)
    }

    private fun clipboardHasContent(): Boolean = try {
        context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
    } catch (_: Exception) {
        false
    }

    /**
     * Runs an edit command on [target] once the desktop window has input focus again (the menu
     * popup is still closing when the item fires, and clipboard access needs focus), then gives
     * the focus back to the field.
     */
    private fun runEditCommand(target: EditText, block: (EditText) -> Unit) {
        val run = Runnable {
            if (!target.isAttachedToWindow) return@Runnable
            try {
                block(target)
            } catch (_: Exception) {
                host.toast("Не удалось выполнить команду")
            }
            target.requestFocus()
        }
        if (target.hasWindowFocus()) {
            run.run()
            return
        }
        var done = false
        lateinit var listener: ViewTreeObserver.OnWindowFocusChangeListener
        val finish = Runnable {
            if (done) return@Runnable
            done = true
            val vto = target.viewTreeObserver
            if (vto.isAlive) vto.removeOnWindowFocusChangeListener(listener)
            run.run()
        }
        listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                main.removeCallbacks(finish)
                main.post(finish)
            }
        }
        target.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        main.postDelayed(finish, FOCUS_WAIT_MS)
    }

    /** Sends Ctrl+Z the way a hardware keyboard does: normal dispatch, then the shortcut. */
    private fun sendUndo(target: EditText) {
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta)
        if (!target.dispatchKeyEvent(down)) target.dispatchKeyShortcutEvent(down)
        target.dispatchKeyEvent(up)
    }

    // ---- Status: active window --------------------------------------------------------------

    private fun appTitle(): String {
        val active = try {
            host.windows.activeWindow
        } catch (_: Exception) {
            null
        }
        return active?.title?.ifBlank { "Окно" } ?: "Рабочий стол"
    }

    // ---- Status: network --------------------------------------------------------------------

    private fun registerNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val active = cm.activeNetwork
            currentNetwork = active
            setNetwork(active?.let { cm.getNetworkCapabilities(it) })
            val cb = NetworkWatcher()
            // Callbacks are delivered on the main thread through this handler.
            cm.registerDefaultNetworkCallback(cb, main)
            netCallback = cb
        } catch (_: Exception) {
            netCallback = null
        }
    }

    private fun unregisterNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    /** Follows the default network; ignores callbacks that arrive after it was unregistered. */
    private inner class NetworkWatcher : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (netCallback !== this) return
            currentNetwork = network
            setNetwork(networkCapabilities)
        }

        override fun onLost(network: Network) {
            if (netCallback !== this) return
            if (currentNetwork == null || currentNetwork == network) {
                currentNetwork = null
                setNetwork(null)
            }
        }
    }

    private fun setNetwork(caps: NetworkCapabilities?) {
        val kind = when {
            caps == null -> NetKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetKind.BLUETOOTH
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> NetKind.USB
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetKind.VPN
            else -> NetKind.OTHER
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (kind == netKind && validated == netValidated) return
        netKind = kind
        netValidated = validated
        refreshNetworkView()
    }

    private fun networkStatusText(): String =
        if (netKind == NetKind.NONE) "Нет подключения к сети" else "${netKind.label}: подключено"

    private fun refreshNetworkView() {
        val p = host.palette
        val limited = netKind != NetKind.NONE && !netValidated
        networkView.setImageDrawable(GlyphDrawable(netKind.glyph, if (limited) p.textSecondary else p.text, intrinsicPx = dp(NET_GLYPH_DP)))
        val status = networkStatusText() + if (limited) ", нет доступа к интернету" else ""
        networkView.contentDescription = status
        networkView.tooltipText = status
    }

    // ---- Status: battery --------------------------------------------------------------------

    private fun onBatteryChanged(intent: Intent) {
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val level = if (raw >= 0 && scale > 0) (raw * 100f / scale).roundToInt().coerceIn(0, 100) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        // The sticky broadcast also fires for voltage/temperature changes: skip those.
        if (present == batteryPresent && level == batteryLevel && status == batteryStatus && plugged == batteryPlugged) return
        batteryPresent = present
        batteryLevel = level
        batteryStatus = status
        batteryPlugged = plugged
        refreshBatteryView()
        scheduleFit()
    }

    private fun batteryShown(): Boolean = batteryPresent && batteryLevel >= 0

    private fun isCharging(): Boolean =
        batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL ||
            batteryPlugged != 0

    private fun percentText(): String = "$batteryLevel %"

    private fun batteryStateText(): String = when {
        batteryStatus == BatteryManager.BATTERY_STATUS_FULL || (batteryPlugged != 0 && batteryLevel >= 100) -> "Заряжен"
        batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING -> "Заряжается"
        batteryPlugged != 0 -> "Подключено к питанию, не заряжается"
        else -> "От аккумулятора"
    }

    private fun chargeTimeText(): String? {
        if (batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING) return null
        val ms = try {
            context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (ms <= 0L) return null
        val minutes = ((ms + 59_999L) / 60_000L).toInt()
        val h = minutes / 60
        val m = minutes % 60
        val text = when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            else -> "$m мин"
        }
        return "До полной зарядки: $text"
    }

    private fun isPowerSaveOn(): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    } catch (_: Exception) {
        false
    }

    private fun refreshBatteryView() {
        val shown = batteryShown()
        setShown(batteryView, shown)
        if (!shown) return
        batteryDrawable.batteryLevel = batteryLevel
        batteryDrawable.charging = isCharging()
        batteryDrawable.invalidateSelf()
        val pct = percentText()
        if (batteryText.text.toString() != pct) batteryText.text = pct
        setShown(batteryText, compactLevel < 3)
        val desc = "Аккумулятор: $pct, ${batteryStateText().lowercase(Locale.getDefault())}"
        batteryView.contentDescription = desc
        batteryView.tooltipText = desc
    }

    // ---- Status: clock ----------------------------------------------------------------------

    private fun updateClock() {
        updateClockStrings()
        refreshClockView()
        scheduleFit()
    }

    private fun updateClockStrings() {
        val time = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
        clockShort = formatNow(time)
        clockFull = if (host.prefs.showDate) formatNow("EEEdMMM$time") else clockShort
    }

    private fun refreshClockView() {
        val text = if (compactLevel >= 1) clockShort else clockFull
        if (clockView.text.toString() != text) clockView.text = text
        val fullDate = formatNow(FULL_DATE_SKELETON)
        clockView.contentDescription = "$fullDate, $clockShort"
        clockView.tooltipText = fullDate
    }

    /** Formats the current time with the locale's best pattern for [skeleton], capitalised. */
    private fun formatNow(skeleton: String): String {
        val locale = Locale.getDefault()
        return try {
            val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
            android.icu.text.SimpleDateFormat(pattern, locale).format(Date())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        } catch (_: Exception) {
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(Date())
        }
    }

    // ---- Responsive layout ------------------------------------------------------------------

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Size or inset padding changed: re-fit after this pass (never re-layout from inside it).
        val available = availableWidth()
        if (available != lastAvailable) {
            lastAvailable = available
            scheduleFit()
        }
    }

    private fun availableWidth(): Int =
        width - paddingLeft - paddingRight - (bar?.let { it.paddingLeft + it.paddingRight } ?: 0)

    private fun scheduleFit() {
        removeCallbacks(fitRunnable)
        post(fitRunnable)
    }

    /**
     * Picks the smallest compact level whose content fits, from text widths only, so the result
     * depends on the width and the texts but never on the previous layout (no feedback loops).
     */
    private fun fitContent() {
        if (bar == null) return
        val available = availableWidth()
        if (available <= 0) return
        val appNatural = min(textWidth(appNameView, appNameView.text), dp(APP_NAME_MAX_DP))
        var titles = 0
        var optional = 0
        for ((m, tv) in titleViews) {
            val w = textWidth(tv, tv.text)
            titles += w
            if (m in COLLAPSIBLE) optional += w
        }
        val shown = batteryShown()
        val battery = if (shown) dp(BATTERY_PADDING_DP) * 2 + dp(BATTERY_ICON_W_DP) else 0
        val percent = if (shown) textWidth(batteryText, percentText()) + dp(BATTERY_TEXT_GAP_DP) else 0
        val fixed = dp(LOGO_WIDTH_DP) + dp(ICON_ITEM_DP) * 2 + battery + dp(GROUP_GAP_DP)
        val clockFullWidth = textWidth(clockView, clockFull)
        val clockShortWidth = textWidth(clockView, clockShort)

        fun required(level: Int): Int = fixed + appNatural + titles -
            (if (level >= 2) optional else 0) +
            (if (level >= 1) clockShortWidth else clockFullWidth) +
            (if (level >= 3) 0 else percent)

        val level = (0..MAX_COMPACT).firstOrNull { required(it) <= available } ?: MAX_COMPACT
        val appMax = (available - (required(level) - appNatural)).coerceIn(dp(APP_NAME_MIN_DP), dp(APP_NAME_MAX_DP))
        applyCompact(level, appMax)
    }

    private fun applyCompact(level: Int, appMax: Int) {
        compactLevel = level
        appNameMaxPx = appMax
        if (appNameView.maxWidth != appMax) appNameView.maxWidth = appMax
        for (m in COLLAPSIBLE) titleViews[m]?.let { setShown(it, level < 2) }
        if (batteryShown()) setShown(batteryText, level < 3)
        val clock = if (level >= 1) clockShort else clockFull
        if (clockView.text.toString() != clock) clockView.text = clock
        // A title that just disappeared cannot keep its menu open.
        if (highlighted?.visibility == View.GONE) host.menus.dismiss()
    }

    private fun textWidth(tv: TextView, text: CharSequence): Int =
        ceil(tv.paint.measureText(text, 0, text.length)).toInt() + tv.paddingLeft + tv.paddingRight

    private fun setShown(v: View, shown: Boolean) {
        val vis = if (shown) View.VISIBLE else View.GONE
        if (v.visibility != vis) v.visibility = vis
    }

    // ---- Drawing & input --------------------------------------------------------------------

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val line = context.hairlinePx
        canvas.drawRect(0f, (height - line).toFloat(), width.toFloat(), height.toFloat(), hairlinePaint)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) rememberPointer(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) rememberPointer(event)
        return super.dispatchGenericMotionEvent(event)
    }

    private fun rememberPointer(e: MotionEvent) {
        pointerX = e.rawX.roundToInt()
        pointerY = e.rawY.roundToInt()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fitRunnable)
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val EDGE_PADDING_DP = 6
        const val LOGO_WIDTH_DP = 38
        const val TEXT_PADDING_DP = 10
        const val ICON_ITEM_DP = 36
        const val ITEM_INSET_DP = 4
        const val NET_GLYPH_DP = 17
        const val BATTERY_PADDING_DP = 8
        const val BATTERY_ICON_W_DP = 26
        const val BATTERY_ICON_H_DP = 13
        const val BATTERY_TEXT_GAP_DP = 5
        const val GROUP_GAP_DP = 16
        const val APP_NAME_MIN_DP = 56
        const val APP_NAME_MAX_DP = 260
        const val MENU_GAP_DP = 2
        const val MAX_COMPACT = 3
        const val REOPEN_GUARD_MS = 300L
        const val FOCUS_WAIT_MS = 500L
        const val FULL_DATE_SKELETON = "EEEEdMMMMyyyy"

        const val TAG_LOGO = "menubar:logo"
        const val TAG_APP = "menubar:app"
        const val TAG_MENU_PREFIX = "menubar:menu:"
        const val TAG_NETWORK = "menubar:network"
        const val TAG_BATTERY = "menubar:battery"
        const val TAG_SEARCH = "menubar:search"
        const val TAG_CLOCK = "menubar:clock"

        val OPEN_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )

        /** Titles hidden first when the bar is narrow. */
        val COLLAPSIBLE = setOf(BarMenu.GO, BarMenu.HELP)
    }
}
