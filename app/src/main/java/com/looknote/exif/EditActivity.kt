package com.looknote.exif

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Màn chỉnh ảnh: xóa viền tím, làm mịn da, xóa mụn, đổi công thức màu,
 * so sánh chia đôi, histogram, vùng cháy; lưu thành ảnh mới và chia sẻ.
 */
class EditActivity : Activity() {

    private lateinit var uri: Uri
    private lateinit var zoom: ZoomView
    private lateinit var body: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var histView: HistogramView
    private lateinit var summary: TextView
    private lateinit var faceInfo: TextView
    private lateinit var srcInfo: TextView
    private lateinit var invertBox: CheckBox
    private lateinit var strengthLabel: TextView
    private lateinit var evLabel: TextView
    private lateinit var smoothLabel: TextView
    private lateinit var fringeLabel: TextView
    private lateinit var evBar: SeekBar
    private lateinit var saveBtn: Button
    private lateinit var compareBtn: Button
    private lateinit var clipBtn: Button
    private val chips = ArrayList<Button>()

    private var base: Bitmap? = null
    private var processed: Bitmap? = null
    private var orientation = 1
    private var faces: List<FaceRegion>? = null
    private var srcPreset: Preset? = null
    private var selected = -1          // -1 = không áp màu mới
    private var strength = 100
    private var ev = 0.0
    private var smooth = 0
    private var blemish = false
    private var invert = false
    private var defringe = 0
    private var compareOn = false
    private var clipOn = false
    private var token = 0
    private var saving = false
    private var deltaKey = ""
    private var delta: SkinRetouch.Delta? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        PresetStore.load(this)
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

        body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        zoom = ZoomView(this).apply { setBackgroundColor(Color.parseColor("#0A0A0A")) }

        // ----- Bảng điều khiển -----
        panelScroll = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(16))
        }
        panelScroll.addView(panel)

        // công cụ xem
        val viewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        compareBtn = Ui.button(this, "So sánh: Tắt", false) { toggleCompare() }
        clipBtn = Ui.button(this, "Vùng cháy: Tắt", false) { toggleClip() }
        viewRow.addView(compareBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        viewRow.addView(clipBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
        panel.addView(viewRow)
        histView = HistogramView(this)
        panel.addView(histView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)).apply { topMargin = dp(8) })

        // --- Ống kính ---
        panel.addView(sectionTitle("Ống kính"))
        fringeLabel = label()
        panel.addView(fringeLabel)
        panel.addView(slider(100, 0, onChange = { defringe = it; updateLabels() }))

        // --- Da ---
        panel.addView(sectionTitle("Da"))
        faceInfo = small("Đang tìm khuôn mặt...")
        panel.addView(faceInfo)
        val skinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        skinRow.addView(CheckBox(this).apply {
            text = "Xóa mụn"; setTextColor(Ui.ink)
            setOnCheckedChangeListener { _, on -> blemish = on; render() }
        })
        smoothLabel = label().apply { setPadding(dp(16), 0, 0, 0) }
        skinRow.addView(smoothLabel)
        panel.addView(skinRow)
        panel.addView(slider(100, 0, onChange = { smooth = it; updateLabels() }))

        // --- Màu ---
        panel.addView(sectionTitle("Màu"))
        invertBox = CheckBox(this).apply {
            text = "Đổi công thức (gỡ màu lúc chụp)"; setTextColor(Ui.ink); isEnabled = false
            setOnCheckedChangeListener { _, on -> invert = on; render() }
        }
        panel.addView(invertBox)
        srcInfo = small("Đang đọc thông số lúc chụp...")
        panel.addView(srcInfo)
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("Không") + Presets.all.map { "${it.slot} · ${it.name}".trimStart(' ', '·') }
        labels.forEachIndexed { i, text ->
            val b = Ui.button(this, text, false) { select(i - 1) }
            chips += b
            chipRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        }
        hs.addView(chipRow)
        panel.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        summary = small("")
        panel.addView(summary)
        strengthLabel = label(); panel.addView(strengthLabel)
        panel.addView(slider(100, 100, onChange = { strength = it; updateLabels() }))
        evLabel = label(); panel.addView(evLabel)
        evBar = slider(20, 10, onChange = { ev = (it - 10) / 10.0; updateLabels() })
        panel.addView(evBar)

        panel.addView(small("Lưu: " + (if (AppSettings.fullRes(this)) "đủ độ phân giải" else "khoảng 8MP") +
            ", JPEG ${AppSettings.quality(this)}. Đổi trong Cài đặt. Ảnh gốc luôn giữ nguyên.").apply { setPadding(dp(2), dp(12), 0, 0) })

        applyLayout()
        setContentView(root)
        updateLabels()
        styleChips()
        loadBase()
    }

    private fun slider(max: Int, start: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { render() }
        })
    }

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
            body.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (tall) 400 else 310)))
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
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.accent)
        setPadding(dp(2), dp(16), 0, dp(2))
    }

    private fun label() = TextView(this).apply {
        textSize = 13f; setTextColor(Ui.ink); setPadding(dp(2), dp(8), 0, 0)
    }

    private fun small(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Ui.mute); setPadding(dp(2), dp(2), 0, dp(4))
    }

    private fun updateLabels() {
        strengthLabel.text = "Độ mạnh màu: $strength%"
        val e = if (ev > 0) "+" + PhotoInfoBuilder.fmt(ev, 1) else PhotoInfoBuilder.fmt(ev, 1)
        evLabel.text = "Độ sáng: $e EV"
        smoothLabel.text = "Mịn da: $smooth%"
        fringeLabel.text = "Xóa viền tím / xanh lá: $defringe%"
        summary.text = if (selected < 0) "Chưa áp màu mới. Chọn một công thức ở trên."
        else Presets.all[selected].summary()
    }

    private fun styleChips() {
        chips.forEachIndexed { i, b ->
            val on = (i - 1) == selected
            b.background = Ui.rounded(this, if (on) Ui.accent else Ui.field, 12, if (on) 0 else Ui.line)
            b.setTextColor(if (on) Color.WHITE else Ui.ink)
        }
    }

    private fun select(i: Int) {
        selected = i
        ev = if (i >= 0) Presets.all[i].ev else 0.0
        evBar.progress = (ev * 10 + 10).toInt().coerceIn(0, 20)
        updateLabels(); styleChips(); render()
    }

    private fun toggleCompare() {
        compareOn = !compareOn
        compareBtn.text = if (compareOn) "So sánh: Bật" else "So sánh: Tắt"
        zoom.setCompare(if (compareOn && processed != null) base else null)
    }

    private fun toggleClip() {
        clipOn = !clipOn
        clipBtn.text = if (clipOn) "Vùng cháy: Bật" else "Vùng cháy: Tắt"
        refreshClip(processed ?: base)
    }

    private fun refreshClip(b: Bitmap?) {
        if (b == null) return
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        histView.setData(Analysis.histogram(px))
        zoom.setOverlay(if (clipOn) Bitmap.createBitmap(Analysis.clipMask(px), w, h, Bitmap.Config.ARGB_8888) else null)
    }

    private val skinOn get() = smooth > 0 || blemish
    private val invertOn get() = invert && srcPreset != null
    private val anyEdit get() = skinOn || selected >= 0 || invertOn || defringe > 0

    private fun loadBase() {
        summary.text = "Đang mở ảnh..."
        worker.execute {
            val head = try { Ui.readHead(this, uri, 512 * 1024) } catch (e: Exception) { ByteArray(0) }
            orientation = PhotoInfoBuilder.build("", head).orientation
            val src = PhotoInfoBuilder.sourcePreset(head)
            val b = try { Ui.decodeMax(this, uri, 1800, orientation) } catch (e: Throwable) { null }
            runOnUiThread {
                when {
                    src == null -> srcInfo.text = "Ảnh không có thông số Creative Look của Sony: màu mới sẽ áp chồng lên màu sẵn có."
                    src.look == "BW" || src.look == "SE" -> srcInfo.text = "Ảnh chụp đen trắng/sepia: không gỡ lại được màu."
                    else -> {
                        srcPreset = src
                        srcInfo.text = "Lúc chụp: " + src.summary() + (if (Presets.match(src) != null) " (dùng màu đã hiệu chỉnh theo máy)" else "")
                        invertBox.isEnabled = true
                        invertBox.isChecked = true
                    }
                }
                if (b == null) {
                    summary.text = "Không mở được ảnh này (định dạng RAW chưa hỗ trợ chỉnh)."
                    saveBtn.isEnabled = false
                } else {
                    base = b
                    zoom.setImage(b)
                    updateLabels()
                    refreshClip(b)
                    if (anyEdit) render()
                }
            }
            if (b != null) {
                val f = try { FaceFinder.detect(b) } catch (e: Throwable) { emptyList() }
                faces = f
                runOnUiThread {
                    faceInfo.text = if (f.isEmpty()) "Không thấy khuôn mặt: chỉ làm mịn nhẹ theo màu da, không xóa mụn."
                    else "Tìm thấy ${f.size} khuôn mặt. Mắt, lông mày, môi và sợi tóc được giữ nguyên nét."
                    if (skinOn) render()
                }
            }
        }
    }

    private fun skinDelta(b: Bitmap, px: IntArray): SkinRetouch.Delta? {
        if (!skinOn) return null
        val f = faces ?: return null
        val key = "$smooth-$blemish-${f.size}"
        if (key == deltaKey && delta != null) return delta
        val d = SkinRetouch.compute(px, b.width, b.height, f, smooth / 100f, blemish)
        delta = d; deltaKey = key
        return d
    }

    private fun options() = EditOptions(
        preset = if (selected >= 0) Presets.all[selected] else null,
        strength = strength / 100f, ev = ev, invert = invertOn,
        smooth = smooth, blemish = blemish, defringe = defringe
    )

    private fun render() {
        val b = base ?: return
        if (!anyEdit) {
            processed = null; zoom.swapBitmap(b); zoom.setCompare(null); refreshClip(b); return
        }
        val my = ++token
        val o = options()
        val inv = if (invertOn) srcPreset else null
        worker.execute {
            if (my != token) return@execute
            val w = b.width; val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            if (o.defringe > 0) Defringe.process(px, w, 0, h, 0, Defringe.radiusFor(w), o.defringe / 100f, true)
            val d = skinDelta(b, px.copyOf())
            if (d != null) SkinRetouch.apply(d, px, w, h, 0, h)
            if (inv != null) LookInverter(inv).process(px)
            o.preset?.let { ColorEngine(it, o.ev, o.strength).process(px) }
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            runOnUiThread {
                if (my != token) return@runOnUiThread
                processed = out
                zoom.swapBitmap(out)
                if (compareOn) zoom.setCompare(b)
                refreshClip(out)
            }
        }
    }

    private fun save() {
        if (!anyEdit) { Ui.toast(this, "Chưa có chỉnh sửa nào"); return }
        if (saving) return
        val b = base ?: return
        saving = true
        saveBtn.text = "Đang lưu..."
        val o = options()
        worker.execute {
            var saved: Processor.Saved? = null
            val msg = try {
                val bpx = IntArray(b.width * b.height)
                b.getPixels(bpx, 0, b.width, 0, 0, b.width, b.height)
                val d = skinDelta(b, bpx)
                saved = Processor.processAndSave(this, uri, o, d)
                "Đã lưu: Pictures/LookExif/${saved!!.name}"
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) "Không đủ bộ nhớ để lưu đủ độ phân giải. Chọn lưu 8MP trong Cài đặt."
                else "Lỗi khi lưu: ${t.message}"
            }
            runOnUiThread {
                saving = false
                saveBtn.text = "Lưu ảnh"
                Ui.toast(this, msg)
                saved?.let { Share.prompt(this, it.uri, it.name) }
            }
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
