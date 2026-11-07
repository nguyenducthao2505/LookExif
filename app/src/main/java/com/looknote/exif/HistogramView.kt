package com.looknote.exif

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/** Biểu đồ histogram R, G, B kèm tỉ lệ cháy sáng / mất chi tiết vùng tối. */
class HistogramView(c: Context) : View(c) {
    private var hist: Analysis.Hist? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.mute; textSize = 26f }
    private val bgPaint = Paint().apply { color = Color.parseColor("#0C0C0C") }

    fun setData(h: Analysis.Hist?) { hist = h; invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val h = hist ?: return
        val top = 6f; val bottom = height - 30f
        // căn theo căn bậc hai để vùng thấp vẫn thấy được
        var peak = 1.0
        for (i in 1 until 255) peak = max(peak, sqrt(max(h.r[i], max(h.g[i], h.b[i])).toDouble()))
        fun draw(a: IntArray, color: Int) {
            val p = Path()
            p.moveTo(0f, bottom)
            for (i in 0 until 256) {
                val x = i / 255f * width
                val v = (sqrt(a[i].toDouble()) / peak).coerceAtMost(1.0).toFloat()
                p.lineTo(x, bottom - v * (bottom - top))
            }
            p.lineTo(width.toFloat(), bottom); p.close()
            fill.color = color
            canvas.drawPath(p, fill)
        }
        draw(h.r, Color.argb(110, 255, 70, 70))
        draw(h.g, Color.argb(110, 70, 220, 90))
        draw(h.b, Color.argb(110, 80, 130, 255))
        fun pct(f: Float) = String.format(Locale.US, "%.1f", f * 100).replace('.', ',') + "%"
        text.color = if (h.clipLow > 0.05f) Color.parseColor("#6FA0FF") else Ui.mute
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Đen ${pct(h.clipLow)}", 6f, height - 6f, text)
        text.color = if (h.clipHigh > 0.02f) Color.parseColor("#FF7070") else Ui.mute
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("Cháy ${pct(h.clipHigh)}", width - 6f, height - 6f, text)
    }
}
