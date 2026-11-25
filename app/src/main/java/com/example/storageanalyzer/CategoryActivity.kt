package com.example.storageanalyzer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class CategoryActivity : Activity() {
    companion object { const val EXTRA = "category"; const val EXTRA_PATH = "path"; const val EXTRA_TITLE = "title"; const val EXTRA_PKG = "owner_pkg"; const val EXTRA_KIND = "kind" }

    private lateinit var category: String
    private val all = mutableListOf<FileItem>()
    private val shown = mutableListOf<FileItem>()
    private var oldestFirst = false
    private var askedPermission = false
    private lateinit var summary: TextView
    private lateinit var sortBtn: Button
    private lateinit var selectBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var adapter: Adapter
    private val thumbCache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(3)
    private var rootPath: String? = null
    private var ownerPkg: String? = null

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        category = intent.getStringExtra(EXTRA) ?: "Others"
        rootPath = intent.getStringExtra(EXTRA_PATH)
        ownerPkg = intent.getStringExtra(EXTRA_PKG)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 16)

        val title = TextView(this)
        title.text = intent.getStringExtra(EXTRA_TITLE) ?: category; title.textSize = 22f; title.setTextColor(Color.BLACK)
        summary = TextView(this); summary.textSize = 13f; summary.setPadding(8, 8, 8, 8)
        summary.setOnClickListener { if (!Environment.isExternalStorageManager()) requestAccess() }

        sortBtn = Button(this)
        sortBtn.text = "Sorted by: Largest first (tap for Oldest first)"
        sortBtn.setOnClickListener {
            oldestFirst = !oldestFirst
            sortBtn.text = if (oldestFirst) "Sorted by: Oldest first (tap for Largest first)" else "Sorted by: Largest first (tap for Oldest first)"
            refresh()
        }
        selectBtn = Button(this)
        selectBtn.setOnClickListener {
            val allChecked = shown.isNotEmpty() && shown.all { it.checked }
            shown.forEach { it.checked = !allChecked }
            adapter.notifyDataSetChanged(); updateButtons()
        }
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            shown[pos].checked = !shown[pos].checked
            adapter.notifyDataSetChanged(); updateButtons()
        }
        deleteBtn = Button(this)
        deleteBtn.setTextColor(Color.RED)
        deleteBtn.setOnClickListener { confirmDelete() }

        root.addView(title); root.addView(summary); root.addView(sortBtn); root.addView(selectBtn)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(deleteBtn)
        setContentView(root)
        updateButtons()
    }

    private fun requestAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onResume() {
        super.onResume()
        if (!Environment.isExternalStorageManager()) {
            summary.text = "Allow \"All files access\" for Storage Analyzer. (Tap here to open the setting.)"
            if (!askedPermission) { askedPermission = true; requestAccess() }
        } else {
            summary.text = "Scanning..."
            if (rootPath != null) loadFolder() else if (ownerPkg != null) loadOwned() else FileIndex.ensure { load() }
        }
    }

    // files in shared storage that were saved by one app (MediaStore owner), filtered by type
    private fun loadOwned() {
        val kind = intent.getStringExtra(EXTRA_KIND)
        Thread {
            val list = mutableListOf<FileItem>()
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?", arrayOf(ownerPkg), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val f = File(c.getString(0) ?: continue)
                    if (f.isFile && FileItem.categoryOf(f) == kind) list.add(FileItem(f, f.length(), f.lastModified()))
                }
            }
            runOnUiThread { all.clear(); all.addAll(list); refresh() }
        }.start()
    }

    private fun loadFolder() {
        Thread {
            val list = mutableListOf<FileItem>()
            File(rootPath!!).walkTopDown().forEach { f ->
                if (f.isFile) list.add(FileItem(f, f.length(), f.lastModified()))
            }
            runOnUiThread { all.clear(); all.addAll(list); refresh() }
        }.start()
    }

    private fun load() {
        all.clear()
        all.addAll(FileIndex.items.filter { it.category == category })
        refresh()
    }

    private fun refresh() {
        shown.clear()
        shown.addAll(if (oldestFirst) all.sortedBy { it.modified } else all.sortedByDescending { it.size })
        val scope = if (rootPath != null || ownerPkg != null) "files" else "files of 1 MB or more"
        summary.text = "${all.size} $scope (${fmt(all.sumOf { it.size })}).\n" +
            "Tap to select, then Delete. Deleted files cannot be recovered."
        adapter.notifyDataSetChanged()
        updateButtons()
    }

    private fun selected() = all.filter { it.checked }

    private fun updateButtons() {
        val sel = selected()
        deleteBtn.isEnabled = sel.isNotEmpty()
        deleteBtn.text = "Delete selected (${sel.size} files, ${fmt(sel.sumOf { it.size })})"
        selectBtn.text = if (shown.isNotEmpty() && shown.all { it.checked }) "Clear selection" else "Select all"
    }

    private fun confirmDelete() {
        val sel = selected()
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${sel.size} files?")
            .setMessage("This will permanently delete ${fmt(sel.sumOf { it.size })}. There is no undo and no recycle bin.")
            .setPositiveButton("Delete") { _, _ -> doDelete(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(sel: List<FileItem>) {
        Thread {
            var freed = 0L; var failed = 0
            val paths = mutableListOf<String>()
            val gone = mutableListOf<FileItem>()
            for (item in sel) {
                if (item.file.delete()) { freed += item.size; paths.add(item.file.path); gone.add(item) } else failed++
            }
            MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
            runOnUiThread {
                all.removeAll(gone.toSet())
                FileIndex.remove(gone)
                refresh()
                Toast.makeText(this, "Freed ${fmt(freed)}" + if (failed > 0) " ($failed could not be deleted)" else "", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun loadThumb(item: FileItem, iv: ImageView) {
        val key = item.file.path
        iv.tag = key
        val cached = thumbCache.get(key)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageDrawable(null)
        pool.execute {
            try {
                val size = Size(160, 160)
                val bmp = if (item.category == "Videos") ThumbnailUtils.createVideoThumbnail(item.file, size, null)
                          else ThumbnailUtils.createImageThumbnail(item.file, size, null)
                thumbCache.put(key, bmp)
                runOnUiThread { if (iv.tag == key) iv.setImageBitmap(bmp) }
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val item = shown[p]
            val base = Environment.getExternalStorageDirectory().path
            val row = LinearLayout(this@CategoryActivity)
            row.orientation = LinearLayout.HORIZONTAL; row.setPadding(8, 16, 8, 16)
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            val cb = CheckBox(this@CategoryActivity)
            cb.isChecked = item.checked; cb.isClickable = false; cb.isFocusable = false
            row.addView(cb)
            if (item.category == "Photos" || item.category == "Videos") {
                val iv = ImageView(this@CategoryActivity)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(Color.parseColor("#E0E0E0"))
                row.addView(iv, LinearLayout.LayoutParams(128, 128))
                loadThumb(item, iv)
            }
            val col = LinearLayout(this@CategoryActivity)
            col.orientation = LinearLayout.VERTICAL; col.setPadding(16, 0, 0, 0)
            val t1 = TextView(this@CategoryActivity); t1.text = "${item.file.name}  -  ${fmt(item.size)}"; t1.textSize = 15f; t1.setTextColor(Color.BLACK)
            val t2 = TextView(this@CategoryActivity)
            t2.text = "${DateFormat.getDateInstance().format(Date(item.modified))}  |  ${item.file.parent?.removePrefix(base) ?: ""}"
            t2.textSize = 12f; t2.setTextColor(Color.DKGRAY)
            col.addView(t1); col.addView(t2)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
    }
}
