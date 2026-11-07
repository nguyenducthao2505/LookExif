package com.looknote.exif

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Các phép đo trên ảnh (thuần Kotlin, chạy trên mảng điểm ảnh ARGB). */
object Analysis {

    class Hist(val r: IntArray, val g: IntArray, val b: IntArray, val y: IntArray,
               val clipHigh: Float, val clipLow: Float)

    /** Histogram 256 mức và tỉ lệ điểm cháy sáng (≥250) / mất chi tiết vùng tối (≤5). */
    fun histogram(px: IntArray): Hist {
        val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val y = IntArray(256)
        var hi = 0; var lo = 0
        for (c in px) {
            val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
            r[rr]++; g[gg]++; b[bb]++
            val yy = (0.299f * rr + 0.587f * gg + 0.114f * bb).roundToInt().coerceIn(0, 255)
            y[yy]++
            if (max(rr, max(gg, bb)) >= 250) hi++
            if (max(rr, max(gg, bb)) <= 5) lo++
        }
        val n = max(1, px.size).toFloat()
        return Hist(r, g, b, y, hi / n, lo / n)
    }

    /**
     * Mặt nạ vùng cháy sáng (đỏ) và vùng đen mất chi tiết (xanh) cùng kích thước ảnh.
     * Trả về ARGB, điểm không lỗi là trong suốt.
     */
    fun clipMask(px: IntArray): IntArray {
        val out = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val m = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))
            out[i] = when {
                m >= 250 -> 0xD0FF2020.toInt()
                m <= 5 -> 0xD02060FF.toInt()
                else -> 0
            }
        }
        return out
    }

    /**
     * Độ nét: phương sai của Laplacian trên vùng [x0,y0,x1,y1] (đã thu nhỏ về ~256px).
     * Số càng lớn càng nét. Ảnh nhòe thường dưới ~40.
     */
    fun sharpness(px: IntArray, w: Int, h: Int, x0: Int = 0, y0: Int = 0, x1: Int = w, y1: Int = h): Double {
        val cw = (x1 - x0).coerceAtLeast(8); val ch = (y1 - y0).coerceAtLeast(8)
        // thu nhỏ vùng về cạnh dài ~256px để so sánh được giữa các ảnh
        val scale = max(1.0, max(cw, ch) / 256.0)
        val tw = max(8, (cw / scale).toInt()); val th = max(8, (ch / scale).toInt())
        val g = FloatArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            // trung bình hộp khi thu nhỏ
            val sx0 = x0 + (tx * scale).toInt(); val sy0 = y0 + (ty * scale).toInt()
            val sx1 = min(x1, (x0 + ((tx + 1) * scale).toInt()).coerceAtLeast(sx0 + 1))
            val sy1 = min(y1, (y0 + ((ty + 1) * scale).toInt()).coerceAtLeast(sy0 + 1))
            var s = 0f; var n = 0
            for (yy in sy0 until sy1) for (xx in sx0 until sx1) {
                val c = px[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
                s += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF); n++
            }
            g[ty * tw + tx] = if (n > 0) s / n else 0f
        }
        var sum = 0.0; var sum2 = 0.0; var cnt = 0
        for (y in 1 until th - 1) for (x in 1 until tw - 1) {
            val i = y * tw + x
            val lap = (g[i - 1] + g[i + 1] + g[i - tw] + g[i + tw] - 4 * g[i]).toDouble()
            sum += lap; sum2 += lap * lap; cnt++
        }
        if (cnt == 0) return 0.0
        val mean = sum / cnt
        return sum2 / cnt - mean * mean
    }
}

/** Học màu thật của máy từ cặp ảnh cùng cảnh: một ảnh ST và một ảnh chụp bằng công thức. */
object Calibration {

    class Result(val curves: FloatArray, val sat: Float, val error: Double)

    /**
     * @param ref điểm ảnh của ảnh ST, [tgt] của ảnh công thức; cùng kích thước w×h (đã thu nhỏ).
     */
    fun fit(ref: IntArray, tgt: IntArray, w: Int, h: Int): Result {
        // làm mờ nhẹ để chịu được lệch khung hình vài điểm ảnh khi chụp cầm tay
        val rp = Array(3) { FloatArray(w * h) }; val tp = Array(3) { FloatArray(w * h) }
        for (i in 0 until w * h) for (ch in 0 until 3) {
            val sh = 16 - ch * 8
            rp[ch][i] = ((ref[i] shr sh) and 0xFF).toFloat()
            tp[ch][i] = ((tgt[i] shr sh) and 0xFF).toFloat()
        }
        val r = max(1, max(w, h) / 400)
        for (ch in 0 until 3) { SkinRetouch.blur(rp[ch], w, h, r); SkinRetouch.blur(tp[ch], w, h, r) }

        val curves = FloatArray(768)
        for (ch in 0 until 3) {
            val sum = DoubleArray(256); val cnt = IntArray(256)
            for (i in 0 until w * h) {
                val v = rp[ch][i].roundToInt().coerceIn(0, 255)
                sum[v] = sum[v] + tp[ch][i]; cnt[v]++
            }
            // điền các mức thiếu bằng nội suy, giữ đơn điệu, làm mượt
            val c = FloatArray(256) { -1f }
            for (v in 0 until 256) if (cnt[v] >= 3) c[v] = (sum[v] / cnt[v]).toFloat() / 255f
            fillGaps(c)
            for (v in 1 until 256) if (c[v] < c[v - 1]) c[v] = c[v - 1]
            val sm = FloatArray(256)
            for (v in 0 until 256) {
                var s = 0f; var n = 0
                for (k in -3..3) { val j = v + k; if (j in 0..255) { s += c[j]; n++ } }
                sm[v] = s / n
            }
            System.arraycopy(sm, 0, curves, ch * 256, 256)
        }

        // hệ số độ bão hòa còn thiếu sau khi khớp đường cong từng kênh
        var num = 0.0; var den = 0.0
        for (i in 0 until w * h) {
            val m = FloatArray(3) { ch -> curves[ch * 256 + rp[ch][i].roundToInt().coerceIn(0, 255)] * 255f }
            val lm = 0.299f * m[0] + 0.587f * m[1] + 0.114f * m[2]
            val lt = 0.299f * tp[0][i] + 0.587f * tp[1][i] + 0.114f * tp[2][i]
            for (ch in 0 until 3) {
                val a = m[ch] - lm; val b = tp[ch][i] - lt
                num += a * b; den += a * a
            }
        }
        val sat = if (den > 1e-6) (num / den).toFloat().coerceIn(0.5f, 1.6f) else 1f

        // sai số trung bình sau khi áp
        var err = 0.0
        for (i in 0 until w * h) {
            val m = FloatArray(3) { ch -> curves[ch * 256 + rp[ch][i].roundToInt().coerceIn(0, 255)] * 255f }
            val lm = 0.299f * m[0] + 0.587f * m[1] + 0.114f * m[2]
            for (ch in 0 until 3) err += kotlin.math.abs(lm + (m[ch] - lm) * sat - tp[ch][i])
        }
        return Result(curves, sat, err / (w * h * 3))
    }

    private fun fillGaps(c: FloatArray) {
        val known = c.indices.filter { c[it] >= 0f }
        if (known.isEmpty()) { for (i in c.indices) c[i] = i / 255f; return }
        for (i in c.indices) {
            if (c[i] >= 0f) continue
            val lo = known.lastOrNull { it < i }; val hi = known.firstOrNull { it > i }
            c[i] = when {
                lo == null -> c[hi!!] * i / max(1, hi)                       // kéo về 0 ở vùng đen
                hi == null -> c[lo] + (1f - c[lo]) * (i - lo) / max(1, 255 - lo) // kéo về 1 ở vùng sáng
                else -> c[lo] + (c[hi] - c[lo]) * (i - lo) / (hi - lo)
            }
        }
    }
}

/**
 * Xóa viền tím (và viền xanh lá) do quang sai màu dọc trục: chỉ khử màu những điểm có sắc tím/xanh lá
 * nằm sát ranh giới sáng–tối gắt. Mảng tím ở vùng phẳng (áo, hoa) giữ nguyên.
 */
object Defringe {

    /** Bán kính tìm ranh giới theo bề rộng ảnh. */
    fun radiusFor(w: Int) = (w / 1600).coerceIn(2, 6)

    /**
     * Xử lý dải ảnh: [buf] gồm [rows] dòng cần sửa, có thêm [top] dòng phía trên và [bottom] dòng phía dưới
     * làm vùng đệm (chỉ để đọc). Kết quả ghi vào phần dòng chính của [buf].
     * @param amount 0..1, @param green có xóa cả viền xanh lá không
     */
    fun process(buf: IntArray, w: Int, top: Int, rows: Int, bottom: Int, r: Int, amount: Float, green: Boolean) {
        val h = top + rows + bottom
        val n = w * h
        val y = FloatArray(n)
        for (i in 0 until n) {
            val c = buf[i]
            y[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        // độ chênh sáng–tối cục bộ = max - min trong cửa sổ (2r+1)²
        val mx = y.copyOf(); val mn = y.copyOf()
        slide(mx, w, h, r, true); slide(mn, w, h, r, false)
        val out = buf.copyOf()
        for (row in top until top + rows) {
            for (x in 0 until w) {
                val i = row * w + x
                val range = mx[i] - mn[i]
                if (range < 45f) continue
                val c = buf[i]
                val rr = ((c shr 16) and 0xFF).toFloat()
                val gg = ((c shr 8) and 0xFF).toFloat()
                val bb = (c and 0xFF).toFloat()
                val cmax = max(rr, max(gg, bb)); val cmin = min(rr, min(gg, bb))
                if (cmax < 30f) continue
                val sat = (cmax - cmin) / cmax
                if (sat < 0.12f) continue
                val hue = hue(rr, gg, bb, cmax, cmin)
                // tím / tím hồng: 255°–330°, mạnh nhất quanh 285°; xanh lá: 85°–155°
                var hw = band(hue, 250f, 265f, 305f, 335f)
                if (green) hw = max(hw, band(hue, 80f, 100f, 135f, 160f) * 0.8f)
                if (hw <= 0f) continue
                val wgt = (hw * ((range - 45f) / 50f).coerceIn(0f, 1f) * ((sat - 0.12f) / 0.15f).coerceIn(0f, 1f) * amount * 1.15f).coerceAtMost(1f)
                if (wgt <= 0f) continue
                val l = y[i]
                val nr = (rr + (l - rr) * wgt).roundToInt().coerceIn(0, 255)
                val ng = (gg + (l - gg) * wgt).roundToInt().coerceIn(0, 255)
                val nb = (bb + (l - bb) * wgt).roundToInt().coerceIn(0, 255)
                out[i] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        System.arraycopy(out, top * w, buf, top * w, rows * w)
    }

    /** Dải sắc độ hình thang: tăng từ [lo] tới [p1], giữ tới [p2], giảm tới [hi]. */
    private fun band(h: Float, lo: Float, p1: Float, p2: Float, hi: Float): Float = when {
        h <= lo || h >= hi -> 0f
        h < p1 -> (h - lo) / (p1 - lo)
        h <= p2 -> 1f
        else -> (hi - h) / (hi - p2)
    }

    private fun hue(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d <= 0f) return 0f
        var h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return h
    }

    /** Lọc max/min trượt theo hàng rồi theo cột. */
    private fun slide(a: FloatArray, w: Int, h: Int, r: Int, isMax: Boolean) {
        val tmp = FloatArray(max(w, h))
        for (yy in 0 until h) line(a, yy * w, 1, w, r, isMax, tmp)
        for (xx in 0 until w) line(a, xx, w, h, r, isMax, tmp)
    }

    private fun line(a: FloatArray, start: Int, step: Int, len: Int, r: Int, isMax: Boolean, tmp: FloatArray) {
        for (i in 0 until len) {
            var v = a[start + i * step]
            val lo = max(0, i - r); val hi = min(len - 1, i + r)
            for (k in lo..hi) {
                val q = a[start + k * step]
                if (if (isMax) q > v else q < v) v = q
            }
            tmp[i] = v
        }
        for (i in 0 until len) a[start + i * step] = tmp[i]
    }
}
