package com.looknote.exif

import android.app.Activity
import android.content.res.Configuration
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

    private lateinit var image: ZoomView
    private var multiTouch = false
    private lateinit var infoBox: LinearLayout
    private lateinit var counter: TextView
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var hint: TextView
    private lateinit var gestures: GestureDetector

    private var index = 0
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)

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
        bar.addView(Film.iconButton(this, IconDrawable.Kind.BACK, null) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(40)))
        bar.addView(Film.iconButton(this, IconDrawable.Kind.PREV, null) { go(-1) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) })
        // bộ đếm khung kiểu máy phim
        counter = TextView(this).apply {
            textSize = 13f
            typeface = Film.mono
            letterSpacing = 0.06f
            setTextColor(Ui.accent)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        bar.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Film.iconButton(this, IconDrawable.Kind.NEXT, null) { go(1) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })
        bar.addView(Film.iconButton(this, IconDrawable.Kind.SHARE, null) { shareCurrent() },
            LinearLayout.LayoutParams(dp(44), dp(40)).apply { rightMargin = dp(6) })
        bar.addView(Ui.button(this, "Chỉnh", true) { openEditor() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(bar)

        // ----- Nội dung -----
        body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // chạm đúp hoặc hai ngón để phóng to ngay tại chỗ; vuốt ngang để sang ảnh khác
        image = ZoomView(this).apply {
            wrapAspect = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        infoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = TextView(this).apply {
            text = "Chạm đúp hoặc dùng hai ngón để phóng to · Vuốt trái / phải để xem ảnh khác"
            textSize = 12f
            setTextColor(Ui.mute)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        applyLayout()
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

    /** Cầm dọc: ảnh trên, thông số dưới (cuộn chung). Cầm ngang: ảnh trái, thông số phải. */
    private fun applyLayout() {
        for (v in listOf<View>(image, infoBox, hint)) (v.parent as? ViewGroup)?.removeView(v)
        body.removeAllViews()
        val sc = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            body.orientation = LinearLayout.HORIZONTAL
            image.wrapAspect = false
            body.addView(image, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f).apply {
                leftMargin = dp(12); bottomMargin = dp(12)
            })
            col.setPadding(dp(12), dp(4), dp(12), dp(24))
            col.addView(infoBox); col.addView(hint)
            sc.addView(col)
            body.addView(sc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            body.orientation = LinearLayout.VERTICAL
            image.wrapAspect = true
            col.setPadding(dp(12), dp(4), dp(12), dp(24))
            col.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            col.addView(infoBox); col.addView(hint)
            sc.addView(col)
            body.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        scroll = sc
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // chỉ chuyển ảnh khi vuốt một ngón và ảnh không đang phóng to
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        if (ev.pointerCount > 1) multiTouch = true
        if (::gestures.isInitialized && !multiTouch && !image.isZoomed) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openZoom() {
        val uri = Gallery.current.getOrNull(index) ?: return
        startActivity(android.content.Intent(this, ZoomActivity::class.java)
            .setData(uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun shareCurrent() {
        val uri = Gallery.current.getOrNull(index) ?: return
        Share.prompt(this, uri, Ui.displayName(this, uri))
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
        image.setImage(null)
        infoBox.removeAllViews()
        infoBox.addView(TextView(this).apply {
            text = "Đang đọc thông số..."
            setTextColor(Ui.mute)
            setPadding(dp(4), dp(12), 0, 0)
        })
        scroll.scrollTo(0, 0)

        Thread {
            val name = Ui.displayName(this, uri)
            var pw = 0; var ph = 0
            val info = try {
                val head = Ui.readHead(this, uri, 8 * 1024 * 1024)
                val orient = PhotoInfoBuilder.build(name, head).orientation
                val sz = Ui.pixelSize(this, uri, orient)
                pw = sz.first; ph = sz.second
                PhotoInfoBuilder.build(name, head, pw, ph, Ui.fileSize(this, uri))
            } catch (e: Exception) {
                PhotoInfo(name, emptyList(), "Lỗi đọc file: ${e.message}", 1)
            }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                infoBox.removeAllViews()
                infoBox.addView(Ui.infoCard(this, info))
            }
            val bmp = try { Ui.decodeMax(this, uri, 1600, info.orientation) } catch (e: Throwable) { null }
            runOnUiThread {
                if (token != loadToken) return@runOnUiThread
                if (bmp != null) {
                    val fw = if (pw > 0) pw else bmp.width
                    val fh = if (ph > 0) ph else bmp.height
                    image.setImage(bmp, fw, fh, uri, info.orientation)
                }
            }
            // trợ lý chụp: mắt nhắm, độ nét, cháy sáng... rồi đưa lên đầu thẻ thông số
            if (bmp != null && info.sections.isNotEmpty()) {
                val scan = try { FaceFinder.analyze(bmp) } catch (e: Throwable) { null }
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val hist = Analysis.histogram(px)
                val sharp = scan?.largest?.let { FaceFinder.sharpness(bmp, it) }
                val shot = info.shot.copy(
                    faces = scan?.regions?.size ?: 0,
                    eyesClosed = scan?.eyesClosed ?: 0,
                    sharpness = sharp,
                    clipHigh = hist.clipHigh, clipLow = hist.clipLow
                )
                val tips = Assistant.tips(shot)
                val withTips = info.copy(sections = info.sections + Section("Trợ lý chụp", tips))
                runOnUiThread {
                    if (token != loadToken) return@runOnUiThread
                    infoBox.removeAllViews()
                    infoBox.addView(Ui.infoCard(this, withTips))
                }
            }
        }.start()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
