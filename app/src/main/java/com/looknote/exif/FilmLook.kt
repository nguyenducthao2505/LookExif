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
     * Áp trực tiếp lên ảnh Sony (nên bật "Đổi công thức" để gỡ màu lúc chụp trước).
     */
    private class Fit(val a: FloatArray, val p: FloatArray, val w: FloatArray)

    private val fits: Map<String, Fit> = mapOf(
        "fuji_cc" to Fit(
            floatArrayOf(0.2587f, 0.0859f, 0.0033f, 0.1099f, -0.1408f, 0.0504f, 0.0178f, 0.0729f, -0.2998f, -22.4065f, -8.9509f, -1.0158f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-17.9842f, 32.1110f, 25.2688f, 28.0498f, -41.9185f, 16.5045f, -1.6092f, -35.0398f, 78.7339f, -15.1042f, 21.6959f, 3.0208f, -0.5730f, 5.3612f, -8.5142f, 9.6530f, -2.0999f, 2.6404f, 10.6888f, -38.7550f, -22.7951f, 18.9348f, 30.9454f, -48.0733f, 14.1070f, -9.7386f, -19.3189f, -11.1692f, -3.5883f, -3.4327f, 12.0212f, -15.7701f, -5.5221f, -5.3931f, 36.9833f, -4.2628f, 0.3258f, -15.2652f, 12.0236f, 5.9007f, -4.1159f, 15.7178f, -2.5008f, 3.3583f, 2.2773f, -4.1893f, -9.6018f, 18.6482f, 0.5964f, 9.0669f, 14.2491f, -8.7486f, 7.1949f, -22.3554f, -49.8600f, 2.6908f, 6.7559f, -9.0305f, -0.8165f, -20.8369f, 23.5381f, 10.4828f, -3.4431f, 32.1403f, 11.4110f, -6.8828f, 7.6886f, 6.4240f, -18.9469f, -37.0158f, -6.8335f, -11.2978f)),
        "fuji_cn" to Fit(
            floatArrayOf(0.1831f, 0.0152f, -0.0533f, 0.0540f, -0.2747f, 0.1776f, -0.0296f, 0.0276f, -0.4124f, -13.4641f, 0.0004f, 5.3208f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(3.2720f, 56.2273f, 94.6078f, 16.6539f, -34.3809f, -61.5535f, 4.8837f, 2.2656f, 33.3334f, -15.5067f, 5.9117f, 23.1794f, -10.5226f, 12.4711f, -1.1091f, 9.2880f, 1.5526f, 21.8809f, 2.5163f, -38.0105f, -41.6848f, 22.6803f, 4.8396f, -7.7050f, 4.9582f, -29.3753f, -37.1420f, -8.4524f, 21.8148f, 25.7680f, 5.6238f, 2.9430f, -4.4268f, 6.6302f, 26.2935f, -3.8427f, -4.7610f, -23.0056f, -21.5997f, 13.9669f, 0.9117f, -6.8199f, -7.4643f, 18.6179f, 0.3201f, -5.7858f, -13.0692f, 18.2648f, 10.9024f, -6.6762f, 7.1588f, -13.0898f, 3.9940f, -24.0071f, -64.3213f, -29.5874f, 0.0360f, -25.9903f, -14.7021f, -0.0428f, 29.6417f, 1.5316f, -10.8296f, 53.3531f, 46.2938f, 17.1815f, 53.4752f, 34.6639f, 35.3298f, -86.2770f, -55.1298f, -65.6769f)),
        "fuji_nn" to Fit(
            floatArrayOf(0.2674f, 0.0270f, 0.0372f, 0.0860f, -0.2126f, 0.3059f, -0.0018f, 0.0370f, -0.1920f, -23.6509f, -2.1212f, -0.9214f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-13.1906f, 30.0335f, -0.0292f, 16.1059f, -17.8746f, 37.9529f, -2.9333f, -39.3976f, 58.2093f, -22.3561f, 36.0450f, -11.6087f, -0.1672f, 13.3697f, -17.7514f, 3.0280f, 37.5334f, -1.3832f, 13.7004f, -14.1359f, -39.4355f, 14.9544f, 29.9146f, -28.0262f, 5.8031f, -25.9141f, 10.9282f, -14.7706f, -10.5393f, 6.7633f, 17.2234f, 3.1152f, -16.5743f, -7.5000f, -14.7292f, 72.2084f, 4.4007f, -13.0640f, -6.5427f, 12.2297f, -33.4661f, 30.0168f, -0.4654f, 10.5601f, -3.7943f, -5.4555f, -3.7209f, -29.9482f, 7.3903f, 32.8173f, -7.4853f, -8.6461f, 8.0917f, -11.3845f, -45.8890f, -10.2362f, -40.9922f, 6.1613f, -1.2776f, -37.3286f, 22.5400f, -5.7312f, 18.5292f, 23.1957f, 2.3490f, 18.3551f, 0.9392f, -16.5915f, 21.5823f, -24.8067f, 1.4031f, -35.0116f)),
        "fuji_eterna" to Fit(
            floatArrayOf(0.2471f, 0.0822f, -0.0274f, 0.0809f, -0.1318f, 0.0956f, 0.0096f, 0.0331f, -0.2119f, -22.7006f, -7.2603f, 3.2229f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-16.3067f, -23.7708f, -1.9288f, 18.3124f, 42.7659f, 40.2208f, -5.4088f, -24.5741f, 85.5239f, -18.7116f, 20.5816f, -29.9681f, 1.7573f, 0.5561f, -28.3406f, 5.2329f, -14.1487f, 9.3742f, 10.2808f, -21.8218f, -22.4129f, 17.8410f, 28.0622f, -48.1117f, 17.9634f, -2.7327f, 18.4783f, -10.3652f, -8.1617f, -2.8796f, 12.9863f, -17.7359f, -7.7734f, -2.0829f, 25.0854f, 34.7709f, 2.7817f, -12.6070f, 16.5963f, 8.6690f, -0.9583f, 28.8734f, -4.2072f, 10.6060f, -8.3770f, -5.9414f, -9.1877f, 5.4886f, -5.5424f, 7.4182f, -12.6264f, -7.2537f, 20.5040f, -23.3968f, -27.7011f, 0.5608f, -10.2798f, 6.0666f, -22.4505f, -12.7084f, 19.3972f, -4.7603f, -5.8326f, 1.8923f, 3.4542f, -9.8655f, 0.8787f, -0.8854f, 9.2881f, -18.5268f, 3.4507f, -21.0413f)),
        "fuji_velvia" to Fit(
            floatArrayOf(0.2853f, -0.0378f, 0.0233f, 0.0723f, 0.1603f, -0.0481f, 0.0242f, -0.1364f, 0.2537f, -25.4950f, 3.6105f, -2.1734f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-7.6128f, 37.6382f, 0.0136f, 19.8320f, -41.5146f, 26.3463f, -4.9128f, -17.6507f, -10.1832f, -27.4524f, 86.6610f, -119.5216f, 6.0051f, -29.4190f, 87.0364f, 4.7521f, 9.1410f, -108.8225f, 4.0409f, -12.3988f, -13.3354f, 9.4002f, 33.5890f, 4.9780f, -4.8707f, -51.3773f, 41.3626f, -13.0650f, -71.5921f, 24.9157f, 16.4194f, -24.7797f, 61.4393f, 9.9680f, -41.8838f, 54.5442f, 7.9129f, 15.0841f, 2.2758f, 11.5113f, -66.1111f, 107.9060f, 3.3493f, 0.5152f, -16.3241f, -11.6787f, 35.3082f, -45.6970f, 10.5455f, 104.2188f, -82.3317f, -5.5327f, 20.5775f, 14.8385f, -48.9303f, 21.6153f, -22.4206f, 1.9264f, -0.5883f, 26.5845f, 50.7780f, 19.5985f, 2.3595f, 12.2147f, 24.8506f, -57.2533f, -34.0947f, -25.6686f, 8.3145f, -7.0594f, -22.4301f, 15.1389f)),
        "fuji_provia" to Fit(
            floatArrayOf(0.2517f, 0.0284f, 0.0027f, 0.0775f, 0.0576f, -0.0186f, 0.0090f, -0.0264f, -0.0495f, -22.3724f, -2.4009f, -1.9289f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-10.1964f, 12.8570f, 35.4420f, 17.7258f, 0.4656f, -4.2060f, -8.7598f, -14.4960f, 16.9033f, -20.3477f, 43.4854f, -22.5108f, 1.9363f, -7.1537f, 10.4214f, 11.7182f, -24.6467f, 26.7726f, 11.1074f, -32.7357f, -21.6545f, 20.3972f, 17.4667f, 1.3246f, 4.3139f, -27.2681f, -5.4227f, -16.9048f, -20.4987f, -2.9629f, 14.9126f, -33.6948f, 22.1315f, -4.1555f, 18.1296f, 10.5655f, 5.3428f, -3.9929f, 2.8547f, 5.6695f, -12.4560f, 12.3045f, -1.4202f, 8.3995f, -6.9587f, -4.6664f, 12.6402f, -8.4959f, 11.6622f, 38.4764f, -1.6806f, -7.9030f, 28.1846f, -30.1649f, -33.2550f, 4.4415f, 11.1589f, -8.6660f, -26.8122f, 6.6788f, 22.7820f, 35.5245f, -22.0286f, 25.9179f, 9.3579f, -34.3595f, -10.4306f, -8.1179f, -2.3378f, -23.5167f, -14.7492f, 0.6001f)),
        "fuji_astia" to Fit(
            floatArrayOf(0.2297f, -0.0058f, 0.1075f, 0.0719f, 0.0034f, 0.0226f, 0.0244f, -0.0041f, -0.0371f, -21.1043f, 0.5655f, -12.0438f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-8.7524f, -9.1989f, 27.4865f, 16.2158f, 23.5486f, 13.6518f, -0.9995f, 41.9046f, 8.1198f, -16.3983f, 16.2060f, -13.5784f, -3.0441f, -21.5512f, -5.0221f, 16.7972f, -52.0601f, 22.0902f, 6.7019f, -19.0146f, -29.6482f, 12.9430f, -21.9303f, 2.5739f, 9.5668f, -10.9569f, 0.3429f, -9.2952f, -26.9573f, 20.4674f, 14.8514f, -23.6360f, 24.2481f, -1.3634f, 14.5233f, 17.5039f, 4.5278f, 13.7919f, -16.1155f, 0.8976f, 18.0320f, 8.9338f, -2.9194f, 9.9350f, -12.5216f, -6.1943f, 8.5676f, -18.9604f, 0.0486f, 22.3830f, -11.7996f, -11.7809f, 23.3729f, -23.6431f, -23.1826f, -1.5599f, -12.3939f, -4.1447f, -4.1757f, -4.1949f, 26.9893f, -3.1897f, 13.6609f, 6.8423f, 35.8488f, -25.1448f, -9.1277f, -16.3623f, -6.2212f, -17.8225f, -10.4099f, 3.4030f)),
        "fuji_pns" to Fit(
            floatArrayOf(0.2459f, 0.0820f, -0.0024f, 0.0668f, -0.0286f, -0.0419f, 0.0094f, 0.0086f, -0.1778f, -21.7316f, -6.9210f, -1.3882f),
            floatArrayOf(72.9399f, 18.4509f, 18.1026f, 89.3668f, 7.7559f, 9.4588f, 80.5595f, -6.8648f, -26.1289f, 75.4296f, -17.0632f, 37.6881f, 94.3726f, -1.2950f, -5.3734f, 92.9285f, -21.5333f, -2.6658f, 86.1108f, 5.6702f, 50.3843f, 72.8079f, -2.9885f, -40.4228f, 75.6883f, 35.9935f, 3.2288f, 69.0378f, 23.4594f, -37.2776f, 92.0279f, -22.8634f, 55.4401f, 88.3208f, -2.6245f, 59.4379f, 59.9588f, 19.7278f, -60.2475f, 87.4911f, -27.1946f, 16.0121f, 65.9340f, 53.6049f, 21.3504f, 94.1476f, -16.4733f, 76.1862f, 81.5620f, 25.2691f, -13.2522f, 86.3494f, -33.0330f, -19.6924f, 98.7903f, -0.8344f, 0.2456f, 96.0430f, -0.4529f, -1.7920f, 91.5484f, -0.0900f, -2.7805f, 87.4636f, -1.1278f, -2.0622f, 78.2810f, 1.5057f, -4.0117f, 63.1259f, 1.9870f, -5.2337f),
            floatArrayOf(-15.8946f, 2.5083f, 18.2625f, 23.3462f, 7.6074f, 9.1713f, -6.4706f, -34.5562f, 75.7228f, -21.2282f, 31.7793f, -21.6444f, 3.7818f, -9.5972f, -4.6181f, 7.0182f, 2.5663f, 14.2330f, 10.8046f, -29.7387f, -31.2589f, 19.5409f, 38.9403f, -52.6836f, 8.5357f, -10.2204f, 0.5909f, -11.9192f, -17.4485f, -3.7474f, 13.2506f, -12.7204f, 6.4018f, -0.3954f, 14.9015f, 49.2309f, 2.5731f, -11.9370f, 20.2366f, 9.4244f, -16.5851f, 17.7723f, -0.5191f, 8.1183f, -5.7309f, -6.5935f, -0.1336f, -22.9555f, 3.3114f, 24.3353f, -7.7432f, -8.2859f, 17.2736f, -31.8895f, -44.7477f, -9.3167f, -7.6889f, -4.2807f, 11.7628f, -18.0463f, 31.2088f, 8.5429f, 7.2191f, 24.7291f, -7.3061f, -14.2166f, -11.1607f, 1.8679f, 1.9758f, -23.7202f, -9.1819f, -8.0414f))
    )

    private fun fitLab(f: Fit, lab: DoubleArray): DoubleArray {
        val l = lab[0]; val a = lab[1]; val b = lab[2]
        val fade = (minOf(l, 100.0 - l) / 4.0).coerceIn(0.0, 1.0)
        val d = DoubleArray(3)
        for (k in 0..2) d[k] = l * f.a[k] + a * f.a[3 + k] + b * f.a[6 + k] + f.a[9 + k]
        val s2 = 2.0 * 32.0 * 32.0
        for (j in 0 until f.p.size / 3) {
            val dl = (l - f.p[3 * j]) * 0.5; val da = a - f.p[3 * j + 1]; val db = b - f.p[3 * j + 2]
            val g = exp(-(dl * dl + da * da + db * db) / s2)
            for (k in 0..2) d[k] += g * f.w[3 * j + k]
        }
        return doubleArrayOf((l + d[0] * fade).coerceIn(0.0, 100.0), a + d[1] * fade, b + d[2] * fade)
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
