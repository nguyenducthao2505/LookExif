package com.looknote.exif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Khung xem ảnh phóng to được: chụm hai ngón, kéo, chạm đúp (vừa khung ↔ 100%).
 * Nếu có [sourceUri], khi phóng to sẽ đọc thêm vùng đang xem từ file gốc ở độ phân giải cao.
 */
class ZoomView(c: Context) : View(c) {

    private var base: Bitmap? = null
    private var fullW = 0f      // kích thước ảnh gốc sau khi xoay
    private var fullH = 0f
    private var orientation = 1
    private var sourceUri: Uri? = null

    private var scale = 1f      // điểm màn hình / điểm ảnh gốc
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f

    private var tile: Bitmap? = null
    private var tileRect = RectF()   // vị trí ô chi tiết theo toạ độ ảnh gốc
    private var tileToken = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var decoder: BitmapRegionDecoder? = null

    /** Trong khung cuộn: chiều cao theo tỉ lệ ảnh (tối đa 1,6 lần bề rộng). */
    var wrapAspect = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (wrapAspect && fullW > 0f) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (w * fullH / fullW).toInt().coerceAtMost((w * 1.6f).toInt())
            setMeasuredDimension(w, h)
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Gọi khi người dùng chạm (dùng cho so sánh ảnh gốc); trả về true nếu đang giữ. */
    var onHoldChange: ((Boolean) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx; ty -= dy; clamp(); invalidate(); scheduleTile()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale > fitScale * 1.05f) fitScale else max(1f, fitScale * 2.5f)
            zoomAt(target, e.x, e.y)
            return true
        }
    })

    /** Đặt ảnh. [fw],[fh]: kích thước ảnh gốc (đã xoay). */
    fun setImage(bmp: Bitmap?, fw: Int = bmp?.width ?: 0, fh: Int = bmp?.height ?: 0,
                 uri: Uri? = null, orient: Int = 1, keepView: Boolean = false) {
        base = bmp
        val changedSize = fw.toFloat() != fullW || fh.toFloat() != fullH
        fullW = fw.toFloat(); fullH = fh.toFloat()
        orientation = orient
        if (uri != sourceUri) {
            sourceUri = uri
            decoder?.recycle(); decoder = null
            tile = null
        }
        if (!keepView || changedSize) fit()
        if (changedSize && wrapAspect) requestLayout()
        invalidate()
        scheduleTile()
    }

    /** Chỉ thay ảnh hiển thị (giữ nguyên vị trí phóng). */
    fun swapBitmap(bmp: Bitmap?) { base = bmp; invalidate() }

    fun release() {
        exec.shutdownNow()
        decoder?.recycle(); decoder = null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    private fun fit() {
        if (fullW <= 0 || width == 0) return
        fitScale = min(width / fullW, height / fullH)
        scale = fitScale
        tx = (width - fullW * scale) / 2f
        ty = (height - fullH * scale) / 2f
        tile = null
    }

    private fun zoomAt(newScale: Float, fx: Float, fy: Float) {
        val s = newScale.coerceIn(fitScale, max(fitScale, 3f))
        val ix = (fx - tx) / scale
        val iy = (fy - ty) / scale
        scale = s
        tx = fx - ix * scale
        ty = fy - iy * scale
        clamp(); invalidate(); scheduleTile()
    }

    private fun clamp() {
        val iw = fullW * scale; val ih = fullH * scale
        tx = if (iw <= width) (width - iw) / 2f else tx.coerceIn(width - iw, 0f)
        ty = if (ih <= height) (height - ih) / 2f else ty.coerceIn(height - ih, 0f)
    }

    val isZoomed get() = scale > fitScale * 1.02f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onHoldChange?.invoke(true)
            MotionEvent.ACTION_POINTER_DOWN -> {
                onHoldChange?.invoke(false)
                // hai ngón: không để khung cuộn cướp thao tác phóng to
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onHoldChange?.invoke(false)
        }
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (isZoomed) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = base ?: return
        val m = Matrix()
        m.postScale(fullW * scale / b.width, fullH * scale / b.height)
        m.postTranslate(tx, ty)
        canvas.drawBitmap(b, m, paint)
        val t = tile
        if (t != null) {
            val dst = RectF(
                tx + tileRect.left * scale, ty + tileRect.top * scale,
                tx + tileRect.right * scale, ty + tileRect.bottom * scale
            )
            canvas.drawBitmap(t, null, dst, paint)
        }
    }

    // ---------- đọc vùng chi tiết từ file gốc ----------
    private val tileRunnable = Runnable { loadTile() }

    private fun scheduleTile() {
        main.removeCallbacks(tileRunnable)
        if (sourceUri == null) return
        main.postDelayed(tileRunnable, 250)
    }

    private fun loadTile() {
        val uri = sourceUri ?: return
        val b = base ?: return
        // ảnh nền đã đủ nét thì không cần đọc thêm
        if (fullW * scale / b.width < 1.2f) { tile = null; invalidate(); return }
        val vx0 = max(0f, -tx / scale); val vy0 = max(0f, -ty / scale)
        val vx1 = min(fullW, (width - tx) / scale); val vy1 = min(fullH, (height - ty) / scale)
        if (vx1 <= vx0 || vy1 <= vy0) return
        var sample = 1
        while (sample * 2 <= 1f / scale) sample *= 2
        val my = ++tileToken
        val area = RectF(vx0, vy0, vx1, vy1)
        val ctx = context
        exec.execute {
            try {
                if (decoder == null) {
                    decoder = ctx.contentResolver.openInputStream(uri)?.use { s -> newDecoder(s) }
                }
                val dec = decoder ?: return@execute
                val rawW = dec.width; val rawH = dec.height
                val raw = toRaw(area, rawW, rawH)
                if (raw.width() <= 0 || raw.height() <= 0) return@execute
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                var bmp = dec.decodeRegion(raw, opts) ?: return@execute
                val deg = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
                if (deg != 0f) {
                    val m = Matrix().apply { postRotate(deg) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                main.post {
                    if (my == tileToken) { tile = bmp; tileRect = area; invalidate() }
                }
            } catch (e: Throwable) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun newDecoder(s: java.io.InputStream): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(s)
        else BitmapRegionDecoder.newInstance(s, false)

    /** Đổi vùng theo toạ độ đã xoay sang toạ độ thô trong file. */
    private fun toRaw(r: RectF, rawW: Int, rawH: Int): Rect {
        val out = when (orientation) {
            6 -> RectF(r.top, rawH - r.right, r.bottom, rawH - r.left)
            3 -> RectF(rawW - r.right, rawH - r.bottom, rawW - r.left, rawH - r.top)
            8 -> RectF(rawW - r.bottom, r.left, rawW - r.top, r.right)
            else -> RectF(r)
        }
        return Rect(
            out.left.toInt().coerceIn(0, rawW), out.top.toInt().coerceIn(0, rawH),
            out.right.toInt().coerceIn(0, rawW), out.bottom.toInt().coerceIn(0, rawH)
        )
    }
}
