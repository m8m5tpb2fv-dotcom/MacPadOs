package com.macpad.macpad

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Vertical, centred, scrollable content used by the info windows. */
private fun MacWindow.scrollColumn(build: LinearLayout.() -> Unit): View {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(22), dp(18), dp(22), dp(22))
        build()
    }
    return ScrollView(context).apply {
        isFillViewport = true
        addView(column)
    }
}

private fun LinearLayout.addFull(v: View, topMarginPx: Int = 0) {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = topMarginPx
    })
}

private fun MacWindow.startSafely(intent: Intent, failMessage: String) {
    try {
        host.activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        host.toast(failMessage)
    }
}

/** A row of equally wide buttons. */
private fun MacWindow.buttonRow(vararg buttons: View): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    buttons.forEachIndexed { i, b ->
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i > 0) marginStart = dp(10)
        })
    }
}

/**
 * Information about one installed app: icon, version, install/update dates, target API, APK size,
 * plus actions (open, Dock, desktop, Android app info). Details are read on a background thread.
 */
class AppInfoWindow(host: DesktopHost, val appKey: String) : MacWindow(host, "appinfo:$appKey") {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var closed = false
    private var details: List<Pair<String, String>>? = null
    private var loading = false

    private val appsListener: () -> Unit = {
        if (!closed) {
            details = null
            rebuild()
        }
    }

    init {
        title = host.apps.findByKey(appKey)?.label ?: "Об этом приложении"
        host.apps.addListener(appsListener)
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.APP_INFO
    override val resizable: Boolean get() = false
    override val defaultWidthDp: Int get() = 480
    override val defaultHeightDp: Int get() = 600

    override fun onCreateContent(): View {
        val p = palette
        val ctx = context
        val entry = host.apps.findByKey(appKey)
            ?: return scrollColumn {
                addFull(ctx.label("Приложение больше не установлено", 16f, p.text, 600, 2).apply { gravity = Gravity.CENTER }, dp(40))
                addFull(ctx.macButton(p, "Закрыть") { close() }, dp(18))
            }
        if (title != entry.label) title = entry.label
        val rows = details
        if (rows == null) loadDetails(entry)
        return scrollColumn {
            addView(ImageView(ctx).apply { setImageBitmap(entry.icon) }, LinearLayout.LayoutParams(dp(96), dp(96)))
            addFull(ctx.label(entry.label, 20f, p.text, 700, 2).apply { gravity = Gravity.CENTER }, dp(8))
            addFull(
                ctx.label(entry.packageName, 12f, p.textSecondary, 400, 2).apply {
                    gravity = Gravity.CENTER
                    setTextIsSelectable(true)
                },
                dp(4),
            )
            if (!entry.isMainProfile) {
                addFull(ctx.label("Рабочий профиль или клон приложения", 12f, p.accent, 500).apply { gravity = Gravity.CENTER }, dp(4))
            }
            val pinned = host.prefs.isPinned(entry.key)
            val onDesktop = host.prefs.isOnDesktop(entry.key)
            addFull(
                buttonRow(
                    ctx.macButton(p, "Открыть", ButtonStyle.PRIMARY) { host.launchApp(entry, it) },
                    ctx.macButton(p, "Сведения в Android") {
                        if (!host.apps.openSystemAppInfo(host.activity, entry)) host.toast("Не удалось открыть сведения о приложении")
                    },
                ),
                dp(16),
            )
            addFull(
                buttonRow(
                    ctx.macButton(p, if (pinned) "Убрать из Dock" else "В Dock") {
                        host.setPinned(entry.key, !pinned)
                        rebuild()
                    },
                    ctx.macButton(p, if (onDesktop) "Убрать со стола" else "На рабочий стол") {
                        host.setOnDesktop(entry.key, !onDesktop)
                        rebuild()
                    },
                ),
                dp(10),
            )
            addFull(WindowKit.sectionTitle(ctx, p, "Подробности"), dp(6))
            if (rows == null) {
                addFull(WindowKit.hint(ctx, p, "Загрузка…"))
            } else {
                addFull(WindowKit.card(ctx, p, rows.map { WindowKit.valueRow(ctx, p, it.first, it.second) }))
            }
        }
    }

    override fun onClosed() {
        closed = true
        host.apps.removeListener(appsListener)
        io.shutdownNow()
    }

    private fun loadDetails(entry: AppEntry) {
        if (loading) return
        loading = true
        val ctx = host.context.applicationContext
        io.execute {
            val rows = computeDetails(ctx, entry)
            main.post {
                loading = false
                if (closed) return@post
                details = rows
                rebuild()
            }
        }
    }

    private fun computeDetails(ctx: Context, e: AppEntry): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val rows = mutableListOf<Pair<String, String>>()
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        var ai: ApplicationInfo? = null
        if (e.isMainProfile) {
            try {
                val pi = pm.getPackageInfo(e.packageName, PackageManager.PackageInfoFlags.of(0))
                ai = pi.applicationInfo
                rows += "Версия" to "${pi.versionName ?: "—"} (${pi.longVersionCode})"
                rows += "Установлено" to df.format(Date(pi.firstInstallTime))
                rows += "Обновлено" to df.format(Date(pi.lastUpdateTime))
            } catch (_: Exception) {
            }
        } else {
            try {
                ai = ctx.getSystemService(LauncherApps::class.java)?.getApplicationInfo(e.packageName, 0, e.user)
            } catch (_: Exception) {
            }
        }
        ai?.let { a ->
            rows += "Целевая версия Android" to "API ${a.targetSdkVersion}"
            rows += "Минимальная версия" to "API ${a.minSdkVersion}"
        }
        rows += "Профиль" to if (e.isMainProfile) "Основной" else "Рабочий или клон приложения"
        rows += "Точка запуска" to e.component.shortClassName
        ai?.let { a ->
            try {
                var total = 0L
                a.sourceDir?.let { total += File(it).length() }
                a.splitSourceDirs?.forEach { total += File(it).length() }
                if (total > 0) rows += "Размер APK" to Formatter.formatShortFileSize(ctx, total)
            } catch (_: Exception) {
            }
            rows += "Системное приложение" to if ((a.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "Да" else "Нет"
        }
        return rows
    }
}

/** "Об этом устройстве": MacPad version and real device data (model, Android, memory, storage, battery). */
class AboutWindow(host: DesktopHost) : MacWindow(host, "about") {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var closed = false
    private var rows: List<Pair<String, String>>? = null
    private var generation = 0

    init {
        title = "Об этом устройстве"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.ABOUT
    override val resizable: Boolean get() = false
    override val defaultWidthDp: Int get() = 520
    override val defaultHeightDp: Int get() = 600

    override fun onCreateContent(): View {
        val p = palette
        val ctx = context
        if (rows == null) refresh()
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } catch (_: Exception) {
            null
        }
        return scrollColumn {
            addView(
                ImageView(ctx).apply { setImageBitmap(IconRenderer.renderBuiltin(BuiltinIcon.ABOUT, dp(96))) },
                LinearLayout.LayoutParams(dp(88), dp(88)),
            )
            addFull(ctx.label("MacPad", 24f, p.text, 700).apply { gravity = Gravity.CENTER }, dp(8))
            addFull(ctx.label("Версия ${version ?: "—"}", 13f, p.textSecondary).apply { gravity = Gravity.CENTER }, dp(2))
            addFull(WindowKit.sectionTitle(ctx, p, "Устройство"), dp(8))
            val r = rows
            if (r == null) {
                addFull(WindowKit.hint(ctx, p, "Загрузка…"))
            } else {
                addFull(WindowKit.card(ctx, p, r.map { WindowKit.valueRow(ctx, p, it.first, it.second) }))
            }
            addFull(
                buttonRow(
                    ctx.macButton(p, "Сведения Android…") {
                        startSafely(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), "Сведения об устройстве недоступны")
                    },
                    ctx.macButton(p, "Хранилище…") {
                        startSafely(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), "Настройки хранилища недоступны")
                    },
                ),
                dp(16),
            )
        }
    }

    override fun onActivated() {
        if (rows != null) refresh()
    }

    /** Rebuilding while a finger or button is down would cancel that click: wait for the release. */
    private var touching = false
    private var pendingRows: List<Pair<String, String>>? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (pendingRows != null) post { applyPending() }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyPending() {
        val data = pendingRows ?: return
        if (closed || touching) return
        pendingRows = null
        rows = data
        rebuild()
    }

    override fun onClosed() {
        closed = true
        io.shutdownNow()
    }

    private fun refresh() {
        val my = ++generation
        val ctx = host.context.applicationContext
        // Window metrics must be read on the main thread.
        val screen = try {
            val b = host.activity.windowManager.maximumWindowMetrics.bounds
            "${b.width()} × ${b.height()} пикс., ${ctx.resources.displayMetrics.densityDpi} dpi"
        } catch (_: Exception) {
            "—"
        }
        io.execute {
            val data = compute(ctx, screen)
            main.post {
                if (closed || my != generation || data == rows) return@post
                pendingRows = data
                applyPending()
            }
        }
    }

    private fun compute(ctx: Context, screen: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val maker = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        list += "Модель" to "$maker ${Build.MODEL}"
        list += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }?.let { list += "Обновление безопасности" to it }
        val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
            .ifBlank { Build.HARDWARE }
        list += "Процессор" to soc
        list += "Экран" to screen
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            if (mi.totalMem > 0) {
                list += "Оперативная память" to "свободно ${Formatter.formatShortFileSize(ctx, mi.availMem)} из ${Formatter.formatShortFileSize(ctx, mi.totalMem)}"
            }
        } catch (_: Exception) {
        }
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            list += "Хранилище" to "свободно ${Formatter.formatShortFileSize(ctx, st.availableBytes)} из ${Formatter.formatShortFileSize(ctx, st.totalBytes)}"
        } catch (_: Exception) {
        }
        try {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0 && scale > 0) {
                    val pct = level * 100 / scale
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    list += "Аккумулятор" to "$pct %" + if (charging) " · заряжается" else ""
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}

/** Reference of every keyboard shortcut MacPad actually handles. */
class ShortcutsWindow(host: DesktopHost) : MacWindow(host, "shortcuts") {

    private class Shortcut(val description: String, val keys: List<String>)
    private class Group(val title: String, val items: List<Shortcut>)

    private val groups = listOf(
        Group(
            "Общие",
            listOf(
                Shortcut("Spotlight", listOf("Ctrl+Space", "Alt+Space", "«Поиск»")),
                Shortcut("Launchpad (все приложения)", listOf("F4", "Ctrl+Shift+A")),
                Shortcut("Настройки MacPad", listOf("Ctrl+,")),
                Shortcut("Поиск в текущем окне", listOf("Ctrl+F", "Ctrl+L")),
                Shortcut("Закрыть меню, поиск или снять фокус", listOf("Esc")),
                Shortcut("Строка меню с клавиатуры", listOf("F10", "Ctrl+F2")),
                Shortcut("Перейти в Dock", listOf("Ctrl+F3")),
                Shortcut("Эта справка", listOf("Ctrl+/")),
            ),
        ),
        Group(
            "Окна",
            listOf(
                Shortcut("Новое окно Finder", listOf("Ctrl+N")),
                Shortcut("Закрыть окно", listOf("Ctrl+W")),
                Shortcut("Свернуть окно", listOf("Ctrl+M")),
                Shortcut("Следующее окно", listOf("Ctrl+`", "Ctrl+Tab")),
                Shortcut("Развернуть окно", listOf("Двойное нажатие на заголовок")),
            ),
        ),
        Group(
            "Навигация",
            listOf(
                Shortcut("Перемещение по значкам, спискам и меню", listOf("Стрелки")),
                Shortcut("Открыть или запустить выбранное", listOf("Enter")),
                Shortcut("Следующий элемент", listOf("Tab")),
                Shortcut("Контекстное меню", listOf("Правая кнопка", "Долгое нажатие", "Shift+F10")),
            ),
        ),
        Group(
            "Finder",
            listOf(
                Shortcut("Назад", listOf("Backspace", "Alt+←")),
                Shortcut("Вперёд", listOf("Alt+→")),
                Shortcut("На уровень вверх", listOf("Ctrl+↑")),
                Shortcut("Новая папка", listOf("Ctrl+Shift+N")),
                Shortcut("Обновить", listOf("F5", "Ctrl+R")),
                Shortcut("Свойства", listOf("Ctrl+I")),
                Shortcut("Удалить", listOf("Delete")),
            ),
        ),
        Group(
            "Заметки",
            listOf(
                Shortcut("Сохранить", listOf("Ctrl+S")),
                Shortcut("Сохранить как", listOf("Ctrl+Shift+S")),
                Shortcut("Открыть файл", listOf("Ctrl+O")),
            ),
        ),
    )

    init {
        title = "Сочетания клавиш"
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.SHORTCUTS
    override val defaultWidthDp: Int get() = 620
    override val defaultHeightDp: Int get() = 580
    override val minWidthDp: Int get() = 420

    override fun onCreateContent(): View {
        val p = palette
        val ctx = context
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(20))
        }
        for (g in groups) {
            column.addFull(WindowKit.sectionTitle(ctx, p, g.title))
            column.addFull(
                WindowKit.card(
                    ctx, p,
                    g.items.map { s -> WindowKit.row(ctx, p, s.description, control = WindowKit.keyCaps(ctx, p, s.keys)) },
                ),
            )
        }
        column.addFull(
            WindowKit.hint(
                ctx, p,
                "Вместо Ctrl можно использовать клавишу Win/⌘ (Meta). Некоторые сочетания может перехватывать Android " +
                    "(например, Ctrl+Space для смены раскладки) — тогда используйте альтернативы.",
            ),
            dp(10),
        )
        return ScrollView(ctx).apply { addView(column) }
    }
}
