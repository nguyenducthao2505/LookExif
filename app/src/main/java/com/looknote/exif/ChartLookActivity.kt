package com.looknote.exif

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Dựng màu mới từ bảng màu 64 ô: chụp cùng bảng màu bằng Sony (Creative Look ST, mọi thông số 0)
 * và bằng máy/màu muốn bắt chước (ví dụ Fuji). Chạm 4 góc bảng trên mỗi ảnh, app đo 64 ô,
 * bỏ chênh phơi sáng rồi học màu (cùng phương pháp đã dựng 11 màu Fuji) và lưu thành màu LUT.
 */
class ChartLookActivity : Activity() {

    companion object { private const val PICK_SRC = 61; private const val PICK_DST = 62 }

    private lateinit var srcView: CornerView
    private lateinit var dstView: CornerView
    private lateinit var nameField: EditText
    private lateinit var status: TextView
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        PresetStore.load(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24)) }
        scroll.addView(col)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Film.iconButton(this, IconDrawable.Kind.BACK, null) { finish() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        bar.addView(TextView(this).apply {
            text = "Dựng màu từ bảng màu"; textSize = 17f; typeface = Film.display; letterSpacing = 0.08f; setTextColor(Ui.ink)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(bar)

        col.addView(note("Chụp bảng màu 64 ô (8×8, hàng đầu là dải xám đen → trắng) 2 lần, cùng chỗ, cùng ánh sáng, cùng phơi sáng: " +
            "1 tấm Sony Creative Look ST (mọi thông số 0, WB Shift 0) và 1 tấm bằng máy/màu muốn bắt chước. " +
            "Trên mỗi ảnh, chạm lần lượt 4 góc ngoài của bảng:\n" +
            "① góc ô đen (đầu hàng xám) → ② góc ô trắng (cuối hàng xám) → ③ góc đối diện ô đen → ④ góc còn lại (cuối cột dưới ô đen).\n" +
            "Lưới đỏ phải nằm khớp các ô. Chạm lại để làm từ đầu."))

        srcView = CornerView(this)
        dstView = CornerView(this)
        col.addView(section("1. Ảnh Sony ST 0"))
        col.addView(Ui.button(this, "Chọn ảnh Sony", false) { pick(PICK_SRC) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        col.addView(srcView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(8) })
        col.addView(section("2. Ảnh màu đích"))
        col.addView(Ui.button(this, "Chọn ảnh màu đích", false) { pick(PICK_DST) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        col.addView(dstView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(8) })

        col.addView(section("3. Tên màu"))
        nameField = EditText(this).apply {
            setText("Màu mới"); setTextColor(Ui.ink); setSingleLine()
            background = Ui.rounded(this@ChartLookActivity, Ui.field, 10, Ui.line); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(nameField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(Ui.button(this, "Dựng màu và thêm vào danh sách", true) { build() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
        status = note("")
        col.addView(status)
        setContentView(scroll)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 12.5f; setTextColor(Ui.mute); setPadding(0, dp(10), 0, dp(4)); setLineSpacing(0f, 1.2f)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ink); setPadding(0, dp(16), 0, dp(6))
    }

    private fun pick(code: Int) {
        val it = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        try { startActivityForResult(it, code) } catch (e: Exception) { Ui.toast(this, "Không mở được trình chọn ảnh") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val view = when (requestCode) { PICK_SRC -> srcView; PICK_DST -> dstView; else -> return }
        worker.execute {
            val bmp = try {
                val head = Ui.readHead(this, u, 512 * 1024)
                Ui.decodeMax(this, u, 1600, PhotoInfoBuilder.build("", head).orientation)
            } catch (t: Throwable) { null }
            runOnUiThread {
                if (bmp == null) Ui.toast(this, "Không mở được ảnh") else view.setImage(bmp)
            }
        }
    }

    private fun build() {
        val s = srcView.bitmap; val d = dstView.bitmap
        if (s == null || d == null) { Ui.toast(this, "Chọn đủ 2 ảnh"); return }
        if (srcView.pts.size < 4 || dstView.pts.size < 4) { Ui.toast(this, "Chạm đủ 4 góc trên mỗi ảnh"); return }
        val name = nameField.text.toString().trim().ifBlank { "Màu mới" }.take(40)
        val sp = srcView.pts.toList(); val dpp = dstView.pts.toList()
        status.text = "Đang đo 64 ô và học màu..."
        worker.execute {
            val msg = try {
                val ms = measure(s, sp); val md = measure(d, dpp)
                val samples = ChartFit.pair(ms, md)
                require(samples.size >= 30) { "chỉ đo được ${samples.size}/64 ô (ảnh cháy sáng, lệch góc hoặc bị lóa). Kiểm tra lại 4 góc." }
                val model = ChartFit.fit(samples)
                val (before, after) = ChartFit.score(model, samples)
                val data = ChartFit.bake(model)
                val dir = java.io.File(filesDir, "luts").apply { mkdirs() }
                val f = java.io.File(dir, "chart_${System.currentTimeMillis()}.cube")
                LabMath.writeCube(f, name, 33, data)
                val n = Presets.all.count { it.lutFile?.startsWith("film:") == false } + 1
                val p = Preset("L$n", name, "LUT", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, "", lutFile = f.absolutePath)
                runOnUiThread { PresetStore.save(this, Presets.all + p) }
                "Đã thêm màu \"$name\" (nhóm LUT). Đo được ${samples.size}/64 ô.\n" +
                    "Sai lệch màu Sony so với màu đích: ΔE ${PhotoInfoBuilder.fmt(before, 1)} → sau khi áp màu: ΔE ${PhotoInfoBuilder.fmt(after, 1)}.\n" +
                    "Dùng màu này cho ảnh Sony chụp Creative Look ST, mọi thông số 0."
            } catch (t: Throwable) { "Không dựng được màu: ${t.message}" }
            runOnUiThread {
                status.text = msg
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Dựng màu").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun measure(b: Bitmap, pts: List<FloatArray>): Array<DoubleArray?> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        return ChartFit.measure(px, w, h, ChartFit.homography(pts))
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    /** Khung xem ảnh: chạm 4 góc bảng màu, vẽ lưới 8×8 theo 4 góc đã chạm. */
    class CornerView(c: Context) : View(c) {
        var bitmap: Bitmap? = null; private set
        val pts = ArrayList<FloatArray>()
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 2f; style = Paint.Style.STROKE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        init { background = Ui.rounded(c, Ui.card, 12, Ui.line) }

        fun setImage(b: Bitmap) { bitmap = b; pts.clear(); invalidate() }

        private fun layoutRect() {
            val b = bitmap ?: return
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * s; val h = b.height * s
            rect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            layoutRect()
            canvas.drawBitmap(b, null, rect, paint)
            val s = rect.width() / b.width
            fun sx(x: Double) = (rect.left + x * s).toFloat()
            fun sy(y: Double) = (rect.top + y * s).toFloat()
            if (pts.size == 4) {
                val hm = ChartFit.homography(pts)
                for (k in 0..8) {
                    val a = ChartFit.project(hm, k.toDouble(), 0.0); val z = ChartFit.project(hm, k.toDouble(), 8.0)
                    canvas.drawLine(sx(a[0]), sy(a[1]), sx(z[0]), sy(z[1]), grid)
                    val a2 = ChartFit.project(hm, 0.0, k.toDouble()); val z2 = ChartFit.project(hm, 8.0, k.toDouble())
                    canvas.drawLine(sx(a2[0]), sy(a2[1]), sx(z2[0]), sy(z2[1]), grid)
                }
            }
            pts.forEachIndexed { i, p ->
                val x = sx(p[0].toDouble()); val y = sy(p[1].toDouble())
                canvas.drawCircle(x, y, 16f, dot)
                canvas.drawText("${i + 1}", x, y + 9f, label)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val b = bitmap ?: return false
            if (e.action == MotionEvent.ACTION_DOWN) { parent?.requestDisallowInterceptTouchEvent(true); return true }
            if (e.action != MotionEvent.ACTION_UP) return true
            layoutRect()
            if (!rect.contains(e.x, e.y)) return true
            if (pts.size >= 4) pts.clear()
            val s = rect.width() / b.width
            pts += floatArrayOf((e.x - rect.left) / s, (e.y - rect.top) / s)
            invalidate()
            return true
        }
    }
}
