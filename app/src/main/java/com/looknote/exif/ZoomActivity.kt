package com.looknote.exif

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Xem ảnh toàn màn hình, phóng to tới 100% (và hơn) để soi độ nét. */
class ZoomActivity : Activity() {

    private lateinit var zoom: ZoomView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        val uri: Uri = intent.data ?: run { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        zoom = ZoomView(this)
        root.addView(zoom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val close = Ui.button(this, "Đóng", false) { finish() }
        root.addView(close, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP or Gravity.START
        ).apply { setMargins(dp(12), dp(12), 0, 0) })

        val hint = TextView(this).apply {
            text = "Chụm hai ngón để phóng to · chạm đúp để xem chi tiết"
            textSize = 12.5f
            setTextColor(Color.parseColor("#DDFFFFFF"))
            background = Ui.rounded(this@ZoomActivity, Color.parseColor("#88000000"), 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(24) })
        setContentView(root)

        Thread {
            val head = try { Ui.readHead(this, uri, 512 * 1024) } catch (e: Exception) { ByteArray(0) }
            val orient = PhotoInfoBuilder.build("", head).orientation
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } } catch (e: Exception) { }
            val rotated = orient == 6 || orient == 8
            val fw = if (rotated) o.outHeight else o.outWidth
            val fh = if (rotated) o.outWidth else o.outHeight
            val bmp = try { Ui.decodeMax(this, uri, 2048, orient) } catch (e: Throwable) { null }
            runOnUiThread {
                if (bmp == null || fw <= 0) {
                    hint.text = "Không mở được ảnh này"
                } else {
                    zoom.setImage(bmp, fw, fh, uri, orient)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::zoom.isInitialized) zoom.release()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
