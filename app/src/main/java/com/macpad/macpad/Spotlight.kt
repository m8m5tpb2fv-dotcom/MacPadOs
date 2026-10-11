package com.macpad.macpad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Spotlight: a floating search panel (Ctrl+Space, Alt+Space, the Search key or the menu bar's
 * magnifier).
 *
 * Typing searches installed apps, MacPad's own commands (windows, theme, Finder folders) and
 * evaluates arithmetic ("2+2*3", "15%", "2^10", "1,5×4"). Results are grouped into sections; the
 * first actionable row is selected, Up/Down (and Page Up/Down) move the selection while keyboard
 * focus stays in the field, and Enter activates it. Mouse: hover highlights, click activates,
 * right-click (or long press) on an app opens its context menu. A tap outside the panel closes
 * Spotlight; Esc is handled by [MainActivity].
 *
 * Contract with [MainActivity]: added as a transparent MATCH_PARENT overlay at the top of the
 * root, initially GONE. [isShowing] changes immediately in [show] / [hide]; the view becomes GONE
 * when the hide animation ends.
 */
class Spotlight(private val host: DesktopHost) : FrameLayout(host.context) {

    /** True from [show] until [hide] (not affected by a running hide animation). */
    val isShowing: Boolean get() = showing

    /** One row of the result list; a null [action] makes it an informational (disabled) row. */
    private class Result(
        val id: String,
        val title: String,
        val secondary: String?,
        val icon: Bitmap?,
        val app: AppEntry? = null,
        val prominent: Boolean = false,
        val action: ((View) -> Unit)? = null,
    )

    private class Section(val title: String?, val items: List<Result>)

    /** A built-in MacPad command matched by its title or keywords. */
    private class Command(
        val id: String,
        val title: String,
        val secondary: String,
        val keywords: List<String>,
        val icon: () -> Bitmap?,
        val run: () -> Unit,
    )

    /** An actionable row on screen: [icon] is the launch-animation source for apps. */
    private class RowRef(val view: View, val icon: View, val result: Result)

    private var showing = false
    private var animToken = 0
    private var previousFocus: View? = null
    private var enterArmed = false
    private var imeRetried = false
    private var places: List<FinderPlace> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Bitmap>()

    private val rows = ArrayList<RowRef>()
    private var selected = -1

    private val queryWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
    }

    /** Shows the soft keyboard once the field is focused; retried once if the IME was not ready. */
    private val showImeRunnable = object : Runnable {
        override fun run() {
            if (!showing || !field.isFocused) return
            val shown = try {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0) == true
            } catch (_: Exception) {
                false
            }
            if (!shown && !imeRetried) {
                imeRetried = true
                main.postDelayed(this, IME_RETRY_MS)
            }
        }
    }

    // ---- Views (recreated by build()) -------------------------------------------------------

    private lateinit var panel: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var field: EditText
    private lateinit var clearButton: ImageView
    private lateinit var divider: View
    private lateinit var resultsScroll: ResultsScroll
    private lateinit var resultsList: LinearLayout

    init {
        // Transparent, but catches taps outside the panel (which close Spotlight) and hover.
        isClickable = true
        isFocusable = false
        setOnClickListener { hide() }
        // Stay above Launchpad, the Dock and the menu bar; no outline, so no shadow of its own.
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

    /**
     * Shows Spotlight with [initialQuery], focuses the field and shows the soft keyboard. If it is
     * already showing, refocuses the field and selects its text (or replaces it with
     * [initialQuery] when one is given).
     */
    fun show(initialQuery: String = "") {
        if (showing) {
            field.requestFocus()
            if (initialQuery.isNotEmpty()) setQuery(initialQuery) else field.selectAll()
            showKeyboard()
            return
        }
        showing = true
        animToken++
        panel.animate().cancel()
        previousFocus = rootView.findFocus()?.takeIf { !isInside(it) }
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        enterArmed = false
        places = host.prefs.finderPlaces
        visibility = VISIBLE
        setQuery(initialQuery)
        field.requestFocus()
        showKeyboard()
        if (host.animationsEnabled) {
            panel.alpha = 0f
            panel.translationY = -dpf(ENTER_OFFSET_DP)
            panel.animate().alpha(1f).translationY(0f).setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            panel.alpha = 1f
            panel.translationY = 0f
        }
    }

    /** Hides Spotlight: hides the keyboard, gives focus back, animates out, then clears the query. */
    fun hide() {
        if (!showing) return
        showing = false
        enterArmed = false
        main.removeCallbacks(showImeRunnable)
        hideKeyboard()
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        restoreFocus()
        val token = ++animToken
        if (host.animationsEnabled && isAttachedToWindow && visibility == VISIBLE) {
            panel.animate().alpha(0f).translationY(-dpf(EXIT_OFFSET_DP)).setDuration(HIDE_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (token == animToken) finishHide() }
                .start()
        } else {
            finishHide()
        }
    }

    /** Palette changed: rebuild the panel, keeping the current query while showing. */
    fun applyPalette() {
        build()
    }

    /** The app list changed: re-run the current query while showing, keeping the selection. */
    fun onAppsChanged() {
        if (!showing || field.text.isBlank()) return
        val keepId = rows.getOrNull(selected)?.result?.id
        updateResults(field.text.toString())
        if (keepId != null) {
            val i = rows.indexOfFirst { it.result.id == keepId }
            if (i >= 0) select(i)
        }
    }

    // ---- View construction ------------------------------------------------------------------

    private fun build() {
        val p = host.palette
        val query = if (::field.isInitialized) field.text.toString() else ""
        val hadFocus = hasFocus()
        if (::panel.isInitialized) panel.animate().cancel()
        removeAllViews()
        rows.clear()
        selected = -1

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(
                this, host.glass, p.popupTint, withAlpha(p.popupTint, 248),
                dpf(PANEL_RADIUS_DP), p.glassStroke, context.hairlinePx.toFloat(),
            )
            elevation = dpf(PANEL_ELEVATION_DP)
            clipToOutline = true
            // Swallow taps inside the panel so they do not reach the overlay (which closes).
            isClickable = true
            isFocusable = false
        }

        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(HEADER_HEIGHT_DP)
            setPadding(dp(18), dp(10), dp(12), dp(10))
            // A click anywhere on the header puts the caret back into the field.
            setOnClickListener {
                field.requestFocus()
                showKeyboard()
            }
            isFocusable = false
        }
        header.addView(
            ImageView(context).apply {
                setImageDrawable(GlyphDrawable(Glyph.SEARCH, p.textSecondary, strokeScale = 1.1f))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) },
        )
        field = buildField(p)
        header.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clearButton = ImageView(context).apply {
            setImageDrawable(GlyphDrawable(Glyph.CLOSE, p.textSecondary, strokeScale = 1.2f))
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            background = context.stateBackground(p, dpf(18f))
            contentDescription = "Очистить поиск"
            tooltipText = "Очистить поиск"
            isClickable = true
            isFocusable = true
            visibility = INVISIBLE
            setOnClickListener {
                field.setText("")
                field.requestFocus()
            }
        }
        header.addView(clearButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        divider = View(context).apply {
            setBackgroundColor(p.separator)
            visibility = GONE
        }
        panel.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.hairlinePx))

        resultsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        resultsScroll = ResultsScroll(context).apply {
            // Rows are not focusable (focus stays in the field), so the ScrollView must not be either.
            isFocusable = false
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = GONE
            addView(resultsList, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(resultsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(panel, LayoutParams(dp(PANEL_MAX_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        if (showing) {
            if (query.isNotEmpty()) setQuery(query)
            if (hadFocus) field.requestFocus()
        } else if (visibility == VISIBLE) {
            finishHide()
        }
    }

    private fun buildField(p: Palette): EditText = EditText(context).apply {
        background = null
        setPadding(0, dp(4), 0, dp(4))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, FIELD_TEXT_SP)
        setTextColor(p.text)
        hint = "Поиск в Spotlight"
        setHintTextColor(p.textTertiary)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Fonts.regular
        includeFontPadding = false
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        highlightColor = withAlpha(p.accent, 90)
        textCursorDrawable = GradientDrawable().apply {
            setColor(p.accent)
            setSize(dp(2), dp(26))
        }
        contentDescription = "Поиск в Spotlight"
        // Soft keyboard action key; the hardware Enter is handled in dispatchKeyEvent.
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                activateSelected()
                true
            } else {
                false
            }
        }
        addTextChangedListener(queryWatcher)
    }

    private fun setQuery(q: String) {
        field.setText(q)
        field.setSelection(field.length())
    }

    // ---- Results ------------------------------------------------------------------------------

    private fun onQueryChanged(text: String) {
        clearButton.visibility = if (text.isEmpty()) INVISIBLE else VISIBLE
        updateResults(text)
    }

    private fun updateResults(raw: String) {
        resultsList.removeAllViews()
        rows.clear()
        selected = -1
        val q = raw.trim()
        if (q.isEmpty()) {
            divider.visibility = GONE
            resultsScroll.visibility = GONE
            return
        }
        val p = host.palette
        for (section in collectSections(q)) {
            section.title?.let { resultsList.addView(sectionHeader(it, p)) }
            for (r in section.items) resultsList.addView(resultRow(r, p))
        }
        divider.visibility = VISIBLE
        resultsScroll.visibility = VISIBLE
        resultsScroll.scrollTo(0, 0)
        if (rows.isNotEmpty()) select(0)
    }

    private fun collectSections(q: String): List<Section> {
        val out = ArrayList<Section>(3)
        calculatorResult(q)?.let { out.add(Section("Калькулятор", listOf(it))) }
        if (!host.apps.isLoaded) {
            out.add(Section("Приложения", listOf(Result("apps:loading", "Загрузка приложений…", null, null))))
        } else {
            val apps = host.apps.search(q, MAX_APP_RESULTS)
            if (apps.isNotEmpty()) out.add(Section("Приложения", apps.map { appResult(it) }))
        }
        val commands = matchingCommands(q)
        if (commands.isNotEmpty()) out.add(Section("MacPad", commands))
        if (out.isEmpty()) out.add(Section(null, listOf(Result("none", "Ничего не найдено", null, null))))
        return out
    }

    private fun appResult(e: AppEntry) = Result(
        id = "app:${e.key}",
        title = e.label,
        secondary = if (e.isMainProfile) "Приложение" else "Приложение · рабочий профиль",
        icon = e.icon,
        app = e,
        action = { source -> host.launchApp(e, source) },
    )

    private fun matchingCommands(q: String): List<Result> {
        val nq = AppRepository.normalize(q)
        val scored = ArrayList<Pair<Int, Command>>()
        for (c in commands()) {
            val title = AppRepository.normalize(c.title)
            val keywords = c.keywords.map { AppRepository.normalize(it) }
            val score = when {
                title.startsWith(nq) -> 0
                title.contains(nq) -> 1
                keywords.any { it.startsWith(nq) } -> 2
                keywords.any { it.contains(nq) } -> 3
                else -> continue
            }
            scored.add(score to c)
        }
        return scored.sortedBy { it.first }.take(MAX_COMMAND_RESULTS).map { (_, c) ->
            Result("cmd:${c.id}", c.title, c.secondary, c.icon(), action = { c.run() })
        }
    }

    /** MacPad's own commands; the theme entries leave out the theme that is already active. */
    private fun commands(): List<Command> {
        val list = ArrayList<Command>()
        list += Command("finder", "Finder", "Файлы и папки", listOf("файлы", "проводник", "finder", "папки"), { builtinIcon(BuiltinIcon.FINDER) }) {
            host.openFinder()
        }
        list += Command("settings", "Настройки MacPad", "Окно MacPad", listOf("настройки", "параметры", "settings"), { builtinIcon(BuiltinIcon.SETTINGS) }) {
            host.openSettings()
        }
        list += Command("wallpaper", "Обои", "Настройки MacPad", listOf("обои", "wallpaper", "фон"), {
            tileIcon("wallpaper", Glyph.IMAGE, Color.rgb(98, 210, 255), Color.rgb(28, 120, 232))
        }) {
            host.openSettings(SettingsWindow.SECTION_WALLPAPER)
        }
        list += Command("notes", "Заметки", "Окно MacPad", listOf("заметки", "текст", "notes"), { builtinIcon(BuiltinIcon.NOTES) }) {
            host.openNotes()
        }
        list += Command("about", "Об этом устройстве", "Окно MacPad", listOf("устройство", "память", "about"), { builtinIcon(BuiltinIcon.ABOUT) }) {
            host.openAbout()
        }
        list += Command("shortcuts", "Сочетания клавиш", "Справка", listOf("клавиши", "горячие", "shortcuts"), { builtinIcon(BuiltinIcon.SHORTCUTS) }) {
            host.openShortcuts()
        }
        list += Command("launchpad", "Launchpad", "Все приложения", listOf("launchpad", "приложения"), { builtinIcon(BuiltinIcon.LAUNCHPAD) }) {
            host.showLaunchpad()
        }
        list += Command("android", "Системные настройки Android", "Android", listOf("android", "система"), { androidSettingsIcon() }) {
            host.openSystemSettings()
        }
        val mode = host.prefs.themeMode
        if (mode != "dark") {
            list += Command("theme-dark", "Тёмная тема", "Оформление", listOf("тема", "тёмная", "dark"), {
                tileIcon("theme-dark", Glyph.MOON, Color.rgb(88, 86, 214), Color.rgb(36, 34, 110))
            }) { setTheme("dark") }
        }
        if (mode != "light") {
            list += Command("theme-light", "Светлая тема", "Оформление", listOf("тема", "светлая", "light"), {
                tileIcon("theme-light", Glyph.SUN, Color.rgb(255, 214, 90), Color.rgb(255, 150, 20))
            }) { setTheme("light") }
        }
        for (place in places) {
            list += Command("place:${place.uri}", place.name, "Папка в Finder", emptyList(), {
                tileIcon("place", Glyph.FOLDER, Color.rgb(130, 206, 255), Color.rgb(44, 140, 240))
            }) { host.openFinder(placeUri = place.uri) }
        }
        return list
    }

    private fun setTheme(mode: String) {
        if (host.prefs.themeMode == mode) return
        host.prefs.themeMode = mode
        host.applySettings()
    }

    private fun calculatorResult(q: String): Result? {
        val value = Calculator.evaluate(q) ?: return null
        val text = Calculator.format(value, decimalSeparatorFor(q))
        return Result(
            id = "calc",
            title = "= $text",
            secondary = "Скопировать результат",
            icon = tileIcon("calc", Glyph.CALC, Color.rgb(255, 176, 64), Color.rgb(232, 104, 18)),
            prominent = true,
            action = { copyToClipboard(text) },
        )
    }

    /** The separator the user typed, else the locale's (comma for Russian). */
    private fun decimalSeparatorFor(q: String): Char = when {
        '.' in q -> '.'
        ',' in q -> ','
        else -> DecimalFormatSymbols.getInstance().decimalSeparator
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (cm == null) {
                host.toast("Буфер обмена недоступен")
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("Результат", text))
            host.toast("Результат скопирован")
        } catch (_: Exception) {
            host.toast("Не удалось скопировать результат")
        }
    }

    // ---- Rows ---------------------------------------------------------------------------------

    private fun sectionHeader(title: String, p: Palette): View = context.label(title, 11.5f, p.textSecondary, 600).apply {
        setPadding(dp(10), dp(8), dp(10), dp(4))
        isAccessibilityHeading = true
    }

    private fun resultRow(r: Result, p: Palette): View {
        val enabled = r.action != null
        val row = PointerRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (r.prominent) ROW_PROMINENT_DP else ROW_HEIGHT_DP)
            setPadding(dp(10), dp(4), dp(12), dp(4))
            contentDescription = listOfNotNull(r.title, r.secondary).joinToString(", ")
        }
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            if (r.icon != null) setImageBitmap(r.icon) else visibility = INVISIBLE
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(ROW_ICON_DP), dp(ROW_ICON_DP)).apply { marginEnd = dp(10) })

        val title = context.label(r.title, if (r.prominent) 18f else 15f, p.text, if (r.prominent) 600 else 400).apply {
            if (enabled) {
                setTextColor(selectableTextColors(p.text, p.onSelection))
                isDuplicateParentStateEnabled = true
            } else {
                setTextColor(p.textSecondary)
            }
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!r.secondary.isNullOrEmpty()) {
            val secondary = context.label(r.secondary, 12.5f, p.textSecondary).apply {
                if (enabled) {
                    setTextColor(selectableTextColors(p.textSecondary, withAlpha(p.onSelection, 210)))
                    isDuplicateParentStateEnabled = true
                }
            }
            row.addView(
                secondary,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(12)
                },
            )
        }

        if (enabled) {
            val ref = RowRef(row, iconView, r)
            row.background = context.stateBackground(p, dpf(8f), selected = p.selection)
            row.setOnClickListener { activate(ref) }
            val app = r.app
            if (app != null) {
                row.setOnLongClickListener {
                    host.showAppContextMenu(app, row)
                    true
                }
                row.setOnContextClickListener {
                    host.showAppContextMenu(app, row, row.pointerX, row.pointerY)
                    true
                }
            }
            // Keyboard focus stays in the field; rows are driven by the selection instead.
            row.isFocusable = false
            rows.add(ref)
        }
        return row
    }

    private fun select(index: Int) {
        if (rows.isEmpty()) {
            selected = -1
            return
        }
        val i = index.coerceIn(0, rows.size - 1)
        rows.getOrNull(selected)?.view?.isSelected = false
        selected = i
        rows[i].view.isSelected = true
        scrollToRow(i)
    }

    private fun moveSelection(delta: Int) {
        if (rows.isEmpty()) return
        select(if (selected < 0) 0 else selected + delta)
    }

    /** Keeps the selected row (and the header of its section) inside the visible list area. */
    private fun scrollToRow(i: Int) {
        val v = rows[i].view
        val list = resultsList
        val sv = resultsScroll
        fun scrollListTo(y: Int) = if (host.animationsEnabled) sv.smoothScrollTo(0, y) else sv.scrollTo(0, y)
        val run = Runnable {
            if (i == 0) {
                scrollListTo(0)
                return@Runnable
            }
            val idx = list.indexOfChild(v)
            if (idx < 0) return@Runnable
            val prev = if (idx > 0) list.getChildAt(idx - 1) else null
            val top = (if (prev != null && prev !is PointerRow) prev.top else v.top) - dp(4)
            val bottom = v.bottom + dp(4)
            when {
                top < sv.scrollY -> scrollListTo(top)
                bottom > sv.scrollY + sv.height -> scrollListTo(bottom - sv.height)
            }
        }
        if (v.height > 0 && !list.isLayoutRequested) run.run() else sv.post(run)
    }

    private fun activateSelected() {
        rows.getOrNull(selected)?.let { activate(it) }
    }

    /** Closes Spotlight first, then runs the row's action. */
    private fun activate(ref: RowRef) {
        val action = ref.result.action ?: return
        hide()
        try {
            action(ref.icon)
        } catch (_: Exception) {
            host.toast("Не удалось выполнить «${ref.result.title}»")
        }
    }

    // ---- Icons --------------------------------------------------------------------------------

    private fun builtinIcon(kind: BuiltinIcon): Bitmap =
        iconCache.getOrPut("builtin:${kind.name}") { IconRenderer.renderBuiltin(kind, dp(ROW_ICON_DP)) }

    private fun androidSettingsIcon(): Bitmap =
        host.apps.findByPackage(ANDROID_SETTINGS_PACKAGE)?.icon
            ?: tileIcon("android", Glyph.GEAR, Color.rgb(150, 160, 176), Color.rgb(84, 94, 110))

    /** A macOS-style rounded tile with a gradient and a white glyph, in the size of a row icon. */
    private fun tileIcon(key: String, glyph: Glyph, top: Int, bottom: Int): Bitmap = iconCache.getOrPut("tile:$key") {
        val size = dp(ROW_ICON_DP)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = size * 0.07f
        val body = RectF(inset, inset * 0.75f, size - inset, size - inset * 1.25f)
        val path = IconRenderer.squircle(body)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, body.top, 0f, body.bottom, top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, paint)
        val g = GlyphDrawable(glyph, Color.WHITE, strokeScale = 1.15f)
        val gs = (body.width() * 0.58f).roundToInt()
        val gl = (body.centerX() - gs / 2f).roundToInt()
        val gt = (body.centerY() - gs / 2f).roundToInt()
        g.setBounds(gl, gt, gl + gs, gt + gs)
        g.draw(canvas)
        bmp
    }

    // ---- Keyboard -----------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (showing && handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val plain = !(e.isCtrlPressed || e.isAltPressed || e.isMetaPressed)
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                if (!plain) return false
                if (down) {
                    moveSelection(if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                    if (!field.isFocused) field.requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                if (rows.isEmpty()) return false
                if (down) moveSelection(if (e.keyCode == KeyEvent.KEYCODE_PAGE_DOWN) PAGE_STEP else -PAGE_STEP)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!field.isFocused) return false
                // Act on key up, and only for an Enter that went down here (not one that opened us).
                if (down) {
                    if (e.repeatCount == 0) enterArmed = true
                } else if (e.action == KeyEvent.ACTION_UP && enterArmed) {
                    enterArmed = false
                    activateSelected()
                }
                return true
            }
        }
        return false
    }

    /** Keeps Tab navigation inside the panel while Spotlight is showing. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!showing) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    // ---- Show/hide helpers ------------------------------------------------------------------

    private fun finishHide() {
        visibility = GONE
        panel.alpha = 1f
        panel.translationY = 0f
        if (field.text.isNotEmpty()) field.setText("") else updateResults("")
    }

    private fun restoreFocus() {
        val prev = previousFocus
        previousFocus = null
        if (!hasFocus()) return
        if (prev != null && prev.isAttachedToWindow && prev.isShown && prev.requestFocus()) return
        findFocus()?.clearFocus()
    }

    private fun showKeyboard() {
        imeRetried = false
        main.removeCallbacks(showImeRunnable)
        main.post(showImeRunnable)
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
        val ins = host.systemInsets
        if (paddingLeft != ins.left || paddingTop != ins.top || paddingRight != ins.right || paddingBottom != 0) {
            setPadding(ins.left, ins.top, ins.right, 0)
        }
        val w = MeasureSpec.getSize(widthMeasureSpec) - ins.left - ins.right
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val lp = panel.layoutParams as LayoutParams
        lp.width = min(dp(PANEL_MAX_WIDTH_DP), (w * PANEL_WIDTH_FRACTION).roundToInt()).coerceAtLeast(0)
        // Top edge at ~18 % of the screen height, below the status bar / cutout.
        lp.topMargin = max(dp(PANEL_MIN_TOP_DP), (h * PANEL_TOP_FRACTION).roundToInt() - ins.top)
        // At most ~8 rows, and never under the on-screen keyboard if there is room.
        val bottomLimit = h - max(imeBottom(), ins.bottom) - dp(PANEL_BOTTOM_GAP_DP)
        val headerHeight = if (header.measuredHeight > 0) header.measuredHeight else dp(HEADER_HEIGHT_DP)
        resultsScroll.maxHeightPx = (bottomLimit - ins.top - lp.topMargin - headerHeight)
            .coerceIn(dp(MIN_RESULTS_DP), dp(MAX_RESULTS_DP))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        if (::panel.isInitialized) panel.animate().cancel()
        super.onDetachedFromWindow()
    }

    // ---- Inner views --------------------------------------------------------------------------

    /** Result list that grows with its content up to [maxHeightPx], then scrolls. */
    private class ResultsScroll(context: Context) : ScrollView(context) {
        var maxHeightPx = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    /** A result row that remembers where the pointer was, so a right-click menu opens there. */
    private class PointerRow(context: Context) : LinearLayout(context) {
        var pointerX = -1
            private set
        var pointerY = -1
            private set

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) remember(ev)
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) remember(event)
            return super.dispatchGenericMotionEvent(event)
        }

        private fun remember(e: MotionEvent) {
            pointerX = e.rawX.roundToInt()
            pointerY = e.rawY.roundToInt()
        }
    }

    /**
     * Small recursive-descent evaluator for the calculator row (no scripting engine):
     * `+ - * / × ÷ ^`, parentheses (also implicit multiplication like `2(3+4)`), unary minus,
     * `%` (`50%` = 0.5; `200 + 10%` adds 10 % of 200), comma or dot as the decimal separator.
     */
    private class Calculator private constructor(private val src: String) {

        /** A parsed operand; [percent] is set for a bare `x%` so `a ± x%` can mean "x % of a". */
        private class Operand(val value: Double, val percent: Double? = null)

        private var pos = 0
        private var depth = 0

        private fun parse(): Double {
            val v = expression()
            if (peek() != END) throw IllegalArgumentException("Unexpected input")
            return v
        }

        private fun expression(): Double {
            var v = term().value
            while (true) {
                val c = peek()
                if (c != '+' && c != '-' && c != '−') return v
                pos++
                val t = term()
                val rhs = t.percent?.let { v * it / 100.0 } ?: t.value
                v = if (c == '+') v + rhs else v - rhs
            }
        }

        private fun term(): Operand {
            var r = unary()
            while (true) {
                r = when (peek()) {
                    '*', '×' -> {
                        pos++
                        Operand(r.value * unary().value)
                    }
                    '/', '÷' -> {
                        pos++
                        val d = unary().value
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        Operand(r.value / d)
                    }
                    '(' -> Operand(r.value * unary().value)
                    else -> return r
                }
            }
        }

        private fun unary(): Operand = when (peek()) {
            '-', '−' -> {
                pos++
                val o = unary()
                Operand(-o.value, o.percent?.let { -it })
            }
            '+' -> {
                pos++
                unary()
            }
            else -> power()
        }

        /** Right-associative: 2^3^2 = 2^9; the exponent may carry a sign (2^-1). */
        private fun power(): Operand {
            val base = postfix()
            if (peek() != '^') return base
            pos++
            return Operand(base.value.pow(unary().value))
        }

        private fun postfix(): Operand {
            var o = primary()
            while (peek() == '%') {
                pos++
                o = Operand(o.value / 100.0, o.value)
            }
            return o
        }

        private fun primary(): Operand {
            if (peek() == '(') {
                pos++
                if (++depth > MAX_DEPTH) throw IllegalArgumentException("Too deep")
                val v = expression()
                depth--
                // A missing ")" at the very end is accepted while the user is still typing.
                when (peek()) {
                    ')' -> pos++
                    END -> Unit
                    else -> throw IllegalArgumentException("Expected )")
                }
                return Operand(v)
            }
            return Operand(number())
        }

        private fun number(): Double {
            peek()
            val start = pos
            var digits = 0
            var separator = false
            while (pos < src.length) {
                val ch = src[pos]
                when {
                    ch in '0'..'9' -> digits++
                    (ch == '.' || ch == ',') && !separator -> separator = true
                    else -> break
                }
                pos++
            }
            if (digits == 0) throw IllegalArgumentException("Number expected")
            return src.substring(start, pos).replace(',', '.').toDouble()
        }

        /** Skips spaces and returns the next character, or [END]. */
        private fun peek(): Char {
            while (pos < src.length && src[pos] == ' ') pos++
            return if (pos < src.length) src[pos] else END
        }

        companion object {
            private const val END = '\u0000'
            private const val MAX_LENGTH = 200
            private const val MAX_DEPTH = 40
            private const val SIGNIFICANT_DIGITS = 10
            private const val ALLOWED = "0123456789 +-−*/×÷().,%^"
            private const val OPERATORS = "+-−*/×÷^%"

            /**
             * The value of [query] if it is an arithmetic expression with at least one operator
             * (a lone signed number does not count), otherwise null. Division by zero, overflow
             * and malformed input give null.
             */
            fun evaluate(query: String): Double? {
                val s = query.trim().replace(' ', ' ')
                if (s.isEmpty() || s.length > MAX_LENGTH) return null
                if (s.any { it !in ALLOWED } || s.none { it in '0'..'9' }) return null
                val core = s.trimStart('-', '−', '+', ' ', '(')
                if (core.none { it in OPERATORS }) return null
                return try {
                    val v = Calculator(s).parse()
                    if (v.isNaN() || v.isInfinite()) null else v
                } catch (_: Exception) {
                    null
                }
            }

            /** Up to 10 significant digits without trailing zeros; scientific for huge/tiny values. */
            fun format(value: Double, separator: Char): String {
                if (value == 0.0) return "0"
                val bd = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros()
                val abs = bd.abs()
                val text = if (abs >= BigDecimal.TEN.pow(15) || abs < BigDecimal.ONE.movePointLeft(9)) {
                    bd.toString().replace('E', 'e')
                } else {
                    bd.toPlainString()
                }
                return if (separator == '.') text else text.replace('.', separator)
            }
        }
    }

    private companion object {
        const val OVERLAY_ELEVATION_DP = 34f
        const val PANEL_ELEVATION_DP = 24f
        const val PANEL_RADIUS_DP = 14f
        const val PANEL_MAX_WIDTH_DP = 680
        const val PANEL_WIDTH_FRACTION = 0.92f
        const val PANEL_TOP_FRACTION = 0.18f
        const val PANEL_MIN_TOP_DP = 12
        const val PANEL_BOTTOM_GAP_DP = 16
        const val HEADER_HEIGHT_DP = 58
        const val FIELD_TEXT_SP = 22f

        const val ROW_HEIGHT_DP = 40
        const val ROW_PROMINENT_DP = 48
        const val ROW_ICON_DP = 28
        const val MIN_RESULTS_DP = 96
        /** About eight rows plus two section headers. */
        const val MAX_RESULTS_DP = 8 * ROW_HEIGHT_DP + 2 * 26 + 12
        const val MAX_APP_RESULTS = 8
        const val MAX_COMMAND_RESULTS = 10
        const val PAGE_STEP = 5

        const val SHOW_MS = 150L
        const val HIDE_MS = 120L
        const val ENTER_OFFSET_DP = 8f
        const val EXIT_OFFSET_DP = 6f
        const val IME_RETRY_MS = 150L

        const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
    }
}
