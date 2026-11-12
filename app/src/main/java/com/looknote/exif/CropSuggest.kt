package com.looknote.exif

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gợi ý phương án cắt: cân thẳng đường chân trời / đường dọc, và các khung theo tỷ lệ phổ biến
 * đặt chủ thể (mắt người, hoặc vùng nổi bật) theo bố cục một phần ba, không cắt ngang mặt.
 * Thuần Kotlin: làm việc trên ảnh nhỏ đã xoay 90°/lật theo khung (chưa cân thẳng).
 */
object CropSuggest {

    /** [angle] = null: giữ góc nghiêng hiện tại. Khung (l, t, r, b) theo tỷ lệ 0..1 của khung. */
    class Suggestion(val name: String, val aspect: Float, val angle: Float?,
                     val l: Float, val t: Float, val r: Float, val b: Float)

    /**
     * @param px ảnh nhỏ (ARGB) w×h theo hướng khung
     * @param faces khung mặt theo điểm ảnh của ảnh nhỏ: [l, t, r, b]
     */
    fun suggest(px: IntArray, w: Int, h: Int, faces: List<FloatArray>): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val lum = FloatArray(w * h) { i ->
            val c = px[i]
            (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
        }
        // ---- cân thẳng ----
        val tilt = tilt(lum, w, h)
        if (tilt != null) {
            val s = CropGeom.maxInner(w.toFloat(), h.toFloat(), CropSpec(angle = tilt), 0f)
            out += Suggestion("Cân thẳng " + (if (tilt > 0) "+" else "") + String.format("%.1f", tilt) + "°",
                0f, tilt, s.l, s.t, s.r, s.b)
        }
        // ---- chủ thể ----
        val subj: FloatArray      // l, t, r, b
        val key: FloatArray       // điểm cần đặt theo 1/3
        val hasFace = faces.isNotEmpty()
        if (hasFace) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = 0f; var b = 0f
            for (f in faces) { l = min(l, f[0]); t = min(t, f[1]); r = max(r, f[2]); b = max(b, f[3]) }
            val big = faces.maxByOrNull { (it[2] - it[0]) * (it[3] - it[1]) }!!
            val fh = big[3] - big[1]
            // gồm cả tóc và vai: nới khung mặt
            subj = floatArrayOf(l - (r - l) * 0.45f, t - fh * 0.3f, r + (r - l) * 0.45f, b + fh * 1.4f)
            key = floatArrayOf((l + r) / 2f, big[1] + fh * 0.42f)
        } else {
            val s = saliency(px, lum, w, h)
            subj = s.copyOfRange(0, 4); key = s.copyOfRange(4, 6)
        }
        subj[0] = max(0f, subj[0]); subj[1] = max(0f, subj[1]); subj[2] = min(w.toFloat(), subj[2]); subj[3] = min(h.toFloat(), subj[3])

        val cands = listOf(
            Triple("4:5 chân dung", 0.8f, "Instagram"), Triple("1:1 vuông", 1f, ""),
            Triple("2:3 dọc", 2f / 3f, ""), Triple("3:2 ngang", 1.5f, ""),
            Triple("16:9 rộng", 16f / 9f, ""), Triple("9:16 story", 9f / 16f, "")
        )
        val frameA = w.toFloat() / h
        val scored = ArrayList<Pair<Float, Suggestion>>()
        for ((name, a, _) in cands) best(w, h, a, subj, key, faces, hasFace)?.let { (score, r) ->
            val where = if (hasFace) "mắt ở 1/3 trên" else "chủ thể theo 1/3"
            scored += score to Suggestion("$name · $where", a, null, r[0], r[1], r[2], r[3])
        }
        // bố cục lại theo tỷ lệ gốc (cắt chặt hơn)
        best(w, h, frameA, subj, key, faces, hasFace, sizes = floatArrayOf(0.85f, 0.75f, 0.65f))?.let { (score, r) ->
            scored += score to Suggestion("Tỷ lệ gốc · cắt chặt", frameA, null, r[0], r[1], r[2], r[3])
        }
        scored.sortByDescending { it.first }
        out += scored.take(5).map { it.second }
        return out
    }

    /** Tìm khung tốt nhất cho một tỷ lệ; trả về (điểm, [l, t, r, b] tỷ lệ 0..1). */
    private fun best(w: Int, h: Int, aspect: Float, subj: FloatArray, key: FloatArray, faces: List<FloatArray>,
                     hasFace: Boolean, sizes: FloatArray = floatArrayOf(1f, 0.9f, 0.8f, 0.7f, 0.6f)): Pair<Float, FloatArray>? {
        var mw = w.toFloat(); var mh = mw / aspect
        if (mh > h) { mh = h.toFloat(); mw = mh * aspect }
        val sw = subj[2] - subj[0]; val sh = subj[3] - subj[1]
        var bestScore = -1e9f; var bestR: FloatArray? = null
        for (s in sizes) {
            val cw = mw * s; val ch = mh * s
            if (cw < w * 0.3f && ch < h * 0.3f) continue
            val steps = 40
            for (ix in 0..steps) for (iy in 0..steps) {
                val x0 = (w - cw) * ix / steps; val y0 = (h - ch) * iy / steps
                val x1 = x0 + cw; val y1 = y0 + ch
                // chủ thể nằm trong khung bao nhiêu
                val iw = max(0f, min(x1, subj[2]) - max(x0, subj[0])); val ih = max(0f, min(y1, subj[3]) - max(y0, subj[1]))
                val inside = if (sw * sh > 0f) iw * ih / (sw * sh) else 1f
                var score = -(1f - inside) * 6f
                // điểm chính gần đường 1/3
                val kx = (key[0] - x0) / cw; val ky = (key[1] - y0) / ch
                if (kx < 0.05f || kx > 0.95f || ky < 0.05f || ky > 0.95f) continue
                val dx = minOf(abs(kx - 1f / 3f), abs(kx - 0.5f) + 0.03f, abs(kx - 2f / 3f))
                val dy = if (hasFace) abs(ky - 1f / 3f) else minOf(abs(ky - 1f / 3f), abs(ky - 0.5f) + 0.03f, abs(ky - 2f / 3f))
                score -= (dx + dy * 1.3f) * 4f
                // không cắt ngang mặt, chừa khoảng trên đầu
                for (f in faces) {
                    val fl = max(x0, f[0]); val fr = min(x1, f[2]); val ft = max(y0, f[1]); val fb = min(y1, f[3])
                    val part = if (fr > fl && fb > ft) (fr - fl) * (fb - ft) / ((f[2] - f[0]) * (f[3] - f[1])) else 0f
                    if (part in 0.01f..0.98f) score -= 5f
                    if (part > 0.98f && f[1] - y0 < (f[3] - f[1]) * 0.2f) score -= 1.5f
                }
                // chủ thể quá nhỏ trong khung thì kém
                val fill = (min(sw, cw) * min(sh, ch)) / (cw * ch)
                score += min(fill, 0.45f) * 2f + s * 0.8f
                if (score > bestScore) { bestScore = score; bestR = floatArrayOf(x0 / w, y0 / h, x1 / w, y1 / h) }
            }
        }
        return bestR?.let { bestScore to it }
    }

    /** Vùng nổi bật khi không có mặt người: [l, t, r, b, kx, ky] theo điểm ảnh. */
    private fun saliency(px: IntArray, lum: FloatArray, w: Int, h: Int): FloatArray {
        // độ tương phản cục bộ + độ bão hòa + cạnh; lệch về giữa ảnh một chút
        val bl = boxBlur(lum, w, h, max(2, w / 24))
        val sal = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val c = px[i]
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val gx = lum[i + 1] - lum[i - 1]; val gy = lum[i + w] - lum[i - w]
            val dxc = (x - w / 2f) / w; val dyc = (y - h / 2f) / h
            sal[i] = (abs(lum[i] - bl[i]) * 2f + (mx - mn) * 0.6f + sqrt(gx * gx + gy * gy)) * (1.2f - (dxc * dxc + dyc * dyc) * 1.5f)
        }
        val sm = boxBlur(sal, w, h, max(2, w / 30))
        val sorted = sm.copyOf().also { it.sort() }
        val thr = sorted[(sorted.size * 0.85f).toInt()]
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val v = sm[y * w + x]
            if (v >= thr) { val wt = (v - thr + 1e-4f).toDouble(); sx += x * wt; sy += y * wt; sw += wt; xs += x; ys += y }
        }
        if (sw <= 0.0 || xs.isEmpty()) return floatArrayOf(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f, w / 2f, h / 2f)
        xs.sort(); ys.sort()
        fun pct(l: List<Int>, p: Float) = l[((l.size - 1) * p).toInt()].toFloat()
        return floatArrayOf(pct(xs, 0.1f), pct(ys, 0.1f), pct(xs, 0.9f), pct(ys, 0.9f), (sx / sw).toFloat(), (sy / sw).toFloat())
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0f; var n = 0
            for (x in -r..r) if (x in 0 until w) { s += src[y * w + x]; n++ }
            for (x in 0 until w) {
                tmp[y * w + x] = s / n
                val xo = x - r; val xi = x + r + 1
                if (xo >= 0) { s -= src[y * w + xo]; n-- }
                if (xi < w) { s += src[y * w + xi]; n++ }
            }
        }
        for (x in 0 until w) {
            var s = 0f; var n = 0
            for (y in -r..r) if (y in 0 until h) { s += tmp[y * w + x]; n++ }
            for (y in 0 until h) {
                out[y * w + x] = s / n
                val yo = y - r; val yi = y + r + 1
                if (yo >= 0) { s -= tmp[yo * w + x]; n-- }
                if (yi < h) { s += tmp[yi * w + x]; n++ }
            }
        }
        return out
    }

    /**
     * Ước lượng độ nghiêng (độ, theo chiều xoay của [CropSpec.angle]) từ các cạnh gần ngang / gần dọc.
     * Trả về null nếu ảnh đã thẳng hoặc không đủ đường thẳng rõ.
     */
    fun tilt(lum0: FloatArray, w: Int, h: Int): Float? {
        // làm mờ nhẹ để cạnh răng cưa không bị đọc thành đường ngang/dọc
        val lum = boxBlur(lum0, w, h, 2)
        val bins = 96              // -12..+12 độ, mỗi ô 0,25°
        val hist = DoubleArray(bins)
        var total = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - 1] + lum[i + w - 1])
            val gy = (lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - w] + lum[i - w + 1])
            val m = gx * gx + gy * gy
            if (m < 0.004f) continue
            // hướng của đường (vuông góc với gradient), đưa về -90..90
            var a = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())) + 90.0
            while (a > 90) a -= 180.0
            while (a <= -90) a += 180.0
            // đường gần ngang: a ≈ 0 ; đường gần dọc: a ≈ ±90
            val dev = if (abs(a) <= 45) a else if (a > 0) a - 90 else a + 90
            if (abs(dev) >= 12) continue
            val wt = sqrt(m.toDouble())
            hist[((dev + 12) / 24 * bins).toInt().coerceIn(0, bins - 1)] += wt
            total += wt
        }
        if (total <= 0) return null
        // làm mượt và tìm đỉnh
        val sm = DoubleArray(bins) { k -> (-2..2).sumOf { d -> hist.getOrElse(k + d) { 0.0 } * (3 - abs(d)) } }
        val k = sm.indices.maxByOrNull { sm[it] }!!
        val peak = (k + 0.5) / bins * 24 - 12
        val zero = sm[bins / 2].coerceAtLeast(sm[bins / 2 - 1])
        // đỉnh phải nổi rõ so với nền và so với vị trí 0°
        val mean = sm.average()
        if (sm[k] < mean * 2.2 || abs(peak) < 0.4 || sm[k] < zero * 1.25) return null
        // dev > 0: đường dốc xuống bên phải (y tăng) → xoay ngược chiều kim đồng hồ để sửa
        return (-peak).toFloat().let { Math.round(it * 10f) / 10f }
    }
}
