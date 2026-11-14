package com.looknote.exif

/**
 * Kiểm tra ảnh nguồn trước khi đổi màu: những thiết lập lúc chụp mà app không gỡ chính xác được,
 * và ảnh đã từng chỉnh bằng app hay chưa (tránh áp màu chồng màu).
 */
object SourceCheck {
    /** Dấu ghi vào thẻ Software khi ảnh đã áp màu dựng sẵn / LUT (giữ đúng độ dài chuỗi phiên bản). */
    const val BUILT_MARK = "Sony look+"

    class Info(val edited: Boolean, val builtColor: Boolean, val warnings: List<String>)

    fun check(head: ByteArray): Info {
        val p = ExifParser(head)
        val r = try { p.parse() } catch (e: Exception) { return Info(false, false, emptyList()) }
        val sw = r.ifd0[0x0131]?.let { p.string(it) } ?: ""
        val built = sw.startsWith(BUILT_MARK)
        val edited = built || sw.startsWith("Sony look") || sw.startsWith("LookExif")
        val w = ArrayList<String>()
        if (built) w += "Ảnh đã được app áp màu dựng sẵn (Fuji/LUT). App sẽ không gỡ màu lần nữa; màu mới sẽ chồng lên màu cũ — nên chỉnh từ ảnh gốc trong thẻ nhớ."
        else if (edited) w += "Ảnh đã chỉnh bằng app trước đây; nên chỉnh từ ảnh gốc trong thẻ nhớ để màu chuẩn nhất."
        val s = r.sony
        fun n(tag: Int) = s[tag]?.let { p.firstLong(it) }
        if (!edited) {
            when (n(0xB025)?.toInt()) {
                2, 3 -> w += "DRO Auto: máy tự nâng vùng tối theo từng ảnh, app không gỡ được phần này nên vùng tối có thể lệch màu."
                10, 11, 12, 18, 19, 20 -> w += "DRO mức cao (Lv3–5): vùng tối đã bị nâng mạnh, gỡ màu chỉ gần đúng."
            }
            val hi = n(0x2033)?.toInt() ?: 0; val sh = n(0x2032)?.toInt() ?: 0
            if (kotlin.math.abs(hi) >= 6 || kotlin.math.abs(sh) >= 6)
                w += "Highlights/Shadows đặt mức cao (${if (hi > 0) "+" else ""}$hi / ${if (sh > 0) "+" else ""}$sh): chi tiết vùng sáng/tối đã bị nén, không lấy lại được."
            val wb = n(0x0115)?.toInt()
            if (wb != null && wb != 0) {
                val k = n(0xB021)
                val name = if (wb == 1 && k != null && k in 2000L..15000L) "${k}K" else when (wb) {
                    0x10 -> "Daylight"; 0x20 -> "Cloudy"; 0x30 -> "Shade"; 0x40 -> "Incandescent"; 0x60 -> "Fluorescent"
                    else -> "cố định" }
                w += "WB lúc chụp: $name (không phải AWB). Màu mới giữ tông ấm/lạnh này; chỉnh thêm bằng thanh WB nếu cần."
            }
        }
        return Info(edited, built, w)
    }
}
