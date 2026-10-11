package com.macpad.macpad

import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * MacPad settings window. Lists the Android system settings screens and the apps installed by
 * Honor (package prefix com.hihonor). Search filters both lists.
 *
 * The MacPad-only sections (appearance, wallpaper, Dock, desktop, Finder) are not built yet: the
 * section ids are kept so existing callers still compile, and [showSection] currently opens the
 * same list.
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
        const val SECTION_KEYBOARD = "keyboard"
        const val SECTION_ABOUT = "about"

        /** Honor apps are installed under this package prefix. */
        private const val HONOR_PREFIX = "com.hihonor"
    }

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
        SystemSetting("Безопасность", Glyph.LOCK, Settings.ACTION_SECURITY_SETTINGS),
        SystemSetting("Конфиденциальность", Glyph.EYE, Settings.ACTION_PRIVACY_SETTINGS),
        SystemSetting("Местоположение", Glyph.HOME, Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SystemSetting("Дата и время", Glyph.CLOCK, Settings.ACTION_DATE_SETTINGS),
        SystemSetting("Язык и ввод", Glyph.KEYBOARD, Settings.ACTION_LOCALE_SETTINGS),
        SystemSetting("Специальные возможности", Glyph.EYE, Settings.ACTION_ACCESSIBILITY_SETTINGS),
        SystemSetting("Поиск", Glyph.SEARCH, Settings.ACTION_SEARCH_SETTINGS),
        SystemSetting("О телефоне и планшете", Glyph.INFO, Settings.ACTION_DEVICE_INFO_SETTINGS),
    )

    private var query = ""
    private var list: LinearLayout? = null

    private val appsListener: () -> Unit = { renderList() }

    init {
        title = "Настройки"
        host.apps.addListener(appsListener)
    }

    override val icon: BuiltinIcon get() = BuiltinIcon.SETTINGS
    override val defaultWidthDp: Int get() = 620
    override val defaultHeightDp: Int get() = 640
    override val minWidthDp: Int get() = 380

    /** Currently only one list is shown; see the class comment. */
    fun showSection(section: String) {
        renderList()
    }

    override fun onCreateContent(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val search = context.macTextField(palette, "Поиск настроек и приложений", 14f, Glyph.SEARCH).apply {
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
            })
        }
        root.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(12), dp(16), dp(4))
            },
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        list = container
        root.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(container)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        renderList()
        return root
    }

    override fun onClosed() {
        host.apps.removeListener(appsListener)
        list = null
    }

    private fun renderList() {
        val container = list ?: return
        container.removeAllViews()
        val q = AppRepository.normalize(query.trim())

        container.addView(sectionTitle("Настройки Android"))
        val settings = systemSettings.filter { q.isEmpty() || AppRepository.normalize(it.title).contains(q) }
        if (settings.isEmpty()) {
            container.addView(hint("Ничего не найдено"))
        } else {
            container.addView(
                card(settings.map { s -> row(glyphIcon(s.glyph), s.title) { openSystemSetting(s.action) } }),
            )
        }

        container.addView(sectionTitle("Приложения Honor"))
        if (!host.apps.isLoaded) {
            container.addView(hint("Загрузка…"))
            return
        }
        val honor = host.apps.apps.filter {
            it.packageName.startsWith(HONOR_PREFIX) && (q.isEmpty() || it.searchLabel.contains(q))
        }
        if (honor.isEmpty()) {
            container.addView(hint(if (q.isEmpty()) "Приложения Honor не найдены" else "Ничего не найдено"))
        } else {
            container.addView(
                card(
                    honor.map { app ->
                        val icon = ImageView(context).apply {
                            setImageBitmap(app.icon)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                        row(icon, app.label) { host.launchApp(app, it) }
                    },
                ),
            )
        }
    }

    private fun openSystemSetting(action: String) {
        try {
            host.activity.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            host.toast("Этот раздел недоступен на устройстве")
        }
    }

    private fun glyphIcon(glyph: Glyph): ImageView = ImageView(context).apply {
        setImageDrawable(GlyphDrawable(glyph, palette.text))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun sectionTitle(text: String): View =
        context.label(text, 13f, palette.textSecondary, 600).apply {
            setPadding(dp(4), dp(16), dp(4), dp(6))
        }

    private fun hint(text: String): View =
        context.label(text, 13f, palette.textTertiary, 400, 2).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }

    private fun card(rows: List<View>): View {
        val p = palette
        val cardColor = if (p.dark) Color.rgb(44, 44, 48) else Color.WHITE
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(cardColor, dpf(10f), p.separator, context.hairlinePx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            rows.forEachIndexed { i, r ->
                if (i > 0) {
                    addView(
                        View(context).apply { setBackgroundColor(p.separator) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.hairlinePx).apply {
                            marginStart = dp(44)
                        },
                    )
                }
                addView(r)
            }
        }
    }

    private fun row(icon: ImageView, title: String, onClick: (View) -> Unit): View {
        val p = palette
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = context.stateBackground(p, dpf(8f))
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
            addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
            addView(
                context.label(title, 15f, p.text),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ImageView(context).apply { setImageDrawable(GlyphDrawable(Glyph.CHEVRON_RIGHT, p.textTertiary)) },
                LinearLayout.LayoutParams(dp(16), dp(16)),
            )
        }
    }
}
