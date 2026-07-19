package dev.sfg.orchard.connect

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF313338.toInt()
    const val SURFACE = 0xFF2B2D31.toInt()
    const val RAISED = 0xFF1E1F22.toInt()
    const val TEXT = 0xFFF2F3F5.toInt()
    const val MUTED = 0xFFB5BAC1.toInt()
    const val ACCENT = 0xFF5865F2.toInt()
    const val BORDER = 0x1AFFFFFF
    const val BORDER_ACTIVE = 0x4D5865F2

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun rounded(context: Context, fill: Int, stroke: Int = Color.TRANSPARENT, radiusDp: Int = 8): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            val density = context.resources.displayMetrics.density
            cornerRadius = radiusDp * density
            if (stroke != Color.TRANSPARENT) {
                setStroke(maxOf(1, (1 * density).toInt()), stroke)
            }
        }
    }

    // Overloaded rounded without context for simple/backward compatibility
    fun rounded(fill: Int, radius: Int = 8): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
        }
    }

    fun roundedRipple(context: Context, fill: Int, stroke: Int = Color.TRANSPARENT, radiusDp: Int = 8, rippleColor: Int = 0x20FFFFFF): RippleDrawable {
        val content = rounded(context, fill, stroke, radiusDp)
        val mask = rounded(context, Color.BLACK, Color.TRANSPARENT, radiusDp)
        val colorStateList = ColorStateList.valueOf(rippleColor)
        return RippleDrawable(colorStateList, content, mask)
    }

    fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1))
        }
    }

    fun panel(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            background = rounded(context, SURFACE, BORDER, radiusDp = 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
            }
        }
    }

    fun row(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    fun text(context: Context, value: String, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView {
        return TextView(context).apply {
            text = value
            textSize = sp
            setTextColor(color)
            typeface = if (bold) {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } else {
                Typeface.create("sans-serif", Typeface.NORMAL)
            }
            includeFontPadding = false
        }
    }

    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(if (primary) BG else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val paddingH = context.dp(14)
            val paddingV = context.dp(8)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            minHeight = context.dp(40)
            isClickable = true
            isFocusable = true
            background = roundedRipple(
                context,
                fill = if (primary) ACCENT else RAISED,
                stroke = if (primary) Color.TRANSPARENT else BORDER,
                radiusDp = 6,
                rippleColor = if (primary) 0x33000000 else 0x20FFFFFF
            )
            setOnClickListener { action() }
        }
    }

    fun smallButton(context: Context, label: String, primary: Boolean = false, action: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(if (primary) BG else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val paddingH = context.dp(10)
            val paddingV = context.dp(6)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            minHeight = context.dp(28)
            isClickable = true
            isFocusable = true
            background = roundedRipple(
                context,
                fill = if (primary) ACCENT else RAISED,
                stroke = if (primary) Color.TRANSPARENT else BORDER,
                radiusDp = 4,
                rippleColor = if (primary) 0x33000000 else 0x20FFFFFF
            )
            setOnClickListener { action() }
        }
    }

    fun LinearLayout.addGap(size: Int) {
        addView(View(context), LinearLayout.LayoutParams(1, context.dp(size)))
    }
}
