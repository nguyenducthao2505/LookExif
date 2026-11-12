package com.looknote.exif

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Màu phim Fujifilm / Kodak dựng sẵn trong app (mô phỏng gần đúng theo đặc trưng màu
 * thường được mô tả của từng loại phim / giả lập phim, không phải LUT chính hãng).
 * Mỗi màu được tính thành một LUT 3D 33 điểm ngay trong app, nên không cần file kèm theo.
 */
object FilmLook {

    /** Điều chỉnh theo dải màu: [hue] độ xoay (+: cyan→lục→vàng→cam→đỏ→tím→lam), [sat] hệ số bão hòa, [lum] sáng/tối. */
    class Band(val ref: Float, val hue: Float = 0f, val sat: Float = 1f, val lum: Float = 0f, val width: Float = 28f)

    class Spec(
        val id: String, val name: String,
        val gain: FloatArray, val contrast: Float, val gamma: Float,
        val black: FloatArray, val white: FloatArray, val sat: Float,
        val shadowTint: FloatArray, val highTint: FloatArray,
        val hiDesat: Float, val bands: List<Band>
    )

    // góc màu (YIQ) của các dải tham chiếu
    private const val RED = 19f; private const val SKIN = 2f; private const val YELLOW = -38f
    private const val GREEN = -103f; private const val CYAN = -165f; private const val BLUE = 164f; private const val MAGENTA = 58f

    private fun f(vararg v: Float) = floatArrayOf(*v)
    private fun red(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(RED, h, s, l, 22f)
    private fun skin(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(SKIN, h, s, l, 18f)
    private fun yellow(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(YELLOW, h, s, l)
    private fun green(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(GREEN, h, s, l, 34f)
    private fun cyan(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(CYAN, h, s, l)
    private fun blue(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(BLUE, h, s, l, 30f)
    private fun magenta(h: Float = 0f, s: Float = 1f, l: Float = 0f) = Band(MAGENTA, h, s, l, 30f)

    val specs: List<Spec> = listOf(
        // ---------------- Fujifilm ----------------
        Spec("fuji_cc", "Fuji Classic Chrome", f(1f, 1f, 0.99f), 0.25f, 1.05f,
            f(0.02f, 0.02f, 0.025f), f(0.97f, 0.96f, 0.94f), 0.78f,
            f(-0.01f, 0f, 0.01f), f(0.01f, 0.005f, -0.01f), 0.15f,
            listOf(red(-4f, 0.85f, -0.03f), skin(-2f, 0.9f), yellow(-6f, 0.7f), green(6f, 0.65f, -0.03f),
                cyan(0f, 0.8f), blue(8f, 0.85f, -0.06f))),
        Spec("fuji_cn", "Fuji Classic Negative", f(1f, 1f, 1f), 0.4f, 1.0f,
            f(0.03f, 0.035f, 0.045f), f(0.97f, 0.95f, 0.9f), 0.85f,
            f(-0.02f, 0.005f, 0.02f), f(0.02f, 0.01f, -0.02f), 0.3f,
            listOf(red(4f, 1.1f, -0.04f), skin(3f, 0.95f), yellow(-10f, 0.8f), green(-18f, 0.75f),
                blue(10f, 0.9f, -0.03f))),
        Spec("fuji_nn", "Fuji Nostalgic Neg", f(1.01f, 1f, 0.98f), 0.1f, 0.98f,
            f(0.03f, 0.025f, 0.02f), f(0.99f, 0.96f, 0.88f), 0.95f,
            f(0f, 0f, 0.005f), f(0.025f, 0.012f, -0.02f), 0.15f,
            listOf(red(-3f, 1.05f), skin(-3f, 1f, 0.01f), yellow(4f, 1.1f), green(8f, 0.85f), blue(4f, 0.9f))),
        Spec("fuji_400h", "Fuji Pro 400H", f(0.99f, 1f, 1.01f), -0.15f, 0.9f,
            f(0.05f, 0.055f, 0.06f), f(0.97f, 0.98f, 0.98f), 0.8f,
            f(-0.01f, 0.008f, 0.012f), f(0f, 0.006f, 0.006f), 0.25f,
            listOf(red(4f, 0.85f), skin(3f, 0.9f, 0.02f), yellow(-8f, 0.75f), green(-20f, 0.75f, 0.02f),
                blue(6f, 0.85f, 0.02f))),
        Spec("fuji_superia", "Fuji Superia 400", f(0.99f, 1.01f, 1f), 0.3f, 1.0f,
            f(0.025f, 0.03f, 0.03f), f(0.98f, 0.98f, 0.96f), 1.1f,
            f(-0.005f, 0.012f, 0f), f(0.005f, 0.005f, -0.005f), 0f,
            listOf(red(2f, 1.15f), green(-6f, 1.25f, -0.02f), blue(4f, 1.1f), magenta(0f, 1.1f))),
        Spec("fuji_velvia", "Fuji Velvia 50", f(1f, 0.99f, 1.01f), 0.55f, 1.12f,
            f(0.01f, 0.008f, 0.015f), f(0.99f, 0.98f, 0.98f), 1.35f,
            f(0.008f, -0.004f, 0.014f), f(0f, 0f, 0f), 0f,
            listOf(red(5f, 1.2f), skin(2f, 0.72f), yellow(0f, 1.2f), green(-4f, 1.45f, -0.03f),
                blue(-4f, 1.3f, -0.06f), magenta(0f, 1.25f))),
        Spec("fuji_eterna", "Fuji Eterna (điện ảnh)", f(1f, 1f, 1f), -0.35f, 0.97f,
            f(0.05f, 0.055f, 0.06f), f(0.94f, 0.94f, 0.92f), 0.62f,
            f(-0.015f, 0.005f, 0.015f), f(0.008f, 0.004f, -0.006f), 0.2f,
            listOf(green(-10f, 0.75f), blue(0f, 0.8f), skin(0f, 0.9f))),
        Spec("fuji_reala", "Fuji Reala Ace", f(1f, 1f, 1f), 0.2f, 1.0f,
            f(0.015f, 0.015f, 0.018f), f(0.99f, 0.99f, 0.99f), 1.05f,
            f(-0.004f, 0f, 0.006f), f(0f, 0f, 0f), 0f,
            listOf(red(0f, 1.08f), skin(0f, 1f, 0.01f), green(-3f, 1.05f), blue(0f, 1.08f))),
        // ---------------- Kodak ----------------
        Spec("kodak_portra400", "Kodak Portra 400", f(1.02f, 1f, 0.97f), 0f, 0.95f,
            f(0.04f, 0.035f, 0.03f), f(0.98f, 0.96f, 0.92f), 0.9f,
            f(0f, 0f, 0.004f), f(0.015f, 0.008f, -0.012f), 0.2f,
            listOf(red(-5f, 0.95f), skin(-4f, 0.95f, 0.02f), yellow(-4f, 0.85f), green(10f, 0.7f),
                cyan(0f, 0.85f), blue(8f, 0.85f, 0.02f))),
        Spec("kodak_portra160", "Kodak Portra 160", f(1.01f, 1f, 0.985f), -0.1f, 0.95f,
            f(0.04f, 0.04f, 0.035f), f(0.98f, 0.97f, 0.94f), 0.82f,
            f(0f, 0f, 0f), f(0.01f, 0.006f, -0.006f), 0.25f,
            listOf(red(0f, 0.9f), skin(-2f, 0.9f, 0.02f), green(6f, 0.7f), blue(6f, 0.8f))),
        Spec("kodak_gold200", "Kodak Gold 200", f(1.04f, 1.01f, 0.94f), 0.2f, 0.97f,
            f(0.035f, 0.03f, 0.02f), f(0.99f, 0.96f, 0.86f), 1.08f,
            f(0.005f, 0f, -0.01f), f(0.025f, 0.015f, -0.025f), 0.1f,
            listOf(red(-6f, 1.05f), skin(-3f, 0.92f, 0.01f), yellow(3f, 1.25f, 0.02f), green(14f, 0.85f),
                cyan(0f, 0.9f), blue(10f, 0.9f))),
        Spec("kodak_ektar", "Kodak Ektar 100", f(1.01f, 1f, 0.99f), 0.35f, 1.0f,
            f(0.015f, 0.015f, 0.02f), f(0.99f, 0.98f, 0.97f), 1.25f,
            f(0f, 0f, 0.004f), f(0.006f, 0.003f, -0.004f), 0f,
            listOf(red(2f, 1.35f, -0.02f), skin(2f, 0.8f), yellow(0f, 1.15f), green(-2f, 1.15f),
                cyan(0f, 1.2f), blue(0f, 1.35f, -0.05f), magenta(0f, 1.2f))),
        Spec("kodak_ultramax", "Kodak UltraMax 400", f(1.03f, 1f, 0.96f), 0.3f, 0.98f,
            f(0.03f, 0.035f, 0.025f), f(0.99f, 0.97f, 0.92f), 1.12f,
            f(-0.005f, 0.01f, -0.002f), f(0.018f, 0.01f, -0.015f), 0f,
            listOf(red(-3f, 1.15f), skin(-2f, 0.92f), yellow(0f, 1.15f), green(8f, 0.95f), blue(6f, 1.05f))),
        Spec("kodak_colorplus", "Kodak ColorPlus 200", f(1.04f, 1.01f, 0.94f), 0.05f, 0.95f,
            f(0.055f, 0.05f, 0.035f), f(0.98f, 0.96f, 0.88f), 0.95f,
            f(0.01f, 0.004f, -0.008f), f(0.02f, 0.018f, -0.025f), 0.2f,
            listOf(red(-6f, 0.95f), skin(-5f, 1f, 0.01f), yellow(4f, 1.15f), green(20f, 0.65f), blue(8f, 0.8f, 0.02f))),
        Spec("kodak_e100", "Kodak Ektachrome E100", f(0.98f, 1f, 1.03f), 0.4f, 1.02f,
            f(0.01f, 0.012f, 0.02f), f(0.98f, 0.99f, 1f), 1.15f,
            f(-0.008f, 0f, 0.012f), f(0f, 0.003f, 0.006f), 0f,
            listOf(red(3f, 1.1f), skin(1f, 0.9f), yellow(-4f, 1f), green(-8f, 1.1f),
                cyan(0f, 1.2f), blue(4f, 1.3f, -0.03f))),
        Spec("kodak_kodachrome", "Kodak Kodachrome 64", f(1.02f, 1f, 0.97f), 0.5f, 1.06f,
            f(0.015f, 0.012f, 0.012f), f(0.99f, 0.97f, 0.93f), 1.15f,
            f(0.004f, 0f, -0.004f), f(0.012f, 0.006f, -0.01f), 0f,
            listOf(red(2f, 1.25f, -0.04f), skin(1f, 0.82f, 0.01f), yellow(3f, 1.2f, -0.01f), green(6f, 0.95f, -0.03f),
                cyan(-6f, 1f), blue(-4f, 1.2f, -0.06f)))
    )

    fun spec(id: String) = specs.firstOrNull { it.id == id }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t)
    }

    /** Màu phim cho một điểm (r, g, b trong 0..1). */
    fun apply(s: Spec, r0: Float, g0: Float, b0: Float, out: FloatArray) {
        var r = r0 * s.gain[0]; var g = g0 * s.gain[1]; var b = b0 * s.gain[2]
        // ---- màu theo dải (YIQ: giữ độ sáng khi xoay màu) ----
        var y = 0.299f * r + 0.587f * g + 0.114f * b
        var i = 0.596f * r - 0.274f * g - 0.322f * b
        var q = 0.211f * r - 0.523f * g + 0.312f * b
        val c = hypot(i, q)
        if (c > 1e-5f) {
            val h = Math.toDegrees(atan2(q, i).toDouble()).toFloat()
            var dh = 0f; var sm = 1f; var dl = 0f
            val cw = smooth(0.0f, 0.06f, c)  // màu gần xám thì ít bị đổi
            for (bd in s.bands) {
                var d = h - bd.ref
                while (d > 180f) d -= 360f
                while (d < -180f) d += 360f
                val w = exp(-(d / bd.width).pow(2)) * cw
                dh += bd.hue * w
                sm *= 1f + (bd.sat - 1f) * w
                dl += bd.lum * w * smooth(0f, 0.15f, c)
            }
            val desat = 1f - s.hiDesat * smooth(0.65f, 1f, y)
            val k = s.sat * sm * desat
            val a = Math.toRadians(dh.toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val i2 = (i * ca - q * sa) * k; val q2 = (i * sa + q * ca) * k
            i = i2; q = q2; y += dl
        }
        r = y + 0.956f * i + 0.621f * q
        g = y - 0.272f * i - 0.647f * q
        b = y - 1.106f * i + 1.703f * q
        // ---- đường cong phim từng kênh ----
        fun tone(x0: Float, ch: Int): Float {
            var x = x0.coerceIn(0f, 1f).pow(s.gamma)
            val sc = x * x * (3 - 2 * x)
            x += (sc - x) * s.contrast
            return s.black[ch] + x.coerceIn(0f, 1f) * (s.white[ch] - s.black[ch])
        }
        r = tone(r, 0); g = tone(g, 1); b = tone(b, 2)
        // ---- tông vùng tối / vùng sáng ----
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val ws = (1 - l) * (1 - l); val wh = l * l
        out[0] = (r + s.shadowTint[0] * ws + s.highTint[0] * wh).coerceIn(0f, 1f)
        out[1] = (g + s.shadowTint[1] * ws + s.highTint[1] * wh).coerceIn(0f, 1f)
        out[2] = (b + s.shadowTint[2] * ws + s.highTint[2] * wh).coerceIn(0f, 1f)
    }

    /** Dựng LUT 3D 33 điểm cho màu phim [id]. */
    fun lut(id: String): Lut? {
        val s = spec(id) ?: return null
        val n = 33
        val data = FloatArray(n * n * n * 3)
        val o = FloatArray(3)
        var k = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            apply(s, ri / (n - 1f), gi / (n - 1f), bi / (n - 1f), o)
            data[k++] = o[0]; data[k++] = o[1]; data[k++] = o[2]
        }
        return Lut(n, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    /** Các công thức màu phim đưa vào danh sách (mã lưu dạng "film:<id>"). */
    fun presets(): List<Preset> {
        var nf = 0; var nk = 0
        return specs.map { s ->
        Preset(if (s.id.startsWith("fuji")) "F${++nf}" else "K${++nk}", s.name, "FILM", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, "",
            lutFile = "film:" + s.id)
        }
    }
}
