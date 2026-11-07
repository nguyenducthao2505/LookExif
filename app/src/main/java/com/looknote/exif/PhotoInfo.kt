package com.looknote.exif

import java.util.Locale

/** Một nhóm thông số hiển thị trên thẻ ảnh. */
data class Section(val title: String, val rows: List<Pair<String, String>>, val highlight: Boolean = false)

data class PhotoInfo(
    val name: String,
    val sections: List<Section>,
    val note: String?,
    val orientation: Int,
    /** Số liệu thô cho trợ lý chụp. */
    val shot: Shot = Shot()
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

    /**
     * @param pixelW, pixelH kích thước thật của ảnh (đã xoay), nếu biết; 0 = lấy từ EXIF
     * @param fileSize dung lượng file (byte), nếu biết; 0 = không hiện
     */
    fun build(name: String, bytes: ByteArray, pixelW: Int = 0, pixelH: Int = 0, fileSize: Long = 0): PhotoInfo {
        val p = ExifParser(bytes)
        val r = try { p.parse() } catch (e: Exception) { ExifParser.Result() }
        if (!r.found) {
            val basic = ArrayList<Pair<String, String>>()
            if (pixelW > 0 && pixelH > 0) {
                basic += "Độ phân giải" to resolutionText(pixelW, pixelH)
                basic += "Tỉ lệ khung" to ratioText(pixelW, pixelH)
            }
            if (fileSize > 0) basic += "Dung lượng" to sizeText(fileSize)
            return PhotoInfo(name, if (basic.isEmpty()) emptyList() else listOf(Section("Ảnh", basic)),
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
        val date = dateText(str(r.exif, 0x9003) ?: str(r.ifd0, 0x0132))
        val camera = listOf(
            "Máy ảnh" to (listOf(make, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { NA }),
            "Ống kính" to lens,
            "Tiêu cự" to focal
        )

        // ----- Ảnh: thời gian, độ phân giải, dung lượng -----
        var pw = pixelW; var ph = pixelH
        if (pw <= 0 || ph <= 0) {
            val ew = num(r.exif, 0xA002)?.toInt() ?: 0
            val eh = num(r.exif, 0xA003)?.toInt() ?: 0
            val rot = orientation == 6 || orientation == 8
            pw = if (rot) eh else ew; ph = if (rot) ew else eh
        }
        val picture = ArrayList<Pair<String, String>>()
        picture += "Thời gian chụp" to date
        picture += "Độ phân giải" to resolutionText(pw, ph)
        if (pw > 0 && ph > 0) picture += "Tỉ lệ khung" to ratioText(pw, ph)
        if (fileSize > 0) picture += "Dung lượng" to sizeText(fileSize)
        val ext = name.substringAfterLast('.', "").uppercase()
        if (ext.isNotBlank() && ext.length <= 4) picture += "Định dạng" to ext
        str(r.ifd0, 0x0131)?.takeIf { it.startsWith("LookExif") }?.let { picture += "Đã chỉnh bằng" to it }

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

        val baseShot = Shot(
            shutter = r.exif[0x829A]?.let { p.rational(it) },
            fnum = r.exif[0x829D]?.let { p.rational(it) },
            iso = (num(r.exif, 0x8827)?.takeIf { it in 1..65534 } ?: num(r.exif, 0x8832))?.toInt(),
            focal = r.exif[0x920A]?.let { p.rational(it) },
            ev = r.exif[0x9204]?.let { p.rational(it) }
        )
        val sections = ArrayList<Section>()
        sections += Section("Ảnh", picture)
        if (!isSony || r.sony.isEmpty()) {
            sections += Section("Phơi sáng", exposure)
            sections += Section("Máy & ống kính", camera)
            val note = if (!isSony) "Ảnh không phải của máy Sony nên không có thông số Creative Look."
            else "Không đọc được MakerNote của Sony. Ảnh có thể đã bị chỉnh sửa hoặc thu nhỏ (ví dụ chuyển qua Creators' App ở kích thước 2MP)."
            return PhotoInfo(name, sections, note, orientation, baseShot)
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
            "Clarity" to signed(s, 0x2036),
            "Soft Skin Effect" to (num(s, 0x200F)?.let { v -> if (v in 0..3) SoftSkin.names[v.toInt()] else null } ?: NA)
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
        val shot = baseShot.copy(
            look = lookRaw?.let { lookName(it).substringBefore(" ") },
            highlights = num(s, 0x2033)?.toInt(),
            shadows = num(s, 0x2032)?.toInt(),
            ab = ab, gm = gm
        )
        return PhotoInfo(name, sections, null, orientation, shot)
    }

    /**
     * Dựng lại công thức màu đã dùng lúc chụp từ EXIF của máy Sony.
     * Trả về null nếu không phải ảnh Sony hoặc không có Creative Look.
     */
    fun sourcePreset(bytes: ByteArray): Preset? {
        val p = ExifParser(bytes)
        val r = try { p.parse() } catch (e: Exception) { return null }
        val s = r.sony
        if (s.isEmpty()) return null
        val raw = s[0xB020]?.let { p.string(it) } ?: return null
        val look = when (raw) {
            "Standard" -> "ST"; "Portrait" -> "PT"; "Neutral" -> "NT"; "Vivid" -> "VV"
            "BW" -> "BW"; "Sepia" -> "SE"; else -> raw
        }
        fun v(tag: Int) = s[tag]?.let { p.firstLong(it).toInt() } ?: 0
        var ab = 0.0; var gm = 0.0
        val precise = s[0x2026]
        if (precise != null && precise.count >= 2) {
            val x = p.ints(precise); ab = x[0].toInt() / 1000.0; gm = x[1].toInt() / 1000.0
        } else s[0x2014]?.takeIf { it.count >= 2 }?.let {
            val x = p.ints(it); ab = x[0].toInt().toDouble(); gm = x[1].toInt().toDouble()
        }
        return Preset("gốc", "Lúc chụp", look, v(0x2004), v(0x2033), v(0x2032), v(0x2034), v(0x2005),
            ab, gm, 0.0, "WB")
    }

    /** "2026:11:06 15:30:00" → "06/11/2026 15:30:00" */
    fun dateText(raw: String?): String {
        if (raw.isNullOrBlank()) return NA
        val m = Regex("""(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}:\d{2}:\d{2})""").find(raw) ?: return raw
        val (y, mo, d, t) = m.destructured
        return "$d/$mo/$y $t"
    }

    fun resolutionText(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return NA
        val mp = w.toLong() * h / 1_000_000.0
        return "$w × $h (" + fmt(mp, 1).replace('.', ',') + " MP)"
    }

    fun ratioText(w: Int, h: Int): String {
        val r = maxOf(w, h).toDouble() / minOf(w, h)
        val known = listOf(1.0 to "1:1", 1.25 to "5:4", 4.0 / 3 to "4:3", 1.5 to "3:2", 16.0 / 9 to "16:9", 2.0 to "2:1", 65.0 / 24 to "65:24")
        val best = known.minByOrNull { kotlin.math.abs(it.first - r) }!!
        if (kotlin.math.abs(best.first - r) > 0.03) return fmt(r, 2).replace('.', ',') + ":1"
        val parts = best.second.split(':')
        return if (w >= h) best.second else parts[1] + ":" + parts[0]
    }

    fun sizeText(b: Long): String = when {
        b >= 1024L * 1024 -> fmt(b / 1024.0 / 1024.0, 1).replace('.', ',') + " MB"
        b >= 1024 -> fmt(b / 1024.0, 0) + " KB"
        else -> "$b byte"
    }

    fun lookName(raw: String): String = when (raw) {
        "Standard", "ST" -> "ST (Standard)"
        "Portrait", "PT" -> "PT (Portrait)"
        "Neutral", "NT" -> "NT (Neutral)"
        "Vivid", "VV" -> "VV (Vivid)"
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
