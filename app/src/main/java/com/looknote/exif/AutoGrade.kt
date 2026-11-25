package com.looknote.exif

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bản đồ da AI ở độ phân giải xem trước (0..1, 1 = chắc chắn là da).
 * Dựng từ: tách người khỏi nền (ML Kit Selfie Segmentation, chạy offline)
 * × độ giống màu da lấy mẫu ở má của từng khuôn mặt − mắt, lông mày, môi.
 * Nhờ vậy chỉnh màu da không lan ra áo, tóc, nền gỗ, cát... có màu gần giống da.
 */
class SkinMap(val w: Int, val h: Int, val m: FloatArray, val usedPerson: Boolean) {
    /** Tỉ lệ diện tích ảnh là da (0..1). */
    val coverage: Float by lazy { var s = 0.0; for (v in m) s += v; (s / m.size).toFloat() }

    /** Lớp phủ để xem bản đồ da (màu cam trong suốt). */
    fun overlay(): Bitmap {
        val px = IntArray(w * h) { i ->
            val a = (m[i] * 170f).toInt().coerceIn(0, 170)
            (a shl 24) or 0xFF7A1A
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        fun build(b: Bitmap, faces: List<FaceRegion>): SkinMap {
            val w = b.width; val h = b.height; val n = w * h
            val px = IntArray(n); b.getPixels(px, 0, w, 0, 0, w, h)
            val person = personMask(b)
            val Y = FloatArray(n); val Cb = FloatArray(n); val Cr = FloatArray(n)
            for (i in 0 until n) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val bl = (c and 0xFF).toFloat()
                Y[i] = 0.299f * r + 0.587f * g + 0.114f * bl
                Cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * bl
                Cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * bl
            }
            val m = FloatArray(n)
            // mẫu màu da theo từng khuôn mặt
            class Sample(val cb: Float, val cr: Float, val dcb: Float, val dcr: Float, val f: FaceRegion)
            val samples = ArrayList<Sample>()
            for (f in faces) {
                var sCb = 0.0; var sCr = 0.0; var sCb2 = 0.0; var sCr2 = 0.0; var cnt = 0
                val x0 = (f.left + f.width * 0.2f).toInt().coerceIn(0, w - 1)
                val x1 = (f.left + f.width * 0.8f).toInt().coerceIn(0, w - 1)
                val y0 = (f.top + f.height * 0.35f).toInt().coerceIn(0, h - 1)
                val y1 = (f.top + f.height * 0.75f).toInt().coerceIn(0, h - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    if (inside(f, x.toFloat(), y.toFloat(), 1.2f)) continue
                    val i = y * w + x
                    if (Y[i] < 40f || Y[i] > 250f) continue
                    sCb += Cb[i]; sCr += Cr[i]; sCb2 += Cb[i] * Cb[i]; sCr2 += Cr[i] * Cr[i]; cnt++
                }
                if (cnt < 20) continue
                val mCb = (sCb / cnt).toFloat(); val mCr = (sCr / cnt).toFloat()
                val dCb = max(4f, sqrt(max(0.0, sCb2 / cnt - mCb.toDouble() * mCb)).toFloat())
                val dCr = max(4f, sqrt(max(0.0, sCr2 / cnt - mCr.toDouble() * mCr)).toFloat())
                samples += Sample(mCb, mCr, dCb, dCr, f)
            }
            for (i in 0 until n) {
                if (Y[i] < 30f) continue
                var v = 0f
                if (samples.isNotEmpty()) {
                    for (s in samples) {
                        val a = (Cb[i] - s.cb) / s.dcb; val c = (Cr[i] - s.cr) / s.dcr
                        val d = sqrt(a * a + c * c)
                        val t = ((3.4f - d) / 1.5f).coerceIn(0f, 1f)
                        if (t > v) v = t
                    }
                } else {
                    // không thấy mặt: dải màu da chung, mức nhẹ
                    if (Cb[i] in 80f..125f && Cr[i] in 135f..172f) v = 0.5f
                }
                if (v <= 0f) continue
                if (person != null) {
                    // vùng người: chuyển mềm từ 0,3 đến 0,7 độ tin cậy
                    val p = ((person[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
                    v *= p * p * (3f - 2f * p)
                } else if (samples.isNotEmpty()) {
                    // không tách được người: chỉ nhận vùng quanh mặt, cổ, vai, tay gần mặt
                    val x = (i % w).toFloat(); val y = (i / w).toFloat()
                    var near = 0f
                    for (s in samples) {
                        val f = s.f
                        val dx = when { x < f.left - f.width * 1.6f -> (f.left - f.width * 1.6f - x) / f.width
                            x > f.right + f.width * 1.6f -> (x - f.right - f.width * 1.6f) / f.width; else -> 0f }
                        val dy = when { y < f.top - f.height * 0.3f -> (f.top - f.height * 0.3f - y) / f.height
                            y > f.bottom + f.height * 3.5f -> (y - f.bottom - f.height * 3.5f) / f.height; else -> 0f }
                        near = max(near, (1f - (dx + dy) * 2f).coerceIn(0f, 1f))
                    }
                    v *= near
                }
                m[i] = v
            }
            // bỏ mắt, lông mày, môi, chân mũi
            for (s in samples) {
                val f = s.f
                for (e in f.exclude) {
                    val x0 = (e.cx - e.rx * 1.1f).toInt().coerceIn(0, w - 1); val x1 = (e.cx + e.rx * 1.1f).toInt().coerceIn(0, w - 1)
                    val y0 = (e.cy - e.ry * 1.1f).toInt().coerceIn(0, h - 1); val y1 = (e.cy + e.ry * 1.1f).toInt().coerceIn(0, h - 1)
                    for (y in y0..y1) for (x in x0..x1) if (inside(f, x.toFloat(), y.toFloat(), 1.05f)) m[y * w + x] = 0f
                }
            }
            val fw = if (faces.isNotEmpty()) faces.map { it.width }.sorted()[faces.size / 2] else max(w, h) * 0.12f
            SkinRetouch.blur(m, w, h, max(1, (fw * 0.012f).roundToInt()))
            return SkinMap(w, h, m, person != null)
        }

        private fun inside(f: FaceRegion, x: Float, y: Float, scale: Float): Boolean {
            for (e in f.exclude) {
                val dx = (x - e.cx) / (e.rx * scale); val dy = (y - e.cy) / (e.ry * scale)
                if (dx * dx + dy * dy <= 1f) return true
            }
            return false
        }

        /** Tách người khỏi nền bằng ML Kit (offline). null nếu thiết bị không chạy được. */
        fun personMask(b: Bitmap): FloatArray? {
            val seg = try {
                Segmentation.getClient(SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
            } catch (t: Throwable) { return null }
            return try {
                val mask = Tasks.await(seg.process(InputImage.fromBitmap(b, 0)))
                val mw = mask.width; val mh = mask.height
                val buf = mask.buffer
                buf.rewind()
                val raw = FloatArray(mw * mh) { buf.float }
                if (mw == b.width && mh == b.height) raw else resample(raw, mw, mh, b.width, b.height)
            } catch (t: Throwable) {
                null
            } finally {
                try { seg.close() } catch (t: Throwable) {}
            }
        }

        private fun resample(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = fy.toInt(); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = fx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                    val a = src[y0 * sw + x0] + (src[y0 * sw + x1] - src[y0 * sw + x0]) * tx
                    val c = src[y1 * sw + x0] + (src[y1 * sw + x1] - src[y1 * sw + x0]) * tx
                    out[y * w + x] = a + (c - a) * ty
                }
            }
            return out
        }
    }
}

/** Thống kê màu của một ảnh (dùng cho ảnh mẫu và ảnh đang chỉnh). */
class ColorStats(
    /** L* ở các mốc 2, 25, 50, 75, 98 %. */
    val lp: FloatArray,
    val aMean: Float, val aStd: Float, val bMean: Float, val bStd: Float,
    /** Màu da trung bình L*, a*, b* (null nếu không có da). */
    val skin: FloatArray?,
    val skinStd: FloatArray?,
    val name: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("lp", JSONArray().also { j -> lp.forEach { j.put(it.toDouble()) } })
        put("a", aMean.toDouble()); put("as", aStd.toDouble()); put("b", bMean.toDouble()); put("bs", bStd.toDouble())
        skin?.let { s -> put("skin", JSONArray().also { j -> s.forEach { j.put(it.toDouble()) } }) }
        skinStd?.let { s -> put("skinStd", JSONArray().also { j -> s.forEach { j.put(it.toDouble()) } }) }
        put("name", name)
    }

    companion object {
        private fun fa(j: JSONArray?) = j?.let { FloatArray(it.length()) { i -> it.getDouble(i).toFloat() } }
        fun fromJson(o: JSONObject) = ColorStats(
            fa(o.getJSONArray("lp"))!!, o.getDouble("a").toFloat(), o.getDouble("as").toFloat(),
            o.getDouble("b").toFloat(), o.getDouble("bs").toFloat(),
            fa(o.optJSONArray("skin")), fa(o.optJSONArray("skinStd")), o.optString("name", ""))

        /**
         * Đo thống kê. [skin] cùng kích thước với [px]; màu nền/áo đo trên vùng không phải da,
         * màu da đo trên vùng da chắc chắn.
         * @param pre biến đổi áp lên từng mẫu trước khi đo (vd. bù sáng), trên sRGB 0..1
         */
        fun measure(px: IntArray, w: Int, h: Int, skin: SkinMap?, name: String = "", pre: ((DoubleArray) -> Unit)? = null): ColorStats {
            val step = max(1, sqrt(w * h / 250_000.0).toInt())
            val ls = ArrayList<Float>(w * h / (step * step) + 16)
            var wa = 0.0; var sa = 0.0; var sa2 = 0.0; var sb = 0.0; var sb2 = 0.0
            var ws = 0.0; val sk = DoubleArray(3); val sk2 = DoubleArray(3)
            val rgb = DoubleArray(3)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = px[y * w + x]
                    rgb[0] = ((c shr 16) and 0xFF) / 255.0; rgb[1] = ((c shr 8) and 0xFF) / 255.0; rgb[2] = (c and 0xFF) / 255.0
                    pre?.invoke(rgb)
                    val lab = LabMath.toLab(rgb[0], rgb[1], rgb[2])
                    ls += lab[0].toFloat()
                    val s = if (skin != null) skin.m[y * w + x].toDouble() else 0.0
                    val wn = 1.0 - s
                    wa += wn; sa += wn * lab[1]; sa2 += wn * lab[1] * lab[1]; sb += wn * lab[2]; sb2 += wn * lab[2] * lab[2]
                    if (s > 0.6) {
                        ws += s
                        for (k in 0 until 3) { sk[k] += s * lab[k]; sk2[k] += s * lab[k] * lab[k] }
                    }
                    x += step
                }
                y += step
            }
            ls.sort()
            fun pct(p: Float) = if (ls.isEmpty()) 50f else ls[((ls.size - 1) * p).roundToInt()]
            val lp = floatArrayOf(pct(0.02f), pct(0.25f), pct(0.5f), pct(0.75f), pct(0.98f))
            val am = if (wa > 0) sa / wa else 0.0; val bm = if (wa > 0) sb / wa else 0.0
            val asd = if (wa > 0) sqrt(max(0.0, sa2 / wa - am * am)) else 10.0
            val bsd = if (wa > 0) sqrt(max(0.0, sb2 / wa - bm * bm)) else 10.0
            val enough = ws > ls.size * 0.003 && ws > 30
            val skinMean = if (enough) FloatArray(3) { (sk[it] / ws).toFloat() } else null
            val skinStd = if (enough) FloatArray(3) { k ->
                val mu = sk[k] / ws; sqrt(max(0.0, sk2[k] / ws - mu * mu)).toFloat()
            } else null
            return ColorStats(lp, am.toFloat(), max(2.0, asd).toFloat(), bm.toFloat(), max(2.0, bsd).toFloat(), skinMean, skinStd, name)
        }
    }
}

/** Thiết lập tự động. Các mức 0..100. */
data class AutoParams(
    /** Chuẩn hóa ánh sáng theo da mặt (hoặc theo cả ảnh khi không có người). */
    val light: Int = 0,
    /** Màu da tự động: chỉnh tông, độ đậm và độ đều màu da, chỉ trong bản đồ da. */
    val skinTone: Int = 0,
    /** Mức giống ảnh mẫu. */
    val refAmount: Int = 0,
    val ref: ColorStats? = null
) {
    val on get() = light > 0 || skinTone > 0 || (ref != null && refAmount > 0)
    fun key() = "$light-$skinTone-$refAmount-${ref?.name}-${ref?.lp?.contentToString()}"
}

/**
 * Chỉnh tự động bằng hai bảng màu 3D:
 *  - [global]: chuẩn hóa sáng + match màu ảnh mẫu, áp cho cả ảnh;
 *  - [skin]: như trên cộng thêm chỉnh màu da, chỉ áp trong bản đồ da [mask].
 * Tính một lần ở ảnh xem trước rồi áp được cho ảnh lớn bất kỳ.
 */
class AutoGrade(val global: Lut?, val skin: Lut?, val mask: SkinMap?, val note: String) {

    val isIdentity get() = global == null && (skin == null || mask == null)

    /** Áp lên các dòng [y0, y0+rows) của ảnh kích thước W×H. */
    fun apply(buf: IntArray, W: Int, H: Int, y0: Int, rows: Int) {
        if (isIdentity) return
        val g = FloatArray(3); val s = FloatArray(3)
        val m = mask
        val sx = if (m != null) m.w.toFloat() / W else 0f
        val sy = if (m != null) m.h.toFloat() / H else 0f
        for (yy in 0 until rows) {
            var my1 = 0; var my2 = 0; var ty = 0f
            if (m != null) {
                val fy = ((y0 + yy) + 0.5f) * sy - 0.5f
                my1 = fy.toInt().coerceIn(0, m.h - 1); my2 = min(m.h - 1, my1 + 1); ty = (fy - my1).coerceIn(0f, 1f)
            }
            for (x in 0 until W) {
                val k = yy * W + x
                val c = buf[k]
                val r = ((c shr 16) and 0xFF) / 255f; val gg = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                if (global != null) global.lookup(r, gg, b, g) else { g[0] = r; g[1] = gg; g[2] = b }
                var a = 0f
                if (m != null && skin != null) {
                    val fx = (x + 0.5f) * sx - 0.5f
                    val x1 = fx.toInt().coerceIn(0, m.w - 1); val x2 = min(m.w - 1, x1 + 1); val tx = (fx - x1).coerceIn(0f, 1f)
                    val top = m.m[my1 * m.w + x1] + (m.m[my1 * m.w + x2] - m.m[my1 * m.w + x1]) * tx
                    val bot = m.m[my2 * m.w + x1] + (m.m[my2 * m.w + x2] - m.m[my2 * m.w + x1]) * tx
                    a = top + (bot - top) * ty
                }
                if (a > 0.004f) {
                    skin!!.lookup(r, gg, b, s)
                    for (ch in 0 until 3) g[ch] += (s[ch] - g[ch]) * a
                }
                val ro = (g[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val go = (g[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bo = (g[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                buf[k] = (c and -0x1000000) or (ro shl 16) or (go shl 8) or bo
            }
        }
    }

    companion object {
        private const val N = 33

        /**
         * @param px ảnh xem trước đã qua các bước chỉnh trước đó (màu, da...)
         * @param map bản đồ da cùng kích thước
         */
        fun compute(px: IntArray, w: Int, h: Int, map: SkinMap?, p: AutoParams): AutoGrade {
            if (!p.on) return AutoGrade(null, null, null, "")
            val notes = ArrayList<String>()

            // ----- 1. chuẩn hóa ánh sáng -----
            val st0 = ColorStats.measure(px, w, h, map)
            var ev = 0.0
            if (p.light > 0) {
                val skin = st0.skin
                val median = st0.lp[2]
                if (skin != null) {
                    val L = skin[0]
                    // da ngăm thật (cảnh xung quanh đủ sáng) thì giữ độ sâu, không kéo sáng làm bệch
                    val target = if (L < 52f && median >= 42f) max(L + 5f, 52f) else 66f
                    ev = evFor(L.toDouble(), target.toDouble()) * 0.8
                    notes += "da mặt L*=${L.roundToInt()} → ${target.roundToInt()}"
                } else {
                    ev = evFor(median.toDouble(), 50.0) * 0.6
                    notes += "không có da: cân theo cả ảnh"
                }
                // không đẩy vùng sáng nhất quá ngưỡng cháy
                if (ev > 0 && st0.lp[4] > 92f) ev *= 0.6
                ev = ev.coerceIn(-1.0, 1.2) * p.light / 100.0
                if (abs(ev) < 0.03) ev = 0.0 else notes += "bù " + (if (ev > 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", ev).replace('.', ',') + " EV"
            }
            val gain = 2.0.pow(ev)
            val expose: (DoubleArray) -> Unit = { rgb -> exposeRgb(rgb, gain) }

            // ----- 2. match ảnh mẫu (đo lại sau khi bù sáng) -----
            val ref = if (p.refAmount > 0) p.ref else null
            val st1 = if (ev != 0.0) ColorStats.measure(px, w, h, map, pre = expose) else st0
            val refAmt = (p.refAmount / 100.0)
            var lMap: ((Double) -> Double)? = null
            var abMap: ((DoubleArray) -> Unit)? = null
            if (ref != null) {
                val src = monotone(st1.lp); val dst = limitSlope(src, monotone(ref.lp))
                lMap = { l -> l + (pwl(l, src, dst) - l) * refAmt }
                val ka = (ref.aStd / st1.aStd).toDouble().coerceIn(0.6, 1.4)
                val kb = (ref.bStd / st1.bStd).toDouble().coerceIn(0.6, 1.4)
                abMap = { lab ->
                    val a2 = (lab[1] - st1.aMean) * ka + ref.aMean; val b2 = (lab[2] - st1.bMean) * kb + ref.bMean
                    lab[1] += (a2 - lab[1]) * refAmt; lab[2] += (b2 - lab[2]) * refAmt
                }
                notes += "giống ảnh mẫu ${p.refAmount}%"
            }

            // ----- 3. chỉnh da (trong bản đồ da) -----
            val tSkin = st1.skin
            var skinFn: ((DoubleArray, DoubleArray) -> Unit)? = null   // (lab gốc, lab đã chỉnh chung) → sửa lab đã chỉnh
            if (map != null && tSkin != null && (p.skinTone > 0 || ref != null)) {
                val sStd = st1.skinStd!!
                // màu da sau các bước chung
                val after = doubleArrayOf(tSkin[0].toDouble(), tSkin[1].toDouble(), tSkin[2].toDouble())
                if (lMap != null) after[0] = lMap(after[0])
                // da giữ màu riêng khi match: ảnh mẫu có da → kéo về da ảnh mẫu; không có → chỉ ăn 35% màu mẫu
                val keep = doubleArrayOf(after[0], tSkin[1].toDouble(), tSkin[2].toDouble())
                val rs = ref?.skin
                if (abMap != null && rs != null) {
                    // ảnh mẫu có người: da đi theo da ảnh mẫu, không ăn màu nền
                    after[0] += (rs[0] - after[0]) * 0.4 * refAmt
                    after[1] = keep[1] + (rs[1] - keep[1]) * 0.8 * refAmt
                    after[2] = keep[2] + (rs[2] - keep[2]) * 0.8 * refAmt
                } else if (abMap != null) {
                    // ảnh mẫu không có người: da chỉ ăn 35% màu mẫu
                    val tmp = after.copyOf(); abMap(tmp)
                    after[1] = tSkin[1] + (tmp[1] - tSkin[1]) * 0.35; after[2] = tSkin[2] + (tmp[2] - tSkin[2]) * 0.35
                } else { after[1] = keep[1]; after[2] = keep[2] }
                // mục tiêu theo loại da
                val goal = after.copyOf()
                val k = p.skinTone / 100.0
                if (k > 0) {
                    val t = skinGoal(after)
                    for (i in 0 until 3) goal[i] = after[i] + (t[i] - after[i]) * k
                    notes += "da: " + describe(after, goal)
                }
                val sa = max(6.0, sStd[1] * 2.6); val sb = max(6.0, sStd[2] * 2.6); val sl = max(14.0, sStd[0] * 2.8)
                val evenA = 0.25 * k; val evenB = 0.12 * k
                val c0 = tSkin
                skinFn = { orig, lab ->
                    // độ gần màu da gốc: chỉ màu giống da mới bị đổi
                    val da = (orig[1] - c0[1]) / sa; val db = (orig[2] - c0[2]) / sb; val dl = (orig[0] - c0[0]) / sl
                    val near = exp(-0.5 * (da * da + db * db + dl * dl * 0.5))
                    if (near > 0.002) {
                        // điểm này "lẽ ra" sau bước chung nằm ở đâu so với tâm da
                        val dA = orig[1] - c0[1]; val dB = orig[2] - c0[2]
                        val tA = goal[1] + dA * (1 - evenA); val tB = goal[2] + dB * (1 - evenB)
                        val tL = lab[0] + (goal[0] - after[0])
                        lab[0] += (tL - lab[0]) * near
                        lab[1] += (tA - lab[1]) * near
                        lab[2] += (tB - lab[2]) * near
                    }
                }
            } else if (p.skinTone > 0) {
                notes += if (map == null) "chưa có bản đồ da" else "không thấy da để chỉnh"
            }

            // ----- 4. dựng bảng 3D -----
            val needGlobal = ev != 0.0 || ref != null
            val gData = if (needGlobal) FloatArray(N * N * N * 3) else null
            val sData = if (skinFn != null) FloatArray(N * N * N * 3) else null
            if (gData != null || sData != null) {
                val rgb = DoubleArray(3)
                for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
                    val idx = ((bi * N + gi) * N + ri) * 3
                    rgb[0] = ri / (N - 1.0); rgb[1] = gi / (N - 1.0); rgb[2] = bi / (N - 1.0)
                    if (ev != 0.0) expose(rgb)
                    // màu sau bù sáng (trước match): mốc để so độ giống da
                    val orig = LabMath.toLab(rgb[0], rgb[1], rgb[2])
                    val lab = orig.copyOf()
                    if (lMap != null) lab[0] = lMap(lab[0]).coerceIn(0.0, 100.0)
                    val labG = lab.copyOf()
                    abMap?.invoke(labG)
                    if (gData != null) put(gData, idx, labG, if (ref == null) rgb else null)
                    if (sData != null) {
                        val labS = lab.copyOf()
                        // da: lấy màu chung theo mức đã tính ở bước 3 (giữ màu riêng), rồi kéo về mục tiêu
                        if (abMap != null) {
                            labS[1] = orig[1] + (labG[1] - orig[1]) * 0.35; labS[2] = orig[2] + (labG[2] - orig[2]) * 0.35
                        }
                        skinFn!!(orig, labS)
                        // ngoài vùng giống da: dùng đúng màu chung để không lộ mép
                        put(sData, idx, labS, null)
                    }
                }
            }
            val one = floatArrayOf(1f, 1f, 1f); val zero = floatArrayOf(0f, 0f, 0f)
            return AutoGrade(gData?.let { Lut(N, it, zero, one) }, sData?.let { Lut(N, it, zero, one) },
                if (sData != null) map else null, notes.joinToString(" · "))
        }

        /** Ghi màu vào bảng; nếu có [rgbDirect] (chỉ bù sáng) thì ghi thẳng, tránh sai số qua Lab. */
        private fun put(d: FloatArray, idx: Int, lab: DoubleArray, rgbDirect: DoubleArray?) {
            val o = rgbDirect ?: LabMath.fromLab(lab[0].coerceIn(0.0, 100.0), lab[1], lab[2])
            d[idx] = o[0].coerceIn(0.0, 1.0).toFloat(); d[idx + 1] = o[1].coerceIn(0.0, 1.0).toFloat(); d[idx + 2] = o[2].coerceIn(0.0, 1.0).toFloat()
        }

        /** Số EV để đưa L* hiện tại về L* mục tiêu (tính trên độ chói tuyến tính). */
        private fun evFor(l: Double, target: Double): Double {
            fun y(L: Double) = if (L > 8.0) ((L + 16.0) / 116.0).pow(3) else L / 903.3
            val a = y(l.coerceIn(3.0, 99.0)); val b = y(target)
            // đường cong bù sáng nén vùng sáng, nên cần nhân nhiều hơn tỉ lệ thô một chút
            // nghịch đảo của đường cong trong exposeRgb
            val g = b * (1 - a) / (a * (1 - b)).coerceAtLeast(1e-6)
            return ln(g.coerceIn(0.1, 10.0)) / ln(2.0)
        }

        /**
         * Bù sáng giữ màu: nhân độ chói tuyến tính theo đường y·g / (1 + (g−1)·y),
         * nên điểm trắng giữ nguyên, vùng sáng không bị cháy.
         */
        fun exposeRgb(rgb: DoubleArray, g: Double) {
            if (g == 1.0) return
            val r = LabMath.lin(rgb[0]); val gg = LabMath.lin(rgb[1]); val b = LabMath.lin(rgb[2])
            val y = 0.2126 * r + 0.7152 * gg + 0.0722 * b
            if (y <= 1e-6) return
            val y2 = y * g / (1 + (g - 1) * y)
            val k = y2 / y
            val mx = max(r, max(gg, b))
            if (mx * k > 1.0) {
                // kênh màu sắp tràn: nhân tới khi chạm trần rồi pha về trắng cho đủ độ sáng (tránh vệt màu ở da sáng)
                val k1 = 1.0 / mx
                val r2 = r * k1; val g2 = gg * k1; val b2 = b * k1
                val y3 = 0.2126 * r2 + 0.7152 * g2 + 0.0722 * b2
                val t = ((y2 - y3) / max(1e-6, 1 - y3)).coerceIn(0.0, 1.0)
                rgb[0] = LabMath.gam(r2 + (1 - r2) * t); rgb[1] = LabMath.gam(g2 + (1 - g2) * t); rgb[2] = LabMath.gam(b2 + (1 - b2) * t)
                return
            }
            rgb[0] = LabMath.gam(r * k); rgb[1] = LabMath.gam(gg * k); rgb[2] = LabMath.gam(b * k)
        }

        /**
         * Màu da mục tiêu theo độ sáng da (L*, a*, b*):
         *  - tông (hue) về khoảng 52–60°: bớt ửng đỏ / bớt vàng xỉn;
         *  - độ đậm (chroma) giữ trong dải theo loại da: da sáng không bị ửng, da ngăm không bị bệch xám;
         *  - độ sáng chỉ nhích nhẹ cho da sáng, giữ nguyên độ sâu của da ngăm.
         */
        fun skinGoal(s: DoubleArray): DoubleArray {
            val L = s[0]; val a = s[1]; val b = s[2]
            val C = hypot(a, b)
            var hDeg = Math.toDegrees(atan2(b, a))
            val dark = ((64.0 - L) / 20.0).coerceIn(0.0, 1.0)      // 0 = da sáng, 1 = da ngăm
            val hLo = 50.0 + dark * 2; val hHi = 60.0 + dark * 3
            val hT = when { hDeg < hLo -> hDeg + (hLo - hDeg) * 0.75; hDeg > hHi -> hDeg - (hDeg - hHi) * 0.75; else -> hDeg }
            hDeg = hDeg + (hT - hDeg).coerceIn(-12.0, 12.0)
            val cLo = 15.0 + dark * 7; val cHi = 24.0 + dark * 8
            val cT = when { C < cLo -> C + (cLo - C) * 0.7; C > cHi -> C - (C - cHi) * 0.7; else -> C }
            val lT = when {
                L > 78 -> L - (L - 76) * 0.5
                dark < 0.3 -> L + 1.5 * (1 - dark / 0.3)
                else -> L
            }
            val hr = Math.toRadians(hDeg)
            return doubleArrayOf(lT, cT * cos(hr), cT * sin(hr))
        }

        private fun describe(a: DoubleArray, b: DoubleArray): String {
            val ha = Math.toDegrees(atan2(a[2], a[1])); val hb = Math.toDegrees(atan2(b[2], b[1]))
            val ca = hypot(a[1], a[2]); val cb = hypot(b[1], b[2])
            val parts = ArrayList<String>()
            if (hb - ha > 1.5) parts += "bớt đỏ" else if (ha - hb > 1.5) parts += "bớt vàng"
            if (cb - ca > 1.5) parts += "thêm sắc hồng hào" else if (ca - cb > 1.5) parts += "bớt ửng"
            if (b[0] - a[0] > 0.8) parts += "sáng hơn" else if (a[0] - b[0] > 0.8) parts += "dịu vùng da cháy"
            return if (parts.isEmpty()) "đã chuẩn, giữ nguyên" else parts.joinToString(", ")
        }

        private fun monotone(v: FloatArray): DoubleArray {
            val o = DoubleArray(v.size)
            for (i in v.indices) o[i] = if (i == 0) v[i].toDouble() else max(v[i].toDouble(), o[i - 1] + 1.0)
            return o
        }

        /**
         * Giữ đường cong sáng không quá gắt: độ dốc mỗi đoạn trong khoảng 0,6–1,7,
         * dựng lại từ mốc giữa (50%) ra hai phía. Tránh trường hợp ảnh có biểu đồ sáng lệch bị kéo cháy.
         */
        private fun limitSlope(src: DoubleArray, dst: DoubleArray): DoubleArray {
            val o = dst.copyOf()
            val mid = src.size / 2
            o[mid] = (src[mid] + (dst[mid] - src[mid]).coerceIn(-18.0, 18.0)).coerceIn(5.0, 95.0)
            for (i in mid + 1 until src.size) {
                val dx = src[i] - src[i - 1]
                o[i] = (o[i - 1] + dx * ((dst[i] - dst[i - 1]) / dx).coerceIn(0.6, 1.7)).coerceAtMost(100.0)
            }
            for (i in mid - 1 downTo 0) {
                val dx = src[i + 1] - src[i]
                o[i] = (o[i + 1] - dx * ((dst[i + 1] - dst[i]) / dx).coerceIn(0.6, 1.7)).coerceAtLeast(0.0)
            }
            return o
        }

        /** Nội suy tuyến tính từng đoạn qua các mốc; ngoài hai đầu thì kéo dài về 0 / 100. */
        private fun pwl(x: Double, src: DoubleArray, dst: DoubleArray): Double {
            val xs = doubleArrayOf(0.0) + src + doubleArrayOf(100.0)
            val ys = doubleArrayOf(0.0) + dst + doubleArrayOf(100.0)
            for (i in 1 until xs.size) {
                if (x <= xs[i] || i == xs.size - 1) {
                    val x0 = xs[i - 1]; val x1 = max(xs[i], x0 + 1e-3)
                    val t = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
                    return ys[i - 1] + (ys[i] - ys[i - 1]) * t
                }
            }
            return x
        }
    }
}

/** Ảnh mẫu đang dùng (lưu lại để chỉnh hàng loạt dùng chung). */
object RefStore {
    private fun sp(c: Context) = c.getSharedPreferences("autoref", Context.MODE_PRIVATE)
    fun load(c: Context): ColorStats? = try {
        sp(c).getString("ref", null)?.let { ColorStats.fromJson(JSONObject(it)) }
    } catch (e: Exception) { null }
    fun save(c: Context, s: ColorStats?) {
        sp(c).edit().apply { if (s == null) remove("ref") else putString("ref", s.toJson().toString()) }.apply()
    }

    /** Mở và đo ảnh mẫu. */
    fun measure(c: Context, uri: android.net.Uri): ColorStats {
        val head = Ui.readHead(c, uri, 512 * 1024)
        val orient = PhotoInfoBuilder.build("", head).orientation
        val b = Ui.decodeMax(c, uri, 1200, orient) ?: throw IllegalStateException("không mở được ảnh mẫu")
        val faces = try { FaceFinder.detect(b) } catch (t: Throwable) { emptyList() }
        val map = SkinMap.build(b, faces)
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val st = ColorStats.measure(px, b.width, b.height, if (faces.isNotEmpty()) map else null, Ui.displayName(c, uri))
        b.recycle()
        return st
    }
}
