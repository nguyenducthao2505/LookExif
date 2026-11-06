package com.looknote.exif

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors

data class MediaItem(val id: Long, val uri: Uri, val name: String, val bucket: String)

/** Truy vấn ảnh trong máy và giữ danh sách đang xem để màn chi tiết vuốt qua lại. */
object Gallery {
    /** Danh sách ảnh màn chi tiết đang duyệt (theo thứ tự lưới hoặc theo file đã chọn). */
    var current: List<Uri> = emptyList()

    fun query(c: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val sort = MediaStore.Images.Media.DATE_ADDED + " DESC"
        try {
            c.contentResolver.query(coll, proj, null, null, sort)?.use { cur ->
                val iId = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iBucket = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cur.moveToNext()) {
                    val id = cur.getLong(iId)
                    out += MediaItem(
                        id,
                        ContentUris.withAppendedId(coll, id),
                        cur.getString(iName) ?: "Ảnh",
                        cur.getString(iBucket) ?: "Khác"
                    )
                }
            }
        } catch (e: Exception) { }
        return out
    }
}

/** Ô vuông trong lưới ảnh. */
class SquareFrame(c: Context) : FrameLayout(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Nạp ảnh thu nhỏ và nhãn Creative Look ở luồng nền, có bộ nhớ đệm. */
class ThumbLoader(private val c: Context) {
    private val cache: LruCache<Long, Bitmap>
    private val looks = HashMap<Long, String>()
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    init {
        val kb = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
        cache = object : LruCache<Long, Bitmap>(kb) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun bind(item: MediaItem, iv: ImageView, badge: TextView) {
        iv.tag = item.id
        val bmp = cache.get(item.id)
        val look = synchronized(looks) { looks[item.id] }
        iv.setImageBitmap(bmp)
        showBadge(badge, look)
        if (bmp != null && look != null) return
        exec.execute {
            val b = bmp ?: try { thumb(item) } catch (e: Throwable) { null }
            val l = look ?: try { readLook(item.uri) } catch (e: Throwable) { "" }
            if (b != null) cache.put(item.id, b)
            synchronized(looks) { looks[item.id] = l }
            main.post {
                if (iv.tag == item.id) {
                    if (b != null) iv.setImageBitmap(b)
                    showBadge(badge, l)
                }
            }
        }
    }

    private fun showBadge(badge: TextView, look: String?) {
        if (look.isNullOrEmpty()) {
            badge.visibility = android.view.View.GONE
        } else {
            badge.text = look
            badge.visibility = android.view.View.VISIBLE
        }
    }

    private fun thumb(item: MediaItem): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 29) {
            c.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                c.contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        }
    }

    /** Đọc phần đầu file để lấy tên Creative Look (rỗng nếu không phải ảnh Sony). */
    private fun readLook(u: Uri): String {
        val bytes = Ui.readHead(c, u, 256 * 1024)
        val p = ExifParser(bytes)
        val r = p.parse()
        val e = r.sony[0xB020] ?: return ""
        val raw = p.string(e)
        if (raw.isBlank()) return ""
        return PhotoInfoBuilder.lookName(raw).substringBefore(" ")
    }

    fun shutdown() = exec.shutdownNow()
}

/**
 * Phân loại ảnh: ảnh gốc máy ảnh hay không, đã qua chỉnh sửa hay chưa.
 * Kết quả được lưu lại để lần sau không phải đọc lại file.
 */
object CameraScan {
    const val SCANNED = 1
    const val CAMERA = 2
    const val EDITED = 4

    private val flags = HashMap<Long, Int>()
    private var loaded = false
    private val brands = listOf(
        "SONY", "CANON", "FUJIFILM", "NIKON", "PANASONIC", "OLYMPUS", "OM DIGITAL",
        "LEICA", "RICOH", "PENTAX", "HASSELBLAD", "SIGMA", "KODAK"
    )
    private val editors = listOf(
        "lightroom", "photoshop", "snapseed", "picsart", "vsco", "meitu", "capcut",
        "lookexif", "gimp", "affinity", "luminar", "polarr", "facetune"
    )

    @Synchronized
    fun load(c: android.content.Context) {
        if (loaded) return
        loaded = true
        try {
            val f = java.io.File(c.filesDir, "scan.txt")
            if (f.exists()) f.forEachLine { line ->
                val p = line.split(',')
                if (p.size == 2) flags[p[0].toLong()] = p[1].toInt()
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun save(c: android.content.Context) {
        try {
            val sb = StringBuilder()
            for ((k, v) in flags) sb.append(k).append(',').append(v).append('\n')
            java.io.File(c.filesDir, "scan.txt").writeText(sb.toString())
        } catch (e: Exception) { }
    }

    @Synchronized
    fun get(id: Long): Int? = flags[id]

    @Synchronized
    fun put(id: Long, v: Int) { flags[id] = v }

    fun isOriginal(item: MediaItem): Boolean {
        val f = get(item.id) ?: return false
        return (f and CAMERA) != 0 && (f and EDITED) == 0 && !isEditedName(item.name)
    }

    fun isEditedName(name: String) = name.contains("_LX-")

    /** Đọc phần đầu file và phân loại. */
    fun classify(c: android.content.Context, item: MediaItem): Int {
        var v = SCANNED
        try {
            val bytes = Ui.readHead(c, item.uri, 128 * 1024)
            val p = ExifParser(bytes)
            val r = p.parse()
            val make = r.ifd0[0x010F]?.let { p.string(it) }?.uppercase() ?: ""
            val soft = r.ifd0[0x0131]?.let { p.string(it) }?.lowercase() ?: ""
            if (make.isNotBlank() && brands.any { make.startsWith(it) }) v = v or CAMERA
            if (editors.any { soft.contains(it) }) v = v or EDITED
        } catch (e: Exception) { }
        if (isEditedName(item.name)) v = v or EDITED
        return v
    }
}
