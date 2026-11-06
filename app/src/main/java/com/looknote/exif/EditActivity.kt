package com.looknote.exif

import android.app.Activity
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Màn chỉnh màu: áp một trong 7 công thức theo bảng rồi lưu thành ảnh mới. */
class EditActivity : Activity() {

    private lateinit var uri: Uri
    private lateinit var preview: ImageView
    private lateinit var compareLabel: TextView
    private lateinit var summary: TextView
    private lateinit var strengthLabel: TextView
    private lateinit var evLabel: TextView
    private lateinit var evBar: SeekBar
    private lateinit var saveBtn: Button
    private val chips = ArrayList<Button>()

    private var base: Bitmap? = null
    private var processed: Bitmap? = null
    private var orientation = 1
    private var selected = -1          // -1 = ảnh gốc
    private var strength = 100         // %
    private var ev = 0.0
    private var token = 0
    private var saving = false
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
            text = "Chỉnh màu"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.ink)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveBtn = Ui.button(this, "Lưu ảnh", true) { save() }
        bar.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(bar)

        // ----- Ảnh xem trước (giữ tay để xem ảnh gốc) -----
        val frame = FrameLayout(this)
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        compareLabel = TextView(this).apply {
            text = "Ảnh gốc"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = Ui.rounded(this@EditActivity, Color.parseColor("#AA000000"), 8)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        frame.addView(compareLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(8) })
        frame.setOnTouchListener { _, me ->
            when (me.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (selected >= 0 && base != null) {
                        preview.setImageBitmap(base); compareLabel.visibility = View.VISIBLE
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    compareLabel.visibility = View.GONE
                    preview.setImageBitmap(if (selected >= 0) processed ?: base else base)
                }
            }
            true
        }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })

        // ----- Bảng điều khiển -----
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("Gốc") + Presets.all.map { "${it.slot} · ${it.name}" }
        labels.forEachIndexed { i, label ->
            val b = Ui.button(this, label, false) { select(i - 1) }
            chips += b
            chipRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        }
        hs.addView(chipRow)
        panel.addView(hs)

        summary = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Ui.mute)
            setPadding(dp(2), dp(8), 0, dp(2))
        }
        panel.addView(summary)

        strengthLabel = label()
        panel.addView(strengthLabel)
        panel.addView(SeekBar(this).apply {
            max = 100; progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    strength = p; updateLabels(); if (fromUser) render()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        evLabel = label()
        panel.addView(evLabel)
        evBar = SeekBar(this).apply {
            max = 20; progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    ev = (p - 10) / 10.0; updateLabels(); if (fromUser) render()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        panel.addView(evBar)

        panel.addView(TextView(this).apply {
            text = "Giữ tay lên ảnh để so với ảnh gốc. Ảnh mới lưu vào Pictures/LookExif, ảnh gốc giữ nguyên. " +
                "Đẹp nhất với ảnh chụp ở Creative Look ST, vì ảnh đã có màu sẵn sẽ bị chồng màu."
            textSize = 11.5f
            setTextColor(Ui.mute)
            setPadding(dp(2), dp(6), 0, 0)
        })
        root.addView(panel)
        setContentView(root)

        updateLabels()
        styleChips()
        loadBase()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun label() = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.ink)
        setPadding(dp(2), dp(10), 0, 0)
    }

    private fun updateLabels() {
        strengthLabel.text = "Độ mạnh: $strength%"
        val e = if (ev > 0) "+" + PhotoInfoBuilder.fmt(ev, 1) else PhotoInfoBuilder.fmt(ev, 1)
        evLabel.text = "Độ sáng: $e EV"
        summary.text = if (selected < 0) "Ảnh gốc. Chọn một công thức màu ở trên."
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
        if (i >= 0) {
            ev = Presets.all[i].ev
            evBar.progress = (ev * 10 + 10).toInt().coerceIn(0, 20)
        }
        updateLabels()
        styleChips()
        render()
    }

    private fun loadBase() {
        summary.text = "Đang mở ảnh..."
        worker.execute {
            val head = try { Ui.readHead(this, uri, 512 * 1024) } catch (e: Exception) { ByteArray(0) }
            orientation = PhotoInfoBuilder.build("", head).orientation
            val b = try { Ui.decodeMax(this, uri, 1800, orientation) } catch (e: Throwable) { null }
            runOnUiThread {
                if (b == null) {
                    summary.text = "Không mở được ảnh này (định dạng RAW chưa hỗ trợ chỉnh màu)."
                    saveBtn.isEnabled = false
                } else {
                    base = b
                    preview.setImageBitmap(b)
                    updateLabels()
                }
            }
        }
    }

    private fun render() {
        val b = base ?: return
        if (selected < 0) { preview.setImageBitmap(b); return }
        val my = ++token
        val p = Presets.all[selected]
        val st = strength / 100f
        val e = ev
        worker.execute {
            if (my != token) return@execute
            val w = b.width; val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            ColorEngine(p, e, st).process(px)
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            runOnUiThread {
                if (my != token) return@runOnUiThread
                processed = out
                preview.setImageBitmap(out)
            }
        }
    }

    private fun save() {
        if (selected < 0) { Ui.toast(this, "Hãy chọn một công thức màu trước"); return }
        if (saving) return
        saving = true
        saveBtn.text = "Đang lưu..."
        val p = Presets.all[selected]
        val st = strength / 100f
        val e = ev
        Thread {
            val msg = try {
                val head = Ui.readHead(this, uri, 512 * 1024)
                val full = Ui.decodeMax(this, uri, 4000, orientation) ?: throw IllegalStateException("không giải mã được ảnh")
                val work = if (full.isMutable) full else full.copy(Bitmap.Config.ARGB_8888, true)
                val w = work.width; val h = work.height
                val rows = 256
                val buf = IntArray(w * rows)
                val engine = ColorEngine(p, e, st)
                var y = 0
                while (y < h) {
                    val hh = minOf(rows, h - y)
                    work.getPixels(buf, 0, w, 0, y, w, hh)
                    engine.process(buf, 0, w * hh)
                    work.setPixels(buf, 0, w, 0, y, w, hh)
                    y += hh
                }
                val bos = ByteArrayOutputStream()
                work.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                var jpg = bos.toByteArray()
                ExifParser.exifSegmentForEdit(head)?.let { jpg = ExifParser.insertExif(jpg, it) }

                val baseName = Ui.displayName(this, uri).substringBeforeLast('.')
                val outName = "${baseName}_LX-${p.slot}.jpg"
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
                "Đã lưu: Pictures/LookExif/$outName"
            } catch (t: Throwable) {
                "Lỗi khi lưu: ${t.message}"
            }
            runOnUiThread {
                saving = false
                saveBtn.text = "Lưu ảnh"
                Ui.toast(this, msg)
            }
        }.start()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
