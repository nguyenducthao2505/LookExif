package com.looknote.exif

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/** Quản lý công thức màu: thêm, sửa, xóa, hiệu chỉnh theo ảnh thật của máy. */
class PresetActivity : Activity() {

    companion object { private const val PICK_CAL = 51 }

    private lateinit var list: LinearLayout
    private var calIndex = -1

    private val looks = listOf("ST", "PT", "NT", "VV", "VV2", "FL", "IN", "SH")
    private val signed = (-9..9).map { if (it > 0) "+$it" else "$it" }
    private val fades = (0..9).map { "$it" }
    private val abVals = (-14..14).map { it * 0.5 }      // B7 .. A7, bước 0,5
    private val gmVals = (-28..28).map { it * 0.25 }     // G7 .. M7, bước 0,25
    private val evVals = listOf(-2.0, -1.7, -1.3, -1.0, -0.7, -0.3, 0.0, 0.3, 0.7, 1.0, 1.3, 1.7, 2.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        PresetStore.load(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24)) }
        scroll.addView(col)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.button(this, "‹ Quay lại", false) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        bar.addView(TextView(this).apply {
            text = "Công thức màu"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ink)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(bar)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        actions.addView(Ui.button(this, "+ Thêm công thức", true) { edit(-1) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        actions.addView(Ui.button(this, "Khôi phục mặc định", false) { resetDefaults() },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        col.addView(actions)

        col.addView(TextView(this).apply {
            text = "Hiệu chỉnh theo máy: chụp cùng một cảnh 2 tấm, một tấm Creative Look ST (mọi thông số 0, WB Shift 0), " +
                "một tấm bằng đúng công thức này. Chọn cả 2 ảnh, app sẽ học màu thật của máy để mô phỏng và gỡ màu chính xác hơn. " +
                "Nên đặt máy cố định, cùng ánh sáng, cùng phơi sáng."
            textSize = 12f; setTextColor(Ui.mute); setPadding(0, dp(12), 0, dp(4)); setLineSpacing(0f, 1.2f)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(scroll)
        render()
    }

    private fun render() {
        list.removeAllViews()
        Presets.all.forEachIndexed { i, p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(this@PresetActivity, Ui.card, 14, Ui.line)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(TextView(this).apply {
                text = listOf(p.slot, p.name).filter { it.isNotBlank() }.joinToString(" · ")
                textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ink)
            })
            card.addView(TextView(this).apply {
                text = p.summary() + " · EV " + (if (p.ev > 0) "+" else "") + PhotoInfoBuilder.fmt(p.ev, 1)
                textSize = 12.5f; setTextColor(if (p.calib != null) Ui.accent else Ui.mute); setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Ui.button(this, "Sửa", false) { edit(i) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
            row.addView(Ui.button(this, if (p.calib != null) "Hiệu chỉnh lại" else "Hiệu chỉnh", false) { startCalibration(i) },
                LinearLayout.LayoutParams(0, dp(38), 1.3f).apply { rightMargin = dp(6) })
            row.addView(Ui.button(this, "Xóa", false) { confirmDelete(i) }, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(row)
            if (p.calib != null) card.addView(Ui.button(this, "Bỏ hiệu chỉnh", false) {
                update(i, p.copy(calib = null, calibSat = 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun update(i: Int, p: Preset?) {
        val l = Presets.all.toMutableList()
        when {
            p == null -> l.removeAt(i)
            i < 0 -> l.add(p)
            else -> l[i] = p
        }
        PresetStore.save(this, l)
        render()
    }

    private fun resetDefaults() {
        dialog().setTitle("Khôi phục 7 công thức mặc định?")
            .setMessage("Các công thức tự thêm, chỉnh sửa và hiệu chỉnh sẽ bị xóa.")
            .setPositiveButton("Khôi phục") { _, _ -> PresetStore.save(this, Presets.defaults); render() }
            .setNegativeButton("Hủy", null).show()
    }

    private fun confirmDelete(i: Int) {
        if (Presets.all.size <= 1) { Ui.toast(this, "Cần giữ lại ít nhất 1 công thức"); return }
        dialog().setTitle("Xóa công thức ${Presets.all[i].name}?")
            .setPositiveButton("Xóa") { _, _ -> update(i, null) }
            .setNegativeButton("Hủy", null).show()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun spinner(items: List<String>, sel: Int) = Spinner(this).apply {
        background = Ui.rounded(this@PresetActivity, Ui.field, 10, Ui.line)
        adapter = object : ArrayAdapter<String>(this@PresetActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Ui.ink) }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Ui.ink); setBackgroundColor(Ui.card); setPadding(dp(14), dp(10), dp(14), dp(10))
                }
        }
        setSelection(sel.coerceIn(0, items.size - 1))
    }

    private fun shiftLabel(v: Double, pos: String, neg: String) =
        if (v == 0.0) "0" else if (v > 0) pos + PhotoInfoBuilder.fmt(v, 2) else neg + PhotoInfoBuilder.fmt(-v, 2)

    /** Sửa (i ≥ 0) hoặc thêm mới (i = -1). */
    private fun edit(i: Int) {
        val p = if (i >= 0) Presets.all[i] else Preset("", "Công thức mới", "ST", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, "AWB")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), 0) }
        fun field(label: String, v: View) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; setTextColor(Ui.mute); textSize = 13.5f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(v, LinearLayout.LayoutParams(0, dp(42), 1.3f))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        fun textField(t: String) = EditText(this).apply {
            setText(t); setTextColor(Ui.ink); textSize = 14f; setSingleLine()
            background = Ui.rounded(this@PresetActivity, Ui.field, 10, Ui.line); setPadding(dp(10), 0, dp(10), 0)
        }
        val eSlot = textField(p.slot); val eName = textField(p.name); val eWb = textField(p.wbNote)
        val sLook = spinner(looks, looks.indexOf(p.look).coerceAtLeast(0))
        val sC = spinner(signed, p.contrast + 9); val sH = spinner(signed, p.highlights + 9)
        val sS = spinner(signed, p.shadows + 9); val sSat = spinner(signed, p.saturation + 9)
        val sF = spinner(fades, p.fade)
        val sAB = spinner(abVals.map { shiftLabel(it, "A", "B") }, abVals.indexOfFirst { kotlin.math.abs(it - p.ab) < 0.26 }.let { if (it < 0) 14 else it })
        val sGM = spinner(gmVals.map { shiftLabel(it, "M", "G") }, gmVals.indexOfFirst { kotlin.math.abs(it - p.gm) < 0.13 }.let { if (it < 0) 28 else it })
        val sEv = spinner(evVals.map { (if (it > 0) "+" else "") + String.format(Locale.US, "%.1f", it) },
            evVals.indexOfFirst { kotlin.math.abs(it - p.ev) < 0.16 }.let { if (it < 0) 6 else it })
        field("Ký hiệu (1, 2, M1…)", eSlot); field("Tên", eName); field("Creative Look", sLook)
        field("Contrast", sC); field("Highlights", sH); field("Shadows", sS); field("Fade", sF); field("Saturation", sSat)
        field("WB (ghi chú)", eWb); field("WB Shift A–B", sAB); field("WB Shift G–M", sGM); field("Bù sáng", sEv)
        val sc = ScrollView(this).apply { addView(box) }
        dialog().setTitle(if (i >= 0) "Sửa công thức" else "Thêm công thức")
            .setView(sc)
            .setPositiveButton("Lưu") { _, _ ->
                val n = p.copy(
                    slot = eSlot.text.toString().trim(), name = eName.text.toString().trim().ifBlank { "Công thức" },
                    look = looks[sLook.selectedItemPosition],
                    contrast = sC.selectedItemPosition - 9, highlights = sH.selectedItemPosition - 9,
                    shadows = sS.selectedItemPosition - 9, fade = sF.selectedItemPosition,
                    saturation = sSat.selectedItemPosition - 9,
                    ab = abVals[sAB.selectedItemPosition], gm = gmVals[sGM.selectedItemPosition],
                    ev = evVals[sEv.selectedItemPosition], wbNote = eWb.text.toString().trim().ifBlank { "AWB" }
                )
                // đổi thông số thì hiệu chỉnh cũ không còn đúng
                val keep = if (n.sameSettings(p)) n else n.copy(calib = null, calibSat = 1f)
                update(i, keep)
            }
            .setNegativeButton("Hủy", null).show()
    }

    // ---------- hiệu chỉnh theo máy ----------
    private fun startCalibration(i: Int) {
        calIndex = i
        dialog().setTitle("Hiệu chỉnh ${Presets.all[i].name}")
            .setMessage("Chọn 2 ảnh chụp cùng cảnh: 1 ảnh Creative Look ST (mọi thông số 0) và 1 ảnh chụp bằng công thức này.")
            .setPositiveButton("Chọn 2 ảnh") { _, _ ->
                val it = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try { startActivityForResult(it, PICK_CAL) } catch (e: Exception) { Ui.toast(this, "Không mở được trình chọn ảnh") }
            }
            .setNegativeButton("Hủy", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CAL || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } } ?: data.data?.let { uris += it }
        if (uris.size != 2) { Ui.toast(this, "Cần chọn đúng 2 ảnh"); return }
        val idx = calIndex
        if (idx !in Presets.all.indices) return
        Ui.toast(this, "Đang học màu từ 2 ảnh...")
        Thread {
            val msg = try { calibrate(idx, uris[0], uris[1]) } catch (t: Throwable) { "Lỗi: ${t.message}" }
            runOnUiThread {
                render()
                dialog().setTitle("Hiệu chỉnh").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun isNeutral(p: Preset?) = p != null && p.look == "ST" && p.contrast == 0 && p.highlights == 0 &&
        p.shadows == 0 && p.fade == 0 && p.saturation == 0 && kotlin.math.abs(p.ab) < 0.1 && kotlin.math.abs(p.gm) < 0.1

    private fun calibrate(i: Int, u1: Uri, u2: Uri): String {
        val h1 = Ui.readHead(this, u1, 512 * 1024); val h2 = Ui.readHead(this, u2, 512 * 1024)
        val s1 = PhotoInfoBuilder.sourcePreset(h1); val s2 = PhotoInfoBuilder.sourcePreset(h2)
        val (refU, refH, tgtU, tgtH, tgtS) = when {
            isNeutral(s1) && !isNeutral(s2) -> Quint(u1, h1, u2, h2, s2)
            isNeutral(s2) && !isNeutral(s1) -> Quint(u2, h2, u1, h1, s1)
            else -> return "Không tìm thấy ảnh chụp bằng Creative Look ST với mọi thông số 0 (và WB Shift 0) trong 2 ảnh đã chọn."
        }
        val p = Presets.all[i]
        val warn = if (tgtS != null && !tgtS.sameSettings(p))
            "\n\nLưu ý: thông số ảnh thứ hai (${tgtS.summary()}) khác công thức đang hiệu chỉnh. Màu học được là màu của ảnh đó." else ""
        val ref = Ui.decodeMax(this, refU, 900, PhotoInfoBuilder.build("", refH).orientation) ?: return "Không mở được ảnh ST."
        var tgt = Ui.decodeMax(this, tgtU, 900, PhotoInfoBuilder.build("", tgtH).orientation) ?: return "Không mở được ảnh công thức."
        if (tgt.width != ref.width || tgt.height != ref.height) tgt = Bitmap.createScaledBitmap(tgt, ref.width, ref.height, true)
        val w = ref.width; val h = ref.height
        val a = IntArray(w * h); val b = IntArray(w * h)
        ref.getPixels(a, 0, w, 0, 0, w, h); tgt.getPixels(b, 0, w, 0, 0, w, h)
        val res = Calibration.fit(a, b, w, h)
        val list = Presets.all.toMutableList()
        list[i] = p.copy(calib = res.curves, calibSat = res.sat)
        PresetStore.save(this, list)
        val q = when { res.error < 4 -> "rất tốt"; res.error < 8 -> "tốt"; res.error < 14 -> "tạm được (2 ảnh có thể hơi lệch khung)"; else -> "kém (2 ảnh lệch nhiều hoặc khác ánh sáng)" }
        return "Đã hiệu chỉnh ${p.name}. Độ khớp: $q (sai số ${PhotoInfoBuilder.fmt(res.error, 1)}/255).$warn"
    }

    private data class Quint(val a: Uri, val b: ByteArray, val c: Uri, val d: ByteArray, val e: Preset?)

    private fun dp(v: Int) = Ui.dp(this, v)
}
