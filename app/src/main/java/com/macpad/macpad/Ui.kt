package com.macpad.macpad

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.EnumMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Units & typography
// ---------------------------------------------------------------------------------------------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)

/** Thinnest visible line: half a dp, but never less than one pixel. */
val Context.hairlinePx: Int get() = max(1, (resources.displayMetrics.density * 0.5f).roundToInt())

object Fonts {
    val regular: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    val mono: Typeface = Typeface.MONOSPACE

    fun weight(w: Int): Typeface = when {
        w >= 700 -> bold
        w >= 600 -> semibold
        w >= 500 -> medium
        else -> regular
    }
}

/** A single-line (by default) label with ellipsis and no extra font padding. */
fun Context.label(
    text: CharSequence,
    sizeSp: Float,
    color: Int,
    weight: Int = 400,
    maxLines: Int = 1,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Fonts.weight(weight)
    includeFontPadding = false
    if (maxLines == 1) {
        isSingleLine = true
    } else {
        this.maxLines = maxLines
    }
    ellipsize = TextUtils.TruncateAt.END
}

// ---------------------------------------------------------------------------------------------
// Colours & drawables
// ---------------------------------------------------------------------------------------------

/** Composites [over] (which may be translucent) on top of [base]. */
fun blendOver(base: Int, over: Int): Int {
    if (Color.alpha(base) == 0) return over
    val a = Color.alpha(over) / 255f
    val r = (Color.red(over) * a + Color.red(base) * (1 - a)).roundToInt()
    val g = (Color.green(over) * a + Color.green(base) * (1 - a)).roundToInt()
    val b = (Color.blue(over) * a + Color.blue(base) * (1 - a)).roundToInt()
    return Color.argb(Color.alpha(base), r, g, b)
}

fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).roundToInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).roundToInt(),
    )
}

fun roundRect(
    color: Int,
    radius: Float,
    strokeColor: Int = Color.TRANSPARENT,
    strokeWidth: Int = 0,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
    if (strokeWidth > 0 && Color.alpha(strokeColor) > 0) setStroke(strokeWidth, strokeColor)
}

/** Rounded rectangle with individual corner radii: top-left, top-right, bottom-right, bottom-left. */
fun roundRect(
    color: Int,
    tl: Float, tr: Float, br: Float, bl: Float,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
    setColor(color)
}

/**
 * Background for anything clickable: hover, pressed, selected and a visible keyboard focus ring.
 * The focus ring only appears for keyboard focus because views using this are not
 * focusable in touch mode (unless the caller makes them so).
 */
fun Context.stateBackground(
    p: Palette,
    radius: Float,
    base: Int = Color.TRANSPARENT,
    selected: Int = p.selection,
    stroke: Int = Color.TRANSPARENT,
): StateListDrawable = StateListDrawable().apply {
    val ring = dp(2)
    val line = hairlinePx
    val transparentBase = Color.alpha(base) == 0
    val pressedColor = if (transparentBase) p.pressed else blendOver(base, p.pressed)
    val hoverColor = if (transparentBase) p.hover else blendOver(base, p.hover)
    addState(intArrayOf(android.R.attr.state_pressed), roundRect(pressedColor, radius, stroke, line))
    addState(intArrayOf(android.R.attr.state_selected, android.R.attr.state_focused), roundRect(selected, radius, p.focusRing, ring))
    addState(intArrayOf(android.R.attr.state_activated, android.R.attr.state_focused), roundRect(selected, radius, p.focusRing, ring))
    addState(intArrayOf(android.R.attr.state_selected), roundRect(selected, radius))
    addState(intArrayOf(android.R.attr.state_activated), roundRect(selected, radius))
    addState(intArrayOf(android.R.attr.state_focused), roundRect(hoverColor, radius, p.focusRing, ring))
    addState(intArrayOf(android.R.attr.state_hovered), roundRect(hoverColor, radius, stroke, line))
    addState(intArrayOf(), roundRect(base, radius, stroke, line))
    setEnterFadeDuration(70)
    setExitFadeDuration(140)
}

/** Text colours that turn [selectedColor] when the row is selected/activated (list rows, menu items). */
fun selectableTextColors(normal: Int, selectedColor: Int): ColorStateList = ColorStateList(
    arrayOf(
        intArrayOf(android.R.attr.state_selected),
        intArrayOf(android.R.attr.state_activated),
        intArrayOf(),
    ),
    intArrayOf(selectedColor, selectedColor, normal),
)

/** Subtle scale-down while pressed, implemented as a StateListAnimator so it never steals touches. */
fun View.applyPressScale(scale: Float = 0.94f) {
    val target = this
    val sla = StateListAnimator()
    val down = AnimatorSet().apply {
        playTogether(
            ObjectAnimator.ofFloat(target, View.SCALE_X, scale),
            ObjectAnimator.ofFloat(target, View.SCALE_Y, scale),
        )
        duration = 90
    }
    val up = AnimatorSet().apply {
        playTogether(
            ObjectAnimator.ofFloat(target, View.SCALE_X, 1f),
            ObjectAnimator.ofFloat(target, View.SCALE_Y, 1f),
        )
        duration = 170
    }
    sla.addState(intArrayOf(android.R.attr.state_pressed), down)
    sla.addState(intArrayOf(), up)
    stateListAnimator = sla
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

enum class ButtonStyle { NORMAL, PRIMARY, DESTRUCTIVE, BORDERLESS }

/** macOS-like push button. Touch target is at least 36dp tall. */
fun Context.macButton(
    p: Palette,
    text: String,
    style: ButtonStyle = ButtonStyle.NORMAL,
    onClick: (View) -> Unit,
): TextView {
    val fg = when (style) {
        ButtonStyle.PRIMARY -> p.onAccent
        ButtonStyle.DESTRUCTIVE -> Color.WHITE
        ButtonStyle.BORDERLESS -> p.accent
        ButtonStyle.NORMAL -> p.text
    }
    val base = when (style) {
        ButtonStyle.PRIMARY -> p.accent
        ButtonStyle.DESTRUCTIVE -> p.destructive
        ButtonStyle.BORDERLESS -> Color.TRANSPARENT
        ButtonStyle.NORMAL -> p.controlBg
    }
    return label(text, 14f, fg, 500).apply {
        gravity = Gravity.CENTER
        minHeight = dp(36)
        minWidth = dp(72)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isClickable = true
        isFocusable = true
        background = stateBackground(
            p, dpf(8f), base = base, selected = base,
            stroke = if (style == ButtonStyle.NORMAL) p.controlStroke else Color.TRANSPARENT,
        )
        setOnClickListener(onClick)
        applyPressScale(0.97f)
    }
}

/** Small square icon button (toolbars). */
fun Context.glyphButton(
    p: Palette,
    glyph: Glyph,
    description: String,
    sizeDp: Int = 34,
    onClick: (View) -> Unit,
): android.widget.ImageView = android.widget.ImageView(this).apply {
    setImageDrawable(GlyphDrawable(glyph, p.textSecondary))
    val pad = dp(((sizeDp - 18) / 2).coerceAtLeast(4))
    setPadding(pad, pad, pad, pad)
    contentDescription = description
    tooltipText = description
    isClickable = true
    isFocusable = true
    background = stateBackground(p, dpf(7f))
    setOnClickListener(onClick)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Rounded text field with an accent focus ring. */
fun Context.macTextField(
    p: Palette,
    hint: String,
    sizeSp: Float = 14f,
    leadingGlyph: Glyph? = null,
): EditText = EditText(this).apply {
    this.hint = hint
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(p.text)
    setHintTextColor(p.textTertiary)
    typeface = Fonts.regular
    includeFontPadding = false
    isSingleLine = true
    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    val r = dpf(8f)
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), roundRect(p.controlBg, r, p.focusRing, dp(2)))
        addState(intArrayOf(), roundRect(p.controlBg, r, p.controlStroke, hairlinePx))
    }
    setPadding(dp(10), dp(8), dp(10), dp(8))
    minHeight = dp(36)
    highlightColor = withAlpha(p.accent, 90)
    textCursorDrawable = GradientDrawable().apply {
        setColor(p.accent)
        setSize(dp(2), dp(18))
    }
    if (leadingGlyph != null) {
        val d = GlyphDrawable(leadingGlyph, p.textTertiary)
        val s = dp(16)
        d.setBounds(0, 0, s, s)
        setCompoundDrawablesRelative(d, null, null, null)
        compoundDrawablePadding = dp(6)
    }
}

/** macOS-style toggle switch. Keyboard: Space/Enter toggles (handled by View as a click). */
class MacSwitch(context: Context) : View(context) {

    var palette: Palette = Palette.dark()
        set(v) { field = v; invalidate() }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        private set

    private var knob = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    fun setChecked(checked: Boolean, animate: Boolean = false) {
        anim?.cancel()
        anim = null
        isChecked = checked
        val target = if (checked) 1f else 0f
        if (!animate) {
            knob = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(knob, target).apply {
            duration = 160
            addUpdateListener { knob = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun toggle() {
        setChecked(!isChecked, animate = true)
        onCheckedChange?.invoke(isChecked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(46), dp(28))
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dpf(2f)
        val h = height - pad * 2
        val w = width - pad * 2
        rect.set(pad, pad, pad + w, pad + h)
        val off = if (palette.dark) Color.rgb(78, 78, 84) else Color.rgb(214, 214, 219)
        paint.style = Paint.Style.FILL
        paint.color = lerpColor(off, palette.accent, knob)
        if (!isEnabled) paint.alpha = 100
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpf(2f)
            paint.color = palette.focusRing
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            paint.style = Paint.Style.FILL
        }
        val r = h / 2 - dpf(2f)
        val cx = pad + h / 2 + (w - h) * knob
        val cy = pad + h / 2
        paint.color = Color.WHITE
        paint.setShadowLayer(dpf(1.5f), 0f, dpf(0.8f), Color.argb(70, 0, 0, 0))
        canvas.drawCircle(cx, cy, r, paint)
        paint.clearShadowLayer()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }
}

/** Segmented control (single choice). */
class MacSegmented(
    context: Context,
    private val p: Palette,
    private val options: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {

    var selectedIndex: Int = selected
        private set

    init {
        orientation = HORIZONTAL
        val trackColor = if (p.dark) Color.rgb(48, 48, 52) else Color.rgb(226, 226, 230)
        background = roundRect(trackColor, dpf(9f))
        setPadding(dp(2), dp(2), dp(2), dp(2))
        options.forEachIndexed { i, s ->
            val tv = context.label(s, 13f, p.text, 500).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                minHeight = dp(32)
                isClickable = true
                isFocusable = true
                setOnClickListener { select(i, notify = true) }
            }
            addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        update()
    }

    fun select(index: Int, notify: Boolean = false) {
        if (index !in options.indices) return
        val changed = index != selectedIndex
        selectedIndex = index
        update()
        if (notify && changed) onSelect(index)
    }

    private fun update() {
        val on = if (p.dark) Color.rgb(100, 100, 106) else Color.WHITE
        for (i in 0 until childCount) {
            val c = getChildAt(i) as TextView
            val sel = i == selectedIndex
            c.background = if (sel) {
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), roundRect(on, dpf(7f), p.focusRing, dp(2)))
                    addState(intArrayOf(), roundRect(on, dpf(7f)))
                }
            } else {
                context.stateBackground(p, dpf(7f))
            }
            c.elevation = if (sel) dpf(1f) else 0f
            c.setTextColor(if (sel) p.text else p.textSecondary)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glass (frosted wallpaper) background
// ---------------------------------------------------------------------------------------------

/**
 * Supplies the blurred wallpaper for [GlassDrawable]. Implemented by [WallpaperController].
 */
interface GlassSource {
    /** Heavily blurred, saturated copy of the wallpaper, or null (system wallpaper mode). */
    val blurredWallpaper: Bitmap?

    /** Maps a rectangle in screen coordinates to a source rectangle inside [blurredWallpaper]. */
    fun mapScreenRect(screen: RectF, out: Rect): Boolean

    /** Registers a view whose background is glass, so it is redrawn when the wallpaper changes. */
    fun registerGlassView(view: View)
}

/**
 * Frosted-glass background: draws the part of the blurred wallpaper that lies behind [host],
 * then a translucent tint and a hairline border. Falls back to an opaque-ish tint when no
 * wallpaper bitmap is available. Also provides a rounded outline so elevation shadows work.
 *
 * Note: it blurs the wallpaper only, not windows that may lie between the panel and the
 * wallpaper (Android cannot sample other views' pixels cheaply), so use it for surfaces that
 * sit directly over the desktop (menu bar, Dock, Launchpad) or keep the tint fairly opaque.
 */
class GlassDrawable(
    private val host: View,
    private val source: GlassSource,
    var tintColor: Int,
    var fallbackTint: Int,
    var radius: Float,
    var strokeColor: Int = Color.TRANSPARENT,
    var strokeWidth: Float = 0f,
) : Drawable() {

    private val loc = IntArray(2)
    private val screen = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        source.registerGlassView(host)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        dst.set(b)
        path.reset()
        path.addRoundRect(dst, radius, radius, Path.Direction.CW)
        val bmp = source.blurredWallpaper
        canvas.save()
        canvas.clipPath(path)
        if (bmp != null && !bmp.isRecycled) {
            host.getLocationOnScreen(loc)
            screen.set(
                (loc[0] + b.left).toFloat(),
                (loc[1] + b.top).toFloat(),
                (loc[0] + b.left + b.width() * host.scaleX),
                (loc[1] + b.top + b.height() * host.scaleY),
            )
            if (source.mapScreenRect(screen, src)) canvas.drawBitmap(bmp, src, dst, bmpPaint)
            tintPaint.color = tintColor
        } else {
            tintPaint.color = fallbackTint
        }
        canvas.drawRect(dst, tintPaint)
        canvas.restore()
        if (strokeWidth > 0f && Color.alpha(strokeColor) > 0) {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidth
            val h = strokeWidth / 2
            dst.inset(h, h)
            canvas.drawRoundRect(dst, radius - h, radius - h, strokePaint)
        }
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------------------------------------
// Vector glyphs (consistent line icons, drawn on a 24x24 grid)
// ---------------------------------------------------------------------------------------------

enum class Glyph {
    SEARCH, GEAR, FOLDER, GRID, CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_UP, CHEVRON_DOWN,
    PLUS, MINUS, CLOSE, CHECK, LIST, ICONS, WIFI, WIFI_OFF, BOLT, NOTE, INFO, TRASH, DOC,
    IMAGE, MUSIC, VIDEO, ARCHIVE, APP, KEYBOARD, MONITOR, SUN, MOON, PIN, REFRESH, SHARE,
    HOME, DOWNLOAD, CLOCK, PENCIL, LOGO, SIDEBAR, STAR, LOCK, EXTERNAL, DOTS, CALC, WINDOW,
    DRIVE, ETHERNET, CELLULAR, PDF, CODE, EYE, SORT, PALETTE, DOCK, DESKTOP,
}

private class GlyphShape(val stroke: Path, val fill: Path)

private object GlyphPaths {
    private val cache = EnumMap<Glyph, GlyphShape>(Glyph::class.java)

    @Synchronized
    fun get(g: Glyph): GlyphShape = cache.getOrPut(g) { build(g) }

    private fun build(g: Glyph): GlyphShape {
        val s = Path()
        val f = Path()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) { s.moveTo(x1, y1); s.lineTo(x2, y2) }
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            (if (fill) f else s).addCircle(cx, cy, r, Path.Direction.CW)
        fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, fill: Boolean = false) =
            (if (fill) f else s).addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            s.addArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep)
        fun poly(vararg pts: Float, close: Boolean = false, fill: Boolean = false) {
            val p = if (fill) f else s
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
            if (close) p.close()
        }

        when (g) {
            Glyph.SEARCH -> { circle(10.5f, 10.5f, 6.5f); line(15.3f, 15.3f, 20.5f, 20.5f) }
            Glyph.GEAR -> {
                circle(12f, 12f, 6.4f)
                circle(12f, 12f, 2.6f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 6.4f * cos(a).toFloat(), 12f + 6.4f * sin(a).toFloat(),
                        12f + 9.2f * cos(a).toFloat(), 12f + 9.2f * sin(a).toFloat())
                }
            }
            Glyph.FOLDER -> {
                s.moveTo(3f, 7f); s.quadTo(3f, 5.5f, 4.5f, 5.5f); s.lineTo(9.2f, 5.5f); s.lineTo(11f, 7.5f)
                s.lineTo(19.5f, 7.5f); s.quadTo(21f, 7.5f, 21f, 9f); s.lineTo(21f, 17.5f)
                s.quadTo(21f, 19f, 19.5f, 19f); s.lineTo(4.5f, 19f); s.quadTo(3f, 19f, 3f, 17.5f); s.close()
                line(3f, 10.2f, 21f, 10.2f)
            }
            Glyph.GRID -> {
                for (r in 0..2) for (c in 0..2) {
                    val x = 3.5f + c * 6.5f
                    val y = 3.5f + r * 6.5f
                    rrect(x, y, x + 4f, y + 4f, 1.2f, fill = true)
                }
            }
            Glyph.CHEVRON_LEFT -> poly(15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.CHEVRON_RIGHT -> poly(9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.CHEVRON_UP -> poly(5f, 15f, 12f, 8f, 19f, 15f)
            Glyph.CHEVRON_DOWN -> poly(5f, 9f, 12f, 16f, 19f, 9f)
            Glyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.MINUS -> line(5f, 12f, 19f, 12f)
            Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.CHECK -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Glyph.LIST -> {
                for (y in listOf(7f, 12f, 17f)) { line(8.5f, y, 20f, y); circle(4.8f, y, 1.2f, fill = true) }
            }
            Glyph.ICONS -> {
                rrect(4f, 4f, 10.5f, 10.5f, 1.8f); rrect(13.5f, 4f, 20f, 10.5f, 1.8f)
                rrect(4f, 13.5f, 10.5f, 20f, 1.8f); rrect(13.5f, 13.5f, 20f, 20f, 1.8f)
            }
            Glyph.WIFI, Glyph.WIFI_OFF -> {
                arc(12f, 19f, 4.2f, 225f, 90f)
                arc(12f, 19f, 8.4f, 225f, 90f)
                arc(12f, 19f, 12.6f, 225f, 90f)
                circle(12f, 19f, 1.4f, fill = true)
                if (g == Glyph.WIFI_OFF) line(4f, 4f, 20f, 20f)
            }
            Glyph.BOLT -> poly(13f, 2f, 5f, 13.5f, 11f, 13.5f, 10f, 22f, 19f, 10f, 13f, 10f, close = true, fill = true)
            Glyph.NOTE -> {
                rrect(5f, 3f, 19f, 21f, 2.5f)
                line(8.5f, 8f, 15.5f, 8f); line(8.5f, 12f, 15.5f, 12f); line(8.5f, 16f, 13f, 16f)
            }
            Glyph.INFO -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 17f); circle(12f, 7.6f, 1.2f, fill = true) }
            Glyph.TRASH -> {
                line(4f, 6.5f, 20f, 6.5f)
                s.moveTo(9f, 6.5f); s.lineTo(9f, 4.5f); s.quadTo(9f, 4f, 9.5f, 4f); s.lineTo(14.5f, 4f)
                s.quadTo(15f, 4f, 15f, 4.5f); s.lineTo(15f, 6.5f)
                s.moveTo(6f, 6.5f); s.lineTo(7f, 20f); s.quadTo(7.1f, 21f, 8f, 21f); s.lineTo(16f, 21f)
                s.quadTo(16.9f, 21f, 17f, 20f); s.lineTo(18f, 6.5f)
                line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
            Glyph.DOC, Glyph.PDF, Glyph.CODE -> {
                s.moveTo(6f, 3f); s.lineTo(14f, 3f); s.lineTo(19f, 8f); s.lineTo(19f, 20f)
                s.quadTo(19f, 21f, 18f, 21f); s.lineTo(6f, 21f); s.quadTo(5f, 21f, 5f, 20f)
                s.lineTo(5f, 4f); s.quadTo(5f, 3f, 6f, 3f); s.close()
                poly(14f, 3f, 14f, 8f, 19f, 8f)
                when (g) {
                    Glyph.PDF -> { line(8f, 13f, 16f, 13f); line(8f, 16.5f, 13f, 16.5f) }
                    Glyph.CODE -> { poly(10f, 12f, 8f, 14.5f, 10f, 17f); poly(14f, 12f, 16f, 14.5f, 14f, 17f) }
                    else -> { line(8f, 12f, 16f, 12f); line(8f, 15f, 16f, 15f); line(8f, 18f, 12.5f, 18f) }
                }
            }
            Glyph.IMAGE -> {
                rrect(3f, 5f, 21f, 19f, 2.5f)
                poly(3.5f, 17f, 9f, 11.5f, 13f, 15.5f, 16f, 12.5f, 20.5f, 17f)
                circle(16f, 9f, 1.5f)
            }
            Glyph.MUSIC -> { poly(9f, 18f, 9f, 6f, 19f, 4f, 19f, 16f); circle(7f, 18f, 2f); circle(17f, 16f, 2f) }
            Glyph.VIDEO -> { rrect(3f, 6f, 15.5f, 18f, 2.5f); poly(15.5f, 10.5f, 21f, 7.5f, 21f, 16.5f, 15.5f, 13.5f, close = true) }
            Glyph.ARCHIVE -> {
                rrect(4f, 4f, 20f, 20f, 2.5f)
                line(12f, 4f, 12f, 5.5f); line(12f, 7f, 12f, 8.5f); line(12f, 10f, 12f, 11.5f)
                rrect(10.2f, 12.5f, 13.8f, 16.5f, 1f)
            }
            Glyph.APP -> {
                rrect(4f, 4f, 20f, 20f, 4.5f)
                circle(9.5f, 9.5f, 1.4f, fill = true); circle(14.5f, 9.5f, 1.4f, fill = true)
                circle(9.5f, 14.5f, 1.4f, fill = true); circle(14.5f, 14.5f, 1.4f, fill = true)
            }
            Glyph.KEYBOARD -> {
                rrect(2.5f, 6f, 21.5f, 18f, 2.5f)
                for (x in listOf(6f, 9.3f, 12.6f, 15.9f, 19f)) circle(x - 0.5f, 10f, 0.9f, fill = true)
                line(7f, 14.5f, 17f, 14.5f)
            }
            Glyph.MONITOR -> { rrect(3f, 4f, 21f, 16f, 2f); line(9f, 20f, 15f, 20f); line(12f, 16f, 12f, 20f) }
            Glyph.SUN -> {
                circle(12f, 12f, 4f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 6.6f * cos(a).toFloat(), 12f + 6.6f * sin(a).toFloat(),
                        12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat())
                }
            }
            Glyph.MOON -> {
                val a = Path().apply { addCircle(11f, 13f, 8f, Path.Direction.CW) }
                val b = Path().apply { addCircle(16.5f, 8f, 7f, Path.Direction.CW) }
                a.op(b, Path.Op.DIFFERENCE)
                f.addPath(a)
            }
            Glyph.PIN -> {
                line(12f, 15f, 12f, 21f)
                poly(8f, 3f, 16f, 3f)
                poly(9.5f, 3f, 9.5f, 9f, 6.5f, 12.5f, 6.5f, 15f, 17.5f, 15f, 17.5f, 12.5f, 14.5f, 9f, 14.5f, 3f)
            }
            Glyph.REFRESH -> { arc(12f, 12f, 7.5f, 40f, 285f); poly(18.5f, 3.8f, 18.5f, 8.2f, 14.1f, 8.2f) }
            Glyph.SHARE -> {
                poly(8f, 9f, 6f, 9f, 6f, 20.5f, 18f, 20.5f, 18f, 9f, 16f, 9f)
                line(12f, 3f, 12f, 14.5f)
                poly(8.5f, 6.5f, 12f, 3f, 15.5f, 6.5f)
            }
            Glyph.HOME -> {
                poly(3.5f, 11f, 12f, 4f, 20.5f, 11f)
                poly(6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f)
                poly(10f, 20f, 10f, 14.5f, 14f, 14.5f, 14f, 20f)
            }
            Glyph.DOWNLOAD -> { line(12f, 4f, 12f, 15f); poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); line(5f, 20f, 19f, 20f) }
            Glyph.CLOCK -> { circle(12f, 12f, 9f); poly(12f, 7f, 12f, 12f, 15.5f, 14f) }
            Glyph.PENCIL -> {
                poly(4f, 20f, 5f, 15.5f, 15.5f, 5f)
                s.quadTo(16.5f, 4f, 17.5f, 5f); s.lineTo(19f, 6.5f); s.quadTo(20f, 7.5f, 19f, 8.5f)
                s.lineTo(8.5f, 19f); s.close()
                line(13.5f, 7f, 17f, 10.5f)
            }
            Glyph.LOGO -> {
                rrect(3f, 3f, 21f, 21f, 5.5f)
                poly(7.5f, 16.5f, 7.5f, 8f, 12f, 12.8f, 16.5f, 8f, 16.5f, 16.5f)
            }
            Glyph.SIDEBAR -> { rrect(3f, 5f, 21f, 19f, 2.5f); line(9.5f, 5f, 9.5f, 19f) }
            Glyph.STAR -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9f else 4f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    pts[i * 2] = 12f + r * cos(a).toFloat()
                    pts[i * 2 + 1] = 12.8f + r * sin(a).toFloat()
                }
                poly(*pts, close = true)
            }
            Glyph.LOCK -> {
                rrect(5f, 11f, 19f, 21f, 2.5f)
                s.moveTo(8f, 11f); s.lineTo(8f, 8f); s.quadTo(8f, 4f, 12f, 4f); s.quadTo(16f, 4f, 16f, 8f); s.lineTo(16f, 11f)
            }
            Glyph.EXTERNAL -> {
                poly(14f, 4f, 20f, 4f, 20f, 10f)
                line(20f, 4f, 11f, 13f)
                poly(18f, 14f, 18f, 19f, 17f, 20f, 5f, 20f, 4f, 19f, 4f, 7f, 5f, 6f, 10f, 6f)
            }
            Glyph.DOTS -> { circle(6f, 12f, 1.7f, fill = true); circle(12f, 12f, 1.7f, fill = true); circle(18f, 12f, 1.7f, fill = true) }
            Glyph.CALC -> {
                rrect(5f, 3f, 19f, 21f, 2.5f)
                rrect(8f, 6f, 16f, 9.5f, 1f)
                for (r in 0..2) for (c in 0..2) circle(8.8f + c * 3.2f, 13f + r * 2.8f, 0.9f, fill = true)
            }
            Glyph.WINDOW -> {
                rrect(3f, 4.5f, 21f, 19.5f, 2.5f)
                line(3f, 8.5f, 21f, 8.5f)
                circle(5.8f, 6.5f, 0.8f, fill = true); circle(8.2f, 6.5f, 0.8f, fill = true); circle(10.6f, 6.5f, 0.8f, fill = true)
            }
            Glyph.DRIVE -> { rrect(3f, 8f, 21f, 16f, 2.5f); circle(17f, 12f, 1.1f, fill = true); line(6f, 12f, 12f, 12f) }
            Glyph.ETHERNET -> {
                rrect(4f, 9f, 20f, 19f, 2f)
                poly(9f, 9f, 9f, 6f, 15f, 6f, 15f, 9f)
                line(8f, 15f, 8f, 19f); line(12f, 15f, 12f, 19f); line(16f, 15f, 16f, 19f)
            }
            Glyph.CELLULAR -> {
                rrect(4f, 15f, 6.5f, 20f, 0.8f, fill = true); rrect(8.5f, 12f, 11f, 20f, 0.8f, fill = true)
                rrect(13f, 8.5f, 15.5f, 20f, 0.8f, fill = true); rrect(17.5f, 5f, 20f, 20f, 0.8f, fill = true)
            }
            Glyph.EYE -> {
                s.moveTo(2.5f, 12f); s.quadTo(12f, 2.5f, 21.5f, 12f); s.quadTo(12f, 21.5f, 2.5f, 12f); s.close()
                circle(12f, 12f, 3f)
            }
            Glyph.SORT -> { poly(7f, 4f, 7f, 20f); poly(3.5f, 16.5f, 7f, 20f, 10.5f, 16.5f); poly(17f, 20f, 17f, 4f); poly(13.5f, 7.5f, 17f, 4f, 20.5f, 7.5f) }
            Glyph.PALETTE -> {
                s.moveTo(12f, 3f); s.quadTo(21f, 3f, 21f, 11f); s.quadTo(21f, 15f, 17f, 15f); s.lineTo(15f, 15f)
                s.quadTo(13f, 15f, 13.5f, 17f); s.quadTo(14.5f, 21f, 12f, 21f); s.quadTo(3f, 21f, 3f, 12f); s.quadTo(3f, 3f, 12f, 3f); s.close()
                circle(8f, 11f, 1.3f, fill = true); circle(11f, 7.3f, 1.3f, fill = true); circle(15.5f, 8f, 1.3f, fill = true)
            }
            Glyph.DOCK -> { rrect(3f, 4f, 21f, 20f, 2.5f); rrect(6f, 14.5f, 18f, 17.5f, 1.2f, fill = true) }
            Glyph.DESKTOP -> {
                rrect(3f, 4f, 21f, 20f, 2.5f)
                rrect(15f, 7f, 18f, 10f, 0.8f, fill = true); rrect(15f, 12f, 18f, 15f, 0.8f, fill = true)
            }
        }
        return GlyphShape(s, f)
    }
}

/**
 * Draws a [Glyph]. Colour can be a [ColorStateList] (pass through [setColors]) so the glyph follows
 * its view's state, e.g. white on a selected row.
 */
class GlyphDrawable(
    val glyph: Glyph,
    color: Int,
    private val strokeScale: Float = 1f,
    private val intrinsicPx: Int = -1,
) : Drawable() {

    private var colors: ColorStateList = ColorStateList.valueOf(color)
    private var current = color
    private var alphaValue = 255
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setColor(color: Int) {
        colors = ColorStateList.valueOf(color)
        current = color
        invalidateSelf()
    }

    fun setColors(csl: ColorStateList) {
        colors = csl
        current = csl.getColorForState(state, csl.defaultColor)
        invalidateSelf()
    }

    override fun isStateful(): Boolean = colors.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        val c = colors.getColorForState(state, colors.defaultColor)
        if (c != current) {
            current = c
            invalidateSelf()
            return true
        }
        return false
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val shape = GlyphPaths.get(glyph)
        val side = min(b.width(), b.height()).toFloat()
        canvas.save()
        canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f)
        canvas.scale(side / 24f, side / 24f)
        val a = (Color.alpha(current) * (alphaValue / 255f)).roundToInt()
        strokePaint.color = current
        strokePaint.alpha = a
        strokePaint.strokeWidth = 1.8f * strokeScale
        fillPaint.color = current
        fillPaint.alpha = a
        canvas.drawPath(shape.stroke, strokePaint)
        canvas.drawPath(shape.fill, fillPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = intrinsicPx
    override fun getIntrinsicHeight(): Int = intrinsicPx

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Battery outline with fill level and an optional charging bolt (menu bar status). */
class BatteryDrawable(
    var batteryLevel: Int,
    var charging: Boolean,
    var color: Int,
    var lowColor: Int,
    var chargingColor: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val bolt = Path().apply {
        moveTo(13f, 1.2f); lineTo(8.5f, 7.4f); lineTo(11.8f, 7.4f); lineTo(10.6f, 11.8f)
        lineTo(15.5f, 5.6f); lineTo(12.2f, 5.6f); close()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Viewport 27 x 13
        val s = min(b.width() / 27f, b.height() / 13f)
        canvas.save()
        canvas.translate(b.left + (b.width() - 27f * s) / 2f, b.top + (b.height() - 13f * s) / 2f)
        canvas.scale(s, s)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f
        paint.color = withAlpha(color, 150)
        r.set(0.6f, 0.6f, 23.4f, 12.4f)
        canvas.drawRoundRect(r, 3.4f, 3.4f, paint)
        paint.style = Paint.Style.FILL
        r.set(24.4f, 4.3f, 26f, 8.7f)
        canvas.drawRoundRect(r, 0.8f, 0.8f, paint)
        val lvl = batteryLevel.coerceIn(0, 100)
        paint.color = when {
            charging -> chargingColor
            lvl <= 20 -> lowColor
            else -> color
        }
        r.set(2.2f, 2.2f, 2.2f + 19.6f * (lvl / 100f).coerceAtLeast(0.06f), 10.8f)
        canvas.drawRoundRect(r, 2f, 2f, paint)
        if (charging) {
            paint.color = if (lvl > 45) Color.WHITE else color
            canvas.drawPath(bolt, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------------------------------------
// App-style icons
// ---------------------------------------------------------------------------------------------

/** Icons for MacPad's own Dock items and windows, drawn as macOS-style rounded squares. */
enum class BuiltinIcon(val top: Int, val bottom: Int, val glyph: Glyph, val glyphColor: Int) {
    FINDER(Color.rgb(108, 199, 255), Color.rgb(16, 112, 240), Glyph.FOLDER, Color.WHITE),
    LAUNCHPAD(Color.rgb(176, 176, 190), Color.rgb(92, 92, 108), Glyph.GRID, Color.WHITE),
    SPOTLIGHT(Color.rgb(140, 138, 255), Color.rgb(84, 70, 220), Glyph.SEARCH, Color.WHITE),
    SETTINGS(Color.rgb(168, 168, 176), Color.rgb(98, 98, 106), Glyph.GEAR, Color.WHITE),
    NOTES(Color.rgb(255, 228, 120), Color.rgb(255, 190, 20), Glyph.NOTE, Color.rgb(110, 80, 0)),
    ABOUT(Color.rgb(84, 84, 92), Color.rgb(30, 30, 34), Glyph.MONITOR, Color.WHITE),
    SHORTCUTS(Color.rgb(255, 170, 60), Color.rgb(250, 100, 20), Glyph.KEYBOARD, Color.WHITE),
    APP_INFO(Color.rgb(120, 214, 140), Color.rgb(30, 160, 80), Glyph.INFO, Color.WHITE),
    FILES(Color.rgb(98, 210, 255), Color.rgb(0, 150, 220), Glyph.DOC, Color.WHITE),
}

object IconRenderer {

    /** Rounded-square path used for all icons. */
    fun squircle(rect: RectF): Path {
        val path = Path()
        val rad = rect.width() * 0.2237f
        path.addRoundRect(rect, rad, rad, Path.Direction.CW)
        return path
    }

    private fun bodyRect(size: Int): RectF {
        val inset = size * 0.07f
        return RectF(inset, inset * 0.75f, size - inset, size - inset * 1.25f)
    }

    private fun drawShadowBase(c: Canvas, path: Path, size: Int, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.setShadowLayer(size * 0.035f, 0f, size * 0.018f, Color.argb(95, 0, 0, 0))
        c.drawPath(path, p)
    }

    /** Renders one of MacPad's own icons at [size] px (includes room for a soft shadow). */
    fun renderBuiltin(kind: BuiltinIcon, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = bodyRect(size)
        val path = squircle(body)
        drawShadowBase(c, path, size, kind.bottom)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, body.top, 0f, body.bottom, kind.top, kind.bottom, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = LinearGradient(0f, body.top, 0f, body.centerY(), Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save()
        c.clipPath(path)
        c.drawRect(body.left, body.top, body.right, body.centerY(), p)
        c.restore()
        val g = GlyphDrawable(kind.glyph, kind.glyphColor, strokeScale = 1.15f)
        val gs = (body.width() * 0.56f).roundToInt()
        val gl = (body.centerX() - gs / 2f).roundToInt()
        val gt = (body.centerY() - gs / 2f).roundToInt()
        g.setBounds(gl, gt, gl + gs, gt + gs)
        g.draw(c)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, size / 128f)
            color = Color.argb(40, 0, 0, 0)
        }
        c.drawPath(path, edge)
        return bmp
    }

    /**
     * Renders an installed app's icon into a uniform rounded square. Adaptive icons get their
     * layers masked by our shape; legacy icons are drawn as-is inside the same area.
     */
    fun renderAppIcon(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = bodyRect(size)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            val path = squircle(body)
            drawShadowBase(c, path, size, Color.WHITE)
            c.save()
            c.clipPath(path)
            val extra = body.width() * 0.25f
            val lb = Rect(
                (body.left - extra).roundToInt(), (body.top - extra).roundToInt(),
                (body.right + extra).roundToInt(), (body.bottom + extra).roundToInt(),
            )
            d.background?.let { it.bounds = lb; it.draw(c) }
            d.foreground?.let { it.bounds = lb; it.draw(c) }
            c.restore()
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, size / 128f)
                color = Color.argb(30, 0, 0, 0)
            }
            c.drawPath(path, edge)
        } else {
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight
            val r = RectF(body)
            if (iw > 0 && ih > 0 && iw != ih) {
                val scale = min(body.width() / iw, body.height() / ih)
                val w = iw * scale
                val h = ih * scale
                r.set(body.centerX() - w / 2, body.centerY() - h / 2, body.centerX() + w / 2, body.centerY() + h / 2)
            }
            d.setBounds(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
            d.draw(c)
        }
        return bmp
    }
}
