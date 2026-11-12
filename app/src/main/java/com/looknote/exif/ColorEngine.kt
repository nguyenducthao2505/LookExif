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
    val filmHighlight: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Hiệu chỉnh theo máy thật: 3 đường cong 256 điểm (R, G, B) từ ảnh ST sang ảnh công thức. */
    val calib: FloatArray? = null,
    /** Hệ số độ bão hòa đi kèm hiệu chỉnh. */
    val calibSat: Float = 1f,
    /** Đường dẫn file LUT 3D (.cube) đã nhập; khác null là công thức LUT. */
    val lutFile: String? = null
) {
    /** Hai công thức có cùng thông số máy (dùng để nhận ra ảnh chụp bằng công thức nào). */
    fun sameSettings(o: Preset): Boolean =
        lutFile == o.lutFile && look == o.look && contrast == o.contrast && highlights == o.highlights && shadows == o.shadows &&
            fade == o.fade && saturation == o.saturation &&
            kotlin.math.abs(ab - o.ab) < 0.3 && kotlin.math.abs(gm - o.gm) < 0.3

    fun summary(): String {
        if (lutFile != null) return if (lutFile.startsWith("film:")) "Màu phim dựng sẵn · chỉ áp trong app" else "LUT 3D (.cube)"
        fun s(v: Int) = if (v > 0) "+$v" else "$v"
        fun sh(v: Double, p: String, n: String) =
            if (v == 0.0) "0" else if (v > 0) p + PhotoInfoBuilder.fmt(v, 1) else n + PhotoInfoBuilder.fmt(-v, 1)
        return "$look · C${s(contrast)} H${s(highlights)} S${s(shadows)} F$fade Sat${s(saturation)} · " +
            "$wbNote ${sh(ab, "A", "B")} ${sh(gm, "M", "G")}" + (if (calib != null) " · đã hiệu chỉnh theo máy" else "")
    }
}

object Presets {
    val defaults: List<Preset> = listOf(
    Preset("1", "Hồng sáng da trắng", "PT", -1, -2, 1, 0, 0, 0.0, 2.0, 0.7, "AWB"),
    Preset("2", "Trầm cổ điển ngọc–đỏ", "FL", -1, -1, 1, 2, 0, 0.0, 2.0, 0.0, "AWB"),
    Preset("3", "Pastel xanh mát", "SH", -2, -3, 2, 2, -2, -1.0, 2.0, 0.7, "AWB"),
    Preset("M1", "Ấm dịu da mịn", "PT", -1, -2, 1, 2, -1, 2.0, 1.0, 0.3, "Daylight",
        floatArrayOf(0.004f, 0.0f, -0.006f), floatArrayOf(0.022f, 0.010f, -0.012f)),
    Preset("M2", "Đêm xanh lạnh", "FL", 1, -2, -1, 1, 1, -2.0, 1.0, -0.3, "3800K",
        floatArrayOf(-0.045f, 0.010f, 0.045f), floatArrayOf(0.030f, 0.008f, -0.020f)),
    Preset("M3", "Vàng nắng rực", "ST", 0, -1, -1, 2, 1, 3.0, 1.0, 0.3, "Daylight",
        floatArrayOf(0.010f, 0.004f, -0.015f), floatArrayOf(0.025f, 0.018f, -0.030f)),
    Preset("M4", "Lạnh trầm cửa sổ", "FL", -1, -2, -9, 1, -2, -7.0, 2.0, 0.0, "AWB"),
    // ---- bổ sung từ ảnh mẫu, đã lọc bỏ các công thức cho màu gần trùng ----
    // (B1, B2, B3 theo công thức kèm ảnh; B4–B6 ảnh không kèm công thức, thông số ước lượng)
    Preset("B1", "Tươi rõ tự nhiên", "NT", -2, -2, 2, 1, 5, 0.0, 0.0, 0.0, "5600K"),
    Preset("B2", "Sáng nhạt thoáng", "FL", -2, -6, 0, 0, 1, 0.0, 0.0, 0.0, "AWB"),
    Preset("B3", "Trắng sữa nhạt", "NT", -1, 0, -1, 0, -2, 0.0, 0.0, 0.0, "AWB"),
    Preset("B4", "Nâu vàng hoài cổ", "FL", -2, -3, 1, 3, -1, 3.0, 1.0, 0.3, "Daylight",
        floatArrayOf(0.012f, 0.004f, -0.018f), floatArrayOf(0.028f, 0.014f, -0.030f)),
    Preset("B5", "Xám xanh film", "FL", -2, -3, 1, 2, -2, 0.0, -0.5, 0.0, "AWB",
        floatArrayOf(-0.010f, 0.012f, 0.010f), floatArrayOf(0.010f, 0.006f, -0.008f)),
    Preset("B6", "Xanh rêu ấm 90s", "FL", 1, -2, -2, 2, -1, 2.0, -1.0, 0.0, "AWB",
        floatArrayOf(-0.020f, 0.025f, 0.005f), floatArrayOf(0.030f, 0.012f, -0.025f))
) + FilmLook.presets()

    /** Tên cũ → tên mới (đổi theo màu sắc công thức thể hiện). */
    val renamed = mapOf(
        "Da trắng hồng (Canon)" to "Hồng sáng da trắng", "Fuji Classic Neg" to "Trầm cổ điển ngọc–đỏ",
        "Fuji Pro 400H" to "Pastel xanh mát", "Kodak Portra 400" to "Ấm dịu da mịn", "Cinestill 800T" to "Đêm xanh lạnh",
        "Kodak Gold 200" to "Vàng nắng rực", "Cạnh cửa sổ" to "Lạnh trầm cửa sổ", "NT ga tàu" to "Tươi rõ tự nhiên",
        "Kamakura FL" to "Sáng nhạt thoáng", "NT trắng hồng" to "Trắng sữa nhạt", "Lau sậy nắng chiều" to "Nâu vàng hoài cổ",
        "Phố Nhật film" to "Xám xanh film", "Hồng Kông 90s" to "Xanh rêu ấm 90s"
    )

    /** Danh sách công thức đang dùng (người dùng thêm/sửa được). */
    @Volatile var all: List<Preset> = defaults
    /** Công thức trong danh sách có cùng thông số máy với ảnh (để dùng hiệu chỉnh khi gỡ màu). */
    fun match(src: Preset): Preset? = all.firstOrNull { it.calib != null && it.sameSettings(src) }

}

/**
 * Bộ xử lý màu: mô phỏng gần đúng Creative Look của Sony trên ảnh đã chụp.
 * Thứ tự: cân bằng trắng → đường cong tone (bù sáng, contrast, highlights, shadows, fade)
 * → độ bão hòa → tông màu riêng của Look → trộn với ảnh gốc theo độ mạnh.
 */
class ColorEngine(p: Preset, evOverride: Double = p.ev, private val strength: Float = 1f) {

    internal val lut = FloatArray(1024)
    internal val gainR: Float
    internal val gainG: Float
    internal val gainB: Float
    internal val satMul: Float
    // tông vùng tối / vùng sáng (cộng thêm vào R, G, B)
    internal val shR: Float; internal val shG: Float; internal val shB: Float
    internal val hiR: Float; internal val hiG: Float; internal val hiB: Float

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
        // mỗi nấc WB Shift của Sony khá mạnh (~5 mired): A/B ~2,5%, G/M ~2% mỗi nấc
        val a = p.ab * 0.025
        val m = p.gm * 0.02
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

    private val cube: Lut? = p.lutFile?.let { Lut.load(it) }
    private val lutOut = FloatArray(3)
    private val calib = p.calib
    private val calibSat = p.calibSat
    // bù sáng khi dùng hiệu chỉnh: chỉ phần chênh so với mức bù sáng của công thức
    private val evLut: FloatArray? = if (p.calib != null || p.lutFile != null) FloatArray(1024) { i ->
        val k = 2.0.pow((evOverride - p.ev) * 0.6)
        (1.0 - (1.0 - i / 1023.0).pow(k)).toFloat()
    } else null

    private fun calibOut(ch: Int, v: Float): Float {
        val c = calib!!
        val x = v.coerceIn(0f, 1f) * 255f
        val i = x.toInt().coerceAtMost(254)
        val t = x - i
        return c[ch * 256 + i] + (c[ch * 256 + i + 1] - c[ch * 256 + i]) * t
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

            var r: Float; var g: Float; var b: Float
            if (cube != null) {
                cube.lookup(r0, g0, b0, lutOut)
                val el = evLut!!
                r = el[(lutOut[0].coerceIn(0f, 1f) * 1023f).toInt()]
                g = el[(lutOut[1].coerceIn(0f, 1f) * 1023f).toInt()]
                b = el[(lutOut[2].coerceIn(0f, 1f) * 1023f).toInt()]
            } else if (calib != null) {
                // màu học từ ảnh thật của máy
                r = calibOut(0, r0); g = calibOut(1, g0); b = calibOut(2, b0)
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * calibSat; g = l + (g - l) * calibSat; b = l + (b - l) * calibSat
                val el = evLut!!
                r = el[(r.coerceIn(0f, 1f) * 1023f).toInt()]
                g = el[(g.coerceIn(0f, 1f) * 1023f).toInt()]
                b = el[(b.coerceIn(0f, 1f) * 1023f).toInt()]
            } else {
                r = curve(r0 * gainR)
                g = curve(g0 * gainG)
                b = curve(b0 * gainB)

                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * satMul
                g = l + (g - l) * satMul
                b = l + (b - l) * satMul

                val ws = (1 - l) * (1 - l)
                val wh = l * l
                r += shR * ws + hiR * wh
                g += shG * ws + hiG * wh
                b += shB * ws + hiB * wh
            }

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

/**
 * Gỡ ngược màu của công thức đã dùng lúc chụp (đọc từ EXIF), đưa ảnh về gần trung tính
 * trước khi áp công thức mới. Chỉ gần đúng: vùng đã cháy sáng hoặc bị dìm đen không lấy lại được.
 */
class LookInverter(src0: Preset, private val amount: Float = 1f) {
    /** Nếu đã hiệu chỉnh công thức này theo máy thật thì dùng đường cong học được. */
    private val cal = Presets.match(src0)
    private val src = src0
    private val f = ColorEngine(src, 0.0, 1f)
    private val inv = FloatArray(1024)
    private val satInv = 1f / max(0.35f, f.satMul)
    private val calInv: FloatArray? = cal?.calib?.let { c ->
        // đảo từng đường cong 256 điểm
        FloatArray(768).also { out ->
            for (ch in 0 until 3) {
                val mono = FloatArray(256); var m = 0f
                for (i in 0 until 256) { m = max(m, c[ch * 256 + i]); mono[i] = m }
                var j = 0
                for (k in 0 until 256) {
                    val y = k / 255f
                    while (j < 255 && mono[j] < y) j++
                    out[ch * 256 + k] = when {
                        mono[j] < y -> 1f
                        j == 0 -> 0f
                        else -> {
                            val y0 = mono[j - 1]; val y1 = mono[j]
                            val t = if (y1 > y0) ((y - y0) / (y1 - y0)).coerceIn(0f, 1f) else 0.5f
                            ((j - 1) + t) / 255f
                        }
                    }
                }
            }
        }
    }
    private val calSatInv = 1f / max(0.35f, cal?.calibSat ?: 1f)

    private fun calIn(ch: Int, v: Float): Float {
        val c = calInv!!
        val x = v.coerceIn(0f, 1f) * 255f
        val i = x.toInt().coerceAtMost(254)
        val t = x - i
        return c[ch * 256 + i] + (c[ch * 256 + i + 1] - c[ch * 256 + i]) * t
    }

    init {
        // đường cong thuận phải đơn điệu mới đảo được
        val mono = FloatArray(1024)
        var m = 0f
        for (i in 0 until 1024) { m = max(m, f.lut[i]); mono[i] = m }
        var j = 0
        for (k in 0 until 1024) {
            val y = k / 1023f
            while (j < 1023 && mono[j] < y) j++
            inv[k] = when {
                mono[j] < y -> 1f
                j == 0 -> 0f
                else -> {
                    val y0 = mono[j - 1]; val y1 = mono[j]
                    val t = if (y1 > y0) ((y - y0) / (y1 - y0)).coerceIn(0f, 1f) else 0.5f
                    ((j - 1) + t) / 1023f
                }
            }
        }
    }

    private fun invCurve(v: Float): Float = inv[(v.coerceIn(0f, 1f) * 1023f).toInt()]

    fun process(px: IntArray, from: Int = 0, to: Int = px.size) {
        val orig = if (amount != 1f) px.copyOfRange(from, to) else null
        processRaw(px, from, to)
        if (orig != null) {
            // mức gỡ màu: <100% gỡ bớt, >100% gỡ mạnh hơn mô hình
            for (i in from until to) {
                val o = orig[i - from]; val c = px[i]
                fun mix(sh: Int): Int {
                    val ov = (o shr sh) and 0xFF; val nv = (c shr sh) and 0xFF
                    return (ov + (nv - ov) * amount).toInt().coerceIn(0, 255)
                }
                px[i] = (c and -0x1000000) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
    }

    private fun processRaw(px: IntArray, from: Int, to: Int) {
        for (i in from until to) {
            val c = px[i]
            val a = c ushr 24
            var r = ((c shr 16) and 0xFF) / 255f
            var g = ((c shr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f

            if (calInv != null) {
                val l0 = 0.299f * r + 0.587f * g + 0.114f * b
                r = l0 + (r - l0) * calSatInv; g = l0 + (g - l0) * calSatInv; b = l0 + (b - l0) * calSatInv
                r = calIn(0, r); g = calIn(1, g); b = calIn(2, b)
                val ri = (max(0f, min(1f, r)) * 255f + 0.5f).toInt()
                val gi = (max(0f, min(1f, g)) * 255f + 0.5f).toInt()
                val bi = (max(0f, min(1f, b)) * 255f + 0.5f).toInt()
                px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
                continue
            }

            // bỏ tông màu riêng của Look
            var l = 0.299f * r + 0.587f * g + 0.114f * b
            val ws = (1 - l) * (1 - l); val wh = l * l
            r -= f.shR * ws + f.hiR * wh
            g -= f.shG * ws + f.hiG * wh
            b -= f.shB * ws + f.hiB * wh

            // trả lại độ bão hòa
            l = 0.299f * r + 0.587f * g + 0.114f * b
            r = l + (r - l) * satInv
            g = l + (g - l) * satInv
            b = l + (b - l) * satInv

            // đảo đường cong tone, rồi đảo cân bằng trắng
            r = invCurve(r) / f.gainR
            g = invCurve(g) / f.gainG
            b = invCurve(b) / f.gainB

            val ri = (max(0f, min(1f, r)) * 255f + 0.5f).toInt()
            val gi = (max(0f, min(1f, g)) * 255f + 0.5f).toInt()
            val bi = (max(0f, min(1f, b)) * 255f + 0.5f).toInt()
            px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }
}
