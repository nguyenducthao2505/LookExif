package com.looknote.exif

import android.app.Activity
import android.content.Intent
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
    private lateinit var lookGrid: LookGrid
    private lateinit var expandBtn: Button
    private var panelBig = false
    private var thumbKey = ""
    private val skinChips = ArrayList<Button>()
    private lateinit var invLabel: TextView
    private var invAmount = 100
    private lateinit var smoothBar: SeekBar
    private lateinit var skinShot: TextView
    private lateinit var cropInfo: TextView
    private var crop = CropSpec()
    private var shownBmp: Bitmap? = null
    private var baseCropKey: CropSpec? = null
    private var baseCropped: Bitmap? = null

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
        bar.addView(Film.iconButton(this, IconDrawable.Kind.BACK, null) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(40)).apply { rightMargin = dp(10) })
        bar.addView(TextView(this).apply {
            text = "CHỈNH ẢNH"
            textSize = 17f
            typeface = Film.display
            letterSpacing = 0.1f
            setTextColor(Ui.ink)
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

        // --- Khung ảnh ---
        panel.addView(sectionTitle("Khung ảnh"))
        val cropRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cropRow.addView(Ui.button(this, "✂ Cắt & xoay", false) { openCrop() }, LinearLayout.LayoutParams(0, dp(38), 1.4f).apply { rightMargin = dp(6) })
        cropRow.addView(Ui.button(this, "Bỏ cắt", false) { crop = CropSpec(); cropChanged() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        panel.addView(cropRow)
        cropInfo = small("Chưa cắt / xoay")
        panel.addView(cropInfo)

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
        smoothBar = slider(SoftSkin.MAX, 0, onChange = { smooth = it; updateLabels(); styleSkinChips() })
        panel.addView(smoothBar)
        // chọn nhanh theo các mức Soft Skin Effect của máy Sony
        val skinChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SoftSkin.names.forEachIndexed { i, n ->
            val b = Ui.button(this, if (i == 0) "Tắt" else n, false) {
                smooth = SoftSkin.appValue[i]; smoothBar.progress = smooth
                updateLabels(); styleSkinChips(); render()
            }
            skinChips += b
            skinChipRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i < 3) rightMargin = dp(6) })
        }
        panel.addView(skinChipRow)
        skinShot = small("")
        panel.addView(skinShot)

        // --- Màu ---
        panel.addView(sectionTitle("Màu"))
        invertBox = CheckBox(this).apply {
            text = "Đổi công thức (gỡ màu lúc chụp)"; setTextColor(Ui.ink); isEnabled = false
            setOnCheckedChangeListener { _, on -> invert = on; render() }
        }
        panel.addView(invertBox)
        invLabel = label().apply { text = "Mức gỡ màu cũ: 100%" }
        panel.addView(invLabel)
        panel.addView(slider(150, 100, onChange = { invAmount = it; invLabel.text = "Mức gỡ màu cũ: $it%" }))
        srcInfo = small("Đang đọc thông số lúc chụp...")
        panel.addView(srcInfo)
        // lưới ảnh xem trước: nhìn nhiều look cùng lúc
        expandBtn = Ui.button(this, "⤢ Mở rộng bảng chọn màu", false) { togglePanel() }
        panel.addView(expandBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) })
        lookGrid = LookGrid(this) { select(it) }
        panel.addView(lookGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
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
        styleSkinChips()
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
            if (panelBig) body.addView(panelScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
            else body.addView(panelScroll, LinearLayout.LayoutParams(dp(if (wide) 420 else 340), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            body.orientation = LinearLayout.VERTICAL
            body.addView(zoom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = dp(8); rightMargin = dp(8)
            })
            val tall = resources.configuration.screenHeightDp >= 900
            if (panelBig) body.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
            else body.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (tall) 400 else 310)))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        if (::lookGrid.isInitialized) lookGrid.release()
        if (::zoom.isInitialized) zoom.release()
    }

    private var sectionNo = 0
    private fun sectionTitle(t: String) = Film.sectionHeader(this, ++sectionNo, t)

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
        smoothLabel.text = "Mịn da: $smooth" + (if (smooth > 0) " (≈ máy: ${SoftSkin.names[SoftSkin.levelOf(smooth)]})" else "")
        fringeLabel.text = "Xóa viền tím / xanh lá: $defringe%"
        summary.text = if (selected < 0) "Chưa áp màu mới. Chạm vào một ô màu ở trên."
        else Presets.all[selected].summary()
    }

    private fun styleChips() {
        lookGrid.setSelected(selected)
    }

    /** Phóng to / thu nhỏ bảng điều khiển để xem được nhiều ô màu hơn. */
    private fun togglePanel() {
        panelBig = !panelBig
        expandBtn.text = if (panelBig) "⤡ Thu nhỏ bảng chọn màu" else "⤢ Mở rộng bảng chọn màu"
        applyLayout()
        if (panelBig) panelScroll.post { panelScroll.smoothScrollTo(0, expandBtn.top) }
    }

    /** Dựng lại ảnh xem trước trong lưới khi ảnh nguồn hoặc mức gỡ màu đổi. */
    private fun refreshThumbs() {
        val b = base ?: return
        val inv = if (invertOn) srcPreset else null
        val amount = invAmount / 100f
        val key = "${System.identityHashCode(b)}-${inv != null}-$invAmount"
        if (key == thumbKey) return
        thumbKey = key
        worker.execute {
            val s = try { LookGrid.small(b) } catch (t: Throwable) { return@execute }
            val out = if (inv != null) {
                val w = s.width; val h = s.height
                val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
                LookInverter(inv, amount).process(px)
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            } else s
            runOnUiThread { lookGrid.setSource(out) }
        }
    }

    private fun styleSkinChips() {
        skinChips.forEachIndexed { i, b ->
            val on = smooth == SoftSkin.appValue[i]
            Film.paintChip(this, b, on)
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
        zoom.setCompare(if (compareOn && processed != null) croppedBase() else null)
    }

    private fun toggleClip() {
        clipOn = !clipOn
        clipBtn.text = if (clipOn) "Vùng cháy: Bật" else "Vùng cháy: Tắt"
        refreshClip(shownBmp ?: base)
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
    private val colorEdit get() = skinOn || selected >= 0 || invertOn || defringe > 0
    private val anyEdit get() = colorEdit || !crop.isIdentity

    private fun loadBase() {
        summary.text = "Đang mở ảnh..."
        worker.execute {
            val head = try { Ui.readHead(this, uri, 512 * 1024) } catch (e: Exception) { ByteArray(0) }
            orientation = PhotoInfoBuilder.build("", head).orientation
            val chk = SourceCheck.check(head)
            val src = PhotoInfoBuilder.sourcePreset(head)?.takeIf { !chk.builtColor }
            val shotSkin = ExifParser.softSkin(head)
            val b = try { Ui.decodeMax(this, uri, 1800, orientation) } catch (e: Throwable) { null }
            runOnUiThread {
                skinShot.text = when (shotSkin) {
                    null -> "Mức Off / Low / Mid / High làm mịn gần giống Soft Skin Effect của máy."
                    0 -> "Lúc chụp: Soft Skin Effect Off. Chọn mức để làm mịn giống máy."
                    else -> "Lúc chụp: Soft Skin Effect ${SoftSkin.names[shotSkin]} (máy đã làm mịn sẵn). Chọn thêm sẽ mịn chồng lên."
                }
                when {
                    chk.builtColor -> srcInfo.text = "Ảnh đã áp màu dựng sẵn bằng app: không gỡ màu lần nữa."
                    src == null -> srcInfo.text = "Ảnh không có thông số Creative Look của Sony: màu mới sẽ áp chồng lên màu sẵn có."
                    src.look == "BW" || src.look == "SE" -> srcInfo.text = "Ảnh chụp đen trắng/sepia: không gỡ lại được màu."
                    else -> {
                        srcPreset = src
                        val m = Presets.match(src)
                        val neutral = src.look == "ST" && src.contrast == 0 && src.highlights == 0 && src.shadows == 0 &&
                            src.fade == 0 && src.saturation == 0 && kotlin.math.abs(src.ab) < 0.3 && kotlin.math.abs(src.gm) < 0.3
                        srcInfo.text = "Lúc chụp: " + src.summary() + when {
                            neutral -> " (ảnh ST 0 — nguồn tốt nhất để đổi màu)"
                            m?.calibInv != null -> " (gỡ màu bằng bảng 3D đã hiệu chỉnh theo máy)"
                            m != null -> " (dùng màu đã hiệu chỉnh theo máy)"
                            else -> " (gỡ màu theo mô hình ước lượng — hiệu chỉnh khe này trong Công thức màu để chính xác hơn, hoặc chụp ST 0)"
                        }
                        invertBox.isEnabled = true
                        invertBox.isChecked = true
                    }
                }
                if (chk.warnings.isNotEmpty()) srcInfo.text = srcInfo.text.toString() + "\n⚠ " + chk.warnings.joinToString("\n⚠ ")
                if (b == null) {
                    summary.text = "Không mở được ảnh này (định dạng RAW chưa hỗ trợ chỉnh)."
                    saveBtn.isEnabled = false
                } else {
                    base = b
                    show(b)
                    refreshThumbs()
                    updateLabels()
                    if (colorEdit) render()
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
        strength = strength / 100f, ev = ev, invert = invertOn, invertStrength = invAmount / 100f,
        smooth = smooth, blemish = blemish, defringe = defringe,
        crop = crop.takeIf { !it.isIdentity }
    )

    private fun render() {
        val b = base ?: return
        refreshThumbs()
        if (!colorEdit) {
            processed = null; show(b); return
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
            if (inv != null) LookInverter(inv, o.invertStrength).process(px)
            o.preset?.let { ColorEngine(it, o.ev, o.strength).process(px) }
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            runOnUiThread {
                if (my != token) return@runOnUiThread
                processed = out
                show(out)
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

    /** Hiện ảnh (đã cắt / xoay theo khung đang chọn). */
    private fun show(out: Bitmap) {
        val shown = try { Crop.apply(out, crop) } catch (t: Throwable) { out }
        val cur = shownBmp
        if (cur == null || cur.width != shown.width || cur.height != shown.height) zoom.setImage(shown) else zoom.swapBitmap(shown)
        shownBmp = shown
        zoom.setCompare(if (compareOn && processed != null) croppedBase() else null)
        refreshClip(shown)
    }

    private fun croppedBase(): Bitmap? {
        val b = base ?: return null
        if (crop.isIdentity) return b
        if (baseCropKey != crop || baseCropped == null) {
            baseCropped = try { Crop.apply(b, crop) } catch (t: Throwable) { b }
            baseCropKey = crop
        }
        return baseCropped
    }

    private fun openCrop() {
        val b = base ?: run { Ui.toast(this, "Ảnh chưa mở xong"); return }
        CropBridge.preview = processed ?: b
        CropBridge.faces = faces ?: emptyList()
        CropBridge.spec = crop
        CropBridge.result = null
        startActivityForResult(Intent(this, CropActivity::class.java), REQ_CROP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CROP) {
            if (resultCode == RESULT_OK) CropBridge.result?.let { crop = it }
            CropBridge.preview = null; CropBridge.result = null
            cropChanged()
        }
    }

    private fun cropChanged() {
        val b = base
        cropInfo.text = if (b == null) "Chưa cắt / xoay" else {
            var (fw, fh) = Ui.pixelSize(this, uri, orientation)
            if (fw <= 0 || fh <= 0) { fw = b.width; fh = b.height }
            val maxSide = if (AppSettings.fullRes(this)) 12000 else 4000
            if (maxOf(fw, fh) > maxSide) { val k = maxSide.toFloat() / maxOf(fw, fh); fw = (fw * k).toInt(); fh = (fh * k).toInt() }
            val (ow, oh) = CropGeom.outSize(fw, fh, crop)
            if (crop.isIdentity) "Chưa cắt / xoay" else crop.describe(b.width, b.height).substringAfter(" · ", "").let { d ->
                "Ảnh lưu ${ow}×${oh}" + (if (d.isNotBlank()) " · $d" else "")
            }
        }
        (processed ?: b)?.let { show(it) }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object { private const val REQ_CROP = 71 }
}
