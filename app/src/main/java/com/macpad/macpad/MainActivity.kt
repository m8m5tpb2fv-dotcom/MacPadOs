
package com.macpad.macpad

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable
)

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(20, 24, 36)
    private val panel = Color.rgb(43, 49, 65)
    private val white = Color.rgb(248, 249, 255)
    private val muted = Color.rgb(177, 187, 207)
    private val blue = Color.rgb(145, 199, 255)
    private val accent = Color.rgb(93, 151, 235)

    private lateinit var root: LinearLayout
    private lateinit var appGrid: GridLayout
    private lateinit var search: EditText
    private lateinit var clockView: TextView
    private lateinit var allApps: List<AppItem>

    private val handler = Handler(Looper.getMainLooper())

    private val clockUpdater = object : Runnable {
        override fun run() {
            if (::clockView.isInitialized) {
                clockView.text = SimpleDateFormat(
                    "EEE, d MMM  HH:mm",
                    Locale.getDefault()
                ).format(Date())
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        buildInterface()
        loadApps()
        renderApps("")
        clockUpdater.run()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockUpdater)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Float,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        setContentView(root)

        // Верхняя строка меню
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, dp(15).toFloat())
            setPadding(dp(14), 0, dp(14), 0)
        }

        menu.addView(
            text("●", 16f, blue, true),
            LinearLayout.LayoutParams(-2, dp(44))
        )

        menu.addView(
            text("  MacPad", 15f, white, true),
            LinearLayout.LayoutParams(-2, dp(44))
        )

        menu.addView(
            text("   Файл    Вид    Окно", 12f, muted),
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        clockView = text("", 12f, white)

        menu.addView(
            clockView,
            LinearLayout.LayoutParams(-2, dp(44))
        )

        root.addView(
            menu,
            LinearLayout.LayoutParams(-1, dp(46))
        )

        // Приветствие
        root.addView(
            text("Рабочий стол", 30f, white, true),
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(16)
            }
        )

        root.addView(
            text("Твой планшет. Твоё пространство.", 14f, muted),
            LinearLayout.LayoutParams(-1, dp(26))
        )

        // Поиск
        search = EditText(this).apply {
            hint = "⌕   Поиск приложений..."
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(panel, dp(16).toFloat(), Color.rgb(62, 72, 92))
            setPadding(dp(16), 0, dp(16), 0)
            isFocusableInTouchMode = true

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    renderApps(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) {}
            })

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    launchFirstSearchResult()
                    true
                } else false
            }
        }

        root.addView(
            search,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        )

        // Заголовок сетки
        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        appsHeader.addView(
            text("Приложения", 18f, white, true),
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        appsHeader.addView(
            text("Ctrl + Space  ·  Поиск", 11f, muted)
        )

        root.addView(
            appsHeader,
            LinearLayout.LayoutParams(-1, dp(38))
        )

        // Сетка приложений
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        appGrid = GridLayout(this).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        scroll.addView(
            appGrid,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Док
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(
                Color.rgb(48, 57, 76),
                dp(23).toFloat(),
                Color.rgb(72, 83, 105)
            )
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }

        addDockItem(dock, "⌂", "Домой") {
            search.setText("")
            search.clearFocus()
            hideKeyboard()
        }

        addDockItem(dock, "▦", "Программы") {
            search.setText("")
            search.clearFocus()
        }

        addDockItem(dock, "⌕", "Поиск") {
            focusSearch()
        }

        addDockItem(dock, "⚙", "Настройки") {
            openSettings()
        }

        root.addView(
            dock,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(3)
            }
        )

        // Подсказка внизу
        root.addView(
            text("MacPad  •  Android Desktop", 10f, muted).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, dp(19)).apply {
                topMargin = dp(3)
            }
        )
    }

    private fun addDockItem(
        dock: LinearLayout,
        symbol: String,
        label: String,
        action: () -> Unit
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            isClickable = true
            isFocusable = true
            background = rounded(Color.TRANSPARENT, dp(14).toFloat())
            setOnClickListener { action() }
        }

        item.addView(
            text(symbol, 27f, blue).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, dp(34))
        )

        item.addView(
            text(label, 10f, muted).apply {
                gravity = Gravity.CENTER
            }
        )

        dock.addView(
            item,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
    }

    private fun focusSearch() {
        search.requestFocus()
        search.setSelection(search.text.length)

        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as InputMethodManager

        imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as InputMethodManager

        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun openSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Настройки недоступны", Toast.LENGTH_SHORT).show()
        }
    }

    // Горячие клавиши для физической клавиатуры
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_SPACE) {
            focusSearch()
            return true
        }

        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_L) {
            focusSearch()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (search.text.isNotEmpty()) {
                search.setText("")
                search.clearFocus()
                hideKeyboard()
                return true
            }
        }

        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_COMMA) {
            openSettings()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun launchFirstSearchResult() {
        if (!::allApps.isInitialized) return

        val query = search.text.toString()
        val first = allApps.firstOrNull {
            it.name.contains(query, ignoreCase = true)
        }

        if (first != null) {
            launchApp(first)
        } else {
            Toast.makeText(this, "Ничего не найдено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        allApps = packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo.packageName

                if (packageName == applicationContext.packageName) {
                    null
                } else {
                    AppItem(
                        info.loadLabel(packageManager).toString(),
                        packageName,
                        info.loadIcon(packageManager)
                    )
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun launchApp(app: AppItem) {
        try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(app.packageName)

            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(
                    this,
                    "Не удалось открыть ${app.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Ошибка запуска ${app.name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderApps(query: String) {
        if (!::appGrid.isInitialized || !::allApps.isInitialized) return

        appGrid.removeAllViews()

        val filtered = allApps.filter {
            it.name.contains(query, ignoreCase = true)
        }

        val usableWidth = resources.displayMetrics.widthPixels - dp(40)
        val columns = (usableWidth / dp(112)).coerceIn(3, 8)
        appGrid.columnCount = columns

        if (filtered.isEmpty()) {
            val empty = text(
                if (query.isBlank()) "Приложения не найдены"
                else "Ничего не найдено",
                15f,
                muted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }

            appGrid.addView(
                empty,
                GridLayout.LayoutParams().apply {
                    width = usableWidth
                    height = dp(90)
                }
            )
            return
        }

        filtered.forEach { app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                background = rounded(Color.TRANSPARENT, dp(14).toFloat())

                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(
                        if (focused) panel else Color.TRANSPARENT,
                        dp(14).toFloat()
                    )
                }

                setOnClickListener { launchApp(app) }
            }

            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = app.name
            }

            tile.addView(
                icon,
                LinearLayout.LayoutParams(dp(48), dp(48))
            )

            tile.addView(
                text(app.name, 12f, white).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(7), 0, 0)
                },
                LinearLayout.LayoutParams(-1, dp(42))
            )

            appGrid.addView(
                tile,
                GridLayout.LayoutParams().apply {
                    width = usableWidth / columns
                    height = dp(92)
                    setMargins(0, 0, 0, dp(4))
                }
            )
        }
    }
}
