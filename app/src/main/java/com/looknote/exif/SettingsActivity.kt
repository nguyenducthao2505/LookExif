package com.looknote.exif

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Cài đặt: chất lượng lưu, chữ ký khi chia sẻ, quản lý công thức. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        scroll.addView(col)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.button(this, "‹ Quay lại", false) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        bar.addView(TextView(this).apply {
            text = "Cài đặt"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ink); setPadding(dp(12), 0, 0, 0)
        })
        col.addView(bar)

        // --- Lưu ảnh ---
        col.addView(title("Lưu ảnh đã chỉnh"))
        val rg = RadioGroup(this)
        val r8 = radio("Khoảng 8MP: nhanh, nhẹ, đủ đăng mạng và in khổ nhỏ", 1)
        val rFull = radio("Đủ độ phân giải (tới 33MP): chậm hơn, cần nhiều bộ nhớ", 2)
        rg.addView(r8); rg.addView(rFull)
        rg.check(if (AppSettings.fullRes(this)) 2 else 1)
        rg.setOnCheckedChangeListener { _, id -> AppSettings.set(this, "fullRes", id == 2) }
        col.addView(rg)

        col.addView(title("Chất lượng JPEG"))
        val rq = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (q in listOf(90, 95, 100)) rq.addView(radio("$q", q))
        rq.check(AppSettings.quality(this))
        rq.setOnCheckedChangeListener { _, id -> AppSettings.set(this, "quality", id) }
        col.addView(rq)

        // --- Chữ ký ---
        col.addView(title("Chữ ký khi chia sẻ"))
        col.addView(CheckBox(this).apply {
            text = "Thêm chữ ký nhỏ ở góc dưới phải"
            setTextColor(Ui.ink); isChecked = AppSettings.watermark(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> AppSettings.set(this@SettingsActivity, "watermark", on) }
        })
        col.addView(EditText(this).apply {
            hint = "Ví dụ: © Tên của bạn"
            setText(AppSettings.watermarkText(this@SettingsActivity))
            setTextColor(Ui.ink); setHintTextColor(Ui.mute)
            background = Ui.rounded(this@SettingsActivity, Ui.field, 10, Ui.line)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { AppSettings.set(this@SettingsActivity, "watermarkText", s?.toString() ?: "") }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(small("Chữ ký chỉ thêm vào bản chia sẻ, không thêm vào ảnh lưu trong máy."))

        // --- Công thức ---
        col.addView(title("Công thức màu"))
        col.addView(Ui.button(this, "Quản lý và hiệu chỉnh công thức", false) { startActivity(Intent(this, PresetActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        col.addView(small("Sony look " + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }).apply {
            setPadding(0, dp(24), 0, 0)
        })
        setContentView(scroll)
    }

    private fun radio(t: String, id: Int) = RadioButton(this).apply {
        text = t; this.id = id; setTextColor(Ui.ink); setPadding(dp(6), dp(6), dp(12), dp(6))
    }

    private fun title(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.accent); setPadding(0, dp(18), 0, dp(4))
    }

    private fun small(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Ui.mute); setPadding(0, dp(4), 0, 0) }

    private fun dp(v: Int) = Ui.dp(this, v)
}
