package com.looknote.exif

import android.app.Activity
import android.content.res.Configuration
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Màn chỉnh ảnh: làm mịn da, xóa mụn, áp một trong 7 công thức màu, phóng to để soi,
 * rồi lưu thành ảnh mới (ảnh gốc giữ nguyên).
 */
class EditActivity : Activity() {

    private lateinit var uri: Uri
    private lateinit var zoom: ZoomView
    private lateinit var body: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var summary: TextView
    private lateinit var faceInfo: TextView
    private lateinit var strengthLabel: TextView
    private lateinit var evLabel: TextView
    private lateinit var smoothLabel: TextView
    private lateinit var evBar: SeekBar
    private lateinit var saveBtn: Button
    private lateinit var compareBtn: Button
    private val chips = ArrayList<Button>()

    private var base: Bitmap? = null
    private var processed: Bitmap? = null
    private var orientation = 1
    private var faces: List<FaceRegion>? = null
    private var selected = -1          // -1 = không áp màu
    private var strength = 100         // %
    private var ev = 0.0
    private var smooth = 0             // % làm mịn da
    private var blemish = false
    private var token = 0
    private var saving = false
    private var srcPreset: Preset? = null   // công thức lúc chụp (đọc từ EXIF)
    private var invert = false               // gỡ màu lúc chụp trước khi áp màu mới
    private lateinit var invertBox: CheckBox
    private lateinit var srcInfo: TextView
    private var deltaKey = ""
    private var delta: SkinRetouch.Delta? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val u = intent.data
        if (u == null) { finish(); return }
        uri = u

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
        bar.addView(Ui.button(this, "‹ Quay lại", false) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        bar.addView(TextView(this).apply {
            text = "Chỉnh ảnh"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.ink)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveBtn = Ui.button(this, "Lưu ảnh", true) { save() }
        bar.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(bar)

        // ----- Ảnh xem trước: chụm để phóng to -----
        body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        zoom = ZoomView(this).apply { setBackgroundColor(Color.parseColor("#EDE8E0")) }

        // ----- Bảng điều khiển (cuộn được) -----
        panelScroll = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }
        panelScroll.addView(panel)

        compareBtn = Ui.button(this, "Giữ để xem ảnh gốc", false) { }
        compareBtn.setOnTouchListener { v, me ->
            when (me.action) {
                MotionEvent.ACTION_DOWN -> base?.let { zoom.swapBitmap(it) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoom.swapBitmap(processed ?: base); v.performClick()
                }
            }
            true
        }
        panel.addView(compareBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

        // --- Da ---
        panel.addView(sectionTitle("Da"))
        faceInfo = TextView(this).apply {
            textSize = 12f; setTextColor(Ui.mute); text = "Đang tìm khuôn mặt..."
        }
        panel.addView(faceInfo)
        val skinRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        skinRow.addView(CheckBox(this).apply {
            text = "Xóa mụn"
            setTextColor(Ui.ink)
            setOnCheckedChangeListener { _, on -> blemish = on; render() }
        })
        smoothLabel = label().apply { setPadding(dp(16), 0, 0, 0) }
        skinRow.addView(smoothLabel)
        panel.addView(skinRow)
        panel.addView(SeekBar(this).apply {
            max = 100; progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    smooth = p; updateLabels()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { render() }
            })
        })

        // --- Màu ---
        panel.addView(sectionTitle("Màu"))
        invertBox = CheckBox(this).apply {
            text = "Đổi công thức (gỡ màu lúc chụp)"
            setTextColor(Ui.ink)
            isEnabled = false
            setOnCheckedChangeListener { _, on -> invert = on; render() }
        }
        panel.addView(invertBox)
        srcInfo = TextView(this).apply {
            textSize = 12f; setTextColor(Ui.mute); text = "Đang đọc thông số lúc chụp..."
            setPadding(dp(2), 0, 0, dp(6))
        }
        panel.addView(srcInfo)
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("Không") + Presets.all.map { "${it.slot} · ${it.name}" }
        labels.forEachIndexed { i, text ->
            val b = Ui.button(this, text, false) { select(i - 1) }
            chips += b
            chipRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        }
        hs.addView(chipRow)
        panel.addView(hs)

        summary = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.mute)
            setPadding(dp(2), dp(6), 0, 0)
        }
        panel.addView(summary)

        strengthLabel = label()
        panel.addView(strengthLabel)
        panel.addView(SeekBar(this).apply {
            max = 100; progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    strength = p; updateLabels()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { render() }
            })
        })

        evLabel = label()
        panel.addView(evLabel)
        evBar = SeekBar(this).apply {
            max = 20; progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    ev = (p - 10) / 10.0; updateLabels()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { render() }
            })
        }
        panel.addView(evBar)

        panel.addView(TextView(this).apply {
            text = "Chụm hai ngón trên ảnh để phóng to, chạm đúp để xem chi tiết. " +
                "Ảnh mới lưu vào Pictures/LookExif, ảnh gốc giữ nguyên. " +
                "Màu đẹp nhất với ảnh chụp ở Creative Look ST."
            textSize = 11.5f
            setTextColor(Ui.mute)
            setPadding(dp(2), dp(8), 0, 0)
        })
        applyLayout()
        setContentView(root)

        updateLabels()
        styleChips()
        loadBase()
    }

    /** Cầm dọc: ảnh trên, bảng điều khiển dưới. Cầm ngang: ảnh trái, bảng điều khiển phải. */
    private fun applyLayout() {
        body.removeAllViews()
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            body.orientation = LinearLayout.HORIZONTAL
            body.addView(zoom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(8); bottomMargin = dp(8)
            })
            val wide = resources.configuration.screenWidthDp >= 900
            body.addView(panelScroll, LinearLayout.LayoutParams(dp(if (wide) 420 else 340), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            body.orientation = LinearLayout.VERTICAL
            body.addView(zoom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = dp(8); rightMargin = dp(8)
            })
            val tall = resources.configuration.screenHeightDp >= 900
            body.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (tall) 380 else 300)))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        if (::zoom.isInitialized) zoom.release()
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase()
        textSize = 11.5f
        letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ui.accent)
        setPadding(dp(2), dp(14), 0, dp(2))
    }

    private fun label() = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.ink)
        setPadding(dp(2), dp(8), 0, 0)
    }

    private fun updateLabels() {
        strengthLabel.text = "Độ mạnh màu: $strength%"
        val e = if (ev > 0) "+" + PhotoInfoBuilder.fmt(ev, 1) else PhotoInfoBuilder.fmt(ev, 1)
        evLabel.text = "Độ sáng: $e EV"
        smoothLabel.text = "Mịn da: $smooth%"
        summary.text = if (selected < 0) "Chưa áp màu mới. Chọn một công thức ở trên."
        else Presets.all[selected].summary()
    }

    private fun styleChips() {
        chips.forEachIndexed { i, b ->
            val on = (i - 1) == selected
            b.background = Ui.rounded(this, if (on) Ui.accent else Color.WHITE, 12, if (on) 0 else Ui.line)
            b.setTextColor(if (on) Color.WHITE else Ui.ink)
        }
    }

    private fun select(i: Int) {
        selected = i
        ev = if (i >= 0) Presets.all[i].ev else 0.0
        evBar.progress = (ev * 10 + 10).toInt().coerceIn(0, 20)
        updateLabels()
        styleChips()
        render()
    }

    private val skinOn get() = smooth > 0 || blemish
    private val invertOn get() = invert && srcPreset != null
    private val anyEdit get() = skinOn || selected >= 0 || invertOn

    private fun loadBase() {
        summary.text = "Đang mở ảnh..."
        worker.execute {
            val head = try { Ui.readHead(this, uri, 512 * 1024) } catch (e: Exception) { ByteArray(0) }
            orientation = PhotoInfoBuilder.build("", head).orientation
            val src = PhotoInfoBuilder.sourcePreset(head)
            runOnUiThread {
                when {
                    src == null -> srcInfo.text = "Ảnh không có thông số Creative Look của Sony: màu mới sẽ áp chồng lên màu sẵn có."
                    src.look == "BW" || src.look == "SE" ->
                        srcInfo.text = "Ảnh chụp đen trắng/sepia: không gỡ lại được màu."
                    else -> {
                        srcPreset = src
                        srcInfo.text = "Lúc chụp: " + src.summary() +
                            ". Bật ô trên để gỡ màu này rồi mới áp công thức mới (vùng đã cháy sáng hoặc bị dìm đen không lấy lại được)."
                        invertBox.isEnabled = true
                        invertBox.isChecked = true
                    }
                }
            }
            val b = try { Ui.decodeMax(this, uri, 1800, orientation) } catch (e: Throwable) { null }
            runOnUiThread {
                if (b == null) {
                    summary.text = "Không mở được ảnh này (định dạng RAW chưa hỗ trợ chỉnh)."
                    saveBtn.isEnabled = false
                } else {
                    base = b
                    zoom.setImage(b)
                    updateLabels()
                    if (anyEdit) render()
                }
            }
            if (b != null) {
                val f = try { FaceFinder.detect(b) } catch (e: Throwable) { emptyList() }
                faces = f
                runOnUiThread {
                    faceInfo.text = if (f.isEmpty())
                        "Không thấy khuôn mặt: chỉ làm mịn nhẹ theo màu da, không xóa mụn."
                    else "Tìm thấy ${f.size} khuôn mặt. Mắt, lông mày, môi được giữ nguyên nét."
                    if (skinOn) render()
                }
            }
        }
    }

    /** Độ lệch làm da (tính một lần cho mỗi mức cài đặt, dùng lại khi đổi màu). */
    private fun skinDelta(b: Bitmap, px: IntArray): SkinRetouch.Delta? {
        if (!skinOn) return null
        val f = faces ?: return null
        val key = "$smooth-$blemish-${f.size}"
        if (key == deltaKey && delta != null) return delta
        val d = SkinRetouch.compute(px, b.width, b.height, f, smooth / 100f, blemish)
        delta = d; deltaKey = key
        return d
    }

    private fun render() {
        val b = base ?: return
        if (!anyEdit) { processed = null; zoom.swapBitmap(b); return }
        val my = ++token
        val inv = if (invertOn) srcPreset else null
        val p = if (selected >= 0) Presets.all[selected] else null
        val st = strength / 100f
        val e = ev
        worker.execute {
            if (my != token) return@execute
            val w = b.width; val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val d = skinDelta(b, px.copyOf())
            if (d != null) SkinRetouch.apply(d, px, w, h, 0, h)
            if (inv != null) LookInverter(inv).process(px)
            if (p != null) ColorEngine(p, e, st).process(px)
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            runOnUiThread {
                if (my != token) return@runOnUiThread
                processed = out
                zoom.swapBitmap(out)
            }
        }
    }

    private fun save() {
        if (!anyEdit) { Ui.toast(this, "Chưa có chỉnh sửa nào"); return }
        if (saving) return
        val b = base ?: return
        saving = true
        saveBtn.text = "Đang lưu..."
        val p = if (selected >= 0) Presets.all[selected] else null
        val st = strength / 100f
        val e = ev
        val inv = if (invertOn) srcPreset else null
        worker.execute {
            val msg = try {
                // độ lệch làm da tính trên ảnh xem trước, phóng to áp lên ảnh lớn
                val bpx = IntArray(b.width * b.height)
                b.getPixels(bpx, 0, b.width, 0, 0, b.width, b.height)
                val d = skinDelta(b, bpx)

                val head = Ui.readHead(this, uri, 512 * 1024)
                val full = Ui.decodeMax(this, uri, 4000, orientation) ?: throw IllegalStateException("không giải mã được ảnh")
                val work = if (full.isMutable) full else full.copy(Bitmap.Config.ARGB_8888, true)
                val w = work.width; val h = work.height
                val rows = 256
                val buf = IntArray(w * rows)
                val engine = p?.let { ColorEngine(it, e, st) }
                val inverter = inv?.let { LookInverter(it) }
                var y = 0
                while (y < h) {
                    val hh = minOf(rows, h - y)
                    work.getPixels(buf, 0, w, 0, y, w, hh)
                    if (d != null) SkinRetouch.apply(d, buf, w, h, y, hh)
                    inverter?.process(buf, 0, w * hh)
                    engine?.process(buf, 0, w * hh)
                    work.setPixels(buf, 0, w, 0, y, w, hh)
                    y += hh
                }
                val bos = ByteArrayOutputStream()
                work.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                var jpg = bos.toByteArray()
                ExifParser.exifSegmentForEdit(head)?.let { jpg = ExifParser.insertExif(jpg, it) }

                val baseName = Ui.displayName(this, uri).substringBeforeLast('.')
                val tag = listOfNotNull(if (inv != null) "doi" else null, p?.slot, if (d != null) "da" else null).joinToString("-")
                val outName = "${baseName}_LX-$tag.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LookExif")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("không tạo được file")
                contentResolver.openOutputStream(out)?.use { it.write(jpg) }
                    ?: throw IllegalStateException("không ghi được file")
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(out, values, null, null)
                }
                work.recycle()
                "Đã lưu: Pictures/LookExif/$outName"
            } catch (t: Throwable) {
                "Lỗi khi lưu: ${t.message}"
            }
            runOnUiThread {
                saving = false
                saveBtn.text = "Lưu ảnh"
                Ui.toast(this, msg)
            }
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
