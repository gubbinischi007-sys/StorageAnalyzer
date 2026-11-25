package com.example.storageanalyzer

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

object ThemeColors {
    val BG_DARK = Color.parseColor("#0F1117")
    val CARD_DARK = Color.parseColor("#181B22")
    val CARD_SECONDARY = Color.parseColor("#252932")
    val TEXT_WHITE = Color.parseColor("#FFFFFF")
    val TEXT_MUTED = Color.parseColor("#8E95A3")
    val TEXT_CHEVRON = Color.parseColor("#6B7280")
    val BAR_EMPTY = Color.parseColor("#2A2E39")

    // Multi-segment colors
    val COLOR_SYSTEM = Color.parseColor("#64748B")
    val COLOR_APPS = Color.parseColor("#3B82F6")
    val COLOR_MEDIA = Color.parseColor("#F59E0B")
    val COLOR_OTHER = Color.parseColor("#10B981")
    val COLOR_CACHE = Color.parseColor("#F59E0B")
    val COLOR_DATA = Color.parseColor("#3B82F6")
    val COLOR_APK = Color.parseColor("#64748B")

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun roundedBg(color: Int, radiusDp: Float, ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, ctx.resources.displayMetrics)
        return d
    }

    fun appColor(pkg: String): Int = when {
        pkg.contains("whatsapp") -> Color.parseColor("#25D366")
        pkg.contains("instagram") -> Color.parseColor("#E1306C")
        pkg.contains("spotify") -> Color.parseColor("#1DB954")
        pkg.contains("youtube") -> Color.parseColor("#FF0000")
        pkg.contains("chrome") -> Color.parseColor("#3B82F6")
        pkg.contains("telegram") -> Color.parseColor("#2AABEE")
        pkg.contains("snapchat") -> Color.parseColor("#FFFC00")
        pkg.contains("facebook") -> Color.parseColor("#1877F2")
        pkg.contains("twitter") || pkg.contains("x.android") -> Color.parseColor("#1DA1F2")
        pkg.contains("netflix") -> Color.parseColor("#E50914")
        else -> {
            val palette = intArrayOf(
                Color.parseColor("#3B82F6"), Color.parseColor("#8B5CF6"),
                Color.parseColor("#EC4899"), Color.parseColor("#10B981"),
                Color.parseColor("#F59E0B"), Color.parseColor("#06B6D4")
            )
            palette[Math.abs(pkg.hashCode()) % palette.size]
        }
    }
}

class SegmentedBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Pairs of (ratio 0f..1f, ColorInt)
    var segments: List<Pair<Float, Int>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val r = h / 2f
        path.reset()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.clipPath(path)

        // Draw empty background
        paint.color = ThemeColors.BAR_EMPTY
        canvas.drawRect(0f, 0f, w, h, paint)

        var curX = 0f
        for ((ratio, color) in segments) {
            val segW = (w * ratio.coerceIn(0f, 1f))
            if (segW > 0) {
                paint.color = color
                canvas.drawRect(curX, 0f, curX + segW, h, paint)
                curX += segW
            }
        }
    }
}
