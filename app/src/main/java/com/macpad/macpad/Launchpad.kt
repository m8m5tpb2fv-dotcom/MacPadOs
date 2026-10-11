package com.macpad.macpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Launchpad: the full-screen app catalogue over the blurred wallpaper.
 *
 * The search field at the top filters the grid as you type (ranked by [AppRepository.search]);
 * Enter launches the first result and Down moves into the grid. The grid is a [ScrollView] around
 * a custom [ViewGroup] that lays out one reusable cell per app (keyed by [AppEntry.key]), so
 * filtering only re-orders and hides cells instead of creating views.
 *
 * Cells: tap launches, long press or right-click opens the app's context menu, arrow keys move in
 * 2D, Page Up/Down and Home/End jump, Enter/Space launch, the Menu key opens the context menu, and
 * typing while a cell is focused continues in the search field. Tapping empty space closes
 * Launchpad; Esc is handled by [MainActivity].
 *
 * Contract with [MainActivity]: added as a MATCH_PARENT overlay at the top of the root, initially
 * GONE. [isShowing] changes immediately in [show] / [hide]; the view becomes GONE when the hide
 * animation ends.
 */
class Launchpad(private val host: DesktopHost) : FrameLayout(host.context) {

    /** True from [show] until [hide] (not affected by a running hide animation). */
    val isShowing: Boolean get() = showing

    private var showing = false
    private var animToken = 0
    private var previousFocus: View? = null
    private var enterArmed = false

    /** Apps changed while hidden: cells are synced on the next [show] to keep start-up light. */
    private var cellsDirty = false

    private val glassBackground = GlassDrawable(this, host.glass, Color.TRANSPARENT, host.palette.glassTintStrong, 0f)
    private val cells = LinkedHashMap<String, AppCell>()

    private val queryWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            applyFilter()
            scroll.scrollTo(0, 0)
        }
    }

    // ---- Views (recreated by build()) -------------------------------------------------------

    private lateinit var content: LinearLayout
    private lateinit var field: EditText
    private lateinit var scroll: ScrollView
    private lateinit var grid: AppGrid
    private lateinit var emptyLabel: TextView

    // ---- Cell metrics & style (recomputed by build()) ---------------------------------------

    private var iconPx = 0
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var rowGapPx = 0
    private var labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cellBackground: Drawable? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        background = glassBackground
        // Swallows touches and hover so nothing below reacts; a tap on empty space closes.
        isClickable = true
        isFocusable = false
        setOnClickListener { hide() }
        // Stay above the Dock and menu bar even if they use elevation; no outline, so no shadow.
        outlineProvider = null
        elevation = dpf(OVERLAY_ELEVATION_DP)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        // The root consumes window insets, so follow the on-screen keyboard through its animation.
        setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets = insets

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (showing) requestLayout()
                }
            },
        )
        build()
    }

    // ---- Public API -------------------------------------------------------------------------

    /** Shows Launchpad with an empty query and focuses the search field (without the soft keyboard). */
    fun show() {
        if (showing) {
            focusSearch()
            return
        }
        showing = true
        animToken++
        animate().cancel()
        content.animate().cancel()
        previousFocus = rootView.findFocus()?.takeIf { !isInside(it) }
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        enterArmed = false
        if (cellsDirty) syncCells()
        if (field.text.isNotEmpty()) field.setText("") else applyFilter()
        scroll.scrollTo(0, 0)
        visibility = VISIBLE
        field.requestFocus()
        if (host.animationsEnabled) {
            alpha = 0f
            content.scaleX = START_SCALE
            content.scaleY = START_SCALE
            animate().alpha(1f).setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator()).start()
            content.animate().scaleX(1f).scaleY(1f).setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            alpha = 1f
            content.scaleX = 1f
            content.scaleY = 1f
        }
    }

    /** Hides Launchpad: hides the keyboard, gives focus back, animates out, then clears the query. */
    fun hide() {
        if (!showing) return
        showing = false
        enterArmed = false
        hideKeyboard()
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        restoreFocus()
        val token = ++animToken
        if (host.animationsEnabled && isAttachedToWindow && visibility == VISIBLE) {
            animate().alpha(0f).setDuration(HIDE_MS).setInterpolator(AccelerateInterpolator())
                .withEndAction { if (token == animToken) finishHide() }
                .start()
            content.animate().scaleX(START_SCALE).scaleY(START_SCALE).setDuration(HIDE_MS)
                .setInterpolator(AccelerateInterpolator()).start()
        } else {
            finishHide()
        }
    }

    /** Palette or icon size changed: rebuild everything, keeping the query while showing. */
    fun applyPalette() {
        build()
    }

    /** The app list changed: add, remove or rebind cells (existing cells are reused). */
    fun onAppsChanged() {
        if (!showing) {
            cellsDirty = true
            return
        }
        val cellHadFocus = findFocus() is AppCell
        syncCells()
        applyFilter()
        if (cellHadFocus && !hasFocus()) field.requestFocus()
    }

    /** Ctrl+F / Ctrl+L: focuses the search field and selects its text. */
    fun focusSearch() {
        if (!showing) return
        field.requestFocus()
        field.selectAll()
    }

    // ---- View construction ------------------------------------------------------------------

    private fun build() {
        val p = host.palette
        val query = if (::field.isInitialized) field.text.toString() else ""
        val hadFocus = hasFocus()
        if (::content.isInitialized) content.animate().cancel()
        removeAllViews()
        cells.clear()

        glassBackground.tintColor = if (p.dark) withAlpha(Color.BLACK, DARK_SCRIM_ALPHA) else withAlpha(Color.WHITE, LIGHT_SCRIM_ALPHA)
        glassBackground.fallbackTint = p.glassTintStrong
        invalidate()

        iconPx = dp(
            when (host.prefs.iconSize) {
                "small" -> ICON_SMALL_DP
                "large" -> ICON_LARGE_DP
                else -> ICON_MEDIUM_DP
            },
        )
        labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP, resources.displayMetrics)
            typeface = Fonts.regular
            color = labelColor(p)
            if (p.dark) setShadowLayer(dpf(3f), 0f, dpf(1f), withAlpha(Color.BLACK, SHADOW_ALPHA))
        }
        cellWidthPx = iconPx + dp(CELL_EXTRA_WIDTH_DP)
        cellHeightPx = dp(CELL_PAD_TOP_DP) + iconPx + dp(LABEL_GAP_DP) +
            labelPaint.getFontMetricsInt(null) * LABEL_LINES + dp(CELL_PAD_BOTTOM_DP)
        rowGapPx = dp(ROW_GAP_DP)
        cellBackground = context.stateBackground(p, dpf(CELL_RADIUS_DP))

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        field = buildField(p)
        val fieldFrame = FieldFrame(context, dp(FIELD_MAX_WIDTH_DP))
        fieldFrame.addView(
            field,
            LayoutParams(dp(FIELD_MAX_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL),
        )
        content.addView(
            fieldFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(FIELD_TOP_DP)
            },
        )

        grid = AppGrid().apply { setPadding(dp(GRID_SIDE_DP), dp(GRID_TOP_DP), dp(GRID_SIDE_DP), dp(GRID_BOTTOM_DP)) }
        scroll = ScrollView(context).apply {
            isFillViewport = true
            // ScrollView makes itself focusable; keep focus on the field and the cells.
            isFocusable = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(FADE_DP))
            isVerticalScrollBarEnabled = true
            addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        emptyLabel = context.label("", EMPTY_SP, labelColor(p), 500).apply {
            gravity = Gravity.CENTER
            visibility = GONE
            if (p.dark) setShadowLayer(dpf(3f), 0f, dpf(1f), withAlpha(Color.BLACK, SHADOW_ALPHA))
        }
        val scrollFrame = FrameLayout(context).apply {
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                emptyLabel,
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(EMPTY_TOP_DP)
                },
            )
        }
        content.addView(
            scrollFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(GRID_GAP_DP) },
        )
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (showing) syncCells() else cellsDirty = true
        if (query.isNotEmpty()) {
            field.setText(query)
            field.setSelection(query.length)
        } else {
            applyFilter()
        }
        if (showing && hadFocus) field.requestFocus()
        if (!showing && visibility == VISIBLE) {
            animate().cancel()
            finishHide()
        }
    }

    private fun labelColor(p: Palette): Int = if (p.dark) Color.WHITE else p.text

    /** The rounded search field, restyled to sit on the blurred wallpaper. */
    private fun buildField(p: Palette): EditText = context.macTextField(p, "Поиск", FIELD_TEXT_SP, Glyph.SEARCH).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Fonts.regular
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        contentDescription = "Поиск приложений"
        setTextColor(labelColor(p))
        setHintTextColor(if (p.dark) withAlpha(Color.WHITE, 150) else p.textTertiary)
        val r = dpf(FIELD_RADIUS_DP)
        val base = if (p.dark) withAlpha(Color.WHITE, 34) else withAlpha(Color.WHITE, 190)
        val stroke = if (p.dark) withAlpha(Color.WHITE, 60) else withAlpha(Color.BLACK, 36)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundRect(base, r, p.focusRing, dp(2)))
            addState(intArrayOf(), roundRect(base, r, stroke, context.hairlinePx))
        }
        val glyph = GlyphDrawable(Glyph.SEARCH, if (p.dark) withAlpha(Color.WHITE, 170) else p.textSecondary)
        glyph.setBounds(0, 0, dp(16), dp(16))
        setCompoundDrawablesRelative(glyph, null, null, null)
        compoundDrawablePadding = dp(8)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        minHeight = dp(40)
        // Soft keyboard action key; the hardware Enter is handled in dispatchKeyEvent.
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                onFieldEnter()
                true
            } else {
                false
            }
        }
        addTextChangedListener(queryWatcher)
    }

    // ---- Cells & filtering ------------------------------------------------------------------

    /** Creates cells for new apps, rebinds changed ones and removes cells of uninstalled apps. */
    private fun syncCells() {
        cellsDirty = false
        val list = host.apps.apps
        val keep = HashSet<String>(list.size * 2)
        for (e in list) {
            keep.add(e.key)
            val cell = cells[e.key]
            if (cell == null) {
                val c = AppCell(e)
                cells[e.key] = c
                grid.addView(c)
            } else if (cell.entry != e) {
                cell.bind(e)
            }
        }
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val (key, cell) = it.next()
            if (key !in keep) {
                grid.removeView(cell)
                it.remove()
            }
        }
    }

    private fun applyFilter() {
        val q = field.text.toString()
        val entries = if (q.isBlank()) host.apps.apps else host.apps.search(q)
        val ordered = ArrayList<AppCell>(entries.size)
        for (e in entries) cells[e.key]?.let { ordered.add(it) }
        grid.setOrder(ordered)
        val message = when {
            !host.apps.isLoaded -> "Загрузка приложений…"
            ordered.isNotEmpty() -> ""
            q.isNotBlank() -> "Ничего не найдено"
            else -> "Нет приложений"
        }
        emptyLabel.text = message
        emptyLabel.visibility = if (message.isEmpty()) GONE else VISIBLE
    }

    private fun onFieldEnter() {
        if (field.text.isBlank()) {
            focusCell(0)
            return
        }
        val first = grid.order.firstOrNull() ?: return
        host.launchApp(first.entry, first)
    }

    // ---- Keyboard -----------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (showing) {
            val focused = findFocus()
            if (focused === field && handleFieldKey(event)) return true
            if (focused is AppCell && event.action == KeyEvent.ACTION_DOWN && handleCellKey(focused, event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleFieldKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Act on key up, and only for an Enter that went down here (not one that opened us).
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (e.repeatCount == 0) enterArmed = true
                } else if (e.action == KeyEvent.ACTION_UP && enterArmed) {
                    enterArmed = false
                    onFieldEnter()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (hasModifiers(e)) return false
                if (e.action == KeyEvent.ACTION_DOWN) focusCell(0)
                return true
            }
        }
        return false
    }

    private fun handleCellKey(cell: AppCell, e: KeyEvent): Boolean {
        val order = grid.order
        val i = order.indexOf(cell)
        if (i < 0) return false
        val n = order.size
        val cols = grid.columns.coerceAtLeast(1)
        val plain = !hasModifiers(e)
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> plain && focusCell(i - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> plain && focusCell(i + 1)
            KeyEvent.KEYCODE_DPAD_UP -> plain && (if (i >= cols) focusCell(i - cols) else focusField())
            KeyEvent.KEYCODE_DPAD_DOWN -> plain && (
                when {
                    i + cols < n -> focusCell(i + cols)
                    // The row below is shorter than this column: go to its last cell.
                    i / cols < (n - 1) / cols -> focusCell(n - 1)
                    else -> true
                }
                )
            KeyEvent.KEYCODE_MOVE_HOME -> focusCell(0)
            KeyEvent.KEYCODE_MOVE_END -> focusCell(n - 1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                val rows = max(1, scroll.height / (cellHeightPx + rowGapPx).coerceAtLeast(1))
                var target = if (e.keyCode == KeyEvent.KEYCODE_PAGE_UP) i - rows * cols else i + rows * cols
                if (target < 0) target = i % cols
                if (target >= n) target = min(((n - 1) / cols) * cols + i % cols, n - 1)
                focusCell(target)
            }
            KeyEvent.KEYCODE_MENU -> {
                host.showAppContextMenu(cell.entry, cell)
                true
            }
            KeyEvent.KEYCODE_DEL -> redirectToField(e)
            else -> plain && e.isPrintingKey && redirectToField(e)
        }
    }

    private fun hasModifiers(e: KeyEvent): Boolean = e.isCtrlPressed || e.isAltPressed || e.isMetaPressed

    /** Focuses the cell at [index] of the visible order (if any). Always consumes the key. */
    private fun focusCell(index: Int): Boolean {
        grid.order.getOrNull(index)?.requestFocus()
        return true
    }

    private fun focusField(): Boolean {
        field.requestFocus()
        field.setSelection(field.length())
        return true
    }

    /** Typing on a focused cell continues the search, like on macOS. */
    private fun redirectToField(e: KeyEvent): Boolean {
        focusField()
        field.dispatchKeyEvent(e)
        return true
    }

    /** Keeps Tab / arrow navigation inside Launchpad while it is showing. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!showing) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    // ---- Show/hide helpers ------------------------------------------------------------------

    private fun finishHide() {
        visibility = GONE
        alpha = 1f
        content.scaleX = 1f
        content.scaleY = 1f
        if (field.text.isNotEmpty()) field.setText("")
        scroll.scrollTo(0, 0)
    }

    private fun restoreFocus() {
        val prev = previousFocus
        previousFocus = null
        if (!hasFocus()) return
        if (prev != null && prev.isAttachedToWindow && prev.isShown && prev.requestFocus()) return
        findFocus()?.clearFocus()
    }

    private fun hideKeyboard() {
        try {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun isInside(v: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === this) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun imeBottom(): Int = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep content away from the system bars and the on-screen keyboard.
        val ins = host.systemInsets
        val bottom = max(ins.bottom, imeBottom())
        if (paddingLeft != ins.left || paddingTop != ins.top || paddingRight != ins.right || paddingBottom != bottom) {
            setPadding(ins.left, ins.top, ins.right, bottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        if (::content.isInitialized) content.animate().cancel()
        super.onDetachedFromWindow()
    }

    // ---- Inner views --------------------------------------------------------------------------

    /** Centres the search field and makes it min([maxWidthPx], 60 % of the available width). */
    private class FieldFrame(context: Context, private val maxWidthPx: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (childCount > 0) {
                val target = min(maxWidthPx, (MeasureSpec.getSize(widthMeasureSpec) * FIELD_WIDTH_FRACTION).roundToInt())
                val lp = getChildAt(0).layoutParams
                if (lp.width != target) lp.width = target
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /**
     * Lays out the visible cells ([order]) in rows; the column count follows the width. Hidden
     * cells are GONE so they neither draw nor take touches or focus. Taps between cells close
     * Launchpad.
     */
    private inner class AppGrid : ViewGroup(context) {

        var order: List<AppCell> = emptyList()
            private set
        var columns = MIN_COLUMNS
            private set

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var tapCandidate = false

        fun setOrder(list: List<AppCell>) {
            order = list
            val shown = HashSet<View>(list)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val vis = if (c in shown) VISIBLE else GONE
                if (c.visibility != vis) c.visibility = vis
            }
            requestLayout()
        }

        private fun contentWidth(totalWidth: Int): Int =
            min(totalWidth - paddingLeft - paddingRight, dp(MAX_GRID_WIDTH_DP)).coerceAtLeast(1)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val inner = contentWidth(w)
            columns = max(MIN_COLUMNS, inner / cellWidthPx.coerceAtLeast(1))
            val cw = min(cellWidthPx, inner / columns)
            val ws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
            val hs = MeasureSpec.makeMeasureSpec(cellHeightPx, MeasureSpec.EXACTLY)
            for (c in order) c.measure(ws, hs)
            val rows = (order.size + columns - 1) / columns
            val contentHeight = paddingTop + paddingBottom + rows * cellHeightPx + max(0, rows - 1) * rowGapPx
            setMeasuredDimension(w, resolveSize(contentHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val inner = contentWidth(w)
            val left = paddingLeft + (w - paddingLeft - paddingRight - inner) / 2
            val slot = inner.toFloat() / columns
            order.forEachIndexed { i, c ->
                val x = (left + slot * (i % columns) + (slot - c.measuredWidth) / 2f).roundToInt()
                val y = paddingTop + (i / columns) * (cellHeightPx + rowGapPx)
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    tapCandidate = event.buttonState and MotionEvent.BUTTON_SECONDARY == 0
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) tapCandidate = false
                MotionEvent.ACTION_UP -> if (tapCandidate) {
                    tapCandidate = false
                    hide()
                }
                MotionEvent.ACTION_CANCEL -> tapCandidate = false
            }
            return true
        }
    }

    /**
     * One app: icon and a centred two-line label, drawn directly (no child views) so hundreds of
     * cells stay cheap. Hover, press and keyboard focus come from the state background.
     */
    private inner class AppCell(initial: AppEntry) : View(context) {

        var entry: AppEntry = initial
            private set

        private val iconRect = Rect()
        private var textLayout: StaticLayout? = null
        private var pointerX = -1
        private var pointerY = -1

        init {
            isClickable = true
            isLongClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            background = cellBackground?.constantState?.newDrawable(resources)
                ?: context.stateBackground(host.palette, dpf(CELL_RADIUS_DP))
            if (host.animationsEnabled) applyPressScale(PRESS_SCALE)
            setOnClickListener { host.launchApp(entry, this) }
            setOnLongClickListener {
                host.showAppContextMenu(entry, this)
                true
            }
            // Right-click: open the menu where the pointer is, like on macOS.
            setOnContextClickListener {
                host.showAppContextMenu(entry, this, pointerX, pointerY)
                true
            }
            bind(initial)
        }

        fun bind(e: AppEntry) {
            entry = e
            contentDescription = if (e.isMainProfile) e.label else "${e.label} (рабочий профиль)"
            rebuildText()
            invalidate()
        }

        private fun rebuildText() {
            val w = width - dp(LABEL_INSET_DP) * 2
            if (w <= 0) {
                textLayout = null
                return
            }
            val text = entry.label
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(LABEL_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            textLayout = layout
            // Truncated names show in full as a tooltip on mouse hover.
            val truncated = layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0
            tooltipText = if (truncated) text else null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) rebuildText()
        }

        override fun onDraw(canvas: Canvas) {
            val top = dp(CELL_PAD_TOP_DP)
            val icon = min(iconPx, width - dp(LABEL_INSET_DP) * 2).coerceAtLeast(0)
            val left = (width - icon) / 2
            iconRect.set(left, top, left + icon, top + icon)
            val bmp = entry.icon
            if (!bmp.isRecycled) canvas.drawBitmap(bmp, null, iconRect, iconPaint)
            val layout = textLayout ?: return
            canvas.save()
            canvas.translate(dp(LABEL_INSET_DP).toFloat(), (top + iconPx + dp(LABEL_GAP_DP)).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) rememberPointer(event)
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) rememberPointer(event)
            return super.dispatchGenericMotionEvent(event)
        }

        private fun rememberPointer(e: MotionEvent) {
            pointerX = e.rawX.roundToInt()
            pointerY = e.rawY.roundToInt()
        }

        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }

    private companion object {
        const val OVERLAY_ELEVATION_DP = 30f
        const val DARK_SCRIM_ALPHA = 90
        const val LIGHT_SCRIM_ALPHA = 120
        const val SHADOW_ALPHA = 150

        const val FIELD_MAX_WIDTH_DP = 380
        const val FIELD_WIDTH_FRACTION = 0.6f
        const val FIELD_TEXT_SP = 16f
        const val FIELD_RADIUS_DP = 10f
        const val FIELD_TOP_DP = 26

        const val GRID_GAP_DP = 18
        const val GRID_SIDE_DP = 32
        const val GRID_TOP_DP = 8
        const val GRID_BOTTOM_DP = 28
        const val MAX_GRID_WIDTH_DP = 1180
        const val ROW_GAP_DP = 14
        const val MIN_COLUMNS = 3
        const val FADE_DP = 24
        const val EMPTY_TOP_DP = 72
        const val EMPTY_SP = 15f

        const val ICON_SMALL_DP = 56
        const val ICON_MEDIUM_DP = 68
        const val ICON_LARGE_DP = 84
        const val CELL_EXTRA_WIDTH_DP = 44
        const val CELL_PAD_TOP_DP = 8
        const val CELL_PAD_BOTTOM_DP = 8
        const val CELL_RADIUS_DP = 14f
        const val LABEL_GAP_DP = 6
        const val LABEL_INSET_DP = 4
        const val LABEL_LINES = 2
        const val LABEL_SP = 12.5f

        const val SHOW_MS = 180L
        const val HIDE_MS = 150L
        const val START_SCALE = 1.06f
        const val PRESS_SCALE = 0.93f
    }
}
