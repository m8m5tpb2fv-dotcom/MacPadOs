package com.macpad.macpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The macOS-style Dock: a frosted panel centred at the bottom of the desktop.
 *
 * Items, left to right: Finder, Launchpad ("Приложения") and Spotlight ("Поиск"); the pinned apps
 * ([MacPrefs.dockPinned]); a separator, Notes and MacPad settings; and, while windows are
 * minimized, another separator followed by a thumbnail of every minimized window. Finder, Notes
 * and Settings show a running dot while one of their windows is open. Android apps never get one:
 * MacPad cannot reliably know whether another app is running.
 *
 * Interaction: a tap or click activates an item; a long press or right click opens its menu
 * (pinned apps: open, reorder, unpin, desktop shortcut, info; Finder: new window and folders;
 * Settings: sections; minimized windows: restore or close). A long press or right click on an
 * empty part of the panel shows the Dock options. A hovering pointer shows the item's name above
 * it and — when [MacPrefs.dockMagnify] is on and animations are enabled — a mouse magnifies the
 * icons around it without ever re-laying out the Dock.
 *
 * Adaptive width: the Dock is never wider than its parent minus 24dp (MainActivity's margins
 * already exclude the horizontal system insets). Icons shrink down to 36dp; after that the pinned
 * apps that do not fit (and, in extreme cases, minimized windows) move into a "…" item whose menu
 * lists them. The fit depends only on the width and the item counts, so it is recomputed inside
 * the measure pass (rotation) without layout loops.
 *
 * Keyboard (Ctrl+F3 → [focusDock]): Left/Right/Home/End move between items, Enter/Space activate,
 * Up, the Menu key or Shift+F10 open the item's menu, Esc leaves (handled by MainActivity).
 *
 * Contract with [MainActivity]: added to the root with WRAP_CONTENT size, gravity
 * BOTTOM|CENTER_HORIZONTAL and margins for the system insets. The measured size is the panel
 * only; magnified icons, launch bounces and the name label draw above it (nothing clips them).
 */
class Dock(private val host: DesktopHost) : FrameLayout(host.context) {

    private enum class Kind { FINDER, LAUNCHPAD, SPOTLIGHT, APP, OVERFLOW, NOTES, SETTINGS, WINDOW }

    /** One Dock entry. [id] identifies it across rebuilds (keyboard focus is restored by it). */
    private class DockItem(
        val kind: Kind,
        val id: String,
        val title: String,
        val app: AppEntry? = null,
        val window: MacWindow? = null,
    )

    /** How the items fit the available width: icon size and how many pins/windows are visible. */
    private data class Fit(val iconPx: Int, val pinnedShown: Int, val windowsShown: Int, val overflow: Boolean)

    /** Thumbnail of a minimized window, valid while its [source] snapshot and [size] are unchanged. */
    private class Thumb(val source: Bitmap?, val size: Int, val bitmap: Bitmap)

    private val main = Handler(Looper.getMainLooper())

    /** Background work (first-run default pins, folder grants). Its single thread exits when idle. */
    private val io: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "MacPad-Dock").apply { isDaemon = true }
        }
    }

    // ---- Shared paints (before the views, which use them) ------------------------------------

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.regular
        textSize = sp(11f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimFilter = PorterDuffColorFilter(Color.argb(90, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
    private val menuItemPaint by lazy { textPaint(14f, Fonts.regular) }
    private val menuHeaderPaint by lazy { textPaint(12f, Fonts.semibold) }
    private val menuShortcutPaint by lazy { textPaint(12f, Fonts.regular) }

    private val panel = Panel()
    private val nameLabel = NameLabel()

    // ---- Dock prefs (read in refresh()/applyPalette(), never while hovering) -----------------

    private var preferredIconDp = 56
    private var showLabels = false
    private var magnifyPref = true

    // ---- Model of the shown items -----------------------------------------------------------

    private var pinned: List<AppEntry> = emptyList()
    private var minimized: List<MacWindow> = emptyList()
    private var minimizedSignature: List<Any?> = emptyList()
    private var appliedFit: Fit? = null
    private var measuredAvail = -1

    /** Index of the first panel child of the minimized-windows section (its separator). */
    private var minimizedStart = 0
    private var defaultsPending = false

    // ---- Geometry of the applied fit --------------------------------------------------------

    private var iconPx = 0
    private var renderPx = 0
    private var cellW = 0
    private var cellH = 0
    private var labelAscent = 0f

    private val bitmapCache = HashMap<String, Bitmap>()
    private val thumbCache = HashMap<String, Thumb>()

    // ---- Pointer, name label and magnification ----------------------------------------------

    private var mousePressed = false

    /** Pointer x in panel coordinates (hover or mouse drag). */
    private var pointerX = 0f
    private var lastPointerRawX = -1f
    private var hoverOwner: ItemView? = null
    private var labelOwner: ItemView? = null
    private var magStrength = 0f
    private var magTarget = 0f
    private var magAnimator: ValueAnimator? = null
    private var roomLeft = 0
    private var roomRight = 0
    private var baseL = FloatArray(0)
    private var baseW = FloatArray(0)
    private var scales = FloatArray(0)
    private var magL = FloatArray(0)
    private val loc = IntArray(2)

    /** A mouse button press also ends hovering: wait a moment so a click keeps the Dock magnified. */
    private val hoverExitRunnable = Runnable { if (!mousePressed) endHover() }

    init {
        clipChildren = false
        clipToPadding = false
        addView(panel)
        addView(nameLabel)
        readPrefs()
        applyPanelStyle()
        buildItems(inLayout = false)
    }

    // ---- Public API -------------------------------------------------------------------------

    /** Palette or Dock prefs changed: rebuild everything (colours, icon size, labels, magnification). */
    fun applyPalette() {
        readPrefs()
        applyPanelStyle()
        bitmapCache.clear()
        thumbCache.clear()
        buildItems(inLayout = false)
    }

    /** Pins or Dock prefs changed: rebuild the items. */
    fun refresh() {
        readPrefs()
        buildItems(inLayout = false)
    }

    /** The app list changed or finished loading: fills the first-run pins and rebuilds the items. */
    fun onAppsChanged() {
        val apps = host.apps
        val prefs = host.prefs
        if (apps.isLoaded && !prefs.dockPinnedInitialized && !defaultsPending) {
            // defaultDockKeys() asks PackageManager for the default browser: keep it off the UI thread.
            defaultsPending = true
            io.execute {
                val keys = try {
                    apps.defaultDockKeys()
                } catch (_: Exception) {
                    emptyList()
                }
                main.post {
                    defaultsPending = false
                    if (prefs.dockPinnedInitialized) return@post
                    // Keep pins that already exist (e.g. from an older MacPad version).
                    if (prefs.dockPinned.isEmpty()) prefs.dockPinned = keys
                    prefs.dockPinnedInitialized = true
                    buildItems(inLayout = false)
                }
            }
        }
        buildItems(inLayout = false)
    }

    /** Windows opened/closed/minimized: updates the running dots and the minimized section only. */
    fun onWindowsChanged() {
        val now = host.windows.windows.filter { it.isMinimized }
        val signature = signatureOf(now)
        if (signature != minimizedSignature) {
            val fit = computeFit(fallbackAvail(), pinned.size, now.size)
            val current = appliedFit
            if (current == null || fit.iconPx != current.iconPx || fit.pinnedShown != current.pinnedShown ||
                fit.overflow != current.overflow
            ) {
                buildItems(inLayout = false)
                return
            }
            replaceMinimizedSection(now, signature, fit)
        }
        updateRunning()
    }

    /** Screen position (px) a window minimizes into / restores from. */
    fun anchorForWindow(windowId: String?): PointF {
        if (windowId != null) {
            val target = itemViews().firstOrNull { it.item.kind == Kind.WINDOW && it.item.window?.windowId == windowId }
                ?: overflowView()?.takeIf { overflowWindows().any { w -> w.windowId == windowId } }
            if (target != null) {
                if (target.isLaidOut && !target.isLayoutRequested && target.width > 0) {
                    target.getLocationOnScreen(loc)
                    return PointF(loc[0] + target.width * target.scaleX / 2f, loc[1] + iconPx * target.scaleY / 2f)
                }
                // Just added (the window is being minimized right now): predict its slot.
                predictedCenter(target)?.let { return it }
            }
        }
        if (panel.isLaidOut && panel.width > 0) {
            panel.getLocationOnScreen(loc)
            return PointF(
                loc[0] + panel.width - panel.paddingRight - iconPx / 2f,
                loc[1] + panel.paddingTop + iconPx / 2f,
            )
        }
        val dm = resources.displayMetrics
        return PointF(dm.widthPixels / 2f, dm.heightPixels.toFloat())
    }

    /** Ctrl+F3: move keyboard focus to the first Dock item. */
    fun focusDock(): Boolean {
        val first = itemViews().firstOrNull() ?: return false
        if (!first.isShown) return false
        // Leaves touch mode so the focus ring shows even right after a tap.
        first.requestFocusFromTouch()
        return true
    }

    /** Launch feedback for the app with [key]: the macOS double bounce. */
    fun bounce(key: String) {
        if (!host.animationsEnabled) return
        val views = itemViews()
        val v = views.firstOrNull { it.item.kind == Kind.APP && it.item.app?.key == key }
            ?: views.firstOrNull { it.item.kind == Kind.OVERFLOW }?.takeIf { overflowApps().any { e -> e.key == key } }
            ?: return
        startBounce(v)
    }

    // ---- Building ---------------------------------------------------------------------------

    private fun readPrefs() {
        val prefs = host.prefs
        preferredIconDp = prefs.dockIconSizeDp
        showLabels = prefs.dockShowLabels
        magnifyPref = prefs.dockMagnify
        if (!canMagnify()) stopMagnifyNow()
    }

    private fun applyPanelStyle() {
        val p = host.palette
        panel.glass.tintColor = p.glassTint
        panel.glass.fallbackTint = p.glassTintStrong
        panel.glass.strokeColor = p.glassStroke
        panel.glass.strokeWidth = context.hairlinePx.toFloat()
        labelPaint.color = p.text
        nameLabel.applyPalette()
        panel.invalidate()
    }

    /**
     * Recreates all items for the current prefs, pins and windows. [inLayout] = true when called
     * from [onMeasure] (the width changed): views are then swapped without requesting a layout.
     */
    private fun buildItems(inLayout: Boolean, avail: Int = fallbackAvail()) {
        val focusedId = (panel.findFocus() as? ItemView)?.item?.id
        hideLabel()
        hoverOwner = null
        cancelBounces()

        pinned = resolvePinned()
        minimized = host.windows.windows.filter { it.isMinimized }
        minimizedSignature = signatureOf(minimized)
        val fit = computeFit(avail, pinned.size, minimized.size)
        appliedFit = fit
        applyGeometry(fit.iconPx)

        val views = ArrayList<View>()
        views += builtinItem(Kind.FINDER, "Finder", BuiltinIcon.FINDER)
        views += builtinItem(Kind.LAUNCHPAD, "Приложения", BuiltinIcon.LAUNCHPAD)
        views += builtinItem(Kind.SPOTLIGHT, "Поиск", BuiltinIcon.SPOTLIGHT)
        pinned.take(fit.pinnedShown).forEach { e ->
            views += ItemView(DockItem(Kind.APP, "app:${e.key}", e.label, app = e), e.icon)
        }
        if (fit.overflow) {
            val icon = cached("overflow:$renderPx") { renderOverflowIcon(renderPx) }
            views += ItemView(DockItem(Kind.OVERFLOW, "overflow", "Ещё"), icon)
        }
        views += SeparatorView()
        views += builtinItem(Kind.NOTES, "Заметки", BuiltinIcon.NOTES)
        views += builtinItem(Kind.SETTINGS, "Настройки MacPad", BuiltinIcon.SETTINGS)
        minimizedStart = views.size
        views += minimizedSectionViews(fit)

        if (inLayout) {
            panel.replaceAllInLayout(views)
        } else {
            panel.removeAllViews()
            views.forEach { panel.addView(it) }
        }
        pruneThumbs()
        updateRunning()
        restoreFocus(focusedId, inLayout)
    }

    private fun replaceMinimizedSection(now: List<MacWindow>, signature: List<Any?>, fit: Fit) {
        val focusedId = (panel.findFocus() as? ItemView)?.item?.id
        if (labelOwner?.item?.kind == Kind.WINDOW) hideLabel()
        if (hoverOwner?.item?.kind == Kind.WINDOW) hoverOwner = null
        minimized = now
        minimizedSignature = signature
        appliedFit = fit
        val count = panel.childCount - minimizedStart
        if (count > 0) panel.removeViews(minimizedStart, count)
        minimizedSectionViews(fit).forEach { panel.addView(it) }
        pruneThumbs()
        restoreFocus(focusedId, inLayout = false)
    }

    private fun minimizedSectionViews(fit: Fit): List<View> {
        if (fit.windowsShown <= 0) return emptyList()
        val out = ArrayList<View>()
        out += SeparatorView()
        minimized.take(fit.windowsShown).forEach { w ->
            out += ItemView(DockItem(Kind.WINDOW, "win:${w.windowId}", windowTitle(w), window = w), windowThumb(w))
        }
        return out
    }

    private fun builtinItem(kind: Kind, title: String, icon: BuiltinIcon): ItemView =
        ItemView(DockItem(kind, kind.name.lowercase(), title), builtinBitmap(icon, renderPx))

    private fun restoreFocus(id: String?, inLayout: Boolean) {
        if (id == null) return
        val views = itemViews()
        val target = views.firstOrNull { it.item.id == id } ?: views.lastOrNull() ?: return
        if (inLayout) post { if (target.isAttachedToWindow) target.requestFocus() } else target.requestFocus()
    }

    private fun applyGeometry(icon: Int) {
        // Bitmaps are rendered at the magnified size so magnification stays sharp.
        val render = if (canMagnify()) (icon * (1f + MAGNIFY_AMOUNT)).roundToInt() else icon
        if (icon != iconPx || render != renderPx) {
            bitmapCache.clear()
            thumbCache.clear()
        }
        iconPx = icon
        renderPx = render
        cellW = icon + if (showLabels) dp(LABEL_CELL_EXTRA_DP) else 0
        val fm = labelPaint.fontMetricsInt
        labelAscent = -fm.ascent.toFloat()
        cellH = icon + dp(DOT_STRIP_DP) + if (showLabels) dp(LABEL_GAP_DP) + (fm.descent - fm.ascent) else 0
        panel.spacing = dp(SPACING_DP)
        val bottom = dp(if (showLabels) PAD_BOTTOM_LABELS_DP else PAD_BOTTOM_DP)
        if (panel.paddingLeft != dp(PAD_H_DP) || panel.paddingTop != dp(PAD_TOP_DP) || panel.paddingBottom != bottom) {
            panel.setPadding(dp(PAD_H_DP), dp(PAD_TOP_DP), dp(PAD_H_DP), bottom)
        }
        panel.glass.radius = icon * 0.30f + dpf(8f)
        panel.invalidateOutline()
        panel.invalidate()
    }

    private fun resolvePinned(): List<AppEntry> {
        // Missing apps are only left out of the UI; prefs are never purged here.
        val byKey = host.apps.apps.associateBy { it.key }
        return host.prefs.dockPinned.mapNotNull { byKey[it] }
    }

    private fun signatureOf(list: List<MacWindow>): List<Any?> =
        list.map { Triple(it.windowId, it.snapshot, it.title) }

    private fun updateRunning() {
        val ws = host.windows.windows
        val finder = ws.any { it is FinderWindow }
        val settings = ws.any { it is SettingsWindow }
        val notes = ws.any { it is NotesWindow }
        for (v in itemViews()) {
            v.running = when (v.item.kind) {
                Kind.FINDER -> finder
                Kind.SETTINGS -> settings
                Kind.NOTES -> notes
                else -> false
            }
            if (v.item.kind == Kind.OVERFLOW) v.contentDescription = titleOf(v.item)
        }
    }

    private fun itemViews(): List<ItemView> = (0 until panel.childCount).mapNotNull { panel.getChildAt(it) as? ItemView }

    private fun overflowView(): ItemView? = itemViews().firstOrNull { it.item.kind == Kind.OVERFLOW }

    private fun overflowApps(): List<AppEntry> = pinned.drop(appliedFit?.pinnedShown ?: pinned.size)

    private fun overflowWindows(): List<MacWindow> = minimized.drop(appliedFit?.windowsShown ?: minimized.size)

    private fun windowTitle(w: MacWindow): String = w.title.ifBlank { "Окно" }

    private fun titleOf(item: DockItem): String =
        if (item.kind == Kind.OVERFLOW) "Ещё: ${overflowApps().size + overflowWindows().size}" else item.title

    // ---- Fitting the width ------------------------------------------------------------------

    /** Width available to the Dock, from the last measure or from the parent/screen width. */
    private fun fallbackAvail(): Int {
        if (measuredAvail > 0) return measuredAvail
        val parentW = (parent as? View)?.width ?: 0
        val base = if (parentW > 0) parentW else resources.displayMetrics.widthPixels
        val i = host.systemInsets
        return base - i.left - i.right - dp(SIDE_MARGIN_DP)
    }

    /**
     * Largest icon size (up to the preferred one) at which everything fits [avail]; below 36dp the
     * pinned apps that do not fit (then minimized windows) go into the overflow item.
     */
    private fun computeFit(avail: Int, nPinned: Int, nWindows: Int): Fit {
        val minIcon = dp(MIN_ICON_DP)
        val preferred = max(minIcon, dp(preferredIconDp))
        val labelExtra = if (showLabels) dp(LABEL_CELL_EXTRA_DP) else 0
        fun cells(p: Int, w: Int, overflow: Boolean) = FIXED_CELLS + p + w + if (overflow) 1 else 0
        fun seps(w: Int) = 1 + if (w > 0) 1 else 0
        fun fixedWidth(cells: Int, seps: Int) =
            dp(PAD_H_DP) * 2 + cells * labelExtra + seps * dp(SEP_SLOT_DP) + (cells + seps - 1) * dp(SPACING_DP)
        fun fitsWithOverflow(p: Int, w: Int): Boolean {
            val c = cells(p, w, true)
            return fixedWidth(c, seps(w)) + c * minIcon <= avail
        }

        val c = cells(nPinned, nWindows, false)
        val largest = (avail - fixedWidth(c, seps(nWindows))) / c
        if (largest >= minIcon) return Fit(min(preferred, largest), nPinned, nWindows, false)
        for (p in nPinned - 1 downTo 0) if (fitsWithOverflow(p, nWindows)) return Fit(minIcon, p, nWindows, true)
        for (w in nWindows - 1 downTo 0) if (fitsWithOverflow(0, w)) return Fit(minIcon, 0, w, true)
        return Fit(minIcon, 0, 0, nPinned + nWindows > 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = if (mode == MeasureSpec.UNSPECIFIED) {
            fallbackAvail()
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - dp(SIDE_MARGIN_DP)
        }
        measuredAvail = avail
        // The fit depends only on the width and the item counts, so rebuilding here cannot loop.
        if (computeFit(avail, pinned.size, minimized.size) != appliedFit) buildItems(inLayout = true, avail = avail)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        panel.measure(unspecified, unspecified)
        nameLabel.measure(exactly(dp(NAME_MAX_W_DP)), exactly(dp(NAME_H_DP)))
        var w = panel.measuredWidth
        if (mode != MeasureSpec.UNSPECIFIED) w = min(w, max(0, avail))
        setMeasuredDimension(w, panel.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val pw = panel.measuredWidth
        val px = (right - left - pw) / 2
        panel.layout(px, 0, px + pw, panel.measuredHeight)
        nameLabel.layout(0, 0, nameLabel.measuredWidth, nameLabel.measuredHeight)
        // The glass samples the wallpaper behind the panel: redraw it when the Dock moved.
        if (changed) {
            panel.invalidate()
            nameLabel.invalidate()
        }
        if (magStrength > 0f) applyMagnification() else if (labelOwner != null) positionLabel()
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    /** Where [v] will be once laid out, assuming the Dock stays centred (for minimize animations). */
    private fun predictedCenter(v: View): PointF? {
        if (!panel.isLaidOut || panel.width <= 0) return null
        var cursor = panel.paddingLeft
        var center = -1f
        var count = 0
        for (i in 0 until panel.childCount) {
            val c = panel.getChildAt(i)
            val w = if (c is ItemView) cellW else dp(SEP_SLOT_DP)
            if (count > 0) cursor += panel.spacing
            if (c === v) center = cursor + w / 2f
            cursor += w
            count++
        }
        if (center < 0f) return null
        val newWidth = cursor + panel.paddingRight
        panel.getLocationOnScreen(loc)
        val newLeft = loc[0] + (panel.width - newWidth) / 2f
        return PointF(newLeft + center, loc[1] + panel.paddingTop + iconPx / 2f)
    }

    // ---- Pointer: hover, name label, magnification ------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        onPointerHover(event)
        return super.dispatchHoverEvent(event)
    }

    /** The Dock is opaque to hover: windows below it must not react to a pointer over it. */
    override fun onHoverEvent(event: MotionEvent): Boolean = true

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) lastPointerRawX = event.rawX
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastPointerRawX = ev.rawX
        if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mousePressed = true
                    removeCallbacks(hoverExitRunnable)
                }
                MotionEvent.ACTION_MOVE -> if (magStrength > 0f) {
                    pointerX = ev.x - panel.left
                    applyMagnification()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mousePressed = false
                    removeCallbacks(hoverExitRunnable)
                    // A HOVER_ENTER follows if the pointer is still over the Dock and cancels this.
                    postDelayed(hoverExitRunnable, HOVER_EXIT_DELAY_MS)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onPointerHover(e: MotionEvent) {
        lastPointerRawX = e.rawX
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                removeCallbacks(hoverExitRunnable)
                pointerX = e.x - panel.left
                if (isMouse(e) && canMagnify()) {
                    if (magTarget < 1f) {
                        computeRoom()
                        animateMagnify(1f)
                    } else {
                        applyMagnification()
                    }
                }
                updateHoverLabel()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                removeCallbacks(hoverExitRunnable)
                postDelayed(hoverExitRunnable, HOVER_EXIT_DELAY_MS)
            }
        }
    }

    private fun isMouse(e: MotionEvent): Boolean =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || e.isFromSource(InputDevice.SOURCE_MOUSE)

    private fun canMagnify(): Boolean = magnifyPref && host.animationsEnabled

    private fun endHover() {
        hoverOwner = null
        if (magTarget > 0f || magStrength > 0f) animateMagnify(0f)
        val focused = panel.findFocus() as? ItemView
        if (focused != null && !isInTouchMode) showLabelFor(focused) else hideLabel()
    }

    private fun updateHoverLabel() {
        if (showLabels) return
        val target = itemAtPointer()
        if (target != null) {
            hoverOwner = target
            showLabelFor(target)
        } else if (pointerOutsideItems()) {
            // Between two icons the label stays; beyond the first/last icon it goes away.
            hoverOwner = null
            val keep = labelOwner?.let { it.isFocused && !isInTouchMode } == true
            if (!keep) hideLabel()
        }
    }

    private fun visualLeft(c: View) = c.left + c.width / 2f + c.translationX - c.width * c.scaleX / 2f

    private fun visualRight(c: View) = c.left + c.width / 2f + c.translationX + c.width * c.scaleX / 2f

    private fun itemAtPointer(): ItemView? {
        for (i in 0 until panel.childCount) {
            val c = panel.getChildAt(i) as? ItemView ?: continue
            if (pointerX >= visualLeft(c) && pointerX < visualRight(c)) return c
        }
        return null
    }

    private fun pointerOutsideItems(): Boolean {
        val n = panel.childCount
        if (n == 0) return true
        return pointerX < visualLeft(panel.getChildAt(0)) || pointerX >= visualRight(panel.getChildAt(n - 1))
    }

    private fun showLabelFor(v: ItemView) {
        if (showLabels) return
        if (labelOwner !== v) {
            labelOwner = v
            nameLabel.setText(titleOf(v.item))
        }
        positionLabel()
        if (nameLabel.visibility != VISIBLE) {
            nameLabel.animate().cancel()
            // VISIBLE <-> INVISIBLE never requests a layout.
            nameLabel.visibility = VISIBLE
            if (host.animationsEnabled) {
                nameLabel.alpha = 0f
                nameLabel.animate().alpha(1f).setDuration(LABEL_FADE_MS).start()
            } else {
                nameLabel.alpha = 1f
            }
        }
    }

    private fun hideLabel() {
        labelOwner = null
        nameLabel.animate().cancel()
        if (nameLabel.visibility != INVISIBLE) nameLabel.visibility = INVISIBLE
    }

    private fun positionLabel() {
        val v = labelOwner ?: return
        if (v.parent !== panel) {
            hideLabel()
            return
        }
        if (!v.isLaidOut) return
        val cx = panel.left + v.left + v.width / 2f + v.translationX
        // Items scale around their bottom edge.
        val top = panel.top + v.top + v.height * (1f - v.scaleY)
        nameLabel.place(cx, top - dp(NAME_GAP_DP))
    }

    /** Free space left/right of the panel on screen, so magnified icons never leave the screen. */
    private fun computeRoom() {
        panel.getLocationOnScreen(loc)
        val screenW = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val i = host.systemInsets
        val edge = dp(EDGE_DP)
        roomLeft = max(0, loc[0] - i.left - edge)
        roomRight = max(0, screenW - i.right - edge - (loc[0] + panel.width))
    }

    private fun animateMagnify(target: Float) {
        magTarget = target
        magAnimator?.cancel()
        magAnimator = null
        if (magStrength == target) {
            applyMagnification()
            return
        }
        magAnimator = ValueAnimator.ofFloat(magStrength, target).apply {
            duration = if (target > magStrength) MAGNIFY_IN_MS else MAGNIFY_OUT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                magStrength = it.animatedValue as Float
                applyMagnification()
            }
            start()
        }
    }

    private fun stopMagnifyNow() {
        magAnimator?.cancel()
        magAnimator = null
        magTarget = 0f
        if (magStrength != 0f) {
            magStrength = 0f
            resetMagnification()
        }
    }

    /**
     * macOS magnification without relayout: every item gets scale = 1 + 0.55·f(distance) (cosine
     * falloff over 2.5 icons), the magnified row is laid out virtually and shifted so that the
     * point under the pointer stays put, and each child gets the resulting translationX. The glass
     * panel widens by the same amount on each side.
     */
    private fun applyMagnification() {
        val n = panel.childCount
        if (n == 0) return
        val strength = magStrength
        if (strength <= 0.001f) {
            resetMagnification()
            return
        }
        if (baseL.size < n) {
            baseL = FloatArray(n)
            baseW = FloatArray(n)
            scales = FloatArray(n)
            magL = FloatArray(n)
        }
        val amount = MAGNIFY_AMOUNT * strength
        val radius = max(1f, iconPx * FALLOFF_ICONS)
        val x = pointerX
        for (i in 0 until n) {
            val c = panel.getChildAt(i)
            baseL[i] = c.left.toFloat()
            baseW[i] = c.width.toFloat()
            val d = abs(baseL[i] + baseW[i] / 2f - x)
            scales[i] = if (c is ItemView && d < radius) 1f + amount * (1f + cos(PI * d / radius).toFloat()) / 2f else 1f
        }
        var extra = layoutMagnified(n)
        val room = (roomLeft + roomRight).toFloat()
        if (extra > room && extra > 0f) {
            val k = room / extra
            for (i in 0 until n) scales[i] = 1f + (scales[i] - 1f) * k
            extra = layoutMagnified(n)
        }
        val lo = max(0f, extra - roomRight)
        val hi = max(lo, min(extra, roomLeft.toFloat()))
        val leftExtra = (mapToMagnified(x, n, extra) - x).coerceIn(lo, hi)
        for (i in 0 until n) {
            val c = panel.getChildAt(i)
            val s = scales[i]
            if (c is ItemView) {
                c.dropPressScale()
                c.scaleX = s
                c.scaleY = s
            }
            c.translationX = magL[i] + baseW[i] * s / 2f - leftExtra - (baseL[i] + baseW[i] / 2f)
        }
        panel.setExtents(leftExtra.roundToInt(), (extra - leftExtra).roundToInt())
        if (labelOwner != null) positionLabel()
    }

    /** Fills [magL] with the magnified slot positions; returns how much wider the row became. */
    private fun layoutMagnified(n: Int): Float {
        var cursor = baseL[0]
        for (i in 0 until n) {
            magL[i] = cursor
            val gap = if (i < n - 1) baseL[i + 1] - (baseL[i] + baseW[i]) else 0f
            cursor += baseW[i] * scales[i] + gap
        }
        return cursor - (baseL[n - 1] + baseW[n - 1])
    }

    /** Piecewise-linear map from the normal row to the magnified one. */
    private fun mapToMagnified(x: Float, n: Int, extra: Float): Float {
        val end = baseL[n - 1] + baseW[n - 1]
        if (x <= baseL[0]) return x
        if (x >= end) return x + extra
        for (i in 0 until n) {
            val start = baseL[i]
            val stop = if (i < n - 1) baseL[i + 1] else end
            if (x < stop) {
                val magStop = if (i < n - 1) magL[i + 1] else magL[i] + baseW[i] * scales[i]
                val f = if (stop > start) (x - start) / (stop - start) else 0f
                return magL[i] + f * (magStop - magL[i])
            }
        }
        return x + extra
    }

    private fun resetMagnification() {
        for (i in 0 until panel.childCount) {
            val c = panel.getChildAt(i)
            c.translationX = 0f
            if (c is ItemView) {
                c.scaleX = 1f
                c.scaleY = 1f
                c.restorePressScale()
            }
        }
        panel.setExtents(0, 0)
        if (labelOwner != null) positionLabel()
    }

    // ---- Launch bounce ----------------------------------------------------------------------

    private fun startBounce(v: ItemView) {
        v.bounce?.cancel()
        val lift = dpf(BOUNCE_DP)
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = BOUNCE_MS
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            val t = it.animatedFraction
            // Two ballistic arcs: y = 4u(1-u) per bounce.
            val u = if (t >= 1f) 1f else (t * BOUNCES) % 1f
            v.translationY = -lift * 4f * u * (1f - u)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                v.translationY = 0f
                if (v.bounce === animation) v.bounce = null
            }
        })
        v.bounce = anim
        anim.start()
    }

    private fun cancelBounces() {
        for (i in 0 until panel.childCount) (panel.getChildAt(i) as? ItemView)?.bounce?.cancel()
    }

    // ---- Actions & menus --------------------------------------------------------------------

    private fun activate(v: ItemView) {
        val item = v.item
        when (item.kind) {
            Kind.FINDER -> host.openFinder()
            Kind.LAUNCHPAD -> host.toggleLaunchpad()
            Kind.SPOTLIGHT -> host.toggleSpotlight()
            Kind.APP -> item.app?.let { host.launchApp(it, v) }
            Kind.OVERFLOW -> openMenu(v, keyboard = !v.isInTouchMode)
            Kind.NOTES -> host.openNotes()
            Kind.SETTINGS -> host.openSettings()
            Kind.WINDOW -> item.window?.let { w ->
                if (host.windows.windows.contains(w)) host.windows.restore(w) else onWindowsChanged()
            }
        }
    }

    /** Opens the item's menu above it. Returns false for items without a menu. */
    private fun openMenu(v: ItemView, keyboard: Boolean): Boolean {
        val item = v.item
        val entries = when (item.kind) {
            Kind.APP -> item.app?.let { appMenu(v, it) }
            Kind.FINDER -> finderMenu()
            Kind.SETTINGS -> settingsMenu()
            Kind.WINDOW -> item.window?.let { windowMenu(it) }
            Kind.OVERFLOW -> overflowMenu(v)
            Kind.LAUNCHPAD, Kind.SPOTLIGHT, Kind.NOTES -> null
        }
        if (entries.isNullOrEmpty() || !v.isAttachedToWindow) return false
        hideLabel()
        v.getLocationOnScreen(loc)
        val cx = loc[0] + v.width * v.scaleX / 2f
        val itemTop = loc[1]
        panel.getLocationOnScreen(loc)
        v.menuOpen = true
        showMenuAbove(cx, min(itemTop, loc[1]).toFloat(), entries, keyboard) { v.menuOpen = false }
        if (!host.menus.isShowing) v.menuOpen = false
        return true
    }

    private fun appMenu(v: ItemView, entry: AppEntry): List<MenuEntry> {
        val keys = pinned.map { it.key }
        val index = keys.indexOf(entry.key)
        val onDesktop = host.prefs.isOnDesktop(entry.key)
        return listOf(
            MenuEntry.Header(entry.label),
            MenuEntry.Item("Открыть", glyph = Glyph.EXTERNAL) { host.launchApp(entry, v) },
            MenuEntry.Separator,
            MenuEntry.Item("Сдвинуть влево", glyph = Glyph.CHEVRON_LEFT, enabled = index > 0) { movePinned(entry.key, -1) },
            MenuEntry.Item("Сдвинуть вправо", glyph = Glyph.CHEVRON_RIGHT, enabled = index >= 0 && index < keys.size - 1) {
                movePinned(entry.key, 1)
            },
            MenuEntry.Item("Убрать из Dock", glyph = Glyph.PIN) { host.setPinned(entry.key, false) },
            MenuEntry.Item(if (onDesktop) "Убрать с рабочего стола" else "Добавить на рабочий стол", glyph = Glyph.DESKTOP) {
                host.setOnDesktop(entry.key, !onDesktop)
            },
            MenuEntry.Separator,
            MenuEntry.Item("Об этом приложении", glyph = Glyph.INFO) { host.openAppInfo(entry) },
            MenuEntry.Item("Сведения в Android", glyph = Glyph.GEAR) {
                if (!host.apps.openSystemAppInfo(host.activity, entry)) host.toast("Не удалось открыть сведения о приложении")
            },
        )
    }

    /** Swaps a pin with its visible neighbour; pins of missing apps keep their place in prefs. */
    private fun movePinned(key: String, dir: Int) {
        val visible = pinned.map { it.key }
        val i = visible.indexOf(key)
        val j = i + dir
        if (i < 0 || j !in visible.indices) return
        val other = visible[j]
        val list = host.prefs.dockPinned.toMutableList()
        val a = list.indexOf(key)
        val b = list.indexOf(other)
        if (a < 0 || b < 0) return
        list[a] = other
        list[b] = key
        host.prefs.dockPinned = list
        refresh()
    }

    private fun finderMenu(): List<MenuEntry> {
        val out = mutableListOf<MenuEntry>(
            MenuEntry.Item("Новое окно Finder", shortcut = "Ctrl+N", glyph = Glyph.WINDOW) { host.openFinder(newWindow = true) },
            MenuEntry.Item("Добавить папку…", glyph = Glyph.PLUS) { addFinderFolder() },
        )
        val places = host.prefs.finderPlaces
        if (places.isNotEmpty()) {
            out += MenuEntry.Separator
            places.forEach { place ->
                out += MenuEntry.Item(place.name, glyph = Glyph.FOLDER) { host.openFinder(placeUri = place.uri) }
            }
        }
        return out
    }

    /** Folder picker → persisted grant (provider query off the UI thread) → Finder at that folder. */
    private fun addFinderFolder() {
        host.pickFolder { uri ->
            if (uri == null) return@pickFolder
            val appContext = host.context.applicationContext
            val prefs = host.prefs
            io.execute {
                val place = try {
                    FinderPlaces.add(appContext, prefs, uri)
                } catch (_: Exception) {
                    null
                }
                main.post {
                    if (!isAttachedToWindow) return@post
                    if (place != null) host.openFinder(placeUri = place.uri) else host.toast("Не удалось добавить папку")
                }
            }
        }
    }

    private fun settingsMenu(): List<MenuEntry> = listOf(
        MenuEntry.Item("Оформление", glyph = Glyph.PALETTE) { host.openSettings(SettingsWindow.SECTION_APPEARANCE) },
        MenuEntry.Item("Обои", glyph = Glyph.IMAGE) { host.openSettings(SettingsWindow.SECTION_WALLPAPER) },
        MenuEntry.Item("Dock", glyph = Glyph.DOCK) { host.openSettings(SettingsWindow.SECTION_DOCK) },
        MenuEntry.Separator,
        MenuEntry.Item("Системные настройки Android…", glyph = Glyph.GEAR) { host.openSystemSettings() },
    )

    private fun windowMenu(w: MacWindow): List<MenuEntry> = listOf(
        MenuEntry.Header(windowTitle(w)),
        MenuEntry.Item("Развернуть", glyph = Glyph.WINDOW) { host.windows.restore(w) },
        MenuEntry.Item("Закрыть", glyph = Glyph.CLOSE) { host.windows.close(w) },
    )

    private fun overflowMenu(v: ItemView): List<MenuEntry> {
        val out = mutableListOf<MenuEntry>()
        overflowApps().forEach { e -> out += MenuEntry.Item(e.label, icon = e.icon) { host.launchApp(e, v) } }
        val windows = overflowWindows()
        if (windows.isNotEmpty()) {
            if (out.isNotEmpty()) out += MenuEntry.Separator
            val size = dp(MENU_ICON_DP)
            windows.forEach { w ->
                out += MenuEntry.Item(windowTitle(w), icon = builtinBitmap(w.icon, size)) { host.windows.restore(w) }
            }
        }
        return out
    }

    private fun showDockOptions() {
        if (!panel.isAttachedToWindow) return
        val prefs = host.prefs
        val entries = mutableListOf<MenuEntry>()
        if (host.animationsEnabled) {
            entries += MenuEntry.Item("Увеличение при наведении", checked = prefs.dockMagnify) {
                prefs.dockMagnify = !prefs.dockMagnify
                host.onDockChanged()
            }
        }
        entries += MenuEntry.Item("Подписи под значками", checked = prefs.dockShowLabels) {
            prefs.dockShowLabels = !prefs.dockShowLabels
            host.onDockChanged()
        }
        entries += MenuEntry.Separator
        entries += MenuEntry.Item("Настройки Dock…") { host.openSettings(SettingsWindow.SECTION_DOCK) }
        panel.getLocationOnScreen(loc)
        val x = if (lastPointerRawX >= 0f) lastPointerRawX else loc[0] + panel.width / 2f
        hideLabel()
        showMenuAbove(x, loc[1].toFloat(), entries, keyboard = false, onDismiss = null)
    }

    /** Shows a menu centred above ([centerX], [top]) in screen coordinates, like macOS Dock menus. */
    private fun showMenuAbove(centerX: Float, top: Float, entries: List<MenuEntry>, keyboard: Boolean, onDismiss: (() -> Unit)?) {
        val (w, h) = estimateMenuSize(entries)
        val x = (centerX - w / 2f).roundToInt()
        val y = max(host.systemInsets.top + dp(EDGE_DP), (top - dp(MENU_GAP_DP) - h).roundToInt())
        host.menus.showAtScreen(x, y, entries, keyboard = keyboard, onDismiss = onDismiss)
    }

    /** Size of a [MacMenu] with [entries] (mirrors its row metrics), to place it above the Dock. */
    private fun estimateMenuSize(entries: List<MenuEntry>): Pair<Int, Int> {
        val itemLine = lineHeight(menuItemPaint)
        var h = dp(10)
        var w = 0
        for (e in entries) {
            when (e) {
                is MenuEntry.Item -> {
                    h += max(dp(34), itemLine + dp(8))
                    var row = dp(6) + dp(14) + dp(6) + textWidth(menuItemPaint, e.title) + dp(12)
                    if (e.glyph != null) row += dp(25) else if (e.icon != null) row += dp(30)
                    if (!e.shortcut.isNullOrEmpty()) row += dp(18) + textWidth(menuShortcutPaint, e.shortcut)
                    w = max(w, row)
                }
                is MenuEntry.Header -> {
                    h += lineHeight(menuHeaderPaint) + dp(10)
                    w = max(w, dp(22) + textWidth(menuHeaderPaint, e.title))
                }
                MenuEntry.Separator -> h += context.hairlinePx + dp(10)
            }
        }
        val maxH = resources.displayMetrics.heightPixels - dp(12)
        return (w + dp(10)).coerceIn(dp(210), dp(380)) to min(h, maxH)
    }

    // ---- Keyboard helpers -------------------------------------------------------------------

    private fun moveFocus(from: ItemView, dir: Int) {
        val list = itemViews()
        val i = list.indexOf(from)
        if (i < 0) return
        list.getOrNull(i + dir)?.requestFocus()
    }

    private fun onItemFocusChanged(v: ItemView, focused: Boolean) {
        if (focused) {
            if (!v.isInTouchMode) showLabelFor(v)
        } else if (labelOwner === v && hoverOwner !== v) {
            hideLabel()
        }
    }

    // ---- Bitmaps ----------------------------------------------------------------------------

    private inline fun cached(key: String, render: () -> Bitmap): Bitmap = bitmapCache.getOrPut(key, render)

    private fun builtinBitmap(kind: BuiltinIcon, size: Int): Bitmap =
        cached("builtin:${kind.name}:$size") { IconRenderer.renderBuiltin(kind, size) }

    private fun windowThumb(w: MacWindow): Bitmap {
        val snap = w.snapshot?.takeIf { !it.isRecycled }
        val size = renderPx
        thumbCache[w.windowId]?.let { if (it.source === snap && it.size == size) return it.bitmap }
        val bmp = if (snap == null) {
            builtinBitmap(w.icon, size)
        } else {
            try {
                composeThumb(snap, w.icon, size)
            } catch (_: Throwable) {
                builtinBitmap(w.icon, size)
            }
        }
        thumbCache[w.windowId] = Thumb(snap, size, bmp)
        return bmp
    }

    private fun pruneThumbs() {
        val ids = minimized.mapTo(HashSet()) { it.windowId }
        thumbCache.keys.retainAll(ids)
    }

    /** The window's snapshot fitted into the icon square (rounded, soft shadow) with its app badge. */
    private fun composeThumb(snap: Bitmap, kind: BuiltinIcon, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val pad = size * 0.08f
        val box = size - pad * 2
        val scale = min(box / snap.width, box / snap.height)
        val tw = snap.width * scale
        val th = snap.height * scale
        val rect = RectF((size - tw) / 2f, (size - th) / 2f, (size + tw) / 2f, (size + th) / 2f)
        val radius = dpf(THUMB_RADIUS_DP) * size / max(1, iconPx)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = host.palette.windowBg
            setShadowLayer(size * 0.04f, 0f, size * 0.02f, Color.argb(90, 0, 0, 0))
        }
        c.drawRoundRect(rect, radius, radius, base)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        c.drawBitmap(snap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        c.restore()
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, size / 96f)
            color = Color.argb(45, 0, 0, 0)
        }
        val half = edge.strokeWidth / 2f
        rect.inset(half, half)
        c.drawRoundRect(rect, radius - half, radius - half, edge)
        val badge = (size * 0.46f).roundToInt()
        c.drawBitmap(builtinBitmap(kind, badge), (size - badge).toFloat(), (size - badge).toFloat(), null)
        return out
    }

    /** Neutral grey rounded square with "…", in the style of [IconRenderer.renderBuiltin]. */
    private fun renderOverflowIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val inset = size * 0.07f
        val body = RectF(inset, inset * 0.75f, size - inset, size - inset * 1.25f)
        val path = IconRenderer.squircle(body)
        val top = Color.rgb(188, 188, 196)
        val bottom = Color.rgb(122, 122, 132)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = bottom
        p.setShadowLayer(size * 0.035f, 0f, size * 0.018f, Color.argb(95, 0, 0, 0))
        c.drawPath(path, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(0f, body.top, 0f, body.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = LinearGradient(0f, body.top, 0f, body.centerY(), Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.save()
        c.clipPath(path)
        c.drawRect(body.left, body.top, body.right, body.centerY(), p)
        c.restore()
        val g = GlyphDrawable(Glyph.DOTS, Color.WHITE, strokeScale = 1.15f)
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

    // ---- Text helpers -----------------------------------------------------------------------

    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun textPaint(sizeSp: Float, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(sizeSp)
        typeface = face
    }

    private fun lineHeight(p: Paint): Int {
        val fm = p.fontMetricsInt
        return fm.descent - fm.ascent
    }

    private fun textWidth(p: Paint, s: String): Int = ceil(p.measureText(s)).toInt()

    // ---- Lifecycle --------------------------------------------------------------------------

    override fun onDetachedFromWindow() {
        magAnimator?.cancel()
        magAnimator = null
        cancelBounces()
        nameLabel.animate().cancel()
        removeCallbacks(hoverExitRunnable)
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // ---- Views ------------------------------------------------------------------------------

    /**
     * The frosted panel. Lays its children out in one row (fixed slot sizes) and draws the glass
     * itself so magnification can widen it beyond its bounds without a relayout.
     */
    private inner class Panel : ViewGroup(host.context) {

        val glass = GlassDrawable(
            this, host.glass, host.palette.glassTint, host.palette.glassTintStrong, 0f,
            host.palette.glassStroke, context.hairlinePx.toFloat(),
        )
        var spacing = 0
        private var extraLeft = 0
        private var extraRight = 0

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            elevation = dpf(PANEL_ELEVATION_DP)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(-extraLeft, 0, view.width + extraRight, view.height, glass.radius)
                }
            }
            // Empty parts of the panel: Dock options (long press or right click).
            setOnLongClickListener {
                showDockOptions()
                true
            }
            setOnContextClickListener {
                showDockOptions()
                true
            }
        }

        /** Widens the drawn glass (and its shadow) by [left]/[right] px while magnifying. */
        fun setExtents(left: Int, right: Int) {
            if (left == extraLeft && right == extraRight) return
            extraLeft = left
            extraRight = right
            invalidate()
            invalidateOutline()
        }

        /** Swaps all children during a measure pass without requesting another layout. */
        fun replaceAllInLayout(views: List<View>) {
            removeAllViewsInLayout()
            views.forEach { addViewInLayout(it, -1, generateDefaultLayoutParams(), true) }
            forceLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            var w = paddingLeft + paddingRight
            var h = 0
            var count = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(unspecified, unspecified)
                if (count > 0) w += spacing
                w += c.measuredWidth
                h = max(h, c.measuredHeight)
                count++
            }
            setMeasuredDimension(w, paddingTop + h + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var x = paddingLeft
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.layout(x, paddingTop, x + c.measuredWidth, paddingTop + c.measuredHeight)
                x += c.measuredWidth + spacing
            }
        }

        override fun onDraw(canvas: Canvas) {
            glass.setBounds(-extraLeft, 0, width + extraRight, height)
            glass.draw(canvas)
        }

        override fun shouldDelayChildPressedState(): Boolean = false
    }

    /** Thin vertical hairline between the Dock sections. */
    private inner class SeparatorView : View(host.context) {
        private val paint = Paint().apply { color = host.palette.separator }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(SEP_SLOT_DP), iconPx)
        }

        override fun onDraw(canvas: Canvas) {
            val line = max(context.hairlinePx, dp(1)).toFloat()
            val lineH = height * 0.6f
            val x = (width - line) / 2f
            val top = (height - lineH) / 2f
            canvas.drawRect(x, top, x + line, top + lineH, paint)
        }
    }

    /**
     * One Dock item: icon (or window thumbnail), running dot and optional name. Drawn directly so
     * magnification only changes render properties. Scales around its bottom edge.
     */
    private inner class ItemView(val item: DockItem, private val icon: Bitmap) : View(host.context) {

        var running = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        /** True while this item's menu is open: the icon is dimmed like on macOS. */
        var menuOpen = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        var bounce: ValueAnimator? = null

        private val iconRect = RectF()
        private val ringRect = RectF()
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val label: CharSequence? = if (showLabels) {
            TextUtils.ellipsize(item.title, labelPaint, (iconPx + dp(LABEL_TEXT_EXTRA_DP)).toFloat(), TextUtils.TruncateAt.END)
        } else {
            null
        }

        init {
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = item.title
            setOnClickListener { activate(this) }
            // Items without a menu fall through to a normal click after a long press.
            setOnLongClickListener { openMenu(this, keyboard = false) }
            setOnContextClickListener {
                openMenu(this, keyboard = false)
                true
            }
            applyPressScale()
        }

        /** While magnifying, the scale belongs to the magnification (press feedback = dimming). */
        fun dropPressScale() {
            val sla = stateListAnimator ?: return
            sla.jumpToCurrentState()
            stateListAnimator = null
        }

        fun restorePressScale() {
            if (stateListAnimator == null) applyPressScale()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(cellW, cellH)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w / 2f
            pivotY = h.toFloat()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val p = host.palette
            val left = (width - iconPx) / 2f
            iconRect.set(left, 0f, left + iconPx, iconPx.toFloat())
            if (isFocused) {
                // Keyboard focus ring around the icon (items are not focusable in touch mode).
                val r = max(dpf(10f), iconPx * 0.22f)
                ringRect.set(iconRect)
                ringRect.inset(dpf(1f), dpf(1f))
                fillPaint.color = p.hover
                canvas.drawRoundRect(ringRect, r, r, fillPaint)
                ringPaint.color = p.focusRing
                ringPaint.strokeWidth = dpf(2f)
                canvas.drawRoundRect(ringRect, r, r, ringPaint)
            }
            bitmapPaint.colorFilter = if (isPressed || menuOpen) dimFilter else null
            if (!icon.isRecycled) canvas.drawBitmap(icon, null, iconRect, bitmapPaint)
            if (running) {
                fillPaint.color = withAlpha(p.text, 210)
                canvas.drawCircle(width / 2f, iconPx + dp(DOT_STRIP_DP) / 2f, dpf(DOT_RADIUS_DP), fillPaint)
            }
            label?.let {
                val baseline = iconPx + dp(DOT_STRIP_DP) + dp(LABEL_GAP_DP) + labelAscent
                canvas.drawText(it, 0, it.length, width / 2f, baseline, labelPaint)
            }
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            invalidate()
            onItemFocusChanged(this, gainFocus)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return super.onKeyDown(keyCode, event)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(this, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(this, 1)
                KeyEvent.KEYCODE_MOVE_HOME -> itemViews().firstOrNull()?.requestFocus()
                KeyEvent.KEYCODE_MOVE_END -> itemViews().lastOrNull()?.requestFocus()
                // Like macOS: Up opens the item's menu.
                KeyEvent.KEYCODE_DPAD_UP -> if (event.repeatCount == 0) openMenu(this, keyboard = true)
                KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }

        /**
         * The Menu key and Shift+F10 open the item's menu. Handled before the IME stage because
         * MainActivity's global shortcuts see F10 before the focused view does.
         */
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            val menuKey = keyCode == KeyEvent.KEYCODE_MENU || (keyCode == KeyEvent.KEYCODE_F10 && event.isShiftPressed)
            if (!menuKey || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                return super.onKeyPreIme(keyCode, event)
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openMenu(this, keyboard = true)
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
        }
    }

    /**
     * Frosted name pill shown above the hovered (or keyboard-focused) item. Its box has a fixed
     * size and it moves by translation, so showing, renaming and moving it never relayouts.
     */
    private inner class NameLabel : View(host.context) {
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Fonts.medium
            textSize = sp(13f)
        }
        private val radius = dpf(NAME_H_DP / 2f)
        private val glass = GlassDrawable(
            this, host.glass, host.palette.popupTint, host.palette.popupTint, radius,
            host.palette.glassStroke, context.hairlinePx.toFloat(),
        )
        private var text: CharSequence = ""
        private var pillW = 0
        private val screenLoc = IntArray(2)

        init {
            visibility = INVISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // Above the panel's elevation, with a soft shadow.
            elevation = dpf(NAME_ELEVATION_DP)
            outlineAmbientShadowColor = Color.argb(60, 0, 0, 0)
            outlineSpotShadowColor = Color.argb(80, 0, 0, 0)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val l = (view.width - pillW) / 2
                    outline.setRoundRect(l, 0, l + pillW, view.height, radius)
                }
            }
        }

        fun applyPalette() {
            val p = host.palette
            glass.tintColor = p.popupTint
            glass.fallbackTint = withAlpha(p.popupTint, 245)
            glass.strokeColor = p.glassStroke
            textPaint.color = p.text
            invalidate()
        }

        fun setText(value: String) {
            val pad = dp(NAME_PAD_DP)
            val maxW = dp(NAME_MAX_W_DP)
            text = TextUtils.ellipsize(value, textPaint, (maxW - pad * 2).toFloat(), TextUtils.TruncateAt.END)
            pillW = min(maxW, ceil(textPaint.measureText(text, 0, text.length)).toInt() + pad * 2)
            invalidate()
            invalidateOutline()
        }

        /** Centres the pill at [cx] with its bottom at [bottom] (Dock coordinates), kept on screen. */
        fun place(cx: Float, bottom: Float) {
            val boxW = if (width > 0) width else dp(NAME_MAX_W_DP)
            val boxH = if (height > 0) height else dp(NAME_H_DP)
            this@Dock.getLocationOnScreen(screenLoc)
            val screenW = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val i = host.systemInsets
            val edge = dp(EDGE_DP)
            val minLeft = (i.left + edge - screenLoc[0]).toFloat()
            val maxLeft = (screenW - i.right - edge - pillW - screenLoc[0]).toFloat()
            val pillLeft = (cx - pillW / 2f).coerceIn(minLeft, max(minLeft, maxLeft))
            translationX = pillLeft - (boxW - pillW) / 2f
            translationY = bottom - boxH
            // The glass samples the wallpaper behind the pill's new position.
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(NAME_MAX_W_DP), dp(NAME_H_DP))
        }

        override fun onDraw(canvas: Canvas) {
            if (pillW <= 0) return
            val l = (width - pillW) / 2
            glass.setBounds(l, 0, l + pillW, height)
            glass.draw(canvas)
            val fm = textPaint.fontMetrics
            val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, 0, text.length, width / 2f, baseline, textPaint)
        }
    }

    private companion object {
        /** Finder, Launchpad, Spotlight, Notes, Settings. */
        const val FIXED_CELLS = 5
        const val MIN_ICON_DP = 36
        const val SIDE_MARGIN_DP = 24
        const val EDGE_DP = 4
        const val PAD_H_DP = 8
        const val PAD_TOP_DP = 7
        const val PAD_BOTTOM_DP = 2
        const val PAD_BOTTOM_LABELS_DP = 4
        const val SPACING_DP = 6
        const val SEP_SLOT_DP = 9
        const val DOT_STRIP_DP = 5
        const val DOT_RADIUS_DP = 2f
        const val LABEL_GAP_DP = 1
        const val LABEL_CELL_EXTRA_DP = 6
        const val LABEL_TEXT_EXTRA_DP = 10
        const val PANEL_ELEVATION_DP = 14f
        const val THUMB_RADIUS_DP = 6f
        const val MENU_ICON_DP = 44
        const val MENU_GAP_DP = 8
        const val NAME_H_DP = 26
        const val NAME_MAX_W_DP = 280
        const val NAME_PAD_DP = 11
        const val NAME_GAP_DP = 4
        const val NAME_ELEVATION_DP = 16f
        const val LABEL_FADE_MS = 90L
        const val MAGNIFY_AMOUNT = 0.55f
        const val FALLOFF_ICONS = 2.5f
        const val MAGNIFY_IN_MS = 110L
        const val MAGNIFY_OUT_MS = 180L
        const val HOVER_EXIT_DELAY_MS = 120L
        const val BOUNCE_DP = 14f
        const val BOUNCE_MS = 600L
        const val BOUNCES = 2
    }
}
