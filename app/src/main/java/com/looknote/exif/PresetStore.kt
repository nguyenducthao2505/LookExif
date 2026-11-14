package com.looknote.exif

/** Lưu / đọc danh sách công thức (thêm, sửa, hiệu chỉnh) vào bộ nhớ của app. */
object PresetStore {
    private var loaded = false

    @Synchronized
    fun load(c: android.content.Context) {
        if (loaded) return
        loaded = true
        try {
            val f = java.io.File(c.filesDir, "presets.json")
            if (!f.exists()) return
            val arr = org.json.JSONArray(f.readText())
            val list = ArrayList<Preset>()
            for (i in 0 until arr.length()) {
                val p = fromJson(arr.getJSONObject(i))
                val q = Presets.renamed[p.name]?.let { p.copy(name = it) } ?: p
                // màu phim ước lượng: cập nhật tên theo bản mới (ghi rõ "ước lượng")
                list += if (q.lutFile == "film:fuji_400h" || q.lutFile == "film:fuji_superia")
                    FilmLook.presets().firstOrNull { it.lutFile == q.lutFile }?.let { d -> q.copy(name = d.name) } ?: q else q
            }
            if (list.isNotEmpty()) {
                // công thức mặc định mới ra ở bản sau: thêm vào cuối (chỉ một lần, xóa rồi thì không thêm lại)
                val seenFile = java.io.File(c.filesDir, "presets_seen.txt")
                val seen = if (seenFile.exists()) seenFile.readLines().toMutableSet()
                    else Presets.defaults.take(7).map { it.slot + "|" + it.name }.toMutableSet()
                for (d in Presets.defaults) {
                    val key = d.slot + "|" + d.name
                    if (key !in seen && list.none { it.name == d.name }) list += d
                    seen += key
                }
                seenFile.writeText(seen.joinToString("\n"))
                Presets.all = list
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun save(c: android.content.Context, list: List<Preset>) {
        Presets.all = list
        try {
            val arr = org.json.JSONArray()
            for (p in list) arr.put(toJson(p))
            java.io.File(c.filesDir, "presets.json").writeText(arr.toString())
        } catch (e: Exception) { }
    }


    private fun arr(a: FloatArray) = org.json.JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
    private fun farr(j: org.json.JSONArray?) = j?.let { FloatArray(it.length()) { i -> it.getDouble(i).toFloat() } }

    fun toJson(p: Preset) = org.json.JSONObject().apply {
        put("slot", p.slot); put("name", p.name); put("look", p.look)
        put("c", p.contrast); put("h", p.highlights); put("s", p.shadows); put("f", p.fade); put("sat", p.saturation)
        put("ab", p.ab); put("gm", p.gm); put("ev", p.ev); put("wb", p.wbNote)
        put("fs", arr(p.filmShadow)); put("fh", arr(p.filmHighlight))
        p.calib?.let { put("calib", arr(it)) }
        put("calibSat", p.calibSat.toDouble())
        p.lutFile?.let { put("lut", it) }
        p.calibInv?.let { put("cinv", it) }
        p.calibFwd?.let { put("cfwd", it) }
    }

    fun fromJson(o: org.json.JSONObject) = Preset(
        o.optString("slot"), o.optString("name"), o.optString("look", "ST"),
        o.optInt("c"), o.optInt("h"), o.optInt("s"), o.optInt("f"), o.optInt("sat"),
        o.optDouble("ab", 0.0), o.optDouble("gm", 0.0), o.optDouble("ev", 0.0), o.optString("wb", "AWB"),
        farr(o.optJSONArray("fs")) ?: floatArrayOf(0f, 0f, 0f),
        farr(o.optJSONArray("fh")) ?: floatArrayOf(0f, 0f, 0f),
        farr(o.optJSONArray("calib")),
        o.optDouble("calibSat", 1.0).toFloat(),
        if (o.has("lut")) o.getString("lut") else null,
        if (o.has("cinv")) o.getString("cinv") else null,
        if (o.has("cfwd")) o.getString("cfwd") else null
    )
}
