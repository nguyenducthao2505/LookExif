package com.looknote.exif

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Màu phim Fujifilm / Kodak và màu máy Canon dựng sẵn trong app (mô phỏng gần đúng theo đặc trưng màu
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
            f(0.03f, 0.035f, 0.045f), f(0.97f, 0.955f, 0.92f), 0.85f,
            f(-0.02f, 0.005f, 0.02f), f(0.02f, 0.01f, -0.02f), 0.3f,
            listOf(red(4f, 1.1f, -0.04f), skin(3f, 0.95f), yellow(-10f, 0.8f), green(-18f, 0.75f),
                blue(10f, 0.9f, -0.03f))),
        Spec("fuji_nn", "Fuji Nostalgic Neg", f(1.01f, 1f, 0.98f), 0.1f, 0.98f,
            f(0.03f, 0.026f, 0.022f), f(0.99f, 0.97f, 0.92f), 0.95f,
            f(0f, 0f, 0.005f), f(0.015f, 0.008f, -0.012f), 0.15f,
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
        Spec("fuji_velvia", "Fuji Velvia 50", f(1f, 1f, 1.005f), 0.55f, 1.12f,
            f(0.01f, 0.008f, 0.015f), f(0.99f, 0.98f, 0.98f), 1.25f,
            f(0.003f, 0f, 0.008f), f(0f, 0f, 0f), 0f,
            listOf(red(4f, 1.05f), skin(2f, 0.72f), yellow(0f, 1.2f), green(-4f, 1.45f, -0.03f),
                blue(-4f, 1.3f, -0.06f), magenta(0f, 1.25f))),
        Spec("fuji_eterna", "Fuji Eterna (điện ảnh)", f(1f, 1f, 1f), -0.35f, 0.97f,
            f(0.05f, 0.055f, 0.06f), f(0.94f, 0.94f, 0.92f), 0.62f,
            f(-0.015f, 0.005f, 0.015f), f(0.008f, 0.004f, -0.006f), 0.2f,
            listOf(green(-10f, 0.75f), blue(0f, 0.8f), skin(0f, 0.9f))),
        Spec("fuji_reala", "Fuji Reala Ace", f(1f, 1f, 1f), 0.2f, 1.0f,
            f(0.015f, 0.015f, 0.018f), f(0.99f, 0.99f, 0.99f), 1.05f,
            f(-0.004f, 0f, 0.006f), f(0f, 0f, 0f), 0f,
            listOf(red(0f, 1.08f), skin(0f, 1f, 0.01f), green(-3f, 1.05f), blue(0f, 1.08f))),
        // ---------------- Canon (màu da trắng hồng) ----------------
        Spec("canon_std", "Canon Tiêu chuẩn", f(1.015f, 0.995f, 0.985f), 0.38f, 1.0f,
            f(0.012f, 0.01f, 0.012f), f(0.995f, 0.99f, 0.985f), 1.18f,
            f(0.004f, -0.004f, 0.004f), f(0.006f, 0f, 0.002f), 0f,
            listOf(red(2f, 0.98f), skin(5f, 0.95f, 0.025f), yellow(4f, 1.05f), green(6f, 1.12f), cyan(0f, 1.05f), blue(4f, 1.08f))),
        Spec("canon_portrait", "Canon Chân dung · da trắng hồng", f(1.008f, 0.992f, 0.998f), -0.02f, 0.92f,
            f(0.025f, 0.02f, 0.026f), f(0.995f, 0.985f, 0.99f), 0.96f,
            f(0.004f, -0.003f, 0.004f), f(0.008f, 0.001f, 0.002f), 0.1f,
            listOf(red(4f, 0.95f), skin(7f, 0.88f, 0.05f), yellow(-6f, 0.85f), green(4f, 1f), blue(4f, 1f))),
        Spec("canon_bright", "Canon trong trẻo · da hồng sáng", f(0.995f, 0.995f, 1.005f), -0.18f, 0.8f,
            f(0.05f, 0.048f, 0.056f), f(0.99f, 0.99f, 0.995f), 0.86f,
            f(0f, -0.002f, 0.006f), f(0.004f, 0.002f, 0.004f), 0.3f,
            listOf(red(6f, 0.9f), skin(6f, 0.88f, 0.05f), yellow(-8f, 0.8f), green(-6f, 0.9f, 0.02f), blue(6f, 0.95f, 0.02f))),
        // ---------------- Kodak ----------------
        Spec("kodak_portra400", "Kodak Portra 400", f(1.02f, 1f, 0.97f), 0f, 0.95f,
            f(0.04f, 0.036f, 0.032f), f(0.98f, 0.965f, 0.935f), 0.9f,
            f(0f, 0f, 0.004f), f(0.015f, 0.008f, -0.012f), 0.2f,
            listOf(red(-5f, 0.95f), skin(-4f, 0.95f, 0.02f), yellow(-4f, 0.85f), green(10f, 0.7f),
                cyan(0f, 0.85f), blue(8f, 0.85f, 0.02f))),
        Spec("kodak_portra160", "Kodak Portra 160", f(1.01f, 1f, 0.985f), -0.1f, 0.95f,
            f(0.04f, 0.04f, 0.035f), f(0.98f, 0.97f, 0.94f), 0.82f,
            f(0f, 0f, 0f), f(0.01f, 0.006f, -0.006f), 0.25f,
            listOf(red(0f, 0.9f), skin(-2f, 0.9f, 0.02f), green(6f, 0.7f), blue(6f, 0.8f))),
        Spec("kodak_gold200", "Kodak Gold 200", f(1.025f, 1.005f, 0.965f), 0.2f, 0.97f,
            f(0.035f, 0.03f, 0.022f), f(0.99f, 0.97f, 0.91f), 1.08f,
            f(0.004f, 0f, -0.006f), f(0.015f, 0.01f, -0.015f), 0.1f,
            listOf(red(-6f, 1.05f), skin(-3f, 0.92f, 0.01f), yellow(3f, 1.25f, 0.02f), green(14f, 0.85f),
                cyan(0f, 0.9f), blue(10f, 0.9f))),
        Spec("kodak_ektar", "Kodak Ektar 100", f(1.01f, 1f, 0.99f), 0.35f, 1.0f,
            f(0.015f, 0.015f, 0.02f), f(0.99f, 0.98f, 0.97f), 1.25f,
            f(0f, 0f, 0.004f), f(0.006f, 0.003f, -0.004f), 0f,
            listOf(red(2f, 1.15f, -0.02f), skin(2f, 0.8f), yellow(0f, 1.15f), green(-2f, 1.15f),
                cyan(0f, 1.2f), blue(0f, 1.35f, -0.05f), magenta(0f, 1.2f))),
        Spec("kodak_ultramax", "Kodak UltraMax 400", f(1.02f, 1f, 0.975f), 0.3f, 0.98f,
            f(0.03f, 0.035f, 0.025f), f(0.99f, 0.97f, 0.92f), 1.12f,
            f(-0.004f, 0.008f, -0.002f), f(0.01f, 0.006f, -0.01f), 0f,
            listOf(red(-3f, 1.15f), skin(-2f, 0.92f), yellow(0f, 1.15f), green(8f, 0.95f), blue(6f, 1.05f))),
        Spec("kodak_colorplus", "Kodak ColorPlus 200", f(1.025f, 1.005f, 0.965f), 0.05f, 0.95f,
            f(0.055f, 0.05f, 0.04f), f(0.98f, 0.965f, 0.91f), 0.95f,
            f(0.006f, 0.003f, -0.005f), f(0.012f, 0.012f, -0.015f), 0.2f,
            listOf(red(-6f, 0.95f), skin(-5f, 1f, 0.01f), yellow(4f, 1.15f), green(20f, 0.65f), blue(8f, 0.8f, 0.02f))),
        Spec("kodak_e100", "Kodak Ektachrome E100", f(0.985f, 1f, 1.02f), 0.4f, 1.02f,
            f(0.01f, 0.012f, 0.02f), f(0.98f, 0.99f, 1f), 1.15f,
            f(-0.008f, 0f, 0.012f), f(0f, 0.003f, 0.006f), 0f,
            listOf(red(3f, 1.1f), skin(1f, 0.9f), yellow(-4f, 1f), green(-8f, 1.1f),
                cyan(0f, 1.2f), blue(4f, 1.3f, -0.03f))),
        Spec("kodak_kodachrome", "Kodak Kodachrome 64", f(1.012f, 1f, 0.982f), 0.5f, 1.06f,
            f(0.015f, 0.012f, 0.012f), f(0.99f, 0.975f, 0.945f), 1.15f,
            f(0.004f, 0f, -0.004f), f(0.012f, 0.006f, -0.01f), 0f,
            listOf(red(2f, 1.25f, -0.04f), skin(1f, 0.82f, 0.01f), yellow(3f, 1.2f, -0.01f), green(6f, 0.95f, -0.03f),
                cyan(-6f, 1f), blue(-4f, 1.2f, -0.06f)))
    )

    fun spec(id: String) = specs.firstOrNull { it.id == id }

    /**
     * Mức ám màu đích trên trục xám: a*, b* ở vùng tối / giữa / sáng (L* khoảng 20 / 50 / 80); trắng giữ trắng.
     * Fuji lấy theo số đo ảnh Fuji X-T5 thật có bảng màu (so với Provia). Kodak giữ độ ấm vừa phải.
     * Canon không có trong bảng nên giữ nguyên như cũ.
     * Mục đích: màu phim chỉ đổi sắc thái, không phủ một lớp ám vàng/xanh lên cả ảnh.
     */
    private val castTargets: Map<String, FloatArray> = mapOf(
        "fuji_cc" to f(-0.9f, 1.5f, -1.0f, 2.5f, 0f, 1.0f),
        "fuji_cn" to f(2.0f, 6.0f, 1.0f, 4.0f, 1.0f, 2.0f),
        "fuji_nn" to f(-0.5f, 6.0f, 0f, 6.0f, 0.5f, 3.0f),
        "fuji_400h" to f(-1.0f, -1.5f, -1.0f, -1.5f, -1.0f, -1.0f),
        "fuji_superia" to f(-1.5f, 1.0f, -1.5f, 1.5f, -1.0f, 1.0f),
        "fuji_velvia" to f(0.4f, -1.0f, 0f, 0.5f, 0.5f, 0.5f),
        "fuji_eterna" to f(-0.5f, 2.5f, -1.0f, 2.0f, 0f, 1.0f),
        "fuji_reala" to f(0f, 0f, 0f, 0f, 0f, 0f),
        "kodak_portra400" to f(0f, 2.0f, 0.5f, 3.5f, 0.5f, 3.0f),
        "kodak_portra160" to f(0f, 1.5f, 0f, 2.5f, 0f, 2.0f),
        "kodak_gold200" to f(0f, 3.5f, 0f, 5.0f, 0f, 4.0f),
        "kodak_ektar" to f(0.5f, 0.5f, 0.5f, 1.5f, 0.5f, 1.5f),
        "kodak_ultramax" to f(-1.5f, 3.0f, -0.5f, 4.0f, 0f, 3.5f),
        "kodak_colorplus" to f(0f, 4.0f, -0.5f, 5.0f, -1.0f, 4.5f),
        "kodak_e100" to f(0f, -2.5f, 0f, -2.5f, 0f, -2.0f),
        "kodak_kodachrome" to f(0.5f, 2.0f, 0.5f, 3.0f, 0.5f, 3.0f)
    )

    // ---- CIELAB (sRGB, D65) ----
    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
    private fun fl(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) / (24389.0 / 27.0)

    private fun toLab(r: Double, g: Double, b: Double): DoubleArray {
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / 0.95047
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / 1.08883
        val fx = fl(x); val fy = fl(y); val fz = fl(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun fromLab(l: Double, a: Double, b: Double): DoubleArray {
        val fy = (l + 16.0) / 116.0; val fx = fy + a / 500.0; val fz = fy - b / 200.0
        val x = fi(fx) * 0.95047; val y = fi(fy); val z = fi(fz) * 1.08883
        val rl = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return doubleArrayOf(gam(rl.coerceIn(0.0, 1.0)), gam(gl.coerceIn(0.0, 1.0)), gam(bl.coerceIn(0.0, 1.0)))
    }

    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        val n = xs.size
        if (x <= xs[0]) return x + (ys[0] - xs[0])
        if (x >= xs[n - 1]) return x + (ys[n - 1] - xs[n - 1])
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (xs[m] <= x) lo = m else hi = m }
        val t = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + (ys[hi] - ys[lo]) * t
    }

    /**
     * Cân bằng trục xám: dựng 3 đường cong theo kênh để màu xám đi qua màu phim ra đúng mức ám đích [t],
     * giữ nguyên độ sáng và đặc trưng màu sắc của phim.
     */
    private fun balanceMaps(s: Spec, t: FloatArray): Array<FloatArray> {
        val n = 256
        val src = Array(3) { DoubleArray(n) }
        val dst = Array(3) { DoubleArray(n) }
        val o = FloatArray(3)
        val lx = doubleArrayOf(0.0, 20.0, 50.0, 80.0, 100.0)
        val la = doubleArrayOf(0.0, t[0].toDouble(), t[2].toDouble(), t[4].toDouble(), 0.0)
        val lb = doubleArrayOf(0.0, t[1].toDouble(), t[3].toDouble(), t[5].toDouble(), 0.0)
        for (k in 0 until n) {
            val v = k / (n - 1f)
            apply(s, v, v, v, o)
            val lab = toLab(o[0].toDouble(), o[1].toDouble(), o[2].toDouble())
            val l = lab[0].coerceIn(0.0, 100.0)
            val tg = fromLab(l, interp(l, lx, la), interp(l, lx, lb))
            for (ch in 0..2) { src[ch][k] = o[ch].toDouble(); dst[ch][k] = tg[ch] }
        }
        return Array(3) { ch ->
            val xs = src[ch]
            for (k in 1 until n) if (xs[k] < xs[k - 1] + 1e-7) xs[k] = xs[k - 1] + 1e-7
            FloatArray(1024) { i -> interp(i / 1023.0, xs, dst[ch]).coerceIn(0.0, 1.0).toFloat() }
        }
    }

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
        val maps = castTargets[id]?.let { balanceMaps(s, it) }
        val n = 33
        val data = FloatArray(n * n * n * 3)
        val o = FloatArray(3)
        var k = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            apply(s, ri / (n - 1f), gi / (n - 1f), bi / (n - 1f), o)
            if (maps != null) for (ch in 0..2) o[ch] = maps[ch][(o[ch].coerceIn(0f, 1f) * 1023f + 0.5f).toInt()]
            data[k++] = o[0]; data[k++] = o[1]; data[k++] = o[2]
        }
        return Lut(n, data, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    /** Các công thức màu phim đưa vào danh sách (mã lưu dạng "film:<id>"). */
    fun presets(): List<Preset> {
        var nf = 0; var nk = 0; var nc = 0
        return specs.map { s ->
        Preset(when { s.id.startsWith("fuji") -> "F${++nf}"; s.id.startsWith("canon") -> "C${++nc}"; else -> "K${++nk}" }, s.name, "FILM", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, "",
            lutFile = "film:" + s.id)
        }
    }
}
