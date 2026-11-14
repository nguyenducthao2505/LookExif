package com.looknote.exif

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Calendar

/** Màn hình chính: lưới ảnh, lọc, chọn nhiều ảnh để chỉnh hàng loạt. */
class MainActivity : Activity() {

    companion object {
        private const val PICK = 42
        private const val REQ_PERM = 7
        private const val ALL = "Tất cả ảnh"
        private val TYPES = listOf("Mọi loại ảnh", "Ảnh gốc máy ảnh", "Ảnh đã chỉnh", "Ảnh lỗi (nhắm mắt, nhòe)")
        private val ISO_OPTS = listOf("Mọi ISO", "ISO ≤ 400", "ISO 401–1600", "ISO 1601–6400", "ISO > 6400")
        private val DATE_OPTS = listOf("Mọi ngày", "Hôm nay", "7 ngày qua", "30 ngày qua", "Năm nay")
    }

    private lateinit var grid: GridView
    private lateinit var spinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var moreBtn: Button
    private lateinit var status: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var filterBox: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectLabel: TextView
    private lateinit var loader: ThumbLoader

    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var buckets: List<String> = listOf(ALL)
    private var selectedBucket = ALL
    private var typeFilter = 0
    private var lookFilter: String? = null
    private var lensFilter: String? = null
    private var isoFilter = 0
    private var dateFilter = 0
    private var scanGen = 0
    private val selected = LinkedHashSet<Long>()

    private val readPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.darkBars(this)
        PresetStore.load(this)
        loader = ThumbLoader(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
        }

        // ----- Tiêu đề -----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(4))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "SONY LOOK"; textSize = 24f; typeface = Film.display; letterSpacing = 0.12f
            setTextColor(Ui.ink); maxLines = 1
        })
        titleCol.addView(TextView(this).apply {
            text = "PHÒNG TỐI MÀU PHIM · v" + Processor.appVersion(this@MainActivity)
            textSize = 10f; typeface = Film.mono; letterSpacing = 0.08f; setTextColor(Ui.accent); maxLines = 1
        })
        titleRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Film.iconButton(this, IconDrawable.Kind.FILM, "Công thức") { startActivity(Intent(this, PresetActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)).apply { rightMargin = dp(6) })
        titleRow.addView(Film.iconButton(this, IconDrawable.Kind.SETTINGS, "Cài đặt") { startActivity(Intent(this, SettingsActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)).apply { rightMargin = dp(6) })
        titleRow.addView(Film.iconButton(this, IconDrawable.Kind.FOLDER, "Mở file") { pick() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)))
        header.addView(titleRow)
        header.addView(Film.sprockets(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(12) })

        // ----- Bộ lọc -----
        filterBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        spinner = makeSpinner(listOf(ALL))
        typeSpinner = makeSpinner(TYPES)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(spinner, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        row1.addView(typeSpinner, LinearLayout.LayoutParams(0, dp(42), 1f))
        filterBox.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) })
        moreBtn = Ui.button(this, "Lọc theo thông số", false) { showMoreFilters() }
        filterBox.addView(moreBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(8) })
        header.addView(filterBox)

        // ----- Thanh chọn nhiều -----
        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        selectLabel = TextView(this).apply { textSize = 14f; setTextColor(Ui.ink); setTypeface(typeface, Typeface.BOLD) }
        selectBar.addView(selectLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectBar.addView(Ui.button(this, "Chọn hết", false) { selected.addAll(shown.map { it.id }); refreshSelection() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(6) })
        selectBar.addView(Ui.button(this, "Hủy", false) { selected.clear(); refreshSelection() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(6) })
        selectBar.addView(Ui.button(this, "Chỉnh hàng loạt", true) { openBatch() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        header.addView(selectBar)

        status = TextView(this).apply { textSize = 12.5f; setTextColor(Ui.mute); setPadding(dp(2), dp(8), 0, 0) }
        header.addView(status)
        root.addView(header)

        // ----- Xin quyền -----
        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        permBox.addView(TextView(this).apply {
            text = "Cho phép Sony look đọc ảnh trong máy để hiện toàn bộ ảnh như một thư viện.\n\n" +
                "App chỉ đọc ảnh, không sửa, không xóa và không gửi ảnh đi đâu."
            textSize = 14f; setTextColor(Ui.mute); gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
        })
        permBox.addView(Ui.button(this, "Cấp quyền đọc ảnh", true) { askPermission() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { topMargin = dp(18) })
        root.addView(permBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ----- Lưới ảnh -----
        grid = GridView(this).apply {
            numColumns = columnsFor(resources.configuration)
            horizontalSpacing = dp(3); verticalSpacing = dp(3)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(3), dp(3), dp(3), dp(3))
            clipToPadding = false
            adapter = GridAdapter()
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                if (selected.isNotEmpty()) toggle(pos) else openDetail(pos)
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ -> toggle(pos); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        spinner.onItemSelectedListener = listener { pos ->
            val b = buckets.getOrNull(pos) ?: ALL
            if (b != selectedBucket) { selectedBucket = b; applyFilter(); ensureScans() }
        }
        typeSpinner.onItemSelectedListener = listener { pos ->
            if (pos != typeFilter) { typeFilter = pos; applyFilter(); ensureScans() }
        }

        handleIntent(intent)
        if (!hasPermission()) askPermission()
    }

    private fun makeSpinner(items: List<String>) = Spinner(this).apply {
        background = Ui.rounded(this@MainActivity, Ui.field, 10, Ui.line)
        setPadding(dp(10), 0, dp(10), 0)
        adapter = darkAdapter(items)
    }

    private fun darkAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Ui.ink); textSize = 13.5f }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(Ui.ink); setBackgroundColor(Ui.card); setPadding(dp(14), dp(12), dp(14), dp(12))
            }
    }

    private fun listener(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = f(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun columnsFor(c: Configuration): Int {
        val w = c.screenWidthDp
        return when { w >= 1200 -> 7; w >= 960 -> 6; w >= 760 -> 5; w >= 560 -> 4; else -> 3 }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.numColumns = columnsFor(newConfig)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) reload() else showPermBox(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanGen++
        loader.shutdown()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) {
            selected.clear(); refreshSelection()
        } else {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---------- quyền ----------
    private fun hasPermission() = checkSelfPermission(readPerm) == PackageManager.PERMISSION_GRANTED
    private fun askPermission() = requestPermissions(arrayOf(readPerm), REQ_PERM)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) { if (hasPermission()) reload() else showPermBox(true) }
    }

    private fun showPermBox(show: Boolean) {
        permBox.visibility = if (show) View.VISIBLE else View.GONE
        grid.visibility = if (show) View.GONE else View.VISIBLE
        filterBox.visibility = if (show) View.GONE else View.VISIBLE
        if (show) status.text = ""
    }

    // ---------- dữ liệu ----------
    private fun reload() {
        showPermBox(false)
        if (all.isEmpty()) status.text = "Đang tải danh sách ảnh..."
        Thread {
            CameraScan.load(this)
            val items = Gallery.query(this)
            runOnUiThread {
                all = items
                val counts = LinkedHashMap<String, Int>()
                for (it in items) counts[it.bucket] = (counts[it.bucket] ?: 0) + 1
                val names = ArrayList<String>()
                names += ALL
                names += counts.keys.sortedByDescending { counts[it] ?: 0 }
                buckets = names
                val labels = names.map { if (it == ALL) "$ALL (${items.size})" else "$it (${counts[it]})" }
                spinner.adapter = darkAdapter(labels)
                val idx = buckets.indexOf(selectedBucket).takeIf { it >= 0 } ?: 0
                selectedBucket = buckets[idx]
                spinner.setSelection(idx)
                applyFilter()
                ensureScans()
            }
        }.start()
    }

    private val extraFilters get() = listOfNotNull(lookFilter, lensFilter,
        if (isoFilter > 0) "iso" else null, if (dateFilter > 0) "date" else null).size

    private fun passExtra(m: Meta?): Boolean {
        if (extraFilters == 0) return true
        if (m == null) return false
        lookFilter?.let { if (m.look != it) return false }
        lensFilter?.let { if (m.lens != it) return false }
        when (isoFilter) {
            1 -> if (m.iso !in 1..400) return false
            2 -> if (m.iso !in 401..1600) return false
            3 -> if (m.iso !in 1601..6400) return false
            4 -> if (m.iso <= 6400) return false
        }
        if (dateFilter > 0) {
            if (m.date == 0) return false
            val cal = Calendar.getInstance()
            fun ymd(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
            val today = ymd(cal)
            val from = when (dateFilter) {
                1 -> today
                2 -> { cal.add(Calendar.DAY_OF_YEAR, -6); ymd(cal) }
                3 -> { cal.add(Calendar.DAY_OF_YEAR, -29); ymd(cal) }
                else -> cal.get(Calendar.YEAR) * 10000 + 101
            }
            if (m.date < from) return false
        }
        return true
    }

    private fun applyFilter(scanStatus: String? = null) {
        val inBucket = if (selectedBucket == ALL) all else all.filter { it.bucket == selectedBucket }
        shown = inBucket.filter { item ->
            val ok = when (typeFilter) {
                1 -> CameraScan.isOriginal(item)
                2 -> CameraScan.isEditedName(item.name)
                3 -> CameraScan.isFaulty(item)
                else -> true
            }
            ok && passExtra(CameraScan.get(item.id))
        }
        moreBtn.text = if (extraFilters > 0) "Lọc theo thông số ($extraFilters đang bật)" else "Lọc theo thông số"
        status.text = when {
            scanStatus != null -> "${shown.size} ảnh · $scanStatus"
            shown.isEmpty() -> "Không có ảnh nào"
            typeFilter == 1 -> "${shown.size} ảnh gốc chụp từ máy ảnh"
            typeFilter == 2 -> "${shown.size} ảnh đã chỉnh bằng Sony look"
            typeFilter == 3 -> "${shown.size} ảnh có thể lỗi (nhắm mắt hoặc nhòe)"
            else -> "${shown.size} ảnh · Giữ lâu một ảnh để chọn nhiều"
        }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    /** Quét EXIF (và kiểm tra lỗi nếu cần) ở nền, cập nhật lưới dần dần. */
    private fun ensureScans(forceMeta: Boolean = false) {
        val needMeta = forceMeta || typeFilter == 1 || extraFilters > 0
        val needQuality = typeFilter == 3
        if (!needMeta && !needQuality) return
        val gen = ++scanGen
        val inBucket = if (selectedBucket == ALL) all else all.filter { it.bucket == selectedBucket }
        Thread {
            CameraScan.load(this)
            val todo = inBucket.filter { CameraScan.get(it.id) == null || (needQuality && CameraScan.get(it.id)!!.q < 0) }
            var done = 0
            for (item in todo) {
                if (gen != scanGen) break
                var m = CameraScan.get(item.id) ?: CameraScan.classify(this, item)
                if (needQuality && m.q < 0) m.q = CameraScan.quality(this, item)
                CameraScan.put(item.id, m)
                done++
                val every = if (needQuality) 5 else 40
                if (done % every == 0 || done == todo.size) {
                    if (done % 200 == 0) CameraScan.save(this)
                    val msg = if (done < todo.size)
                        (if (needQuality) "Đang kiểm tra lỗi ảnh $done/${todo.size}..." else "Đang quét thông số $done/${todo.size}...")
                    else null
                    runOnUiThread { if (gen == scanGen) applyFilter(msg) }
                }
            }
            CameraScan.save(this)
            runOnUiThread { if (gen == scanGen) applyFilter() }
        }.start()
    }

    private fun showMoreFilters() {
        CameraScan.load(this)
        val looks = listOf("Mọi Creative Look") + CameraScan.allLooks()
        val lenses = listOf("Mọi ống kính") + CameraScan.allLenses()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val sLook = makeSpinner(looks).apply { setSelection(lookFilter?.let { looks.indexOf(it) }?.takeIf { it > 0 } ?: 0) }
        val sLens = makeSpinner(lenses).apply { setSelection(lensFilter?.let { lenses.indexOf(it) }?.takeIf { it > 0 } ?: 0) }
        val sIso = makeSpinner(ISO_OPTS).apply { setSelection(isoFilter) }
        val sDate = makeSpinner(DATE_OPTS).apply { setSelection(dateFilter) }
        for (s in listOf(sLook, sLens, sIso, sDate)) box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        if (looks.size == 1) box.addView(TextView(this).apply {
            text = "Danh sách Creative Look và ống kính sẽ có sau khi app quét ảnh (bấm Áp dụng một lần)."
            textSize = 12f; setTextColor(Ui.mute); setPadding(0, dp(10), 0, 0)
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Lọc theo thông số")
            .setView(box)
            .setPositiveButton("Áp dụng") { _, _ ->
                lookFilter = if (sLook.selectedItemPosition > 0) looks[sLook.selectedItemPosition] else null
                lensFilter = if (sLens.selectedItemPosition > 0) lenses[sLens.selectedItemPosition] else null
                isoFilter = sIso.selectedItemPosition
                dateFilter = sDate.selectedItemPosition
                applyFilter()
                // quét để có danh sách Look / ống kính (lần đầu) hoặc để lọc
                ensureScans(forceMeta = true)
            }
            .setNeutralButton("Xóa lọc") { _, _ ->
                lookFilter = null; lensFilter = null; isoFilter = 0; dateFilter = 0; applyFilter()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ---------- chọn nhiều ----------
    private fun toggle(pos: Int) {
        val id = shown.getOrNull(pos)?.id ?: return
        if (!selected.remove(id)) selected.add(id)
        refreshSelection()
    }

    private fun refreshSelection() {
        selectBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        filterBox.visibility = if (selected.isEmpty()) View.VISIBLE else View.GONE
        selectLabel.text = "Đã chọn ${selected.size} ảnh"
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun openBatch() {
        val uris = all.filter { it.id in selected }.map { it.uri }
        if (uris.isEmpty()) return
        Gallery.batch = uris
        startActivity(Intent(this, BatchActivity::class.java))
        selected.clear(); refreshSelection()
    }

    private fun openDetail(pos: Int) {
        Gallery.current = shown.map { it.uri }
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_INDEX, pos))
    }

    // ---------- mở file / nhận ảnh chia sẻ ----------
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> streamExtra(i)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(i)?.let { uris += it }
            Intent.ACTION_VIEW -> i.data?.let { uris += it }
        }
        if (uris.isNotEmpty()) {
            Gallery.current = uris
            startActivity(Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_INDEX, 0)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, PICK) } catch (e: Exception) { Ui.toast(this, "Không mở được trình chọn file") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) { for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it } }
        else data.data?.let { uris += it }
        if (uris.isNotEmpty()) {
            Gallery.current = uris
            startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_INDEX, 0))
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    // ---------- adapter lưới ----------
    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell: SquareFrame
            val iv: ImageView
            val badge: TextView
            val qBadge: TextView
            val check: TextView
            if (convertView is SquareFrame) {
                cell = convertView
                iv = cell.getChildAt(0) as ImageView
                badge = cell.getChildAt(1) as TextView
                qBadge = cell.getChildAt(2) as TextView
                check = cell.getChildAt(3) as TextView
            } else {
                cell = SquareFrame(this@MainActivity).apply { setBackgroundColor(Ui.card) }
                iv = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                badge = TextView(this@MainActivity).apply {
                    textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                    background = Ui.rounded(this@MainActivity, Color.parseColor("#CCB5452F"), 6)
                    setPadding(dp(6), dp(1), dp(6), dp(2))
                }
                qBadge = TextView(this@MainActivity).apply {
                    textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                    background = Ui.rounded(this@MainActivity, Color.parseColor("#DDE5484D"), 6)
                    setPadding(dp(6), dp(1), dp(6), dp(2))
                }
                check = TextView(this@MainActivity).apply {
                    text = "✓"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Ui.onAccent)
                    background = Ui.rounded(this@MainActivity, Ui.accent, 12)
                }
                cell.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                cell.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
                cell.addView(qBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
                cell.addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
            }
            val item = shown[position]
            loader.bind(item, iv, badge)
            val q = CameraScan.get(item.id)?.q ?: -1
            val qText = when {
                q <= 0 -> null
                (q and CameraScan.Q_EYES) != 0 && (q and CameraScan.Q_BLUR) != 0 -> "Nhắm mắt · Nhòe"
                (q and CameraScan.Q_EYES) != 0 -> "Nhắm mắt"
                (q and CameraScan.Q_BLUR) != 0 -> "Nhòe"
                else -> null
            }
            qBadge.text = qText ?: ""
            qBadge.visibility = if (qText != null) View.VISIBLE else View.GONE
            val sel = item.id in selected
            check.visibility = if (sel) View.VISIBLE else View.GONE
            cell.foreground = if (sel) Ui.rounded(this@MainActivity, Color.parseColor("#33F0A23B"), 0, Ui.accent) else null
            return cell
        }
    }
}
