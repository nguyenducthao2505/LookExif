package com.looknote.exif

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Một công thức màu theo bảng thông số A7C II.
 * Các giá trị dùng đúng thang của máy: C/H/S/Sat -9..+9, Fade 0..9,
 * WB Shift: ab > 0 là A (vàng), ab < 0 là B (xanh); gm > 0 là M (hồng tím), gm < 0 là G.
 */
data class Preset(
    val slot: String,
    val name: String,
    val look: String,
    val contrast: Int,
    val highlights: Int,
    val shadows: Int,
    val fade: Int,
    val saturation: Int,
    val ab: Double,
    val gm: Double,
    val ev: Double,
    val wbNote: String,
    /** Tông riêng của loại phim: cộng vào R, G, B ở vùng tối / vùng sáng. */
    val filmShadow: FloatArray = floatArrayOf(0f, 0f, 0f),
    val filmHighlight: FloatArray = floatArrayOf(0f, 0f, 0f)
) {
    fun summary(): String {
        fun s(v: Int) = if (v > 0) "+$v" else "$v"
        fun sh(v: Double, p: String, n: String) =
            if (v == 0.0) "0" else if (v > 0) p + PhotoInfoBuilder.fmt(v, 1) else n + PhotoInfoBuilder.fmt(-v, 1)
        return "$look · C${s(contrast)} H${s(highlights)} S${s(shadows)} F$fade Sat${s(saturation)} · " +
            "$wbNote ${sh(ab, "A", "B")} ${sh(gm, "M", "G")}"
    }
}

object Presets {
    val all = listOf(
        Preset("1", "Da trắng hồng (Canon)", "PT", -1, -2, 1, 0, 0, 0.0, 2.0, 0.7, "AWB"),
        Preset("2", "Fuji Classic Neg", "FL", -1, -1, 1, 2, 0, 0.0, 2.0, 0.0, "AWB"),
        Preset("3", "Fuji Pro 400H", "SH", -2, -3, 2, 2, -2, -1.0, 2.0, 0.7, "AWB"),
        Preset("M1", "Kodak Portra 400", "PT", -1, -2, 1, 2, -1, 2.0, 1.0, 0.3, "Daylight",
            floatArrayOf(0.004f, 0.0f, -0.006f), floatArrayOf(0.022f, 0.010f, -0.012f)),
        Preset("M2", "Cinestill 800T", "FL", 1, -2, -1, 1, 1, -2.0, 1.0, -0.3, "3800K",
            floatArrayOf(-0.045f, 0.010f, 0.045f), floatArrayOf(0.030f, 0.008f, -0.020f)),
        Preset("M3", "Kodak Gold 200", "ST", 0, -1, -1, 2, 1, 3.0, 1.0, 0.3, "Daylight",
            floatArrayOf(0.010f, 0.004f, -0.015f), floatArrayOf(0.025f, 0.018f, -0.030f)),
        Preset("M4", "Cạnh cửa sổ", "FL", -1, -2, -9, 1, -2, -7.0, 2.0, 0.0, "AWB")
    )
}

/**
 * Bộ xử lý màu: mô phỏng gần đúng Creative Look của Sony trên ảnh đã chụp.
 * Thứ tự: cân bằng trắng → đường cong tone (bù sáng, contrast, highlights, shadows, fade)
 * → độ bão hòa → tông màu riêng của Look → trộn với ảnh gốc theo độ mạnh.
 */
class ColorEngine(p: Preset, evOverride: Double = p.ev, private val strength: Float = 1f) {

    private val lut = FloatArray(1024)
    private val gainR: Float
    private val gainG: Float
    private val gainB: Float
    private val satMul: Float
    // tông vùng tối / vùng sáng (cộng thêm vào R, G, B)
    private val shR: Float; private val shG: Float; private val shB: Float
    private val hiR: Float; private val hiG: Float; private val hiB: Float

    init {
        // ----- đặc trưng của từng Look -----
        var cBase = 0.0; var satBase = 0.0; var evBase = 0.0; var fadeBase = 0.0
        var sh = floatArrayOf(0f, 0f, 0f); var hi = floatArrayOf(0f, 0f, 0f)
        when (p.look) {
            "PT" -> { cBase = -0.5; satBase = -0.04; hi = floatArrayOf(0.012f, 0.0f, 0.004f) }
            "FL" -> { cBase = 1.0; satBase = -0.12; fadeBase = 0.5
                sh = floatArrayOf(-0.030f, 0.008f, 0.022f); hi = floatArrayOf(0.014f, 0.006f, -0.010f) }
            "SH" -> { cBase = -1.0; satBase = 0.04; evBase = 0.15
                hi = floatArrayOf(0.0f, 0.004f, 0.012f); sh = floatArrayOf(-0.006f, 0.008f, 0.010f) }
            "IN" -> { cBase = -1.5; satBase = -0.2; fadeBase = 1.0 }
            "NT" -> { satBase = -0.1 }
            "VV" -> { cBase = 0.5; satBase = 0.15 }
            else -> { }
        }
        shR = sh[0] + p.filmShadow[0]; shG = sh[1] + p.filmShadow[1]; shB = sh[2] + p.filmShadow[2]
        hiR = hi[0] + p.filmHighlight[0]; hiG = hi[1] + p.filmHighlight[1]; hiB = hi[2] + p.filmHighlight[2]

        // ----- cân bằng trắng (mỗi bước ~1,5% kênh màu) -----
        val a = p.ab * 0.015
        val m = p.gm * 0.012
        gainR = (1.0 + a + m * 0.5).toFloat()
        gainG = (1.0 - m).toFloat()
        gainB = (1.0 - a + m * 0.5).toFloat()

        satMul = (1.0 + p.saturation * 0.07 + satBase).toFloat()

        // ----- đường cong tone -----
        val ev = evOverride + evBase
        val k = 2.0.pow(ev * 0.6)
        val cAmt = (p.contrast + cBase) * 0.12
        val fadeAmt = (p.fade + fadeBase) * 0.015
        for (i in 0 until 1024) {
            var x = i / 1023.0
            // bù sáng: cong mềm, không cháy sáng
            x = 1.0 - (1.0 - x).pow(k)
            // contrast: trộn với đường S
            val s = x * x * (3 - 2 * x)
            x += (s - x) * cAmt
            // highlights / shadows
            if (x > 0.5) { val w = (x - 0.5) * 2; x += p.highlights * 0.015 * w * w }
            else { val w = (0.5 - x) * 2; x += p.shadows * 0.015 * w * w }
            // fade: nâng điểm đen
            x += fadeAmt * (1 - x) * (1 - x)
            lut[i] = x.coerceIn(0.0, 1.0).toFloat()
        }
    }

    private fun curve(v: Float): Float {
        val idx = (v.coerceIn(0f, 1f) * 1023f).toInt()
        return lut[idx]
    }

    /** Xử lý mảng điểm ảnh ARGB tại chỗ. */
    fun process(px: IntArray, from: Int = 0, to: Int = px.size) {
        val st = strength
        for (i in from until to) {
            val c = px[i]
            val a = c ushr 24
            val r0 = ((c shr 16) and 0xFF) / 255f
            val g0 = ((c shr 8) and 0xFF) / 255f
            val b0 = (c and 0xFF) / 255f

            var r = curve(r0 * gainR)
            var g = curve(g0 * gainG)
            var b = curve(b0 * gainB)

            val l = 0.299f * r + 0.587f * g + 0.114f * b
            r = l + (r - l) * satMul
            g = l + (g - l) * satMul
            b = l + (b - l) * satMul

            val ws = (1 - l) * (1 - l)
            val wh = l * l
            r += shR * ws + hiR * wh
            g += shG * ws + hiG * wh
            b += shB * ws + hiB * wh

            if (st < 1f) {
                r = r0 + (r - r0) * st
                g = g0 + (g - g0) * st
                b = b0 + (b - b0) * st
            }
            val ri = (max(0f, min(1f, r)) * 255f + 0.5f).toInt()
            val gi = (max(0f, min(1f, g)) * 255f + 0.5f).toInt()
            val bi = (max(0f, min(1f, b)) * 255f + 0.5f).toInt()
            px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }
}
