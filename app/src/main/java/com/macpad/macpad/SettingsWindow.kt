package com.macpad.macpad

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * MacPad settings in the style of macOS System Settings: a sidebar of sections and grouped cards.
 * Every change is saved to [MacPrefs] immediately and applied through the host.
 *
 * Besides MacPad's own options it lists the Android system settings screens and the apps installed
 * by Honor (package prefix com.hihonor).
 */
class SettingsWindow(host: DesktopHost) : MacWindow(host, ID) {

    companion object {
        const val ID = "settings"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_WALLPAPER = "wallpaper"
        const val SECTION_DOCK = "dock"
        const val SECTION_DESKTOP = "desktop"
        const val SECTION_FINDER = "finder"
        const val SECTION_LAUNCHER = "launcher"
        const val SECTION_ANDROID = "android"
        const val SECTION_HONOR = "honor"
        const val SECTION_KEYBOARD = "keyboard"
        const val SECTION_ABOUT = "about"

        /** Honor apps are installed under this package prefix. */
        private const val HONOR_PREFIX = "com.hihonor"

        /** Wallpaper thumbnails survive window rebuilds and reopenings. */
        private val thumbs = HashMap<String, Bitmap>()
    }

    private class Section(val id: String, val title: String, val glyph: Glyph, val color: Int)

    private val sections = listOf(
        Section(SECTION_APPEARANCE, "Оформление", Glyph.PALETTE, Color.rgb(10, 132, 255)),
        Section(SECTION_WALLPAPER, "Обои", Glyph.IMAGE, Color.rgb(48, 176, 199)),
        Section(SECTION_DOCK, "Dock", Glyph.DOCK, Color.rgb(94, 92, 230)),
        Section(SECTION_DESKTOP, "Рабочий стол", Glyph.DESKTOP, Color.rgb(88, 86, 214)),
        Section(SECTION_FINDER, "Finder", Glyph.FOLDER, Color.rgb(0, 122, 255)),
        Section(SECTION_LAUNCHER, "Главный экран", Glyph.HOME, Color.rgb(52, 199, 89)),
        Section(SECTION_ANDROID, "Настройки Android", Glyph.GEAR, Color.rgb(142, 142, 147)),
        Section(SECTION_HONOR, "Приложения Honor", Glyph.APP, Color.rgb(255, 69, 58)),
        Section(SECTION_KEYBOARD, "Клавиатура", Glyph.KEYBOARD, Color.rgb(99, 99, 102)),
        Section(SECTION_ABOUT, "О MacPad", Glyph.INFO, Color.rgb(99, 99, 102)),
    )

    /** A system settings screen opened through a public Settings intent action. */
    private class SystemSetting(val title: String, val glyph: Glyph, val action: String)

    private val systemSettings = listOf(
        SystemSetting("Wi-Fi", Glyph.WIFI, Settings.ACTION_WIFI_SETTINGS),
        SystemSetting("Сети и интернет", Glyph.CELLULAR, Settings.ACTION_WIRELESS_SETTINGS),
        SystemSetting("Режим полёта", Glyph.CELLULAR, Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        SystemSetting("Bluetooth", Glyph.ETHERNET, Settings.ACTION_BLUETOOTH_SETTINGS),
        SystemSetting("Экран", Glyph.SUN, Settings.ACTION_DISPLAY_SETTINGS),
        SystemSetting("Звук", Glyph.MUSIC, Settings.ACTION_SOUND_SETTINGS),
        SystemSetting("Батарея", Glyph.BOLT, Settings.ACTION_BATTERY_SAVER_SETTINGS),
        SystemSetting("Хранилище", Glyph.DRIVE, Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        SystemSetting("Приложения", Glyph.APP, Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        SystemSetting("Приложения по умолчанию", Glyph.HOME, Settings.ACTION_HOME_SETTINGS),
        SystemSetting("Безопасность", Glyph.LOCK, Settings.ACTION_SECURITY_SETTINGS),
        SystemSetting("Конфиденциальность", Glyph.EYE, Settings.ACTION_PRIVACY_SETTINGS),
        SystemSetting("Местоположение", Glyph.HOME, Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SystemSetting("Дата и время", Glyph.CLOCK, Settings.ACTION_DATE_SETTINGS),
        SystemSetting("Язык и регион", Glyph.KEYBOARD, Settings.ACTION_LOCALE_SETTINGS),
        SystemSetting("Клавиатуры", Glyph.KEYBOARD, Settings.ACTION_INPUT_METHOD_SETTINGS),
        SystemSetting("Специальные возможности", Glyph.EYE, Settings.ACTION_ACCESSIBILITY_SETTINGS),
        SystemSetting("О планшете", Glyph.INFO, Settings.ACTION_DEVICE_INFO_SETTINGS),
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var closed = false

    private var current = SECTION_APPEARANCE
    private var sidebar: LinearLayout? = null
    private var content: LinearLayout? = null
    private var contentScroll: ScrollView? = null
    private var shownExternalState = ""

    /** Thumbnail views of the wallpaper section currently on screen, by wallpaper id. */
    private val thumbViews = HashMap<String, ImageView>()

    private val appsListener: () -> Unit = {
        if (current == SECTION_HONOR || current == SECTION_LAUNCHER || current == SECTION_DOCK || current == SECTION_DESKTOP) {
            renderContent()
        }
    }

    init {
        title = "Настройки MacPad"
        host.apps.addListener(appsListener)
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.SETTINGS
    override val defaultWidthDp: Int get() = 840
    override val defaultHeightDp: Int get() = 580
    override val minWidthDp: Int get() = 560
    override val minHeightDp: Int get() = 380

    /** Opens [section] (one of the SECTION_* ids); unknown ids open "Оформление". */
    fun showSection(section: String) {
        current = if (sections.any { it.id == section }) section else SECTION_APPEARANCE
        renderSidebar()
        renderContent(resetScroll = true)
    }

    override fun onCreateContent(): View {
        val p = palette
        val ctx = context
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val side = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        sidebar = side
        root.addView(
            ScrollView(ctx).apply {
                setBackgroundColor(p.windowSidebar)
                isFillViewport = true
                addView(side)
            },
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(View(ctx).apply { setBackgroundColor(p.separator) }, LinearLayout.LayoutParams(ctx.hairlinePx, ViewGroup.LayoutParams.MATCH_PARENT))

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        content = body
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(body)
        }
        contentScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        renderSidebar()
        renderContent()
        return root
    }

    override fun onActivated() {
        // The default-launcher state or granted folders may have changed in Android settings or
        // Finder. Re-render only on a change, and posted: onActivated runs while the click that
        // activated the window is being dispatched, and new views would swallow it.
        if (current != SECTION_LAUNCHER && current != SECTION_FINDER) return
        post {
            if (!closed && (current == SECTION_LAUNCHER || current == SECTION_FINDER) && externalState() != shownExternalState) renderContent()
        }
    }

    /** State shown by the Finder and Main screen sections that can change outside this window. */
    private fun externalState(): String =
        host.isDefaultLauncher().toString() + "|" + host.prefs.finderPlaces.joinToString("|") { it.uri + "=" + FinderPlaces.hasAccess(context, it.uri) }

    override fun onClosed() {
        closed = true
        host.apps.removeListener(appsListener)
        io.shutdownNow()
        thumbViews.clear()
        sidebar = null
        content = null
    }

    // ---- Sidebar ----------------------------------------------------------------------------

    private fun renderSidebar() {
        val side = sidebar ?: return
        val p = palette
        val ctx = context
        side.removeAllViews()
        for (s in sections) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = ctx.stateBackground(p, dpf(8f))
                isClickable = true
                isFocusable = true
                isSelected = s.id == current
                contentDescription = s.title
                setOnClickListener { showSection(s.id) }
            }
            row.addView(WindowKit.iconTile(ctx, s.glyph, s.color), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            row.addView(
                ctx.label(s.title, 14f, p.text).apply {
                    setTextColor(selectableTextColors(p.text, p.onSelection))
                    isDuplicateParentStateEnabled = true
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            side.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2)
            })
        }
    }

    // ---- Content ----------------------------------------------------------------------------

    private fun renderContent(resetScroll: Boolean = false) {
        val body = content ?: return
        val scrollY = if (resetScroll) 0 else contentScroll?.scrollY ?: 0
        shownExternalState = externalState()
        body.removeAllViews()
        thumbViews.clear()
        val section = sections.firstOrNull { it.id == current } ?: sections.first()
        body.addView(WindowKit.pageTitle(context, palette, section.title))
        when (section.id) {
            SECTION_APPEARANCE -> appearance(body)
            SECTION_WALLPAPER -> wallpaper(body)
            SECTION_DOCK -> dock(body)
            SECTION_DESKTOP -> desktop(body)
            SECTION_FINDER -> finder(body)
            SECTION_LAUNCHER -> launcher(body)
            SECTION_ANDROID -> android(body)
            SECTION_HONOR -> honor(body)
            SECTION_KEYBOARD -> keyboard(body)
            SECTION_ABOUT -> about(body)
        }
        // Buttons inside a page (↑ ↓ ✕, switches) re-render it: keep the user's place.
        val scroll = contentScroll
        if (scrollY == 0) scroll?.scrollTo(0, 0) else scroll?.post { scroll.scrollTo(0, scrollY) }
    }

    private fun LinearLayout.add(v: View, topDp: Int = 0) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topDp)
        })
    }

    private fun section(body: LinearLayout, title: String?, rows: List<View>) {
        if (title != null) body.add(WindowKit.sectionTitle(context, palette, title))
        else body.add(View(context), 12)
        body.add(WindowKit.card(context, palette, rows))
    }

    private fun switch(checked: Boolean, onChange: (Boolean) -> Unit): MacSwitch = MacSwitch(context).apply {
        palette = this@SettingsWindow.palette
        setChecked(checked)
        onCheckedChange = onChange
    }

    private fun buttons(vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
    }

    private fun openSafely(intent: Intent, fail: String) {
        try {
            host.activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            host.toast(fail)
        }
    }

    private fun appIcon(entry: AppEntry): ImageView = ImageView(context).apply {
        setImageBitmap(entry.icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    // ---- Sections ---------------------------------------------------------------------------

    private fun appearance(body: LinearLayout) {
        val p = palette
        val ctx = context
        val prefs = host.prefs
        val themes = listOf("light", "dark", "system")
        val sizes = listOf("small", "medium", "large")
        section(
            body, null,
            listOf(
                WindowKit.row(
                    ctx, p, "Тема",
                    control = MacSegmented(ctx, p, listOf("Светлая", "Тёмная", "Как в системе"), max(0, themes.indexOf(prefs.themeMode))) {
                        prefs.themeMode = themes[it]
                        host.applySettings()
                    },
                ),
                WindowKit.row(
                    ctx, p, "Размер значков", "Launchpad и рабочий стол",
                    control = MacSegmented(ctx, p, listOf("Мелкие", "Средние", "Крупные"), max(0, sizes.indexOf(prefs.iconSize))) {
                        prefs.iconSize = sizes[it]
                        host.applySettings()
                    },
                ),
            ),
        )
        section(
            body, "Строка меню",
            listOf(
                WindowKit.row(ctx, p, "Дата в строке меню", control = switch(prefs.showDate) {
                    prefs.showDate = it
                    host.applySettings()
                }),
                WindowKit.row(
                    ctx, p, "Скрывать строку состояния Android",
                    "Часы и заряд аккумулятора показываются в строке меню MacPad. Строку состояния можно вызвать свайпом сверху.",
                    control = switch(prefs.hideStatusBar) {
                        prefs.hideStatusBar = it
                        host.applySettings()
                    },
                ),
            ),
        )
        section(
            body, "Движение",
            listOf(
                WindowKit.row(ctx, p, "Уменьшить движение", "Отключает анимации окон, Dock и Launchpad.", control = switch(prefs.reduceMotion) {
                    prefs.reduceMotion = it
                }),
            ),
        )
    }

    private fun wallpaper(body: LinearLayout) {
        val p = palette
        val ctx = context
        val prefs = host.prefs
        val selected = prefs.wallpaper
        val cards = Wallpapers.catalog.map { info ->
            wallpaperCard(info.title, selected == info.id, info.id) {
                prefs.wallpaper = info.id
                host.applySettings()
                renderContent()
            }
        } + listOf(
            wallpaperCard("Системные обои", selected == Wallpapers.SYSTEM, null, Glyph.MONITOR) {
                prefs.wallpaper = Wallpapers.SYSTEM
                host.applySettings()
                renderContent()
            },
            wallpaperCard(
                "Своё изображение…",
                selected == Wallpapers.CUSTOM,
                prefs.customWallpaperUri?.let { "custom:$it" },
                Glyph.IMAGE,
            ) { host.pickWallpaperImage() },
        )
        val columns = 3
        cards.chunked(columns).forEach { chunk ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, card ->
                line.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(12)
                })
            }
            repeat(columns - chunk.size) {
                line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(12) })
            }
            body.add(line, 14)
        }
        if (selected == Wallpapers.SYSTEM) {
            body.add(WindowKit.hint(ctx, p, "Используются обои Android. Стеклянные панели в этом режиме не размываются."), 8)
        }
        loadThumbs()
    }

    /**
     * A wallpaper card. [thumbKey] is a catalog id, "custom:<uri>" or null (glyph placeholder only).
     */
    private fun wallpaperCard(caption: String, selected: Boolean, thumbKey: String?, placeholder: Glyph? = null, onClick: () -> Unit): View {
        val p = palette
        val ctx = context
        val radius = dpf(10f)
        val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundRect(if (p.dark) Color.rgb(58, 58, 62) else Color.rgb(222, 222, 228), radius)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            val cached = thumbKey?.let { thumbs[it] }
            if (cached != null) {
                setImageBitmap(cached)
            } else if (placeholder != null) {
                setImageDrawable(GlyphDrawable(placeholder, p.textSecondary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(30)
                setPadding(pad, pad, pad, pad)
            }
        }
        if (thumbKey != null && thumbs[thumbKey] == null) thumbViews[thumbKey] = image
        val frame = FrameLayout(ctx).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = if (selected) roundRect(Color.TRANSPARENT, radius + dpf(3f), p.accent, dp(3)) else null
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            background = ctx.stateBackground(p, dpf(12f))
            setPadding(dp(4), dp(4), dp(4), dp(6))
            contentDescription = caption
            setOnClickListener { onClick() }
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                ctx.label(caption, 12.5f, if (selected) p.accent else p.text, if (selected) 600 else 400).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
    }

    private fun loadThumbs() {
        val wanted = thumbViews.keys.toList()
        if (wanted.isEmpty()) return
        val resolver = host.context.contentResolver
        io.execute {
            for (key in wanted) {
                val bmp = try {
                    if (key.startsWith("custom:")) {
                        val src = ImageDecoder.createSource(resolver, Uri.parse(key.removePrefix("custom:")))
                        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val w = 336
                            val h = max(1, info.size.height * w / max(1, info.size.width))
                            decoder.setTargetSize(w, h)
                        }
                    } else {
                        Wallpapers.render(key, 336, 210)
                    }
                } catch (_: Throwable) {
                    null
                } ?: continue
                main.post {
                    if (closed) return@post
                    thumbs[key] = bmp
                    thumbViews.remove(key)?.let {
                        it.setPadding(0, 0, 0, 0)
                        it.scaleType = ImageView.ScaleType.CENTER_CROP
                        it.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun dock(body: LinearLayout) {
        val p = palette
        val ctx = context
        val prefs = host.prefs
        val valueLabel = ctx.label("${prefs.dockIconSizeDp} dp", 13f, p.textSecondary)
        val seek = SeekBar(ctx).apply {
            max = 10
            progress = ((prefs.dockIconSizeDp - 40) / 4).coerceIn(0, 10)
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            isFocusable = true
            var tracking = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    val dpValue = 40 + value * 4
                    valueLabel.text = "$dpValue dp"
                    if (fromUser && !tracking) apply(dpValue)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    tracking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    tracking = false
                    apply(40 + sb.progress * 4)
                }

                private fun apply(v: Int) {
                    prefs.dockIconSizeDp = v
                    host.onDockChanged()
                }
            })
        }
        val sizeControl = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(valueLabel, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        section(
            body, null,
            listOf(
                WindowKit.row(ctx, p, "Размер значков", control = sizeControl),
                WindowKit.row(ctx, p, "Увеличение значков при наведении мыши", control = switch(prefs.dockMagnify) {
                    prefs.dockMagnify = it
                    host.onDockChanged()
                }),
                WindowKit.row(ctx, p, "Подписи под значками", control = switch(prefs.dockShowLabels) {
                    prefs.dockShowLabels = it
                    host.onDockChanged()
                }),
            ),
        )
        val pinned = prefs.dockPinned
        val resolved = pinned.mapNotNull { k -> host.apps.findByKey(k)?.let { k to it } }
        val rows = if (resolved.isEmpty()) {
            listOf(WindowKit.row(ctx, p, if (host.apps.isLoaded) "Нет закреплённых приложений" else "Загрузка…", titleColor = p.textSecondary))
        } else {
            resolved.mapIndexed { i, (key, entry) ->
                val controls = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(ctx.glyphButton(p, Glyph.CHEVRON_UP, "Выше", 32) { movePinned(key, -1) }.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.35f })
                    addView(ctx.glyphButton(p, Glyph.CHEVRON_DOWN, "Ниже", 32) { movePinned(key, 1) }.apply {
                        val ok = i < resolved.size - 1
                        isEnabled = ok
                        alpha = if (ok) 1f else 0.35f
                    })
                    addView(ctx.glyphButton(p, Glyph.CLOSE, "Убрать из Dock", 32) {
                        host.setPinned(key, false)
                        renderContent()
                    })
                }
                WindowKit.row(ctx, p, entry.label, control = controls, icon = appIcon(entry))
            }
        }
        section(body, "Закреплённые приложения", rows)
        body.add(
            buttons(
                ctx.macButton(p, "Добавить приложение…") {
                    pickApp(prefs.dockPinned.toSet()) { e ->
                        host.setPinned(e.key, true)
                        renderContent()
                    }
                },
                ctx.macButton(p, "Вернуть по умолчанию") {
                    prefs.dockPinned = host.apps.defaultDockKeys()
                    host.onDockChanged()
                    renderContent()
                },
            ),
            12,
        )
    }

    private fun movePinned(key: String, delta: Int) {
        val list = host.prefs.dockPinned.toMutableList()
        val i = list.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]
        list[j] = key
        host.prefs.dockPinned = list
        host.onDockChanged()
        renderContent()
    }

    private fun desktop(body: LinearLayout) {
        val p = palette
        val ctx = context
        val prefs = host.prefs
        section(
            body, null,
            listOf(
                WindowKit.row(ctx, p, "Показывать значки на рабочем столе", control = switch(prefs.desktopIconsEnabled) {
                    prefs.desktopIconsEnabled = it
                    host.onDesktopChanged()
                }),
            ),
        )
        val items = prefs.desktopItems.mapNotNull { host.apps.findByKey(it.key) }
        val rows = if (items.isEmpty()) {
            listOf(WindowKit.row(ctx, p, "На рабочем столе нет значков", titleColor = p.textSecondary))
        } else {
            items.map { e ->
                WindowKit.row(
                    ctx, p, e.label, icon = appIcon(e),
                    control = ctx.glyphButton(p, Glyph.CLOSE, "Убрать с рабочего стола", 32) {
                        host.setOnDesktop(e.key, false)
                        renderContent()
                    },
                )
            }
        }
        section(body, "Значки", rows)
        body.add(
            buttons(
                ctx.macButton(p, "Добавить приложение…") {
                    pickApp(prefs.desktopItems.map { it.key }.toSet()) { e ->
                        host.setOnDesktop(e.key, true)
                        renderContent()
                    }
                },
                ctx.macButton(p, "Упорядочить") { host.arrangeDesktop() }.apply {
                    isEnabled = items.isNotEmpty()
                    alpha = if (items.isNotEmpty()) 1f else 0.4f
                },
                ctx.macButton(p, "Убрать все…", ButtonStyle.DESTRUCTIVE) {
                    host.dialogs.confirm("Убрать все значки с рабочего стола?", "Приложения не будут удалены.", "Убрать", destructive = true) {
                        prefs.desktopItems = emptyList()
                        host.onDesktopChanged()
                        renderContent()
                    }
                }.apply {
                    isEnabled = items.isNotEmpty()
                    alpha = if (items.isNotEmpty()) 1f else 0.4f
                },
            ),
            12,
        )
    }

    private fun finder(body: LinearLayout) {
        val p = palette
        val ctx = context
        val prefs = host.prefs
        val modes = listOf("list", "grid")
        section(
            body, null,
            listOf(
                WindowKit.row(ctx, p, "Показывать скрытые файлы", control = switch(prefs.finderShowHidden) { prefs.finderShowHidden = it }),
                WindowKit.row(
                    ctx, p, "Вид по умолчанию",
                    control = MacSegmented(ctx, p, listOf("Список", "Значки"), max(0, modes.indexOf(prefs.finderViewMode))) {
                        prefs.finderViewMode = modes[it]
                    },
                ),
            ),
        )
        val places = prefs.finderPlaces
        val rows = if (places.isEmpty()) {
            listOf(WindowKit.row(ctx, p, "Папок пока нет", titleColor = p.textSecondary))
        } else {
            places.map { place ->
                val ok = FinderPlaces.hasAccess(ctx, place.uri)
                val controls = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(ctx.glyphButton(p, Glyph.EXTERNAL, "Открыть в Finder", 32) { host.openFinder(placeUri = place.uri) })
                    addView(ctx.glyphButton(p, Glyph.CLOSE, "Убрать", 32) {
                        FinderPlaces.remove(ctx, prefs, place)
                        renderContent()
                    })
                }
                WindowKit.row(
                    ctx, p, place.name,
                    description = if (ok) null else "Доступ отозван",
                    control = controls,
                    icon = WindowKit.glyphIcon(ctx, Glyph.FOLDER, if (ok) p.accent else p.destructive),
                )
            }
        }
        section(body, "Папки с доступом", rows)
        body.add(
            WindowKit.hint(ctx, p, "MacPad использует системный выбор папок (Storage Access Framework) и видит только папки, к которым вы дали доступ."),
            6,
        )
        body.add(
            buttons(
                ctx.macButton(p, "Добавить папку…") {
                    host.pickFolder { uri ->
                        if (uri == null || closed) return@pickFolder
                        val appContext = host.context.applicationContext
                        val prefs = host.prefs
                        // FinderPlaces.add queries the provider for the folder name: keep it off the UI thread.
                        io.execute {
                            FinderPlaces.add(appContext, prefs, uri)
                            main.post { if (!closed) renderContent() }
                        }
                    }
                },
            ),
            8,
        )
    }

    private fun launcher(body: LinearLayout) {
        val p = palette
        val ctx = context
        val isDefault = host.isDefaultLauncher()
        val statusRow = if (isDefault) {
            WindowKit.row(ctx, p, "MacPad — главный экран по умолчанию", icon = WindowKit.glyphIcon(ctx, Glyph.CHECK, p.success))
        } else {
            WindowKit.row(
                ctx, p, "Сейчас главный экран — другое приложение",
                control = ctx.macButton(p, "Сделать главным", ButtonStyle.PRIMARY) { host.requestDefaultLauncher() },
            )
        }
        val count = if (host.apps.isLoaded) host.apps.apps.size.toString() else "загрузка…"
        section(
            body, null,
            listOf(
                statusRow,
                WindowKit.row(
                    ctx, p, "Найдено приложений: $count",
                    control = ctx.macButton(p, "Обновить список") {
                        host.apps.reload()
                        host.toast("Список приложений обновляется")
                    },
                ),
            ),
        )
        body.add(
            WindowKit.hint(
                ctx, p,
                "MacPad показывает все приложения, у которых есть значок запуска (в том числе из рабочего профиля). " +
                    "Специальное разрешение QUERY_ALL_PACKAGES не требуется.",
            ),
            6,
        )
        body.add(buttons(ctx.macButton(p, "Системные настройки Android…") { host.openSystemSettings() }), 8)
    }

    private fun android(body: LinearLayout) {
        val p = palette
        val ctx = context
        section(
            body, null,
            systemSettings.map { s ->
                WindowKit.row(
                    ctx, p, s.title,
                    icon = WindowKit.glyphIcon(ctx, s.glyph, p.text),
                    control = WindowKit.glyphIcon(ctx, Glyph.CHEVRON_RIGHT, p.textTertiary).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                    },
                ) { openSafely(Intent(s.action), "Этот раздел недоступен на устройстве") }
            },
        )
        body.add(buttons(ctx.macButton(p, "Все настройки Android…") { host.openSystemSettings() }), 12)
    }

    private fun honor(body: LinearLayout) {
        val p = palette
        val ctx = context
        if (!host.apps.isLoaded) {
            body.add(WindowKit.hint(ctx, p, "Загрузка…"), 12)
            return
        }
        val apps = host.apps.apps.filter { it.packageName.startsWith(HONOR_PREFIX) }
        if (apps.isEmpty()) {
            body.add(WindowKit.hint(ctx, p, "Приложения Honor не найдены."), 12)
            return
        }
        section(
            body, null,
            apps.map { e ->
                WindowKit.row(
                    ctx, p, e.label,
                    icon = appIcon(e),
                    control = WindowKit.glyphIcon(ctx, Glyph.CHEVRON_RIGHT, p.textTertiary).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                    },
                ) { host.launchApp(e, it) }
            },
        )
        body.add(WindowKit.hint(ctx, p, "Долгое нажатие на значок в Launchpad позволяет закрепить приложение в Dock или на рабочем столе."), 6)
    }

    private fun keyboard(body: LinearLayout) {
        val p = palette
        val ctx = context
        val items = listOf(
            "Spotlight" to listOf("Ctrl+Space", "Alt+Space"),
            "Launchpad" to listOf("F4", "Ctrl+Shift+A"),
            "Настройки MacPad" to listOf("Ctrl+,"),
            "Поиск" to listOf("Ctrl+L", "Ctrl+F"),
            "Закрыть поиск или меню" to listOf("Esc"),
            "Новое окно Finder" to listOf("Ctrl+N"),
            "Закрыть окно" to listOf("Ctrl+W"),
            "Свернуть окно" to listOf("Ctrl+M"),
            "Следующее окно" to listOf("Ctrl+`", "Ctrl+Tab"),
            "Строка меню" to listOf("F10", "Ctrl+F2"),
            "Dock" to listOf("Ctrl+F3"),
            "Все сочетания" to listOf("Ctrl+/"),
        )
        section(body, null, items.map { (d, keys) -> WindowKit.row(ctx, p, d, control = WindowKit.keyCaps(ctx, p, keys)) })
        body.add(
            WindowKit.hint(ctx, p, "Вместо Ctrl можно нажимать клавишу Win/⌘. Если Android перехватывает Ctrl+Space для смены раскладки, используйте Alt+Space."),
            6,
        )
        body.add(buttons(ctx.macButton(p, "Все сочетания клавиш…") { host.openShortcuts() }), 8)
    }

    private fun about(body: LinearLayout) {
        val p = palette
        val ctx = context
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } catch (_: Exception) {
            null
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val iconView = ImageView(ctx).apply {
                try {
                    setImageDrawable(pm.getApplicationIcon(ctx.packageName))
                } catch (_: Exception) {
                    setImageBitmap(IconRenderer.renderBuiltin(BuiltinIcon.SETTINGS, dp(72)))
                }
            }
            addView(iconView, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(ctx.label("MacPad", 22f, p.text, 700))
            texts.addView(ctx.label("Версия ${info?.versionName ?: "—"} (${info?.longVersionCode ?: 0})", 13f, p.textSecondary))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.add(header, 14)
        section(
            body, null,
            listOf(
                WindowKit.valueRow(ctx, p, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                WindowKit.valueRow(ctx, p, "Устройство", "${Build.MANUFACTURER} ${Build.MODEL}"),
            ),
        )
        body.add(buttons(ctx.macButton(p, "Об этом устройстве…") { host.openAbout() }), 12)
    }

    // ---- App picker -------------------------------------------------------------------------

    /** Searchable list of installed apps (without [exclude]); [onPick] receives the chosen app. */
    private fun pickApp(exclude: Set<String>, onPick: (AppEntry) -> Unit) {
        val p = palette
        val ctx = context
        var filtered = host.apps.apps.filter { it.key !in exclude }
        val adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    addView(ImageView(ctx), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
                    addView(ctx.label("", 14f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val e = filtered[position]
                (row.getChildAt(0) as ImageView).setImageBitmap(e.icon)
                (row.getChildAt(1) as TextView).text = e.label
                return row
            }
        }
        val list = ListView(ctx).apply {
            this.adapter = adapter
            divider = null
            selector = ctx.stateBackground(p, dpf(6f))
            setOnItemClickListener { _, _, position, _ ->
                val e = filtered.getOrNull(position) ?: return@setOnItemClickListener
                host.dialogs.dismissTop()
                onPick(e)
            }
        }
        val search = ctx.macTextField(p, "Поиск приложений", 14f, Glyph.SEARCH).apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filtered = host.apps.search(s?.toString().orEmpty()).filter { it.key !in exclude }
                    adapter.notifyDataSetChanged()
                }
            })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(8) })
        }
        host.dialogs.show(
            "Выберите приложение", null, content = box,
            buttons = listOf(DialogButton("Отмена", isCancel = true)),
        )
    }
}
