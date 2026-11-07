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

    private val chips = ArrayList<Button>()
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
        bar.addView(Ui.button(this, "‹ Quay lại", false) { if (!running) finish() else Ui.toast(this, "Đang xử lý, bấm Dừng trước") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        bar.addView(TextView(this).apply {
            text = "Chỉnh hàng loạt (${uris.size} ảnh)"; textSize = 17f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ink); setPadding(dp(12), 0, 0, 0)
        })
        col.addView(bar)

        col.addView(title("Ống kính"))
        col.addView(slider(0) { defringe = it; updateLabels() })

        col.addView(title("Da"))
        col.addView(CheckBox(this).apply {
            text = "Xóa mụn"; setTextColor(Ui.ink)
            setOnCheckedChangeListener { _, on -> blemish = on }
        })
        col.addView(slider(0) { smooth = it; updateLabels() })

        col.addView(title("Màu"))
        col.addView(CheckBox(this).apply {
            text = "Đổi công thức (gỡ màu lúc chụp)"; setTextColor(Ui.ink); isChecked = true
            setOnCheckedChangeListener { _, on -> invert = on }
        })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (listOf("Không") + Presets.all.map { "${it.slot} · ${it.name}".trimStart(' ', '·') }).forEachIndexed { i, t ->
            val b = Ui.button(this, t, false) { selected = i - 1; styleChips(); updateLabels() }
            chips += b
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        }
        hs.addView(row)
        col.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
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

    private fun title(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.accent); setPadding(0, dp(16), 0, dp(2))
    }

    private fun slider(start: Int, f: (Int) -> Unit) = SeekBar(this).apply {
        max = 100; progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = f(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun styleChips() {
        chips.forEachIndexed { i, b ->
            val on = (i - 1) == selected
            b.background = Ui.rounded(this, if (on) Ui.accent else Ui.field, 12, if (on) 0 else Ui.line)
            b.setTextColor(if (on) Color.WHITE else Ui.ink)
        }
    }

    private fun updateLabels() {
        val color = if (selected >= 0) Presets.all[selected].let { "${it.slot} ${it.name}, độ mạnh $strength%" } else "không đổi màu"
        labels.text = "Xóa viền tím $defringe% · Mịn da $smooth% · Màu: $color.\n" +
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
