package com.macpad.macpad

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared building blocks for the content of MacPad windows (Settings, info windows, Notes):
 * macOS-style grouped cards, rows, section titles and key caps. All colours come from [Palette].
 */
object WindowKit {

    fun cardColor(p: Palette): Int = if (p.dark) Color.rgb(44, 44, 48) else Color.WHITE

    /** A rounded card that stacks [rows] separated by hairlines (inset by [separatorInsetDp]). */
    fun card(ctx: Context, p: Palette, rows: List<View>, separatorInsetDp: Int = 14): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(cardColor(p), ctx.dpf(10f), p.separator, ctx.hairlinePx)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            rows.forEachIndexed { i, r ->
                if (i > 0) {
                    addView(
                        View(ctx).apply { setBackgroundColor(p.separator) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairlinePx).apply {
                            marginStart = ctx.dp(separatorInsetDp)
                            marginEnd = ctx.dp(4)
                        },
                    )
                }
                addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

    /**
     * A settings row: optional [icon], [title] with an optional [description] underneath and an
     * optional [control] at the right. With [onClick] the whole row is clickable and focusable.
     */
    fun row(
        ctx: Context,
        p: Palette,
        title: String,
        description: String? = null,
        control: View? = null,
        icon: View? = null,
        titleColor: Int = p.text,
        onClick: ((View) -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(48)
        setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        if (icon != null) {
            addView(icon, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply { marginEnd = ctx.dp(12) })
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.label(title, 14.5f, titleColor, 400, 2))
        if (!description.isNullOrEmpty()) {
            texts.addView(
                ctx.label(description, 12f, p.textSecondary, 400, 6).apply { setLineSpacing(0f, 1.1f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(3)
                },
            )
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (control != null) {
            addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(12)
            })
        }
        if (onClick != null) {
            background = ctx.stateBackground(p, ctx.dpf(8f))
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
        }
    }

    /** "Label ……… value" row with a selectable value. */
    fun valueRow(ctx: Context, p: Palette, label: String, value: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(40)
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            addView(
                ctx.label(label, 13.5f, p.textSecondary, 400, 2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ctx.label(value, 13.5f, p.text, 500, 4).apply {
                    gravity = Gravity.END
                    setTextIsSelectable(true)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginStart = ctx.dp(12) },
            )
        }

    fun sectionTitle(ctx: Context, p: Palette, text: String): TextView =
        ctx.label(text, 13f, p.textSecondary, 600).apply {
            setPadding(ctx.dp(4), ctx.dp(18), ctx.dp(4), ctx.dp(6))
        }

    fun pageTitle(ctx: Context, p: Palette, text: String): TextView =
        ctx.label(text, 21f, p.text, 700).apply {
            setPadding(ctx.dp(2), ctx.dp(4), ctx.dp(2), ctx.dp(2))
        }

    fun hint(ctx: Context, p: Palette, text: String): TextView =
        ctx.label(text, 12.5f, p.textTertiary, 400, 6).apply {
            setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
            setLineSpacing(0f, 1.1f)
        }

    /** A single key cap such as "Ctrl+Space". */
    fun keyCap(ctx: Context, p: Palette, text: String): TextView =
        ctx.label(text, 12f, p.text, 500).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            background = roundRect(p.controlBg, ctx.dpf(6f), p.controlStroke, ctx.hairlinePx)
        }

    /** Alternative key combinations shown as caps separated by "или". */
    fun keyCaps(ctx: Context, p: Palette, alternatives: List<String>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            alternatives.forEachIndexed { i, a ->
                if (i > 0) {
                    addView(
                        ctx.label("или", 11.5f, p.textTertiary),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = ctx.dp(6)
                            marginEnd = ctx.dp(6)
                        },
                    )
                }
                addView(keyCap(ctx, p, a))
            }
        }

    /** A small coloured rounded square with a white glyph (sidebar / row icons). */
    fun iconTile(ctx: Context, glyph: Glyph, color: Int): ImageView = ImageView(ctx).apply {
        setImageDrawable(GlyphDrawable(glyph, Color.WHITE))
        val pad = ctx.dp(5)
        setPadding(pad, pad, pad, pad)
        background = roundRect(color, ctx.dpf(7f))
    }

    /** A plain glyph icon in [color]. */
    fun glyphIcon(ctx: Context, glyph: Glyph, color: Int): ImageView = ImageView(ctx).apply {
        setImageDrawable(GlyphDrawable(glyph, color))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** Russian plural: 1 объект, 2 объекта, 5 объектов. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }
}
