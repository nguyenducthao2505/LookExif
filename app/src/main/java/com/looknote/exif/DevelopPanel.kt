package com.looknote.exif

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Màn chỉnh ảnh cung cấp cho bảng Develop. */
interface DevelopHost {
    val activity: Activity
    /** Thông số đã đổi: dựng lại ảnh xem trước. */
    fun developChanged()
    /** Bật ống hút: chạm lên ảnh trả về màu Lab tại điểm chạm (trước bước Develop). */
    fun pickColor(onPicked: (DoubleArray) -> Unit)
    /** Màu da trung bình đo được (Lab), null nếu không có người. */
    fun skinLab(): FloatArray?
    fun histogram(): Analysis.Hist?
    /** Bật / tắt chế độ vẽ mask cho layer [index] (−1 = tắt). */
    fun editMask(index: Int, kind: String)
    /** Hiện / ẩn lớp phủ mask của layer [index] (−1 = ẩn). */
    fun showMask(index: Int)
    fun currentStyle(name: String): StyleData
    fun applyStyle(s: StyleData)
}

/**
 * Bảng công cụ kiểu Capture One: các tab Màu, Tông, Chi tiết, Ống kính, Layer, Style.
 * Giữ [p] là thông số hiện tại; mọi thay đổi gọi [DevelopHost.developChanged].
 */
class DevelopPanel(private val host: DevelopHost) {
    private val c: Context = host.activity
    var p = DevelopParams()
        private set

    /** Gán thông số từ ngoài (Style, dán chỉnh sửa) và dựng lại giao diện. */
    fun setParams(np: DevelopParams) { p = np; rebuildAll() }

    private fun dp(v: Int) = Ui.dp(c, v)

    val color = box(); val tone = box(); val detail = box(); val lens = box(); val layers = box(); val styles = box()

    private fun box() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    init { rebuildAll() }

    private fun rebuildAll() {
        buildColor(); buildTone(); buildDetail(); buildLens(); buildLayers(); buildStyles()
    }

    private fun update(np: DevelopParams, render: Boolean = true) {
        p = np
        if (render) host.developChanged()
    }

    // ---------------------------------------------------------------- helpers

    private fun title(t: String) = TextView(c).apply {
        text = t.uppercase(); textSize = 12f; typeface = Film.display; letterSpacing = 0.1f
        setTextColor(Ui.accent); setPadding(dp(2), dp(14), 0, dp(2))
    }

    private fun note(t: String) = TextView(c).apply { text = t; textSize = 11.5f; setTextColor(Ui.mute); setPadding(dp(2), dp(2), 0, dp(4)) }

    /** Thanh trượt có nhãn "Tên: giá trị"; chạm đúp nhãn để đặt lại. */
    private fun slider(parent: LinearLayout, name: String, min: Float, max: Float, value: Float, step: Float = 1f,
                       def: Float = 0f, fmt: (Float) -> String = { v -> (if (v > 0 && min < 0) "+" else "") + v.roundToInt() },
                       onSet: (Float) -> Unit): SeekBar {
        val steps = ((max - min) / step).roundToInt()
        val lab = TextView(c).apply { textSize = 13f; setTextColor(Ui.ink); setPadding(dp(2), dp(6), 0, 0) }
        fun show(v: Float) { lab.text = "$name: ${fmt(v)}" }
        show(value)
        val sb = SeekBar(c).apply {
            this.max = steps; progress = ((value - min) / step).roundToInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) { show(min + pr * step) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onSet(min + (s?.progress ?: 0) * step) }
            })
        }
        lab.setOnClickListener(object : View.OnClickListener {
            var last = 0L
            override fun onClick(v: View?) {
                val now = System.currentTimeMillis()
                if (now - last < 350) { sb.progress = ((def - min) / step).roundToInt(); show(def); onSet(def) }
                last = now
            }
        })
        parent.addView(lab); parent.addView(sb)
        return sb
    }

    private fun chipRow(parent: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit): List<Button> {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val sc = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        val btns = ArrayList<Button>()
        labels.forEachIndexed { i, t ->
            val b = Ui.button(c, t, false) { onPick(i) }
            Film.paintChip(c, b, i == selected)
            btns += b
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }
        parent.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return btns
    }

    private fun buttons(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (t, f) ->
            row.addView(Ui.button(c, t, false, f), LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i < items.size - 1) rightMargin = dp(6) })
        }
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    // ---------------------------------------------------------------- MÀU

    private var cbZone = 0
    private var colorSel = 0
    private var pickedColors = ArrayList<ColorAdj>()

    /** Danh sách dải màu đang hiện: 8 dải cơ bản (đè bằng chỉnh đã lưu) + màu đã chọn bằng ống hút. */
    private fun colorList(): List<ColorAdj> {
        val saved = p.colors
        val basic = ColorAdj.BASIC.map { b -> saved.firstOrNull { it.name == b.name } ?: b }
        val picked = saved.filter { s -> ColorAdj.BASIC.none { it.name == s.name } }
        return basic + picked
    }

    private fun setColor(i: Int, a: ColorAdj) {
        val l = colorList().toMutableList()
        if (i < l.size) l[i] = a else l += a
        update(p.copy(colors = l.filter { !it.isZero || ColorAdj.BASIC.none { b -> b.name == it.name } }))
    }

    private fun buildColor() {
        val v = color; v.removeAllViews()
        // Color Balance
        v.addView(title("Color Balance"))
        v.addView(note("Kéo điểm trong vòng màu để nhuộm vùng chọn; chạm đúp vòng để đặt lại."))
        val zones = listOf("Chung", "Vùng tối", "Trung tính", "Vùng sáng")
        chipRow(v, zones, cbZone) { cbZone = it; buildColor() }
        val k = cbZone * 3
        val wheel = WheelView(c, p.cb[k], p.cb[k + 1]) { hue, amt ->
            val cb = p.cb.copyOf(); cb[k] = hue; cb[k + 1] = amt; update(p.copy(cb = cb))
        }
        v.addView(wheel, LinearLayout.LayoutParams(dp(190), dp(190)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6) })
        slider(v, "Độ sáng ${zones[cbZone].lowercase()}", -50f, 50f, p.cb[k + 2]) { x ->
            val cb = p.cb.copyOf(); cb[k + 2] = x; update(p.copy(cb = cb))
        }

        // Color Editor
        v.addView(title("Color Editor"))
        v.addView(note("Chọn dải màu (hoặc dùng ống hút chạm lên ảnh), rồi đổi tông / độ đậm / độ sáng chỉ cho dải đó."))
        val list = colorList()
        if (colorSel >= list.size) colorSel = 0
        val labels = list.map { a -> (if (!a.isZero) "● " else "") + a.name.ifBlank { "Màu ${a.hue.roundToInt()}°" } } + "＋ Ống hút"
        chipRow(v, labels, colorSel) { i ->
            if (i == list.size) {
                host.pickColor { lab ->
                    val hue = Math.toDegrees(atan2(lab[2], lab[1])).let { if (it < 0) it + 360 else it }.toFloat()
                    val adj = ColorAdj(hue, 18f, name = "Màu ${hue.roundToInt()}°")
                    colorSel = list.size
                    update(p.copy(colors = p.colors + adj), render = false)
                    buildColor()
                }
            } else { colorSel = i; buildColor() }
        }
        val a = list[colorSel]
        v.addView(swatch(a.hue))
        slider(v, "Tông (Hue)", -30f, 30f, a.dHue) { x -> setColor(colorSel, a.copy(dHue = x)) }
        slider(v, "Độ đậm", -100f, 100f, a.dSat) { x -> setColor(colorSel, a.copy(dSat = x)) }
        slider(v, "Độ sáng", -100f, 100f, a.dLight) { x -> setColor(colorSel, a.copy(dLight = x)) }
        slider(v, "Độ rộng dải", 6f, 60f, a.width, def = 25f, fmt = { "${it.roundToInt()}°" }) { x -> setColor(colorSel, a.copy(width = x)) }
        buttons(v, "Đặt lại dải này" to { setColor(colorSel, a.copy(dHue = 0f, dSat = 0f, dLight = 0f)); buildColor() },
            "Xóa màu đã chọn" to {
                if (ColorAdj.BASIC.none { it.name == a.name }) { update(p.copy(colors = p.colors.filter { it !== a && it.name != a.name })); colorSel = 0; buildColor() }
            })

        // Skin Tone
        v.addView(title("Skin Tone"))
        v.addView(note("Chọn màu da (tự động theo khuôn mặt hoặc ống hút), rồi làm đều: các vết ửng đỏ, vàng, loang sáng tối được kéo về cùng một màu da."))
        val sk = p.skin ?: SkinToneAdj()
        buttons(v, "Lấy da tự động" to {
            val s = host.skinLab()
            if (s == null) Ui.toast(c, "Không thấy người trong ảnh, dùng ống hút")
            else { update(p.copy(skin = sk.copy(L = s[0], a = s[1], b = s[2]))); buildColor() }
        }, "Ống hút chọn da" to {
            host.pickColor { lab -> update(p.copy(skin = sk.copy(L = lab[0].toFloat(), a = lab[1].toFloat(), b = lab[2].toFloat()))); buildColor() }
        })
        v.addView(swatchLab(sk.L, sk.a, sk.b))
        slider(v, "Đều tông", 0f, 100f, sk.uHue) { x -> update(p.copy(skin = (p.skin ?: sk).copy(uHue = x))) }
        slider(v, "Đều độ đậm", 0f, 100f, sk.uSat) { x -> update(p.copy(skin = (p.skin ?: sk).copy(uSat = x))) }
        slider(v, "Đều độ sáng", 0f, 100f, sk.uLight) { x -> update(p.copy(skin = (p.skin ?: sk).copy(uLight = x))) }
        slider(v, "Tông da", -20f, 20f, sk.dHue) { x -> update(p.copy(skin = (p.skin ?: sk).copy(dHue = x))) }
        slider(v, "Độ đậm da", -60f, 60f, sk.dSat) { x -> update(p.copy(skin = (p.skin ?: sk).copy(dSat = x))) }
        slider(v, "Độ sáng da", -60f, 60f, sk.dLight) { x -> update(p.copy(skin = (p.skin ?: sk).copy(dLight = x))) }
        slider(v, "Độ rộng dải da", 8f, 45f, sk.width, def = 22f, fmt = { "${it.roundToInt()}°" }) { x -> update(p.copy(skin = (p.skin ?: sk).copy(width = x))) }

        // Bão hòa, cân bằng trắng tinh
        v.addView(title("Độ bão hòa & cân bằng trắng"))
        slider(v, "Saturation", -100f, 100f, p.saturation.toFloat()) { x -> update(p.copy(saturation = x.roundToInt())) }
        slider(v, "Vibrance", -100f, 100f, p.vibrance.toFloat()) { x -> update(p.copy(vibrance = x.roundToInt())) }
        slider(v, "Nhiệt độ (ấm / lạnh)", -100f, 100f, p.temp.toFloat()) { x -> update(p.copy(temp = x.roundToInt())) }
        slider(v, "Tint (lục / hồng)", -100f, 100f, p.tint.toFloat()) { x -> update(p.copy(tint = x.roundToInt())) }
    }

    private fun labColor(L: Double, a: Double, b: Double): Int {
        val o = LabMath.fromLab(L, a, b)
        return Color.rgb((o[0].coerceIn(0.0, 1.0) * 255).toInt(), (o[1].coerceIn(0.0, 1.0) * 255).toInt(), (o[2].coerceIn(0.0, 1.0) * 255).toInt())
    }

    private fun swatch(hue: Float): View = View(c).apply {
        val hr = Math.toRadians(hue.toDouble())
        background = Ui.rounded(c, labColor(62.0, 45 * cos(hr), 45 * sin(hr)), 6)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(6) }
    }

    private fun swatchLab(L: Float, a: Float, b: Float): View = View(c).apply {
        background = Ui.rounded(c, labColor(L.toDouble(), a.toDouble(), b.toDouble()), 6)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(6) }
    }

    // ---------------------------------------------------------------- TÔNG

    private var curveCh = 0

    private fun buildTone() {
        val v = tone; v.removeAllViews()
        v.addView(title("Exposure"))
        slider(v, "Phơi sáng", -3f, 3f, p.exposure, 0.05f, fmt = { (if (it > 0) "+" else "") + PhotoInfoBuilder.fmt(it.toDouble(), 2) + " EV" }) { x -> update(p.copy(exposure = x)) }
        slider(v, "Contrast", -100f, 100f, p.contrast.toFloat()) { x -> update(p.copy(contrast = x.roundToInt())) }
        slider(v, "Brightness", -100f, 100f, p.brightness.toFloat()) { x -> update(p.copy(brightness = x.roundToInt())) }

        v.addView(title("High Dynamic Range"))
        slider(v, "Highlight (cứu vùng sáng)", -100f, 100f, p.highlight.toFloat()) { x -> update(p.copy(highlight = x.roundToInt())) }
        slider(v, "Shadow (mở vùng tối)", -100f, 100f, p.shadow.toFloat()) { x -> update(p.copy(shadow = x.roundToInt())) }
        slider(v, "White", -100f, 100f, p.white.toFloat()) { x -> update(p.copy(white = x.roundToInt())) }
        slider(v, "Black", -100f, 100f, p.black.toFloat()) { x -> update(p.copy(black = x.roundToInt())) }

        v.addView(title("Clarity"))
        slider(v, "Clarity", -100f, 100f, p.clarity.toFloat()) { x -> update(p.copy(clarity = x.roundToInt())) }
        slider(v, "Structure", -100f, 100f, p.structure.toFloat()) { x -> update(p.copy(structure = x.roundToInt())) }
        slider(v, "Dehaze (khử sương)", -100f, 100f, p.dehaze.toFloat()) { x -> update(p.copy(dehaze = x.roundToInt())) }

        v.addView(title("Levels"))
        slider(v, "Điểm đen vào", 0f, 120f, p.lvIn0.toFloat()) { x -> update(p.copy(lvIn0 = x.roundToInt())) }
        slider(v, "Điểm trắng vào", 135f, 255f, p.lvIn1.toFloat(), def = 255f, fmt = { "${it.roundToInt()}" }) { x -> update(p.copy(lvIn1 = x.roundToInt())) }
        slider(v, "Trung tính (gamma)", 0.3f, 3f, p.lvGamma, 0.01f, def = 1f, fmt = { PhotoInfoBuilder.fmt(it.toDouble(), 2) }) { x -> update(p.copy(lvGamma = x)) }
        slider(v, "Đen ra", 0f, 120f, p.lvOut0.toFloat()) { x -> update(p.copy(lvOut0 = x.roundToInt())) }
        slider(v, "Trắng ra", 135f, 255f, p.lvOut1.toFloat(), def = 255f, fmt = { "${it.roundToInt()}" }) { x -> update(p.copy(lvOut1 = x.roundToInt())) }

        v.addView(title("Curve"))
        v.addView(note("Chạm để thêm điểm, kéo để chỉnh, chạm đúp một điểm để xóa."))
        val chs = listOf("RGB", "Luma", "R", "G", "B")
        chipRow(v, chs, curveCh) { curveCh = it; buildTone() }
        val cur = when (curveCh) { 0 -> p.curveRgb; 1 -> p.curveLuma; 2 -> p.curveR; 3 -> p.curveG; else -> p.curveB }
        val col = when (curveCh) { 2 -> Color.rgb(255, 90, 90); 3 -> Color.rgb(90, 220, 110); 4 -> Color.rgb(100, 150, 255); else -> Ui.ink }
        val cv = CurveView(c, cur, col, host.histogram()) { pts ->
            update(when (curveCh) {
                0 -> p.copy(curveRgb = pts); 1 -> p.copy(curveLuma = pts); 2 -> p.copy(curveR = pts); 3 -> p.copy(curveG = pts); else -> p.copy(curveB = pts)
            })
        }
        v.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(6) })
        buttons(v, "Đặt lại đường này" to {
            update(when (curveCh) {
                0 -> p.copy(curveRgb = Curves.ID); 1 -> p.copy(curveLuma = Curves.ID); 2 -> p.copy(curveR = Curves.ID); 3 -> p.copy(curveG = Curves.ID); else -> p.copy(curveB = Curves.ID)
            }); buildTone()
        }, "Đặt lại cả tab" to {
            update(p.copy(exposure = 0f, contrast = 0, brightness = 0, highlight = 0, shadow = 0, white = 0, black = 0,
                clarity = 0, structure = 0, dehaze = 0, lvIn0 = 0, lvIn1 = 255, lvGamma = 1f, lvOut0 = 0, lvOut1 = 255,
                curveRgb = Curves.ID, curveLuma = Curves.ID, curveR = Curves.ID, curveG = Curves.ID, curveB = Curves.ID)); buildTone()
        })
    }

    // ---------------------------------------------------------------- CHI TIẾT

    private fun buildDetail() {
        val v = detail; v.removeAllViews()
        v.addView(title("Sharpening"))
        slider(v, "Mức nét", 0f, 100f, p.sharpen.toFloat()) { x -> update(p.copy(sharpen = x.roundToInt())) }
        slider(v, "Bán kính", 0.3f, 3f, p.sharpRadius, 0.1f, def = 1f, fmt = { PhotoInfoBuilder.fmt(it.toDouble(), 1) + " px" }) { x -> update(p.copy(sharpRadius = x)) }
        slider(v, "Ngưỡng", 0f, 20f, p.sharpThreshold.toFloat(), def = 1f, fmt = { "${it.roundToInt()}" }) { x -> update(p.copy(sharpThreshold = x.roundToInt())) }
        v.addView(note("Nét chỉ tác động lên độ sáng, có hãm quầng sáng ở cạnh mạnh. Xem ở 100% (chạm đúp ảnh) để đánh giá."))
        v.addView(title("Noise Reduction"))
        slider(v, "Nhiễu sáng", 0f, 100f, p.nrLuma.toFloat()) { x -> update(p.copy(nrLuma = x.roundToInt())) }
        slider(v, "Nhiễu màu", 0f, 100f, p.nrColor.toFloat()) { x -> update(p.copy(nrColor = x.roundToInt())) }
        v.addView(title("Film Grain"))
        slider(v, "Hạt film", 0f, 100f, p.grain.toFloat()) { x -> update(p.copy(grain = x.roundToInt())) }
        slider(v, "Cỡ hạt", 0f, 100f, p.grainSize.toFloat(), def = 30f, fmt = { "${it.roundToInt()}" }) { x -> update(p.copy(grainSize = x.roundToInt())) }
        v.addView(title("Vignetting"))
        slider(v, "Vignette (− tối góc / + sáng góc)", -100f, 100f, p.vignette.toFloat()) { x -> update(p.copy(vignette = x.roundToInt())) }
        v.addView(note("Vignette tính theo khung đã cắt."))
    }

    // ---------------------------------------------------------------- ỐNG KÍNH

    private fun buildLens() {
        val v = lens; v.removeAllViews()
        v.addView(title("Lens Correction"))
        slider(v, "Méo (− gối / + thùng)", -100f, 100f, p.distortion.toFloat()) { x -> update(p.copy(distortion = x.roundToInt())) }
        v.addView(title("Keystone"))
        slider(v, "Dọc (chỉnh nhà / cột bị ngả)", -100f, 100f, p.keystoneV.toFloat()) { x -> update(p.copy(keystoneV = x.roundToInt())) }
        slider(v, "Ngang", -100f, 100f, p.keystoneH.toFloat()) { x -> update(p.copy(keystoneH = x.roundToInt())) }
        v.addView(note("Ảnh tự phóng nhẹ để ẩn phần trống ở mép. Ảnh JPEG của A7C II đã được máy sửa méo ống kính; dùng mục này cho hiệu ứng hoặc ống kính ngoài. Xóa viền tím nằm ở tab Cơ bản."))
    }

    // ---------------------------------------------------------------- LAYER

    private var layerSel = 0
    var drawing = false; private set
    var erase = false; private set
    var brushSize = 0.06f; private set
    var brushHard = 0.4f; private set
    var brushFlow = 1f; private set
    private var maskShown = false

    fun selectedLayer(): Int = if (p.layers.isEmpty()) -1 else layerSel.coerceIn(0, p.layers.size - 1)

    fun setLayer(i: Int, l: Layer, render: Boolean = true) {
        val list = p.layers.toMutableList(); list[i] = l
        update(p.copy(layers = list), render)
    }

    /** Dừng vẽ (khi rời tab). */
    fun stopDrawing() {
        if (drawing) { drawing = false; host.editMask(-1, ""); buildLayers() }
    }

    private fun buildLayers() {
        val v = layers; v.removeAllViews()
        v.addView(title("Layer & mask"))
        v.addView(note("Mỗi layer có mask riêng (vẽ cọ, gradient, chọn bằng AI) và thông số riêng chỉ áp trong mask."))
        val names = p.layers.mapIndexed { i, l -> (if (!l.on) "○ " else "") + l.name } + "＋ Layer"
        chipRow(v, names, selectedLayer()) { i ->
            if (i == p.layers.size) {
                val l = Layer("Layer ${p.layers.size + 1}", MaskSpec("brush"), DevelopParams())
                layerSel = p.layers.size
                update(p.copy(layers = p.layers + l), render = false)
            } else layerSel = i
            stopDrawing(); buildLayers()
        }
        val li = selectedLayer()
        if (li < 0) { v.addView(note("Chưa có layer. Bấm ＋ Layer để thêm.")); return }
        val L = p.layers[li]

        v.addView(title("Mask"))
        val kinds = MaskSpec.KINDS
        chipRow(v, kinds.map { it.second }, kinds.indexOfFirst { it.first == L.mask.kind }) { k ->
            val kind = kinds[k].first
            val pr = when (kind) {
                "linear" -> floatArrayOf(0.5f, 0.15f, 0.5f, 0.6f, 0f, 0f)
                "radial" -> floatArrayOf(0.5f, 0.5f, 0.25f, 0.25f, 0.5f, 0f)
                "luma" -> floatArrayOf(0.6f, 1f, 0.1f, 0f, 0f, 0f)
                "color" -> floatArrayOf(55f, 20f, 0f, 0f, 0f, 0f)
                else -> L.mask.p
            }
            setLayer(li, L.copy(mask = L.mask.copy(kind = kind, p = pr)))
            stopDrawing(); buildLayers()
            if (maskShown) host.showMask(li)
        }
        when (L.mask.kind) {
            "brush", "linear", "radial" -> {
                buttons(v, (if (drawing) "✓ Xong vẽ" else when (L.mask.kind) {
                    "brush" -> "✎ Vẽ mask"; "linear" -> "✎ Kéo gradient"; else -> "✎ Kéo vòng tròn" }) to {
                    drawing = !drawing
                    host.editMask(if (drawing) li else -1, L.mask.kind)
                    buildLayers()
                }, (if (maskShown) "Ẩn mask" else "Xem mask") to { maskShown = !maskShown; host.showMask(if (maskShown) li else -1); buildLayers() })
                if (L.mask.kind == "brush") {
                    val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(CheckBox(c).apply { text = "Tẩy"; setTextColor(Ui.ink); isChecked = erase; setOnCheckedChangeListener { _, on -> erase = on } })
                    row.addView(Ui.button(c, "Xóa hết nét", false) { setLayer(li, L.copy(mask = L.mask.copy(strokes = emptyList()))); if (maskShown) host.showMask(li) },
                        LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(10) })
                    v.addView(row)
                    slider(v, "Cỡ cọ", 1f, 30f, brushSize * 100, def = 6f, fmt = { "${it.roundToInt()}%" }) { x -> brushSize = x / 100f }
                    slider(v, "Độ cứng", 0f, 95f, brushHard * 100, def = 40f, fmt = { "${it.roundToInt()}%" }) { x -> brushHard = x / 100f }
                    slider(v, "Lưu lượng", 5f, 100f, brushFlow * 100, def = 100f, fmt = { "${it.roundToInt()}%" }) { x -> brushFlow = x / 100f }
                    v.addView(note("Khi đang vẽ: một ngón vẽ, hai ngón phóng to / kéo ảnh."))
                }
                if (L.mask.kind == "radial") slider(v, "Độ mềm mép", 2f, 100f, L.mask.p[4] * 100, def = 50f, fmt = { "${it.roundToInt()}%" }) { x ->
                    val pr = L.mask.p.copyOf(); pr[4] = x / 100f; setLayer(li, L.copy(mask = L.mask.copy(p = pr)))
                }
            }
            "color" -> {
                buttons(v, "Ống hút chọn màu" to {
                    host.pickColor { lab ->
                        val hue = Math.toDegrees(atan2(lab[2], lab[1])).let { if (it < 0) it + 360 else it }.toFloat()
                        val pr = L.mask.p.copyOf(); pr[0] = hue; setLayer(li, L.copy(mask = L.mask.copy(p = pr))); buildLayers()
                        if (maskShown) host.showMask(li)
                    }
                }, (if (maskShown) "Ẩn mask" else "Xem mask") to { maskShown = !maskShown; host.showMask(if (maskShown) li else -1); buildLayers() })
                v.addView(swatch(L.mask.p[0]))
                slider(v, "Độ rộng dải màu", 5f, 60f, L.mask.p[1], def = 20f, fmt = { "${it.roundToInt()}°" }) { x ->
                    val pr = L.mask.p.copyOf(); pr[1] = x; setLayer(li, L.copy(mask = L.mask.copy(p = pr))); if (maskShown) host.showMask(li)
                }
            }
            "luma" -> {
                buttons(v, (if (maskShown) "Ẩn mask" else "Xem mask") to { maskShown = !maskShown; host.showMask(if (maskShown) li else -1); buildLayers() })
                slider(v, "Sáng từ", 0f, 100f, L.mask.p[0] * 100, def = 60f, fmt = { "${it.roundToInt()}%" }) { x ->
                    val pr = L.mask.p.copyOf(); pr[0] = x / 100f; setLayer(li, L.copy(mask = L.mask.copy(p = pr))); if (maskShown) host.showMask(li)
                }
                slider(v, "Sáng đến", 0f, 100f, L.mask.p[1] * 100, def = 100f, fmt = { "${it.roundToInt()}%" }) { x ->
                    val pr = L.mask.p.copyOf(); pr[1] = x / 100f; setLayer(li, L.copy(mask = L.mask.copy(p = pr))); if (maskShown) host.showMask(li)
                }
            }
            else -> {
                buttons(v, (if (maskShown) "Ẩn mask" else "Xem mask") to { maskShown = !maskShown; host.showMask(if (maskShown) li else -1); buildLayers() })
                if (L.mask.kind == "sky") v.addView(note("Bầu trời được nhận theo màu xanh / trắng sáng nối với mép trên ảnh, trừ vùng người."))
            }
        }
        v.addView(CheckBox(c).apply {
            text = "Đảo mask"; setTextColor(Ui.ink); isChecked = L.mask.invert
            setOnCheckedChangeListener { _, on -> setLayer(li, L.copy(mask = L.mask.copy(invert = on))); buildLayers(); if (maskShown) host.showMask(li) }
        })

        v.addView(title("Chỉnh trong layer"))
        val d = L.dev
        fun setDev(nd: DevelopParams) = setLayer(li, p.layers[li].copy(dev = nd))
        slider(v, "Độ mờ layer", 0f, 100f, L.opacity.toFloat(), def = 100f, fmt = { "${it.roundToInt()}%" }) { x -> setLayer(li, p.layers[li].copy(opacity = x.roundToInt())) }
        slider(v, "Phơi sáng", -3f, 3f, d.exposure, 0.05f, fmt = { (if (it > 0) "+" else "") + PhotoInfoBuilder.fmt(it.toDouble(), 2) + " EV" }) { x -> setDev(p.layers[li].dev.copy(exposure = x)) }
        slider(v, "Contrast", -100f, 100f, d.contrast.toFloat()) { x -> setDev(p.layers[li].dev.copy(contrast = x.roundToInt())) }
        slider(v, "Brightness", -100f, 100f, d.brightness.toFloat()) { x -> setDev(p.layers[li].dev.copy(brightness = x.roundToInt())) }
        slider(v, "Highlight", -100f, 100f, d.highlight.toFloat()) { x -> setDev(p.layers[li].dev.copy(highlight = x.roundToInt())) }
        slider(v, "Shadow", -100f, 100f, d.shadow.toFloat()) { x -> setDev(p.layers[li].dev.copy(shadow = x.roundToInt())) }
        slider(v, "Clarity", -100f, 100f, d.clarity.toFloat()) { x -> setDev(p.layers[li].dev.copy(clarity = x.roundToInt())) }
        slider(v, "Saturation", -100f, 100f, d.saturation.toFloat()) { x -> setDev(p.layers[li].dev.copy(saturation = x.roundToInt())) }
        slider(v, "Nhiệt độ", -100f, 100f, d.temp.toFloat()) { x -> setDev(p.layers[li].dev.copy(temp = x.roundToInt())) }
        slider(v, "Tint", -100f, 100f, d.tint.toFloat()) { x -> setDev(p.layers[li].dev.copy(tint = x.roundToInt())) }
        v.addView(note("Nhuộm màu trong layer (Color Balance):"))
        val wheel = WheelView(c, d.cb[0], d.cb[1]) { hue, amt ->
            val cb = p.layers[li].dev.cb.copyOf(); cb[0] = hue; cb[1] = amt; setDev(p.layers[li].dev.copy(cb = cb))
        }
        v.addView(wheel, LinearLayout.LayoutParams(dp(160), dp(160)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6) })

        buttons(v, (if (L.on) "Tắt layer" else "Bật layer") to { setLayer(li, L.copy(on = !L.on)); buildLayers() },
            "Đổi tên" to { rename(li) },
            "Xóa layer" to {
                stopDrawing(); host.showMask(-1); maskShown = false
                update(p.copy(layers = p.layers.filterIndexed { i, _ -> i != li })); layerSel = 0; buildLayers()
            })
    }

    private fun rename(li: Int) {
        val e = EditText(c).apply { setText(p.layers[li].name) }
        AlertDialog.Builder(c).setTitle("Tên layer").setView(e)
            .setPositiveButton("Lưu") { _, _ -> setLayer(li, p.layers[li].copy(name = e.text.toString().ifBlank { "Layer" }), false); buildLayers() }
            .setNegativeButton("Hủy", null).show()
    }

    // ---- nhận thao tác vẽ từ ảnh (toạ độ ảnh gốc 0..1) ----
    private var curStroke = ArrayList<Float>()
    private var dragStart: FloatArray? = null

    /** action: 0 chạm, 1 kéo, 2 nhấc, 3 hủy. Trả về đường dẫn để vẽ lên ảnh (toạ độ ảnh gốc). */
    fun pen(action: Int, x: Float, y: Float): FloatArray? {
        val li = selectedLayer(); if (li < 0) return null
        val L = p.layers[li]
        when (L.mask.kind) {
            "brush" -> {
                when (action) {
                    0 -> { curStroke = arrayListOf(x, y) }
                    1 -> { curStroke += x; curStroke += y }
                    2 -> {
                        curStroke += x; curStroke += y
                        val s = Stroke(curStroke.toFloatArray(), brushSize, brushHard, erase, brushFlow)
                        setLayer(li, L.copy(mask = L.mask.copy(strokes = L.mask.strokes + s)))
                        if (maskShown) host.showMask(li)
                        curStroke = ArrayList()
                    }
                    3 -> curStroke = ArrayList()
                }
                return floatArrayOf(3f, x, y, brushSize)
            }
            "linear", "radial" -> {
                when (action) {
                    0 -> dragStart = floatArrayOf(x, y)
                    1, 2 -> {
                        val s = dragStart ?: return null
                        val pr = L.mask.p.copyOf()
                        if (L.mask.kind == "linear") { pr[0] = s[0]; pr[1] = s[1]; pr[2] = x; pr[3] = y }
                        else { pr[0] = s[0]; pr[1] = s[1]; pr[2] = kotlin.math.abs(x - s[0]).coerceAtLeast(0.01f); pr[3] = kotlin.math.abs(y - s[1]).coerceAtLeast(0.01f) }
                        if (action == 2) {
                            setLayer(li, L.copy(mask = L.mask.copy(p = pr)))
                            if (maskShown) host.showMask(li)
                            dragStart = null
                        }
                        return guideFor(L.mask.kind, pr)
                    }
                    3 -> dragStart = null
                }
                return guideFor(L.mask.kind, L.mask.p)
            }
        }
        return null
    }

    fun guideFor(kind: String, pr: FloatArray): FloatArray? = when (kind) {
        "linear" -> {
            // hai đường vuông góc ở đầu và cuối gradient
            val dx = pr[2] - pr[0]; val dy = pr[3] - pr[1]
            val len = max(1e-4f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
            val nx = -dy / len * 0.5f; val ny = dx / len * 0.5f
            floatArrayOf(1f, pr[0] - nx, pr[1] - ny, pr[0] + nx, pr[1] + ny, 1f, pr[2] - nx, pr[3] - ny, pr[2] + nx, pr[3] + ny,
                1f, pr[0], pr[1], pr[2], pr[3])
        }
        "radial" -> floatArrayOf(2f, pr[0], pr[1], pr[2], pr[3])
        else -> null
    }

    // ---------------------------------------------------------------- STYLE

    fun buildStyles() {
        val v = styles; v.removeAllViews()
        v.addView(title("Styles"))
        v.addView(note("Style lưu toàn bộ chỉnh sửa (công thức màu, da, tự động, màu, tông, chi tiết, layer...) để áp nhanh cho ảnh khác hoặc khi chỉnh hàng loạt. Không gồm khung cắt."))
        buttons(v, "＋ Lưu Style hiện tại" to {
            val e = EditText(c).apply { hint = "Tên Style" }
            AlertDialog.Builder(c).setTitle("Lưu Style").setView(e)
                .setPositiveButton("Lưu") { _, _ ->
                    val n = e.text.toString().ifBlank { "Style ${StyleStore.list(c).size + 1}" }
                    StyleStore.add(c, host.currentStyle(n)); buildStyles(); Ui.toast(c, "Đã lưu Style \"$n\"")
                }.setNegativeButton("Hủy", null).show()
            Unit
        })
        val list = StyleStore.list(c)
        if (list.isEmpty()) v.addView(note("Chưa có Style nào."))
        for (s in list) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(Ui.button(c, "▶ ${s.name}", false) { host.applyStyle(s); Ui.toast(c, "Đã áp Style \"${s.name}\"") },
                LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
            row.addView(Ui.button(c, "✕", false) {
                AlertDialog.Builder(c).setMessage("Xóa Style \"${s.name}\"?")
                    .setPositiveButton("Xóa") { _, _ -> StyleStore.remove(c, s.name); buildStyles() }
                    .setNegativeButton("Hủy", null).show()
            }, LinearLayout.LayoutParams(dp(48), dp(38)))
            v.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        v.addView(title("Sao chép / dán chỉnh sửa"))
        buttons(v, "Sao chép chỉnh sửa" to { StyleStore.copy(c, host.currentStyle("Bản sao")); Ui.toast(c, "Đã sao chép. Mở ảnh khác rồi bấm Dán.") },
            "Dán chỉnh sửa" to {
                val s = StyleStore.clip(c)
                if (s == null) Ui.toast(c, "Chưa sao chép chỉnh sửa nào") else { host.applyStyle(s); Ui.toast(c, "Đã dán chỉnh sửa") }
            })
        buttons(v, "Đặt lại Develop (Màu, Tông, Chi tiết, Ống kính, Layer)" to {
            AlertDialog.Builder(c).setMessage("Đặt lại toàn bộ chỉnh sửa ở các tab Màu, Tông, Chi tiết, Ống kính, Layer?")
                .setPositiveButton("Đặt lại") { _, _ -> stopDrawing(); host.showMask(-1); setParams(DevelopParams()); host.developChanged() }
                .setNegativeButton("Hủy", null).show()
            Unit
        })
    }
}

// =====================================================================
//  Vòng màu (Color Balance)
// =====================================================================

class WheelView(c: Context, hue0: Float, amount0: Float, private val onSet: (Float, Float) -> Unit) : View(c) {
    private var hue = hue0; private var amt = amount0
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1714") }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.line; strokeWidth = 2f }
    private var last = 0L

    private fun labHueColor(h: Double): Int {
        val r = Math.toRadians(h)
        val o = LabMath.fromLab(62.0, 48 * cos(r), 48 * sin(r))
        return Color.rgb((o[0].coerceIn(0.0, 1.0) * 255).toInt(), (o[1].coerceIn(0.0, 1.0) * 255).toInt(), (o[2].coerceIn(0.0, 1.0) * 255).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // màn hình: góc tính theo chiều kim đồng hồ từ trục x; ta vẽ hue Lab ngược chiều (y lên trên)
        val cols = IntArray(37) { labHueColor(-it * 10.0) }
        ring.shader = SweepGradient(w / 2f, h / 2f, cols, null)
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy) - 4f
        cv.drawCircle(cx, cy, r, ring)
        cv.drawCircle(cx, cy, r * 0.8f, disc)
        cv.drawLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, cross); cv.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, cross)
        val hr = Math.toRadians(hue.toDouble())
        val d = r * 0.8f * amt / 100f
        cv.drawCircle(cx + (d * cos(hr)).toFloat(), cy - (d * sin(hr)).toFloat(), 12f, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy) * 0.8f
        val dx = e.x - cx; val dy = cy - e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - last < 300) { hue = 0f; amt = 0f; invalidate(); onSet(0f, 0f); last = 0; return true }
                last = now
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) {
            hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }.toFloat()
            amt = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / r * 100f).coerceIn(0f, 100f)
            invalidate()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) onSet(hue, amt)
        return true
    }
}

// =====================================================================
//  Đường cong (Curve)
// =====================================================================

class CurveView(c: Context, pts0: FloatArray, private val color: Int, private val hist: Analysis.Hist?,
                private val onSet: (FloatArray) -> Unit) : View(c) {
    private val pts = ArrayList<FloatArray>().apply { for (i in 0 until pts0.size / 2) add(floatArrayOf(pts0[i * 2], pts0[i * 2 + 1])) }
    private val grid = Paint().apply { this.color = Ui.line; strokeWidth = 1.5f }
    private val bg = Paint().apply { this.color = Color.parseColor("#0C0C0C") }
    private val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(60, 200, 200, 200) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var drag = -1
    private var lastTap = 0L; private var lastIdx = -1
    private val pad = 14f

    private fun sx(x: Float) = pad + x * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y * (height - 2 * pad)
    private fun ix(px: Float) = ((px - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
    private fun iy(py: Float) = ((height - pad - py) / (height - 2 * pad)).coerceIn(0f, 1f)

    private fun flat() = FloatArray(pts.size * 2).also { a -> pts.forEachIndexed { i, p -> a[i * 2] = p[0]; a[i * 2 + 1] = p[1] } }

    override fun onDraw(cv: Canvas) {
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        hist?.let { h ->
            var peak = 1.0
            for (i in 2 until 254) peak = max(peak, kotlin.math.sqrt(h.y[i].toDouble()))
            val path = Path(); path.moveTo(sx(0f), sy(0f))
            for (i in 0 until 256) path.lineTo(sx(i / 255f), sy((kotlin.math.sqrt(h.y[i].toDouble()) / peak).toFloat().coerceAtMost(1f) * 0.9f))
            path.lineTo(sx(1f), sy(0f)); path.close()
            cv.drawPath(path, hp)
        }
        for (k in 1..3) { cv.drawLine(sx(k / 4f), sy(0f), sx(k / 4f), sy(1f), grid); cv.drawLine(sx(0f), sy(k / 4f), sx(1f), sy(k / 4f), grid) }
        cv.drawLine(sx(0f), sy(0f), sx(1f), sy(1f), grid)
        val t = Curves.table(flat(), 256)
        val path = Path()
        for (i in 0 until 256) { val x = sx(i / 255f); val y = sy(t[i]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        cv.drawPath(path, line)
        for (p in pts) cv.drawCircle(sx(p[0]), sy(p[1]), 11f, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val x = ix(e.x); val y = iy(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = pts.indices.minByOrNull { hypot((sx(pts[it][0]) - e.x).toDouble(), (sy(pts[it][1]) - e.y).toDouble()) } ?: -1
                if (drag >= 0 && hypot((sx(pts[drag][0]) - e.x).toDouble(), (sy(pts[drag][1]) - e.y).toDouble()) > 48) drag = -1
                val now = System.currentTimeMillis()
                if (drag > 0 && drag < pts.size - 1 && drag == lastIdx && now - lastTap < 350) {
                    pts.removeAt(drag); drag = -1; invalidate(); onSet(flat()); lastIdx = -1; return true
                }
                lastTap = now; lastIdx = drag
                if (drag < 0) {
                    // thêm điểm mới
                    var at = pts.indexOfFirst { it[0] > x }
                    if (at <= 0) at = pts.size - 1
                    pts.add(at, floatArrayOf(x, y)); drag = at
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                val p = pts[drag]
                if (drag == 0) p[0] = min(x, pts[1][0] - 0.02f).coerceAtLeast(0f)
                else if (drag == pts.size - 1) p[0] = max(x, pts[drag - 1][0] + 0.02f).coerceAtMost(1f)
                else p[0] = x.coerceIn(pts[drag - 1][0] + 0.02f, pts[drag + 1][0] - 0.02f)
                p[1] = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (drag >= 0) { drag = -1; onSet(flat()) }
        }
        return true
    }
}
