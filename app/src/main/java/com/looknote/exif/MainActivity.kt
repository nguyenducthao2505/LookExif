package com.looknote.exif

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Màn hình chính: lưới toàn bộ ảnh trong máy, lọc theo thư mục. */
class MainActivity : Activity() {

    companion object {
        private const val PICK = 42
        private const val REQ_PERM = 7
        private const val ALL = "Tất cả ảnh"
    }

    private lateinit var grid: GridView
    private lateinit var spinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var loader: ThumbLoader

    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var buckets: List<String> = listOf(ALL)
    private var selectedBucket = ALL
    private var typeFilter = 0          // 0 tất cả, 1 ảnh gốc máy ảnh, 2 ảnh đã chỉnh
    private var scanGen = 0

    private val readPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        loader = ThumbLoader(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
        }

        // ----- Tiêu đề -----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(6))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "LookExif"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Ui.button(this, "Mở file", false) { pick() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        header.addView(titleRow)

        spinner = Spinner(this).apply {
            background = Ui.rounded(this@MainActivity, Color.WHITE, 10, Ui.line)
            setPadding(dp(10), 0, dp(10), 0)
        }
        typeSpinner = Spinner(this).apply {
            background = Ui.rounded(this@MainActivity, Color.WHITE, 10, Ui.line)
            setPadding(dp(10), 0, dp(10), 0)
            val ad = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                listOf("Mọi loại ảnh", "Ảnh gốc máy ảnh", "Ảnh đã chỉnh"))
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = ad
        }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(spinner, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        filterRow.addView(typeSpinner, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(filterRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(10) })

        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Ui.mute)
            setPadding(dp(2), dp(8), 0, 0)
        }
        header.addView(status)
        root.addView(header)

        // ----- Khung xin quyền -----
        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        permBox.addView(TextView(this).apply {
            text = "Cho phép LookExif đọc ảnh trong máy để hiện toàn bộ ảnh như một thư viện.\n\n" +
                "App chỉ đọc ảnh, không sửa, không xóa và không gửi ảnh đi đâu."
            textSize = 14f
            setTextColor(Ui.mute)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        })
        permBox.addView(Ui.button(this, "Cấp quyền đọc ảnh", true) { askPermission() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { topMargin = dp(18) })
        root.addView(permBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ----- Lưới ảnh -----
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(3)
            verticalSpacing = dp(3)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(3), dp(3), dp(3), dp(3))
            clipToPadding = false
            adapter = GridAdapter()
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> openDetail(pos) }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val b = buckets.getOrNull(position) ?: ALL
                if (b != selectedBucket) {
                    selectedBucket = b
                    applyFilter()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != typeFilter) {
                    typeFilter = position
                    applyFilter()
                    if (typeFilter == 1) startScan()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        handleIntent(intent)
        if (!hasPermission()) askPermission()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---------- quyền ----------
    private fun hasPermission(): Boolean =
        checkSelfPermission(readPerm) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() = requestPermissions(arrayOf(readPerm), REQ_PERM)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (hasPermission()) reload() else showPermBox(true)
        }
    }

    private fun showPermBox(show: Boolean) {
        permBox.visibility = if (show) View.VISIBLE else View.GONE
        grid.visibility = if (show) View.GONE else View.VISIBLE
        spinner.visibility = if (show) View.GONE else View.VISIBLE
        typeSpinner.visibility = if (show) View.GONE else View.VISIBLE
        if (show) status.text = ""
    }

    // ---------- dữ liệu ----------
    private fun reload() {
        showPermBox(false)
        status.text = "Đang tải danh sách ảnh..."
        Thread {
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
                val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = ad
                val idx = buckets.indexOf(selectedBucket).takeIf { it >= 0 } ?: 0
                selectedBucket = buckets[idx]
                spinner.setSelection(idx)
                applyFilter()
                if (typeFilter == 1) startScan()
            }
        }.start()
    }

    private fun applyFilter(scanStatus: String? = null) {
        val inBucket = if (selectedBucket == ALL) all else all.filter { it.bucket == selectedBucket }
        shown = when (typeFilter) {
            1 -> inBucket.filter { CameraScan.isOriginal(it) }
            2 -> inBucket.filter { CameraScan.isEditedName(it.name) }
            else -> inBucket
        }
        status.text = when {
            scanStatus != null -> "${shown.size} ảnh · $scanStatus"
            shown.isEmpty() -> "Không có ảnh nào"
            typeFilter == 1 -> "${shown.size} ảnh gốc chụp từ máy ảnh"
            typeFilter == 2 -> "${shown.size} ảnh đã chỉnh màu bằng LookExif"
            else -> "${shown.size} ảnh · Nhãn góc ảnh là Creative Look (chỉ ảnh Sony)"
        }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    /** Quét EXIF ở nền để nhận ra ảnh gốc máy ảnh; cập nhật lưới dần dần. */
    private fun startScan() {
        val gen = ++scanGen
        val inBucket = if (selectedBucket == ALL) all else all.filter { it.bucket == selectedBucket }
        val rest = all.filter { it.bucket != selectedBucket || selectedBucket == ALL }
        val order = (inBucket + rest).distinctBy { it.id }
        Thread {
            CameraScan.load(this)
            val todo = order.filter { CameraScan.get(it.id) == null }
            if (todo.isEmpty()) { runOnUiThread { if (gen == scanGen) applyFilter() }; return@Thread }
            var done = 0
            for (item in todo) {
                if (gen != scanGen) break
                CameraScan.put(item.id, CameraScan.classify(this, item))
                done++
                if (done % 40 == 0 || done == todo.size) {
                    if (done % 400 == 0) CameraScan.save(this)
                    val msg = if (done < todo.size) "Đang quét ảnh máy ảnh $done/${todo.size}..." else null
                    runOnUiThread { if (gen == scanGen && typeFilter == 1) applyFilter(msg) }
                }
            }
            CameraScan.save(this)
        }.start()
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
        try {
            startActivityForResult(i, PICK)
        } catch (e: Exception) {
            Ui.toast(this, "Không mở được trình chọn file")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it }
        } else data.data?.let { uris += it }
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
            if (convertView is SquareFrame) {
                cell = convertView
                iv = cell.getChildAt(0) as ImageView
                badge = cell.getChildAt(1) as TextView
            } else {
                cell = SquareFrame(this@MainActivity).apply { setBackgroundColor(Ui.line) }
                iv = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                badge = TextView(this@MainActivity).apply {
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = Ui.rounded(this@MainActivity, Color.parseColor("#CCB5452F"), 6)
                    setPadding(dp(6), dp(1), dp(6), dp(2))
                }
                cell.addView(iv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                cell.addView(badge, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply { setMargins(dp(5), 0, 0, dp(5)) })
            }
            loader.bind(shown[position], iv, badge)
            return cell
        }
    }
}
