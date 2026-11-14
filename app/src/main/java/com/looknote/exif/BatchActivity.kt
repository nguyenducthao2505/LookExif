package com.looknote.exif

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Chỉnh hàng loạt: áp cùng một thiết lập cho nhiều ảnh rồi lưu từng ảnh. */
class BatchActivity : Activity() {

    private lateinit var lookGrid: LookGrid
    private var selected = -1
    private var strength = 100
    private var smooth = 0
    private var defringe = 0
    private var blemish = false
    private var invert = true
    @Volatile private var running = false
    @Volatile private var cancel = false

    private lateinit var startBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView
    private lateinit var labels: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        PresetStore.load(this)
        val uris = Gallery.batch
        if (uris.isEmpty()) { finish(); return }

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        scroll.addView(col)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Film.iconButton(this, IconDrawable.Kind.BACK, null) { if (!running) finish() else Ui.toast(this, "Đang xử lý, bấm Dừng trước") },
            LinearLayout.LayoutParams(dp(44), dp(40)))
        bar.addView(TextView(this).apply {
            text = "Chỉnh hàng loạt (${uris.size} ảnh)"; textSize = 17f
            typeface = Film.display; letterSpacing = 0.08f; setTextColor(Ui.ink); setPadding(dp(12), 0, 0, 0)
        })
        col.addView(bar)

        col.addView(title("Ống kính"))
        col.addView(slider(0) { defringe = it; updateLabels() })

        col.addView(title("Da"))
        col.addView(CheckBox(this).apply {
            text = "Xóa mụn"; setTextColor(Ui.ink)
            setOnCheckedChangeListener { _, on -> blemish = on }
        })
        col.addView(slider(0, SoftSkin.MAX) { smooth = it; updateLabels() })

        col.addView(title("Màu"))
        col.addView(CheckBox(this).apply {
            text = "Đổi công thức (gỡ màu lúc chụp)"; setTextColor(Ui.ink); isChecked = true
            setOnCheckedChangeListener { _, on -> invert = on }
        })
        // lưới xem trước, dựng từ ảnh đầu tiên trong lô
        col.addView(TextView(this).apply {
            text = "Ảnh xem trước dựng từ ảnh đầu tiên trong lô."; textSize = 12f; setTextColor(Ui.mute)
        })
        lookGrid = LookGrid(this) { selected = it; styleChips(); updateLabels() }
        col.addView(lookGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        loadPreview(uris[0])
        col.addView(slider(100) { strength = it; updateLabels() })

        labels = TextView(this).apply { textSize = 12.5f; setTextColor(Ui.mute); setPadding(0, dp(12), 0, 0) }
        col.addView(labels)

        startBtn = Ui.button(this, "Bắt đầu", true) { if (running) cancel = true else start(uris) }
        col.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(14) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = uris.size; progress = 0 }
        col.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(12) })
        log = TextView(this).apply { textSize = 12.5f; setTextColor(Ui.mute); setPadding(0, dp(8), 0, 0) }
        col.addView(log)

        setContentView(scroll)
        styleChips(); updateLabels()
    }

    private var sectionNo = 0
    private fun title(t: String) = Film.sectionHeader(this, ++sectionNo, t)

    private fun slider(start: Int, mx: Int = 100, f: (Int) -> Unit) = SeekBar(this).apply {
        max = mx; progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = f(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun styleChips() {
        lookGrid.setSelected(selected)
    }

    /** Dựng ảnh nguồn cho lưới xem trước (gỡ màu lúc chụp nếu ảnh có thông số Creative Look). */
    private fun loadPreview(u: android.net.Uri) {
        Thread {
            try {
                val head = Ui.readHead(this, u, 512 * 1024)
                val ori = PhotoInfoBuilder.build("", head).orientation
                val src = PhotoInfoBuilder.sourcePreset(head)?.takeIf { it.look != "BW" && it.look != "SE" && !SourceCheck.check(head).builtColor }
                val b = Ui.decodeMax(this, u, 480, ori) ?: return@Thread
                val s = LookGrid.small(b)
                val out = if (src != null) {
                    val w = s.width; val h = s.height
                    val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
                    LookInverter(src).process(px)
                    android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                } else s
                runOnUiThread { if (!isFinishing) lookGrid.setSource(out) }
            } catch (t: Throwable) { }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::lookGrid.isInitialized) lookGrid.release()
    }

    private fun updateLabels() {
        val color = if (selected >= 0) Presets.all[selected].let { "${it.slot} ${it.name}, độ mạnh $strength%" } else "không đổi màu"
        labels.text = "Xóa viền tím $defringe% · Mịn da $smooth${if (smooth > 0) " (≈ ${SoftSkin.names[SoftSkin.levelOf(smooth)]})" else ""} · Màu: $color.\n" +
            "Mỗi ảnh lưu thành file mới trong Pictures/LookExif, ảnh gốc giữ nguyên."
    }

    private fun start(uris: List<android.net.Uri>) {
        val o = EditOptions(
            preset = if (selected >= 0) Presets.all[selected] else null,
            strength = strength / 100f, invert = invert,
            smooth = smooth, blemish = blemish, defringe = defringe
        )
        if (o.preset == null && !o.skin && o.defringe == 0 && !o.invert) {
            Ui.toast(this, "Chưa chọn chỉnh sửa nào"); return
        }
        running = true; cancel = false
        startBtn.text = "Dừng"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            var ok = 0; var fail = 0
            for ((k, u) in uris.withIndex()) {
                if (cancel) break
                val name = Ui.displayName(this, u)
                runOnUiThread { log.text = "Đang xử lý ${k + 1}/${uris.size}: $name" }
                try { Processor.processAndSave(this, u, o); ok++ } catch (t: Throwable) { fail++ }
                runOnUiThread { progress.progress = k + 1 }
            }
            runOnUiThread {
                running = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startBtn.text = "Bắt đầu"
                log.text = (if (cancel) "Đã dừng. " else "Xong. ") + "Đã lưu $ok ảnh vào Pictures/LookExif" +
                    (if (fail > 0) ", lỗi $fail ảnh (thường do định dạng RAW hoặc thiếu bộ nhớ)." else ".")
            }
        }.start()
    }

    private fun dp(v: Int) = Ui.dp(this, v)
}
