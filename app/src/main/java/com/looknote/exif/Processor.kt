package com.looknote.exif

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * Các mức Soft Skin Effect của máy, quy ra mức "Mịn da" của app (0..150).
 * Đo trên bộ 4 ảnh A7C II (Off / Low / Mid / High) cùng khuôn mặt: so độ còn lại của vân da.
 */
object SoftSkin {
    val names = listOf("Off", "Low", "Mid", "High")
    val appValue = intArrayOf(0, 50, 75, 120)
    const val MAX = 150
    /** Mức máy gần nhất với giá trị mịn da của app. */
    fun levelOf(smooth: Int): Int = when {
        smooth <= 0 -> 0
        smooth < 63 -> 1
        smooth < 98 -> 2
        else -> 3
    }
}

/** Cài đặt của app. */
object AppSettings {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun fullRes(c: Context) = sp(c).getBoolean("fullRes", false)
    fun quality(c: Context) = sp(c).getInt("quality", 95)
    fun watermark(c: Context) = sp(c).getBoolean("watermark", false)
    fun watermarkText(c: Context) = sp(c).getString("watermarkText", "") ?: ""
    fun set(c: Context, key: String, v: Any) {
        val e = sp(c).edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is String -> e.putString(key, v)
        }
        e.apply()
    }
}

/** Thiết lập chỉnh ảnh (dùng cho cả chỉnh một ảnh và chỉnh hàng loạt). */
data class EditOptions(
    val preset: Preset?,
    val strength: Float = 1f,
    val ev: Double = preset?.ev ?: 0.0,
    val invert: Boolean = false,
    /** Mức gỡ màu cũ 0..1,5. */
    val invertStrength: Float = 1f,
    val smooth: Int = 0,
    val blemish: Boolean = false,
    /** Xóa viền tím 0..100. */
    val defringe: Int = 0
) {
    val skin get() = smooth > 0 || blemish
}

/** Xử lý toàn bộ ảnh ở độ phân giải lưu và ghi vào Pictures/LookExif. */
object Processor {

    class Saved(val uri: Uri, val name: String)

    /**
     * @param delta0 độ lệch làm da đã tính sẵn (từ màn chỉnh); null thì tự tính khi cần
     */
    fun processAndSave(c: Context, uri: Uri, o: EditOptions, delta0: SkinRetouch.Delta? = null): Saved {
        val head = Ui.readHead(c, uri, 512 * 1024)
        val orient = PhotoInfoBuilder.build("", head).orientation
        val src = if (o.invert) PhotoInfoBuilder.sourcePreset(head)?.takeIf { it.look != "BW" && it.look != "SE" } else null

        var delta = delta0
        if (delta == null && o.skin) {
            val prev = Ui.decodeMax(c, uri, 1800, orient) ?: throw IllegalStateException("không mở được ảnh")
            val faces = FaceFinder.detect(prev)
            val px = IntArray(prev.width * prev.height)
            prev.getPixels(px, 0, prev.width, 0, 0, prev.width, prev.height)
            delta = SkinRetouch.compute(px, prev.width, prev.height, faces, o.smooth / 100f, o.blemish)
            prev.recycle()
        }

        val maxW = if (AppSettings.fullRes(c)) 12000 else 4000
        val full = Ui.decodeMax(c, uri, maxW, orient) ?: throw IllegalStateException("không giải mã được ảnh")
        val work = if (full.isMutable) full else full.copy(Bitmap.Config.ARGB_8888, true)
        val w = work.width; val h = work.height
        val rows = 256
        val buf = IntArray(w * rows)
        val engine = o.preset?.let { ColorEngine(it, o.ev, o.strength) }
        val inverter = src?.let { LookInverter(it, o.invertStrength) }
        // lượt 1: xóa viền tím ở độ phân giải đầy đủ (cần đọc thêm vài dòng đệm trên/dưới)
        if (o.defringe > 0) {
            val r = Defringe.radiusFor(w)
            val margin = r + 1
            val band = IntArray(w * (rows + 2 * margin))
            var yy = 0
            while (yy < h) {
                val hh = minOf(rows, h - yy)
                val t = minOf(margin, yy); val b = minOf(margin, h - (yy + hh))
                work.getPixels(band, 0, w, 0, yy - t, w, t + hh + b)
                Defringe.process(band, w, t, hh, b, r, o.defringe / 100f, true)
                work.setPixels(band, t * w, w, 0, yy, w, hh)
                yy += hh
            }
        }
        // lượt 2: làm da, gỡ màu cũ, áp màu mới
        var y = 0
        while (y < h) {
            val hh = minOf(rows, h - y)
            work.getPixels(buf, 0, w, 0, y, w, hh)
            if (delta != null) SkinRetouch.apply(delta, buf, w, h, y, hh)
            inverter?.process(buf, 0, w * hh)
            engine?.process(buf, 0, w * hh)
            work.setPixels(buf, 0, w, 0, y, w, hh)
            y += hh
        }
        val bos = ByteArrayOutputStream()
        work.compress(Bitmap.CompressFormat.JPEG, AppSettings.quality(c), bos)
        work.recycle()
        var jpg = bos.toByteArray()
        ExifParser.exifSegmentForEdit(head)?.let { seg ->
            // ghi lại thông số mới để app (và phần mềm đọc EXIF Sony) thấy đúng màu, độ mịn da, kích thước
            val newLook = when {
                o.preset != null && o.preset.lutFile != null && o.strength >= 0.5f ->
                    Preset("", "", "ST", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, "")  // LUT: không gỡ lại màu này lần sau
                o.preset != null && o.strength >= 0.5f -> o.preset
                src != null -> Preset("", "", "ST", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, "")
                else -> null
            }
            val oldSkin = ExifParser.softSkin(head) ?: 0
            val skin = if (delta != null && o.smooth > 0) maxOf(oldSkin, SoftSkin.levelOf(o.smooth)) else null
            ExifParser.rewrite(seg, ExifParser.Rewrite(newLook, skin, w, h, "LookExif " + appVersion(c)))
            jpg = ExifParser.insertExif(jpg, seg)
        }

        val baseName = Ui.displayName(c, uri).substringBeforeLast('.')
        val tag = listOfNotNull(if (src != null) "doi" else null, o.preset?.slot?.ifBlank { null },
            if (delta != null) "da" else null, if (o.defringe > 0) "vien" else null).joinToString("-").ifBlank { "chinh" }
        val outName = "${baseName}_LX-$tag.jpg"
        return Saved(saveJpeg(c, jpg, outName, "LookExif"), outName)
    }

    private fun appVersion(c: Context): String =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Ghi JPEG vào Pictures/[dir] qua MediaStore. */
    fun saveJpeg(c: Context, jpg: ByteArray, name: String, dir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + dir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val out = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("không tạo được file")
        c.contentResolver.openOutputStream(out)?.use { it.write(jpg) }
            ?: throw IllegalStateException("không ghi được file")
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            c.contentResolver.update(out, values, null, null)
        }
        return out
    }
}

/** Chia sẻ nhanh: chọn kích thước cho Zalo / Facebook, thêm chữ ký nếu bật. */
object Share {

    fun prompt(a: Activity, uri: Uri, name: String) {
        val options = arrayOf("Zalo (2560px)", "Facebook (2048px)", "Ảnh đầy đủ", "Không chia sẻ")
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Chia sẻ ảnh")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> share(a, uri, name, 2560)
                    1 -> share(a, uri, name, 2048)
                    2 -> share(a, uri, name, 0)
                }
            }
            .show()
    }

    /** @param size cạnh dài tối đa; 0 = giữ nguyên */
    fun share(a: Activity, uri: Uri, name: String, size: Int) {
        val wm = AppSettings.watermark(a) && AppSettings.watermarkText(a).isNotBlank()
        if (size == 0 && !wm) { send(a, uri); return }
        Ui.toast(a, "Đang chuẩn bị ảnh...")
        Thread {
            try {
                val head = Ui.readHead(a, uri, 512 * 1024)
                val orient = PhotoInfoBuilder.build("", head).orientation
                var b = Ui.decodeMax(a, uri, if (size == 0) 12000 else size * 2, orient)
                    ?: throw IllegalStateException("không mở được ảnh")
                if (size > 0 && maxOf(b.width, b.height) > size) {
                    val s = size.toFloat() / maxOf(b.width, b.height)
                    b = Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
                }
                if (!b.isMutable) b = b.copy(Bitmap.Config.ARGB_8888, true)
                if (wm) drawSignature(b, AppSettings.watermarkText(a))
                val bos = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                val out = Processor.saveJpeg(a, bos.toByteArray(),
                    name.substringBeforeLast('.') + "_share.jpg", "LookExif/Chia se")
                a.runOnUiThread { send(a, out) }
            } catch (t: Throwable) {
                a.runOnUiThread { Ui.toast(a, "Lỗi chia sẻ: ${t.message}") }
            }
        }.start()
    }

    private fun send(a: Activity, uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.startActivity(Intent.createChooser(i, "Chia sẻ qua"))
    }

    /** Chữ ký nhỏ góc dưới bên phải. */
    fun drawSignature(b: Bitmap, text: String) {
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 210
            textSize = maxOf(b.width, b.height) * 0.022f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setShadowLayer(textSize * 0.15f, 0f, textSize * 0.05f, Color.argb(140, 0, 0, 0))
            textAlign = Paint.Align.RIGHT
        }
        val m = maxOf(b.width, b.height) * 0.025f
        cv.drawText(text, b.width - m, b.height - m, p)
    }
}
