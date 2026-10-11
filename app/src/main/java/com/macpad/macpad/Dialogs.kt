package com.macpad.macpad

import android.content.Context
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** A button in a [DialogLayer] dialog. [action] returns true to close the dialog. */
data class DialogButton(
    val text: String,
    val style: ButtonStyle = ButtonStyle.NORMAL,
    val isDefault: Boolean = false,
    val isCancel: Boolean = false,
    val action: () -> Boolean = { true },
)

/**
 * Modal dialogs drawn inside the desktop (so they match the MacPad look): a dimmed scrim and a
 * centred frosted panel. Keyboard: Enter = default button, Esc = cancel button.
 */
class DialogLayer(private val host: DesktopHost, private val root: FrameLayout) {

    private class Shown(val overlay: View, val buttons: List<DialogButton>)

    /** Scrim that keeps keyboard focus inside the dialog, so keys never reach the desktop behind. */
    private class ModalFrame(context: Context) : FrameLayout(context) {
        override fun focusSearch(focused: View?, direction: Int): View? =
            FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    private val stack = mutableListOf<Shown>()

    val isShowing: Boolean get() = stack.isNotEmpty()

    /** Space taken by the on-screen keyboard; dialogs are centred above it. */
    var bottomInset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            stack.forEach { it.overlay.setPadding(0, 0, 0, value) }
        }

    /**
     * Shows a dialog. [content] (optional) is placed between the message and the buttons.
     * [onShown] receives the panel after it is attached (e.g. to focus a field).
     */
    fun show(
        title: String,
        message: String?,
        content: View? = null,
        buttons: List<DialogButton> = listOf(DialogButton("OK", ButtonStyle.PRIMARY, isDefault = true)),
        icon: BuiltinIcon? = null,
        onShown: ((View) -> Unit)? = null,
    ) {
        val ctx = host.context
        val p = host.palette
        val overlay = ModalFrame(ctx).apply {
            setBackgroundColor(p.dimOverlay)
            isClickable = true
            isFocusable = false
            setPadding(0, 0, 0, bottomInset)
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(22), ctx.dp(20), ctx.dp(22), ctx.dp(18))
            background = GlassDrawable(this, host.glass, p.popupTint, withAlpha(p.popupTint, 248), ctx.dpf(14f), p.glassStroke, ctx.hairlinePx.toFloat())
            elevation = ctx.dpf(28f)
            isClickable = true
        }
        if (icon != null) {
            panel.addView(
                ImageView(ctx).apply { setImageBitmap(IconRenderer.renderBuiltin(icon, ctx.dp(64))) },
                LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply { bottomMargin = ctx.dp(10) },
            )
        }
        panel.addView(
            ctx.label(title, 16f, p.text, 700, maxLines = 3).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (!message.isNullOrEmpty()) {
            panel.addView(
                ctx.label(message, 13.5f, p.textSecondary, maxLines = 12).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.15f)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(8)
                },
            )
        }
        if (content != null) {
            panel.addView(
                content,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(14)
                },
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val shown = Shown(overlay, buttons)
        val buttonViews = ArrayList<View>()
        buttons.forEachIndexed { i, b ->
            val btn = ctx.macButton(p, b.text, b.style) {
                // Ignore clicks while the dialog fades out (a repeated Enter on the focused button).
                if (stack.contains(shown) && b.action()) dismiss(shown)
            }
            buttonViews += btn
            row.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = ctx.dp(10)
            })
        }
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(18)
        })

        val width = min(ctx.dp(440), (root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) - ctx.dp(32))
        overlay.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.elevation = ctx.dpf(40f)
        stack.add(shown)
        if (host.animationsEnabled) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(120).start()
            panel.scaleX = 0.92f
            panel.scaleY = 0.92f
            panel.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        if (onShown != null) {
            onShown(panel)
        } else {
            // Move focus off the desktop (a list, an editor) into the dialog. From touch mode a
            // plain requestFocus() would fail for a button.
            val i = buttons.indexOfFirst { it.isDefault }.coerceAtLeast(0)
            buttonViews.getOrNull(i)?.requestFocusFromTouch()
        }
    }

    fun alert(title: String, message: String?) {
        show(title, message, buttons = listOf(DialogButton("OK", ButtonStyle.PRIMARY, isDefault = true, isCancel = true)))
    }

    fun confirm(
        title: String,
        message: String?,
        confirmText: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        show(
            title, message,
            buttons = listOf(
                DialogButton("Отмена", isCancel = true),
                DialogButton(
                    confirmText,
                    if (destructive) ButtonStyle.DESTRUCTIVE else ButtonStyle.PRIMARY,
                    isDefault = true,
                ) { onConfirm(); true },
            ),
        )
    }

    /** Asks for a line of text. [onResult] is only called with a non-blank value. */
    fun prompt(
        title: String,
        message: String?,
        initial: String,
        confirmText: String,
        hint: String = "",
        onResult: (String) -> Unit,
    ) {
        val ctx = host.context
        val field: EditText = ctx.macTextField(host.palette, hint, 15f).apply {
            setText(initial)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        lateinit var submit: () -> Boolean
        submit = {
            val v = field.text.toString().trim()
            if (v.isEmpty()) {
                false
            } else {
                hideKeyboard(field)
                onResult(v)
                true
            }
        }
        show(
            title, message, content = field,
            buttons = listOf(
                DialogButton("Отмена", isCancel = true) { hideKeyboard(field); true },
                DialogButton(confirmText, ButtonStyle.PRIMARY, isDefault = true) { submit() },
            ),
            onShown = {
                field.requestFocus()
                // Select the name without the extension, like Finder does when renaming.
                val dot = initial.lastIndexOf('.')
                if (dot > 0) field.setSelection(0, dot) else field.selectAll()
                field.post {
                    host.context.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            },
        )
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                stack.lastOrNull()?.let { s -> if (submit()) dismiss(s) }
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard(v: View) {
        host.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun dismiss(s: Shown) {
        if (!stack.remove(s)) return
        val v = s.overlay
        (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        v.findFocus()?.clearFocus()
        // A dialog shown from another dialog: give focus back to the one below.
        stack.lastOrNull()?.overlay?.requestFocus()
        if (host.animationsEnabled) {
            v.animate().alpha(0f).setDuration(110).withEndAction { root.removeView(v) }.start()
        } else {
            root.removeView(v)
        }
    }

    /** Closes the top dialog as if Cancel was pressed. */
    fun dismissTop(): Boolean {
        val s = stack.lastOrNull() ?: return false
        val cancel = s.buttons.firstOrNull { it.isCancel }
        if (cancel == null || cancel.action()) dismiss(s)
        return true
    }

    /** Keyboard handling while a dialog is open. Returns true if the event was consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        val s = stack.lastOrNull() ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> dismissTop()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val focused = s.overlay.findFocus()
                if (focused is TextView && focused !is EditText && focused.isClickable) return false
                val def = s.buttons.firstOrNull { it.isDefault } ?: return false
                if (def.action()) dismiss(s)
                true
            }
            else -> false
        }
    }
}
