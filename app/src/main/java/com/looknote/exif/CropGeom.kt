package com.looknote.exif

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Thiết lập cắt / xoay ảnh.
 * Khung cắt (l, t, r, b) tính theo tỷ lệ 0..1 của "khung" = ảnh sau khi xoay [quarter] × 90° và lật,
 * chưa tính góc cân thẳng [angle] (ảnh xoay quanh tâm khung, khung cắt phải nằm gọn trong ảnh).
 */
data class CropSpec(
    val quarter: Int = 0,
    val flip: Boolean = false,
    val angle: Float = 0f,
    val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f,
    /** Tỷ lệ đang khóa (rộng/cao theo điểm ảnh), 0 = tự do. Chỉ để nhớ lựa chọn trên màn cắt. */
    val aspect: Float = 0f
) {
    val q get() = ((quarter % 4) + 4) % 4
    val isIdentity get() = q == 0 && !flip && abs(angle) < 0.01f &&
        l <= 0.0005f && t <= 0.0005f && r >= 0.9995f && b >= 0.9995f

    fun describe(w: Int, h: Int): String {
        if (isIdentity) return "Chưa cắt / xoay"
        val fw = CropGeom.frameW(w.toFloat(), h.toFloat(), q); val fh = CropGeom.frameH(w.toFloat(), h.toFloat(), q)
        val ow = ((r - l) * fw).toInt(); val oh = ((b - t) * fh).toInt()
        val parts = ArrayList<String>()
        parts += "${ow}×${oh}"
        if (aspect > 0f) parts += CropGeom.ratioName(aspect)
        if (q != 0) parts += "xoay ${q * 90}°"
        if (flip) parts += "lật ngang"
        if (abs(angle) >= 0.01f) parts += "nghiêng " + (if (angle > 0) "+" else "") + String.format("%.1f", angle) + "°"
        return parts.joinToString(" · ")
    }
}

/** Hình học cắt / xoay (thuần Kotlin để kiểm thử được). */
object CropGeom {

    fun frameW(w: Float, h: Float, q: Int) = if (q % 2 == 1) h else w
    fun frameH(w: Float, h: Float, q: Int) = if (q % 2 == 1) w else h

    val ratios: List<Pair<String, Float>> = listOf(
        "1:1" to 1f, "4:5" to 0.8f, "5:4" to 1.25f, "3:4" to 0.75f, "4:3" to 4f / 3f,
        "2:3" to 2f / 3f, "3:2" to 1.5f, "9:16" to 9f / 16f, "16:9" to 16f / 9f
    )

    fun ratioName(a: Float): String =
        ratios.firstOrNull { abs(it.second - a) < 0.005f }?.first ?: String.format("%.2f:1", a)

    /**
     * Phép biến đổi ảnh gốc (w×h) → khung: [a, b, c, d, e, f] với x' = a·x + b·y + c, y' = d·x + e·y + f.
     * Thứ tự: dời tâm về gốc, lật ngang, xoay (quarter·90° + angle, chiều kim đồng hồ), dời về tâm khung.
     */
    fun forward(w: Float, h: Float, s: CropSpec): FloatArray {
        val th = Math.toRadians(90.0 * s.q + s.angle)
        val cs = cos(th).toFloat(); val sn = sin(th).toFloat()
        val fx = if (s.flip) -1f else 1f
        val a = cs * fx; val b = -sn; val d = sn * fx; val e = cs
        val fw = frameW(w, h, s.q); val fh = frameH(w, h, s.q)
        val c = -(a * w / 2f + b * h / 2f) + fw / 2f
        val f = -(d * w / 2f + e * h / 2f) + fh / 2f
        // làm tròn các hệ số gần 0 / ±1 để xoay 90° khớp đúng điểm ảnh
        fun snap(v: Float) = if (abs(v) < 1e-6f) 0f else if (abs(abs(v) - 1f) < 1e-6f) Math.signum(v) else v
        return floatArrayOf(snap(a), snap(b), c, snap(d), snap(e), f)
    }

    fun invert(m: FloatArray): FloatArray {
        val det = m[0] * m[4] - m[1] * m[3]
        val ia = m[4] / det; val ib = -m[1] / det; val id = -m[3] / det; val ie = m[0] / det
        val ic = -(ia * m[2] + ib * m[5]); val iff = -(id * m[2] + ie * m[5])
        return floatArrayOf(ia, ib, ic, id, ie, iff)
    }

    fun map(m: FloatArray, x: Float, y: Float, out: FloatArray) {
        out[0] = m[0] * x + m[1] * y + m[2]; out[1] = m[3] * x + m[4] * y + m[5]
    }

    /** Khung cắt (tỷ lệ 0..1) có nằm gọn trong ảnh đã xoay không. */
    fun fits(w: Float, h: Float, s: CropSpec, l: Float, t: Float, r: Float, b: Float): Boolean {
        if (l < -1e-4f || t < -1e-4f || r > 1.0001f || b > 1.0001f || r <= l || b <= t) return false
        if (abs(s.angle) < 0.01f) return true
        val inv = invert(forward(w, h, s))
        val fw = frameW(w, h, s.q); val fh = frameH(w, h, s.q)
        val o = FloatArray(2)
        val tol = 0.5f
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) {
            map(inv, x * fw, y * fh, o)
            if (o[0] < -tol || o[1] < -tol || o[0] > w + tol || o[1] > h + tol) return false
        }
        return true
    }

    /** Thu nhỏ khung cắt quanh tâm (giữ tỷ lệ) cho tới khi nằm gọn trong ảnh. */
    fun shrinkToFit(w: Float, h: Float, s: CropSpec): CropSpec {
        if (fits(w, h, s, s.l, s.t, s.r, s.b)) return s
        val cx = (s.l + s.r) / 2f; val cy = (s.t + s.b) / 2f
        val hw = (s.r - s.l) / 2f; val hh = (s.b - s.t) / 2f
        var lo = 0f; var hi = 1f
        repeat(30) {
            val m = (lo + hi) / 2f
            if (fits(w, h, s, cx - hw * m, cy - hh * m, cx + hw * m, cy + hh * m)) lo = m else hi = m
        }
        // nếu tâm lệch ra ngoài vùng hợp lệ thì dời dần về tâm khung
        if (lo < 0.05f && (abs(cx - 0.5f) > 1e-3f || abs(cy - 0.5f) > 1e-3f)) {
            return shrinkToFit(w, h, s.copy(l = 0.5f - hw, t = 0.5f - hh, r = 0.5f + hw, b = 0.5f + hh))
        }
        return s.copy(l = cx - hw * lo, t = cy - hh * lo, r = cx + hw * lo, b = cy + hh * lo)
    }

    /**
     * Khung lớn nhất có tỷ lệ [aspect] (rộng/cao theo điểm ảnh; 0 = tỷ lệ khung) đặt giữa khung,
     * nằm gọn trong ảnh đã xoay góc [CropSpec.angle].
     */
    fun maxInner(w: Float, h: Float, s: CropSpec, aspect: Float): CropSpec {
        val fw = frameW(w, h, s.q); val fh = frameH(w, h, s.q)
        val a = if (aspect > 0f) aspect else fw / fh
        var cw = fw; var ch = fw / a
        if (ch > fh) { ch = fh; cw = fh * a }
        val nw = cw / fw / 2f; val nh = ch / fh / 2f
        return shrinkToFit(w, h, s.copy(l = 0.5f - nw, t = 0.5f - nh, r = 0.5f + nw, b = 0.5f + nh, aspect = aspect))
    }

    /** Kích thước ảnh kết quả (điểm ảnh) khi cắt ảnh w×h. */
    fun outSize(w: Int, h: Int, s: CropSpec): Pair<Int, Int> {
        val fw = frameW(w.toFloat(), h.toFloat(), s.q); val fh = frameH(w.toFloat(), h.toFloat(), s.q)
        return max(1, Math.round((s.r - s.l) * fw)) to max(1, Math.round((s.b - s.t) * fh))
    }

    fun clamp01(v: Float) = min(1f, max(0f, v))
}
