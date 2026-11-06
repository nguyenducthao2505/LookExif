package com.looknote.exif

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream

class MainActivity : Activity() {

    private val bg = Color.parseColor("#F4F1EC")
    private val card = Color.WHITE
    private val ink = Color.parseColor("#1F1D1A")
    private val mute = Color.parseColor("#6B665E")
    private val accent = Color.parseColor("#B5452F")
    private val accentSoft = Color.parseColor("#FBEDE8")
    private val line = Color.parseColor("#E6E1D8")

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private val results = ArrayList<PhotoInfo>()

    companion object { private const val PICK = 42 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Thanh tiêu đề
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "LookExif"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        })
        header.addView(TextView(this).apply {
            text = "Xem Creative Look, WB và thông số chụp của ảnh Sony"
            textSize = 13f
            setTextColor(mute)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(makeButton("Chọn ảnh", true) { pick() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        buttons.addView(makeButton("Sao chép tất cả", false) { copyAll() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        header.addView(buttons)
        root.addView(header)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(24))
        }
        empty = TextView(this).apply {
            text = "Bấm \"Chọn ảnh\" để mở ảnh JPEG/HEIF/ARW gốc từ máy Sony.\n\n" +
                "Có thể chọn nhiều ảnh một lúc, hoặc từ Thư viện ảnh bấm Chia sẻ → LookExif.\n\n" +
                "Lưu ý: ảnh gửi qua Zalo, Messenger, Facebook hoặc ảnh 2MP từ Creators' App thường đã mất thông số."
            textSize = 14f
            setTextColor(mute)
            setPadding(dp(8), dp(24), dp(8), dp(8))
            setLineSpacing(0f, 1.25f)
        }
        list.addView(empty)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> streamExtra(i)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(i)?.let { uris += it }
            Intent.ACTION_VIEW -> i.data?.let { uris += it }
        }
        if (uris.isNotEmpty()) load(uris)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, PICK)
        } catch (e: Exception) {
            toast("Không mở được trình chọn file")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it }
        } else data.data?.let { uris += it }
        if (uris.isNotEmpty()) load(uris)
    }

    private fun load(uris: List<Uri>) {
        results.clear()
        list.removeAllViews()
        val loading = TextView(this).apply {
            text = "Đang đọc ${uris.size} ảnh..."
            setTextColor(mute)
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        list.addView(loading)
        Thread {
            for (u in uris) {
                val name = displayName(u)
                val info = try {
                    val bytes = readHead(u, 8 * 1024 * 1024)
                    PhotoInfoBuilder.build(name, bytes)
                } catch (e: Exception) {
                    PhotoInfo(name, emptyList(), "Lỗi đọc file: ${e.message}", 1)
                }
                val thumb = try { thumbnail(u, info.orientation) } catch (e: Throwable) { null }
                runOnUiThread {
                    if (loading.parent != null) list.removeView(loading)
                    results += info
                    list.addView(photoCard(info, thumb))
                }
            }
        }.start()
    }

    // ---------- đọc file ----------
    private fun readHead(u: Uri, max: Int): ByteArray {
        val input: InputStream = contentResolver.openInputStream(u) ?: throw IllegalStateException("không mở được")
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

    private fun displayName(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (e: Exception) { }
        return u.lastPathSegment ?: "Ảnh"
    }

    private fun thumbnail(u: Uri, orientation: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 900) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val deg = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ---------- giao diện ----------
    private fun photoCard(info: PhotoInfo, thumb: Bitmap?): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, dp(16).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        c.layoutParams = lp

        if (thumb != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(thumb)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                background = rounded(line, dp(10).toFloat())
            }
            c.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = info.name
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(makeButton("Sao chép", false) { copy(info.toText()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        c.addView(top)

        for (s in info.sections) c.addView(sectionView(s))

        if (info.note != null) {
            c.addView(TextView(this).apply {
                text = info.note
                textSize = 13f
                setTextColor(accent)
                setPadding(0, dp(10), 0, dp(4))
            })
        }
        return c
    }

    private fun sectionView(s: Section): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            background = rounded(if (s.highlight) accentSoft else bg, dp(12).toFloat())
        }
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        box.addView(TextView(this).apply {
            text = s.title.uppercase()
            textSize = 11.5f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (s.highlight) accent else mute)
            setPadding(0, 0, 0, dp(4))
        })
        for ((k, v) in s.rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(TextView(this).apply {
                text = k; textSize = 14f; setTextColor(mute)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = v; textSize = 14f; setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            box.addView(row)
        }
        return box
    }

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            stateListAnimator = null
            setTextColor(if (primary) Color.WHITE else ink)
            background = rounded(if (primary) accent else Color.WHITE, dp(12).toFloat(), if (primary) 0 else line)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun copyAll() {
        if (results.isEmpty()) { toast("Chưa có ảnh nào"); return }
        copy(results.joinToString("\n") { it.toText() })
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LookExif", text))
        toast("Đã sao chép thông số")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
