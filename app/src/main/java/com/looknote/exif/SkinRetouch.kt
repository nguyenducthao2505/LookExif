package com.looknote.exif

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Vùng loại trừ hình elip (mắt, lông mày, môi, chân mũi). */
data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/** Một khuôn mặt: khung bao và các vùng không được làm mịn. */
data class FaceRegion(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val exclude: List<Ellipse>
) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Làm mịn da và xóa mụn, viết thuần Kotlin (không cần thư viện).
 * Tính ở độ phân giải làm việc (~1600px) rồi trả về "độ lệch" để cộng lên ảnh lớn,
 * nhờ đó giữ nguyên chi tiết nhỏ của ảnh gốc và tiết kiệm bộ nhớ.
 */
object SkinRetouch {

    class Delta(val w: Int, val h: Int, val d: ShortArray)

    /**
     * @param smooth mức làm mịn 0..2 (trên 1 là mạnh như Soft Skin High của máy)
     * @param blemish bật xóa mụn
     */
    fun compute(px: IntArray, w: Int, h: Int, faces: List<FaceRegion>, smooth: Float, blemish: Boolean): Delta {
        val n = w * h
        val R = FloatArray(n); val G = FloatArray(n); val B = FloatArray(n)
        val Y = FloatArray(n); val Cb = FloatArray(n); val Cr = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            R[i] = r; G[i] = g; B[i] = b
            Y[i] = 0.299f * r + 0.587f * g + 0.114f * b
            Cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            Cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        // ----- kích thước tham chiếu theo khuôn mặt -----
        val fw = if (faces.isNotEmpty()) faces.map { it.width }.sorted()[faces.size / 2]
        else max(w, h) * 0.15f

        // ----- mặt nạ da M0 (cứng) -----
        val m0 = skinMask(w, h, Y, Cb, Cr, faces)
        val hasFace = faces.isNotEmpty()

        // ----- bảo vệ chi tiết dạng đường: sợi tóc, lông mi, viền -----
        val protect = lineMask(Y, w, h)
        // da "sạch" để lấy mẫu màu: bỏ qua sợi tóc
        val mClean = FloatArray(n) { m0[it] * (1f - protect[it]) }

        // làm mềm mép mặt nạ, rồi trừ vùng sợi tóc (giữ sắc nét)
        val mSoft = m0.copyOf()
        blur(mSoft, w, h, max(1, (fw * 0.02f).roundToInt()))
        for (i in 0 until n) mSoft[i] *= (1f - protect[i])

        var r1 = R.copyOf(); var g1 = G.copyOf(); var b1 = B.copyOf()

        // ----- xóa mụn -----
        if (blemish && hasFace) {
            val rs = max(1, (fw * 0.004f).roundToInt())
            val rb = max(3, (fw * 0.03f).roundToInt())
            val ys = Y.copyOf(); blur(ys, w, h, rs)
            val yb = normBlur(Y, mClean, w, h, rb)
            val crs = Cr.copyOf(); blur(crs, w, h, rs)
            val crb = normBlur(Cr, mClean, w, h, rb)
            val spot = FloatArray(n)
            for (i in 0 until n) {
                if (m0[i] < 0.6f) continue
                val dark = (yb[i] - ys[i]) - 4f       // tối hơn xung quanh
                val red = (crs[i] - crb[i]) - 4f      // đỏ hơn xung quanh
                val s = max(dark / 6f, red / 5f)
                if (s > 0f) spot[i] = min(1f, s)
            }
            // nới rộng và làm mềm vùng mụn
            blur(spot, w, h, max(1, (fw * 0.008f).roundToInt()))
            for (i in 0 until n) spot[i] = min(1f, spot[i] * 3.5f) * (1f - protect[i])
            // màu vá: trung bình vùng da xung quanh, bỏ qua chính chấm mụn
            val wgt = FloatArray(n) { mClean[it] * (1f - spot[it]) }
            val fr = normBlur(R, wgt, w, h, (rb * 0.7f).roundToInt())
            val fg = normBlur(G, wgt, w, h, (rb * 0.7f).roundToInt())
            val fb = normBlur(B, wgt, w, h, (rb * 0.7f).roundToInt())
            for (i in 0 until n) {
                val a = spot[i]
                if (a <= 0f) continue
                r1[i] += (fr[i] - r1[i]) * a
                g1[i] += (fg[i] - g1[i]) * a
                b1[i] += (fb[i] - b1[i]) * a
            }
        }

        // ----- làm mịn: tách tần số -----
        if (smooth > 0f) {
            val rFine = max(1, (fw * 0.003f).roundToInt())
            val rBase = max(2, (fw * 0.018f).roundToInt())
            val amount = min(1f, smooth) * (if (hasFace) 0.9f else 0.5f)
            // trên mức 1: giữ ít vân da hơn và phủ đều hơn
            val fineKeep = if (smooth <= 1f) 0.55f else (0.55f - (smooth - 1f) * 0.45f).coerceAtLeast(0.08f)
            val extra = if (smooth <= 1f) 1f else 1f + (smooth - 1f) * 0.1f
            val planes = arrayOf(r1, g1, b1)
            for (p in planes) {
                val base = normBlur(p, mClean, w, h, rBase)
                val fineBlur = p.copyOf(); blur(fineBlur, w, h, rFine)
                for (i in 0 until n) {
                    val m = mSoft[i]
                    if (m <= 0.001f) continue
                    val fine = p[i] - fineBlur[i]
                    val sm = base[i] + fine * fineKeep
                    p[i] += (sm - p[i]) * min(1f, m * amount * extra)
                }
            }
            r1 = planes[0]; g1 = planes[1]; b1 = planes[2]
        }

        // ----- độ lệch so với ảnh gốc -----
        val d = ShortArray(n * 3)
        for (i in 0 until n) {
            d[i * 3] = (r1[i] - R[i]).roundToInt().coerceIn(-255, 255).toShort()
            d[i * 3 + 1] = (g1[i] - G[i]).roundToInt().coerceIn(-255, 255).toShort()
            d[i * 3 + 2] = (b1[i] - B[i]).roundToInt().coerceIn(-255, 255).toShort()
        }
        return Delta(w, h, d)
    }

    /** Cộng độ lệch (phóng to song tuyến) lên các dòng [y0, y0+rows) của ảnh lớn kích thước W×H. */
    fun apply(delta: Delta, buf: IntArray, W: Int, H: Int, y0: Int, rows: Int) {
        val sx = delta.w.toFloat() / W
        val sy = delta.h.toFloat() / H
        val dw = delta.w; val dh = delta.h; val d = delta.d
        for (yy in 0 until rows) {
            val fy = ((y0 + yy) + 0.5f) * sy - 0.5f
            val y1 = fy.toInt().coerceIn(0, dh - 1)
            val y2 = min(dh - 1, y1 + 1)
            val ty = (fy - y1).coerceIn(0f, 1f)
            for (x in 0 until W) {
                val fx = (x + 0.5f) * sx - 0.5f
                val x1 = fx.toInt().coerceIn(0, dw - 1)
                val x2 = min(dw - 1, x1 + 1)
                val tx = (fx - x1).coerceIn(0f, 1f)
                val i11 = (y1 * dw + x1) * 3; val i12 = (y1 * dw + x2) * 3
                val i21 = (y2 * dw + x1) * 3; val i22 = (y2 * dw + x2) * 3
                val k = yy * W + x
                val c = buf[k]
                var out = c and -0x1000000
                for (ch in 0 until 3) {
                    val top = d[i11 + ch] + (d[i12 + ch] - d[i11 + ch]) * tx
                    val bot = d[i21 + ch] + (d[i22 + ch] - d[i21 + ch]) * tx
                    val dv = top + (bot - top) * ty
                    val shift = 16 - ch * 8
                    val v = (((c shr shift) and 0xFF) + dv).roundToInt().coerceIn(0, 255)
                    out = out or (v shl shift)
                }
                buf[k] = out
            }
        }
    }

    /**
     * Nhận diện chi tiết dạng đường mảnh (sợi tóc, lông mi) bằng ma trận Hessian:
     * đường mảnh cong mạnh theo một hướng, còn chấm mụn cong đều mọi hướng.
     * Trả về 0..1 (1 = cần giữ nguyên).
     */
    fun lineMask(Y: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val ys = Y.copyOf(); blur(ys, w, h, 1)
        val hxx = FloatArray(n); val hyy = FloatArray(n); val hxy = FloatArray(n)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val c = ys[i]
                hxx[i] = ys[i - 2] - 2 * c + ys[i + 2]
                hyy[i] = ys[i - 2 * w] - 2 * c + ys[i + 2 * w]
                hxy[i] = (ys[i + 2 * w + 2] - ys[i + 2 * w - 2] - ys[i - 2 * w + 2] + ys[i - 2 * w - 2]) / 4f
            }
        }
        // lấy trung bình theo vùng: đường thẳng giữ được hướng, viền tròn thì triệt tiêu
        blur(hxx, w, h, 2); blur(hyy, w, h, 2); blur(hxy, w, h, 2)
        val L = FloatArray(n)
        val blob = FloatArray(n)
        val t = 1.6f
        for (i in 0 until n) {
            val ixx = hxx[i]; val iyy = hyy[i]; val ixy = hxy[i]
            val half = (ixx + iyy) / 2f
            val r = sqrt(((ixx - iyy) / 2f) * ((ixx - iyy) / 2f) + ixy * ixy)
            val l1 = half + r; val l2 = half - r
            val a1 = max(kotlin.math.abs(l1), kotlin.math.abs(l2))
            val a2 = min(kotlin.math.abs(l1), kotlin.math.abs(l2))
            if (a1 <= t) continue
            // chấm tròn tối: cong dương đều theo hai hướng
            if (l2 > 0f && a2 > 0.5f * a1) blob[i] = min(1f, (a2 - t * 0.5f) / t)
            val elong = 1f - (a2 / (0.4f * a1))
            if (elong <= 0f) continue
            L[i] = min(1f, (a1 - t) / t) * min(1f, elong * 1.5f)
        }
        blur(L, w, h, 1)
        // vùng quanh chấm tròn không được bảo vệ (để xóa sạch cả viền mụn)
        blur(blob, w, h, 3)
        for (i in 0 until n) L[i] = min(1f, L[i] * 3f) * (1f - min(1f, blob[i] * 4f))
        return L
    }

    // ---------- mặt nạ da ----------
    private fun skinMask(w: Int, h: Int, Y: FloatArray, Cb: FloatArray, Cr: FloatArray, faces: List<FaceRegion>): FloatArray {
        val m = FloatArray(w * h)
        if (faces.isEmpty()) {
            // không thấy mặt: dùng dải màu da chuẩn, mức nhẹ
            for (i in m.indices) {
                if (Y[i] < 50f) continue
                val cb = Cb[i]; val cr = Cr[i]
                if (cb in 80f..125f && cr in 135f..170f) m[i] = 0.6f
            }
            return m
        }
        for (f in faces) {
            // lấy mẫu màu da ở vùng má
            var sCb = 0.0; var sCr = 0.0; var sCb2 = 0.0; var sCr2 = 0.0; var cnt = 0
            val sx0 = (f.left + f.width * 0.2f).toInt().coerceIn(0, w - 1)
            val sx1 = (f.left + f.width * 0.8f).toInt().coerceIn(0, w - 1)
            val sy0 = (f.top + f.height * 0.35f).toInt().coerceIn(0, h - 1)
            val sy1 = (f.top + f.height * 0.75f).toInt().coerceIn(0, h - 1)
            for (y in sy0..sy1) for (x in sx0..sx1) {
                if (excluded(f, x.toFloat(), y.toFloat(), 1.2f)) continue
                val i = y * w + x
                if (Y[i] < 40f) continue
                sCb += Cb[i]; sCr += Cr[i]; sCb2 += Cb[i] * Cb[i]; sCr2 += Cr[i] * Cr[i]; cnt++
            }
            if (cnt < 20) continue
            val mCb = (sCb / cnt).toFloat(); val mCr = (sCr / cnt).toFloat()
            val dCb = max(4f, sqrt(max(0.0, sCb2 / cnt - mCb * mCb.toDouble())).toFloat())
            val dCr = max(4f, sqrt(max(0.0, sCr2 / cnt - mCr * mCr.toDouble())).toFloat())

            // vùng xử lý: mặt + cổ
            val x0 = (f.left - f.width * 0.4f).toInt().coerceIn(0, w - 1)
            val x1 = (f.right + f.width * 0.4f).toInt().coerceIn(0, w - 1)
            val y0 = (f.top - f.height * 0.3f).toInt().coerceIn(0, h - 1)
            val y1 = (f.bottom + f.height * 1.2f).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                if (Y[i] < 35f) continue
                val a = (Cb[i] - mCb) / dCb
                val b = (Cr[i] - mCr) / dCr
                val dist = sqrt(a * a + b * b)
                var v = ((3.2f - dist) / 1.4f).coerceIn(0f, 1f)
                if (v <= 0f) continue
                if (excluded(f, x.toFloat(), y.toFloat(), 1f)) v = 0f
                if (v > m[i]) m[i] = v
            }
        }
        return m
    }

    private fun excluded(f: FaceRegion, x: Float, y: Float, scale: Float): Boolean {
        for (e in f.exclude) {
            val dx = (x - e.cx) / (e.rx * scale)
            val dy = (y - e.cy) / (e.ry * scale)
            if (dx * dx + dy * dy <= 1f) return true
        }
        return false
    }

    // ---------- làm mờ ----------
    /** Làm mờ có trọng số: chỉ các điểm có trọng số (da) đóng góp vào trung bình. */
    private fun normBlur(src: FloatArray, wgt: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val num = FloatArray(src.size) { src[it] * wgt[it] }
        val den = wgt.copyOf()
        blur(num, w, h, r); blur(den, w, h, r)
        for (i in num.indices) num[i] = if (den[i] > 1e-3f) num[i] / den[i] else src[i]
        return num
    }

    /** Làm mờ hộp 3 lượt (xấp xỉ Gauss), tại chỗ. */
    fun blur(a: FloatArray, w: Int, h: Int, r: Int) {
        if (r < 1) return
        val tmp = FloatArray(max(w, h))
        repeat(3) {
            for (y in 0 until h) boxLine(a, y * w, 1, w, r, tmp)
            for (x in 0 until w) boxLine(a, x, w, h, r, tmp)
        }
    }

    private fun boxLine(a: FloatArray, start: Int, step: Int, len: Int, r: Int, tmp: FloatArray) {
        val win = 2 * r + 1
        var sum = 0f
        // biên: lặp lại điểm đầu/cuối
        for (k in -r..r) sum += a[start + k.coerceIn(0, len - 1) * step]
        for (i in 0 until len) {
            tmp[i] = sum / win
            val outIdx = (i - r).coerceIn(0, len - 1)
            val inIdx = (i + r + 1).coerceIn(0, len - 1)
            sum += a[start + inIdx * step] - a[start + outIdx * step]
        }
        for (i in 0 until len) a[start + i * step] = tmp[i]
    }
}
