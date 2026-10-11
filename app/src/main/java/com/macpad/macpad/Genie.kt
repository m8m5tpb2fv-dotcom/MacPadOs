package com.macpad.macpad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * The macOS "genie" minimize effect: draws a window snapshot warped through a funnel that ends in
 * the Dock icon. [progress] 0 = the window at [from], 1 = fully sucked into [to]. Restoring plays
 * the same mesh backwards. Coordinates are in this view's parent (the full-screen root).
 */
class GenieView(context: Context, private val bitmap: Bitmap, private val from: RectF, private val to: RectF) : View(context) {

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val verts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Animates [progress] to [target]; [onEnd] runs only if the animation was not [stop]ped. */
    fun animateTo(target: Float, durationMs: Long, onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = durationMs.coerceAtLeast(1)
            interpolator = PathInterpolator(0.45f, 0f, 0.25f, 1f)
            addUpdateListener { progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    /** Cancels the animation without running its end action and detaches the view. */
    fun stop() {
        animator?.cancel()
        animator = null
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        // The sides bend into the funnel first, then the content slides down through it.
        val bend = smooth(p / 0.4f)
        val bottomP = smooth(p / 0.7f)
        val topP = smooth((p - 0.3f) / 0.7f)
        val top = lerp(from.top, to.top, topP)
        val bottom = lerp(from.bottom, to.bottom, bottomP)
        val span = (to.top - from.top).let { if (it < 1f) 1f else it }
        var i = 0
        for (row in 0..MESH_H) {
            val y = lerp(top, bottom, row / MESH_H.toFloat())
            // The funnel is fixed on screen: the closer a row is to the Dock, the narrower it is.
            val f = smooth((y - from.top) / span) * bend
            val left = lerp(from.left, to.left, f)
            val right = lerp(from.right, to.right, f)
            for (col in 0..MESH_W) {
                verts[i++] = lerp(left, right, col / MESH_W.toFloat())
                verts[i++] = y
            }
        }
        paint.alpha = (255 * (1f - smooth((p - 0.88f) / 0.12f))).toInt()
        canvas.drawBitmapMesh(bitmap, MESH_W, MESH_H, verts, 0, null, 0, paint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        private const val MESH_W = 8
        private const val MESH_H = 32
    }
}
