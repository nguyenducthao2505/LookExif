package com.looknote.exif

import java.util.Locale

/** Số liệu thô của một ảnh để trợ lý chụp đánh giá. */
data class Shot(
    val shutter: Double? = null,     // giây
    val fnum: Double? = null,
    val iso: Int? = null,
    val focal: Double? = null,       // mm
    val ev: Double? = null,
    val look: String? = null,
    val highlights: Int? = null,
    val shadows: Int? = null,
    val ab: Double? = null,
    val gm: Double? = null,
    val faces: Int = 0,
    val eyesClosed: Int = 0,
    val sharpness: Double? = null,
    val clipHigh: Float = 0f,
    val clipLow: Float = 0f
)

/** Trợ lý chụp: soi lỗi kỹ thuật và gợi ý chỉnh thông số cho lần chụp sau. */
object Assistant {

    const val BLUR_LIMIT = 40.0

    fun tips(s: Shot): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun pct(f: Float) = String.format(Locale.US, "%.1f", f * 100).replace('.', ',') + "%"

        // chuyển động: chụp người, nhất là trẻ em
        val t = s.shutter
        if (t != null && t > 0) {
            val inv = Math.round(1.0 / t)
            if (s.faces > 0 && t > 1.0 / 250) {
                out += "Tốc độ chậm" to "1/${inv}s dễ nhòe khi người, nhất là trẻ em, cử động. Nên ≥1/250s: đặt Auto ISO tốc độ tối thiểu là 1/250."
            }
            val f = s.focal
            if (f != null && f > 0 && t > 1.0 / (f * 1.5)) {
                out += "Dễ rung tay" to "1/${inv}s với ống ${f.toInt()}mm dễ rung tay. Nên ≥1/${(f * 2).toInt()}s, hoặc bật chống rung."
            }
        }
        if (s.eyesClosed > 0) {
            out += "Nhắm mắt" to "Có ${s.eyesClosed} người nhắm mắt. Chụp liên tiếp (Drive mode Hi) để có tấm mắt mở."
        }
        val sh = s.sharpness
        if (sh != null && sh < BLUR_LIMIT) {
            out += "Có thể bị nhòe / trượt nét" to "Độ nét vùng mặt thấp. Dùng AF-C + nhận diện mắt, tăng tốc độ, hoặc khép khẩu 1 nấc (f/2.8)."
        }
        if (s.clipHigh > 0.02f) {
            out += "Cháy sáng ${pct(s.clipHigh)}" to "Giảm bù sáng 0,3–0,7 EV hoặc chỉnh Highlights -2. Bật Zebra 100% khi chụp để thấy trước."
        }
        if (s.clipLow > 0.05f) {
            out += "Vùng tối mất chi tiết ${pct(s.clipLow)}" to "Tăng Shadows (+1 đến +2) hoặc DRO Lv2–3; tránh Shadows quá thấp."
        }
        val shd = s.shadows
        if (shd != null && shd <= -6) {
            out += "Shadows $shd quá thấp" to "Tóc đen và áo tối dễ thành mảng đen. Nên dùng -2 đến -4."
        }
        val iso = s.iso
        if (iso != null && iso >= 6400) {
            out += "ISO $iso cao" to "Ảnh dễ nhiễu. Mở khẩu lớn hơn (f/1.8), hạ tốc độ nếu chủ thể đứng yên, bật High ISO NR: Low."
        }
        val ab = s.ab
        if (ab != null && kotlin.math.abs(ab) >= 5) {
            val dir = if (ab < 0) "xanh (B)" else "vàng (A)"
            out += "WB lệch mạnh" to "WB Shift lệch ${dir} ${PhotoInfoBuilder.fmt(kotlin.math.abs(ab), 1)} nấc. Ổn dưới đèn vàng, nhưng ngoài trời da sẽ ám màu."
        }
        val ev = s.ev
        if (ev != null && ev >= 1.0 && s.clipHigh > 0.01f) {
            out += "Bù sáng cao" to "Bù sáng +${PhotoInfoBuilder.fmt(ev, 1)} EV làm vùng sáng dễ cháy. Thử +0,3 đến +0,7."
        }
        if (out.isEmpty()) out += "Ổn" to "Không thấy lỗi kỹ thuật đáng kể."
        return out
    }
}
