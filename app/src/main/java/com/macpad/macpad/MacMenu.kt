package com.macpad.macpad

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.min

/** An entry of a [MacMenu]. */
sealed class MenuEntry {
    data class Item(
        val title: String,
        /** Shortcut hint shown at the right, e.g. "Ctrl+W". Purely informational. */
        val shortcut: String? = null,
        val enabled: Boolean = true,
        val checked: Boolean = false,
        val glyph: Glyph? = null,
        val icon: Bitmap? = null,
        val destructive: Boolean = false,
        val action: () -> Unit = {},
    ) : MenuEntry()

    data class Header(val title: String) : MenuEntry()

    object Separator : MenuEntry()
}

/**
 * macOS-style popup menu (menu bar drop-downs and context menus) on a frosted panel.
 * Keyboard: Up/Down move, Enter activates, Esc closes, Left/Right are forwarded to
 * [show]'s `onHorizontalKey` (used by the menu bar to switch between menus).
 */
class MacMenu(private val host: DesktopHost) {

    private var popup: PopupWindow? = null
    private val main = Handler(Looper.getMainLooper())

    val isShowing: Boolean get() = popup?.isShowing == true

    /** Shows the menu below [anchor]. */
    fun show(
        anchor: View,
        entries: List<MenuEntry>,
        xOffset: Int = 0,
        yOffset: Int = 0,
        minWidthPx: Int = 0,
        keyboard: Boolean = false,
        onHorizontalKey: ((Int) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        touchModal: Boolean = true,
    ) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        showAtScreen(loc[0] + xOffset, loc[1] + anchor.height + yOffset, entries, minWidthPx, keyboard, onHorizontalKey, onDismiss, touchModal)
    }

    /**
     * Shows the menu with its top-left corner at screen position ([x], [y]), kept on screen.
     * With [touchModal] = false, touches and mouse hover outside the menu also reach the views
     * below (the menu still closes on an outside tap) — used by the menu bar so that hovering
     * another title switches menus like on macOS.
     */
    fun showAtScreen(
        x: Int,
        y: Int,
        entries: List<MenuEntry>,
        minWidthPx: Int = 0,
        keyboard: Boolean = false,
        onHorizontalKey: ((Int) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        touchModal: Boolean = true,
    ) {
        dismiss()
        if (entries.isEmpty()) return
        val ctx = host.context
        val p = host.palette

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
        }
        val focusables = mutableListOf<View>()
        for (e in entries) {
            when (e) {
                is MenuEntry.Item -> {
                    val row = itemRow(e, p)
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    if (e.enabled) focusables.add(row)
                }
                is MenuEntry.Header -> list.addView(
                    ctx.label(e.title, 12f, p.textTertiary, 600).apply {
                        setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(12), ctx.dp(4))
                    },
                )
                MenuEntry.Separator -> list.addView(
                    View(ctx).apply { setBackgroundColor(p.separator) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairlinePx).apply {
                        setMargins(ctx.dp(8), ctx.dp(5), ctx.dp(8), ctx.dp(5))
                    },
                )
            }
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            addView(list)
        }

        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ESCAPE -> { dismiss(); return true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> onHorizontalKey?.let { cb -> dismiss(); cb(-1); return true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> onHorizontalKey?.let { cb -> dismiss(); cb(1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                            if (findFocus() == null && focusables.isNotEmpty()) {
                                (if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) focusables.first() else focusables.last()).requestFocus()
                                return true
                            }
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.addView(scroll)
        val radius = ctx.dpf(10f)
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        root.clipToOutline = true

        val dm = ctx.resources.displayMetrics
        val margin = ctx.dp(6)
        val maxW = ctx.dp(380)
        val minW = max(minWidthPx, ctx.dp(210))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels - margin * 2, View.MeasureSpec.AT_MOST),
        )
        val w = root.measuredWidth.coerceIn(minW, maxW)
        val h = min(root.measuredHeight, dm.heightPixels - margin * 2)
        val px = x.coerceIn(margin, max(margin, dm.widthPixels - w - margin))
        val py = if (y + h > dm.heightPixels - margin) max(margin, dm.heightPixels - margin - h) else y

        val pw = PopupWindow(root, w, h, true)
        pw.setBackgroundDrawable(
            GlassDrawable(root, host.glass, p.popupTint, withAlpha(p.popupTint, 245), radius, p.glassStroke, ctx.hairlinePx.toFloat()),
        )
        pw.elevation = ctx.dpf(18f)
        pw.isOutsideTouchable = true
        pw.isTouchModal = touchModal
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        pw.animationStyle = 0
        pw.setOnDismissListener {
            if (popup === pw) popup = null
            onDismiss?.invoke()
        }
        try {
            pw.showAtLocation(host.activity.window.decorView, Gravity.TOP or Gravity.START, px, py)
        } catch (e: Exception) {
            return
        }
        popup = pw
        if (host.animationsEnabled) {
            root.alpha = 0f
            root.translationY = -ctx.dpf(4f)
            root.animate().alpha(1f).translationY(0f).setDuration(120).start()
        }
        if (keyboard && focusables.isNotEmpty()) root.post { focusables.first().requestFocus() }
    }

    fun dismiss() {
        val pw = popup
        popup = null
        try {
            pw?.dismiss()
        } catch (_: Exception) {
        }
    }

    private fun itemRow(item: MenuEntry.Item, p: Palette): View {
        val ctx = host.context
        val normal = if (item.destructive) p.destructive else p.text
        val active = intArrayOf(android.R.attr.state_pressed)
        val focused = intArrayOf(android.R.attr.state_focused)
        val hovered = intArrayOf(android.R.attr.state_hovered)
        val textColors = ColorStateList(arrayOf(active, focused, hovered, intArrayOf()), intArrayOf(p.onAccent, p.onAccent, p.onAccent, normal))
        val dimColors = ColorStateList(
            arrayOf(active, focused, hovered, intArrayOf()),
            intArrayOf(withAlpha(p.onAccent, 200), withAlpha(p.onAccent, 200), withAlpha(p.onAccent, 200), p.textTertiary),
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(34)
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(12), ctx.dp(4))
            val r = ctx.dpf(6f)
            background = StateListDrawable().apply {
                addState(active, roundRect(p.accentPressed, r))
                addState(focused, roundRect(p.accent, r))
                addState(hovered, roundRect(p.accent, r))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            isClickable = item.enabled
            isFocusable = item.enabled
            alpha = if (item.enabled) 1f else 0.38f
            contentDescription = item.title
        }
        val check = ImageView(ctx).apply {
            setImageDrawable(GlyphDrawable(Glyph.CHECK, normal, strokeScale = 1.3f).apply { setColors(textColors) })
            visibility = if (item.checked) View.VISIBLE else View.INVISIBLE
            isDuplicateParentStateEnabled = true
        }
        row.addView(check, LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply { marginEnd = ctx.dp(6) })
        if (item.glyph != null) {
            row.addView(
                ImageView(ctx).apply {
                    setImageDrawable(GlyphDrawable(item.glyph, normal).apply { setColors(textColors) })
                    isDuplicateParentStateEnabled = true
                },
                LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)).apply { marginEnd = ctx.dp(8) },
            )
        } else if (item.icon != null) {
            row.addView(
                ImageView(ctx).apply { setImageBitmap(item.icon) },
                LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginEnd = ctx.dp(8) },
            )
        }
        row.addView(
            ctx.label(item.title, 14f, normal).apply {
                setTextColor(textColors)
                isDuplicateParentStateEnabled = true
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (!item.shortcut.isNullOrEmpty()) {
            row.addView(
                ctx.label(item.shortcut, 12f, p.textTertiary).apply {
                    setTextColor(dimColors)
                    isDuplicateParentStateEnabled = true
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ctx.dp(18)
                },
            )
        }
        if (item.enabled) {
            row.setOnClickListener {
                dismiss()
                main.post { item.action() }
            }
        }
        return row
    }
}
