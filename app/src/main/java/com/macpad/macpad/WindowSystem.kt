package com.macpad.macpad

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.PointerIcon
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Base class of MacPad's internal windows (Finder, Settings, ...). Draws the macOS chrome:
 * traffic-light buttons, a title bar that drags the window (double-tap = zoom), an optional
 * toolbar, and a resize grip. Subclasses provide [onCreateContent] and keep their state in fields
 * so [rebuild] (called after a theme change) can recreate the views.
 *
 * Third-party Android apps are never shown inside these windows — Android does not allow
 * embedding other apps' activities; those are launched normally.
 */
abstract class MacWindow(val host: DesktopHost, val windowId: String) : FrameLayout(host.context) {

    /** Windows sharing a key remember one set of bounds (e.g. all Finder windows). */
    open val boundsKey: String get() = windowId.substringBefore(':')
    open val defaultWidthDp: Int get() = 760
    open val defaultHeightDp: Int get() = 500
    open val minWidthDp: Int get() = 360
    open val minHeightDp: Int get() = 240
    open val resizable: Boolean get() = true

    /** Icon used in the Dock (minimized windows) and the Window menu. */
    open val icon: BuiltinIcon get() = BuiltinIcon.FINDER

    /** When false the title is not drawn centred in the title bar (the toolbar shows it). */
    open val showsCenteredTitle: Boolean get() = true

    var title: String = ""
        set(value) {
            field = value
            titleView?.text = value
            contentDescription = value
            host.windows.notifyChanged()
        }

    var isActive = false
        internal set
    var isMinimized = false
        internal set
    var isMaximized = false
        internal set
    internal var restoreBounds: Rect? = null
    /**
     * Bounds the user chose (open, drag, resize, restore). The layer can shrink for a while (soft
     * keyboard, Dock size, rotation); the window is fitted from these, so it grows back after.
     */
    internal var wanted: Rect? = null

    /** Scaled screenshot taken when minimized (shown in the Dock). */
    var snapshot: Bitmap? = null
        internal set

    protected val palette: Palette get() = host.palette

    private var titleView: TextView? = null
    private val lights = mutableListOf<TrafficLight>()
    private var lightsHovered = false

    /** Creates the window content. Called on open and after every [rebuild]. */
    protected abstract fun onCreateContent(): View

    /** Optional toolbar placed in the title bar after the traffic lights (fills the rest). */
    protected open fun onCreateToolbar(): View? = null

    /** Called once when the window is closed; release resources here. */
    open fun onClosed() {}
    open fun onActivated() {}
    open fun onDeactivated() {}

    /** Window-specific keyboard shortcuts (ACTION_DOWN). Return true if handled. */
    open fun handleKey(event: KeyEvent): Boolean = false

    /** Ctrl+F / Ctrl+L: focus this window's search field. Return true if it has one. */
    open fun focusSearchField(): Boolean = false

    /** Called after the window was moved or resized. */
    open fun onBoundsChanged() {}

    fun close() = host.windows.close(this)
    fun minimize() = host.windows.minimize(this)
    fun toggleMaximize() = host.windows.toggleMaximize(this)

    /** Recreates chrome and content with the current palette. */
    fun rebuild() = build()

    internal fun build() {
        removeAllViews()
        lights.clear()
        val p = palette
        val ctx = context
        background = roundRect(p.windowBg, dpf(12f), p.windowStroke, ctx.hairlinePx)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        outlineAmbientShadowColor = Color.BLACK
        outlineSpotShadowColor = Color.BLACK
        isFocusable = false

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = FrameLayout(ctx).apply { background = ColorDrawable(p.windowTitleBar) }
        val tv = ctx.label(title, 14f, p.text, 600).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(420)
        }
        titleView = tv
        if (showsCenteredTitle) {
            bar.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val lightsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (kind in 0..2) {
            val l = TrafficLight(kind)
            lights.add(l)
            lightsBox.addView(l, LinearLayout.LayoutParams(dp(26), dp(30)))
        }
        row.addView(lightsBox)
        onCreateToolbar()?.let { tb ->
            row.addView(tb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(8) })
        }
        bar.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        column.addView(View(ctx).apply { setBackgroundColor(p.separator) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairlinePx))
        column.addView(onCreateContent(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (resizable) {
            addView(ResizeGrip(), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))
        }
        installTitleDrag(bar)
        setActiveVisual(isActive)
    }

    internal fun setActiveVisual(active: Boolean) {
        elevation = if (active) dpf(26f) else dpf(10f)
        titleView?.setTextColor(if (active) palette.text else palette.textTertiary)
        lights.forEach { it.invalidate() }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) host.windows.focus(this)
        return false
    }

    private fun installTitleDrag(bar: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        var lastUpTime = 0L
        var lastUpX = 0f
        var lastUpY = 0f
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = x
                    startY = y
                    dragging = false
                    host.windows.focus(this)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        if (isMaximized) {
                            host.windows.unmaximizeForDrag(this, e.rawX)
                            startX = x
                            startY = y
                            downX = e.rawX
                            downY = e.rawY
                        }
                    }
                    if (dragging) host.windows.moveTo(this, startX + (e.rawX - downX), startY + (e.rawY - downY))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        host.windows.onUserMoved(this)
                    } else {
                        val now = e.eventTime
                        if (now - lastUpTime < 330 && abs(e.rawX - lastUpX) < slop * 2 && abs(e.rawY - lastUpY) < slop * 2) {
                            host.windows.toggleMaximize(this)
                            lastUpTime = 0L
                        } else {
                            lastUpTime = now
                            lastUpX = e.rawX
                            lastUpY = e.rawY
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) host.windows.onUserMoved(this)
                    true
                }
                else -> false
            }
        }
    }

    /** Close / minimize / zoom button. kind: 0 = close, 1 = minimize, 2 = zoom. */
    private inner class TrafficLight(private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val enabledLight = kind != 2 || resizable

        init {
            isClickable = enabledLight
            isFocusable = enabledLight
            val desc = when (kind) {
                0 -> "Закрыть окно"
                1 -> "Свернуть окно"
                else -> "Развернуть окно"
            }
            contentDescription = desc
            tooltipText = desc
            setOnClickListener {
                when (kind) {
                    0 -> close()
                    1 -> minimize()
                    else -> toggleMaximize()
                }
            }
        }

        override fun onHoverChanged(hovered: Boolean) {
            super.onHoverChanged(hovered)
            lightsHovered = lights.any { it.isHovered }
            lights.forEach { it.invalidate() }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val p = palette
            val r = dpf(7f)
            val cx = width / 2f
            val cy = height / 2f
            val lit = enabledLight && (isActive || lightsHovered)
            val base = when (kind) {
                0 -> p.trafficClose
                1 -> p.trafficMinimize
                else -> p.trafficMaximize
            }
            paint.style = Paint.Style.FILL
            paint.color = if (lit) base else p.trafficInactive
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, dpf(0.6f))
            paint.color = Color.argb(40, 0, 0, 0)
            canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
            if (isFocused) {
                paint.color = p.focusRing
                paint.strokeWidth = dpf(2f)
                canvas.drawCircle(cx, cy, r + dpf(2.5f), paint)
            }
            if (enabledLight && (lightsHovered || isPressed || isFocused)) {
                paint.color = Color.argb(150, 60, 20, 0)
                paint.strokeWidth = dpf(1.3f)
                paint.strokeCap = Paint.Cap.ROUND
                val a = dpf(3.2f)
                when (kind) {
                    0 -> {
                        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, paint)
                        canvas.drawLine(cx + a, cy - a, cx - a, cy + a, paint)
                    }
                    1 -> canvas.drawLine(cx - a, cy, cx + a, cy, paint)
                    else -> {
                        paint.style = Paint.Style.FILL
                        val path = Path()
                        path.moveTo(cx - a, cy - a); path.lineTo(cx + a * 0.4f, cy - a); path.lineTo(cx - a, cy + a * 0.4f); path.close()
                        path.moveTo(cx + a, cy + a); path.lineTo(cx - a * 0.4f, cy + a); path.lineTo(cx + a, cy - a * 0.4f); path.close()
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
    }

    /** Bottom-right corner handle that resizes the window. */
    private inner class ResizeGrip : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var downX = 0f
        private var downY = 0f
        private var startW = 0
        private var startH = 0

        init {
            contentDescription = "Изменить размер окна"
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = withAlpha(palette.textTertiary, 110)
            paint.strokeWidth = dpf(1.2f)
            val w = width.toFloat()
            val h = height.toFloat()
            val m = dpf(6f)
            canvas.drawLine(w - m, h - m - dpf(9f), w - m - dpf(9f), h - m, paint)
            canvas.drawLine(w - m, h - m - dpf(4.5f), w - m - dpf(4.5f), h - m, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val win = this@MacWindow
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = win.width
                    startH = win.height
                    host.windows.focus(win)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE ->
                    host.windows.resizeTo(win, (startW + e.rawX - downX).roundToInt(), (startH + e.rawY - downY).roundToInt())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host.windows.onUserMoved(win)
            }
            return true
        }
    }
}

/**
 * Keeps MacPad windows in [layer]: z-order, focus, open/close/minimize/zoom with animations,
 * clamping to the visible area and persisted bounds.
 */
class WindowManager(private val host: DesktopHost, val layer: FrameLayout) {

    private val stack = mutableListOf<MacWindow>()
    private val listeners = mutableListOf<() -> Unit>()
    private var notifying = false
    private var notifyAgain = false

    /** All windows, bottom to top (including minimized ones). */
    val windows: List<MacWindow> get() = stack.toList()

    val visibleWindows: List<MacWindow> get() = stack.filter { !it.isMinimized }

    val activeWindow: MacWindow? get() = stack.lastOrNull { it.isActive && !it.isMinimized }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Tells listeners (menu bar, Dock) that windows/titles/state changed. */
    fun notifyChanged() {
        if (notifying) {
            notifyAgain = true
            return
        }
        notifying = true
        try {
            do {
                notifyAgain = false
                listeners.toList().forEach { it() }
            } while (notifyAgain)
        } finally {
            notifying = false
        }
    }

    fun find(id: String): MacWindow? = stack.firstOrNull { it.windowId == id }

    /** Next free id for a multi-instance window, e.g. "finder:2". */
    fun nextId(prefix: String): String {
        var i = 1
        while (find("$prefix:$i") != null) i++
        return "$prefix:$i"
    }

    private fun layerW(): Int = if (layer.width > 0) layer.width else host.context.resources.displayMetrics.widthPixels
    private fun layerH(): Int = if (layer.height > 0) layer.height else (host.context.resources.displayMetrics.heightPixels * 0.8f).toInt()

    /**
     * Opens the window with [id], creating it with [factory] if it does not exist yet.
     * An existing window is restored/focused instead.
     */
    fun <T : MacWindow> open(id: String, factory: () -> T): T {
        find(id)?.let { existing ->
            @Suppress("UNCHECKED_CAST")
            val w = existing as T
            if (w.isMinimized) restore(w) else focus(w)
            return w
        }
        val w = factory()
        w.build()
        val lw = layerW()
        val lh = layerH()
        val minW = w.dp(w.minWidthDp)
        val minH = w.dp(w.minHeightDp)
        val saved = host.prefs.windowBounds(w.boundsKey)
        val width = (saved?.width() ?: w.dp(w.defaultWidthDp)).coerceIn(min(minW, lw), max(min(minW, lw), lw))
        val height = (saved?.height() ?: w.dp(w.defaultHeightDp)).coerceIn(min(minH, lh), max(min(minH, lh), lh))
        var left = saved?.left ?: ((lw - width) / 2)
        var top = saved?.top ?: ((lh - height) / 3).coerceAtLeast(0)
        val step = w.dp(28)
        var guard = 0
        while (guard++ < 12 && stack.any { !it.isMinimized && abs(it.x - left) < step / 3 && abs(it.y - top) < step / 3 }) {
            left += step
            top += step
        }
        val lp = FrameLayout.LayoutParams(width, height).apply { gravity = Gravity.TOP or Gravity.START }
        layer.addView(w, lp)
        w.x = left.toFloat()
        w.y = top.toFloat()
        clamp(w, width, height)
        w.wanted = boundsOf(w)
        stack.add(w)
        focus(w)
        if (host.animationsEnabled) {
            w.alpha = 0f
            w.scaleX = 0.94f
            w.scaleY = 0.94f
            w.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).setInterpolator(DecelerateInterpolator()).start()
        }
        notifyChanged()
        return w
    }

    fun focus(w: MacWindow) {
        if (!stack.contains(w)) return
        if (w.isMinimized) {
            restore(w)
            return
        }
        var changed = false
        if (stack.last() !== w) {
            stack.remove(w)
            stack.add(w)
            w.bringToFront()
            changed = true
        }
        stack.forEach {
            val a = it === w
            if (it.isActive != a) {
                it.isActive = a
                it.setActiveVisual(a)
                if (a) it.onActivated() else it.onDeactivated()
                changed = true
            }
        }
        if (changed) notifyChanged()
    }

    /** Makes no window active (e.g. the user clicked the desktop or pressed Esc). */
    fun deactivateAll() {
        var changed = false
        stack.forEach {
            if (it.isActive) {
                it.isActive = false
                it.setActiveVisual(false)
                it.onDeactivated()
                changed = true
            }
        }
        if (changed) notifyChanged()
    }

    fun close(w: MacWindow) {
        if (!stack.contains(w)) return
        saveBounds(w)
        stack.remove(w)
        val wasMinimized = w.isMinimized
        w.isActive = false
        try {
            w.onClosed()
        } catch (_: Exception) {
        }
        val done = {
            layer.removeView(w)
            w.snapshot = null
        }
        if (host.animationsEnabled && !wasMinimized) {
            w.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140).withEndAction(done).start()
        } else {
            done()
        }
        stack.lastOrNull { !it.isMinimized }?.let { focus(it) }
        notifyChanged()
    }

    fun closeAll() {
        stack.toList().forEach { close(it) }
    }

    fun minimize(w: MacWindow) {
        if (w.isMinimized || !stack.contains(w)) return
        saveBounds(w)
        w.snapshot = capture(w)
        w.isMinimized = true
        if (w.isActive) {
            w.isActive = false
            w.setActiveVisual(false)
            w.onDeactivated()
        }
        notifyChanged()
        val finish = {
            w.visibility = View.GONE
            w.scaleX = 1f
            w.scaleY = 1f
            w.alpha = 1f
        }
        if (host.animationsEnabled && w.width > 0) {
            setPivotToDock(w)
            w.animate().scaleX(0.06f).scaleY(0.06f).alpha(0f).setDuration(260)
                .setInterpolator(AccelerateInterpolator()).withEndAction(finish).start()
        } else {
            finish()
        }
        stack.lastOrNull { !it.isMinimized }?.let { focus(it) }
    }

    fun restore(w: MacWindow) {
        if (!stack.contains(w)) return
        if (!w.isMinimized) {
            focus(w)
            return
        }
        w.isMinimized = false
        w.visibility = View.VISIBLE
        if (host.animationsEnabled && w.width > 0) {
            setPivotToDock(w)
            w.scaleX = 0.06f
            w.scaleY = 0.06f
            w.alpha = 0f
            w.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        }
        w.snapshot = null
        focus(w)
        notifyChanged()
    }

    private fun setPivotToDock(w: MacWindow) {
        val target = host.dockAnchor(w.windowId)
        val loc = IntArray(2)
        layer.getLocationOnScreen(loc)
        w.pivotX = target.x - loc[0] - w.x
        w.pivotY = target.y - loc[1] - w.y
    }

    fun toggleMaximize(w: MacWindow) {
        if (!w.resizable || !stack.contains(w)) return
        if (w.isMinimized) restore(w)
        if (!w.isMaximized) {
            w.restoreBounds = w.wanted ?: boundsOf(w)
            w.isMaximized = true
            animateBounds(w, Rect(0, 0, layerW(), layerH()))
        } else {
            w.isMaximized = false
            val r = w.restoreBounds ?: defaultRect(w)
            w.wanted = Rect(r)
            animateBounds(w, fitted(r))
            saveBounds(w)
        }
        focus(w)
        notifyChanged()
    }

    fun closeActive(): Boolean {
        val w = activeWindow ?: return false
        close(w)
        return true
    }

    fun minimizeActive(): Boolean {
        val w = activeWindow ?: return false
        minimize(w)
        return true
    }

    /** Ctrl+` : bring the bottom-most visible window to the front. */
    fun cycle(): Boolean {
        val vis = visibleWindows
        if (vis.isEmpty()) return false
        if (vis.size == 1) {
            focus(vis[0])
            return true
        }
        focus(vis.first())
        return true
    }

    fun applyPalette() {
        stack.forEach { it.rebuild() }
    }

    /** Called when the window layer changed size (rotation, IME, Dock size). */
    fun onLayerResized() {
        val lw = layerW()
        val lh = layerH()
        stack.forEach { w ->
            if (w.isMaximized) {
                setBounds(w, Rect(0, 0, lw, lh))
            } else {
                val lp = w.layoutParams ?: return@forEach
                val want = w.wanted ?: boundsOf(w).also { w.wanted = it }
                val nw = min(want.width(), lw)
                val nh = min(want.height(), lh)
                if (nw != lp.width || nh != lp.height) {
                    lp.width = nw
                    lp.height = nh
                    w.layoutParams = lp
                }
                w.x = want.left.toFloat()
                w.y = want.top.toFloat()
                clamp(w, nw, nh)
                w.onBoundsChanged()
            }
        }
    }

    fun boundsOf(w: MacWindow): Rect {
        val lp = w.layoutParams
        val width = if (lp != null && lp.width > 0) lp.width else w.width
        val height = if (lp != null && lp.height > 0) lp.height else w.height
        return Rect(w.x.roundToInt(), w.y.roundToInt(), w.x.roundToInt() + width, w.y.roundToInt() + height)
    }

    fun setBounds(w: MacWindow, r: Rect) {
        val lp = w.layoutParams ?: return
        lp.width = max(1, r.width())
        lp.height = max(1, r.height())
        w.layoutParams = lp
        w.x = r.left.toFloat()
        w.y = r.top.toFloat()
        w.onBoundsChanged()
    }

    internal fun moveTo(w: MacWindow, x: Float, y: Float) {
        val lp = w.layoutParams ?: return
        w.x = x
        w.y = y
        clamp(w, lp.width, lp.height)
        val want = w.wanted
        w.wanted = if (want == null) boundsOf(w) else Rect(w.x.roundToInt(), w.y.roundToInt(), w.x.roundToInt() + want.width(), w.y.roundToInt() + want.height())
    }

    internal fun resizeTo(w: MacWindow, width: Int, height: Int) {
        val lp = w.layoutParams ?: return
        val maxW = max(w.dp(w.minWidthDp), layerW() - w.x.roundToInt())
        val maxH = max(w.dp(w.minHeightDp), layerH() - w.y.roundToInt())
        lp.width = width.coerceIn(min(w.dp(w.minWidthDp), layerW()), maxW)
        lp.height = height.coerceIn(min(w.dp(w.minHeightDp), layerH()), maxH)
        w.layoutParams = lp
        if (w.isMaximized) w.isMaximized = false
        w.wanted = boundsOf(w)
        w.onBoundsChanged()
    }

    internal fun unmaximizeForDrag(w: MacWindow, rawX: Float) {
        val r = w.restoreBounds ?: defaultRect(w)
        val loc = IntArray(2)
        layer.getLocationOnScreen(loc)
        val rel = if (w.width > 0) ((rawX - loc[0] - w.x) / w.width).coerceIn(0f, 1f) else 0.5f
        val newLeft = (rawX - loc[0] - r.width() * rel).roundToInt()
        w.isMaximized = false
        val target = Rect(newLeft, w.y.roundToInt(), newLeft + r.width(), w.y.roundToInt() + r.height())
        w.wanted = Rect(target)
        setBounds(w, fitted(target))
        notifyChanged()
    }

    internal fun onUserMoved(w: MacWindow) {
        saveBounds(w)
        w.onBoundsChanged()
    }

    private fun defaultRect(w: MacWindow): Rect {
        val lw = layerW()
        val lh = layerH()
        val width = min(w.dp(w.defaultWidthDp), lw)
        val height = min(w.dp(w.defaultHeightDp), lh)
        val left = (lw - width) / 2
        val top = ((lh - height) / 3).coerceAtLeast(0)
        return Rect(left, top, left + width, top + height)
    }

    private fun clamp(w: MacWindow, width: Int, height: Int) {
        val lw = layerW()
        val lh = layerH()
        val keep = w.dp(110)
        val minX = -(width - keep).toFloat()
        val maxX = (lw - keep).toFloat()
        w.x = w.x.coerceIn(min(minX, maxX), max(minX, maxX))
        val maxY = max(0f, (lh - w.dp(46)).toFloat())
        w.y = w.y.coerceIn(0f, maxY)
    }

    private fun animateBounds(w: MacWindow, to: Rect) {
        if (!host.animationsEnabled) {
            setBounds(w, to)
            return
        }
        val from = boundsOf(w)
        val cur = Rect()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                cur.set(
                    (from.left + (to.left - from.left) * f).roundToInt(),
                    (from.top + (to.top - from.top) * f).roundToInt(),
                    (from.right + (to.right - from.right) * f).roundToInt(),
                    (from.bottom + (to.bottom - from.bottom) * f).roundToInt(),
                )
                setBounds(w, cur)
            }
            start()
        }
    }

    /** [r] limited to the current layer size (the layer may be shrunk by the soft keyboard). */
    private fun fitted(r: Rect): Rect =
        Rect(r.left, r.top, r.left + min(r.width(), layerW()), r.top + min(r.height(), layerH()))

    private fun saveBounds(w: MacWindow) {
        val r = if (w.isMaximized) w.restoreBounds else (w.wanted ?: boundsOf(w))
        if (r != null && r.width() > 0 && r.height() > 0) host.prefs.setWindowBounds(w.boundsKey, r)
    }

    private fun capture(w: MacWindow): Bitmap? {
        if (w.width <= 0 || w.height <= 0) return null
        return try {
            val scale = min(1f, w.dpf(360f) / w.width)
            val bw = max(1, (w.width * scale).roundToInt())
            val bh = max(1, (w.height * scale).roundToInt())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            val clip = Path().apply {
                addRoundRect(RectF(0f, 0f, w.width.toFloat(), w.height.toFloat()), w.dpf(12f), w.dpf(12f), Path.Direction.CW)
            }
            c.clipPath(clip)
            w.draw(c)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
