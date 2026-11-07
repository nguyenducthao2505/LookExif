package com.looknote.exif

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/** Kết quả nhận diện: vùng mặt cho làm da, số người nhắm mắt, khung mặt lớn nhất. */
class FaceScan(val regions: List<FaceRegion>, val eyesClosed: Int, val largest: Rect?)

/** Nhận diện khuôn mặt bằng ML Kit (chạy offline) và dựng vùng loại trừ mắt, mày, môi, mũi. */
object FaceFinder {

    fun detect(b: Bitmap): List<FaceRegion> = analyze(b).regions

    /** @param fast chế độ nhanh (dùng khi quét hàng loạt) */
    fun analyze(b: Bitmap, fast: Boolean = false): FaceScan {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(if (fast) FaceDetectorOptions.PERFORMANCE_MODE_FAST else FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
        val det = FaceDetection.getClient(opts)
        return try {
            val faces = Tasks.await(det.process(InputImage.fromBitmap(b, 0)))
            var closed = 0
            for (f in faces) {
                val l = f.leftEyeOpenProbability; val r = f.rightEyeOpenProbability
                // chỉ tính khi mặt đủ lớn để đánh giá mắt
                if (l != null && r != null && f.boundingBox.width() > b.width * 0.06f && l < 0.3f && r < 0.3f) closed++
            }
            FaceScan(faces.map { toRegion(it) }, closed, faces.maxByOrNull { it.boundingBox.width() }?.boundingBox)
        } catch (e: Exception) {
            FaceScan(emptyList(), 0, null)
        } finally {
            det.close()
        }
    }

    private fun toRegion(f: Face): FaceRegion {
        val box = f.boundingBox
        val l = box.left.toFloat(); val t = box.top.toFloat()
        val w = box.width().toFloat(); val h = box.height().toFloat()
        val cx = l + w / 2f
        fun lm(type: Int): PointF? = f.getLandmark(type)?.position

        val eyeL = lm(FaceLandmark.LEFT_EYE) ?: PointF(l + w * 0.32f, t + h * 0.42f)
        val eyeR = lm(FaceLandmark.RIGHT_EYE) ?: PointF(l + w * 0.68f, t + h * 0.42f)
        val nose = lm(FaceLandmark.NOSE_BASE) ?: PointF(cx, t + h * 0.64f)
        val mL = lm(FaceLandmark.MOUTH_LEFT)
        val mR = lm(FaceLandmark.MOUTH_RIGHT)
        val mB = lm(FaceLandmark.MOUTH_BOTTOM)

        val ex = ArrayList<Ellipse>()
        for (e in listOf(eyeL, eyeR)) {
            ex += Ellipse(e.x, e.y, w * 0.15f, w * 0.09f)                // mắt
            ex += Ellipse(e.x, e.y - w * 0.13f, w * 0.17f, w * 0.06f)    // lông mày
        }
        if (mL != null && mR != null) {
            val mcx = (mL.x + mR.x) / 2f
            val mcy = if (mB != null) (mL.y + mR.y) / 2f * 0.6f + mB.y * 0.4f else (mL.y + mR.y) / 2f
            ex += Ellipse(mcx, mcy, abs(mR.x - mL.x) / 2f * 1.3f + 2f, w * 0.12f)
        } else {
            ex += Ellipse(cx, t + h * 0.8f, w * 0.22f, w * 0.12f)
        }
        ex += Ellipse(nose.x, nose.y, w * 0.12f, w * 0.06f)              // chân mũi
        return FaceRegion(l, t, l + w, t + h, ex)
    }

    /** Độ nét vùng mặt lớn nhất (hoặc giữa ảnh nếu không có mặt). */
    fun sharpness(b: Bitmap, face: Rect?): Double {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return if (face != null) {
            Analysis.sharpness(px, w, h, face.left.coerceIn(0, w - 1), face.top.coerceIn(0, h - 1),
                face.right.coerceIn(1, w), face.bottom.coerceIn(1, h))
        } else {
            Analysis.sharpness(px, w, h, w / 4, h / 4, w * 3 / 4, h * 3 / 4)
        }
    }
}
