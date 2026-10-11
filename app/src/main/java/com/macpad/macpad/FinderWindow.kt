package com.macpad.macpad

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.text.Collator
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Finder: a file manager over the Storage Access Framework. It shows only folders the user granted
 * through the system folder picker ([FinderPlaces]); files are opened with other apps through
 * ACTION_VIEW with a temporary read grant.
 *
 * All state (location, history, listing, selection, search) lives in fields so [rebuild] (theme
 * change) recreates the views without querying the provider again.
 */
class FinderWindow(host: DesktopHost, id: String) : MacWindow(host, id) {

    private data class Crumb(val docId: String, val name: String)

    private data class Doc(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
        val flags: Int,
        /** Set for recursive search results: the folders from the place root to the parent. */
        val parents: List<Crumb>? = null,
    ) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private class Location(val place: FinderPlace, val path: List<Crumb>, val selected: String?)

    private enum class State { WELCOME, LOADING, READY, NO_ACCESS, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbIo = Executors.newSingleThreadExecutor()
    private val thumbCache = LruCache<String, Bitmap>(64)
    /** Thumbnails queued or known to be unavailable, so rebinding a cell does not request them again. */
    private val thumbRequested = HashSet<String>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private var closed = false

    private var place: FinderPlace? = null
    private var treeUri: Uri? = null
    private var path: List<Crumb> = emptyList()
    private val back = ArrayDeque<Location>()
    private val forward = ArrayDeque<Location>()
    private var listing: List<Doc> = emptyList()
    private var folderFlags = 0
    private var state = State.WELCOME
    private var errorText = ""
    private var generation = 0
    private var searchGeneration = 0
    private var filter = ""
    private var searchResults: List<Doc>? = null
    private var searching = false
    private var selectedId: String? = null
    private var viewMode = host.prefs.finderViewMode
    private var sidebarWanted = true
    private var lastWide: Boolean? = null
    private var lastShowSidebar: Boolean? = null
    private var suppressSearchWatcher = false

    // Views (recreated by build()).
    private var backButton: ImageView? = null
    private var forwardButton: ImageView? = null
    private var folderLabel: TextView? = null
    private var listButton: ImageView? = null
    private var gridButton: ImageView? = null
    private var searchField: EditText? = null
    private var sidebarBox: LinearLayout? = null
    private var mainBox: FrameLayout? = null
    private var statusLabel: TextView? = null
    private var adapter: DocAdapter? = null
    private var itemsView: AbsListView? = null

    init {
        title = "Finder"
        val first = host.prefs.finderPlaces.firstOrNull()
        if (first != null) navigateTo(first, emptyList(), record = false)
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.FINDER
    override val defaultWidthDp: Int get() = 820
    override val defaultHeightDp: Int get() = 520
    override val minWidthDp: Int get() = 440
    override val minHeightDp: Int get() = 300
    override val showsCenteredTitle: Boolean get() = false

    /** Navigates to the root of a granted folder (one of prefs.finderPlaces). */
    fun openPlace(uri: String) {
        val p = host.prefs.finderPlaces.firstOrNull { it.uri == uri }
            ?: FinderPlace(uri, FinderPlaces.displayName(context, Uri.parse(uri)))
        navigateTo(p, emptyList(), record = true)
    }

    // ---- Toolbar ----------------------------------------------------------------------------

    override fun onCreateToolbar(): View {
        val p = palette
        val ctx = context
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = ctx.glyphButton(p, Glyph.CHEVRON_LEFT, "Назад") { goBack() }
        forwardButton = ctx.glyphButton(p, Glyph.CHEVRON_RIGHT, "Вперёд") { goForward() }
        bar.addView(backButton)
        bar.addView(forwardButton)
        folderLabel = ctx.label(title, 14f, p.text, 600).apply { setPadding(dp(6), 0, dp(6), 0) }
        bar.addView(folderLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ctx.glyphButton(p, Glyph.SIDEBAR, "Боковая панель") {
            sidebarWanted = !sidebarWanted
            rebuild()
        })
        val selectedColors = selectableTextColors(p.textSecondary, p.onSelection)
        listButton = ctx.glyphButton(p, Glyph.LIST, "Список") { setViewMode("list") }.apply {
            (drawable as? GlyphDrawable)?.setColors(selectedColors)
        }
        gridButton = ctx.glyphButton(p, Glyph.ICONS, "Значки") { setViewMode("grid") }.apply {
            (drawable as? GlyphDrawable)?.setColors(selectedColors)
        }
        bar.addView(listButton)
        bar.addView(gridButton)
        bar.addView(ctx.glyphButton(p, Glyph.SORT, "Сортировка") { v -> host.menus.show(v, sortEntries()) })
        bar.addView(ctx.glyphButton(p, Glyph.PLUS, "Создать") { v ->
            host.menus.show(
                v,
                listOf(
                    MenuEntry.Item("Новая папка…", shortcut = "Ctrl+Shift+N", enabled = canCreate(), glyph = Glyph.FOLDER) { newFolder() },
                    MenuEntry.Item("Добавить папку в боковую панель…", glyph = Glyph.PLUS) { addPlace() },
                    MenuEntry.Item("Открыть файл…", glyph = Glyph.DOC) { pickAndOpenFile() },
                ),
            )
        })
        val field = ctx.macTextField(p, "Поиск", 13.5f, Glyph.SEARCH).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            suppressSearchWatcher = true
            setText(filter)
            suppressSearchWatcher = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressSearchWatcher) return
                    filter = s?.toString().orEmpty()
                    searchGeneration++
                    searching = false
                    searchResults = null
                    renderMain()
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    startRecursiveSearch()
                    true
                } else {
                    false
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    startRecursiveSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchField = field
        bar.addView(field, LinearLayout.LayoutParams(dp(170), dp(36)).apply { marginStart = dp(6) })
        updateToolbar()
        return bar
    }

    private fun updateToolbar() {
        backButton?.let { it.isEnabled = back.isNotEmpty(); it.alpha = if (back.isNotEmpty()) 1f else 0.35f }
        forwardButton?.let { it.isEnabled = forward.isNotEmpty(); it.alpha = if (forward.isNotEmpty()) 1f else 0.35f }
        folderLabel?.text = title
        listButton?.isSelected = viewMode == "list"
        gridButton?.isSelected = viewMode == "grid"
    }

    // ---- Content ----------------------------------------------------------------------------

    override fun onCreateContent(): View {
        val p = palette
        val ctx = context
        val showSidebar = sidebarWanted && (width == 0 || width >= dp(600))
        lastShowSidebar = showSidebar
        lastWide = isWide()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (showSidebar) {
            val side = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            sidebarBox = side
            root.addView(
                ScrollView(ctx).apply {
                    setBackgroundColor(p.windowSidebar)
                    isFillViewport = true
                    addView(side)
                },
                LinearLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            root.addView(View(ctx).apply { setBackgroundColor(p.separator) }, LinearLayout.LayoutParams(ctx.hairlinePx, ViewGroup.LayoutParams.MATCH_PARENT))
            renderSidebar()
        } else {
            sidebarBox = null
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val box = FrameLayout(ctx).apply {
            setBackgroundColor(p.windowBg)
            setOnTouchListener { _, e -> handleEmptyTouch(e) }
            setOnLongClickListener {
                showEmptyMenu(-1f, -1f, this)
                true
            }
        }
        mainBox = box
        column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(View(ctx).apply { setBackgroundColor(p.separator) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairlinePx))
        statusLabel = ctx.label("", 12f, p.textSecondary).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(p.windowTitleBar)
        }
        column.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        renderMain()
        return root
    }

    override fun onBoundsChanged() {
        val showSidebar = sidebarWanted && width >= dp(600)
        if ((lastShowSidebar != null && showSidebar != lastShowSidebar) || (lastWide != null && isWide() != lastWide)) {
            post { if (!closed) rebuild() }
        }
    }

    override fun onClosed() {
        closed = true
        generation++
        searchGeneration++
        io.shutdownNow()
        thumbIo.shutdownNow()
        thumbCache.evictAll()
    }

    private fun isWide(): Boolean = width == 0 || width >= dp(760)

    // ---- Sidebar ----------------------------------------------------------------------------

    private fun renderSidebar() {
        val side = sidebarBox ?: return
        side.removeAllViews()
        side.addView(sidebarHeader("Места"))
        for (pl in host.prefs.finderPlaces) {
            val row = sidebarRow(Glyph.FOLDER, pl.name, selected = pl.uri == place?.uri) { navigateTo(pl, emptyList(), record = true) }
            val menu = {
                host.menus.show(
                    row,
                    listOf(
                        MenuEntry.Header(pl.name),
                        MenuEntry.Item("Открыть в новом окне", glyph = Glyph.WINDOW) { host.openFinder(newWindow = true, placeUri = pl.uri) },
                        MenuEntry.Item("Убрать из боковой панели", glyph = Glyph.CLOSE) { removePlace(pl) },
                    ),
                )
            }
            row.setOnLongClickListener { menu(); true }
            row.setOnContextClickListener { menu(); true }
            side.addView(row)
        }
        side.addView(sidebarRow(Glyph.PLUS, "Добавить папку…", selected = false) { addPlace() })
        side.addView(sidebarHeader("Устройство"))
        side.addView(sidebarRow(Glyph.DOC, "Открыть файл…", selected = false) { pickAndOpenFile() })
        val fm = listOf("com.hihonor.filemanager", "com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui")
            .firstNotNullOfOrNull { host.apps.findByPackage(it) }
        if (fm != null) side.addView(sidebarRow(Glyph.DRIVE, "Файловый менеджер", selected = false) { host.launchApp(fm, it) })
    }

    private fun sidebarHeader(text: String): View = context.label(text, 11f, palette.textTertiary, 600).apply {
        setPadding(dp(8), dp(12), dp(8), dp(4))
    }

    private fun sidebarRow(glyph: Glyph, text: String, selected: Boolean, onClick: (View) -> Unit): LinearLayout {
        val p = palette
        val ctx = context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = ctx.stateBackground(p, dpf(7f))
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = text
            setOnClickListener(onClick)
            val iconColors = selectableTextColors(p.accent, p.onSelection)
            addView(ImageView(ctx).apply {
                setImageDrawable(GlyphDrawable(glyph, p.accent).apply { setColors(iconColors) })
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(ctx.label(text, 13.5f, p.text).apply {
                setTextColor(selectableTextColors(p.text, p.onSelection))
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ---- Main area --------------------------------------------------------------------------

    private fun visibleDocs(): List<Doc> {
        val base = searchResults ?: run {
            val q = AppRepository.normalize(filter.trim())
            if (q.isEmpty()) listing else listing.filter { AppRepository.normalize(it.name).contains(q) }
        }
        return sorted(base)
    }

    private fun sorted(docs: List<Doc>): List<Doc> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val desc = host.prefs.finderSortDescending
        val byKey: Comparator<Doc> = when (host.prefs.finderSortBy) {
            "date" -> compareBy { it.modified }
            "size" -> compareBy { it.size }
            "kind" -> Comparator { a, b -> collator.compare(kindOf(a), kindOf(b)) }
            else -> Comparator { a, b -> collator.compare(a.name, b.name) }
        }
        val ordered = if (desc) byKey.reversed() else byKey
        return docs.sortedWith(compareBy<Doc> { !it.isDir }.then(ordered))
    }

    private fun renderMain() {
        updateToolbar()
        val box = mainBox ?: return
        val p = palette
        val ctx = context
        box.removeAllViews()
        adapter = null
        itemsView = null
        when (state) {
            State.WELCOME -> box.addView(messageView(
                Glyph.FOLDER,
                "Добавьте папку, чтобы просматривать файлы. MacPad видит только папки, к которым вы дали доступ.",
                listOf("Добавить папку…" to { addPlace() }, "Открыть файл…" to { pickAndOpenFile() }),
            ))
            State.LOADING -> box.addView(centered(ctx.label("Загрузка…", 13f, p.textSecondary)))
            State.NO_ACCESS -> box.addView(messageView(
                Glyph.LOCK,
                "Нет доступа к папке «${place?.name ?: ""}». Разрешение было отозвано.",
                listOf(
                    "Предоставить доступ снова" to { addPlace() },
                    "Убрать из боковой панели" to { place?.let { removePlace(it) } },
                ),
            ))
            State.ERROR -> box.addView(messageView(Glyph.INFO, "Не удалось открыть папку. $errorText", listOf("Повторить" to { load(selectedId) })))
            State.READY -> {
                val docs = visibleDocs()
                if (docs.isEmpty()) {
                    val msg = when {
                        searching -> "Поиск…"
                        searchResults != null || filter.isNotBlank() -> "Ничего не найдено"
                        else -> "Папка пуста"
                    }
                    box.addView(centered(ctx.label(msg, 13f, p.textSecondary)))
                } else {
                    box.addView(if (viewMode == "grid") gridView(docs) else listView(docs))
                }
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val label = statusLabel ?: return
        label.text = when {
            state != State.READY -> ""
            searching -> "Поиск…"
            searchResults != null -> "Результаты поиска: ${searchResults?.size ?: 0}"
            else -> {
                val n = visibleDocs().size
                "$n ${WindowKit.plural(n, "объект", "объекта", "объектов")}"
            }
        }
    }

    private fun centered(v: View): View = FrameLayout(context).apply {
        addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun messageView(glyph: Glyph, text: String, actions: List<Pair<String, () -> Unit>>): View {
        val p = palette
        val ctx = context
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ImageView(ctx).apply { setImageDrawable(GlyphDrawable(glyph, p.accent, strokeScale = 0.9f)) }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(ctx.label(text, 14f, p.text, 400, 4).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            actions.forEachIndexed { i, (label, action) ->
                buttons.addView(
                    ctx.macButton(p, label, if (i == 0) ButtonStyle.PRIMARY else ButtonStyle.NORMAL) { action() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(10) },
                )
            }
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        return centered(column)
    }

    // ---- List & grid ------------------------------------------------------------------------

    private fun listView(docs: List<Doc>): View {
        val p = palette
        val ctx = context
        val wide = isWide()
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        fun headerCell(text: String, key: String, weight: Float, end: Boolean = false) {
            val active = host.prefs.finderSortBy == key
            val arrow = if (!active) "" else if (host.prefs.finderSortDescending) " ↓" else " ↑"
            header.addView(ctx.label(text + arrow, 12f, if (active) p.text else p.textSecondary, if (active) 600 else 500).apply {
                gravity = if (end) Gravity.END else Gravity.START
                setPadding(dp(4), dp(6), dp(4), dp(6))
                isClickable = true
                isFocusable = true
                background = ctx.stateBackground(p, dpf(5f))
                setOnClickListener { setSort(key) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
        }
        headerCell("Имя", "name", NAME_W)
        if (wide) headerCell("Дата изменения", "date", DATE_W)
        headerCell("Размер", "size", SIZE_W, end = true)
        if (wide) headerCell("Тип", "kind", KIND_W)
        column.addView(header)
        column.addView(View(ctx).apply { setBackgroundColor(p.separator) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairlinePx))
        val a = DocAdapter(docs, grid = false, wide = wide)
        adapter = a
        val list = ListView(ctx).apply {
            this.adapter = a
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            clipToPadding = false
            isFocusable = true
        }
        wireItems(list)
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun gridView(docs: List<Doc>): View {
        val a = DocAdapter(docs, grid = true, wide = true)
        adapter = a
        val grid = GridView(context).apply {
            this.adapter = a
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(108)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(6)
            horizontalSpacing = dp(6)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipToPadding = false
            isFocusable = true
        }
        wireItems(grid)
        return grid
    }

    private fun wireItems(view: AbsListView) {
        itemsView = view
        view.setOnItemClickListener { _, _, position, _ ->
            val d = adapter?.items?.getOrNull(position) ?: return@setOnItemClickListener
            select(d.docId)
            open(d)
        }
        view.setOnItemLongClickListener { _, v, position, _ ->
            val d = adapter?.items?.getOrNull(position) ?: return@setOnItemLongClickListener false
            select(d.docId)
            showItemMenu(d, v, -1f, -1f)
            true
        }
        view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                adapter?.items?.getOrNull(position)?.let { select(it.docId) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Right-click: on an item opens its menu, on empty space the folder menu.
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                val pos = view.pointToPosition(e.x.toInt(), e.y.toInt())
                val d = adapter?.items?.getOrNull(pos)
                if (d != null) {
                    select(d.docId)
                    showItemMenu(d, null, e.rawX, e.rawY)
                } else {
                    showEmptyMenu(e.rawX, e.rawY, null)
                }
                true
            } else {
                false
            }
        }
        view.setOnKeyListener { _, keyCode, event -> handleListKey(keyCode, event) }
        selectedId?.let { id ->
            val pos = adapter?.items?.indexOfFirst { it.docId == id } ?: -1
            if (pos >= 0) view.post { view.setSelection(pos) }
        }
    }

    private fun select(id: String?) {
        if (selectedId == id) return
        selectedId = id
        adapter?.notifyDataSetChanged()
    }

    private fun selectedDoc(): Doc? = selectedId?.let { id -> adapter?.items?.firstOrNull { it.docId == id } }

    /** Adapter shared by the list and grid views. */
    private inner class DocAdapter(val items: List<Doc>, private val grid: Boolean, private val wide: Boolean) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val d = items[position]
            val row = (convertView as? LinearLayout) ?: if (grid) createGridCell() else createListRow()
            row.isActivated = d.docId == selectedId
            if (grid) bindGrid(row, d) else bindList(row, d)
            return row
        }

        private fun createListRow(): LinearLayout {
            val p = palette
            val ctx = context
            val colors = selectableTextColors(p.text, p.onSelection)
            val dim = selectableTextColors(p.textSecondary, p.onSelection)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(34)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = ctx.stateBackground(p, dpf(6f))
                val nameBox = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(ctx), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                    addView(ctx.label("", 13.5f, p.text).apply {
                        setTextColor(colors)
                        isDuplicateParentStateEnabled = true
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    isDuplicateParentStateEnabled = true
                }
                addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, NAME_W))
                fun cell(weight: Float, end: Boolean): TextView = ctx.label("", 12.5f, p.textSecondary).apply {
                    setTextColor(dim)
                    isDuplicateParentStateEnabled = true
                    gravity = if (end) Gravity.END else Gravity.START
                    setPadding(dp(4), 0, dp(4), 0)
                }.also { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)) }
                if (wide) cell(DATE_W, false).tag = "date"
                cell(SIZE_W, true).tag = "size"
                if (wide) cell(KIND_W, false).tag = "kind"
            }
        }

        private fun bindList(row: LinearLayout, d: Doc) {
            val nameBox = row.getChildAt(0) as LinearLayout
            (nameBox.getChildAt(0) as ImageView).setImageDrawable(fileGlyph(d))
            (nameBox.getChildAt(1) as TextView).text = d.name
            row.findViewWithTag<TextView>("date")?.text = if (d.modified > 0) dateFormat.format(Date(d.modified)) else "—"
            row.findViewWithTag<TextView>("size")?.text = if (d.isDir || d.size < 0) "—" else Formatter.formatShortFileSize(context, d.size)
            row.findViewWithTag<TextView>("kind")?.text = d.parents?.lastOrNull()?.let { "в «${it.name}»" } ?: kindOf(d)
        }

        private fun createGridCell(): LinearLayout {
            val p = palette
            val ctx = context
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = ctx.stateBackground(p, dpf(8f))
                addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(TextView(ctx).apply {
                    textSize = 12f
                    setTextColor(selectableTextColors(p.text, p.onSelection))
                    isDuplicateParentStateEnabled = true
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }

        private fun bindGrid(cell: LinearLayout, d: Doc) {
            val image = cell.getChildAt(0) as ImageView
            (cell.getChildAt(1) as TextView).text = d.name
            image.tag = d.docId
            val cached = thumbCache.get(d.docId)
            if (cached != null) {
                image.setPadding(0, 0, 0, 0)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageBitmap(cached)
                return
            }
            val pad = dp(6)
            image.setPadding(pad, pad, pad, pad)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setImageDrawable(fileGlyph(d))
            if ((d.flags and Document.FLAG_SUPPORTS_THUMBNAIL) != 0) loadThumb(d, image)
        }
    }

    private fun loadThumb(d: Doc, image: ImageView) {
        val tree = treeUri ?: return
        if (!thumbRequested.add(d.docId)) return
        val cr = context.contentResolver
        val size = dp(112)
        thumbIo.execute {
            val bmp = try {
                DocumentsContract.getDocumentThumbnail(cr, DocumentsContract.buildDocumentUriUsingTree(tree, d.docId), Point(size, size), null)
            } catch (_: Exception) {
                null
            } ?: return@execute
            main.post {
                if (closed) return@post
                thumbCache.put(d.docId, bmp)
                // An evicted thumbnail may be requested again later.
                thumbRequested.remove(d.docId)
                if (image.tag == d.docId) {
                    image.setPadding(0, 0, 0, 0)
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.setImageBitmap(bmp)
                }
            }
        }
    }

    // ---- Types ------------------------------------------------------------------------------

    private fun fileGlyph(d: Doc): GlyphDrawable {
        val m = d.mime
        val (glyph, color) = when {
            d.isDir -> Glyph.FOLDER to palette.accent
            m.startsWith("image/") -> Glyph.IMAGE to Color.rgb(52, 199, 89)
            m.startsWith("video/") -> Glyph.VIDEO to Color.rgb(175, 82, 222)
            m.startsWith("audio/") -> Glyph.MUSIC to Color.rgb(255, 45, 85)
            m == "application/pdf" -> Glyph.PDF to Color.rgb(255, 59, 48)
            isArchive(m) -> Glyph.ARCHIVE to Color.rgb(255, 149, 0)
            m == "application/vnd.android.package-archive" -> Glyph.APP to Color.rgb(48, 209, 88)
            m.startsWith("text/") || m.contains("json") || m.contains("xml") -> Glyph.CODE to Color.rgb(90, 130, 180)
            else -> Glyph.DOC to Color.rgb(142, 142, 147)
        }
        return GlyphDrawable(glyph, color)
    }

    private fun isArchive(m: String) = m == "application/zip" || m.contains("7z") || m.contains("rar") || m.contains("tar") || m.contains("gzip")

    private fun kindOf(d: Doc): String {
        if (d.isDir) return "Папка"
        val m = d.mime
        val ext = d.name.substringAfterLast('.', "").uppercase(Locale.getDefault())
        return when {
            m == "image/jpeg" -> "Изображение JPEG"
            m == "image/png" -> "Изображение PNG"
            m.startsWith("image/") -> "Изображение"
            m == "video/mp4" -> "Видео MP4"
            m.startsWith("video/") -> "Видео"
            m == "audio/mpeg" -> "Аудио MP3"
            m.startsWith("audio/") -> "Аудио"
            m == "application/pdf" -> "Документ PDF"
            m == "text/plain" -> "Текст"
            m.startsWith("text/") -> "Текст"
            m == "application/zip" -> "Архив ZIP"
            isArchive(m) -> "Архив"
            m == "application/vnd.android.package-archive" -> "Приложение Android"
            ext.isNotEmpty() && ext.length <= 5 -> "$ext-файл"
            else -> "Документ"
        }
    }

    // ---- Navigation -------------------------------------------------------------------------

    private fun currentLocation(): Location? = place?.let { Location(it, path, selectedId) }

    private fun navigateTo(target: FinderPlace, subPath: List<Crumb>, record: Boolean, selectAfter: String? = null) {
        if (record) {
            currentLocation()?.let { back.push(it) }
            forward.clear()
        }
        val tree = Uri.parse(target.uri)
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            place = target
            treeUri = tree
            state = State.ERROR
            errorText = "Неверный адрес папки."
            renderSidebar()
            renderMain()
            return
        }
        val samePlace = place?.uri == target.uri
        place = target
        treeUri = tree
        path = listOf(Crumb(rootId, target.name)) + subPath
        if (!samePlace) renderSidebar()
        load(selectAfter)
    }

    private fun load(selectAfter: String?) {
        val tree = treeUri ?: return
        val pl = place ?: return
        val crumbs = path
        val my = ++generation
        searchGeneration++
        searching = false
        searchResults = null
        thumbRequested.clear()
        if (filter.isNotEmpty()) {
            filter = ""
            searchField?.let {
                suppressSearchWatcher = true
                it.setText("")
                suppressSearchWatcher = false
            }
        }
        selectedId = selectAfter
        title = crumbs.lastOrNull()?.name ?: "Finder"
        state = State.LOADING
        renderMain()
        if (!FinderPlaces.hasAccess(context, pl.uri)) {
            state = State.NO_ACCESS
            renderMain()
            return
        }
        val cr = context.contentResolver
        val showHidden = host.prefs.finderShowHidden
        io.execute {
            var docs: List<Doc>? = null
            var flags = 0
            var failure: Exception? = null
            try {
                val parentId = crumbs.last().docId
                cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, parentId), arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
                    if (it.moveToFirst()) flags = it.getInt(0)
                }
                docs = queryChildren(cr, tree, parentId, showHidden)
            } catch (e: Exception) {
                failure = e
            }
            main.post {
                if (closed || my != generation) return@post
                when {
                    docs != null -> {
                        listing = docs!!
                        folderFlags = flags
                        state = State.READY
                    }
                    failure is SecurityException -> state = State.NO_ACCESS
                    else -> {
                        state = State.ERROR
                        errorText = failure?.localizedMessage.orEmpty()
                    }
                }
                renderMain()
            }
        }
    }

    private fun queryChildren(cr: ContentResolver, tree: Uri, parentId: String, showHidden: Boolean): List<Doc> {
        val out = ArrayList<Doc>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!showHidden && name.startsWith(".")) continue
                out += Doc(
                    docId = c.getString(0) ?: continue,
                    name = name,
                    mime = c.getString(2) ?: "application/octet-stream",
                    size = if (c.isNull(3)) -1L else c.getLong(3),
                    modified = if (c.isNull(4)) 0L else c.getLong(4),
                    flags = if (c.isNull(5)) 0 else c.getInt(5),
                )
            }
        }
        return out
    }

    private fun open(d: Doc) {
        if (d.isDir) {
            val pl = place ?: return
            val parents = d.parents
            val sub = if (parents != null) parents.drop(1) + Crumb(d.docId, d.name) else path.drop(1) + Crumb(d.docId, d.name)
            navigateTo(pl, sub, record = true)
        } else {
            openFile(d, chooser = false)
        }
    }

    private fun goUp() {
        if (path.size <= 1) return
        val pl = place ?: return
        val leaving = path.last().docId
        navigateTo(pl, path.drop(1).dropLast(1), record = true, selectAfter = leaving)
    }

    private fun goBack() {
        val target = back.pollFirst() ?: return
        currentLocation()?.let { forward.push(it) }
        restore(target)
    }

    private fun goForward() {
        val target = forward.pollFirst() ?: return
        currentLocation()?.let { back.push(it) }
        restore(target)
    }

    private fun restore(loc: Location) {
        navigateTo(loc.place, loc.path.drop(1), record = false, selectAfter = loc.selected)
    }

    private fun refresh() {
        if (place != null) load(selectedId)
    }

    private fun setViewMode(mode: String) {
        viewMode = mode
        host.prefs.finderViewMode = mode
        renderMain()
    }

    private fun setSort(key: String) {
        val prefs = host.prefs
        if (prefs.finderSortBy == key) prefs.finderSortDescending = !prefs.finderSortDescending
        else {
            prefs.finderSortBy = key
            prefs.finderSortDescending = false
        }
        renderMain()
    }

    private fun sortEntries(): List<MenuEntry> {
        val prefs = host.prefs
        fun item(title: String, key: String) = MenuEntry.Item(title, checked = prefs.finderSortBy == key) {
            prefs.finderSortBy = key
            renderMain()
        }
        return listOf(
            item("По имени", "name"),
            item("По дате изменения", "date"),
            item("По размеру", "size"),
            item("По типу", "kind"),
            MenuEntry.Separator,
            MenuEntry.Item("По убыванию", checked = prefs.finderSortDescending) {
                prefs.finderSortDescending = !prefs.finderSortDescending
                renderMain()
            },
        )
    }

    // ---- Search -----------------------------------------------------------------------------

    private fun startRecursiveSearch() {
        val tree = treeUri ?: return
        val q = AppRepository.normalize(filter.trim())
        if (q.isEmpty() || state != State.READY) return
        val start = path
        val my = ++searchGeneration
        searching = true
        searchResults = emptyList()
        renderMain()
        val cr = context.contentResolver
        val showHidden = host.prefs.finderShowHidden
        io.execute {
            val results = ArrayList<Doc>()
            val queue = ArrayDeque<List<Crumb>>()
            queue.add(start)
            try {
                while (queue.isNotEmpty() && results.size < MAX_RESULTS && my == searchGeneration && !closed) {
                    val crumbs = queue.pollFirst() ?: break
                    for (d in queryChildren(cr, tree, crumbs.last().docId, showHidden)) {
                        if (AppRepository.normalize(d.name).contains(q)) results += d.copy(parents = crumbs)
                        if (d.isDir && crumbs.size - start.size < MAX_DEPTH) queue.add(crumbs + Crumb(d.docId, d.name))
                        if (results.size >= MAX_RESULTS) break
                    }
                }
            } catch (_: Exception) {
                // Show what was found before the provider failed.
            }
            main.post {
                if (closed || my != searchGeneration) return@post
                searching = false
                searchResults = results
                renderMain()
            }
        }
    }

    override fun focusSearchField(): Boolean {
        val f = searchField ?: return false
        f.requestFocus()
        f.selectAll()
        return true
    }

    // ---- File actions -----------------------------------------------------------------------

    private fun docUri(d: Doc): Uri? = treeUri?.let { DocumentsContract.buildDocumentUriUsingTree(it, d.docId) }

    private fun openFile(d: Doc, chooser: Boolean) {
        val uri = docUri(d) ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = if (chooser) Intent.createChooser(view, "Открыть с помощью") else view
        try {
            host.activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            host.dialogs.alert("Не удалось открыть файл", "Нет приложения, которое умеет открывать файлы этого типа (${d.mime}).")
        } catch (e: Exception) {
            host.dialogs.alert("Не удалось открыть файл", e.localizedMessage ?: "")
        }
    }

    private fun share(d: Doc) {
        val uri = docUri(d) ?: return
        val send = Intent(Intent.ACTION_SEND).setType(d.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            host.activity.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            host.toast("Нет приложений, чтобы поделиться файлом")
        }
    }

    private fun rename(d: Doc) {
        val uri = docUri(d) ?: return
        host.dialogs.prompt("Переименовать", null, d.name, "Переименовать") { newName ->
            if (newName == d.name) return@prompt
            val cr = context.contentResolver
            io.execute {
                val error = try {
                    DocumentsContract.renameDocument(cr, uri, newName)
                    null
                } catch (e: Exception) {
                    e.localizedMessage ?: "Не удалось переименовать."
                }
                main.post {
                    if (closed) return@post
                    if (error != null) host.dialogs.alert("Не удалось переименовать", error) else load(null)
                }
            }
        }
    }

    private fun delete(d: Doc) {
        val uri = docUri(d) ?: return
        host.dialogs.confirm("Удалить «${d.name}»?", "Это действие нельзя отменить.", "Удалить", destructive = true) {
            val cr = context.contentResolver
            io.execute {
                val error = try {
                    if (DocumentsContract.deleteDocument(cr, uri)) null else "Провайдер отказал в удалении."
                } catch (e: Exception) {
                    e.localizedMessage ?: "Не удалось удалить."
                }
                main.post {
                    if (closed) return@post
                    if (error != null) host.dialogs.alert("Не удалось удалить", error) else load(null)
                }
            }
        }
    }

    private fun canCreate(): Boolean = state == State.READY && (folderFlags and Document.FLAG_DIR_SUPPORTS_CREATE) != 0

    private fun newFolder() {
        val tree = treeUri ?: return
        if (!canCreate()) {
            host.toast("В этой папке нельзя создавать файлы")
            return
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, path.last().docId)
        host.dialogs.prompt("Новая папка", null, "Новая папка", "Создать") { name ->
            val cr = context.contentResolver
            io.execute {
                val error = try {
                    if (DocumentsContract.createDocument(cr, parent, Document.MIME_TYPE_DIR, name) != null) null else "Не удалось создать папку."
                } catch (e: Exception) {
                    e.localizedMessage ?: "Не удалось создать папку."
                }
                main.post {
                    if (closed) return@post
                    if (error != null) host.dialogs.alert("Не удалось создать папку", error) else load(null)
                }
            }
        }
    }

    private fun properties(d: Doc) {
        val folder = (d.parents ?: path).joinToString(" / ") { it.name }
        val lines = buildList {
            add("Тип: ${kindOf(d)}")
            if (!d.isDir && d.size >= 0) add("Размер: ${Formatter.formatFileSize(context, d.size)}")
            if (d.modified > 0) add("Изменён: ${dateFormat.format(Date(d.modified))}")
            add("Папка: $folder")
        }
        host.dialogs.alert(d.name, lines.joinToString("\n"))
    }

    private fun addPlace() {
        host.pickFolder { uri ->
            if (uri != null) {
                val p = FinderPlaces.add(host.context, host.prefs, uri)
                renderSidebar()
                navigateTo(p, emptyList(), record = true)
            }
        }
    }

    private fun removePlace(pl: FinderPlace) {
        FinderPlaces.remove(host.context, host.prefs, pl)
        if (place?.uri == pl.uri) {
            back.clear()
            forward.clear()
            val next = host.prefs.finderPlaces.firstOrNull()
            if (next != null) {
                place = null
                navigateTo(next, emptyList(), record = false)
            } else {
                place = null
                treeUri = null
                path = emptyList()
                listing = emptyList()
                state = State.WELCOME
                title = "Finder"
                renderMain()
            }
        }
        renderSidebar()
    }

    private fun pickAndOpenFile() {
        host.pickFile(arrayOf("*/*")) { uri ->
            if (uri == null) return@pickFile
            val type = try {
                host.context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            } ?: "*/*"
            try {
                host.activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Открыть с помощью",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                host.toast("Нет приложения для открытия этого файла")
            }
        }
    }

    // ---- Menus ------------------------------------------------------------------------------

    private fun showItemMenu(d: Doc, anchor: View?, x: Float, y: Float) {
        val entries = buildList {
            add(MenuEntry.Header(d.name))
            add(MenuEntry.Item("Открыть", shortcut = "Enter", glyph = Glyph.EXTERNAL) { open(d) })
            if (!d.isDir) {
                add(MenuEntry.Item("Открыть с помощью…") { openFile(d, chooser = true) })
                add(MenuEntry.Item("Поделиться", glyph = Glyph.SHARE) { share(d) })
            }
            add(MenuEntry.Separator)
            add(MenuEntry.Item("Переименовать…", enabled = (d.flags and Document.FLAG_SUPPORTS_RENAME) != 0, glyph = Glyph.PENCIL) { rename(d) })
            add(MenuEntry.Item("Удалить…", shortcut = "Delete", enabled = (d.flags and Document.FLAG_SUPPORTS_DELETE) != 0, glyph = Glyph.TRASH, destructive = true) { delete(d) })
            add(MenuEntry.Separator)
            add(MenuEntry.Item("Свойства", shortcut = "Ctrl+I", glyph = Glyph.INFO) { properties(d) })
        }
        showMenu(entries, anchor, x, y)
    }

    private fun showEmptyMenu(x: Float, y: Float, anchor: View?) {
        if (state != State.READY) return
        val entries = buildList {
            add(MenuEntry.Item("Новая папка…", shortcut = "Ctrl+Shift+N", enabled = canCreate(), glyph = Glyph.FOLDER) { newFolder() })
            add(MenuEntry.Item("Обновить", shortcut = "F5", glyph = Glyph.REFRESH) { refresh() })
            add(MenuEntry.Separator)
            add(MenuEntry.Item("Вид: список", checked = viewMode == "list") { setViewMode("list") })
            add(MenuEntry.Item("Вид: значки", checked = viewMode == "grid") { setViewMode("grid") })
            add(MenuEntry.Separator)
            addAll(sortEntries())
        }
        showMenu(entries, anchor, x, y)
    }

    private fun showMenu(entries: List<MenuEntry>, anchor: View?, x: Float, y: Float) {
        when {
            x >= 0 && y >= 0 -> host.menus.showAtScreen(x.toInt(), y.toInt(), entries)
            anchor != null -> host.menus.show(anchor, entries)
            else -> {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                host.menus.showAtScreen(loc[0] + width / 3, loc[1] + height / 3, entries)
            }
        }
    }

    private fun handleEmptyTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
            showEmptyMenu(e.rawX, e.rawY, null)
            return true
        }
        return false
    }

    // ---- Keyboard ---------------------------------------------------------------------------

    private fun handleListKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when {
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                selectedDoc()?.let { open(it) }
                true
            }
            keyCode == KeyEvent.KEYCODE_DEL && !event.isCtrlPressed -> {
                goBack()
                true
            }
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> {
                selectedDoc()?.let { if ((it.flags and Document.FLAG_SUPPORTS_DELETE) != 0) delete(it) }
                true
            }
            keyCode == KeyEvent.KEYCODE_F5 -> {
                refresh()
                true
            }
            event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                goBack()
                true
            }
            event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                goForward()
                true
            }
            keyCode == KeyEvent.KEYCODE_MENU || (keyCode == KeyEvent.KEYCODE_F10 && event.isShiftPressed) -> {
                val d = selectedDoc()
                if (d != null) showItemMenu(d, null, -1f, -1f) else showEmptyMenu(-1f, -1f, null)
                true
            }
            else -> false
        }
    }

    override fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val f = searchField
            if (f != null && filter.isNotEmpty()) {
                f.setText("")
                return true
            }
            return false
        }
        if (!(event.isCtrlPressed || event.isMetaPressed)) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_N -> if (event.isShiftPressed) {
                newFolder()
                return true
            }
            KeyEvent.KEYCODE_R -> {
                refresh()
                return true
            }
            KeyEvent.KEYCODE_I -> {
                selectedDoc()?.let { properties(it) }
                return true
            }
            KeyEvent.KEYCODE_O -> {
                selectedDoc()?.let { open(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                goUp()
                return true
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> {
                goBack()
                return true
            }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                goForward()
                return true
            }
            // Window keys run before the focused view: leave Ctrl+Backspace to the search field.
            KeyEvent.KEYCODE_DEL -> if (searchField?.hasFocus() != true) {
                selectedDoc()?.let { if ((it.flags and Document.FLAG_SUPPORTS_DELETE) != 0) delete(it) }
                return true
            }
        }
        return false
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
        const val MAX_RESULTS = 500
        const val MAX_DEPTH = 8
        const val NAME_W = 3f
        const val DATE_W = 2f
        const val SIZE_W = 1f
        const val KIND_W = 1.6f
    }
}
