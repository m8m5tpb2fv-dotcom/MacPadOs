package com.macpad.macpad

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * "Заметки": a small TextEdit-like editor.
 *
 * The note is autosaved to [MacPrefs.notesText] (debounced) so it survives restarts. Files can be
 * opened and saved through the Storage Access Framework. Font size and the monospace switch are
 * remembered in the window's own SharedPreferences. Keyboard: Ctrl+S save, Ctrl+Shift+S save as,
 * Ctrl+O open.
 */
class NotesWindow(host: DesktopHost) : MacWindow(host, "notes") {

    private val sp = host.context.getSharedPreferences("macpad_notes", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var closed = false

    private var noteText: String = host.prefs.notesText
    private var selStart = noteText.length
    private var selEnd = noteText.length
    private var fontSp = sp.getFloat("font", 16f).coerceIn(MIN_FONT, MAX_FONT)
    private var mono = sp.getBoolean("mono", false)

    private var fileUri: Uri? = null
    private var fileName: String? = null

    /** Text differs from what was last written to / read from [fileUri]. */
    private var dirty = false
    private var suppressWatcher = false

    private var editor: EditText? = null
    private var countsLabel: TextView? = null
    private var fileLabel: TextView? = null
    private var titleLabel: TextView? = null
    private var monoButton: ImageView? = null

    private val autosave = Runnable { host.prefs.notesText = noteText }

    init {
        title = "Заметки"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.NOTES
    override val defaultWidthDp: Int get() = 640
    override val defaultHeightDp: Int get() = 480
    override val minWidthDp: Int get() = 380
    override val minHeightDp: Int get() = 260
    override val showsCenteredTitle: Boolean get() = false

    override fun onCreateToolbar(): View {
        val p = palette
        val ctx = context
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLabel = ctx.label(title, 14f, p.text, 600).apply { gravity = Gravity.CENTER }
        bar.addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ctx.glyphButton(p, Glyph.DOC, "Открыть файл…") { openFile() })
        bar.addView(ctx.glyphButton(p, Glyph.DOWNLOAD, "Сохранить") { save() })
        bar.addView(ctx.glyphButton(p, Glyph.SHARE, "Сохранить как…") { saveAs() })
        bar.addView(
            View(ctx).apply { setBackgroundColor(p.separator) },
            LinearLayout.LayoutParams(ctx.hairlinePx, dp(20)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            },
        )
        bar.addView(textButton("A−", "Уменьшить шрифт") { changeFont(-2f) })
        bar.addView(textButton("A+", "Увеличить шрифт") { changeFont(2f) })
        monoButton = ctx.glyphButton(p, Glyph.CODE, "Моноширинный шрифт") { toggleMono() }.apply {
            isSelected = mono
            (drawable as? GlyphDrawable)?.setColors(selectableTextColors(p.textSecondary, p.onSelection))
        }
        bar.addView(monoButton)
        return bar
    }

    override fun onCreateContent(): View {
        val p = palette
        val ctx = context
        editor?.let {
            selStart = it.selectionStart
            selEnd = it.selectionEnd
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val field = EditText(ctx).apply {
            background = null
            setTextColor(p.text)
            setHintTextColor(p.textTertiary)
            hint = "Начните печатать…"
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = false
            setHorizontallyScrolling(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
            typeface = if (mono) Fonts.mono else Fonts.regular
            setLineSpacing(0f, 1.15f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            highlightColor = withAlpha(p.accent, 90)
            textCursorDrawable = GradientDrawable().apply {
                setColor(p.accent)
                setSize(dp(2), dp(20))
            }
            suppressWatcher = true
            setText(noteText)
            suppressWatcher = false
            val len = noteText.length
            setSelection(selStart.coerceIn(0, len), selEnd.coerceIn(0, len))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatcher) return
                    noteText = s?.toString().orEmpty()
                    if (fileUri != null) dirty = true
                    main.removeCallbacks(autosave)
                    main.postDelayed(autosave, AUTOSAVE_MS)
                    updateStatus()
                }
            })
        }
        editor = field
        column.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(
            View(ctx).apply { setBackgroundColor(p.separator) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairlinePx),
        )
        val status = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(p.windowTitleBar)
        }
        countsLabel = ctx.label("", 12f, p.textSecondary)
        fileLabel = ctx.label("", 12f, p.textSecondary).apply { gravity = Gravity.END }
        status.addView(countsLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        status.addView(fileLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateStatus()
        return column
    }

    override fun handleKey(event: KeyEvent): Boolean {
        if (!(event.isCtrlPressed || event.isMetaPressed)) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_S -> {
                if (event.isShiftPressed) saveAs() else save()
                true
            }
            KeyEvent.KEYCODE_O -> {
                openFile()
                true
            }
            else -> false
        }
    }

    override fun onDeactivated() {
        flushAutosave()
    }

    override fun onClosed() {
        closed = true
        flushAutosave()
        io.shutdown()
    }

    // ---- Editing helpers --------------------------------------------------------------------

    private fun textButton(text: String, description: String, onClick: () -> Unit): TextView =
        context.label(text, 13.5f, palette.textSecondary, 600).apply {
            gravity = Gravity.CENTER
            minWidth = dp(34)
            minHeight = dp(34)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = description
            tooltipText = description
            isClickable = true
            isFocusable = true
            background = context.stateBackground(palette, dpf(7f))
            setOnClickListener { onClick() }
        }

    private fun flushAutosave() {
        main.removeCallbacks(autosave)
        host.prefs.notesText = noteText
    }

    private fun changeFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(MIN_FONT, MAX_FONT)
        editor?.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        sp.edit().putFloat("font", fontSp).apply()
    }

    private fun toggleMono() {
        mono = !mono
        editor?.typeface = if (mono) Fonts.mono else Fonts.regular
        monoButton?.isSelected = mono
        sp.edit().putBoolean("mono", mono).apply()
    }

    private fun updateStatus() {
        val words = noteText.split(WHITESPACE).count { it.isNotEmpty() }
        countsLabel?.text = "Слов: $words · Символов: ${noteText.length}"
        val name = fileName
        fileLabel?.text = when {
            name == null -> "Не сохранено в файл"
            dirty -> "$name •"
            else -> name
        }
    }

    private fun updateTitle() {
        val name = fileName
        title = if (name != null) "Заметки — $name" else "Заметки"
        titleLabel?.text = title
    }

    private fun setEditorText(value: String) {
        noteText = value
        selStart = value.length
        selEnd = value.length
        editor?.let {
            suppressWatcher = true
            it.setText(value)
            suppressWatcher = false
            it.setSelection(value.length)
        }
        flushAutosave()
        updateStatus()
    }

    // ---- Files (Storage Access Framework) ---------------------------------------------------

    private fun openFile() {
        host.pickFile(arrayOf("text/*")) { uri -> if (uri != null) readFile(uri) }
    }

    private fun readFile(uri: Uri) {
        val cr = host.context.contentResolver
        io.execute {
            var error: String? = null
            var content: String? = null
            var name: String? = null
            try {
                name = queryName(uri)
                val bytes = cr.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) throw FileTooLarge()
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IOException("Нет доступа к файлу")
                content = String(bytes, Charsets.UTF_8)
            } catch (_: FileTooLarge) {
                error = "Файл слишком большой (больше 2 МБ)."
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Не удалось прочитать файл."
            }
            main.post {
                if (closed) return@post
                val loaded = content
                if (error != null || loaded == null) {
                    host.dialogs.alert("Не удалось открыть файл", error ?: "Не удалось прочитать файл.")
                    return@post
                }
                val apply = {
                    takePermission(uri)
                    fileUri = uri
                    fileName = name ?: "Без названия.txt"
                    dirty = false
                    setEditorText(loaded)
                    updateTitle()
                    updateStatus()
                }
                if (noteText.isNotEmpty() && noteText != loaded) {
                    host.dialogs.confirm(
                        "Заменить текст заметки?",
                        "Текущий текст будет заменён содержимым файла «${name ?: "файл"}».",
                        "Заменить",
                    ) { apply() }
                } else {
                    apply()
                }
            }
        }
    }

    private fun save() {
        val uri = fileUri
        if (uri == null) saveAs() else writeFile(uri, fileName)
    }

    private fun saveAs() {
        host.createDocument("text/plain", fileName ?: "Заметка.txt") { uri ->
            if (uri != null) writeFile(uri, null)
        }
    }

    private fun writeFile(uri: Uri, knownName: String?) {
        val cr = host.context.contentResolver
        val content = noteText
        io.execute {
            var error: String? = null
            var name = knownName
            try {
                val stream = try {
                    cr.openOutputStream(uri, "wt")
                } catch (_: Exception) {
                    cr.openOutputStream(uri, "w")
                } ?: throw IOException("Нет доступа к файлу")
                stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                if (name == null) name = queryName(uri)
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Не удалось записать файл."
            }
            main.post {
                if (closed) return@post
                if (error != null) {
                    host.dialogs.alert("Не удалось сохранить файл", error)
                    return@post
                }
                takePermission(uri)
                fileUri = uri
                fileName = name ?: fileName ?: "Заметка.txt"
                dirty = noteText != content
                updateTitle()
                updateStatus()
                host.toast("Сохранено")
            }
        }
    }

    private fun takePermission(uri: Uri) {
        try {
            host.context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Not persistable: the file still works for this session.
        }
    }

    private fun queryName(uri: Uri): String? = try {
        host.context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private class FileTooLarge : IOException()

    private companion object {
        const val AUTOSAVE_MS = 600L
        const val MIN_FONT = 12f
        const val MAX_FONT = 28f
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        val WHITESPACE = Regex("\\s+")
    }
}
