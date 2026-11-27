package com.looknote.exif

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder
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

// =====================================================================
//  1. Phân vùng người bằng AI: da mặt, da người, tóc, quần áo, nền
// =====================================================================

/**
 * MediaPipe Selfie Multiclass (256×256, chạy offline, mô hình nằm trong app):
 * 0 nền · 1 tóc · 2 da người · 3 da mặt · 4 quần áo · 5 phụ kiện.
 */
object Segmenter {
    const val MODEL = "selfie_multiclass_256x256.tflite"
    private var seg: ImageSegmenter? = null
    private var failed = false

    /** Xác suất từng lớp, cùng kích thước ảnh [b]; null nếu máy không chạy được. */
    @Synchronized
    fun classes(c: Context, b: Bitmap): Array<FloatArray>? {
        if (failed) return null
        try {
            val s = seg ?: ImageSegmenter.createFromOptions(c, ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()).also { seg = it }
            val src = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
            val res = s.segment(BitmapImageBuilder(src).build())
            val masks = res.confidenceMasks().orElse(null) ?: return null
            if (masks.size < 6) return null
            val out = Array(6) { k ->
                val mp = masks[k]
                val mw = mp.width; val mh = mp.height
                val raw = readFloats(ByteBufferExtractor.extract(mp), mw * mh)
                if (mw == b.width && mh == b.height) raw else SkinMap.resample(raw, mw, mh, b.width, b.height)
            }
            return out
        } catch (t: Throwable) {
            failed = true
            return null
        }
    }

    /** Đọc mảng float; tự nhận thứ tự byte (giá trị hợp lệ nằm trong 0..1). */
    private fun readFloats(buf: java.nio.ByteBuffer, n: Int): FloatArray {
        fun read(order: ByteOrder): FloatArray {
            val fb = buf.duplicate().order(order); fb.rewind()
            val f = fb.asFloatBuffer()
            return FloatArray(n) { if (it < f.limit()) f.get(it) else 0f }
        }
        val a = read(ByteOrder.nativeOrder())
        var bad = 0
        var i = 0
        while (i < n) { val v = a[i]; if (v.isNaN() || v < -0.01f || v > 1.01f) bad++; i += 97 }
        return if (bad == 0) a else read(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
    }
}

/**
 * Bản đồ da (0..1) và vùng người (0..1) ở độ phân giải xem trước.
 * Nguồn tốt nhất là AI phân lớp da; dự phòng: tách người + màu da, cuối cùng là vùng quanh mặt.
 */
class SkinMap(val w: Int, val h: Int, val m: FloatArray, val person: FloatArray?, val source: String,
              /** Tóc và quần áo (chỉ có khi dùng AI phân lớp). */
              val hair: FloatArray? = null, val clothes: FloatArray? = null) {
    val usedPerson get() = source != "face"
    val coverage: Float by lazy { var s = 0.0; for (v in m) s += v; (s / m.size).toFloat() }

    fun overlay(): Bitmap {
        val px = IntArray(w * h) { i ->
            val a = (m[i] * 170f).toInt().coerceIn(0, 170)
            (a shl 24) or 0xFF7A1A
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun describe(): String = when (source) {
        "ai" -> "Bản đồ da AI: phân lớp da mặt / da người / tóc / áo (MediaPipe)"
        "person" -> "Bản đồ da: tách người (ML Kit) + màu da lấy mẫu ở má"
        else -> "Bản đồ da: theo vùng quanh khuôn mặt"
    } + " · ${"%.1f".format(coverage * 100)}% ảnh là da."

    companion object {
        fun build(c: Context?, b: Bitmap, faces: List<FaceRegion>): SkinMap {
            val w = b.width; val h = b.height; val n = w * h
            val px = IntArray(n); b.getPixels(px, 0, w, 0, 0, w, h)
            val Y = FloatArray(n); val Cb = FloatArray(n); val Cr = FloatArray(n)
            for (i in 0 until n) {
                val cc = px[i]
                val r = ((cc shr 16) and 0xFF).toFloat(); val g = ((cc shr 8) and 0xFF).toFloat(); val bl = (cc and 0xFF).toFloat()
                Y[i] = 0.299f * r + 0.587f * g + 0.114f * bl
                Cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * bl
                Cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * bl
            }
            val samples = sampleFaces(faces, w, h, Y, Cb, Cr)
            val fw = if (faces.isNotEmpty()) faces.map { it.width }.sorted()[faces.size / 2] else max(w, h) * 0.12f

            // ----- a. AI phân lớp: cả ảnh + từng vùng quanh mặt nhỏ (ảnh toàn thân / ảnh nhóm) -----
            val ai = if (c != null) aiClasses(c, b, faces) else null
            if (ai != null) {
                val skin = ai[0]; val person = ai[1]; val hair = ai[2]; val clothes = ai[3]
                val m = FloatArray(n)
                for (i in 0 until n) {
                    var v = skin[i]
                    if (v <= 0.02f || Y[i] < 18f) continue
                    // lọc nhẹ theo màu: viền tóc / áo bị AI nhầm thường lệch xa màu da
                    if (samples.isNotEmpty()) v *= min(1f, 0.25f + colorLike(samples, Cb[i], Cr[i], 4.6f))
                    m[i] = min(1f, v)
                }
                excludeFeatures(m, samples, w, h)
                SkinRetouch.blur(m, w, h, max(1, (fw * 0.008f).roundToInt()))
                SkinRetouch.blur(person, w, h, max(1, (fw * 0.02f).roundToInt()))
                SkinRetouch.blur(hair, w, h, max(1, (fw * 0.006f).roundToInt()))
                SkinRetouch.blur(clothes, w, h, max(1, (fw * 0.008f).roundToInt()))
                return SkinMap(w, h, m, person, "ai", hair, clothes)
            }

            // ----- b. tách người (ML Kit) × màu da; c. vùng quanh mặt -----
            val person = personMask(b)
            val m = FloatArray(n)
            for (i in 0 until n) {
                if (Y[i] < 30f) continue
                var v = if (samples.isNotEmpty()) colorLike(samples, Cb[i], Cr[i], 3.4f)
                else if (Cb[i] in 80f..125f && Cr[i] in 135f..172f) 0.5f else 0f
                if (v <= 0f) continue
                if (person != null) {
                    val p = ((person[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
                    v *= p * p * (3f - 2f * p)
                } else if (samples.isNotEmpty()) {
                    v *= nearFaces(samples, (i % w).toFloat(), (i / w).toFloat())
                }
                m[i] = v
            }
            excludeFeatures(m, samples, w, h)
            SkinRetouch.blur(m, w, h, max(1, (fw * 0.012f).roundToInt()))
            person?.let { SkinRetouch.blur(it, w, h, max(1, (fw * 0.02f).roundToInt())) }
            return SkinMap(w, h, m, person, if (person != null) "person" else "face")
        }

        /** [da, người] từ MediaPipe; ghép kết quả cả ảnh với các vùng cắt quanh mặt nhỏ (lấy lớn nhất). */
        private fun aiClasses(c: Context, b: Bitmap, faces: List<FaceRegion>): Array<FloatArray>? {
            val w = b.width; val h = b.height
            val full = Segmenter.classes(c, b) ?: return null
            val skin = FloatArray(w * h) { full[2][it] + full[3][it] }
            val person = FloatArray(w * h) { 1f - full[0][it] }
            val hair = full[1].copyOf(); val clothes = FloatArray(w * h) { full[4][it] + full[5][it] }
            // mặt nhỏ hơn ~1/6 ảnh: mô hình 256px nhìn không rõ, chạy thêm trên vùng cắt
            for (f in faces.sortedByDescending { it.width }.take(6)) {
                if (f.width > max(w, h) / 6f) continue
                val x0 = (f.left - f.width * 1.8f).toInt().coerceIn(0, w - 1)
                val x1 = (f.right + f.width * 1.8f).toInt().coerceIn(x0 + 1, w)
                val y0 = (f.top - f.height * 0.8f).toInt().coerceIn(0, h - 1)
                val y1 = (f.bottom + f.height * 4.5f).toInt().coerceIn(y0 + 1, h)
                if (x1 - x0 < 16 || y1 - y0 < 16) continue
                val crop = try { Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0) } catch (t: Throwable) { continue }
                val cl = Segmenter.classes(c, crop) ?: continue
                val cw = x1 - x0; val chh = y1 - y0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val j = (y - y0) * cw + (x - x0); val i = y * w + x
                    // làm mềm mép vùng cắt để không lộ đường ranh
                    val ex = min(min(x - x0, x1 - 1 - x), min(y - y0, y1 - 1 - y)).toFloat()
                    val t = (ex / (0.08f * min(cw, chh))).coerceIn(0f, 1f)
                    val s2 = (cl[2][j] + cl[3][j]) * t; val p2 = (1f - cl[0][j]) * t
                    if (s2 > skin[i]) skin[i] = s2
                    if (p2 > person[i]) person[i] = p2
                    val h2 = cl[1][j] * t; val c2 = (cl[4][j] + cl[5][j]) * t
                    if (h2 > hair[i]) hair[i] = h2
                    if (c2 > clothes[i]) clothes[i] = c2
                }
            }
            return arrayOf(skin, person, hair, clothes)
        }

        class Sample(val cb: Float, val cr: Float, val dcb: Float, val dcr: Float, val f: FaceRegion)

        private fun sampleFaces(faces: List<FaceRegion>, w: Int, h: Int, Y: FloatArray, Cb: FloatArray, Cr: FloatArray): List<Sample> {
            val out = ArrayList<Sample>()
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
                out += Sample(mCb, mCr, dCb, dCr, f)
            }
            return out
        }

        private fun colorLike(samples: List<Sample>, cb: Float, cr: Float, reach: Float): Float {
            var v = 0f
            for (s in samples) {
                val a = (cb - s.cb) / s.dcb; val c = (cr - s.cr) / s.dcr
                val t = ((reach - sqrt(a * a + c * c)) / 1.5f).coerceIn(0f, 1f)
                if (t > v) v = t
            }
            return v
        }

        private fun nearFaces(samples: List<Sample>, x: Float, y: Float): Float {
            var near = 0f
            for (s in samples) {
                val f = s.f
                val dx = when { x < f.left - f.width * 1.6f -> (f.left - f.width * 1.6f - x) / f.width
                    x > f.right + f.width * 1.6f -> (x - f.right - f.width * 1.6f) / f.width; else -> 0f }
                val dy = when { y < f.top - f.height * 0.3f -> (f.top - f.height * 0.3f - y) / f.height
                    y > f.bottom + f.height * 3.5f -> (y - f.bottom - f.height * 3.5f) / f.height; else -> 0f }
                near = max(near, (1f - (dx + dy) * 2f).coerceIn(0f, 1f))
            }
            return near
        }

        private fun excludeFeatures(m: FloatArray, samples: List<Sample>, w: Int, h: Int) {
            for (s in samples) {
                val f = s.f
                for (e in f.exclude) {
                    val x0 = (e.cx - e.rx * 1.1f).toInt().coerceIn(0, w - 1); val x1 = (e.cx + e.rx * 1.1f).toInt().coerceIn(0, w - 1)
                    val y0 = (e.cy - e.ry * 1.1f).toInt().coerceIn(0, h - 1); val y1 = (e.cy + e.ry * 1.1f).toInt().coerceIn(0, h - 1)
                    for (y in y0..y1) for (x in x0..x1) if (inside(f, x.toFloat(), y.toFloat(), 1.05f)) m[y * w + x] = 0f
                }
            }
        }

        private fun inside(f: FaceRegion, x: Float, y: Float, scale: Float): Boolean {
            for (e in f.exclude) {
                val dx = (x - e.cx) / (e.rx * scale); val dy = (y - e.cy) / (e.ry * scale)
                if (dx * dx + dy * dy <= 1f) return true
            }
            return false
        }

        /** Tách người khỏi nền bằng ML Kit (dự phòng khi MediaPipe không chạy). */
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

        fun resample(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
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

// =====================================================================
//  5. Thông số Sony: điểm lấy nét, WB Shift, DRO / HDR
// =====================================================================

/** Gợi ý từ EXIF Sony. [focusX], [focusY]: điểm lấy nét trên ảnh đã xoay, 0..1. */
class SonyHints(val focusX: Float?, val focusY: Float?, val ab: Double, val gm: Double, val dro: Boolean) {
    fun describe(): String {
        val parts = ArrayList<String>()
        if (focusX != null) parts += "có điểm lấy nét"
        if (abs(ab) >= 0.5) parts += "WB Shift ${if (ab > 0) "A" else "B"}${PhotoInfoBuilder.fmt(abs(ab), 2)}"
        if (dro) parts += "DRO/HDR bật"
        return parts.joinToString(", ")
    }

    companion object {
        val NONE = SonyHints(null, null, 0.0, 0.0, false)

        fun read(head: ByteArray, orientation: Int): SonyHints {
            val p = ExifParser(head)
            val r = try { p.parse() } catch (e: Exception) { return NONE }
            var fx: Float? = null; var fy: Float? = null
            (r.sony[0x2027] ?: r.sony[0x204a])?.let { e ->
                try {
                    if (e.count >= 4) {
                        // [rộng, cao, x, y] theo cảm biến (chưa xoay)
                        val w = p.u16At(e, 0); val h = p.u16At(e, 1); val x = p.u16At(e, 2); val y = p.u16At(e, 3)
                        if (w > 0 && h > 0 && x in 1 until w && y in 1 until h) {
                            val nx = x.toFloat() / w; val ny = y.toFloat() / h
                            when (orientation) {
                                6 -> { fx = 1f - ny; fy = nx }
                                8 -> { fx = ny; fy = 1f - nx }
                                3 -> { fx = 1f - nx; fy = 1f - ny }
                                else -> { fx = nx; fy = ny }
                            }
                        }
                    }
                } catch (t: Throwable) { }
            }
            val dro = try {
                val d = r.sony[0xB025]?.let { p.firstLong(it) } ?: 0L
                val hdr = r.sony[0x200A]?.let { p.firstLong(it) } ?: 0L
                d != 0L || (hdr and 0xFFFF) != 0L || (hdr shr 16) != 0L
            } catch (t: Throwable) { false }
            val src = try { PhotoInfoBuilder.sourcePreset(head) } catch (t: Throwable) { null }
            return SonyHints(fx, fy, src?.ab ?: 0.0, src?.gm ?: 0.0, dro)
        }
    }
}

// =====================================================================
//  Thống kê màu (ảnh mẫu, gu riêng, ảnh đang chỉnh)
// =====================================================================

class ColorStats(
    /** L* ở các mốc 2, 25, 50, 75, 98 %. */
    val lp: FloatArray,
    val aMean: Float, val aStd: Float, val bMean: Float, val bStd: Float,
    /** Màu da trung bình L*, a*, b* (null nếu không có da). */
    val skin: FloatArray?,
    val skinStd: FloatArray?,
    val name: String = "",
    /** Theo vùng tối / trung / sáng: trọng số, a*, b* (9 số). */
    val zones: FloatArray = FloatArray(9),
    /** Cây lá và trời: trọng số, hue, chroma (6 số). */
    val sectors: FloatArray = FloatArray(6),
    /** L* da mặt chính, L* trung vị phần nền (ngoài người). */
    val faceL: Float = Float.NaN,
    val bgL: Float = Float.NaN
) {
    fun toJson() = JSONObject().apply {
        put("lp", arr(lp)); put("a", aMean.toDouble()); put("as", aStd.toDouble()); put("b", bMean.toDouble()); put("bs", bStd.toDouble())
        skin?.let { put("skin", arr(it)) }; skinStd?.let { put("skinStd", arr(it)) }
        put("name", name); put("zones", arr(zones)); put("sectors", arr(sectors))
    }

    companion object {
        private fun arr(a: FloatArray) = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
        private fun fa(j: JSONArray?) = j?.let { FloatArray(it.length()) { i -> it.getDouble(i).toFloat() } }

        fun fromJson(o: JSONObject) = ColorStats(
            fa(o.getJSONArray("lp"))!!, o.getDouble("a").toFloat(), o.getDouble("as").toFloat(),
            o.getDouble("b").toFloat(), o.getDouble("bs").toFloat(),
            fa(o.optJSONArray("skin")), fa(o.optJSONArray("skinStd")), o.optString("name", ""),
            fa(o.optJSONArray("zones"))?.takeIf { it.size == 9 } ?: FloatArray(9),
            fa(o.optJSONArray("sectors"))?.takeIf { it.size == 6 } ?: FloatArray(6))

        fun zoneWeights(L: Double, out: DoubleArray) {
            val s = ((45.0 - L) / 20.0).coerceIn(0.0, 1.0)
            val hgh = ((L - 60.0) / 20.0).coerceIn(0.0, 1.0)
            out[0] = s; out[2] = hgh; out[1] = max(0.0, 1.0 - s - hgh)
        }

        /** Mức thuộc vùng cây lá (k=0) / trời (k=1) theo hue (độ) và chroma. */
        fun sectorWeight(k: Int, hue: Double, chroma: Double): Double {
            val center = if (k == 0) 125.0 else 245.0
            val half = if (k == 0) 45.0 else 35.0
            var d = abs(hue - center); if (d > 180) d = 360 - d
            val wh = ((half - d) / (half * 0.4)).coerceIn(0.0, 1.0)
            val wc = ((chroma - 6.0) / 8.0).coerceIn(0.0, 1.0)
            return wh * wc
        }

        /**
         * Đo thống kê. [map] cùng kích thước với [px]: màu nền đo ở vùng không phải da, màu da ở vùng da chắc chắn.
         * @param pre biến đổi áp lên từng mẫu trước khi đo (vd. bù sáng), trên sRGB 0..1
         * @param main khuôn mặt chính để đo độ sáng mặt
         */
        fun measure(px: IntArray, w: Int, h: Int, map: SkinMap?, name: String = "",
                    pre: ((DoubleArray) -> Unit)? = null, main: FaceRegion? = null): ColorStats {
            val step = max(1, sqrt(w * h / 250_000.0).toInt())
            val ls = ArrayList<Float>(w * h / (step * step) + 16)
            val bgs = ArrayList<Float>()
            var wa = 0.0; var sa = 0.0; var sa2 = 0.0; var sb = 0.0; var sb2 = 0.0
            var ws = 0.0; val sk = DoubleArray(3); val sk2 = DoubleArray(3)
            val zw = DoubleArray(3); val za = DoubleArray(3); val zb = DoubleArray(3); val zt = DoubleArray(3)
            val secW = DoubleArray(2); val secA = DoubleArray(2); val secB = DoubleArray(2)
            var fs = 0.0; var fn = 0.0
            val rgb = DoubleArray(3)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val i = y * w + x
                    val c = px[i]
                    rgb[0] = ((c shr 16) and 0xFF) / 255.0; rgb[1] = ((c shr 8) and 0xFF) / 255.0; rgb[2] = (c and 0xFF) / 255.0
                    pre?.invoke(rgb)
                    val lab = LabMath.toLab(rgb[0], rgb[1], rgb[2])
                    ls += lab[0].toFloat()
                    val s = if (map != null) map.m[i].toDouble() else 0.0
                    val pers = map?.person?.get(i)?.toDouble() ?: s
                    if (pers < 0.3) bgs += lab[0].toFloat()
                    val wn = 1.0 - s
                    wa += wn; sa += wn * lab[1]; sa2 += wn * lab[1] * lab[1]; sb += wn * lab[2]; sb2 += wn * lab[2] * lab[2]
                    zoneWeights(lab[0], zt)
                    for (k in 0 until 3) { val q = zt[k] * wn; zw[k] += q; za[k] += q * lab[1]; zb[k] += q * lab[2] }
                    val ch = hypot(lab[1], lab[2])
                    val hue = Math.toDegrees(atan2(lab[2], lab[1])).let { if (it < 0) it + 360 else it }
                    for (k in 0 until 2) {
                        val q = sectorWeight(k, hue, ch) * wn
                        if (q > 0) { secW[k] += q; secA[k] += q * lab[1]; secB[k] += q * lab[2] }
                    }
                    if (s > 0.6) {
                        ws += s
                        for (k in 0 until 3) { sk[k] += s * lab[k]; sk2[k] += s * lab[k] * lab[k] }
                        if (main != null && x >= main.left && x <= main.right && y >= main.top && y <= main.bottom) { fs += s * lab[0]; fn += s }
                    }
                    x += step
                }
                y += step
            }
            ls.sort(); bgs.sort()
            fun pct(p: Float) = if (ls.isEmpty()) 50f else ls[((ls.size - 1) * p).roundToInt()]
            val lp = floatArrayOf(pct(0.02f), pct(0.25f), pct(0.5f), pct(0.75f), pct(0.98f))
            val am = if (wa > 0) sa / wa else 0.0; val bm = if (wa > 0) sb / wa else 0.0
            val asd = if (wa > 0) sqrt(max(0.0, sa2 / wa - am * am)) else 10.0
            val bsd = if (wa > 0) sqrt(max(0.0, sb2 / wa - bm * bm)) else 10.0
            val total = max(1.0, wa)
            val zones = FloatArray(9)
            for (k in 0 until 3) {
                zones[k * 3] = (zw[k] / total).toFloat()
                zones[k * 3 + 1] = if (zw[k] > 0) (za[k] / zw[k]).toFloat() else am.toFloat()
                zones[k * 3 + 2] = if (zw[k] > 0) (zb[k] / zw[k]).toFloat() else bm.toFloat()
            }
            val sectors = FloatArray(6)
            for (k in 0 until 2) {
                sectors[k * 3] = (secW[k] / total).toFloat()
                if (secW[k] > 0) {
                    val a = secA[k] / secW[k]; val b = secB[k] / secW[k]
                    sectors[k * 3 + 1] = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }.toFloat()
                    sectors[k * 3 + 2] = hypot(a, b).toFloat()
                }
            }
            val enough = ws > ls.size * 0.003 && ws > 30
            val skinMean = if (enough) FloatArray(3) { (sk[it] / ws).toFloat() } else null
            val skinStd = if (enough) FloatArray(3) { k -> val mu = sk[k] / ws; sqrt(max(0.0, sk2[k] / ws - mu * mu)).toFloat() } else null
            val faceL = if (fn > 10) (fs / fn).toFloat() else skinMean?.get(0) ?: Float.NaN
            val bgL = if (bgs.size > 50) bgs[bgs.size / 2] else Float.NaN
            return ColorStats(lp, am.toFloat(), max(2.0, asd).toFloat(), bm.toFloat(), max(2.0, bsd).toFloat(),
                skinMean, skinStd, name, zones, sectors, faceL, bgL)
        }

        /** 3. Gộp nhiều ảnh thành một "gu" (trung vị từng thông số). */
        fun merge(list: List<ColorStats>, name: String): ColorStats {
            fun med(v: List<Float>): Float = if (v.isEmpty()) 0f else v.sorted()[v.size / 2]
            val lp = FloatArray(5) { k -> med(list.map { it.lp[k] }) }
            val withSkin = list.filter { it.skin != null && it.skinStd != null }
            val skin = if (withSkin.isNotEmpty()) FloatArray(3) { k -> med(withSkin.map { it.skin!![k] }) } else null
            val skinStd = if (withSkin.isNotEmpty()) FloatArray(3) { k -> med(withSkin.map { it.skinStd!![k] }) } else null
            val zones = FloatArray(9) { k -> med(list.map { it.zones[k] }) }
            val sectors = FloatArray(6) { k ->
                if (k % 3 == 0) med(list.map { it.sectors[k] })
                else med(list.filter { it.sectors[k - k % 3] > 0.01f }.map { it.sectors[k] })
            }
            return ColorStats(lp, med(list.map { it.aMean }), med(list.map { it.aStd }), med(list.map { it.bMean }), med(list.map { it.bStd }),
                skin, skinStd, name, zones, sectors)
        }
    }
}

/** 2. Mục tiêu chung của cả bộ ảnh (chỉnh hàng loạt đồng bộ). */
class SetTarget(val skin: FloatArray?, val medianL: Float, val count: Int) {
    companion object {
        fun of(list: List<ColorStats>): SetTarget {
            fun med(v: List<Float>): Float = if (v.isEmpty()) 50f else v.sorted()[v.size / 2]
            val ws = list.filter { it.skin != null }
            val skin = if (ws.size >= 2) FloatArray(3) { k -> med(ws.map { it.skin!![k] }) } else null
            return SetTarget(skin, med(list.map { it.lp[2] }), list.size)
        }
    }
}

/** Thiết lập tự động. Các mức 0..100. */
data class AutoParams(
    val light: Int = 0,
    val skinTone: Int = 0,
    val refAmount: Int = 0,
    val ref: ColorStats? = null,
    /** Gu riêng học từ ảnh đẹp của người dùng (null = dùng mục tiêu chuẩn). */
    val taste: ColorStats? = null,
    /** Mục tiêu chung cả bộ ảnh (null = mỗi ảnh tự cân). */
    val set: SetTarget? = null,
    val hints: SonyHints = SonyHints.NONE,
    /** Tự nâng riêng người khi ảnh ngược sáng. */
    val backlight: Boolean = true
) {
    val on get() = light > 0 || skinTone > 0 || (ref != null && refAmount > 0)
    fun key() = "$light-$skinTone-$refAmount-${ref?.name}-${ref?.lp?.contentToString()}-${taste?.name}-${taste?.skin?.contentToString()}-" +
        "${set?.skin?.contentToString()}-${set?.medianL}-${hints.focusX}-${hints.ab}-${hints.dro}-$backlight"
}

// =====================================================================
//  Bộ chỉnh tự động: bảng màu 3D cho cả ảnh, vùng người, vùng da
// =====================================================================

class AutoGrade(
    val global: Lut?,
    /** Vùng người (khi ngược sáng): biến đổi đầy đủ tính từ ảnh gốc. */
    val personLut: Lut?, val personMask: FloatArray?,
    val skin: Lut?, val mask: SkinMap?,
    val note: String
) {
    val isIdentity get() = global == null && (personLut == null || personMask == null) && (skin == null || mask == null)

    fun apply(buf: IntArray, W: Int, H: Int, y0: Int, rows: Int) {
        if (isIdentity) return
        val g = FloatArray(3); val s = FloatArray(3)
        val m = mask
        val mw = m?.w ?: 0; val mh = m?.h ?: 0
        val sx = if (m != null) mw.toFloat() / W else 0f
        val sy = if (m != null) mh.toFloat() / H else 0f
        val pl = personLut; val sl = skin
        val pm = if (pl != null) personMask else null
        val sm = if (sl != null) m?.m else null
        for (yy in 0 until rows) {
            var my1 = 0; var my2 = 0; var ty = 0f
            if (m != null) {
                val fy = ((y0 + yy) + 0.5f) * sy - 0.5f
                my1 = fy.toInt().coerceIn(0, mh - 1); my2 = min(mh - 1, my1 + 1); ty = (fy - my1).coerceIn(0f, 1f)
            }
            for (x in 0 until W) {
                val k = yy * W + x
                val c = buf[k]
                val r = ((c shr 16) and 0xFF) / 255f; val gg = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                if (global != null) global.lookup(r, gg, b, g) else { g[0] = r; g[1] = gg; g[2] = b }
                if (m != null && (pm != null || sm != null)) {
                    val fx = (x + 0.5f) * sx - 0.5f
                    val x1 = fx.toInt().coerceIn(0, mw - 1); val x2 = min(mw - 1, x1 + 1); val tx = (fx - x1).coerceIn(0f, 1f)
                    val i11 = my1 * mw + x1; val i12 = my1 * mw + x2; val i21 = my2 * mw + x1; val i22 = my2 * mw + x2
                    if (pm != null && pl != null) {
                        val top = pm[i11] + (pm[i12] - pm[i11]) * tx; val bot = pm[i21] + (pm[i22] - pm[i21]) * tx
                        val a = (top + (bot - top) * ty).coerceIn(0f, 1f)
                        if (a > 0.004f) { pl.lookup(r, gg, b, s); for (ch in 0 until 3) g[ch] += (s[ch] - g[ch]) * a }
                    }
                    if (sm != null && sl != null) {
                        val top = sm[i11] + (sm[i12] - sm[i11]) * tx; val bot = sm[i21] + (sm[i22] - sm[i21]) * tx
                        val a = (top + (bot - top) * ty).coerceIn(0f, 1f)
                        if (a > 0.004f) { sl.lookup(r, gg, b, s); for (ch in 0 until 3) g[ch] += (s[ch] - g[ch]) * a }
                    }
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
        private fun fmtEv(v: Double) = (if (v > 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')

        /** 5. Khuôn mặt chính: gần điểm lấy nét nhất; không có điểm nét thì mặt lớn nhất. */
        fun mainFace(faces: List<FaceRegion>, w: Int, h: Int, hints: SonyHints): FaceRegion? {
            if (faces.isEmpty()) return null
            val fx = hints.focusX; val fy = hints.focusY
            if (fx != null && fy != null) {
                val px = fx * w; val py = fy * h
                fun dist(f: FaceRegion) = hypot((px - (f.left + f.right) / 2).toDouble(), (py - (f.top + f.bottom) / 2).toDouble())
                val best = faces.minByOrNull { dist(it) / it.width }!!
                // điểm nét phải gần mặt (trong ~2 lần bề rộng mặt), không thì nét đang ở chỗ khác
                if (dist(best) < best.width * 2.0) return best
            }
            return faces.maxByOrNull { it.width }
        }

        /**
         * @param px ảnh xem trước đã qua các bước chỉnh trước đó (màu, da...)
         * @param map bản đồ da cùng kích thước
         * @param faces khuôn mặt (để chọn người chính theo điểm lấy nét)
         */
        fun compute(px: IntArray, w: Int, h: Int, map: SkinMap?, p: AutoParams, faces: List<FaceRegion> = emptyList()): AutoGrade {
            if (!p.on) return AutoGrade(null, null, null, null, null, "")
            val notes = ArrayList<String>()
            val main = mainFace(faces, w, h, p.hints)
            if (main != null && p.hints.focusX != null && faces.size > 1) notes += "cân theo người được lấy nét"

            // ----- mục tiêu: cả bộ ảnh → gu riêng → chuẩn -----
            val st0 = ColorStats.measure(px, w, h, map, main = main)
            val setSkin = p.set?.skin?.takeIf { s ->
                val t = st0.skin ?: return@takeIf false
                // chỉ ép theo cả bộ khi cùng kiểu da: so tông và tỉ lệ độ đậm (không so độ sáng, vì độ sáng là thứ cần cân)
                var dh = Math.toDegrees(atan2(t[2].toDouble(), t[1].toDouble()) - atan2(s[2].toDouble(), s[1].toDouble()))
                if (dh > 180) dh -= 360
                if (dh < -180) dh += 360
                val cr = hypot(t[1].toDouble(), t[2].toDouble()) / max(1.0, hypot(s[1].toDouble(), s[2].toDouble()))
                abs(dh) < 12 && cr in 0.55..1.8
            }
            val tasteSkin = p.taste?.skin
            val baseTarget = when {
                setSkin != null -> setSkin[0].toDouble()
                tasteSkin != null -> tasteSkin[0].toDouble().coerceIn(55.0, 78.0)
                else -> 66.0
            }

            // ----- ánh sáng; 6. ngược sáng: nâng riêng người -----
            var ev = 0.0; var evPerson = 0.0
            val hasPersonMask = map?.person != null
            if (p.light > 0) {
                val L = st0.faceL
                val median = st0.lp[2]
                if (!L.isNaN() && st0.skin != null) {
                    val bg = st0.bgL
                    // ngược sáng: nền sáng hơn mặt rõ rệt và có vùng rất sáng
                    val backCase = hasPersonMask && !bg.isNaN() && bg - L > 18 && st0.lp[4] > 85
                    // da ngăm thật (cảnh đủ sáng, không ngược sáng) thì giữ độ sâu
                    val target = if (!backCase && L < baseTarget - 14 && median >= 42f && setSkin == null) max(L + 5.0, baseTarget - 14) else baseTarget
                    val evFace = (evFor(L.toDouble(), target) * 0.85).coerceIn(-1.0, 1.6)
                    val backlit = p.backlight && backCase && evFace > 0.3
                    if (backlit) {
                        evPerson = evFace
                        ev = (evFor(median.toDouble(), 48.0) * 0.4).coerceIn(-0.5, 0.3)
                    } else {
                        ev = evFace
                        notes += "da mặt L*=${L.roundToInt()} → ${target.roundToInt()}"
                    }
                } else {
                    val tgt = p.set?.medianL?.toDouble() ?: p.taste?.lp?.get(2)?.toDouble()?.coerceIn(38.0, 62.0) ?: 50.0
                    ev = (evFor(median.toDouble(), tgt) * 0.6).coerceIn(-1.0, 1.2)
                    notes += "không có da: cân theo cả ảnh"
                }
                // vùng sáng gần cháy: bớt tăng; máy đã bật DRO/HDR thì càng giữ vùng sáng
                if (ev > 0 && st0.lp[4] > 92f) ev *= 0.6
                if (ev > 0 && p.hints.dro && st0.lp[4] > 88f) ev *= 0.7
                ev *= p.light / 100.0; evPerson *= p.light / 100.0
                if (abs(ev) < 0.03) ev = 0.0
                if (abs(evPerson) < 0.03) evPerson = 0.0
                if (evPerson != 0.0) notes += "ngược sáng: nâng riêng người ${fmtEv(evPerson)} EV, giữ trời/nền"
                else if (ev != 0.0) notes += "bù ${fmtEv(ev)} EV"
            }
            val gain = 2.0.pow(ev)
            val gainP = 2.0.pow(evPerson)
            val expose: (DoubleArray) -> Unit = { rgb -> exposeRgb(rgb, gain) }

            // ----- 4. match ảnh mẫu theo vùng tối / trung / sáng, cây lá, trời -----
            val ref = if (p.refAmount > 0) p.ref else null
            val st1 = if (ev != 0.0) ColorStats.measure(px, w, h, map, pre = expose, main = main) else st0
            val refAmt = p.refAmount / 100.0
            var lMap: ((Double) -> Double)? = null
            var abMap: ((DoubleArray) -> Unit)? = null
            if (ref != null) {
                val src = monotone(st1.lp); val dst = limitSlope(src, monotone(ref.lp))
                lMap = { l -> l + (pwl(l, src, dst) - l) * refAmt }
                val ka = (ref.aStd / st1.aStd).toDouble().coerceIn(0.6, 1.4)
                val kb = (ref.bStd / st1.bStd).toDouble().coerceIn(0.6, 1.4)
                // độ lệch màu từng vùng; vùng nào một trong hai ảnh không có thì dùng độ lệch trung bình
                val dA = DoubleArray(3); val dB = DoubleArray(3)
                for (k in 0 until 3) {
                    val ok = ref.zones[k * 3] > 0.03f && st1.zones[k * 3] > 0.03f
                    dA[k] = (if (ok) (ref.zones[k * 3 + 1] - st1.zones[k * 3 + 1]).toDouble() else (ref.aMean - st1.aMean).toDouble()).coerceIn(-25.0, 25.0)
                    dB[k] = (if (ok) (ref.zones[k * 3 + 2] - st1.zones[k * 3 + 2]).toDouble() else (ref.bMean - st1.bMean).toDouble()).coerceIn(-25.0, 25.0)
                }
                // cây lá / trời: xoay tông + đổi độ đậm theo ảnh mẫu
                val secRot = DoubleArray(2); val secK = DoubleArray(2) { 1.0 }; val secOn = BooleanArray(2)
                for (k in 0 until 2) {
                    if (ref.sectors[k * 3] > 0.01f && st1.sectors[k * 3] > 0.01f && st1.sectors[k * 3 + 2] > 3f) {
                        var d = (ref.sectors[k * 3 + 1] - st1.sectors[k * 3 + 1]).toDouble()
                        if (d > 180) d -= 360
                        if (d < -180) d += 360
                        secRot[k] = d.coerceIn(-30.0, 30.0)
                        secK[k] = (ref.sectors[k * 3 + 2] / st1.sectors[k * 3 + 2]).toDouble().coerceIn(0.6, 1.5)
                        secOn[k] = true
                    }
                }
                val zt = DoubleArray(3)
                abMap = { lab ->
                    val a0 = lab[1]; val b0 = lab[2]
                    var a2 = (a0 - st1.aMean) * ka + st1.aMean
                    var b2 = (b0 - st1.bMean) * kb + st1.bMean
                    ColorStats.zoneWeights(lab[0], zt)
                    for (k in 0 until 3) { a2 += zt[k] * dA[k]; b2 += zt[k] * dB[k] }
                    val ch = hypot(a0, b0)
                    val hue = Math.toDegrees(atan2(b0, a0)).let { if (it < 0) it + 360 else it }
                    for (k in 0 until 2) {
                        if (!secOn[k]) continue
                        val q = ColorStats.sectorWeight(k, hue, ch)
                        if (q <= 0) continue
                        val nh = Math.toRadians(hue + secRot[k]); val nc = ch * secK[k]
                        a2 += (nc * cos(nh) - a0) * q; b2 += (nc * sin(nh) - b0) * q
                    }
                    lab[1] += (a2 - lab[1]) * refAmt; lab[2] += (b2 - lab[2]) * refAmt
                }
                notes += "giống ảnh mẫu ${p.refAmount}% (theo vùng tối/sáng, cây lá, trời)"
            }

            // ----- chỉnh da (trong bản đồ da) -----
            val tSkin = st1.skin
            var skinFn: ((DoubleArray, DoubleArray) -> Unit)? = null
            if (map != null && tSkin != null && (p.skinTone > 0 || ref != null)) {
                val sStd = st1.skinStd!!
                val after = doubleArrayOf(tSkin[0].toDouble(), tSkin[1].toDouble(), tSkin[2].toDouble())
                if (evPerson != 0.0) after[0] = lStar(after[0], gainP)
                if (lMap != null) after[0] = lMap(after[0])
                val keep = after.copyOf()
                val rs = ref?.skin
                if (abMap != null && rs != null) {
                    // ảnh mẫu có người: da theo da ảnh mẫu, không ăn màu nền
                    after[0] += (rs[0] - after[0]) * 0.4 * refAmt
                    after[1] = keep[1] + (rs[1] - keep[1]) * 0.8 * refAmt
                    after[2] = keep[2] + (rs[2] - keep[2]) * 0.8 * refAmt
                } else if (abMap != null) {
                    val tmp = after.copyOf(); abMap(tmp)
                    after[1] = tSkin[1] + (tmp[1] - tSkin[1]) * 0.35; after[2] = tSkin[2] + (tmp[2] - tSkin[2]) * 0.35
                }
                val goal = after.copyOf()
                val k = p.skinTone / 100.0
                if (k > 0) {
                    val t = skinGoal(after, tasteSkin, p.hints.ab)
                    // 2. đồng bộ cả bộ: kéo về màu da chung
                    if (setSkin != null) for (i in 1..2) t[i] = t[i] * 0.3 + setSkin[i] * 0.7
                    for (i in 0 until 3) goal[i] = after[i] + (t[i] - after[i]) * k
                    notes += "da: " + describe(after, goal) + when {
                        setSkin != null -> " · theo màu da chung của bộ"
                        tasteSkin != null -> " · theo gu riêng"
                        else -> ""
                    }
                }
                val shiftL = goal[0] - after[0]
                val sa = max(6.0, sStd[1] * 2.6); val sb = max(6.0, sStd[2] * 2.6); val sl = max(14.0, sStd[0] * 2.8)
                val evenA = 0.25 * k; val evenB = 0.12 * k
                val c0 = tSkin
                skinFn = { orig, lab ->
                    val da = (orig[1] - c0[1]) / sa; val db = (orig[2] - c0[2]) / sb; val dl = (orig[0] - c0[0]) / sl
                    val near = exp(-0.5 * (da * da + db * db + dl * dl * 0.5))
                    if (near > 0.002) {
                        val tA = goal[1] + (orig[1] - c0[1]) * (1 - evenA); val tB = goal[2] + (orig[2] - c0[2]) * (1 - evenB)
                        val tL = lab[0] + shiftL
                        lab[0] += (tL - lab[0]) * near; lab[1] += (tA - lab[1]) * near; lab[2] += (tB - lab[2]) * near
                    }
                }
            } else if (p.skinTone > 0) {
                notes += if (map == null) "chưa có bản đồ da" else "không thấy da để chỉnh"
            }

            // ----- dựng bảng 3D -----
            val needGlobal = ev != 0.0 || ref != null
            val needPerson = evPerson != 0.0 && map?.person != null
            val gData = if (needGlobal) FloatArray(N * N * N * 3) else null
            val pData = if (needPerson) FloatArray(N * N * N * 3) else null
            val sData = if (skinFn != null) FloatArray(N * N * N * 3) else null
            if (gData != null || pData != null || sData != null) {
                val rgb = DoubleArray(3); val rgbP = DoubleArray(3)
                for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
                    val idx = ((bi * N + gi) * N + ri) * 3
                    rgb[0] = ri / (N - 1.0); rgb[1] = gi / (N - 1.0); rgb[2] = bi / (N - 1.0)
                    rgbP[0] = rgb[0]; rgbP[1] = rgb[1]; rgbP[2] = rgb[2]
                    if (ev != 0.0) expose(rgb)
                    val labE = LabMath.toLab(rgb[0], rgb[1], rgb[2])
                    val labG = labE.copyOf()
                    if (lMap != null) labG[0] = lMap(labG[0]).coerceIn(0.0, 100.0)
                    abMap?.invoke(labG)
                    if (gData != null) put(gData, idx, labG, if (ref == null) rgb else null)
                    // vùng người khi ngược sáng: bù sáng riêng thay cho bù sáng chung
                    var labPE = labE
                    if (evPerson != 0.0) {
                        exposeRgb(rgbP, gainP)
                        labPE = LabMath.toLab(rgbP[0], rgbP[1], rgbP[2])
                    }
                    if (pData != null) {
                        val labP = labPE.copyOf()
                        if (lMap != null) labP[0] = lMap(labP[0]).coerceIn(0.0, 100.0)
                        abMap?.invoke(labP)
                        put(pData, idx, labP, if (ref == null) rgbP else null)
                    }
                    if (sData != null) {
                        val base = labPE
                        val labS = base.copyOf()
                        if (lMap != null) labS[0] = lMap(labS[0]).coerceIn(0.0, 100.0)
                        if (abMap != null) {
                            val t = labS.copyOf(); abMap(t)
                            labS[1] = base[1] + (t[1] - base[1]) * 0.35; labS[2] = base[2] + (t[2] - base[2]) * 0.35
                        }
                        // độ giống da so với màu da đo trước bước nâng riêng người
                        skinFn?.invoke(labE, labS)
                        put(sData, idx, labS, null)
                    }
                }
            }
            val one = floatArrayOf(1f, 1f, 1f); val zero = floatArrayOf(0f, 0f, 0f)
            return AutoGrade(gData?.let { Lut(N, it, zero, one) },
                pData?.let { Lut(N, it, zero, one) }, if (pData != null) map?.person else null,
                sData?.let { Lut(N, it, zero, one) }, if (sData != null || pData != null) map else null,
                notes.joinToString(" · "))
        }

        private fun put(d: FloatArray, idx: Int, lab: DoubleArray, rgbDirect: DoubleArray?) {
            val o = rgbDirect ?: LabMath.fromLab(lab[0].coerceIn(0.0, 100.0), lab[1], lab[2])
            d[idx] = o[0].coerceIn(0.0, 1.0).toFloat(); d[idx + 1] = o[1].coerceIn(0.0, 1.0).toFloat(); d[idx + 2] = o[2].coerceIn(0.0, 1.0).toFloat()
        }

        private fun yOf(L: Double) = if (L > 8.0) ((L + 16.0) / 116.0).pow(3) else L / 903.3
        private fun lOf(y: Double) = if (y > 216.0 / 24389.0) 116.0 * Math.cbrt(y) - 16.0 else y * 903.3

        /** L* sau khi bù sáng với hệ số [g]. */
        private fun lStar(L: Double, g: Double): Double { val y = yOf(L); return lOf(y * g / (1 + (g - 1) * y)) }

        /** Số EV để đưa L* hiện tại về L* mục tiêu (nghịch đảo đường cong trong exposeRgb). */
        private fun evFor(l: Double, target: Double): Double {
            val a = yOf(l.coerceIn(3.0, 99.0)); val b = yOf(target.coerceIn(3.0, 97.0))
            val g = b * (1 - a) / (a * (1 - b)).coerceAtLeast(1e-6)
            return ln(g.coerceIn(0.1, 10.0)) / ln(2.0)
        }

        /** Bù sáng giữ màu: y·g / (1 + (g−1)·y), điểm trắng giữ nguyên nên vùng sáng không cháy. */
        fun exposeRgb(rgb: DoubleArray, g: Double) {
            if (g == 1.0) return
            val r = LabMath.lin(rgb[0]); val gg = LabMath.lin(rgb[1]); val b = LabMath.lin(rgb[2])
            val y = 0.2126 * r + 0.7152 * gg + 0.0722 * b
            if (y <= 1e-6) return
            val y2 = y * g / (1 + (g - 1) * y)
            val k = y2 / y
            val mx = max(r, max(gg, b))
            if (mx * k > 1.0) {
                // kênh màu sắp tràn: nhân tới trần rồi pha về trắng cho đủ độ sáng (tránh vệt màu ở da sáng)
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
         * Màu da mục tiêu.
         *  - Chuẩn: tông 50–60°, độ đậm theo loại da (da sáng không ửng, da ngăm không bệch).
         *  - 3. Có gu riêng: tông và độ đậm theo da trong ảnh người dùng thích (±4).
         *  - 5. WB Shift lúc chụp: dời khoảng tông theo hướng người chụp đã chủ ý (A ấm / B lạnh).
         */
        fun skinGoal(s: DoubleArray, taste: FloatArray? = null, wbAb: Double = 0.0): DoubleArray {
            val L = s[0]; val a = s[1]; val b = s[2]
            val C = hypot(a, b)
            var hDeg = Math.toDegrees(atan2(b, a))
            val dark = ((64.0 - L) / 20.0).coerceIn(0.0, 1.0)
            val bias = (wbAb * 1.5).coerceIn(-6.0, 6.0)
            var hLo = 50.0 + dark * 2 + bias; var hHi = 60.0 + dark * 3 + bias
            var cLo = 15.0 + dark * 7; var cHi = 24.0 + dark * 8 + abs(wbAb).coerceAtMost(4.0)
            if (taste != null) {
                val th = Math.toDegrees(atan2(taste[2].toDouble(), taste[1].toDouble())) + bias
                val tc = hypot(taste[1].toDouble(), taste[2].toDouble())
                hLo = th - 4; hHi = th + 4
                // da sẫm hơn nhiều so với da trong gu: giữ dải độ đậm chuẩn để không bệch
                if (L > taste[0] - 12) { cLo = tc - 4; cHi = tc + 4 }
            }
            val hT = when { hDeg < hLo -> hDeg + (hLo - hDeg) * 0.75; hDeg > hHi -> hDeg - (hDeg - hHi) * 0.75; else -> hDeg }
            hDeg += (hT - hDeg).coerceIn(-12.0, 12.0)
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

        /** Độ dốc mỗi đoạn 0,6–1,7, dựng từ mốc giữa ra hai phía: tránh kéo cháy khi biểu đồ sáng lệch. */
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

// =====================================================================
//  Lưu ảnh mẫu và gu riêng
// =====================================================================

object RefStore {
    private fun sp(c: Context) = c.getSharedPreferences("autoref", Context.MODE_PRIVATE)
    private fun get(c: Context, k: String): ColorStats? = try {
        sp(c).getString(k, null)?.let { ColorStats.fromJson(JSONObject(it)) }
    } catch (e: Exception) { null }
    private fun set(c: Context, k: String, s: ColorStats?) {
        sp(c).edit().apply { if (s == null) remove(k) else putString(k, s.toJson().toString()) }.apply()
    }

    fun load(c: Context) = get(c, "ref")
    fun save(c: Context, s: ColorStats?) = set(c, "ref", s)
    /** 3. Gu riêng (gộp từ nhiều ảnh đẹp). */
    fun loadTaste(c: Context) = get(c, "taste")
    fun saveTaste(c: Context, s: ColorStats?) = set(c, "taste", s)

    /** Mở và đo một ảnh (dùng cho ảnh mẫu và ảnh học gu). */
    fun measure(c: Context, uri: android.net.Uri): ColorStats {
        val head = Ui.readHead(c, uri, 512 * 1024)
        val orient = PhotoInfoBuilder.build("", head).orientation
        val b = Ui.decodeMax(c, uri, 1200, orient) ?: throw IllegalStateException("không mở được ảnh")
        val faces = try { FaceFinder.detect(b) } catch (t: Throwable) { emptyList() }
        val map = SkinMap.build(c, b, faces)
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val main = AutoGrade.mainFace(faces, b.width, b.height, SonyHints.read(head, orient))
        val st = ColorStats.measure(px, b.width, b.height, map.takeIf { faces.isNotEmpty() || it.usedPerson },
            Ui.displayName(c, uri), main = main)
        b.recycle()
        return st
    }
}
