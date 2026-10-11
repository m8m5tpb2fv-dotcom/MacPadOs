package com.macpad.macpad

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Desktop shortcuts, laid out on a grid that starts at the top-right corner and fills columns
 * top to bottom (like macOS). Positions are stored in [MacPrefs.desktopItems].
 *
 * Icons: tap launches, long press or right-click opens the app's context menu, dragging moves the
 * icon to another cell (swapping with an icon already there). Keyboard: arrows move focus,
 * Enter/Space launch, Menu or Shift+F10 open the context menu.
 *
 * Empty area: tap deactivates windows; long press or right-click shows the desktop menu.
 */
class DesktopIconsLayer(private val host: DesktopHost) : FrameLayout(host.context) {

    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private val icons = LinkedHashMap<String, IconView>()
    private val displayCells = HashMap<String, Cell>()
    private var selectedKey: String? = null

    private var iconPx = 0
    private var cellW = 0
    private var cellH = 0
    private var pad = 0

    // Empty-area gesture state.
    private var emptyDownX = 0f
    private var emptyDownY = 0f
    private var emptyMoved = false
    private var emptyLongFired = false
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastMenuAt = 0L
    private val emptyLongPress = Runnable {
        emptyLongFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showDesktopMenu(lastPointerX, lastPointerY)
    }

    private data class Cell(val col: Int, val row: Int)

    init {
        clipChildren = false
        isFocusable = false
        setOnContextClickListener {
            showDesktopMenu(lastPointerX, lastPointerY)
            true
        }
        computeMetrics()
    }

    // ---- Public API -------------------------------------------------------------------------

    /** Palette or icon size changed: rebuild every icon. */
    fun applyPalette() {
        computeMetrics()
        removeAllViews()
        icons.clear()
        refresh()
    }

    /** Re-reads prefs and rebuilds/repositions icons (views are reused by key). */
    fun refresh() {
        if (!host.prefs.desktopIconsEnabled) {
            removeAllViews()
            icons.clear()
            displayCells.clear()
            return
        }
        val items = host.prefs.desktopItems
        val keys = items.map { it.key }.toSet()
        icons.keys.filter { it !in keys }.forEach { k -> icons.remove(k)?.let { removeView(it) } }
        for (item in items) {
            val entry = host.apps.findByKey(item.key) ?: continue
            val view = icons[item.key] ?: IconView(item.key).also {
                icons[item.key] = it
                addView(it, LayoutParams(cellW, cellH))
            }
            view.bind(entry)
        }
        // Icons whose app is not (yet) known are hidden but kept in prefs.
        icons.keys.filter { host.apps.findByKey(it) == null }.forEach { k -> icons.remove(k)?.let { removeView(it) } }
        layoutIcons(animate = false)
        updateSelection()
    }

    /** The app list changed: drop shortcuts of removed apps and rebind icons. */
    fun onAppsChanged() {
        if (host.apps.isLoaded) {
            val items = host.prefs.desktopItems
            val alive = items.filter { host.apps.findByKey(it.key) != null }
            if (alive.size != items.size) host.prefs.desktopItems = alive
        }
        refresh()
    }

    /** Adds a shortcut in the first free cell (or highlights it if it is already there). */
    fun addApp(key: String) {
        if (!host.prefs.desktopIconsEnabled) host.prefs.desktopIconsEnabled = true
        val items = host.prefs.desktopItems
        if (items.any { it.key == key }) {
            refresh()
            selectedKey = key
            updateSelection()
            icons[key]?.let { pulse(it) }
            return
        }
        val (cols, rows) = gridSize()
        val used = displayCells.values.toHashSet().apply { items.forEach { add(Cell(it.col, it.row)) } }
        var cell: Cell? = null
        loop@ for (c in 0 until cols) {
            for (r in 0 until rows) {
                val candidate = Cell(c, r)
                if (candidate !in used) {
                    cell = candidate
                    break@loop
                }
            }
        }
        if (cell == null) {
            cell = Cell(cols - 1, rows - 1)
            host.toast("Рабочий стол заполнен")
        }
        host.prefs.desktopItems = items + DesktopItem(key, cell.col, cell.row)
        selectedKey = key
        refresh()
    }

    fun removeApp(key: String) {
        host.prefs.desktopItems = host.prefs.desktopItems.filter { it.key != key }
        if (selectedKey == key) selectedKey = null
        refresh()
    }

    /** Sorts shortcuts by name and packs them column by column from the top-right. */
    fun arrange() {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val items = host.prefs.desktopItems
            .sortedWith { a, b ->
                collator.compare(host.apps.findByKey(a.key)?.label.orEmpty(), host.apps.findByKey(b.key)?.label.orEmpty())
            }
        val (_, rows) = gridSize()
        host.prefs.desktopItems = items.mapIndexed { i, it -> DesktopItem(it.key, i / rows, i % rows) }
        layoutIcons(animate = true)
    }

    fun focusFirstIcon(): Boolean {
        val first = icons.entries
            .sortedWith(compareBy({ displayCells[it.key]?.col ?: Int.MAX_VALUE }, { displayCells[it.key]?.row ?: Int.MAX_VALUE }))
            .firstOrNull()?.value ?: return false
        return first.requestFocus()
    }

    // ---- Layout -----------------------------------------------------------------------------

    private fun computeMetrics() {
        val iconDp = when (host.prefs.iconSize) {
            "small" -> 48
            "large" -> 76
            else -> 60
        }
        iconPx = dp(iconDp)
        cellW = iconPx + dp(44)
        cellH = iconPx + dp(46)
        pad = dp(14)
    }

    private fun gridSize(): Pair<Int, Int> {
        val w = if (width > 0) width else context.resources.displayMetrics.widthPixels
        val h = if (height > 0) height else (context.resources.displayMetrics.heightPixels * 0.75f).toInt()
        val cols = max(1, (w - 2 * pad) / cellW)
        val rows = max(1, (h - 2 * pad) / cellH)
        return cols to rows
    }

    private fun originOf(cell: Cell): Pair<Float, Float> {
        val w = if (width > 0) width else context.resources.displayMetrics.widthPixels
        return (w - pad - (cell.col + 1) * cellW).toFloat() to (pad + cell.row * cellH).toFloat()
    }

    private fun cellAt(centerX: Float, centerY: Float): Cell {
        val (cols, rows) = gridSize()
        val col = floor((width - pad - centerX) / cellW).toInt().coerceIn(0, cols - 1)
        val row = floor((centerY - pad) / cellH).toInt().coerceIn(0, rows - 1)
        return Cell(col, row)
    }

    /**
     * Places icons in their stored cells. Icons whose stored cell is outside the current grid
     * (e.g. after rotation) or taken are shown in the nearest free cell without rewriting prefs.
     */
    private fun layoutIcons(animate: Boolean) {
        val (cols, rows) = gridSize()
        val items = host.prefs.desktopItems.filter { icons.containsKey(it.key) }
        val taken = HashSet<Cell>()
        displayCells.clear()
        val pending = mutableListOf<DesktopItem>()
        for (it in items) {
            val c = Cell(it.col, it.row)
            if (it.col in 0 until cols && it.row in 0 until rows && c !in taken) {
                taken += c
                displayCells[it.key] = c
            } else {
                pending += it
            }
        }
        for (it in pending) {
            var best: Cell? = null
            var bestDist = Int.MAX_VALUE
            for (c in 0 until cols) for (r in 0 until rows) {
                val cand = Cell(c, r)
                if (cand in taken) continue
                val d = abs(c - it.col.coerceIn(0, cols - 1)) + abs(r - it.row.coerceIn(0, rows - 1))
                if (d < bestDist) {
                    bestDist = d
                    best = cand
                }
            }
            val cell = best ?: Cell(cols - 1, rows - 1)
            taken += cell
            displayCells[it.key] = cell
        }
        for ((key, view) in icons) {
            val cell = displayCells[key] ?: continue
            val (x, y) = originOf(cell)
            if (animate && host.animationsEnabled && view.isLaidOut) {
                view.animate().x(x).y(y).setDuration(220).start()
            } else {
                view.x = x
                view.y = y
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) post { layoutIcons(animate = false) }
    }

    // ---- Selection & feedback ---------------------------------------------------------------

    private fun updateSelection() {
        for ((key, view) in icons) view.setSelectedLook(key == selectedKey || view.isFocused)
    }

    private fun pulse(v: View) {
        if (!host.animationsEnabled) return
        v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    // ---- Empty area -------------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastPointerX = ev.rawX
        lastPointerY = ev.rawY
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        lastPointerX = event.rawX
        lastPointerY = event.rawY
        return super.dispatchHoverEvent(event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                    showDesktopMenu(e.rawX, e.rawY)
                    return true
                }
                emptyDownX = e.rawX
                emptyDownY = e.rawY
                emptyMoved = false
                emptyLongFired = false
                main.postDelayed(emptyLongPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!emptyMoved && hypot(e.rawX - emptyDownX, e.rawY - emptyDownY) > slop) {
                    emptyMoved = true
                    main.removeCallbacks(emptyLongPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(emptyLongPress)
                if (!emptyMoved && !emptyLongFired) {
                    selectedKey = null
                    updateSelection()
                    host.windows.deactivateAll()
                    rootView.findFocus()?.clearFocus()
                }
            }
            MotionEvent.ACTION_CANCEL -> main.removeCallbacks(emptyLongPress)
        }
        return true
    }

    private fun showDesktopMenu(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMenuAt < 300) return
        lastMenuAt = now
        val prefs = host.prefs
        host.menus.showAtScreen(
            x.toInt(), y.toInt(),
            listOf(
                MenuEntry.Item("Новое окно Finder", glyph = Glyph.FOLDER) { host.openFinder(newWindow = true) },
                MenuEntry.Item("Приложения", glyph = Glyph.GRID) { host.showLaunchpad() },
                MenuEntry.Separator,
                MenuEntry.Item("Упорядочить значки", enabled = icons.isNotEmpty()) { arrange() },
                MenuEntry.Item("Показывать значки", checked = prefs.desktopIconsEnabled) {
                    prefs.desktopIconsEnabled = !prefs.desktopIconsEnabled
                    refresh()
                },
                MenuEntry.Separator,
                MenuEntry.Item("Сменить обои…", glyph = Glyph.IMAGE) { host.openSettings(SettingsWindow.SECTION_WALLPAPER) },
                MenuEntry.Item("Настройки MacPad…", shortcut = "Ctrl+,", glyph = Glyph.GEAR) { host.openSettings() },
            ),
        )
    }

    private fun showIconMenu(view: IconView) {
        val entry = host.apps.findByKey(view.key) ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastMenuAt < 300) return
        lastMenuAt = now
        selectedKey = view.key
        updateSelection()
        host.showAppContextMenu(entry, view)
    }

    // ---- Icon view --------------------------------------------------------------------------

    /** One desktop shortcut: icon + label, with its own tap / long-press / drag handling. */
    private inner class IconView(val key: String) : LinearLayout(context) {

        private val image = ImageView(context)
        private val labelView: TextView = TextView(context)

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var longFired = false
        private val longPress = Runnable {
            longFired = true
            isPressed = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showIconMenu(this)
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
            isFocusable = true
            isClickable = true
            background = context.stateBackground(host.palette, dpf(10f))
            addView(image, LayoutParams(iconPx, iconPx))
            labelView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                typeface = Fonts.medium
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                setShadowLayer(dpf(4f), 0f, dpf(1f), 0x99000000.toInt())
                setPadding(dp(5), dp(2), dp(5), dp(3))
            }
            addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
            setOnContextClickListener {
                showIconMenu(this)
                true
            }
            setOnFocusChangeListener { _, _ -> updateSelection() }
            setOnTouchListener { _, e -> handleTouch(e) }
            setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
        }

        fun bind(entry: AppEntry) {
            image.setImageBitmap(entry.icon)
            labelView.text = entry.label
            contentDescription = entry.label
        }

        fun setSelectedLook(selected: Boolean) {
            image.background = if (selected) roundRect(Color.argb(46, 255, 255, 255), dpf(12f)) else null
            labelView.background = if (selected) roundRect(host.palette.selection, dpf(5f)) else null
            labelView.setShadowLayer(if (selected) 0f else dpf(4f), 0f, dpf(1f), 0x99000000.toInt())
        }

        private fun launch() {
            val entry = host.apps.findByKey(key) ?: return
            selectedKey = key
            updateSelection()
            host.launchApp(entry, image)
        }

        private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return when {
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER -> {
                    launch()
                    true
                }
                keyCode == KeyEvent.KEYCODE_MENU || (keyCode == KeyEvent.KEYCODE_F10 && event.isShiftPressed) -> {
                    showIconMenu(this)
                    true
                }
                else -> false
            }
        }

        private fun handleTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                        showIconMenu(this)
                        return true
                    }
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = x
                    startY = y
                    dragging = false
                    longFired = false
                    isPressed = true
                    main.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && !longFired && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        isPressed = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        bringToFront()
                        elevation = dpf(12f)
                        alpha = 0.9f
                        scaleX = 1.08f
                        scaleY = 1.08f
                        selectedKey = key
                        updateSelection()
                    }
                    if (dragging) {
                        x = startX + dx
                        y = startY + dy
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    isPressed = false
                    when {
                        dragging -> drop()
                        !longFired -> launch()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    isPressed = false
                    if (dragging) {
                        dragging = false
                        endDragLook()
                        layoutIcons(animate = true)
                    }
                }
            }
            return true
        }

        private fun endDragLook() {
            elevation = 0f
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }

        /** Snaps to the cell under the icon centre, swapping with an icon already there. */
        private fun drop() {
            endDragLook()
            val target = cellAt(x + width / 2f, y + height / 2f)
            val from = displayCells[key]
            val items = host.prefs.desktopItems.toMutableList()
            val otherKey = displayCells.entries.firstOrNull { it.value == target && it.key != key }?.key
            for (i in items.indices) {
                val it = items[i]
                when {
                    it.key == key -> items[i] = DesktopItem(key, target.col, target.row)
                    it.key == otherKey && from != null -> items[i] = DesktopItem(it.key, from.col, from.row)
                }
            }
            host.prefs.desktopItems = items
            layoutIcons(animate = true)
        }
    }
}
