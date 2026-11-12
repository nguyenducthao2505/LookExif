package com.looknote.exif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Lưới chọn công thức màu: mỗi ô là ảnh xem trước nhỏ của chính ảnh đang sửa đã áp công thức đó,
 * nên nhìn được nhiều look cùng lúc để so sánh.
 * - Lọc theo nhóm: Tất cả / Máy ảnh / Fuji / Kodak / Canon / LUT.
 * - Chọn số cột 3–5 (ghi nhớ giữa các lần mở).
 * Ô đầu tiên ("Gốc") là không áp màu mới, tương ứng chỉ số -1.
 */
class LookGrid(
    private val ctx: Context,
    private val onPick: (Int) -> Unit
) : LinearLayout(ctx) {

    private enum class Group(val label: String) { ALL("Tất cả"), CAM("Máy ảnh"), FUJI("Fuji"), KODAK("Kodak"), CANON("Canon"), LUT("LUT") }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var token = 0

    private var group = Group.ALL
    private var cols = prefs().getInt("lookGridCols", 4).coerceIn(3, 5)
    private var selected = -1
    private var source: Bitmap? = null
    /** Ảnh xem trước theo chỉ số công thức (-1 = gốc). */
    private val thumbs = HashMap<Int, Bitmap>()
    private val tiles = HashMap<Int, Tile>()

    private val groupBtns = ArrayList<Pair<Group, Button>>()
    private val colBtns = ArrayList<Pair<Int, Button>>()
    private val gridBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val status = TextView(ctx).apply { textSize = 11.5f; setTextColor(Ui.mute); setPadding(dp(2), dp(4), 0, 0) }

    private class Tile(val box: LinearLayout, val img: ImageView, val name: TextView)

    init {
        orientation = VERTICAL

        // hàng lọc nhóm (chỉ hiện nhóm có công thức)
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        Group.values().filter { g -> g == Group.ALL || Presets.all.any { groupOf(it) == g } }.forEach { g ->
            val b = Ui.button(ctx, g.label, false) { group = g; styleBars(); build() }
            groupBtns += g to b
            row.addView(b, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }
        hs.addView(row)
        addView(hs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // chọn số cột
        val colRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colRow.addView(TextView(ctx).apply { text = "Cỡ ô:"; textSize = 12f; setTextColor(Ui.mute) },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        for ((n, t) in listOf(3 to "Lớn", 4 to "Vừa", 5 to "Nhỏ")) {
            val b = Ui.button(ctx, t, false) {
                cols = n; prefs().edit().putInt("lookGridCols", n).apply(); styleBars(); build()
            }
            colBtns += n to b
            colRow.addView(b, LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(6) })
        }
        addView(colRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        addView(gridBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(status)
        styleBars()
        build()
    }

    /** Đánh dấu công thức đang chọn (-1 = gốc). */
    fun setSelected(i: Int) {
        selected = i
        tiles.forEach { (k, t) -> styleTile(k, t) }
    }

    /**
     * Ảnh nguồn để dựng ảnh xem trước (nên là ảnh nhỏ, đã gỡ màu lúc chụp nếu đang bật).
     * Gọi lại mỗi khi ảnh nguồn đổi; ảnh cũ được thay khi dựng xong.
     */
    fun setSource(b: Bitmap?) {
        val oldRatio = source?.let { it.height.toFloat() / it.width }
        source = b
        val my = ++token
        if (b != null && oldRatio != b.height.toFloat() / b.width) build()
        if (b == null) { thumbs.clear(); tiles.forEach { (_, t) -> t.img.setImageDrawable(null) }; return }
        val w = b.width; val h = b.height
        val src = IntArray(w * h)
        b.getPixels(src, 0, w, 0, 0, w, h)
        main.post { if (my == token) { put(-1, b) } }
        // công thức đang hiện trên lưới được dựng trước
        val order = visibleIndices() + Presets.all.indices.filter { it !in visibleIndices() }
        status.text = "Đang dựng ảnh xem trước..."
        worker.execute {
            for (i in order) {
                if (my != token) return@execute
                val p = Presets.all.getOrNull(i) ?: continue
                val px = src.copyOf()
                try {
                    ColorEngine(p, p.ev, 1f).process(px)
                    val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                    main.post { if (my == token) put(i, out) }
                } catch (t: Throwable) { }
            }
            main.post { if (my == token) status.text = "" }
        }
    }

    fun release() { token++; worker.shutdownNow() }

    // ---------------------------------------------------------------------

    private fun put(i: Int, b: Bitmap) {
        thumbs[i] = b
        tiles[i]?.img?.setImageBitmap(b)
    }

    private fun groupOf(p: Preset): Group {
        val f = p.lutFile ?: return Group.CAM
        return when {
            f.startsWith("film:fuji") -> Group.FUJI
            f.startsWith("film:kodak") -> Group.KODAK
            f.startsWith("film:canon") -> Group.CANON
            f.startsWith("film:") -> Group.LUT
            else -> Group.LUT
        }
    }

    private fun visibleIndices(): List<Int> =
        Presets.all.indices.filter { group == Group.ALL || groupOf(Presets.all[it]) == group }

    private fun build() {
        gridBox.removeAllViews()
        tiles.clear()
        val items = listOf(-1) + visibleIndices()
        var row: LinearLayout? = null
        items.forEachIndexed { n, i ->
            if (n % cols == 0) {
                row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
                gridBox.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            }
            val t = makeTile(i)
            tiles[i] = t
            row!!.addView(t.box, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (n % cols != cols - 1) rightMargin = dp(6)
            })
        }
        // ô trống cho đủ hàng cuối để các ô đều nhau
        val rest = (cols - items.size % cols) % cols
        repeat(rest) { k ->
            row?.addView(View(ctx), LayoutParams(0, 1, 1f).apply { if (k < rest - 1) rightMargin = dp(6) })
        }
    }

    private fun makeTile(i: Int): Tile {
        val p = Presets.all.getOrNull(i)
        val box = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(4))
            isClickable = true
            setOnClickListener { onPick(i) }
        }
        val ratio = source?.let { it.height.toFloat() / it.width } ?: (2f / 3f)
        val img = object : ImageView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val width = View.MeasureSpec.getSize(w)
                setMeasuredDimension(width, (width * ratio).toInt().coerceAtLeast(dp(30)))
            }
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            (thumbs[i])?.let { setImageBitmap(it) }
        }
        val frame = FrameLayout(ctx)
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // mã ô nhớ ở góc ảnh
        if (p != null && p.slot.isNotBlank()) {
            frame.addView(TextView(ctx).apply {
                text = p.slot; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = Ui.rounded(ctx, Color.parseColor("#99000000"), 4)
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(3); topMargin = dp(3)
            })
        }
        box.addView(frame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val name = TextView(ctx).apply {
            text = p?.name ?: "Gốc"
            textSize = if (cols >= 5) 9.5f else 10.5f
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(3), 0, 0)
        }
        box.addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val t = Tile(box, img, name)
        styleTile(i, t)
        return t
    }

    private fun styleTile(i: Int, t: Tile) {
        val on = i == selected
        t.box.background = Ui.rounded(ctx, if (on) Ui.accentSoft else Ui.field, 8, if (on) Ui.accent else Ui.line)
        t.name.setTextColor(if (on) Ui.accent else Ui.ink)
        t.name.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun styleBars() {
        for ((g, b) in groupBtns) paint(b, g == group)
        for ((n, b) in colBtns) paint(b, n == cols)
    }

    private fun paint(b: Button, on: Boolean) {
        b.background = Ui.rounded(ctx, if (on) Ui.accent else Ui.field, 12, if (on) 0 else Ui.line)
        b.setTextColor(if (on) Color.WHITE else Ui.ink)
    }

    private fun prefs() = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun dp(v: Int) = Ui.dp(ctx, v)

    companion object {
        /** Thu nhỏ ảnh làm nguồn xem trước (cạnh dài khoảng [side] px). */
        fun small(b: Bitmap, side: Int = 240): Bitmap {
            val k = side.toFloat() / maxOf(b.width, b.height)
            if (k >= 1f) return b
            return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        }
    }
}
