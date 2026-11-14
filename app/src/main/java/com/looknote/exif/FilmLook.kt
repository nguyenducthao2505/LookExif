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
    private fun neutral(id: String, name: String) = Spec(id, name, f(1f, 1f, 1f), 0f, 1f, f(0f, 0f, 0f), f(1f, 1f, 1f), 1f,
        f(0f, 0f, 0f), f(0f, 0f, 0f), 0f, emptyList())
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
        // Ba màu dưới đây dựng hoàn toàn từ số đo ảnh Fuji X-T5 thật (xem fits), thông số Spec chỉ để giữ chỗ.
        neutral("fuji_provia", "Fuji Provia (Tiêu chuẩn)"),
        neutral("fuji_astia", "Fuji Astia (Mềm)"),
        neutral("fuji_pns", "Fuji Pro Neg. Std"),
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
     * Dùng cho các màu chưa có ảnh đối chứng (Fuji 400H, Superia, Reala; Kodak giữ độ ấm vừa phải).
     * Canon không có trong bảng nên giữ nguyên như cũ.
     * Mục đích: màu phim chỉ đổi sắc thái, không phủ một lớp ám vàng/xanh lên cả ảnh.
     */
    private val castTargets: Map<String, FloatArray> = mapOf(
        "fuji_400h" to f(-1.0f, -1.5f, -1.0f, -1.5f, -1.0f, -1.0f),
        "fuji_superia" to f(-1.5f, 1.0f, -1.5f, 1.5f, -1.0f, 1.0f),
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

    /**
     * Màu Fuji dựng từ số đo: ảnh bảng màu 24 ô chụp cùng cảnh bằng Sony a6700 (Creative Look ST) và
     * Fuji X-T5 (từng giả lập phim). Hiệu chỉnh trong CIELAB = biến đổi tuyến tính [a] (hàng L, a, b, 1)
     * + tổng hàm Gauss quanh 24 màu tham chiếu [p] với trọng số [w] (σ = 32, trục L thu 0,5 lần).
     * v1.15: bỏ phần chênh sáng/tương phản chung giữa hai lần chụp (do phơi sáng, không phải do giả lập),
     * chỉ giữ khác biệt tông riêng của từng giả lập và toàn bộ khác biệt màu.
     * v1.16: Classic Chrome dựng lại từ 4 cặp ảnh A7C II (ST) / X-T30 III của người dùng, bảng màu 64 ô
     * (khoảng L 5–95, nên không cần chặn vùng tối: lmin = 1, σ = 20).
     * Áp trực tiếp lên ảnh Sony (nên bật "Đổi công thức" để gỡ màu lúc chụp trước).
     */
    private class Fit(val a: FloatArray, val p: FloatArray, val w: FloatArray, val lmin: Double = 60.0, val sig: Double = 32.0)

    private val fits: Map<String, Fit> = mapOf(
        "fuji_cc" to Fit(
            floatArrayOf(-0.0217f, 0.0086f, -0.0477f, 0.0987f, -0.0808f, 0.1105f, 0.0354f, -0.0042f, -0.0147f, 1.8890f, 0.4443f, 1.4916f),
            floatArrayOf(72.5829f, 8.8361f, 4.6352f, 63.6425f, 32.5419f, 53.2378f, 31.2722f, 10.2236f, -44.5451f, 35.1409f, 46.1923f, 6.4247f, 66.7288f, -16.6774f, -29.9090f, 76.7944f, -24.5600f, -17.1775f, 39.6656f, 12.1171f, -52.0296f, 45.2998f, 30.5754f, -35.2395f, 79.6900f, 2.4875f, -5.3231f, 70.7194f, 15.1503f, 59.2563f, 53.9193f, 21.6057f, 14.9781f, 73.6569f, 4.7823f, 56.4805f, 49.7947f, 5.9615f, -14.4374f, 57.5521f, -19.7340f, 26.1927f, 61.8805f, 24.7200f, 38.8423f, 67.9481f, 30.3160f, -7.5467f, 75.3037f, -13.3338f, -19.1353f, 43.4613f, 62.3587f, 24.5181f, 42.4205f, -18.1752f, 12.3743f, 52.0283f, 52.0592f, -1.0265f, 48.1738f, 0.5202f, -43.7407f, 27.4935f, -7.5040f, -9.0475f, 69.2370f, 16.3296f, 28.3902f, 59.0215f, 26.4372f, -27.7581f, 66.7048f, -13.9684f, 33.7520f, 68.0742f, 9.6975f, -16.4829f, 82.9899f, -13.8595f, -7.9472f, 78.1208f, 10.1601f, 16.8986f, 44.7374f, 15.9741f, 8.4344f, 56.6526f, 44.3044f, 43.4043f, 67.8455f, 15.6231f, -0.0477f, 59.4154f, -7.7671f, -36.1825f, 79.5407f, 0.0883f, -10.6464f, 58.0916f, -18.4052f, -1.7497f, 75.0934f, 14.9989f, -6.6129f, 28.8111f, 25.5841f, -26.0183f, 34.2155f, 54.6053f, 18.7133f, 14.0536f, 3.7471f, -8.4877f, 69.5095f, -7.8082f, -26.3797f, 74.0315f, -9.4392f, 7.2323f),
            floatArrayOf(0.0287f, 0.1152f, 0.3780f, -0.3975f, -0.5721f, -1.0732f, -0.0738f, -0.6314f, 0.3014f, 0.0395f, 0.8599f, 0.8772f, -0.2016f, 0.8670f, -0.7219f, -0.1849f, 0.9963f, -0.7464f, -0.1507f, -0.5748f, 0.3130f, 0.4713f, -1.1836f, -0.0662f, 0.0204f, -0.3722f, 0.3480f, -0.4072f, -0.7864f, 0.0701f, 0.1126f, 1.1770f, 0.1170f, -0.2212f, -0.7768f, 0.3227f, -0.1978f, -0.1410f, -0.2030f, 0.3597f, -0.4022f, 0.1195f, -0.0940f, -0.2695f, -0.1329f, 0.0431f, 0.3120f, 0.1509f, -0.0810f, 0.3488f, -0.4026f, 0.0379f, -0.0152f, -1.6798f, 0.2342f, -0.3632f, 0.2734f, -0.1513f, 0.0157f, 1.3524f, -0.1249f, -0.1148f, 0.2181f, -0.0921f, -0.1637f, -0.1691f, 0.0952f, -0.1277f, 0.5072f, 0.3042f, -0.8378f, -0.2377f, 0.3742f, -0.4660f, 0.1017f, -0.0808f, -0.2430f, -0.2164f, 0.0951f, -0.1126f, 0.1229f, 0.1745f, -0.1620f, 0.6132f, -0.1467f, 1.2226f, 0.0285f, -0.1575f, -0.1695f, -2.1201f, -0.0451f, 0.5517f, 0.0154f, -0.1044f, 0.3362f, -0.2246f, -0.0012f, -0.3940f, 0.1966f, 0.2478f, -0.1276f, 0.1372f, -0.0371f, 0.1750f, -0.0801f, 0.3514f, -0.8836f, -0.0109f, 0.1502f, 0.5764f, -0.6591f, -0.3301f, -0.0581f, -0.2020f, -0.0808f, 0.1885f, -0.3517f, 0.3189f, -0.6246f, 0.6657f),
            lmin = 1.0, sig = 20.0),
        "fuji_cn" to Fit(
            floatArrayOf(-0.0630f, 0.0152f, -0.0533f, -0.0234f, -0.2747f, 0.1776f, -0.0374f, 0.0276f, -0.4124f, 8.1516f, 0.0004f, 5.3208f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(14.1053f, 56.2273f, 94.6078f, -2.8764f, -34.3809f, -61.5535f, 8.1600f, 2.2656f, 33.3334f, 4.1315f, 5.9117f, 23.1794f, -10.4193f, 12.4711f, -1.1091f, 0.8521f, 1.5526f, 21.8809f, -6.2138f, -38.0105f, -41.6848f, 5.5938f, 4.8396f, -7.7050f, -2.5890f, -29.3753f, -37.1420f, 3.5403f, 21.8148f, 25.7680f, -7.7873f, 2.9430f, -4.4268f, 7.1667f, 26.2935f, -3.8427f, -7.6490f, -23.0056f, -21.5997f, 5.4333f, 0.9117f, -6.8199f, -5.4459f, 18.6179f, 0.3201f, 0.5273f, -13.0692f, 18.2648f, 6.0380f, -6.6762f, 7.1588f, -4.1847f, 3.9940f, -24.0071f, -22.0854f, -29.5874f, 0.0360f, -21.2456f, -14.7021f, -0.0428f, 1.2823f, 1.5316f, -10.8296f, 30.8175f, 46.2938f, 17.1815f, 53.7042f, 34.6639f, 35.3298f, -56.4338f, -55.1298f, -65.6769f)),
        "fuji_nn" to Fit(
            floatArrayOf(0.0213f, 0.0270f, 0.0372f, 0.0086f, -0.2126f, 0.3059f, -0.0097f, 0.0370f, -0.1920f, -2.0352f, -2.1212f, -0.9214f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-2.3574f, 30.0335f, -0.0292f, -3.4243f, -17.8746f, 37.9529f, 0.3430f, -39.3976f, 58.2093f, -2.7180f, 36.0450f, -11.6087f, -0.0639f, 13.3697f, -17.7514f, -5.4080f, 37.5334f, -1.3832f, 4.9703f, -14.1359f, -39.4355f, -2.1321f, 29.9146f, -28.0262f, -1.7441f, -25.9141f, 10.9282f, -2.7779f, -10.5393f, 6.7633f, 3.8123f, 3.1152f, -16.5743f, -6.9635f, -14.7292f, 72.2084f, 1.5127f, -13.0640f, -6.5427f, 3.6960f, -33.4661f, 30.0168f, 1.5530f, 10.5601f, -3.7943f, 0.8576f, -3.7209f, -29.9482f, 2.5260f, 32.8173f, -7.4853f, 0.2590f, 8.0917f, -11.3845f, -3.6532f, -10.2362f, -40.9922f, 10.9060f, -1.2776f, -37.3286f, -5.8193f, -5.7312f, 18.5292f, 0.6600f, 2.3490f, 18.3551f, 1.1682f, -16.5915f, 21.5823f, 5.0364f, 1.4031f, -35.0116f)),
        "fuji_eterna" to Fit(
            floatArrayOf(0.0010f, 0.0822f, -0.0274f, 0.0035f, -0.1318f, 0.0956f, 0.0017f, 0.0331f, -0.2119f, -1.0849f, -7.2603f, 3.2229f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-5.4735f, -23.7708f, -1.9288f, -1.2178f, 42.7659f, 40.2208f, -2.1325f, -24.5741f, 85.5239f, 0.9266f, 20.5816f, -29.9681f, 1.8606f, 0.5561f, -28.3406f, -3.2031f, -14.1487f, 9.3742f, 1.5506f, -21.8218f, -22.4129f, 0.7546f, 28.0622f, -48.1117f, 10.4163f, -2.7327f, 18.4783f, 1.6275f, -8.1617f, -2.8796f, -0.4248f, -17.7359f, -7.7734f, -1.5464f, 25.0854f, 34.7709f, -0.1063f, -12.6070f, 16.5963f, 0.1354f, -0.9583f, 28.8734f, -2.1888f, 10.6060f, -8.3770f, 0.3717f, -9.1877f, 5.4886f, -10.4067f, 7.4182f, -12.6264f, 1.6514f, 20.5040f, -23.3968f, 14.5348f, 0.5608f, -10.2798f, 10.8113f, -22.4505f, -12.7084f, -8.9622f, -4.7603f, -5.8326f, -20.6433f, 3.4542f, -9.8655f, 1.1077f, -0.8854f, 9.2881f, 11.3163f, 3.4507f, -21.0413f)),
        "fuji_velvia" to Fit(
            floatArrayOf(0.0391f, -0.0378f, 0.0233f, -0.0051f, 0.1603f, -0.0481f, 0.0163f, -0.1364f, 0.2537f, -3.8793f, 3.6105f, -2.1734f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(3.2204f, 37.6382f, 0.0136f, 0.3017f, -41.5146f, 26.3463f, -1.6365f, -17.6507f, -10.1832f, -7.8143f, 86.6610f, -119.5216f, 6.1084f, -29.4190f, 87.0364f, -3.6839f, 9.1410f, -108.8225f, -4.6893f, -12.3988f, -13.3354f, -7.6863f, 33.5890f, 4.9780f, -12.4179f, -51.3773f, 41.3626f, -1.0723f, -71.5921f, 24.9157f, 3.0084f, -24.7797f, 61.4393f, 10.5045f, -41.8838f, 54.5442f, 5.0249f, 15.0841f, 2.2758f, 2.9777f, -66.1111f, 107.9060f, 5.3677f, 0.5152f, -16.3241f, -5.3656f, 35.3082f, -45.6970f, 5.6812f, 104.2188f, -82.3317f, 3.3724f, 20.5775f, 14.8385f, -6.6944f, 21.6153f, -22.4206f, 6.6711f, -0.5883f, 26.5845f, 22.4186f, 19.5985f, 2.3595f, -10.3210f, 24.8506f, -57.2533f, -33.8657f, -25.6686f, 8.3145f, 22.7838f, -22.4301f, 15.1389f)),
        "fuji_provia" to Fit(
            floatArrayOf(0.0055f, 0.0284f, 0.0027f, 0.0001f, 0.0576f, -0.0186f, 0.0012f, -0.0264f, -0.0495f, -0.7567f, -2.4009f, -1.9289f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(0.6368f, 12.8570f, 35.4420f, -1.8044f, 0.4656f, -4.2060f, -5.4835f, -14.4960f, 16.9033f, -0.7096f, 43.4854f, -22.5108f, 2.0397f, -7.1537f, 10.4214f, 3.2823f, -24.6467f, 26.7726f, 2.3772f, -32.7357f, -21.6545f, 3.3107f, 17.4667f, 1.3246f, -3.2333f, -27.2681f, -5.4227f, -4.9121f, -20.4987f, -2.9629f, 1.5015f, -33.6948f, 22.1315f, -3.6190f, 18.1296f, 10.5655f, 2.4548f, -3.9929f, 2.8547f, -2.8642f, -12.4560f, 12.3045f, 0.5982f, 8.3995f, -6.9587f, 1.6467f, 12.6402f, -8.4959f, 6.7979f, 38.4764f, -1.6806f, 1.0021f, 28.1846f, -30.1649f, 8.9809f, 4.4415f, 11.1589f, -3.9212f, -26.8122f, 6.6788f, -5.5774f, 35.5245f, -22.0286f, 3.3822f, 9.3579f, -34.3595f, -10.2016f, -8.1179f, -2.3378f, 6.3265f, -14.7492f, 0.6001f)),
        "fuji_astia" to Fit(
            floatArrayOf(-0.0164f, -0.0058f, 0.1075f, -0.0055f, 0.0034f, 0.0226f, 0.0165f, -0.0041f, -0.0371f, 0.5114f, 0.5655f, -12.0438f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(2.0808f, -9.1989f, 27.4865f, -3.3145f, 23.5486f, 13.6518f, 2.2767f, 41.9046f, 8.1198f, 3.2398f, 16.2060f, -13.5784f, -2.9408f, -21.5512f, -5.0221f, 8.3612f, -52.0601f, 22.0902f, -2.0283f, -19.0146f, -29.6482f, -4.1435f, -21.9303f, 2.5739f, 2.0196f, -10.9569f, 0.3429f, 2.6975f, -26.9573f, 20.4674f, 1.4403f, -23.6360f, 24.2481f, -0.8269f, 14.5233f, 17.5039f, 1.6398f, 13.7919f, -16.1155f, -7.6360f, 18.0320f, 8.9338f, -0.9010f, 9.9350f, -12.5216f, 0.1189f, 8.5676f, -18.9604f, -4.8157f, 22.3830f, -11.7996f, -2.8758f, 23.3729f, -23.6431f, 19.0533f, -1.5599f, -12.3939f, 0.6001f, -4.1757f, -4.1949f, -1.3701f, -3.1897f, 13.6609f, -15.6934f, 35.8488f, -25.1448f, -8.8987f, -16.3623f, -6.2212f, 12.0207f, -10.4099f, 3.4030f)),
        "fuji_pns" to Fit(
            floatArrayOf(-0.0002f, 0.0820f, -0.0024f, -0.0107f, -0.0286f, -0.0419f, 0.0015f, 0.0086f, -0.1778f, -0.1159f, -6.9210f, -1.3882f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-5.0614f, 2.5083f, 18.2625f, 3.8160f, 7.6074f, 9.1713f, -3.1944f, -34.5562f, 75.7228f, -1.5901f, 31.7793f, -21.6444f, 3.8851f, -9.5972f, -4.6181f, -1.4177f, 2.5663f, 14.2330f, 2.0745f, -29.7387f, -31.2589f, 2.4544f, 38.9403f, -52.6836f, 0.9885f, -10.2204f, 0.5909f, 0.0735f, -17.4485f, -3.7474f, -0.1605f, -12.7204f, 6.4018f, 0.1412f, 14.9015f, 49.2309f, -0.3149f, -11.9370f, 20.2366f, 0.8907f, -16.5851f, 17.7723f, 1.4992f, 8.1183f, -5.7309f, -0.2804f, -0.1336f, -22.9555f, -1.5529f, 24.3353f, -7.7432f, 0.6192f, 17.2736f, -31.8895f, -2.5118f, -9.3167f, -7.6889f, 0.4641f, 11.7628f, -18.0463f, 2.8494f, 8.5429f, 7.2191f, 2.1934f, -7.3061f, -14.2166f, -10.9317f, 1.8679f, 1.9758f, 6.1230f, -9.1819f, -8.0414f))
    )

    private fun fitLab(f: Fit, lab: DoubleArray): DoubleArray {
        val l = lab[0]; val a = lab[1]; val b = lab[2]
        // Bảng màu đo được chỉ phủ L 60–99: vùng tối hơn dùng hiệu chỉnh tại L = 60, giảm dần về đen
        // để không bóp đen vùng tối (lỗi làm ảnh v1.14 đậm, tối).
        val lq = maxOf(l, f.lmin)
        val top = ((100.0 - l) / 4.0).coerceIn(0.0, 1.0)
        val wl = (l / f.lmin).coerceIn(0.0, 1.0) * top
        val wc = (l / 20.0).coerceIn(0.0, 1.0) * top
        val d = DoubleArray(3)
        for (k in 0..2) d[k] = lq * f.a[k] + a * f.a[3 + k] + b * f.a[6 + k] + f.a[9 + k]
        val s2 = 2.0 * f.sig * f.sig
        for (j in 0 until f.p.size / 3) {
            val dl = (lq - f.p[3 * j]) * 0.5; val da = a - f.p[3 * j + 1]; val db = b - f.p[3 * j + 2]
            val g = exp(-(dl * dl + da * da + db * db) / s2)
            for (k in 0..2) d[k] += g * f.w[3 * j + k]
        }
        return doubleArrayOf((l + d[0] * wl).coerceIn(0.0, 100.0), a + d[1] * wc, b + d[2] * wc)
    }

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
        val fit = fits[id]
        val maps = if (fit != null) null else castTargets[id]?.let { balanceMaps(s, it) }
        val n = 33
        val data = FloatArray(n * n * n * 3)
        val o = FloatArray(3)
        var k = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            if (fit != null) {
                val lab = fitLab(fit, toLab(ri / (n - 1.0), gi / (n - 1.0), bi / (n - 1.0)))
                val rgb = fromLab(lab[0], lab[1], lab[2])
                o[0] = rgb[0].toFloat(); o[1] = rgb[1].toFloat(); o[2] = rgb[2].toFloat()
            } else apply(s, ri / (n - 1f), gi / (n - 1f), bi / (n - 1f), o)
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
