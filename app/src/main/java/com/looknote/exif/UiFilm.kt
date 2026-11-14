package com.looknote.exif

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Biểu tượng nét mảnh vẽ bằng code (lưới 24×24), không cần file ảnh. */
class IconDrawable(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { BACK, PREV, NEXT, SHARE, GRID, EDIT, SETTINGS, FOLDER, FILM, CHECK, FILTER }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        p.strokeWidth = 1.9f
        val path = Path()
        when (kind) {
            Kind.BACK -> { path.moveTo(20f, 12f); path.lineTo(5f, 12f); path.moveTo(11f, 6f); path.lineTo(5f, 12f); path.lineTo(11f, 18f) }
            Kind.PREV -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f) }
            Kind.NEXT -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f) }
            Kind.SHARE -> {
                canvas.drawCircle(18f, 5.5f, 2.6f, p); canvas.drawCircle(6f, 12f, 2.6f, p); canvas.drawCircle(18f, 18.5f, 2.6f, p)
                path.moveTo(8.3f, 10.8f); path.lineTo(15.7f, 6.8f); path.moveTo(8.3f, 13.2f); path.lineTo(15.7f, 17.2f)
            }
            Kind.GRID -> for (r in 0..1) for (c in 0..1) canvas.drawRoundRect(RectF(4f + c * 9f, 4f + r * 9f, 11f + c * 9f, 11f + r * 9f), 1.5f, 1.5f, p)
            Kind.EDIT -> {
                for ((y, x) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                    path.moveTo(3f, y); path.lineTo(21f, y)
                    canvas.drawCircle(x, y, 2.4f, Paint(p).apply { style = Paint.Style.FILL })
                }
            }
            Kind.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3f, p)
                canvas.drawCircle(12f, 12f, 7f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    path.moveTo((12 + 7 * Math.cos(a)).toFloat(), (12 + 7 * Math.sin(a)).toFloat())
                    path.lineTo((12 + 9.6 * Math.cos(a)).toFloat(), (12 + 9.6 * Math.sin(a)).toFloat())
                }
            }
            Kind.FOLDER -> {
                path.moveTo(3f, 7f); path.lineTo(3f, 18.5f); path.lineTo(21f, 18.5f); path.lineTo(21f, 8.5f)
                path.lineTo(11.5f, 8.5f); path.lineTo(9.5f, 5.5f); path.lineTo(3f, 5.5f); path.close()
            }
            Kind.FILM -> {
                // cuộn phim: khung + lỗ răng cưa hai bên
                canvas.drawRoundRect(RectF(4f, 3f, 20f, 21f), 2f, 2f, p)
                path.moveTo(8f, 3f); path.lineTo(8f, 21f); path.moveTo(16f, 3f); path.lineTo(16f, 21f)
                val f = Paint(p).apply { style = Paint.Style.FILL }
                for (y in listOf(6.5f, 12f, 17.5f)) { canvas.drawRect(5.4f, y - 1f, 6.6f, y + 1f, f); canvas.drawRect(17.4f, y - 1f, 18.6f, y + 1f, f) }
            }
            Kind.CHECK -> { path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f) }
            Kind.FILTER -> { path.moveTo(4f, 5f); path.lineTo(20f, 5f); path.lineTo(14f, 12.5f); path.lineTo(14f, 19f); path.lineTo(10f, 17f); path.lineTo(10f, 12.5f); path.close() }
        }
        canvas.drawPath(path, p)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Dải lỗ răng cưa của phim 135, dùng làm đường phân cách trang trí. */
class SprocketView(c: Context) : View(c) {
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.bg }
    private val r = RectF()
    init { setBackgroundColor(Ui.card) }
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val hw = h * 0.55f; val hh = h * 0.5f; val gap = h * 1.15f
        var x = gap / 2
        while (x < width) {
            r.set(x, (h - hh) / 2, x + hw, (h + hh) / 2)
            canvas.drawRoundRect(r, hh * 0.25f, hh * 0.25f, hole)
            x += gap
        }
    }
}

/** Các thành phần giao diện kiểu máy ảnh phim. */
object Film {
    /** Chữ khắc trên nóc máy: đậm, hẹp, giãn chữ. */
    val display: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    /** Số kiểu bộ đếm khung phim. */
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun dp(c: Context, v: Int) = Ui.dp(c, v)

    /** Nút biểu tượng (có thể kèm chữ nhỏ bên dưới); giữ lâu để hiện tên. */
    fun iconButton(c: Context, kind: IconDrawable.Kind, caption: String?, primary: Boolean = false, onClick: () -> Unit): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = Ui.rounded(c, if (primary) Ui.accent else Ui.field, 12, if (primary) 0 else Ui.line)
            setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5))
            isClickable = true; isFocusable = true
            contentDescription = caption
            setOnClickListener { onClick() }
            setOnLongClickListener { if (caption != null) Ui.toast(c, caption); true }
            minimumWidth = dp(c, 44)
        }
        val ink = if (primary) Ui.onAccent else Ui.ink
        box.addView(ImageView(c).apply { setImageDrawable(IconDrawable(kind, ink)) },
            LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)))
        if (caption != null) box.addView(TextView(c).apply {
            // không dùng letterSpacing + ellipsize cùng lúc: Android cắt chữ sớm ("Côn...")
            text = caption; textSize = 10f; typeface = Film.display
            setTextColor(if (primary) Ui.onAccent else Ui.mute); setSingleLine(true)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** Thanh trên cùng: nút quay lại · tiêu đề · các nút thao tác. */
    fun topBar(c: Context, title: String, onBack: (() -> Unit)?, vararg actions: View): LinearLayout {
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 6))
        }
        if (onBack != null) bar.addView(iconButton(c, IconDrawable.Kind.BACK, null) { onBack() },
            LinearLayout.LayoutParams(dp(c, 44), dp(c, 40)).apply { rightMargin = dp(c, 10) })
        bar.addView(TextView(c).apply {
            text = title.uppercase(); textSize = 17f; typeface = Film.display; letterSpacing = 0.1f
            setTextColor(Ui.ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEachIndexed { i, v ->
            bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 40)).apply {
                leftMargin = if (i == 0) dp(c, 6) else dp(c, 6)
            })
        }
        return bar
    }

    /** Tiêu đề nhóm kiểu số khung trên mép phim: "02 ─ KHUNG ẢNH ───". */
    fun sectionHeader(c: Context, no: Int, title: String): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 2), dp(c, 18), 0, dp(c, 4))
        }
        row.addView(TextView(c).apply {
            text = String.format("%02d", no); textSize = 11f; typeface = Film.mono; setTextColor(Ui.accent)
        })
        row.addView(TextView(c).apply {
            text = title.uppercase(); textSize = 12.5f; typeface = Film.display; letterSpacing = 0.14f
            setTextColor(Ui.ink); setPadding(dp(c, 8), 0, dp(c, 10), 0)
        })
        row.addView(View(c).apply { setBackgroundColor(Ui.line) },
            LinearLayout.LayoutParams(0, dp(c, 1), 1f))
        return row
    }

    /** Dải thông báo có vạch màu bên trái. */
    fun banner(c: Context, text: CharSequence, warn: Boolean = true): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(c, if (warn) Ui.accentSoft else Ui.card, 10)
        }
        box.addView(View(c).apply { background = Ui.rounded(c, if (warn) Ui.accent else Ui.mute, 2) },
            LinearLayout.LayoutParams(dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(c, 10) })
        box.addView(TextView(c).apply {
            this.text = text; textSize = 13f; setTextColor(if (warn) Ui.ink else Ui.mute); setLineSpacing(0f, 1.15f)
            setPadding(0, dp(c, 9), dp(c, 10), dp(c, 9))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return box
    }

    /** Dải răng cưa phim. */
    fun sprockets(c: Context): View = SprocketView(c)

    /** Tô nút dạng chọn (bật = màu hổ phách, chữ tối). */
    fun paintChip(c: Context, b: TextView, on: Boolean) {
        b.background = Ui.rounded(c, if (on) Ui.accent else Ui.field, 12, if (on) 0 else Ui.line)
        b.setTextColor(if (on) Ui.onAccent else Ui.ink)
    }
}
