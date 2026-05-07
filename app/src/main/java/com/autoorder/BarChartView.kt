package com.autoorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Bar(val label: String, val value: Float, val valueText: String? = null)

    private var bars: List<Bar> = emptyList()
    private var titleText: String = ""

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E88E5")
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFD8DC")
        strokeWidth = density(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        strokeWidth = density(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#546E7A")
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        textSize = sp(10f)
        textAlign = Paint.Align.RIGHT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        textSize = sp(13f)
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }

    fun setData(title: String, data: List<Bar>) {
        this.titleText = title
        this.bars = data
        invalidate()
    }

    private fun density(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = density(44f)
        val padR = density(12f)
        val padT = density(28f)
        val padB = density(56f)

        if (titleText.isNotEmpty()) {
            canvas.drawText(titleText, density(8f), density(18f), titlePaint)
        }

        if (bars.isEmpty()) {
            canvas.drawText("Không có dữ liệu", w / 2, h / 2, emptyPaint)
            return
        }

        val maxV = max(1f, ceil(bars.maxOf { it.value }))
        val niceMax = niceCeil(maxV)
        val plotL = padL
        val plotR = w - padR
        val plotT = padT
        val plotB = h - padB
        val plotW = plotR - plotL
        val plotH = plotB - plotT

        val gridSteps = 4
        for (i in 0..gridSteps) {
            val y = plotB - (plotH * i / gridSteps)
            canvas.drawLine(plotL, y, plotR, y, if (i == 0) axisPaint else gridPaint)
            val v = niceMax * i / gridSteps
            canvas.drawText(formatY(v), plotL - density(4f), y + sp(3.5f), yLabelPaint)
        }
        canvas.drawLine(plotL, plotT, plotL, plotB, axisPaint)

        val n = bars.size
        val slot = plotW / n
        val barWidth = (slot * 0.65f).coerceAtMost(density(56f))
        val rect = RectF()
        for (i in bars.indices) {
            val b = bars[i]
            val cx = plotL + slot * (i + 0.5f)
            val barH = if (niceMax > 0) (b.value / niceMax) * plotH else 0f
            rect.set(cx - barWidth / 2, plotB - barH, cx + barWidth / 2, plotB)
            canvas.drawRoundRect(rect, density(4f), density(4f), barPaint)

            val valStr = b.valueText ?: formatY(b.value)
            if (b.value > 0f) {
                canvas.drawText(valStr, cx, plotB - barH - density(4f), valuePaint)
            }

            val label = truncate(b.label, slot)
            canvas.drawText(label, cx, plotB + density(16f), labelPaint)
        }
    }

    private fun truncate(s: String, slot: Float): String {
        var t = s
        val maxChars = max(4, (slot / sp(6.5f)).toInt())
        if (t.length > maxChars) t = t.take(maxChars - 1) + "…"
        return t
    }

    private fun formatY(v: Float): String {
        if (v >= 1_000_000f) return String.format("%.1fM", v / 1_000_000f)
        if (v >= 1_000f) return String.format("%.1fk", v / 1_000f)
        val n = v.toLong()
        return if (n.toFloat() == v) n.toString() else String.format("%.1f", v)
    }

    private fun niceCeil(v: Float): Float {
        if (v <= 1f) return 1f
        val mag = Math.pow(10.0, Math.floor(Math.log10(v.toDouble()))).toFloat()
        val n = v / mag
        val rounded = when {
            n <= 1f -> 1f
            n <= 2f -> 2f
            n <= 5f -> 5f
            else -> 10f
        }
        return rounded * mag
    }
}
