package com.looknote.exif

/**
 * Bộ đọc EXIF tối giản, không dùng thư viện ngoài.
 * Hỗ trợ JPEG (APP1 Exif), TIFF/ARW và HEIF (tìm khối TIFF trong file).
 * Đọc thêm MakerNote của Sony để lấy Creative Look và các thông số màu.
 */
class ExifParser(private val b: ByteArray) {

    private var base = 0
    private var le = true

    data class Entry(val tag: Int, val type: Int, val count: Int, val pos: Int, val le: Boolean)

    class Result {
        val ifd0 = HashMap<Int, Entry>()
        val exif = HashMap<Int, Entry>()
        val sony = HashMap<Int, Entry>()
        var found = false
    }

    private lateinit var res: Result

    fun parse(): Result {
        res = Result()
        val tiff = findTiff() ?: return res
        base = tiff
        le = b[base] == 'I'.code.toByte()
        val ifd0Off = u32(base + 4).toInt()
        readIfd(base + ifd0Off, res.ifd0)
        res.found = res.ifd0.isNotEmpty()
        res.ifd0[0x8769]?.let { e -> readIfd(base + firstLong(e).toInt(), res.exif) }
        res.exif[0x927C]?.let { e -> readMakerNote(e) }
        return res
    }

    // ---------- tìm vị trí khối TIFF ----------
    private fun findTiff(): Int? {
        if (b.size > 4 && ub(0) == 0xFF && ub(1) == 0xD8) {
            var i = 2
            while (i + 4 < b.size) {
                if (ub(i) != 0xFF) { i++; continue }
                val marker = ub(i + 1)
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xFF) { i++; continue }
                if (marker == 0xDA || marker == 0xD9) break
                val len = (ub(i + 2) shl 8) or ub(i + 3)
                if (marker == 0xE1 && i + 10 < b.size &&
                    b[i + 4] == 'E'.code.toByte() && b[i + 5] == 'x'.code.toByte() &&
                    b[i + 6] == 'i'.code.toByte() && b[i + 7] == 'f'.code.toByte()
                ) {
                    val t = i + 10
                    if (validTiff(t)) return t
                }
                i += 2 + len
            }
        }
        // TIFF / ARW / HEIF: dò tìm header "II*\0" hoặc "MM\0*"
        var p = 0
        while (p + 8 < b.size) {
            val isII = ub(p) == 0x49 && ub(p + 1) == 0x49 && ub(p + 2) == 0x2A && ub(p + 3) == 0x00
            val isMM = ub(p) == 0x4D && ub(p + 1) == 0x4D && ub(p + 2) == 0x00 && ub(p + 3) == 0x2A
            if ((isII || isMM) && validTiff(p)) return p
            p++
        }
        return null
    }

    private fun validTiff(t: Int): Boolean {
        if (t + 8 > b.size) return false
        val saveBase = base; val saveLe = le
        base = t; le = ub(t) == 0x49
        val ok = try {
            val off = u32(t + 4)
            if (off < 8 || t + off + 2 > b.size) false
            else {
                val n = u16(t + off.toInt())
                n in 1..400
            }
        } catch (e: Exception) { false }
        base = saveBase; le = saveLe
        return ok
    }

    // ---------- đọc IFD ----------
    private fun readIfd(abs: Int, into: HashMap<Int, Entry>) {
        if (abs < 0 || abs + 2 > b.size) return
        val n = u16(abs)
        if (n <= 0 || n > 1000) return
        for (k in 0 until n) {
            val e = abs + 2 + k * 12
            if (e + 12 > b.size) break
            val tag = u16(e)
            val type = u16(e + 2)
            val count = u32(e + 4)
            val size = typeSize(type)
            if (size == 0 || count <= 0 || count > 10_000_000) continue
            val total = size.toLong() * count
            val pos = if (total <= 4) e + 8 else (base + u32(e + 8)).toInt()
            if (pos < 0 || pos + total > b.size) continue
            into[tag] = Entry(tag, type, count.toInt(), pos, le)
        }
    }

    private fun readMakerNote(e: Entry) {
        val p = e.pos
        val hdr = String(b, p, minOf(12, b.size - p), Charsets.ISO_8859_1)
        val start = if (hdr.startsWith("SONY DSC") || hdr.startsWith("SONY CAM") ||
            hdr.startsWith("SONY MOBILE") || hdr.startsWith("VHAB")) p + 12 else p
        if (start + 2 > b.size) return
        val saveLe = le
        val n = u16(start)
        if (n !in 1..500) {
            // thử đảo thứ tự byte
            le = !le
            val n2 = u16(start)
            if (n2 !in 1..500) { le = saveLe; return }
        }
        readIfd(start, res.sony)
        le = saveLe
    }

    // ---------- đọc giá trị ----------
    fun ints(e: Entry): List<Long> {
        le = e.le
        val out = ArrayList<Long>()
        val sz = typeSize(e.type)
        for (k in 0 until minOf(e.count, 64)) {
            val q = e.pos + k * sz
            out += when (e.type) {
                1, 7 -> ub(q).toLong()
                6 -> b[q].toLong()
                3 -> u16(q).toLong()
                8 -> u16(q).toShort().toLong()
                4 -> u32(q)
                9 -> u32(q).toInt().toLong()
                else -> 0L
            }
        }
        return out
    }

    fun firstLong(e: Entry): Long = ints(e).firstOrNull() ?: 0L

    /** Đọc 2 số int16 không dấu nằm trong ô 32-bit (dùng cho thẻ HDR của Sony). */
    fun u16At(e: Entry, index: Int): Int { le = e.le; return u16(e.pos + index * 2) }

    fun rational(e: Entry, index: Int = 0): Double? {
        if (e.type != 5 && e.type != 10) return null
        if (index >= e.count) return null
        le = e.le
        val q = e.pos + index * 8
        val num = u32(q); val den = u32(q + 4)
        return if (e.type == 10) {
            val n = num.toInt().toDouble(); val d = den.toInt().toDouble()
            if (d == 0.0) null else n / d
        } else {
            if (den == 0L) null else num.toDouble() / den.toDouble()
        }
    }

    fun string(e: Entry): String {
        val len = minOf(e.count, b.size - e.pos)
        if (len <= 0) return ""
        return String(b, e.pos, len, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    }

    // ---------- tiện ích ----------
    private fun ub(i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(i: Int): Int =
        if (le) ub(i) or (ub(i + 1) shl 8) else (ub(i) shl 8) or ub(i + 1)
    private fun u32(i: Int): Long =
        if (le) (ub(i).toLong() or (ub(i + 1).toLong() shl 8) or (ub(i + 2).toLong() shl 16) or (ub(i + 3).toLong() shl 24))
        else ((ub(i).toLong() shl 24) or (ub(i + 1).toLong() shl 16) or (ub(i + 2).toLong() shl 8) or ub(i + 3).toLong())

    private fun typeSize(t: Int): Int = when (t) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 0
    }
}
