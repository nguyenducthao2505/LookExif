package com.looknote.exif

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Áp cắt / xoay lên ảnh. */
object Crop {
    fun apply(src: Bitmap, s: CropSpec): Bitmap {
        if (s.isIdentity) return src
        val w = src.width; val h = src.height
        val fw = CropGeom.frameW(w.toFloat(), h.toFloat(), s.q); val fh = CropGeom.frameH(w.toFloat(), h.toFloat(), s.q)
        val (ow, oh) = CropGeom.outSize(w, h, s)
        val ox = s.l * fw; val oy = s.t * fh
        if (s.q == 0 && !s.flip && abs(s.angle) < 0.01f) {
            val x = Math.round(ox).coerceIn(0, w - 1); val y = Math.round(oy).coerceIn(0, h - 1)
            return Bitmap.createBitmap(src, x, y, min(ow, w - x), min(oh, h - y))
        }
        val fwd = CropGeom.forward(w.toFloat(), h.toFloat(), s)
        val inv = CropGeom.invert(fwd)
        // chỉ lấy phần ảnh gốc cần dùng (đỡ tốn bộ nhớ khi ảnh lớn)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val o = FloatArray(2)
        for ((x, y) in listOf(ox to oy, ox + ow to oy, ox to oy + oh, ox + ow to oy + oh)) {
            CropGeom.map(inv, x, y, o)
            minX = min(minX, o[0]); minY = min(minY, o[1]); maxX = max(maxX, o[0]); maxY = max(maxY, o[1])
        }
        val bx = (minX.toInt() - 2).coerceIn(0, w - 1); val by = (minY.toInt() - 2).coerceIn(0, h - 1)
        val bw = (maxX.toInt() + 3 - bx).coerceIn(1, w - bx); val bh = (maxY.toInt() + 3 - by).coerceIn(1, h - by)
        val sub = if (bx == 0 && by == 0 && bw == w && bh == h) src else Bitmap.createBitmap(src, bx, by, bw, bh)
        val m = Matrix()
        m.setValues(floatArrayOf(
            fwd[0], fwd[1], fwd[2] + fwd[0] * bx + fwd[1] * by - ox,
            fwd[3], fwd[4], fwd[5] + fwd[3] * bx + fwd[4] * by - oy,
            0f, 0f, 1f))
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { if (abs(s.angle) >= 0.01f) isAntiAlias = true }
        Canvas(out).apply { drawColor(Color.BLACK); drawBitmap(sub, m, p) }
        if (sub !== src) sub.recycle()
        return out
    }
}

/** Dữ liệu trao đổi giữa màn chỉnh và màn cắt. */
object CropBridge {
    var preview: Bitmap? = null
    var faces: List<FaceRegion> = emptyList()
    var spec: CropSpec = CropSpec()
    var result: CropSpec? = null
}

/** Khung cắt: kéo góc / cạnh / giữa khung, lưới 1/3. */
class CropView(c: Context) : View(c) {
    var bmp: Bitmap? = null
        set(v) { field = v; invalidate() }
    var spec = CropSpec()
        set(v) { field = v; invalidate() }
    var onChange: ((CropSpec) -> Unit)? = null

    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dpf(1.6f) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); strokeWidth = dpf(0.8f) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dpf(4f); strokeCap = Paint.Cap.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // khung hiển thị: điểm khung (fw×fh) → màn hình
    private var dispScale = 1f; private var dispX = 0f; private var dispY = 0f
    private var fw = 1f; private var fh = 1f

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun layoutFrame() {
        val b = bmp ?: return
        fw = CropGeom.frameW(b.width.toFloat(), b.height.toFloat(), spec.q)
        fh = CropGeom.frameH(b.width.toFloat(), b.height.toFloat(), spec.q)
        val pad = dpf(22f)
        val aw = width - 2 * pad; val ah = height - 2 * pad
        dispScale = min(aw / fw, ah / fh)
        dispX = (width - fw * dispScale) / 2f; dispY = (height - fh * dispScale) / 2f
    }

    private fun sx(nx: Float) = dispX + nx * fw * dispScale
    private fun sy(ny: Float) = dispY + ny * fh * dispScale

    override fun onDraw(cv: Canvas) {
        val b = bmp ?: return
        layoutFrame()
        val f = CropGeom.forward(b.width.toFloat(), b.height.toFloat(), spec)
        val m = Matrix()
        m.setValues(floatArrayOf(f[0], f[1], f[2], f[3], f[4], f[5], 0f, 0f, 1f))
        m.postScale(dispScale, dispScale); m.postTranslate(dispX, dispY)
        cv.drawBitmap(b, m, bmpPaint)
        val l = sx(spec.l); val t = sy(spec.t); val r = sx(spec.r); val bt = sy(spec.b)
        // làm tối phần bị cắt
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(l, t, r, bt, Path.Direction.CW)
        }
        cv.drawPath(path, dimPaint)
        for (i in 1..2) {
            val x = l + (r - l) * i / 3f; val y = t + (bt - t) * i / 3f
            cv.drawLine(x, t, x, bt, gridPaint); cv.drawLine(l, y, r, y, gridPaint)
        }
        cv.drawRect(l, t, r, bt, linePaint)
        val k = dpf(16f)
        for ((x, y, dx, dy) in listOf(Quad(l, t, 1f, 1f), Quad(r, t, -1f, 1f), Quad(l, bt, 1f, -1f), Quad(r, bt, -1f, -1f))) {
            cv.drawLine(x, y, x + k * dx, y, handlePaint); cv.drawLine(x, y, x, y + k * dy, handlePaint)
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    // ----- kéo -----
    private var mode = 0           // bit: 1 trái, 2 trên, 4 phải, 8 dưới; 16 = di chuyển
    private var lastX = 0f; private var lastY = 0f
    private var start = CropSpec()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bmp ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutFrame()
                val l = sx(spec.l); val t = sy(spec.t); val r = sx(spec.r); val bt = sy(spec.b)
                val tol = dpf(28f)
                mode = 0
                if (e.y > t - tol && e.y < bt + tol) {
                    if (abs(e.x - l) < tol) mode = mode or 1 else if (abs(e.x - r) < tol) mode = mode or 4
                }
                if (e.x > l - tol && e.x < r + tol) {
                    if (abs(e.y - t) < tol) mode = mode or 2 else if (abs(e.y - bt) < tol) mode = mode or 8
                }
                if (mode == 0 && e.x in l..r && e.y in t..bt) mode = 16
                lastX = e.x; lastY = e.y; start = spec
                parent?.requestDisallowInterceptTouchEvent(true)
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return false
                val dx = (e.x - lastX) / (fw * dispScale); val dy = (e.y - lastY) / (fh * dispScale)
                val n = drag(b, start, dx, dy)
                if (n != null) { spec = n; onChange?.invoke(n) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
        }
        return true
    }

    private fun drag(b: Bitmap, s0: CropSpec, dx: Float, dy: Float): CropSpec? {
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val minN = 0.06f
        if (mode == 16) {
            // di chuyển: thử cả hai trục, rồi từng trục
            for ((ddx, ddy) in listOf(dx to dy, dx to 0f, 0f to dy)) {
                var mx = ddx; var my = ddy
                mx = mx.coerceIn(-s0.l, 1f - s0.r); my = my.coerceIn(-s0.t, 1f - s0.b)
                val c = s0.copy(l = s0.l + mx, r = s0.r + mx, t = s0.t + my, b = s0.b + my)
                if (CropGeom.fits(w, h, c, c.l, c.t, c.r, c.b)) return c
            }
            return null
        }
        var l = s0.l; var t = s0.t; var r = s0.r; var bt = s0.b
        if (mode and 1 != 0) l = min(s0.l + dx, r - minN)
        if (mode and 4 != 0) r = max(s0.r + dx, l + minN)
        if (mode and 2 != 0) t = min(s0.t + dy, bt - minN)
        if (mode and 8 != 0) bt = max(s0.b + dy, t + minN)
        l = max(0f, l); t = max(0f, t); r = min(1f, r); bt = min(1f, bt)
        if (s0.aspect > 0f) {
            // giữ tỷ lệ (theo điểm ảnh): cạnh kéo quyết định, cạnh kia co giãn quanh góc / tâm đối diện
            val horiz = (mode and 5) != 0; val vert = (mode and 10) != 0
            val pw = (r - l) * fw; val ph = (bt - t) * fh
            if (horiz && (!vert || pw / s0.aspect >= ph)) {
                val nh = pw / s0.aspect / fh
                if (mode and 2 != 0) t = bt - nh else if (mode and 8 != 0) bt = t + nh
                else { val cy = (s0.t + s0.b) / 2f; t = cy - nh / 2f; bt = cy + nh / 2f }
            } else {
                val nw = ph * s0.aspect / fw
                if (mode and 1 != 0) l = r - nw else if (mode and 4 != 0) r = l + nw
                else { val cx = (s0.l + s0.r) / 2f; l = cx - nw / 2f; r = cx + nw / 2f }
            }
        }
        val c = s0.copy(l = l, t = t, r = r, b = bt)
        return if (CropGeom.fits(w, h, c, l, t, r, bt)) c else null
    }
}

/** Màn cắt / xoay: tỷ lệ, xoay 90°, lật, cân thẳng, gợi ý bố cục. */
class CropActivity : Activity() {

    private lateinit var view: CropView
    private lateinit var angleLabel: TextView
    private lateinit var angleBar: SeekBar
    private lateinit var info: TextView
    private lateinit var body: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var sugRow: LinearLayout
    private val ratioBtns = ArrayList<Pair<Float, Button>>()
    private var spec = CropSpec()
    private var suggestToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        val b = CropBridge.preview
        if (b == null) { finish(); return }
        spec = CropBridge.spec

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.bg) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        bar.addView(Ui.button(this, "Hủy", false) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        bar.addView(TextView(this).apply {
            text = "Cắt & xoay"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ink); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.button(this, "Xong", true) {
            CropBridge.result = spec; setResult(RESULT_OK); finish()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(bar)

        body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view = CropView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            bmp = b
            onChange = { s -> this@CropActivity.spec = s; autoInner = false; updateInfo() }
        }

        panelScroll = ScrollView(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(16)) }
        panelScroll.addView(panel)

        info = small("")
        panel.addView(info)

        panel.addView(title("Gợi ý bố cục"))
        sugRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sugRow) })
        sugRow.addView(small("Đang phân tích ảnh..."))

        panel.addView(title("Tỷ lệ"))
        val ratioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ratios = listOf("Tự do" to 0f, "Gốc" to -1f) + CropGeom.ratios
        for ((name, a) in ratios) {
            val btn = Ui.button(this, name, false) { setRatio(a) }
            ratioBtns += a to btn
            ratioRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(6) })
        }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ratioRow) })

        panel.addView(title("Xoay"))
        val rot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rot.addView(Ui.button(this, "⟲ 90°", false) { rotate(-1) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        rot.addView(Ui.button(this, "⟳ 90°", false) { rotate(1) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        rot.addView(Ui.button(this, "Lật ngang", false) { flip() }, LinearLayout.LayoutParams(0, dp(38), 1.2f).apply { rightMargin = dp(6) })
        rot.addView(Ui.button(this, "Đặt lại", false) { reset() }, LinearLayout.LayoutParams(0, dp(38), 1.1f))
        panel.addView(rot)
        angleLabel = TextView(this).apply { textSize = 13f; setTextColor(Ui.ink); setPadding(dp(2), dp(10), 0, 0) }
        panel.addView(angleLabel)
        angleBar = SeekBar(this).apply {
            max = 300; progress = (spec.angle * 10 + 150).toInt().coerceIn(0, 300)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setAngle((p - 150) / 10f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        panel.addView(angleBar)
        panel.addView(small("Kéo góc hoặc cạnh khung để cắt, kéo giữa khung để dời. Ảnh được cắt khi lưu, ảnh gốc giữ nguyên."))

        applyLayout()
        setContentView(root)
        view.spec = spec
        updateInfo()
        computeSuggestions()
    }

    private fun applyLayout() {
        body.removeAllViews()
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            body.orientation = LinearLayout.HORIZONTAL
            body.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            body.addView(panelScroll, LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            body.orientation = LinearLayout.VERTICAL
            body.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            body.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private val bw get() = CropBridge.preview!!.width.toFloat()
    private val bh get() = CropBridge.preview!!.height.toFloat()

    private fun set(s: CropSpec) { spec = s; view.spec = s; updateInfo() }

    private fun setRatio(a0: Float) {
        val a = if (a0 < 0f) CropGeom.frameW(bw, bh, spec.q) / CropGeom.frameH(bw, bh, spec.q) else a0
        if (a == 0f) { set(spec.copy(aspect = 0f)); return }
        set(CropGeom.maxInner(bw, bh, spec, a))
    }

    private fun rotate(d: Int) {
        val s = spec.copy(quarter = (spec.q + d + 4) % 4)
        // tỷ lệ đang khóa giữ nguyên hướng theo ảnh: đổi dọc ↔ ngang
        val a = if (spec.aspect > 0f) 1f / spec.aspect else 0f
        set(CropGeom.maxInner(bw, bh, s, a).copy(aspect = a))
        computeSuggestions()
    }

    private fun flip() {
        // lật ngang: khung cắt cũng đối xứng theo
        set(CropGeom.shrinkToFit(bw, bh, spec.copy(flip = !spec.flip, angle = -spec.angle, l = 1f - spec.r, r = 1f - spec.l)))
        angleBar.progress = (spec.angle * 10 + 150).toInt().coerceIn(0, 300)
        computeSuggestions()
    }

    private fun reset() {
        set(CropSpec()); angleBar.progress = 150; computeSuggestions()
    }

    private fun setAngle(a: Float) {
        val wasFull = spec.l <= 0.001f && spec.t <= 0.001f && spec.r >= 0.999f && spec.b >= 0.999f
        val s = spec.copy(angle = a)
        // nếu khung đang là cả ảnh (hoặc do cân thẳng thu nhỏ) thì luôn lấy khung lớn nhất
        set(if (wasFull || autoInner) CropGeom.maxInner(bw, bh, s, s.aspect).also { autoInner = true }
            else CropGeom.shrinkToFit(bw, bh, s))
    }
    private var autoInner = false

    private fun updateInfo() {
        val e = if (spec.angle > 0) "+" else ""
        angleLabel.text = "Cân thẳng: $e" + String.format("%.1f", spec.angle) + "°"
        val full = CropGeom.outSize(bw.toInt(), bh.toInt(), spec)
        info.text = if (spec.isIdentity) "Chưa cắt / xoay" else spec.describe(bw.toInt(), bh.toInt()) +
            "  (khung xem ${full.first}×${full.second}, khi lưu theo độ phân giải thật)"
        for ((a, btn) in ratioBtns) {
            val on = when {
                a == 0f -> spec.aspect == 0f
                a < 0f -> spec.aspect > 0f && abs(spec.aspect - CropGeom.frameW(bw, bh, spec.q) / CropGeom.frameH(bw, bh, spec.q)) < 0.005f &&
                    CropGeom.ratios.none { abs(it.second - spec.aspect) < 0.005f }
                else -> abs(spec.aspect - a) < 0.005f
            }
            Film.paintChip(this, btn, on)
        }
    }

    private fun computeSuggestions() {
        val my = ++suggestToken
        val base = CropBridge.preview ?: return
        val faces = CropBridge.faces
        val q = spec.q; val fl = spec.flip
        sugRow.removeAllViews(); sugRow.addView(small("Đang phân tích ảnh..."))
        Thread {
            val list = try {
                // ảnh nhỏ theo hướng khung (đã xoay 90°/lật, chưa cân thẳng)
                val sc = 480f / max(base.width, base.height)
                val sm = Bitmap.createScaledBitmap(base, max(1, (base.width * sc).toInt()), max(1, (base.height * sc).toInt()), true)
                val orient = CropSpec(quarter = q, flip = fl)
                val fr = Crop.apply(sm, orient)
                val w = fr.width; val h = fr.height
                val px = IntArray(w * h); fr.getPixels(px, 0, w, 0, 0, w, h)
                val fwd = CropGeom.forward(sm.width.toFloat(), sm.height.toFloat(), orient)
                val o = FloatArray(2); val o2 = FloatArray(2)
                val fs = faces.map { f ->
                    CropGeom.map(fwd, f.left * sc, f.top * sc, o); CropGeom.map(fwd, f.right * sc, f.bottom * sc, o2)
                    floatArrayOf(min(o[0], o2[0]), min(o[1], o2[1]), max(o[0], o2[0]), max(o[1], o2[1]))
                }
                CropSuggest.suggest(px, w, h, fs)
            } catch (t: Throwable) { emptyList() }
            runOnUiThread {
                if (my != suggestToken) return@runOnUiThread
                sugRow.removeAllViews()
                if (list.isEmpty()) sugRow.addView(small("Không có gợi ý cho ảnh này."))
                for (g in list) {
                    sugRow.addView(Ui.button(this, g.name, false) { applySuggestion(g) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(6) })
                }
            }
        }.start()
    }

    private fun applySuggestion(g: CropSuggest.Suggestion) {
        val angle = g.angle ?: spec.angle
        var s = spec.copy(angle = angle, l = g.l, t = g.t, r = g.r, b = g.b, aspect = g.aspect)
        s = CropGeom.shrinkToFit(bw, bh, s)
        autoInner = g.angle != null
        angleBar.progress = (s.angle * 10 + 150).toInt().coerceIn(0, 300)
        set(s)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.accent); setPadding(dp(2), dp(14), 0, dp(4))
    }

    private fun small(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Ui.mute); setPadding(dp(2), dp(2), 0, dp(4))
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
