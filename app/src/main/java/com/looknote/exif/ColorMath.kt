package com.looknote.exif

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** CIELAB (sRGB, D65) dùng chung cho các phép dựng màu. */
object LabMath {
    fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
    private fun fl(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) / (24389.0 / 27.0)

    fun toLab(r: Double, g: Double, b: Double): DoubleArray {
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / 0.95047
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / 1.08883
        val fx = fl(x); val fy = fl(y); val fz = fl(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun fromLab(l: Double, a: Double, b: Double): DoubleArray {
        val fy = (l + 16.0) / 116.0; val fx = fy + a / 500.0; val fz = fy - b / 200.0
        val x = fi(fx) * 0.95047; val y = fi(fy); val z = fi(fz) * 1.08883
        val rl = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return doubleArrayOf(gam(rl.coerceIn(0.0, 1.0)), gam(gl.coerceIn(0.0, 1.0)), gam(bl.coerceIn(0.0, 1.0)))
    }

    fun de76(a: DoubleArray, b: DoubleArray): Double {
        val d0 = a[0] - b[0]; val d1 = a[1] - b[1]; val d2 = a[2] - b[2]
        return sqrt(d0 * d0 + d1 * d1 + d2 * d2)
    }

    /** Giải hệ A·X = B (A: n×n, B: n×m) bằng khử Gauss có chọn trụ. */
    fun solve(a0: Array<DoubleArray>, b0: Array<DoubleArray>): Array<DoubleArray> {
        val n = a0.size; val m = b0[0].size
        val a = Array(n) { a0[it].copyOf() }; val b = Array(n) { b0[it].copyOf() }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (p != c) { val t = a[p]; a[p] = a[c]; a[c] = t; val u = b[p]; b[p] = b[c]; b[c] = u }
            val d = a[c][c]; if (abs(d) < 1e-12) continue
            for (r in 0 until n) if (r != c) {
                val f = a[r][c] / d
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                for (k in 0 until m) b[r][k] -= f * b[c][k]
            }
        }
        return Array(n) { r -> DoubleArray(m) { k -> if (abs(a[r][r]) < 1e-12) 0.0 else b[r][k] / a[r][r] } }
    }

    /** Ghi bảng màu 3D ra file .cube (thứ tự chuẩn: R chạy nhanh nhất). */
    fun writeCube(f: java.io.File, title: String, size: Int, data: FloatArray) {
        val sb = StringBuilder()
        sb.append("TITLE \"").append(title.replace("\"", "")).append("\"\nLUT_3D_SIZE ").append(size).append('\n')
        for (i in 0 until size * size * size) {
            sb.append(String.format(java.util.Locale.US, "%.5f %.5f %.5f\n", data[i * 3], data[i * 3 + 1], data[i * 3 + 2]))
        }
        f.parentFile?.mkdirs()
        f.writeText(sb.toString())
    }
}

/**
 * Học bảng màu 3D từ 2 ảnh cùng cảnh, cùng khung (máy đặt cố định): màu ảnh [src] → màu ảnh [dst].
 * Dùng để gỡ chính xác màu của một khe máy (ảnh khe → ảnh ST) và mô phỏng khe đó (ảnh ST → ảnh khe).
 * Cách làm: rải từng điểm ảnh vào lưới 17³ theo màu nguồn (nội suy 3 chiều), lấy trung bình màu đích,
 * bỏ qua điểm nằm trên đường biên (dễ lệch khung), điền các ô trống từ ô lân cận rồi làm mượt nhẹ.
 */
object Calib3D {
    const val N = 17

    class Result(val data: FloatArray, val size: Int, val error: Double, val coverage: Double)

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        if (r < 1) return
        val tmp = FloatArray(max(w, h))
        repeat(2) {
            for (y in 0 until h) {
                var s = 0f; var n = 0
                for (x in -r..r) { val xx = x.coerceIn(0, w - 1); s += a[y * w + xx]; n++ }
                for (x in 0 until w) {
                    tmp[x] = s / n
                    val out = (x - r).coerceIn(0, w - 1); val inn = (x + r + 1).coerceIn(0, w - 1)
                    s += a[y * w + inn] - a[y * w + out]
                }
                for (x in 0 until w) a[y * w + x] = tmp[x]
            }
            for (x in 0 until w) {
                var s = 0f; var n = 0
                for (y in -r..r) { val yy = y.coerceIn(0, h - 1); s += a[yy * w + x]; n++ }
                for (y in 0 until h) {
                    tmp[y] = s / n
                    val out = (y - r).coerceIn(0, h - 1); val inn = (y + r + 1).coerceIn(0, h - 1)
                    s += a[inn * w + x] - a[out * w + x]
                }
                for (y in 0 until h) a[y * w + x] = tmp[y]
            }
        }
    }

    fun build(src: IntArray, dst: IntArray, w: Int, h: Int, n: Int = N): Result {
        val sp = Array(3) { FloatArray(w * h) }; val dp = Array(3) { FloatArray(w * h) }
        for (i in 0 until w * h) for (ch in 0 until 3) {
            val sh = 16 - ch * 8
            sp[ch][i] = ((src[i] shr sh) and 0xFF) / 255f
            dp[ch][i] = ((dst[i] shr sh) and 0xFF) / 255f
        }
        val r = max(1, max(w, h) / 300)
        for (ch in 0 until 3) { boxBlur(sp[ch], w, h, r); boxBlur(dp[ch], w, h, r) }
        val lum = FloatArray(w * h) { 0.299f * sp[0][it] + 0.587f * sp[1][it] + 0.114f * sp[2][it] }

        val nn = n * n * n
        val sum = DoubleArray(nn * 3); val cnt = DoubleArray(nn)
        val n1 = n - 1
        val step = max(1, r)
        var y = step
        while (y < h - step) {
            var x = step
            while (x < w - step) {
                val i = y * w + x
                val g = abs(lum[i + step] - lum[i - step]) + abs(lum[i + step * w] - lum[i - step * w])
                if (g < 0.05f) {
                    val fr = sp[0][i] * n1; val fg = sp[1][i] * n1; val fb = sp[2][i] * n1
                    val r0 = min(fr.toInt(), n1 - 1); val g0 = min(fg.toInt(), n1 - 1); val b0 = min(fb.toInt(), n1 - 1)
                    val dr = fr - r0; val dg = fg - g0; val db = fb - b0
                    for (k in 0 until 8) {
                        val ri = r0 + (k and 1); val gi = g0 + ((k shr 1) and 1); val bi = b0 + ((k shr 2) and 1)
                        val wt = (if (k and 1 == 1) dr else 1 - dr) * (if ((k shr 1) and 1 == 1) dg else 1 - dg) *
                            (if ((k shr 2) and 1 == 1) db else 1 - db)
                        if (wt <= 0f) continue
                        val c = (bi * n + gi) * n + ri
                        cnt[c] += wt.toDouble()
                        for (ch in 0 until 3) sum[c * 3 + ch] += wt * dp[ch][i].toDouble()
                    }
                }
                x += step
            }
            y += step
        }

        val v = FloatArray(nn * 3)
        val known = BooleanArray(nn)
        var nk = 0
        for (c in 0 until nn) if (cnt[c] > 0.5) {
            for (ch in 0 until 3) v[c * 3 + ch] = (sum[c * 3 + ch] / cnt[c]).toFloat()
            known[c] = true; nk++
        }
        // điền ô trống: lan dần từ ô đã biết
        val filled = known.copyOf()
        var pass = 0
        while (pass < 3 * n) {
            var changed = false
            val add = ArrayList<Int>()
            for (c in 0 until nn) if (!filled[c]) {
                val ri = c % n; val gi = (c / n) % n; val bi = c / (n * n)
                var s0 = 0f; var s1 = 0f; var s2 = 0f; var k = 0
                fun take(rr: Int, gg: Int, bb: Int) {
                    if (rr < 0 || gg < 0 || bb < 0 || rr >= n || gg >= n || bb >= n) return
                    val q = (bb * n + gg) * n + rr
                    if (!filled[q]) return
                    // giữ đúng hướng thay đổi theo màu nguồn: cộng phần chênh giữa 2 ô nguồn
                    s0 += v[q * 3] + (ri - rr).toFloat() / n1; s1 += v[q * 3 + 1] + (gi - gg).toFloat() / n1
                    s2 += v[q * 3 + 2] + (bi - bb).toFloat() / n1; k++
                }
                take(ri - 1, gi, bi); take(ri + 1, gi, bi); take(ri, gi - 1, bi); take(ri, gi + 1, bi); take(ri, gi, bi - 1); take(ri, gi, bi + 1)
                if (k > 0) { v[c * 3] = s0 / k; v[c * 3 + 1] = s1 / k; v[c * 3 + 2] = s2 / k; add += c; changed = true }
            }
            for (c in add) filled[c] = true
            if (!changed) break
            pass++
        }
        for (c in 0 until nn) if (!filled[c]) {
            v[c * 3] = (c % n).toFloat() / n1; v[c * 3 + 1] = ((c / n) % n).toFloat() / n1; v[c * 3 + 2] = (c / (n * n)).toFloat() / n1
        }
        // làm mượt: ô ít mẫu kéo về trung bình lân cận
        repeat(2) {
            val o = v.copyOf()
            for (c in 0 until nn) {
                val ri = c % n; val gi = (c / n) % n; val bi = c / (n * n)
                var s0 = 0f; var s1 = 0f; var s2 = 0f; var k = 0
                for ((dr, dg, db) in listOf(Triple(-1, 0, 0), Triple(1, 0, 0), Triple(0, -1, 0), Triple(0, 1, 0), Triple(0, 0, -1), Triple(0, 0, 1))) {
                    val rr = ri + dr; val gg = gi + dg; val bb = bi + db
                    if (rr < 0 || gg < 0 || bb < 0 || rr >= n || gg >= n || bb >= n) continue
                    val q = (bb * n + gg) * n + rr
                    s0 += o[q * 3] - dr.toFloat() / n1; s1 += o[q * 3 + 1] - dg.toFloat() / n1; s2 += o[q * 3 + 2] - db.toFloat() / n1; k++
                }
                if (k == 0) continue
                val wSelf = (cnt[c] / (cnt[c] + 3.0)).toFloat()
                v[c * 3] = o[c * 3] * wSelf + s0 / k * (1 - wSelf)
                v[c * 3 + 1] = o[c * 3 + 1] * wSelf + s1 / k * (1 - wSelf)
                v[c * 3 + 2] = o[c * 3 + 2] * wSelf + s2 / k * (1 - wSelf)
            }
        }
        for (i in v.indices) v[i] = v[i].coerceIn(0f, 1f)

        // sai số trung bình (thang 0–255) trên các điểm dùng để học
        val lut = Lut(n, v, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val out = FloatArray(3)
        var err = 0.0; var m = 0
        y = step
        while (y < h - step) {
            var x = step
            while (x < w - step) {
                val i = y * w + x
                val g = abs(lum[i + step] - lum[i - step]) + abs(lum[i + step * w] - lum[i - step * w])
                if (g < 0.05f) {
                    lut.lookup(sp[0][i], sp[1][i], sp[2][i], out)
                    err += (abs(out[0] - dp[0][i]) + abs(out[1] - dp[1][i]) + abs(out[2] - dp[2][i])) * 255.0 / 3; m++
                }
                x += step * 2
            }
            y += step * 2
        }
        return Result(v, n, if (m > 0) err / m else 99.0, nk.toDouble() / nn)
    }
}

/**
 * Dựng màu mới từ bảng màu 64 ô (8×8) chụp bằng 2 máy (hoặc 2 thiết lập):
 * ảnh nguồn (Sony ST, mọi thông số 0) và ảnh đích (ví dụ Fuji). Cùng phương pháp đã dùng cho 11 màu Fuji:
 * biến đổi tuyến tính trong CIELAB + tổng hàm Gauss quanh các màu đo được, bỏ chênh phơi sáng theo hàng xám.
 */
object ChartFit {
    /** Mẫu đo của một ô: màu Lab ảnh nguồn và ảnh đích. */
    class Sample(val src: DoubleArray, val dst: DoubleArray)

    /** Ma trận chiếu từ lưới ô (0..8, 0..8) sang tọa độ ảnh, qua 4 góc theo thứ tự (0,0) (8,0) (8,8) (0,8). */
    fun homography(pts: List<FloatArray>): DoubleArray {
        val src = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(8.0, 0.0), doubleArrayOf(8.0, 8.0), doubleArrayOf(0.0, 8.0))
        val a = Array(8) { DoubleArray(8) }; val b = Array(8) { DoubleArray(1) }
        for (i in 0 until 4) {
            val x = src[i][0]; val y = src[i][1]; val u = pts[i][0].toDouble(); val v = pts[i][1].toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); b[2 * i][0] = u
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); b[2 * i + 1][0] = v
        }
        val h = LabMath.solve(a, b)
        return DoubleArray(9) { if (it < 8) h[it][0] else 1.0 }
    }

    fun project(h: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    /** Đo màu trung bình 64 ô (sRGB 0..1); ô bị cháy, quá loang hoặc ra ngoài ảnh thì null. */
    fun measure(px: IntArray, w: Int, h: Int, hom: DoubleArray): Array<DoubleArray?> = Array(64) { idx ->
        val r = idx / 8; val c = idx % 8
        val vals = ArrayList<DoubleArray>()
        var ok = true
        for (iy in 0 until 7) for (ix in 0 until 7) {
            val p = project(hom, c + 0.5 + (ix - 3) * 0.07, r + 0.5 + (iy - 3) * 0.07)
            val x = p[0].toInt(); val y = p[1].toInt()
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) { ok = false; break }
            val v = px[y * w + x]
            vals += doubleArrayOf(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
        }
        if (!ok || vals.isEmpty()) return@Array null
        val m = DoubleArray(3) { ch -> vals.sumOf { it[ch] } / vals.size }
        val sd = DoubleArray(3) { ch -> sqrt(vals.sumOf { (it[ch] - m[ch]) * (it[ch] - m[ch]) } / vals.size) }
        if (sd.max() > 0.05 || m.max() > 0.985) null else m
    }

    /** Ghép 2 lần đo thành mẫu Lab; bỏ chênh phơi sáng giữa 2 ảnh theo các ô xám 2..6 của hàng đầu. */
    fun pair(src: Array<DoubleArray?>, dst: Array<DoubleArray?>): List<Sample> {
        val ratios = (2..6).mapNotNull { i ->
            val s = src[i]; val d = dst[i]
            if (s == null || d == null) null else {
                val ls = s.sumOf { LabMath.lin(it) } / 3; val ld = d.sumOf { LabMath.lin(it) } / 3
                if (ld > 1e-4) ls / ld else null
            }
        }.sorted()
        val k = if (ratios.isEmpty()) 1.0 else ratios[ratios.size / 2]
        val out = ArrayList<Sample>()
        for (i in 0 until 64) {
            val s = src[i] ?: continue; val d = dst[i] ?: continue
            val dd = DoubleArray(3) { LabMath.gam((LabMath.lin(d[it]) * k).coerceIn(0.0, 1.0)) }
            out += Sample(LabMath.toLab(s[0], s[1], s[2]), LabMath.toLab(dd[0], dd[1], dd[2]))
        }
        return out
    }

    class Model(val a: Array<DoubleArray>, val centers: List<DoubleArray>, val w: Array<DoubleArray>, val sig: Double)

    private fun kern(x: DoubleArray, c: DoubleArray, sig: Double): Double {
        val dl = (x[0] - c[0]) * 0.5; val da = x[1] - c[1]; val db = x[2] - c[2]
        return kotlin.math.exp(-(dl * dl + da * da + db * db) / (2 * sig * sig))
    }

    fun fit(samples: List<Sample>, sig: Double = 20.0, lam: Double = 3.0, ridge: Double = 1000.0): Model {
        val n = samples.size
        val z = Array(n) { i -> doubleArrayOf(samples[i].src[0], samples[i].src[1], samples[i].src[2], 1.0) }
        val r = Array(n) { i -> DoubleArray(3) { samples[i].dst[it] - samples[i].src[it] } }
        // tuyến tính có chặn (ridge) trên 3 hệ số, không chặn hằng số
        val ztz = Array(4) { p -> DoubleArray(4) { q -> (0 until n).sumOf { z[it][p] * z[it][q] } + if (p == q && p < 3) ridge else 0.0 } }
        val ztr = Array(4) { p -> DoubleArray(3) { k -> (0 until n).sumOf { z[it][p] * r[it][k] } } }
        val a = LabMath.solve(ztz, ztr)
        val r2 = Array(n) { i -> DoubleArray(3) { k -> r[i][k] - (0 until 4).sumOf { p -> z[i][p] * a[p][k] } } }
        val centers = samples.map { it.src }
        val kk = Array(n) { i -> DoubleArray(n) { j -> kern(samples[i].src, centers[j], sig) } }
        val ktk = Array(n) { p -> DoubleArray(n) { q -> (0 until n).sumOf { kk[it][p] * kk[it][q] } + if (p == q) lam else 0.0 } }
        val ktr = Array(n) { p -> DoubleArray(3) { k -> (0 until n).sumOf { kk[it][p] * r2[it][k] } } }
        val w = LabMath.solve(ktk, ktr)
        return Model(a, centers, w, sig)
    }

    fun apply(m: Model, lab: DoubleArray): DoubleArray {
        val l = lab[0]
        val q = doubleArrayOf(max(l, 1.0), lab[1], lab[2])
        val d = DoubleArray(3) { k -> q[0] * m.a[0][k] + q[1] * m.a[1][k] + q[2] * m.a[2][k] + m.a[3][k] }
        for (j in m.centers.indices) {
            val g = kern(q, m.centers[j], m.sig)
            if (g < 1e-6) continue
            for (k in 0 until 3) d[k] += g * m.w[j][k]
        }
        val top = ((100.0 - l) / 4.0).coerceIn(0.0, 1.0)
        val wl = l.coerceIn(0.0, 1.0) * top
        val wc = (l / 20.0).coerceIn(0.0, 1.0) * top
        return doubleArrayOf((l + d[0] * wl).coerceIn(0.0, 100.0), lab[1] + d[1] * wc, lab[2] + d[2] * wc)
    }

    /** Bảng màu 3D 33³ để lưu thành .cube. */
    fun bake(m: Model, n: Int = 33): FloatArray {
        val data = FloatArray(n * n * n * 3)
        var k = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val lab = apply(m, LabMath.toLab(ri / (n - 1.0), gi / (n - 1.0), bi / (n - 1.0)))
            val rgb = LabMath.fromLab(lab[0], lab[1], lab[2])
            data[k++] = rgb[0].toFloat(); data[k++] = rgb[1].toFloat(); data[k++] = rgb[2].toFloat()
        }
        return data
    }

    /** Sai số trung bình (ΔE76) trước và sau khi áp màu, trên chính các ô đã đo. */
    fun score(m: Model, samples: List<Sample>): Pair<Double, Double> {
        if (samples.isEmpty()) return 0.0 to 0.0
        val before = samples.sumOf { LabMath.de76(it.src, it.dst) } / samples.size
        val after = samples.sumOf { LabMath.de76(apply(m, it.src), it.dst) } / samples.size
        return before to after
    }
}
