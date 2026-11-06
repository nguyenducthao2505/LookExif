package com.looknote.exif

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/** Nhận diện khuôn mặt bằng ML Kit (chạy offline) và dựng vùng loại trừ mắt, mày, môi, mũi. */
object FaceFinder {

    fun detect(b: Bitmap): List<FaceRegion> {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
        val det = FaceDetection.getClient(opts)
        return try {
            val faces = Tasks.await(det.process(InputImage.fromBitmap(b, 0)))
            faces.map { toRegion(it) }
        } catch (e: Exception) {
            emptyList()
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
}
