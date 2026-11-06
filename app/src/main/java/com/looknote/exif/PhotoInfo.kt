package com.looknote.exif

import java.util.Locale

/** Một nhóm thông số hiển thị trên thẻ ảnh. */
data class Section(val title: String, val rows: List<Pair<String, String>>, val highlight: Boolean = false)

data class PhotoInfo(
    val name: String,
    val sections: List<Section>,
    val note: String?,
    val orientation: Int
) {
    fun toText(): String {
        val sb = StringBuilder()
        sb.append("Ảnh: ").append(name).append('\n')
        for (s in sections) {
            sb.append('[').append(s.title).append("]\n")
            for ((k, v) in s.rows) sb.append("- ").append(k).append(": ").append(v).append('\n')
        }
        if (note != null) sb.append("Ghi chú: ").append(note).append('\n')
        return sb.toString()
    }
}

object PhotoInfoBuilder {

    private const val NA = "—"

    fun build(name: String, bytes: ByteArray): PhotoInfo {
        val p = ExifParser(bytes)
        val r = try { p.parse() } catch (e: Exception) { ExifParser.Result() }
        if (!r.found) {
            return PhotoInfo(name, emptyList(),
                "Không tìm thấy dữ liệu EXIF. Ảnh có thể đã bị nén lại hoặc xóa thông tin khi gửi qua mạng xã hội/Zalo/Messenger. Hãy dùng file gốc từ thẻ nhớ.", 1)
        }

        fun str(m: Map<Int, ExifParser.Entry>, tag: Int): String? =
            m[tag]?.let { p.string(it) }?.takeIf { it.isNotBlank() }
        fun num(m: Map<Int, ExifParser.Entry>, tag: Int): Long? = m[tag]?.let { p.firstLong(it) }
        fun signed(m: Map<Int, ExifParser.Entry>, tag: Int): String {
            val v = num(m, tag) ?: return NA
            val i = v.toInt()
            return if (i > 0) "+$i" else "$i"
        }

        val make = str(r.ifd0, 0x010F) ?: ""
        val model = str(r.ifd0, 0x0110) ?: ""
        val orientation = num(r.ifd0, 0x0112)?.toInt() ?: 1
        val isSony = make.uppercase(Locale.ROOT).startsWith("SONY")

        // ----- Máy & ống kính -----
        val lens = str(r.exif, 0xA434) ?: NA
        val focal = r.exif[0x920A]?.let { p.rational(it) }?.let { fmt(it, 0) + " mm" } ?: NA
        val date = str(r.exif, 0x9003) ?: str(r.ifd0, 0x0132) ?: NA
        val camera = listOf(
            "Máy ảnh" to (listOf(make, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { NA }),
            "Ống kính" to lens,
            "Tiêu cự" to focal,
            "Thời gian chụp" to date
        )

        // ----- Phơi sáng -----
        val shutter = r.exif[0x829A]?.let { p.rational(it) }?.let { shutterText(it) } ?: NA
        val fnum = r.exif[0x829D]?.let { p.rational(it) }?.let { "f/" + fmt(it, 1) } ?: NA
        val iso = (num(r.exif, 0x8827)?.takeIf { it in 1..65534 } ?: num(r.exif, 0x8832))?.toString() ?: NA
        val ev = r.exif[0x9204]?.let { p.rational(it) }?.let { evText(it) } ?: NA
        val metering = when (num(r.exif, 0x9207)?.toInt()) {
            1 -> "Average"; 2 -> "Center"; 3 -> "Spot"; 4 -> "Multi-spot"
            5 -> "Multi"; 6 -> "Partial / Highlight"; null -> NA; else -> "Khác"
        }
        val program = when (num(r.exif, 0x8822)?.toInt()) {
            1 -> "M (thủ công)"; 2 -> "P"; 3 -> "A (ưu tiên khẩu)"; 4 -> "S (ưu tiên tốc)"
            null -> NA; else -> "Tự động / khác"
        }
        val exposure = listOf(
            "Chế độ" to program,
            "Tốc độ" to shutter,
            "Khẩu độ" to fnum,
            "ISO" to iso,
            "Bù sáng" to ev,
            "Đo sáng" to metering
        )

        val sections = ArrayList<Section>()
        if (!isSony || r.sony.isEmpty()) {
            sections += Section("Phơi sáng", exposure)
            sections += Section("Máy & ống kính", camera)
            val note = if (!isSony) "Ảnh không phải của máy Sony nên không có thông số Creative Look."
            else "Không đọc được MakerNote của Sony. Ảnh có thể đã bị chỉnh sửa hoặc thu nhỏ (ví dụ chuyển qua Creators' App ở kích thước 2MP)."
            return PhotoInfo(name, sections, note, orientation)
        }

        val s = r.sony

        // ----- Creative Look -----
        val lookRaw = str(s, 0xB020)
        val look = lookRaw?.let { lookName(it) } ?: NA
        val creative = listOf(
            "Creative Look" to look,
            "Contrast" to signed(s, 0x2004),
            "Highlights" to signed(s, 0x2033),
            "Shadows" to signed(s, 0x2032),
            "Fade" to (num(s, 0x2034)?.toString() ?: NA),
            "Saturation" to signed(s, 0x2005),
            "Sharpness" to signed(s, 0x2006),
            "Sharpness Range" to (num(s, 0x2035)?.toString() ?: NA),
            "Clarity" to signed(s, 0x2036)
        )

        // ----- Cân bằng trắng -----
        val wbCode = num(s, 0x0115)?.toInt()
        val kelvin = num(s, 0xB021)
        val wbName = when (wbCode) {
            0x00 -> "AWB"
            0x01 -> "Nhiệt độ màu / Color Filter"
            0x10 -> "Daylight"; 0x20 -> "Cloudy"; 0x30 -> "Shade"
            0x40 -> "Incandescent"; 0x50 -> "Flash"; 0x60 -> "Fluorescent"
            0x70 -> "Custom"; 0x80 -> "Underwater"
            null -> NA; else -> "Mã $wbCode"
        }
        val kelvinText = when {
            kelvin == null -> NA
            kelvin == 0L -> "Tự động"
            kelvin == 0xFFFFFFFFL -> NA
            else -> "${kelvin}K"
        }
        var ab: Double? = null; var gm: Double? = null
        val precise = s[0x2026]
        if (precise != null && precise.count >= 2) {
            val v = p.ints(precise)
            ab = v[0].toInt() / 1000.0; gm = v[1].toInt() / 1000.0
        } else s[0x2014]?.takeIf { it.count >= 2 }?.let {
            val v = p.ints(it)
            ab = v[0].toInt().toDouble(); gm = v[1].toInt().toDouble()
        }
        val wb = listOf(
            "Chế độ WB" to wbName,
            "Nhiệt độ màu" to kelvinText,
            "WB Shift A–B" to (ab?.let { shiftText(it, "A", "B") } ?: NA),
            "WB Shift G–M" to (gm?.let { shiftText(it, "M", "G") } ?: NA)
        )

        // ----- Dải động & khử nhiễu -----
        val dro = when (num(s, 0xB025)?.toInt()) {
            0 -> "Off"; 1 -> "Standard"; 2 -> "Advanced Auto"; 3 -> "Auto"
            8 -> "Advanced Lv1"; 9 -> "Advanced Lv2"; 10 -> "Advanced Lv3"; 11 -> "Advanced Lv4"; 12 -> "Advanced Lv5"
            16 -> "Lv1"; 17 -> "Lv2"; 18 -> "Lv3"; 19 -> "Lv4"; 20 -> "Lv5"
            null -> NA; else -> "Mã " + num(s, 0xB025)
        }
        val hdr = s[0x200A]?.let {
            when (val c = p.u16At(it, 0)) {
                0 -> "Off"; 1 -> "Auto"
                in 0x10..0x1A -> String.format(Locale.US, "%.1f EV", 1.0 + (c - 0x10) * 0.5)
                else -> "Mã $c"
            }
        } ?: NA
        val nr = when (num(s, 0x2009)?.toInt()) {
            0 -> "Off"; 1 -> "Low"; 2 -> "Normal"; 3 -> "High"; 256 -> "Auto"
            null, 65535 -> NA; else -> "Mã " + num(s, 0x2009)
        }
        val dr = listOf(
            "DRO" to dro,
            "Auto HDR" to hdr,
            "High ISO NR" to nr
        )

        sections += Section("Creative Look", creative, highlight = true)
        sections += Section("Cân bằng trắng", wb, highlight = true)
        sections += Section("Phơi sáng", exposure)
        sections += Section("Dải động & khử nhiễu", dr)
        sections += Section("Máy & ống kính", camera)
        return PhotoInfo(name, sections, null, orientation)
    }

    fun lookName(raw: String): String = when (raw) {
        "Standard" -> "ST (Standard)"
        "Portrait" -> "PT (Portrait)"
        "Neutral" -> "NT (Neutral)"
        "Vivid" -> "VV (Vivid)"
        "VV2" -> "VV2"
        "FL" -> "FL"
        "IN" -> "IN"
        "SH" -> "SH"
        "BW" -> "BW (Đen trắng)"
        "Sepia" -> "SE (Sepia)"
        else -> raw
    }

    fun shiftText(v: Double, posLetter: String, negLetter: String): String = when {
        v == 0.0 -> "0"
        v > 0 -> posLetter + fmt(v, 2)
        else -> negLetter + fmt(-v, 2)
    }

    fun shutterText(t: Double): String {
        if (t <= 0) return NA
        return if (t >= 0.3) fmt(t, 1) + "s" else "1/" + Math.round(1.0 / t) + "s"
    }

    fun evText(v: Double): String {
        if (Math.abs(v) < 0.05) return "0 EV"
        return (if (v > 0) "+" else "") + String.format(Locale.US, "%.1f", v) + " EV"
    }

    /** In số gọn: bỏ phần thập phân thừa. */
    fun fmt(v: Double, maxDec: Int): String {
        var s = String.format(Locale.US, "%." + maxDec + "f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }
}
