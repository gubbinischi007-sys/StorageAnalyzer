package com.example.storageanalyzer

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * Why does Settings show more Photos/Videos storage than I can see?
 * Finds: gallery trash (.trashed-*), unfinished files (.pending-*), trash folders,
 * thumbnail caches and media hidden from the Gallery by a .nomedia file.
 */
class HiddenActivity : Activity() {
    private class Entry(val file: File, val kind: String, val size: Long, val count: Int) {
        var checked = false
    }

    private val items = mutableListOf<Entry>()
    private var scanning = false
    private var askedPermission = false
    private lateinit var gap: TextView
    private lateinit var summary: TextView
    private lateinit var deleteBtn: Button
    private lateinit var adapter: Adapter

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)
    private fun nested(f: File, others: Collection<File>) = others.any { it != f && f.path.startsWith(it.path + "/") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 16)
        val title = TextView(this)
        title.text = "Hidden & trash"; title.textSize = 22f; title.setTextColor(Color.BLACK)
        gap = TextView(this); gap.textSize = 13f; gap.setPadding(8, 8, 8, 8)
        summary = TextView(this); summary.textSize = 13f; summary.setPadding(8, 8, 8, 8)
        summary.setOnClickListener { if (!Environment.isExternalStorageManager()) requestAccess() }
        val refresh = Button(this)
        refresh.text = "Refresh media index"
        refresh.setOnClickListener { refreshIndex() }
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            items[pos].checked = !items[pos].checked
            adapter.notifyDataSetChanged(); updateButton()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = items[pos]
            if (e.file.isDirectory) startActivity(Intent(this, CategoryActivity::class.java)
                .putExtra(CategoryActivity.EXTRA_PATH, e.file.path)
                .putExtra(CategoryActivity.EXTRA_TITLE, e.file.name))
            true
        }
        deleteBtn = Button(this)
        deleteBtn.setTextColor(Color.RED)
        deleteBtn.setOnClickListener { confirmDelete() }
        root.addView(title); root.addView(gap); root.addView(summary); root.addView(refresh)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(deleteBtn)
        setContentView(root)
        updateButton()
    }

    private fun requestAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    override fun onResume() {
        super.onResume()
        if (!Environment.isExternalStorageManager()) {
            summary.text = "Allow \"All files access\" for Storage Analyzer. (Tap here to open the setting.)"
            if (!askedPermission) { askedPermission = true; requestAccess() }
        } else {
            showGap()
            if (!scanning) scan()
        }
    }

    // Compare Android's own Photos+Videos figure (what Settings uses) with what we can see in normal folders
    private fun showGap() {
        if (!hasUsageAccess()) {
            gap.text = "To compare with Android's figure, allow Usage access (open the Apps page once)."
            return
        }
        FileIndex.ensure {
            try {
                val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                val ext = ssm.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
                val android = ext.imageBytes + ext.videoBytes
                val found = (FileIndex.totalBytes["Photos"] ?: 0L) + (FileIndex.totalBytes["Videos"] ?: 0L)
                gap.text = "Android counts ${fmt(android)} for Photos + Videos.\nWe can see ${fmt(found)} in normal folders.\n" +
                    (if (android > found) "Difference: ${fmt(android - found)} - usually trash, hidden media, thumbnails, unfinished files or media inside app folders."
                     else "The figures match.")
            } catch (e: Exception) {
                gap.text = "Could not read Android's storage figure on this phone."
            }
        }
    }

    private fun scan() {
        scanning = true
        summary.text = "Scanning for trash and hidden media..."
        val keep = items.filter { it.checked }.map { it.file.path }.toSet()
        Thread {
            val root = Environment.getExternalStorageDirectory()
            val special = mutableListOf<Pair<File, String>>()   // trash folders + thumbnail caches
            val trashedFiles = mutableListOf<File>()
            val pendingFiles = mutableListOf<File>()
            val noMediaDirs = mutableSetOf<File>()
            root.walkTopDown()
                .onEnter { d ->
                    val skipAndroid = d.parentFile == root && d.name == "Android"
                    if (!skipAndroid && d != root) {
                        val n = d.name.lowercase()
                        if (n == ".thumbnails") special.add(Pair(d, "Thumbnail cache"))
                        else if (n.contains("trash") || n.contains("recycle") || n.contains("recently deleted"))
                            special.add(Pair(d, "Trash folder"))
                    }
                    !skipAndroid
                }
                .forEach { f ->
                    if (f.isFile) {
                        if (f.name.startsWith(".trashed-")) trashedFiles.add(f)
                        else if (f.name.startsWith(".pending-")) pendingFiles.add(f)
                        else if (f.name == ".nomedia") f.parentFile?.let { noMediaDirs.add(it) }
                    }
                }

            fun measure(d: File, mediaOnly: Boolean): Pair<Long, Int> {
                var size = 0L; var count = 0
                d.walkTopDown().forEach { f ->
                    if (f.isFile && (!mediaOnly || FileItem.categoryOf(f).let { it == "Photos" || it == "Videos" })) { size += f.length(); count++ }
                }
                return Pair(size, count)
            }

            val specialDirs = special.map { it.first }.filter { !nested(it, special.map { s -> s.first }) }
            val found = mutableListOf<Entry>()
            for ((d, kind) in special) {
                if (d !in specialDirs) continue
                val (s, c) = measure(d, false)
                if (s > 0) found.add(Entry(d, kind, s, c))
            }
            for (f in trashedFiles) if (!nested(f, specialDirs) && f.length() > 0) found.add(Entry(f, "Trashed in Gallery (still using space)", f.length(), 1))
            for (f in pendingFiles) if (!nested(f, specialDirs) && f.length() > 0) found.add(Entry(f, "Unfinished download or capture", f.length(), 1))
            val hidden = noMediaDirs.filter { !nested(it, specialDirs) && !nested(it, noMediaDirs) }
            for (d in hidden) {
                val (s, c) = measure(d, true)
                if (s >= 5L * 1024 * 1024) found.add(Entry(d, "Hidden from Gallery (.nomedia) - check first", s, c))
            }
            found.sortWith(compareBy<Entry> { it.kind.startsWith("Hidden") }.thenByDescending { it.size })
            found.forEach { it.checked = it.file.path in keep }
            runOnUiThread {
                scanning = false
                items.clear(); items.addAll(found)
                summary.text = if (found.isEmpty()) "Nothing found. If Settings still looks too high, try Refresh media index and restart the phone."
                else "${found.size} items (${fmt(found.sumOf { it.size })}).\n" +
                    "Trash, unfinished files and thumbnails are safe to delete. Hidden media (.nomedia) belongs to some app - long-press a folder to look inside first.\n" +
                    "Nothing is selected automatically."
                adapter.notifyDataSetChanged()
                updateButton()
            }
        }.start()
    }

    // ask Android to re-read photos and videos so stale entries disappear and Settings figures catch up
    private fun refreshIndex() {
        FileIndex.ensure {
            val paths = FileIndex.items.filter { it.category == "Photos" || it.category == "Videos" }.map { it.file.path }
            MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
            Toast.makeText(this, "Media index refreshed. Settings can take a few minutes to update.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateButton() {
        val sel = items.filter { it.checked }
        deleteBtn.isEnabled = sel.isNotEmpty()
        deleteBtn.text = "Delete selected (${sel.size} items, ${fmt(sel.sumOf { it.size })})"
    }

    private fun confirmDelete() {
        val sel = items.filter { it.checked }
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${sel.size} items?")
            .setMessage("This permanently deletes ${fmt(sel.sumOf { it.size })}. Trashed photos and videos cannot be restored afterwards.")
            .setPositiveButton("Delete") { _, _ -> doDelete(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(sel: List<Entry>) {
        Thread {
            var freed = 0L; var failed = 0
            val gone = mutableListOf<Entry>()
            for (e in sel) {
                if (e.file.deleteRecursively()) { freed += e.size; gone.add(e) } else failed++
            }
            MediaScannerConnection.scanFile(this, gone.map { it.file.path }.toTypedArray(), null, null)
            runOnUiThread {
                items.removeAll(gone.toSet())
                FileIndex.scanned = false
                adapter.notifyDataSetChanged(); updateButton(); showGap()
                Toast.makeText(this, "Freed ${fmt(freed)}" + if (failed > 0) " ($failed could not be deleted)" else "", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val e = items[p]
            val base = Environment.getExternalStorageDirectory().path
            val row = LinearLayout(this@HiddenActivity)
            row.orientation = LinearLayout.HORIZONTAL; row.setPadding(8, 20, 8, 20)
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            val cb = CheckBox(this@HiddenActivity)
            cb.isChecked = e.checked; cb.isClickable = false; cb.isFocusable = false
            val col = LinearLayout(this@HiddenActivity)
            col.orientation = LinearLayout.VERTICAL; col.setPadding(16, 0, 0, 0)
            val t1 = TextView(this@HiddenActivity)
            t1.text = "${e.file.path.removePrefix(base)}  -  ${fmt(e.size)}"; t1.textSize = 14f; t1.setTextColor(Color.BLACK)
            val t2 = TextView(this@HiddenActivity)
            t2.text = "${e.kind}  |  ${e.count} files"; t2.textSize = 12f
            t2.setTextColor(if (e.kind.startsWith("Hidden")) Color.DKGRAY else Color.parseColor("#C62828"))
            col.addView(t1); col.addView(t2)
            row.addView(cb); row.addView(col)
            return row
        }
    }
}
