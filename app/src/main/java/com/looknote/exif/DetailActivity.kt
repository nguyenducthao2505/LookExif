package com.looknote.exif

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Màn chi tiết: ảnh lớn kèm toàn bộ thông số, vuốt trái/phải để sang ảnh khác. */
class DetailActivity : Activity() {

    companion object { const val EXTRA_INDEX = "index" }

    private lateinit var image: ImageView
    private lateinit var infoBox: LinearLayout
    private lateinit var counter: TextView
    private lateinit var scroll: ScrollView
    private lateinit var gestures: GestureDetector

    private var index = 0
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (Gallery.current.isEmpty()) { finish(); return }
        index = index.coerceIn(0, Gallery.current.size - 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
        }

        // ----- Thanh trên -----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        bar.addView(Ui.button(this, "Lưới", false) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        counter = TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.mute)
            gravity = Gravity.CENTER
        }
        bar.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.button(this, "Chỉnh màu", true) { openEditor() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        bar.addView(Ui.button(this, "‹", false) { go(-1) },
            LinearLayout.LayoutParams(dp(44), dp(40)).apply { rightMargin = dp(6) })
        bar.addView(Ui.button(this, "›", false) { go(1) },
            LinearLayout.LayoutParams(dp(44), dp(40)))
        root.addView(bar)

        // ----- Nội dung -----
        scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openZoom() }
        }
        content.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
        infoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(infoBox)
        content.addView(TextView(this).apply {
            text = "Chạm vào ảnh để phóng to · Vuốt trái / phải để xem ảnh khác"
            textSize = 12f
            setTextColor(Ui.mute)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(80) && abs(dx) > abs(dy) * 1.5f && abs(velocityX) > 600) {
                    go(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })

        show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openZoom() {
        val uri = Gallery.current.getOrNull(index) ?: return
        startActivity(android.content.Intent(this, ZoomActivity::class.java)
            .setData(uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun openEditor() {
        val uri = Gallery.current.getOrNull(index) ?: return
        startActivity(android.content.Intent(this, EditActivity::class.java)
            .setData(uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun go(step: Int) {
        val n = index + step
        if (n < 0 || n >= Gallery.current.size) {
            Ui.toast(this, if (n < 0) "Đây là ảnh đầu tiên" else "Đây là ảnh cuối cùng")
            return
        }
        index = n
        show()
    }

    private fun show() {
        val list = Gallery.current
        val uri = list[index]
        val token = ++loadToken
        counter.text = "${index + 1} / ${list.size}"
        image.setImageBitmap(null)
        infoBox.removeAllViews()
        infoBox.addView(TextView(this).apply {
            text = "Đang đọc thông số..."
            setTextColor(Ui.mute)
            setPadding(dp(4), dp(12), 0, 0)
        })
        scroll.scrollTo(0, 0)

        Thread {
            val name = Ui.displayName(this, uri)
            val info = try {
                val head = Ui.readHead(this, uri, 8 * 1024 * 1024)
                val orient = PhotoInfoBuilder.build(name, head).orientation
                val (pw, ph) = Ui.pixelSize(this, uri, orient)
                PhotoInfoBuilder.build(name, head, pw, ph, Ui.fileSize(this, uri))
            } catch (e: Exception) {
                PhotoInfo(name, emptyList(), "Lỗi đọc file: ${e.message}", 1)
            }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                infoBox.removeAllViews()
                infoBox.addView(Ui.infoCard(this, info))
            }
            val bmp = try { Ui.decode(this, uri, 1600, info.orientation) } catch (e: Throwable) { null }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                image.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
