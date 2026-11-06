package com.looknote.exif

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Màu sắc và các hàm dựng giao diện dùng chung. */
object Ui {
    val bg = Color.parseColor("#F4F1EC")
    val card = Color.WHITE
    val ink = Color.parseColor("#1F1D1A")
    val mute = Color.parseColor("#6B665E")
    val accent = Color.parseColor("#B5452F")
    val accentSoft = Color.parseColor("#FBEDE8")
    val line = Color.parseColor("#E6E1D8")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(c: Context, color: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(c, 1), stroke)
        }

    fun button(c: Context, label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(c).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            stateListAnimator = null
            setTextColor(if (primary) Color.WHITE else ink)
            background = rounded(c, if (primary) accent else Color.WHITE, 12, if (primary) 0 else line)
            setPadding(dp(c, 14), 0, dp(c, 14), 0)
            setOnClickListener { onClick() }
        }

    fun sectionView(c: Context, s: Section): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 6))
            background = rounded(c, if (s.highlight) accentSoft else bg, 12)
        }
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(c, 10) }
        box.addView(TextView(c).apply {
            text = s.title.uppercase()
            textSize = 11.5f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (s.highlight) accent else mute)
            setPadding(0, 0, 0, dp(c, 4))
        })
        for ((k, v) in s.rows) {
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(c, 5), 0, dp(c, 5))
            }
            row.addView(TextView(c).apply {
                text = k; textSize = 14f; setTextColor(mute)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(c).apply {
                text = v; textSize = 14f; setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            box.addView(row)
        }
        return box
    }

    /** Thẻ thông số của một ảnh: tên file, nút sao chép, các nhóm thông số. */
    fun infoCard(c: Context, info: PhotoInfo): View {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c, Ui.card, 16)
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(c).apply {
            text = info.name
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button(c, "Sao chép", false) { copy(c, info.toText()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 38)))
        card.addView(top)
        for (s in info.sections) card.addView(sectionView(c, s))
        if (info.note != null) {
            card.addView(TextView(c).apply {
                text = info.note
                textSize = 13f
                setTextColor(accent)
                setPadding(0, dp(c, 10), 0, dp(c, 4))
            })
        }
        return card
    }

    fun copy(c: Context, text: String) {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LookExif", text))
        toast(c, "Đã sao chép thông số")
    }

    fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

    /** Đọc tối đa [max] byte đầu file. */
    fun readHead(c: Context, u: Uri, max: Int): ByteArray {
        val input = c.contentResolver.openInputStream(u) ?: throw IllegalStateException("không mở được")
        return input.use { s ->
            val buf = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = s.read(buf, total, max - total)
                if (n <= 0) break
                total += n
            }
            buf.copyOf(total)
        }
    }

    /** Giải mã ảnh thu nhỏ theo chiều rộng tối đa và xoay theo EXIF. */
    fun decode(c: Context, u: Uri, maxW: Int, orientation: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxW) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val deg = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun displayName(c: Context, u: Uri): String {
        try {
            c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val v = cur.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (e: Exception) { }
        return u.lastPathSegment ?: "Ảnh"
    }
}
