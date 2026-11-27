package com.looknote.exif

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Bộ công cụ chỉnh ảnh kiểu Capture One:
 *  - Màu: Color Editor (dải màu cơ bản + màu chọn bằng ống hút), Skin Tone (đều màu da), Color Balance 3 vùng
 *  - Tông: Exposure, HDR (Highlight / Shadow / White / Black), Levels, Curve (RGB, Luma, R, G, B),
 *          Contrast, Brightness, Saturation, Vibrance, Clarity, Structure, Dehaze, nhiệt độ màu
 *  - Chi tiết: Sharpening, Noise Reduction, Film Grain, Vignette
 *  - Ống kính: méo (barrel / pincushion), Keystone dọc / ngang
 *  - Layer & mask: cọ vẽ, gradient tuyến tính, tròn, mask AI (da, người, nền, trời), theo màu, theo độ sáng
 */

// =====================================================================
//  Thông số
// =====================================================================

/** Một dải màu của Color Editor, theo hue Lab (độ). dHue độ, dSat / dLight −100..100. */
data class ColorAdj(
    val hue: Float, val width: Float = 25f,
    val dHue: Float = 0f, val dSat: Float = 0f, val dLight: Float = 0f,
    val name: String = ""
) {
    val isZero get() = dHue == 0f && dSat == 0f && dLight == 0f
    fun toJson() = JSONObject().put("h", hue.toDouble()).put("w", width.toDouble()).put("dh", dHue.toDouble())
        .put("ds", dSat.toDouble()).put("dl", dLight.toDouble()).put("n", name)
    companion object {
        fun fromJson(o: JSONObject) = ColorAdj(o.getDouble("h").toFloat(), o.optDouble("w", 25.0).toFloat(),
            o.optDouble("dh", 0.0).toFloat(), o.optDouble("ds", 0.0).toFloat(), o.optDouble("dl", 0.0).toFloat(), o.optString("n", ""))

        /** 8 dải màu cơ bản (hue Lab). */
        val BASIC = listOf(
            ColorAdj(30f, 22f, name = "Đỏ"), ColorAdj(58f, 18f, name = "Cam"), ColorAdj(90f, 20f, name = "Vàng"),
            ColorAdj(135f, 30f, name = "Lục"), ColorAdj(200f, 30f, name = "Lam ngọc"), ColorAdj(270f, 30f, name = "Lam"),
            ColorAdj(310f, 20f, name = "Tím"), ColorAdj(345f, 18f, name = "Hồng sen")
        )
    }
}

/** Skin Tone: màu da gốc (Lab), độ rộng dải; đều màu (0..100) và dịch màu. */
data class SkinToneAdj(
    val L: Float = 68f, val a: Float = 14f, val b: Float = 18f, val width: Float = 22f,
    val uHue: Float = 0f, val uSat: Float = 0f, val uLight: Float = 0f,
    val dHue: Float = 0f, val dSat: Float = 0f, val dLight: Float = 0f
) {
    val isZero get() = uHue == 0f && uSat == 0f && uLight == 0f && dHue == 0f && dSat == 0f && dLight == 0f
    fun toJson() = JSONObject().apply {
        put("L", L.toDouble()); put("a", a.toDouble()); put("b", b.toDouble()); put("w", width.toDouble())
        put("uh", uHue.toDouble()); put("us", uSat.toDouble()); put("ul", uLight.toDouble())
        put("dh", dHue.toDouble()); put("ds", dSat.toDouble()); put("dl", dLight.toDouble())
    }
    companion object {
        fun fromJson(o: JSONObject) = SkinToneAdj(
            o.getDouble("L").toFloat(), o.getDouble("a").toFloat(), o.getDouble("b").toFloat(), o.optDouble("w", 22.0).toFloat(),
            o.optDouble("uh", 0.0).toFloat(), o.optDouble("us", 0.0).toFloat(), o.optDouble("ul", 0.0).toFloat(),
            o.optDouble("dh", 0.0).toFloat(), o.optDouble("ds", 0.0).toFloat(), o.optDouble("dl", 0.0).toFloat())
    }
}

/** Đường cong: các điểm (x, y) 0..1 xen kẽ, đã sắp theo x. */
object Curves {
    val ID = floatArrayOf(0f, 0f, 1f, 1f)
    fun isId(c: FloatArray) = c.size == 4 && c[0] == 0f && c[1] == 0f && c[2] == 1f && c[3] == 1f

    /** Bảng 256 giá trị theo spline Hermite đơn điệu (Fritsch–Carlson). */
    fun table(c: FloatArray, n: Int = 1024): FloatArray {
        val k = c.size / 2
        val xs = FloatArray(k) { c[it * 2] }; val ys = FloatArray(k) { c[it * 2 + 1] }
        val out = FloatArray(n)
        if (k < 2) { for (i in 0 until n) out[i] = i / (n - 1f); return out }
        val d = FloatArray(k - 1) { (ys[it + 1] - ys[it]) / max(1e-5f, xs[it + 1] - xs[it]) }
        val m = FloatArray(k)
        m[0] = d[0]; m[k - 1] = d[k - 2]
        for (i in 1 until k - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until k - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        for (j in 0 until n) {
            val x = j / (n - 1f)
            out[j] = when {
                x <= xs[0] -> ys[0]
                x >= xs[k - 1] -> ys[k - 1]
                else -> {
                    var i = 0
                    while (i < k - 2 && x > xs[i + 1]) i++
                    val h = max(1e-5f, xs[i + 1] - xs[i]); val t = (x - xs[i]) / h
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                }
            }.coerceIn(0f, 1f)
        }
        return out
    }

    fun eval(t: FloatArray, v: Double): Double {
        val f = (v.coerceIn(0.0, 1.0) * (t.size - 1))
        val i = f.toInt().coerceAtMost(t.size - 2); val r = f - i
        return t[i] + (t[i + 1] - t[i]) * r
    }
}

data class DevelopParams(
    // ----- tông -----
    val exposure: Float = 0f,
    val contrast: Int = 0, val brightness: Int = 0, val saturation: Int = 0, val vibrance: Int = 0,
    val highlight: Int = 0, val shadow: Int = 0, val white: Int = 0, val black: Int = 0,
    val temp: Int = 0, val tint: Int = 0,
    val lvIn0: Int = 0, val lvIn1: Int = 255, val lvGamma: Float = 1f, val lvOut0: Int = 0, val lvOut1: Int = 255,
    val curveRgb: FloatArray = Curves.ID, val curveLuma: FloatArray = Curves.ID,
    val curveR: FloatArray = Curves.ID, val curveG: FloatArray = Curves.ID, val curveB: FloatArray = Curves.ID,
    val clarity: Int = 0, val structure: Int = 0, val dehaze: Int = 0,
    // ----- màu -----
    /** Color Balance: [chung, vùng tối, trung tính, vùng sáng] × (hue độ, mức 0..100, sáng −50..50). */
    val cb: FloatArray = FloatArray(12),
    val colors: List<ColorAdj> = emptyList(),
    val skin: SkinToneAdj? = null,
    // ----- chi tiết -----
    val sharpen: Int = 0, val sharpRadius: Float = 1f, val sharpThreshold: Int = 1,
    val nrLuma: Int = 0, val nrColor: Int = 0,
    val grain: Int = 0, val grainSize: Int = 30,
    val vignette: Int = 0,
    // ----- ống kính -----
    val distortion: Int = 0, val keystoneV: Int = 0, val keystoneH: Int = 0,
    // ----- layer -----
    val layers: List<Layer> = emptyList()
) {
    val lutOn get() = exposure != 0f || contrast != 0 || brightness != 0 || saturation != 0 || vibrance != 0 ||
        white != 0 || black != 0 || temp != 0 || tint != 0 ||
        lvIn0 != 0 || lvIn1 != 255 || lvGamma != 1f || lvOut0 != 0 || lvOut1 != 255 ||
        !Curves.isId(curveRgb) || !Curves.isId(curveLuma) || !Curves.isId(curveR) || !Curves.isId(curveG) || !Curves.isId(curveB) ||
        cb.any { it != 0f } || colors.any { !it.isZero } || (skin != null && !skin.isZero)
    val localOn get() = highlight != 0 || shadow != 0 || clarity != 0 || dehaze != 0
    val detailOn get() = sharpen != 0 || nrLuma != 0 || nrColor != 0 || grain != 0 || structure != 0
    val lensOn get() = distortion != 0 || keystoneV != 0 || keystoneH != 0
    val layersOn get() = layers.any { it.on && it.opacity > 0 }
    val on get() = lutOn || localOn || detailOn || lensOn || vignette != 0 || layersOn

    fun key(): String = toJson().toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("ex", exposure.toDouble()); put("ct", contrast); put("br", brightness); put("sa", saturation); put("vi", vibrance)
        put("hl", highlight); put("sh", shadow); put("wh", white); put("bl", black); put("tp", temp); put("tn", tint)
        put("lv", JSONArray(listOf(lvIn0, lvIn1, lvOut0, lvOut1))); put("lg", lvGamma.toDouble())
        put("cRgb", arr(curveRgb)); put("cL", arr(curveLuma)); put("cR", arr(curveR)); put("cG", arr(curveG)); put("cB", arr(curveB))
        put("cla", clarity); put("str", structure); put("dh", dehaze)
        put("cb", arr(cb))
        put("colors", JSONArray().also { j -> colors.forEach { j.put(it.toJson()) } })
        skin?.let { put("skin", it.toJson()) }
        put("shp", sharpen); put("shr", sharpRadius.toDouble()); put("sht", sharpThreshold)
        put("nrl", nrLuma); put("nrc", nrColor); put("gr", grain); put("grs", grainSize); put("vg", vignette)
        put("dist", distortion); put("kv", keystoneV); put("kh", keystoneH)
        put("layers", JSONArray().also { j -> layers.forEach { j.put(it.toJson()) } })
    }

    companion object {
        fun arr(a: FloatArray) = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
        fun fa(j: JSONArray?, def: FloatArray) = j?.let { FloatArray(it.length()) { i -> it.getDouble(i).toFloat() } } ?: def

        fun fromJson(o: JSONObject): DevelopParams {
            val lv = o.optJSONArray("lv")
            return DevelopParams(
                o.optDouble("ex", 0.0).toFloat(), o.optInt("ct"), o.optInt("br"), o.optInt("sa"), o.optInt("vi"),
                o.optInt("hl"), o.optInt("sh"), o.optInt("wh"), o.optInt("bl"), o.optInt("tp"), o.optInt("tn"),
                lv?.optInt(0, 0) ?: 0, lv?.optInt(1, 255) ?: 255, o.optDouble("lg", 1.0).toFloat(), lv?.optInt(2, 0) ?: 0, lv?.optInt(3, 255) ?: 255,
                fa(o.optJSONArray("cRgb"), Curves.ID), fa(o.optJSONArray("cL"), Curves.ID),
                fa(o.optJSONArray("cR"), Curves.ID), fa(o.optJSONArray("cG"), Curves.ID), fa(o.optJSONArray("cB"), Curves.ID),
                o.optInt("cla"), o.optInt("str"), o.optInt("dh"),
                fa(o.optJSONArray("cb"), FloatArray(12)).let { if (it.size == 12) it else FloatArray(12) },
                o.optJSONArray("colors")?.let { j -> (0 until j.length()).map { ColorAdj.fromJson(j.getJSONObject(it)) } } ?: emptyList(),
                o.optJSONObject("skin")?.let { SkinToneAdj.fromJson(it) },
                o.optInt("shp"), o.optDouble("shr", 1.0).toFloat(), o.optInt("sht", 1),
                o.optInt("nrl"), o.optInt("nrc"), o.optInt("gr"), o.optInt("grs", 30), o.optInt("vg"),
                o.optInt("dist"), o.optInt("kv"), o.optInt("kh"),
                o.optJSONArray("layers")?.let { j -> (0 until j.length()).map { Layer.fromJson(j.getJSONObject(it)) } } ?: emptyList()
            )
        }
    }

    // FloatArray trong data class: so sánh theo nội dung
    override fun equals(other: Any?) = other is DevelopParams && key() == other.key()
    override fun hashCode() = key().hashCode()
}

// =====================================================================
//  Layer & mask
// =====================================================================

/** Một nét cọ: các điểm (x, y) theo tỷ lệ ảnh gốc 0..1, bán kính theo tỷ lệ bề rộng ảnh. */
class Stroke(val pts: FloatArray, val r: Float, val hard: Float, val erase: Boolean, val flow: Float = 1f) {
    fun toJson() = JSONObject().put("p", DevelopParams.arr(pts)).put("r", r.toDouble()).put("h", hard.toDouble())
        .put("e", erase).put("f", flow.toDouble())
    companion object {
        fun fromJson(o: JSONObject) = Stroke(DevelopParams.fa(o.getJSONArray("p"), FloatArray(0)), o.getDouble("r").toFloat(),
            o.optDouble("h", 0.5).toFloat(), o.optBoolean("e"), o.optDouble("f", 1.0).toFloat())
    }
}

/**
 * Mask: kind = all | brush | linear | radial | skin | person | background | sky | color | luma.
 * p: linear (x1, y1, x2, y2) · radial (cx, cy, rx, ry, độ mềm) · color (hue, rộng) · luma (thấp, cao, mềm).
 */
class MaskSpec(
    val kind: String = "brush", val p: FloatArray = FloatArray(6),
    val strokes: List<Stroke> = emptyList(), val invert: Boolean = false
) {
    fun toJson() = JSONObject().put("k", kind).put("p", DevelopParams.arr(p)).put("i", invert)
        .put("s", JSONArray().also { j -> strokes.forEach { j.put(it.toJson()) } })
    fun copy(kind: String = this.kind, p: FloatArray = this.p, strokes: List<Stroke> = this.strokes, invert: Boolean = this.invert) =
        MaskSpec(kind, p, strokes, invert)
    companion object {
        fun fromJson(o: JSONObject) = MaskSpec(o.getString("k"), DevelopParams.fa(o.optJSONArray("p"), FloatArray(6)),
            o.optJSONArray("s")?.let { j -> (0 until j.length()).map { Stroke.fromJson(j.getJSONObject(it)) } } ?: emptyList(),
            o.optBoolean("i"))
        val KINDS = listOf("brush" to "Cọ vẽ", "linear" to "Gradient thẳng", "radial" to "Gradient tròn",
            "skin" to "AI: Da", "person" to "AI: Người", "hair" to "AI: Tóc", "clothes" to "AI: Quần áo",
            "background" to "AI: Nền", "sky" to "Bầu trời",
            "color" to "Theo màu", "luma" to "Theo độ sáng", "all" to "Toàn ảnh")
    }
}

/** Layer chỉnh cục bộ. [dev] dùng các thông số tông / màu / clarity (không dùng ống kính, chi tiết). */
class Layer(val name: String, val mask: MaskSpec, val dev: DevelopParams, val opacity: Int = 100, val on: Boolean = true) {
    fun toJson() = JSONObject().put("n", name).put("m", mask.toJson()).put("d", dev.toJson()).put("o", opacity).put("on", on)
    fun copy(name: String = this.name, mask: MaskSpec = this.mask, dev: DevelopParams = this.dev, opacity: Int = this.opacity, on: Boolean = this.on) =
        Layer(name, mask, dev, opacity, on)
    companion object {
        fun fromJson(o: JSONObject) = Layer(o.optString("n", "Layer"), MaskSpec.fromJson(o.getJSONObject("m")),
            DevelopParams.fromJson(o.getJSONObject("d")), o.optInt("o", 100), o.optBoolean("on", true))
    }
}

/** Vẽ mask ra lưới (độ phân giải xem trước). [px] là ảnh đầu vào bước Develop, cùng kích thước. */
object MaskRender {
    fun render(m: MaskSpec, w: Int, h: Int, px: IntArray?, skin: SkinMap?): FloatArray {
        val out = FloatArray(w * h)
        when (m.kind) {
            "all" -> out.fill(1f)
            "brush" -> for (s in m.strokes) stroke(out, w, h, s)
            "linear" -> {
                val x1 = m.p[0] * w; val y1 = m.p[1] * h; val x2 = m.p[2] * w; val y2 = m.p[3] * h
                val dx = x2 - x1; val dy = y2 - y1; val len2 = max(1f, dx * dx + dy * dy)
                for (y in 0 until h) for (x in 0 until w) {
                    // 1 ở phía điểm đầu, mờ dần tới 0 ở điểm cuối
                    val t = (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0f, 1f)
                    out[y * w + x] = 1f - t * t * (3f - 2f * t)
                }
            }
            "radial" -> {
                val cx = m.p[0] * w; val cy = m.p[1] * h
                val rx = max(1f, m.p[2] * w); val ry = max(1f, m.p[3] * h)
                val soft = m.p[4].coerceIn(0.02f, 1f)
                for (y in 0 until h) for (x in 0 until w) {
                    val d = sqrt(((x - cx) / rx).pow(2) + ((y - cy) / ry).pow(2))
                    val t = ((1f - d) / soft + 0f).coerceIn(0f, 1f)
                    out[y * w + x] = t * t * (3f - 2f * t)
                }
            }
            "skin" -> skin?.let { s -> if (s.w == w && s.h == h) System.arraycopy(s.m, 0, out, 0, out.size) }
            "person" -> skin?.person?.let { p -> if (skin.w == w && skin.h == h) System.arraycopy(p, 0, out, 0, out.size) }
            "hair" -> skin?.hair?.let { p -> if (skin.w == w && skin.h == h) System.arraycopy(p, 0, out, 0, out.size) }
            "clothes" -> skin?.clothes?.let { p -> if (skin.w == w && skin.h == h) System.arraycopy(p, 0, out, 0, out.size) }
            "background" -> skin?.person?.let { p -> if (skin.w == w && skin.h == h) for (i in out.indices) out[i] = 1f - p[i] }
                ?: out.fill(1f)
            "sky" -> if (px != null) sky(px, w, h, skin, out)
            "color" -> if (px != null) {
                val hue = m.p[0].toDouble(); val wd = max(4.0, m.p[1].toDouble())
                for (i in out.indices) {
                    val c = px[i]
                    val lab = LabMath.toLab(((c shr 16) and 255) / 255.0, ((c shr 8) and 255) / 255.0, (c and 255) / 255.0)
                    val ch = hypot(lab[1], lab[2])
                    var d = abs(Math.toDegrees(atan2(lab[2], lab[1])) - hue) % 360.0; if (d > 180) d = 360 - d
                    val t = ((wd * 1.4 - d) / (wd * 0.8)).coerceIn(0.0, 1.0) * ((ch - 4) / 8).coerceIn(0.0, 1.0)
                    out[i] = t.toFloat()
                }
                SkinRetouch.blur(out, w, h, max(1, w / 400))
            }
            "luma" -> if (px != null) {
                val lo = m.p[0]; val hi = max(lo + 0.01f, m.p[1]); val soft = max(0.01f, m.p[2])
                for (i in out.indices) {
                    val c = px[i]
                    val yv = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
                    val a = ((yv - (lo - soft)) / soft).coerceIn(0f, 1f); val b = (((hi + soft) - yv) / soft).coerceIn(0f, 1f)
                    out[i] = min(a, b)
                }
                SkinRetouch.blur(out, w, h, max(1, w / 500))
            }
        }
        if (m.invert) for (i in out.indices) out[i] = 1f - out[i]
        return out
    }

    private fun stroke(out: FloatArray, w: Int, h: Int, s: Stroke) {
        val r = max(1f, s.r * w)
        val hard = s.hard.coerceIn(0f, 0.95f)
        val n = s.pts.size / 2
        if (n == 0) return
        // rải các chấm dọc nét, cách nhau 1/4 bán kính
        val dots = ArrayList<Float>()
        for (k in 0 until n) {
            val x = s.pts[k * 2] * w; val y = s.pts[k * 2 + 1] * h
            if (k == 0) { dots += x; dots += y; continue }
            val px0 = s.pts[k * 2 - 2] * w; val py0 = s.pts[k * 2 - 1] * h
            val d = hypot((x - px0).toDouble(), (y - py0).toDouble()).toFloat()
            val steps = max(1, (d / (r * 0.25f)).toInt())
            for (j in 1..steps) { val t = j / steps.toFloat(); dots += px0 + (x - px0) * t; dots += py0 + (y - py0) * t }
        }
        val tmp = FloatArray(w * h)
        var i = 0
        while (i < dots.size) {
            val cx = dots[i]; val cy = dots[i + 1]
            val x0 = (cx - r).toInt().coerceIn(0, w - 1); val x1 = (cx + r).toInt().coerceIn(0, w - 1)
            val y0 = (cy - r).toInt().coerceIn(0, h - 1); val y1 = (cy + r).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() / r
                if (d >= 1f) continue
                val v = if (d <= hard) 1f else { val t = (1f - d) / (1f - hard); t * t * (3f - 2f * t) }
                val k = y * w + x
                if (v > tmp[k]) tmp[k] = v
            }
            i += 2
        }
        for (k in out.indices) {
            val v = tmp[k] * s.flow
            if (v <= 0f) continue
            out[k] = if (s.erase) out[k] * (1f - v) else out[k] + (1f - out[k]) * v
        }
    }

    /** Bầu trời (không có mô hình riêng): vùng sáng, xanh / trắng, nối với mép trên, không phải người. */
    private fun sky(px: IntArray, w: Int, h: Int, skin: SkinMap?, out: FloatArray) {
        val cand = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val c = px[i]
            val lab = LabMath.toLab(((c shr 16) and 255) / 255.0, ((c shr 8) and 255) / 255.0, (c and 255) / 255.0)
            val L = lab[0]; val b = lab[2]; val ch = hypot(lab[1], lab[2])
            val blue = (-b / 12.0).coerceIn(0.0, 1.0)
            val white = if (L > 80 && ch < 10) 1.0 else 0.0
            var v = max(blue * ((L - 35) / 20).coerceIn(0.0, 1.0), white)
            v *= (1.3 - y.toDouble() / h).coerceIn(0.0, 1.0)
            val p = skin?.person?.let { if (skin.w == w) it[i] else 0f } ?: 0f
            cand[i] = (v * (1 - p)).toFloat()
        }
        // chỉ giữ vùng nối với mép trên (lan từ trên xuống)
        val q = ArrayDeque<Int>()
        for (x in 0 until w) if (cand[x] > 0.35f) { out[x] = cand[x]; q.add(x) }
        while (q.isNotEmpty()) {
            val i = q.removeFirst(); val x = i % w; val y = i / w
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (out[j] == 0f && cand[j] > 0.35f) { out[j] = cand[j]; q.add(j) }
            }
        }
        SkinRetouch.blur(out, w, h, max(1, w / 300))
    }
}

// =====================================================================
//  Bảng màu 3D cho các phép chỉnh theo điểm ảnh
// =====================================================================

object DevelopLut {
    private const val N = 33

    /** null nếu không có phép chỉnh màu / tông nào. */
    fun build(p: DevelopParams): Lut? {
        if (!p.lutOn) return null
        val tRgb = if (Curves.isId(p.curveRgb)) null else Curves.table(p.curveRgb)
        val tR = if (Curves.isId(p.curveR)) null else Curves.table(p.curveR)
        val tG = if (Curves.isId(p.curveG)) null else Curves.table(p.curveG)
        val tB = if (Curves.isId(p.curveB)) null else Curves.table(p.curveB)
        val tL = if (Curves.isId(p.curveLuma)) null else Curves.table(p.curveLuma)
        val gain = 2.0.pow(p.exposure.toDouble())
        val kr = 1.0 + p.temp / 100.0 * 0.22; val kb = 1.0 - p.temp / 100.0 * 0.22; val kg = 1.0 - p.tint / 100.0 * 0.15
        val wn = 1.0 / (0.2126 * kr + 0.7152 * kg + 0.0722 * kb)
        val in0 = p.lvIn0 / 255.0; val in1 = max(in0 + 0.01, p.lvIn1 / 255.0)
        val o0 = p.lvOut0 / 255.0; val o1 = p.lvOut1 / 255.0
        val gam = 1.0 / p.lvGamma.toDouble().coerceIn(0.2, 5.0)
        val ct = p.contrast / 100.0; val brG = 2.0.pow(-p.brightness / 100.0 * 0.7)
        val wh = p.white / 100.0; val bl = p.black / 100.0
        val cbOn = p.cb.any { it != 0f }
        val cbA = DoubleArray(4); val cbB = DoubleArray(4); val cbL = DoubleArray(4)
        for (k in 0 until 4) {
            val hr = Math.toRadians(p.cb[k * 3].toDouble()); val amt = p.cb[k * 3 + 1] / 100.0 * 18.0
            cbA[k] = cos(hr) * amt; cbB[k] = sin(hr) * amt; cbL[k] = p.cb[k * 3 + 2] / 50.0 * 12.0
        }
        val colors = p.colors.filter { !it.isZero }
        val sk = p.skin?.takeIf { !it.isZero }
        val skH = sk?.let { Math.toDegrees(atan2(it.b.toDouble(), it.a.toDouble())) } ?: 0.0
        val skC = sk?.let { hypot(it.a.toDouble(), it.b.toDouble()) } ?: 0.0
        val sat = p.saturation / 100.0; val vib = p.vibrance / 100.0

        val data = FloatArray(N * N * N * 3)
        val v = DoubleArray(3); val zt = DoubleArray(3)
        for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
            v[0] = ri / (N - 1.0); v[1] = gi / (N - 1.0); v[2] = bi / (N - 1.0)
            // nhiệt độ màu, phơi sáng (tuyến tính)
            if (p.temp != 0 || p.tint != 0) {
                v[0] = LabMath.gam((LabMath.lin(v[0]) * kr * wn).coerceIn(0.0, 1.0))
                v[1] = LabMath.gam((LabMath.lin(v[1]) * kg * wn).coerceIn(0.0, 1.0))
                v[2] = LabMath.gam((LabMath.lin(v[2]) * kb * wn).coerceIn(0.0, 1.0))
            }
            if (gain != 1.0) AutoGrade.exposeRgb(v, gain)
            // White / Black
            if (wh != 0.0 || bl != 0.0) lumaMap(v) { y ->
                var t = y + wh * 0.35 * y.pow(3) * (1 - y) * 4
                t += bl * 0.25 * (1 - t).pow(4) * (if (bl > 0) 1.0 else t * 4).coerceAtMost(1.0)
                t
            }
            // Levels
            if (p.lvIn0 != 0 || p.lvIn1 != 255 || p.lvGamma != 1f || p.lvOut0 != 0 || p.lvOut1 != 255)
                for (c in 0 until 3) v[c] = o0 + (o1 - o0) * ((v[c] - in0) / (in1 - in0)).coerceIn(0.0, 1.0).pow(gam)
            // Contrast (đường S quanh 0,5), Brightness (gamma vùng giữa)
            if (ct != 0.0) for (c in 0 until 3) { val x = v[c]; v[c] = (x + ct * 0.55 * x * (1 - x) * (2 * x - 1)).coerceIn(0.0, 1.0) }
            if (brG != 1.0) for (c in 0 until 3) v[c] = v[c].coerceIn(0.0, 1.0).pow(brG)
            // Curves
            if (tRgb != null) for (c in 0 until 3) v[c] = Curves.eval(tRgb, v[c])
            if (tR != null) v[0] = Curves.eval(tR, v[0])
            if (tG != null) v[1] = Curves.eval(tG, v[1])
            if (tB != null) v[2] = Curves.eval(tB, v[2])
            if (tL != null) lumaMap(v) { y -> Curves.eval(tL, y) }
            // ----- màu trong Lab -----
            if (cbOn || colors.isNotEmpty() || sk != null || sat != 0.0 || vib != 0.0) {
                val lab = LabMath.toLab(v[0].coerceIn(0.0, 1.0), v[1].coerceIn(0.0, 1.0), v[2].coerceIn(0.0, 1.0))
                if (cbOn) {
                    ColorStats.zoneWeights(lab[0], zt)
                    // giữ trắng / đen thuần không bị nhuộm quá
                    val keep = (lab[0] / 8).coerceIn(0.0, 1.0) * ((100 - lab[0]) / 6).coerceIn(0.0, 1.0)
                    lab[1] += (cbA[0] + zt[0] * cbA[1] + zt[1] * cbA[2] + zt[2] * cbA[3]) * keep
                    lab[2] += (cbB[0] + zt[0] * cbB[1] + zt[1] * cbB[2] + zt[2] * cbB[3]) * keep
                    lab[0] += cbL[0] + zt[0] * cbL[1] + zt[1] * cbL[2] + zt[2] * cbL[3]
                }
                var C = hypot(lab[1], lab[2])
                var hDeg = Math.toDegrees(atan2(lab[2], lab[1]))
                val hue0 = hDeg; val c0 = C
                var dL = 0.0
                for (a in colors) {
                    val wgt = hueWeight(hue0, a.hue.toDouble(), a.width.toDouble()) * ((c0 - 3) / 7).coerceIn(0.0, 1.0)
                    if (wgt <= 0) continue
                    hDeg += a.dHue * wgt
                    C *= (1 + a.dSat / 100.0 * wgt).coerceAtLeast(0.0)
                    dL += a.dLight / 100.0 * 18 * wgt * (c0 / (c0 + 8))
                }
                if (sk != null) {
                    val wH = hueWeight(hue0, skH, sk.width.toDouble())
                    val wC = (1 - abs(c0 - skC) / (skC * 1.2 + 8)).coerceIn(0.0, 1.0)
                    val wL = (1 - abs(lab[0] - sk.L) / 45.0).coerceIn(0.0, 1.0)
                    val wgt = wH * wC * wL
                    if (wgt > 0) {
                        // đều màu: kéo độ lệch về tâm da
                        var dh = hDeg - skH; while (dh > 180) dh -= 360; while (dh < -180) dh += 360
                        hDeg -= dh * (sk.uHue / 100.0) * wgt
                        C -= (C - skC) * (sk.uSat / 100.0) * wgt
                        dL -= (lab[0] - sk.L) * (sk.uLight / 100.0) * 0.6 * wgt
                        hDeg += sk.dHue * wgt
                        C *= (1 + sk.dSat / 100.0 * wgt)
                        dL += sk.dLight / 100.0 * 15 * wgt
                    }
                }
                if (sat != 0.0) C *= (1 + sat).coerceAtLeast(0.0)
                if (vib != 0.0) {
                    // vibrance: tăng màu nhạt nhiều hơn, bảo vệ màu da
                    val lowSat = (1 - C / 60).coerceIn(0.0, 1.0)
                    val skinish = hueWeight(hue0, 55.0, 25.0)
                    C *= (1 + vib * lowSat * (1 - 0.6 * skinish)).coerceAtLeast(0.0)
                }
                val hr = Math.toRadians(hDeg)
                val o = LabMath.fromLab((lab[0] + dL).coerceIn(0.0, 100.0), C * cos(hr), C * sin(hr))
                v[0] = o[0]; v[1] = o[1]; v[2] = o[2]
            }
            val idx = ((bi * N + gi) * N + ri) * 3
            data[idx] = v[0].coerceIn(0.0, 1.0).toFloat(); data[idx + 1] = v[1].coerceIn(0.0, 1.0).toFloat(); data[idx + 2] = v[2].coerceIn(0.0, 1.0).toFloat()
        }
        return Lut(N, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    /** Trọng số theo khoảng cách hue (độ): 1 trong [width·0,6], giảm mềm tới 0 ở [width·1,5]. */
    fun hueWeight(h: Double, center: Double, width: Double): Double {
        var d = abs(h - center) % 360.0; if (d > 180) d = 360 - d
        val inner = width * 0.6; val outer = width * 1.5
        if (d <= inner) return 1.0
        if (d >= outer) return 0.0
        val t = (outer - d) / (outer - inner)
        return t * t * (3 - 2 * t)
    }

    /** Áp hàm lên độ sáng (giữ tỷ lệ màu). */
    private inline fun lumaMap(v: DoubleArray, f: (Double) -> Double) {
        val y = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
        if (y <= 1e-5) { val t = f(0.0); v[0] = t; v[1] = t; v[2] = t; return }
        val y2 = f(y).coerceIn(0.0, 1.0)
        val k = y2 / y
        val mx = max(v[0], max(v[1], v[2]))
        if (mx * k <= 1.0) { for (c in 0 until 3) v[c] *= k; return }
        // tràn kênh: pha về trắng
        for (c in 0 until 3) v[c] = v[c] / mx
        val y3 = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
        val t = ((y2 - y3) / max(1e-6, 1 - y3)).coerceIn(0.0, 1.0)
        for (c in 0 until 3) v[c] = v[c] + (1 - v[c]) * t
    }
}

// =====================================================================
//  Bản đồ hướng dẫn độ phân giải thấp (HDR, Clarity, Dehaze, mask)
// =====================================================================

/** Ảnh nhỏ (~640px) của đầu vào bước Develop, dùng để tính các phép chỉnh theo vùng. */
class DevelopGuide(val w: Int, val h: Int, val px: IntArray, val skin: SkinMap?) {
    /** Độ sáng 0..1 làm mờ giữ cạnh (cho HDR / Clarity). */
    val base: FloatArray by lazy { guided(lum(), max(2, w / 60), 0.012f) }
    val baseSmall: FloatArray by lazy { guided(lum(), max(1, w / 160), 0.008f) }

    private var hazeCache: Pair<FloatArray, FloatArray>? = null
    /** (truyền qua t, màu không khí A[3]). */
    fun haze(): Pair<FloatArray, FloatArray> {
        hazeCache?.let { return it }
        val n = w * h
        val dc = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            dc[i] = min(((c shr 16) and 255), min(((c shr 8) and 255), (c and 255))) / 255f
        }
        val dmin = minFilter(dc, w, h, max(2, w / 120))
        // màu không khí: trung bình 0,2% điểm tối thiểu sáng nhất
        val idx = (0 until n).sortedByDescending { dmin[it] }.take(max(1, n / 500))
        val A = FloatArray(3)
        for (i in idx) { val c = px[i]; A[0] += ((c shr 16) and 255) / 255f; A[1] += ((c shr 8) and 255) / 255f; A[2] += (c and 255) / 255f }
        for (k in 0 until 3) A[k] = (A[k] / idx.size).coerceIn(0.5f, 1f)
        val am = (A[0] + A[1] + A[2]) / 3f
        val t = FloatArray(n) { (1f - 0.92f * dmin[it] / am).coerceIn(0.08f, 1f) }
        val tg = guided(t, max(2, w / 80), 0.001f, lum())
        return (tg to A).also { hazeCache = it }
    }

    private var lumCache: FloatArray? = null
    fun lum(): FloatArray {
        lumCache?.let { return it }
        return FloatArray(w * h) { i ->
            val c = px[i]
            (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
        }.also { lumCache = it }
    }

    private val masks = HashMap<String, FloatArray>()
    /** Mask của layer ở độ phân giải hướng dẫn (có lưu đệm). */
    fun mask(m: MaskSpec): FloatArray = masks.getOrPut(m.toJson().toString()) { MaskRender.render(m, w, h, px, skin) }

    companion object {
        fun of(px: IntArray, w: Int, h: Int, skin: SkinMap?): DevelopGuide {
            val s = min(1f, 640f / max(w, h))
            if (s >= 1f) return DevelopGuide(w, h, px, skin?.takeIf { it.w == w && it.h == h })
            val gw = max(1, (w * s).roundToInt()); val gh = max(1, (h * s).roundToInt())
            val out = IntArray(gw * gh)
            // thu nhỏ trung bình theo ô
            for (y in 0 until gh) {
                val y0 = y * h / gh; val y1 = max(y0 + 1, (y + 1) * h / gh)
                for (x in 0 until gw) {
                    val x0 = x * w / gw; val x1 = max(x0 + 1, (x + 1) * w / gw)
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) { val c = px[yy * w + xx]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
                    out[y * gw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            val sm = skin?.let { sk ->
                SkinMap(gw, gh, SkinMap.resample(sk.m, sk.w, sk.h, gw, gh), sk.person?.let { SkinMap.resample(it, sk.w, sk.h, gw, gh) }, sk.source,
                    sk.hair?.let { SkinMap.resample(it, sk.w, sk.h, gw, gh) }, sk.clothes?.let { SkinMap.resample(it, sk.w, sk.h, gw, gh) })
            }
            return DevelopGuide(gw, gh, out, sm)
        }

        /** Lọc dẫn hướng (He et al.): làm mờ giữ cạnh. */
        fun guided(p: FloatArray, w: Int, h: Int, r: Int, eps: Float, I: FloatArray = p): FloatArray {
            val mI = boxMean(I, w, h, r); val mP = boxMean(p, w, h, r)
            val ip = FloatArray(p.size) { I[it] * p[it] }; val ii = FloatArray(p.size) { I[it] * I[it] }
            val mIP = boxMean(ip, w, h, r); val mII = boxMean(ii, w, h, r)
            val a = FloatArray(p.size); val b = FloatArray(p.size)
            for (k in p.indices) {
                val cov = mIP[k] - mI[k] * mP[k]; val v = mII[k] - mI[k] * mI[k]
                a[k] = cov / (v + eps); b[k] = mP[k] - a[k] * mI[k]
            }
            val ma = boxMean(a, w, h, r); val mb = boxMean(b, w, h, r)
            return FloatArray(p.size) { ma[it] * I[it] + mb[it] }
        }

        fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(src.size); val out = FloatArray(src.size)
            for (y in 0 until h) {
                var s = 0f; val row = y * w
                for (x in -r..r) s += src[row + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[row + x] = s / (2 * r + 1)
                    s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                var s = 0f
                for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = s / (2 * r + 1)
                    s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
                }
            }
            return out
        }

        fun minFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(src.size); val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var m = 1f
                for (k in max(0, x - r)..min(w - 1, x + r)) m = min(m, src[y * w + k])
                tmp[y * w + x] = m
            }
            for (y in 0 until h) for (x in 0 until w) {
                var m = 1f
                for (k in max(0, y - r)..min(h - 1, y + r)) m = min(m, tmp[k * w + x])
                out[y * w + x] = m
            }
            return out
        }
    }

    private fun guided(p: FloatArray, r: Int, eps: Float, I: FloatArray = p) = Companion.guided(p, w, h, r, eps, I)
}

// =====================================================================
//  Bộ máy Develop: áp cho một dải dòng ảnh (có dòng đệm trên / dưới)
// =====================================================================

class DevelopEngine(val p: DevelopParams, val guide: DevelopGuide?) {
    private val lut = DevelopLut.build(p)
    private val layerLuts = p.layers.filter { it.on && it.opacity > 0 }.map { it to DevelopLut.build(it.dev) }
    private val haze = if (p.dehaze != 0 && guide != null) guide.haze() else null

    /** Số dòng đệm cần thêm trên / dưới cho ảnh rộng [W]. */
    fun margin(W: Int): Int {
        val s = W / 4000f
        var m = 1
        if (p.structure != 0) m = max(m, (3 * s).roundToInt() + 1)
        if (p.sharpen != 0) m = max(m, ceil(2.5 * p.sharpRadius * max(0.5f, s)).toInt() + 1)
        if (p.nrLuma != 0) m = max(m, (2 * max(1f, s)).roundToInt() + 1)
        if (p.nrColor != 0) m = max(m, ((2 + p.nrColor / 100f * 6) * max(1f, s)).roundToInt() + 1)
        return m
    }

    /**
     * Xử lý dải: [band] chứa (top + rows + bottom) dòng, rộng [W]; dòng đầu của phần chính là dòng [y0] của ảnh W×H.
     * Kết quả ghi đè phần chính.
     */
    fun process(band: IntArray, W: Int, H: Int, top: Int, rows: Int, bottom: Int, y0: Int) {
        val all = top + rows + bottom
        val s = W / 4000f
        // 1. giảm nhiễu (cả dải, để bước sau có đệm sạch)
        if (p.nrLuma != 0 || p.nrColor != 0) Detail.denoise(band, W, all, p.nrLuma / 100f, p.nrColor / 100f, max(1f, s))
        // 2. theo điểm ảnh + theo vùng (chỉ phần chính... nhưng structure / sharpen cần đệm đã xử lý: làm cả dải)
        val gy0 = y0 - top
        for (yy in 0 until all) {
            val gy = gy0 + yy
            if (gy < 0 || gy >= H) continue
            pointRow(band, yy * W, W, H, gy)
        }
        // 3. Structure + Sharpen trên độ sáng
        if (p.structure != 0) Detail.localContrast(band, W, all, max(1, (3 * s).roundToInt()), p.structure / 100f * 0.9f, 0f, 0)
        if (p.sharpen != 0) Detail.localContrast(band, W, all, max(1, (p.sharpRadius * max(0.5f, s) * 1.2f).roundToInt()),
            p.sharpen / 100f * 1.6f, p.sharpThreshold / 255f, 1)
        // 4. hạt film
        if (p.grain != 0) Detail.grain(band, W, top, rows, y0, p.grain / 100f, p.grainSize / 100f, max(0.5f, s))
    }

    private val tmp = FloatArray(3); private val tmp2 = FloatArray(3)

    private fun pointRow(band: IntArray, off: Int, W: Int, H: Int, gy: Int) {
        val g = guide
        val hdr = p.highlight != 0 || p.shadow != 0
        val cla = p.clarity != 0
        val needGuide = g != null && (hdr || cla || haze != null || layerLuts.isNotEmpty())
        val sh = p.shadow / 100.0; val hl = p.highlight / 100.0; val cl = p.clarity / 100f
        val dz = p.dehaze / 100f
        var gyy = 0f; var g1 = 0; var g2 = 0; var ty = 0f
        if (needGuide) {
            gyy = ((gy + 0.5f) * g!!.h / H - 0.5f).coerceIn(0f, (g.h - 1).toFloat())
            g1 = gyy.toInt(); g2 = min(g.h - 1, g1 + 1); ty = gyy - g1
        }
        val masks = if (needGuide && layerLuts.isNotEmpty()) layerLuts.map { g!!.mask(it.first.mask) } else emptyList()
        val rgb = DoubleArray(3)
        for (x in 0 until W) {
            val k = off + x
            val c = band[k]
            var r = ((c shr 16) and 255) / 255f; var gg = ((c shr 8) and 255) / 255f; var b = (c and 255) / 255f
            var gx1 = 0; var gx2 = 0; var tx = 0f
            if (needGuide) {
                val gxx = ((x + 0.5f) * g!!.w / W - 0.5f).coerceIn(0f, (g.w - 1).toFloat())
                gx1 = gxx.toInt(); gx2 = min(g.w - 1, gx1 + 1); tx = gxx - gx1
            }
            fun sample(a: FloatArray): Float {
                val gw = g!!.w
                val t0 = a[g1 * gw + gx1] + (a[g1 * gw + gx2] - a[g1 * gw + gx1]) * tx
                val t1 = a[g2 * gw + gx1] + (a[g2 * gw + gx2] - a[g2 * gw + gx1]) * tx
                return t0 + (t1 - t0) * ty
            }
            // Dehaze: J = (I − A) / t + A
            if (haze != null) {
                val t = sample(haze.first); val A = haze.second
                if (dz > 0) {
                    val tt = 1f - (1f - max(t, 0.1f)) * dz
                    r = ((r - A[0]) / tt + A[0]).coerceIn(0f, 1f); gg = ((gg - A[1]) / tt + A[1]).coerceIn(0f, 1f); b = ((b - A[2]) / tt + A[2]).coerceIn(0f, 1f)
                } else {
                    val a = -dz * 0.5f * (1f - t * 0.5f)
                    r += (A[0] - r) * a; gg += (A[1] - gg) * a; b += (A[2] - b) * a
                }
            }
            // HDR: Highlight / Shadow theo độ sáng vùng (giữ chi tiết vì hệ số theo vùng, không theo điểm)
            if (hdr && g != null) {
                val B = sample(g.base).toDouble()
                val sL = 1 - smooth(0.0, 0.55, B); val hL = smooth(0.4, 1.0, B)
                val ev = sh * 1.5 * sL * sL - hl * 1.4 * hL
                if (ev != 0.0) {
                    rgb[0] = r.toDouble(); rgb[1] = gg.toDouble(); rgb[2] = b.toDouble()
                    AutoGrade.exposeRgb(rgb, 2.0.pow(ev))
                    r = rgb[0].toFloat(); gg = rgb[1].toFloat(); b = rgb[2].toFloat()
                }
            }
            // Clarity: tương phản vùng giữa theo lớp nền lớn
            if (cla && g != null) {
                val Y = 0.2126f * r + 0.7152f * gg + 0.0722f * b
                val B = sample(g.base)
                val mid = 4f * Y * (1f - Y)
                val Y2 = (Y + cl * 0.9f * (Y - B) * mid).coerceIn(0f, 1f)
                if (Y > 1e-4f) { val k2 = Y2 / Y; r = (r * k2).coerceIn(0f, 1f); gg = (gg * k2).coerceIn(0f, 1f); b = (b * k2).coerceIn(0f, 1f) }
            }
            var or = r; var og = gg; var ob = b
            if (lut != null) { lut.lookup(r, gg, b, tmp); or = tmp[0]; og = tmp[1]; ob = tmp[2] }
            // layer: biến đổi màu của layer trộn theo mask × độ mờ
            for ((li, pair) in layerLuts.withIndex()) {
                if (li >= masks.size) break
                val (layer, ll) = pair
                val a = sample(masks[li]) * layer.opacity / 100f
                if (a <= 0.003f) continue
                var lr = or; var lg = og; var lb = ob
                val d = layer.dev
                if (d.highlight != 0 || d.shadow != 0 || d.clarity != 0) {
                    val B = sample(g!!.base).toDouble()
                    val sL = 1 - smooth(0.0, 0.55, B); val hL = smooth(0.4, 1.0, B)
                    val ev = d.shadow / 100.0 * 1.5 * sL * sL - d.highlight / 100.0 * 1.4 * hL
                    if (ev != 0.0) {
                        rgb[0] = lr.toDouble(); rgb[1] = lg.toDouble(); rgb[2] = lb.toDouble()
                        AutoGrade.exposeRgb(rgb, 2.0.pow(ev))
                        lr = rgb[0].toFloat(); lg = rgb[1].toFloat(); lb = rgb[2].toFloat()
                    }
                    if (d.clarity != 0) {
                        val Y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                        val Y2 = (Y + d.clarity / 100f * 0.9f * (Y - B.toFloat()) * 4f * Y * (1f - Y)).coerceIn(0f, 1f)
                        if (Y > 1e-4f) { val k2 = Y2 / Y; lr = (lr * k2).coerceIn(0f, 1f); lg = (lg * k2).coerceIn(0f, 1f); lb = (lb * k2).coerceIn(0f, 1f) }
                    }
                }
                if (ll != null) { ll.lookup(lr, lg, lb, tmp2); lr = tmp2[0]; lg = tmp2[1]; lb = tmp2[2] }
                val aa = a.coerceIn(0f, 1f)
                or += (lr - or) * aa; og += (lg - og) * aa; ob += (lb - ob) * aa
            }
            // nhiễu trộn (dither) ±1 mức khi làm tròn về 8 bit: tránh vệt dải ở trời, nền trơn khi kéo đường cong mạnh
            val dth = Detail.hash(x, gy) - Detail.hash(x + 7919, gy + 104729)
            val ro = (or * 255f + 0.5f + dth).toInt().coerceIn(0, 255)
            val go = (og * 255f + 0.5f + dth).toInt().coerceIn(0, 255)
            val bo = (ob * 255f + 0.5f + dth).toInt().coerceIn(0, 255)
            band[k] = (c and -0x1000000) or (ro shl 16) or (go shl 8) or bo
        }
    }

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    companion object {
        /** Áp cả ảnh nhỏ (xem trước) trong bộ nhớ. */
        fun applyAll(px: IntArray, w: Int, h: Int, p: DevelopParams, guide: DevelopGuide?) {
            val e = DevelopEngine(p, guide)
            e.process(px, w, h, 0, h, 0, 0)
        }
    }
}

// =====================================================================
//  Chi tiết: giảm nhiễu, Structure / Sharpen, hạt film
// =====================================================================

object Detail {
    /** Tách độ sáng (Y) và hai kênh màu. */
    private fun split(px: IntArray, n: Int, Y: FloatArray, U: FloatArray, V: FloatArray) {
        for (i in 0 until n) {
            val c = px[i]
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            Y[i] = 0.299f * r + 0.587f * g + 0.114f * b
            U[i] = b - Y[i]; V[i] = r - Y[i]
        }
    }

    private fun join(px: IntArray, n: Int, Y: FloatArray, U: FloatArray, V: FloatArray) {
        for (i in 0 until n) {
            val r = Y[i] + V[i]; val b = Y[i] + U[i]
            val g = (Y[i] - 0.299f * r - 0.114f * b) / 0.587f
            val ro = (r * 255f + 0.5f).toInt().coerceIn(0, 255); val go = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bo = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (px[i] and -0x1000000) or (ro shl 16) or (go shl 8) or bo
        }
    }

    /** Giảm nhiễu: độ sáng bằng lọc song phương 5×5, màu bằng làm mờ hộp. */
    fun denoise(px: IntArray, w: Int, h: Int, luma: Float, chroma: Float, scale: Float) {
        val n = w * h
        val Y = FloatArray(n); val U = FloatArray(n); val V = FloatArray(n)
        split(px, n, Y, U, V)
        if (luma > 0f) {
            val r = max(1, (2 * scale).roundToInt()).coerceAtMost(4)
            val sr = 0.015f + luma * 0.07f
            val rw = FloatArray(256) { exp(-(it / 255f / sr).pow(2) / 2f) }
            val sw = FloatArray((2 * r + 1) * (2 * r + 1)) { j -> val dx = j % (2 * r + 1) - r; val dy = j / (2 * r + 1) - r; exp(-(dx * dx + dy * dy) / (2f * r * r * 0.6f)) }
            val out = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                val c = Y[y * w + x]
                var s = 0f; var ws = 0f; var j = 0
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -r..r) {
                        val v = Y[yy * w + (x + dx).coerceIn(0, w - 1)]
                        val wt = sw[j++] * rw[(abs(v - c) * 255f).toInt().coerceAtMost(255)]
                        s += v * wt; ws += wt
                    }
                }
                out[y * w + x] = c + (s / ws - c) * min(1f, luma * 1.2f)
            }
            System.arraycopy(out, 0, Y, 0, n)
        }
        if (chroma > 0f) {
            val r = max(1, ((2 + chroma * 6) * scale).roundToInt())
            val mu = DevelopGuide.boxMean(U, w, h, r); val mv = DevelopGuide.boxMean(V, w, h, r)
            val k = min(1f, chroma * 1.5f)
            for (i in 0 until n) { U[i] += (mu[i] - U[i]) * k; V[i] += (mv[i] - V[i]) * k }
        }
        join(px, n, Y, U, V)
    }

    /**
     * Tăng tương phản cục bộ trên độ sáng: Y += k·(Y − mờ(Y)). mode 0 = Structure (bỏ qua vùng rất tối / sáng),
     * mode 1 = Sharpen (bỏ qua chênh lệch nhỏ hơn ngưỡng).
     */
    fun localContrast(px: IntArray, w: Int, h: Int, r: Int, k: Float, threshold: Float, mode: Int) {
        val n = w * h
        val Y = FloatArray(n); val U = FloatArray(n); val V = FloatArray(n)
        split(px, n, Y, U, V)
        // mờ gần Gauss: hai lần hộp
        val b1 = DevelopGuide.boxMean(Y, w, h, max(1, r / 2 + 1))
        val bl = DevelopGuide.boxMean(b1, w, h, max(1, r / 2))
        for (i in 0 until n) {
            var d = Y[i] - bl[i]
            if (mode == 1) {
                val a = abs(d)
                if (a < threshold) continue
                d = Math.signum(d) * (a - threshold)
                // giảm quầng sáng ở cạnh mạnh
                d = d / (1f + abs(d) * 6f)
            } else {
                d *= 4f * Y[i] * (1f - Y[i])
            }
            Y[i] = (Y[i] + k * d).coerceIn(0f, 1f)
        }
        join(px, n, Y, U, V)
    }

    /** Hạt film: nhiễu giá trị (value noise) theo toạ độ ảnh nên cố định giữa các lần xử lý dải. */
    fun grain(px: IntArray, w: Int, top: Int, rows: Int, y0: Int, amount: Float, size: Float, scale: Float) {
        val cell = (1f + size * 3f) * scale
        val amp = amount * 0.11f
        for (yy in 0 until rows) {
            val gy = (y0 + yy) / cell
            val iy = gy.toInt(); val fy = gy - iy
            val sy = fy * fy * (3 - 2 * fy)
            for (x in 0 until w) {
                val gx = x / cell
                val ix = gx.toInt(); val fx = gx - ix
                val sx = fx * fx * (3 - 2 * fx)
                val n00 = hash(ix, iy); val n10 = hash(ix + 1, iy); val n01 = hash(ix, iy + 1); val n11 = hash(ix + 1, iy + 1)
                val nz = (n00 + (n10 - n00) * sx) + ((n01 + (n11 - n01) * sx) - (n00 + (n10 - n00) * sx)) * sy
                val k = (top + yy) * w + x
                val c = px[k]
                val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
                val Y = 0.299f * r + 0.587f * g + 0.114f * b
                val d = (nz - 0.5f) * 2f * amp * (0.35f + 2.6f * Y * (1f - Y))
                val ro = ((r + d) * 255f + 0.5f).toInt().coerceIn(0, 255); val go = ((g + d) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bo = ((b + d) * 255f + 0.5f).toInt().coerceIn(0, 255)
                px[k] = (c and -0x1000000) or (ro shl 16) or (go shl 8) or bo
            }
        }
    }

    fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }
}

// =====================================================================
//  Ống kính (méo, Keystone) và Vignette
// =====================================================================

object Lens {
    /** Toạ độ nguồn (điểm ảnh) cho điểm đích (x, y) trên ảnh W×H. */
    class Warp(val W: Int, val H: Int, p: DevelopParams) {
        private val k = p.distortion / 100f * 0.18f
        private val kv = p.keystoneV / 100f * 0.35f
        private val kh = p.keystoneH / 100f * 0.35f
        private val cx = W / 2f; private val cy = H / 2f
        private val rn = hypot(cx.toDouble(), cy.toDouble()).toFloat()
        /** Phóng to để ẩn phần trống ở mép. */
        val zoom: Float

        init {
            var z = 1f
            val o = FloatArray(2)
            val samples = 24
            for (i in 0..samples) {
                val t = i / samples.toFloat()
                for ((x, y) in listOf(t * W to 0f, t * W to H.toFloat(), 0f to t * H, W.toFloat() to t * H)) {
                    raw(x, y, o)
                    val sx = abs(o[0] - cx) / cx; val sy = abs(o[1] - cy) / cy
                    // điểm mép phải ánh xạ vào trong ảnh: cần phóng to theo tỷ lệ vượt
                    z = max(z, max(sx, sy))
                }
            }
            zoom = z.coerceAtMost(2f)
        }

        private fun raw(x: Float, y: Float, o: FloatArray) {
            var dx = x - cx; var dy = y - cy
            // keystone: co / giãn theo trục kia
            val ny = dy / cy; val nx = dx / cx
            dx *= (1f + kv * ny); dy *= (1f + kh * nx)
            // méo hướng tâm: r_src = r·(1 + k·r²)
            val r2 = (dx * dx + dy * dy) / (rn * rn)
            val f = 1f + k * r2
            o[0] = cx + dx * f; o[1] = cy + dy * f
        }

        fun map(x: Float, y: Float, o: FloatArray) = raw(cx + (x - cx) / zoom, cy + (y - cy) / zoom, o)
    }

    /** Nắn ảnh (lấy mẫu song tuyến). */
    fun apply(src: Bitmap, p: DevelopParams): Bitmap {
        if (!p.lensOn) return src
        val W = src.width; val H = src.height
        val warp = Warp(W, H, p)
        val inp = IntArray(W * H); src.getPixels(inp, 0, W, 0, 0, W, H)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val row = IntArray(W); val o = FloatArray(2)
        for (y in 0 until H) {
            for (x in 0 until W) {
                warp.map(x + 0.5f, y + 0.5f, o)
                row[x] = bilinear(inp, W, H, o[0] - 0.5f, o[1] - 0.5f)
            }
            out.setPixels(row, 0, W, 0, y, W, 1)
        }
        return out
    }

    fun bilinear(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, w - 1f); val y = fy.coerceIn(0f, h - 1f)
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val tx = x - x0; val ty = y - y0
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        fun ch(s: Int): Int {
            val va = (a shr s) and 255; val vb = (b shr s) and 255; val vc = (c shr s) and 255; val vd = (d shr s) and 255
            val t0 = va + (vb - va) * tx; val t1 = vc + (vd - vc) * tx
            return (t0 + (t1 - t0) * ty + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Vignette sau khi cắt: −100 tối góc, +100 sáng góc. */
    fun vignette(b: Bitmap, amount: Int): Bitmap {
        if (amount == 0) return b
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
        val W = out.width; val H = out.height
        val cx = W / 2f; val cy = H / 2f; val rn = hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val k = amount / 100f
        val row = IntArray(W)
        for (y in 0 until H) {
            out.getPixels(row, 0, W, 0, y, W, 1)
            for (x in 0 until W) {
                val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() / rn
                val t = ((d - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val f = 2.0.pow((k * 1.4f * t * t).toDouble()).toFloat()
                val c = row[x]
                fun ch(s: Int): Int {
                    val v = ((c shr s) and 255) / 255f
                    val lin = LabMath.lin(v.toDouble()) * f
                    return (LabMath.gam(lin.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
                }
                row[x] = (c and -0x1000000) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
            out.setPixels(row, 0, W, 0, y, W, 1)
        }
        return out
    }
}

// =====================================================================
//  Style và sao chép / dán chỉnh sửa
// =====================================================================

/** Một Style: các chỉnh sửa có thể áp sang ảnh khác (không gồm cắt, mask vẽ tay). */
class StyleData(
    val name: String, val dev: DevelopParams,
    val presetSlot: String?, val presetName: String?, val strength: Int, val ev: Double,
    val smooth: Int, val blemish: Boolean, val defringe: Int, val light: Int, val skinTone: Int
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("dev", dev.toJson())
        presetSlot?.let { put("ps", it) }; presetName?.let { put("pn", it) }
        put("st", strength); put("ev", ev); put("sm", smooth); put("bm", blemish); put("df", defringe)
        put("li", light); put("sk", skinTone)
    }

    /** Vị trí công thức màu trong danh sách hiện tại (−1 nếu không có). */
    fun presetIndex(): Int = if (presetSlot == null) -1 else
        Presets.all.indexOfFirst { it.slot == presetSlot && it.name == presetName }.takeIf { it >= 0 }
            ?: Presets.all.indexOfFirst { it.slot == presetSlot }

    companion object {
        fun fromJson(o: JSONObject) = StyleData(
            o.optString("name", "Style"), DevelopParams.fromJson(o.getJSONObject("dev")),
            if (o.has("ps")) o.getString("ps") else null, if (o.has("pn")) o.getString("pn") else null,
            o.optInt("st", 100), o.optDouble("ev", 0.0), o.optInt("sm"), o.optBoolean("bm"), o.optInt("df"),
            o.optInt("li"), o.optInt("sk"))
    }
}

object StyleStore {
    private fun sp(c: Context) = c.getSharedPreferences("styles", Context.MODE_PRIVATE)

    fun list(c: Context): List<StyleData> = try {
        val a = JSONArray(sp(c).getString("list", "[]"))
        (0 until a.length()).map { StyleData.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun saveList(c: Context, l: List<StyleData>) {
        sp(c).edit().putString("list", JSONArray().also { j -> l.forEach { j.put(it.toJson()) } }.toString()).apply()
    }

    fun add(c: Context, s: StyleData) = saveList(c, list(c).filter { it.name != s.name } + s)
    fun remove(c: Context, name: String) = saveList(c, list(c).filter { it.name != name })

    /** Bộ nhớ tạm cho Sao chép / Dán chỉnh sửa. */
    fun copy(c: Context, s: StyleData) = sp(c).edit().putString("clip", s.toJson().toString()).apply()
    fun clip(c: Context): StyleData? = try { sp(c).getString("clip", null)?.let { StyleData.fromJson(JSONObject(it)) } } catch (e: Exception) { null }
}
