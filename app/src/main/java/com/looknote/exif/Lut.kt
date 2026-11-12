package com.looknote.exif

import java.io.File
import java.io.InputStream

/** Bảng tra màu 3D đọc từ file .cube (chuẩn Adobe/Resolve). */
class Lut(val size: Int, val data: FloatArray, val minV: FloatArray, val maxV: FloatArray) {

    /** Tra màu (0..1) bằng nội suy ba chiều; ghi kết quả vào [out]. */
    fun lookup(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        fun pos(v: Float, ch: Int): Float {
            val t = (v - minV[ch]) / (maxV[ch] - minV[ch])
            return (t.coerceIn(0f, 1f)) * n1
        }
        val fr = pos(r, 0); val fg = pos(g, 1); val fb = pos(b, 2)
        val r0 = fr.toInt().coerceAtMost(n1 - 1); val g0 = fg.toInt().coerceAtMost(n1 - 1); val b0 = fb.toInt().coerceAtMost(n1 - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        for (ch in 0 until 3) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + ch]
            val c00 = v(r0, g0, b0) + (v(r0 + 1, g0, b0) - v(r0, g0, b0)) * dr
            val c10 = v(r0, g0 + 1, b0) + (v(r0 + 1, g0 + 1, b0) - v(r0, g0 + 1, b0)) * dr
            val c01 = v(r0, g0, b0 + 1) + (v(r0 + 1, g0, b0 + 1) - v(r0, g0, b0 + 1)) * dr
            val c11 = v(r0, g0 + 1, b0 + 1) + (v(r0 + 1, g0 + 1, b0 + 1) - v(r0, g0 + 1, b0 + 1)) * dr
            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            out[ch] = c0 + (c1 - c0) * db
        }
    }

    companion object {
        private val cache = HashMap<String, Lut>()

        /** Đọc (có bộ nhớ đệm) LUT từ đường dẫn file đã lưu trong app. */
        @Synchronized
        fun load(path: String): Lut? = cache[path] ?: try {
            (if (path.startsWith("film:")) FilmLook.lut(path.substring(5))!!
             else File(path).inputStream().use { parse(it) }).also { cache[path] = it }
        } catch (e: Exception) { null }

        /** Đọc file .cube; báo lỗi nếu không phải LUT 3D hợp lệ. */
        fun parse(input: InputStream): Lut {
            var size = 0
            val minV = floatArrayOf(0f, 0f, 0f); val maxV = floatArrayOf(1f, 1f, 1f)
            var vals = FloatArray(0); var n = 0
            input.bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val p = line.split(Regex("\\s+"))
                when (p[0].uppercase()) {
                    "TITLE" -> {}
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("Đây là LUT 1D, app chỉ hỗ trợ LUT 3D")
                    "LUT_3D_SIZE" -> { size = p[1].toInt(); require(size in 2..129) { "Kích thước LUT không hợp lệ" }; vals = FloatArray(size * size * size * 3) }
                    "DOMAIN_MIN" -> for (i in 0 until 3) minV[i] = p[i + 1].toFloat()
                    "DOMAIN_MAX" -> for (i in 0 until 3) maxV[i] = p[i + 1].toFloat()
                    else -> {
                        val f = p[0].toFloatOrNull() ?: return@forEachLine
                        require(size > 0) { "Thiếu dòng LUT_3D_SIZE" }
                        if (n + 3 <= vals.size && p.size >= 3) {
                            vals[n] = f; vals[n + 1] = p[1].toFloat(); vals[n + 2] = p[2].toFloat(); n += 3
                        }
                    }
                }
            }
            require(size > 0) { "Không phải file .cube" }
            require(n == vals.size) { "File .cube thiếu dữ liệu (${n / 3}/${vals.size / 3} điểm)" }
            return Lut(size, vals, minV, maxV)
        }
    }
}
