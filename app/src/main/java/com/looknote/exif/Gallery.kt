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

    /** Danh sách ảnh chọn để chỉnh hàng loạt. */
    var batch: List<Uri> = emptyList()

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

/** Thông tin đã quét của một ảnh (lưu lại để không phải đọc lại). */
class Meta(
    var flags: Int, var look: String, var iso: Int, var lens: String,
    var date: Int,      // yyyyMMdd, 0 nếu không rõ
    var q: Int = -1     // -1 chưa kiểm tra lỗi; bit: 1 nhắm mắt, 2 nhòe, 4 không có mặt
)

/**
 * Phân loại ảnh: ảnh gốc máy ảnh hay không, đã qua chỉnh sửa chưa, Creative Look, ISO, ống kính, ngày chụp;
 * và kiểm tra lỗi (nhắm mắt, nhòe). Kết quả được lưu lại để lần sau không phải đọc lại file.
 */
object CameraScan {
    const val SCANNED = 1
    const val CAMERA = 2
    const val EDITED = 4
    const val Q_EYES = 1
    const val Q_BLUR = 2
    const val Q_NOFACE = 4

    private val meta = HashMap<Long, Meta>()
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
            val f = java.io.File(c.filesDir, "scan2.txt")
            if (f.exists()) f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 7) meta[p[0].toLong()] = Meta(p[1].toInt(), p[2], p[3].toInt(), p[4], p[5].toInt(), p[6].toInt())
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun save(c: android.content.Context) {
        try {
            val sb = StringBuilder()
            for ((k, m) in meta) sb.append(k).append('\t').append(m.flags).append('\t').append(m.look).append('\t')
                .append(m.iso).append('\t').append(m.lens.replace('\t', ' ')).append('\t').append(m.date).append('\t').append(m.q).append('\n')
            java.io.File(c.filesDir, "scan2.txt").writeText(sb.toString())
        } catch (e: Exception) { }
    }

    @Synchronized fun get(id: Long): Meta? = meta[id]
    @Synchronized fun put(id: Long, m: Meta) { meta[id] = m }
    @Synchronized fun allLooks(): List<String> = meta.values.map { it.look }.filter { it.isNotBlank() }.distinct().sorted()
    @Synchronized fun allLenses(): List<String> = meta.values.map { it.lens }.filter { it.isNotBlank() }.distinct().sorted()

    fun isOriginal(item: MediaItem): Boolean {
        val f = get(item.id)?.flags ?: return false
        return (f and CAMERA) != 0 && (f and EDITED) == 0 && !isEditedName(item.name)
    }

    fun isEditedName(name: String) = name.contains("_LX-")

    /** Ảnh có lỗi: nhắm mắt hoặc nhòe. */
    fun isFaulty(item: MediaItem): Boolean {
        val q = get(item.id)?.q ?: return false
        return q > 0 && (q and (Q_EYES or Q_BLUR)) != 0
    }

    /** Đọc EXIF phần đầu file và phân loại. */
    fun classify(c: android.content.Context, item: MediaItem): Meta {
        var v = SCANNED
        var look = ""; var iso = 0; var lens = ""; var date = 0
        try {
            val bytes = Ui.readHead(c, item.uri, 128 * 1024)
            val p = ExifParser(bytes)
            val r = p.parse()
            val make = r.ifd0[0x010F]?.let { p.string(it) }?.uppercase() ?: ""
            val soft = r.ifd0[0x0131]?.let { p.string(it) }?.lowercase() ?: ""
            if (make.isNotBlank() && brands.any { make.startsWith(it) }) v = v or CAMERA
            if (editors.any { soft.contains(it) }) v = v or EDITED
            r.sony[0xB020]?.let { look = PhotoInfoBuilder.lookName(p.string(it)).substringBefore(" ") }
            iso = (r.exif[0x8827]?.let { p.firstLong(it) }?.takeIf { it in 1..65534 }
                ?: r.exif[0x8832]?.let { p.firstLong(it) } ?: 0L).toInt()
            lens = r.exif[0xA434]?.let { p.string(it) } ?: ""
            val d = r.exif[0x9003]?.let { p.string(it) } ?: r.ifd0[0x0132]?.let { p.string(it) } ?: ""
            if (d.length >= 10) date = (d.substring(0, 4) + d.substring(5, 7) + d.substring(8, 10)).toIntOrNull() ?: 0
        } catch (e: Exception) { }
        if (isEditedName(item.name)) v = v or EDITED
        val old = get(item.id)
        return Meta(v, look, iso, lens, date, old?.q ?: -1)
    }

    /** Kiểm tra lỗi: nhắm mắt (ML Kit) và độ nét vùng mặt. */
    fun quality(c: android.content.Context, item: MediaItem): Int {
        return try {
            val head = Ui.readHead(c, item.uri, 256 * 1024)
            val orient = PhotoInfoBuilder.build("", head).orientation
            val b = Ui.decodeMax(c, item.uri, 1000, orient) ?: return Q_NOFACE
            val scan = FaceFinder.analyze(b, fast = true)
            var q = 0
            if (scan.eyesClosed > 0) q = q or Q_EYES
            val f = scan.largest
            if (f == null) q = q or Q_NOFACE
            else if (f.width() >= 80 && FaceFinder.sharpness(b, f) < Assistant.BLUR_LIMIT) q = q or Q_BLUR
            b.recycle()
            q
        } catch (e: Throwable) { Q_NOFACE }
    }
}
