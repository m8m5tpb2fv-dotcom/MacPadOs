package com.macpad.macpad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Built-in wallpapers, generated procedurally so they are sharp at any resolution. */
object Wallpapers {
    const val SYSTEM = "system"
    const val CUSTOM = "custom"
    const val DEFAULT = "dusk"

    data class Info(val id: String, val title: String)

    val catalog = listOf(
        Info("dusk", "Сумерки"),
        Info("ocean", "Океан"),
        Info("dunes", "Дюны"),
        Info("aurora", "Аврора"),
        Info("graphite", "Графит"),
        Info("dawn", "Рассвет"),
    )

    fun title(id: String): String = when (id) {
        SYSTEM -> "Системные обои"
        CUSTOM -> "Своё изображение"
        else -> catalog.firstOrNull { it.id == id }?.title ?: id
    }

    private class W(val y: Float, val amp: Float, val phase: Float, val freq: Float, val top: Int, val bottom: Int)

    private fun c(hex: Long): Int = (0xFF000000 or hex).toInt()

    /** Renders wallpaper [id] at the given size. Safe to call from a background thread. */
    fun render(id: String, width: Int, height: Int): Bitmap {
        val w = max(1, width)
        val h = max(1, height)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val fw = w.toFloat()
        val fh = h.toFloat()
        when (id) {
            "ocean" -> {
                background(cv, fw, fh, c(0x041A44), c(0x0B4FB3), c(0x2BA8F0))
                glow(cv, fw * 0.2f, fh * 0.18f, fw * 0.55f, Color.argb(110, 120, 230, 255))
                waves(cv, fw, fh, listOf(
                    W(0.50f, 0.07f, 0.5f, 0.8f, c(0x1E6FE0), c(0x0A2F7A)),
                    W(0.62f, 0.06f, 2.1f, 1.2f, c(0x36B6F5), c(0x1255C9)),
                    W(0.74f, 0.07f, 3.3f, 1.0f, c(0x0E3D9E), c(0x061E5C)),
                    W(0.86f, 0.05f, 4.7f, 1.4f, c(0x59D0FF), c(0x1B6FD6)),
                ))
            }
            "dunes" -> {
                background(cv, fw, fh, c(0xFBE3B8), c(0xF7B27A), c(0xE9875A))
                glow(cv, fw * 0.3f, fh * 0.16f, fw * 0.5f, Color.argb(150, 255, 255, 240))
                waves(cv, fw, fh, listOf(
                    W(0.52f, 0.05f, 0.2f, 0.7f, c(0xF2A065), c(0xD9733F)),
                    W(0.63f, 0.06f, 1.9f, 1.0f, c(0xE2814A), c(0xB9542B)),
                    W(0.74f, 0.05f, 3.0f, 0.8f, c(0xC9602F), c(0x933A1C)),
                    W(0.86f, 0.04f, 4.2f, 1.1f, c(0x8C3A1E), c(0x5E2410)),
                ))
            }
            "aurora" -> {
                background(cv, fw, fh, c(0x02061A), c(0x071437), c(0x0B1C3F))
                stars(cv, fw, fh)
                ribbon(cv, fw, fh, 0.30f, 0.08f, 0.4f, Color.argb(150, 40, 255, 170))
                ribbon(cv, fw, fh, 0.40f, 0.10f, 2.0f, Color.argb(120, 60, 160, 255))
                ribbon(cv, fw, fh, 0.24f, 0.07f, 3.4f, Color.argb(110, 180, 90, 255))
                waves(cv, fw, fh, listOf(
                    W(0.82f, 0.04f, 0.9f, 1.2f, c(0x0A1630), c(0x040A18)),
                    W(0.90f, 0.03f, 2.4f, 1.6f, c(0x050C1C), c(0x02050E)),
                ))
            }
            "graphite" -> {
                background(cv, fw, fh, c(0x34343A), c(0x232327), c(0x18181B))
                glow(cv, fw * 0.7f, fh * 0.28f, fw * 0.6f, Color.argb(70, 140, 140, 160))
                waves(cv, fw, fh, listOf(
                    W(0.60f, 0.05f, 0.4f, 0.9f, c(0x3A3A40), c(0x232327)),
                    W(0.72f, 0.06f, 2.2f, 1.1f, c(0x2E2E33), c(0x1B1B1E)),
                    W(0.85f, 0.05f, 3.6f, 1.3f, c(0x242428), c(0x141416)),
                ))
            }
            "dawn" -> {
                background(cv, fw, fh, c(0xDDEBFF), c(0xF4E3F6), c(0xFFE6D5))
                glow(cv, fw * 0.25f, fh * 0.2f, fw * 0.5f, Color.argb(170, 255, 255, 255))
                waves(cv, fw, fh, listOf(
                    W(0.56f, 0.06f, 0.6f, 0.9f, c(0xC9DBFF), c(0xAFC6F5)),
                    W(0.67f, 0.06f, 1.8f, 1.2f, c(0xF6CFE6), c(0xE4B3D6)),
                    W(0.78f, 0.05f, 3.1f, 1.0f, c(0xFFD9C2), c(0xF2B79B)),
                    W(0.89f, 0.04f, 4.4f, 1.3f, c(0xBFD2F7), c(0x9DB6EC)),
                ))
            }
            else -> { // dusk
                background(cv, fw, fh, c(0x1A1446), c(0x4B2A7B), c(0xC2557A))
                glow(cv, fw * 0.75f, fh * 0.25f, fw * 0.6f, Color.argb(120, 255, 140, 120))
                waves(cv, fw, fh, listOf(
                    W(0.55f, 0.06f, 0.3f, 1.1f, c(0x6B3FA0), c(0x2D1B5A)),
                    W(0.66f, 0.07f, 1.6f, 0.9f, c(0xE0708A), c(0x7A2E6E)),
                    W(0.76f, 0.06f, 2.8f, 1.3f, c(0xFF9E7A), c(0xB8456A)),
                    W(0.87f, 0.05f, 4.1f, 1.0f, c(0x3E1F6E), c(0x1A0E36)),
                ))
            }
        }
        return bmp
    }

    private fun background(c: Canvas, w: Float, h: Float, top: Int, mid: Int, bottom: Int) {
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, w * 0.25f, h, intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, y, max(1f, r), color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    private fun waveY(t: Float, base: Float, amp: Float, phase: Float, freq: Float): Float {
        val tau = (Math.PI * 2).toFloat()
        return base + amp * (sin(t * freq * tau + phase) * 0.65f + sin(t * freq * 1.37f * tau + phase * 1.7f) * 0.35f)
    }

    private fun waves(c: Canvas, w: Float, h: Float, list: List<W>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val steps = 140
        for (wv in list) {
            val base = wv.y * h
            val amp = wv.amp * h
            val path = Path()
            path.moveTo(0f, h)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                path.lineTo(w * t, waveY(t, base, amp, wv.phase, wv.freq))
            }
            path.lineTo(w, h)
            path.close()
            p.shader = LinearGradient(0f, base - amp, 0f, h, wv.top, wv.bottom, Shader.TileMode.CLAMP)
            p.setShadowLayer(h * 0.018f, 0f, -h * 0.004f, Color.argb(70, 0, 0, 0))
            c.drawPath(path, p)
        }
    }

    private fun ribbon(c: Canvas, w: Float, h: Float, y: Float, amp: Float, phase: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.09f
            strokeCap = Paint.Cap.ROUND
            this.color = color
            maskFilter = BlurMaskFilter(max(1f, h * 0.06f), BlurMaskFilter.Blur.NORMAL)
        }
        val path = Path()
        val steps = 100
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val yy = waveY(t, y * h, amp * h, phase, 0.8f)
            if (i == 0) path.moveTo(w * t, yy) else path.lineTo(w * t, yy)
        }
        c.drawPath(path, p)
    }

    private fun stars(c: Canvas, w: Float, h: Float) {
        val rnd = java.util.Random(7)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val count = ((w * h) / 9000f).roundToInt().coerceIn(40, 400)
        repeat(count) {
            p.color = Color.argb(60 + rnd.nextInt(150), 255, 255, 255)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, 0.5f + rnd.nextFloat() * 1.2f, p)
        }
    }
}

/** Draws the current wallpaper bitmap with centre-crop scaling and a short cross-fade. */
class WallpaperView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private val drawMatrix = Matrix()
    private val prevMatrix = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var fadeAnim: android.animation.ValueAnimator? = null

    /** Colour shown while nothing is loaded yet. */
    var fallbackColor: Int = Color.rgb(28, 28, 30)
        set(v) { field = v; invalidate() }

    /** When true and no bitmap is set, nothing is drawn so the system wallpaper shows through. */
    var transparentMode = false
        set(v) { field = v; invalidate() }

    val currentBitmap: Bitmap? get() = bitmap

    fun setWallpaper(bmp: Bitmap?, animate: Boolean) {
        fadeAnim?.cancel()
        previous = if (animate) bitmap else null
        bitmap = bmp
        computeMatrix(bmp, drawMatrix)
        drawMatrix.invert(inverse)
        previous?.let { computeMatrix(it, prevMatrix) }
        if (animate && previous != null && bmp != null) {
            fade = 0f
            fadeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        previous = null
                        invalidate()
                    }
                })
                start()
            }
        } else {
            fade = 1f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeMatrix(bitmap, drawMatrix)
        drawMatrix.invert(inverse)
        previous?.let { computeMatrix(it, prevMatrix) }
    }

    private fun computeMatrix(b: Bitmap?, m: Matrix) {
        m.reset()
        if (b == null || width == 0 || height == 0) return
        val scale = max(width / b.width.toFloat(), height / b.height.toFloat())
        val dx = (width - b.width * scale) / 2f
        val dy = (height - b.height * scale) / 2f
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
    }

    /** Maps a rect in this view's coordinates into bitmap pixel coordinates. */
    fun mapViewRectToBitmap(r: RectF): Boolean {
        if (bitmap == null) return false
        inverse.mapRect(r)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null) {
            if (!transparentMode) canvas.drawColor(fallbackColor)
            return
        }
        val prev = previous
        if (prev != null && fade < 1f) {
            paint.alpha = 255
            canvas.drawBitmap(prev, prevMatrix, paint)
            paint.alpha = (fade * 255).roundToInt()
            canvas.drawBitmap(b, drawMatrix, paint)
            paint.alpha = 255
        } else {
            canvas.drawBitmap(b, drawMatrix, paint)
        }
    }
}

/**
 * Loads/generates the wallpaper off the main thread, shows it in [view], and serves the blurred
 * copy used by [GlassDrawable]s. In system-wallpaper mode it makes the window show the launcher
 * wallpaper (FLAG_SHOW_WALLPAPER) instead.
 */
class WallpaperController(
    private val activity: Activity,
    private val view: WallpaperView,
    private val prefs: MacPrefs,
) : GlassSource {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val glassViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val loc = IntArray(2)
    private val tmp = RectF()
    private var appliedKey: String? = null
    private var renderedW = 0
    private var renderedH = 0
    private var token = 0
    private val resizeRunnable = Runnable { rerenderIfNeeded() }

    @Volatile
    override var blurredWallpaper: Bitmap? = null
        private set

    /** Called on the main thread with a user-facing message when a wallpaper cannot be loaded. */
    var onError: ((String) -> Unit)? = null

    var usesSystemWallpaper = false
        private set

    init {
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                main.removeCallbacks(resizeRunnable)
                main.postDelayed(resizeRunnable, 250)
            }
        }
    }

    /** Applies the wallpaper chosen in prefs (no-op if it is already shown, unless [force]). */
    fun apply(force: Boolean = false) {
        val id = prefs.wallpaper
        val key = id + "|" + (if (id == Wallpapers.CUSTOM) prefs.customWallpaperUri.orEmpty() else "")
        if (!force && key == appliedKey) return
        appliedKey = key
        if (id == Wallpapers.SYSTEM) {
            token++
            setSystemMode(true)
            view.setWallpaper(null, animate = false)
            blurredWallpaper = null
            invalidateGlass()
            return
        }
        setSystemMode(false)
        load(id)
    }

    /** Stores a user-picked image as the wallpaper (taking a persistable read permission). */
    fun setCustom(uri: Uri) {
        val old = prefs.customWallpaperUri
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not persistable (e.g. a one-off grant); it still works for this session.
        }
        if (old != null && old != uri.toString()) {
            try {
                activity.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        prefs.customWallpaperUri = uri.toString()
        prefs.wallpaper = Wallpapers.CUSTOM
        apply(force = true)
    }

    fun shutdown() {
        main.removeCallbacks(resizeRunnable)
        executor.shutdownNow()
    }

    private fun targetSize(): Pair<Int, Int> {
        val dm = activity.resources.displayMetrics
        val w = if (view.width > 0) view.width else dm.widthPixels
        val h = if (view.height > 0) view.height else dm.heightPixels
        return max(1, w / 2) to max(1, h / 2)
    }

    private fun rerenderIfNeeded() {
        val id = prefs.wallpaper
        if (id == Wallpapers.SYSTEM || id == Wallpapers.CUSTOM) return
        val (tw, th) = targetSize()
        if (tw != renderedW || th != renderedH) load(id)
    }

    private fun load(id: String) {
        val my = ++token
        val (tw, th) = targetSize()
        val uriStr = prefs.customWallpaperUri
        executor.execute {
            var error: String? = null
            val bmp: Bitmap? = try {
                if (id == Wallpapers.CUSTOM) decodeCustom(uriStr) else Wallpapers.render(id, tw, th)
            } catch (e: Exception) {
                error = "Не удалось открыть выбранное изображение. Выбраны стандартные обои."
                null
            } catch (e: OutOfMemoryError) {
                error = "Изображение слишком большое. Выбраны стандартные обои."
                null
            }
            val finalBmp = bmp ?: Wallpapers.render(Wallpapers.DEFAULT, tw, th)
            val blurred = makeBlurred(finalBmp)
            main.post {
                if (my != token) return@post
                renderedW = tw
                renderedH = th
                view.setWallpaper(finalBmp, animate = true)
                blurredWallpaper = blurred
                invalidateGlass()
                if (error != null) {
                    prefs.wallpaper = Wallpapers.DEFAULT
                    appliedKey = Wallpapers.DEFAULT + "|"
                    onError?.invoke(error!!)
                }
            }
        }
    }

    private fun decodeCustom(uriStr: String?): Bitmap {
        if (uriStr.isNullOrEmpty()) throw IOException("No wallpaper selected")
        val src = ImageDecoder.createSource(activity.contentResolver, Uri.parse(uriStr))
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = info.size
            val m = max(s.width, s.height)
            val maxDim = 2048
            if (m > maxDim) {
                val f = maxDim / m.toFloat()
                decoder.setTargetSize(max(1, (s.width * f).toInt()), max(1, (s.height * f).toInt()))
            }
        }
    }

    private fun setSystemMode(on: Boolean) {
        val w = activity.window
        if (on) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        view.transparentMode = on
        usesSystemWallpaper = on
    }

    private fun invalidateGlass() {
        glassViews.toList().forEach { it.invalidate() }
    }

    override fun registerGlassView(view: View) {
        glassViews.add(view)
    }

    override fun mapScreenRect(screen: RectF, out: Rect): Boolean {
        val blurred = blurredWallpaper ?: return false
        val src = view.currentBitmap ?: return false
        view.getLocationOnScreen(loc)
        tmp.set(screen)
        tmp.offset(-loc[0].toFloat(), -loc[1].toFloat())
        if (!view.mapViewRectToBitmap(tmp)) return false
        val sx = blurred.width / src.width.toFloat()
        val sy = blurred.height / src.height.toFloat()
        out.set(
            (tmp.left * sx).toInt(),
            (tmp.top * sy).toInt(),
            ceil(tmp.right * sx).toInt(),
            ceil(tmp.bottom * sy).toInt(),
        )
        if (!out.intersect(0, 0, blurred.width, blurred.height)) return false
        if (out.width() < 1) out.right = (out.left + 1).coerceAtMost(blurred.width)
        if (out.height() < 1) out.bottom = (out.top + 1).coerceAtMost(blurred.height)
        return !out.isEmpty
    }

    companion object {
        /** Produces a small, heavily blurred and slightly saturated copy for glass panels. */
        fun makeBlurred(src: Bitmap): Bitmap {
            val mid = if (src.width > 400) {
                Bitmap.createScaledBitmap(src, 384, max(1, (src.height * 384f / src.width).roundToInt()), true)
            } else src
            val tw = 96
            val th = max(1, (mid.height * tw / mid.width.toFloat()).roundToInt())
            val small = Bitmap.createScaledBitmap(mid, tw, th, true)
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
            Canvas(out).drawBitmap(small, 0f, 0f, paint)
            if (mid !== src) mid.recycle()
            if (small !== src && small !== mid) small.recycle()
            boxBlur(out, 3, 3)
            return out
        }

        private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
            val w = bmp.width
            val h = bmp.height
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            bmp.getPixels(a, 0, w, 0, 0, w, h)
            repeat(passes) {
                blurPass(a, b, w, h, radius, horizontal = true)
                blurPass(b, a, w, h, radius, horizontal = false)
            }
            bmp.setPixels(a, 0, w, 0, 0, w, h)
        }

        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val div = 2 * r + 1
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) {
                fun idx(i: Int): Int {
                    val c = i.coerceIn(0, len - 1)
                    return if (horizontal) line * w + c else c * w + line
                }
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val p = src[idx(i)]
                    sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                }
                for (i in 0 until len) {
                    dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val out = src[idx(i - r)]
                    val inn = src[idx(i + r + 1)]
                    sa += (inn ushr 24) - (out ushr 24)
                    sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    sb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
    }
}
