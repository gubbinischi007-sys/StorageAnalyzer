package com.example.storageanalyzer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class LeftoverActivity : Activity() {
    class Cand(val dir: File, val where: String, val size: Long, val count: Int, val likely: Boolean) {
        var checked = false
    }

    private val items = mutableListOf<Cand>()
    private var scanning = false
    private var askedPermission = false
    private lateinit var summary: TextView
    private lateinit var deleteBtn: Button
    private lateinit var adapter: Adapter

    // standard folders that must never be suggested
    private val protectedNames = setOf(
        "dcim", "download", "downloads", "pictures", "movies", "music", "documents", "android", "alarms",
        "notifications", "ringtones", "podcasts", "audiobooks", "recordings", "screenshots",
        "whatsapp", "whatsapp business"
    )
    private val packageStyle = Regex("^(com|org|net|io|in|app|me|co)\\.[a-z0-9_.]+$", RegexOption.IGNORE_CASE)

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 16)
        val title = TextView(this)
        title.text = "Leftover folders"; title.textSize = 22f; title.setTextColor(Color.BLACK)
        summary = TextView(this); summary.textSize = 13f; summary.setPadding(8, 8, 8, 8)
        summary.setOnClickListener { if (!Environment.isExternalStorageManager()) requestAccess() }
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            items[pos].checked = !items[pos].checked
            adapter.notifyDataSetChanged(); updateButton()
        }
        // long-press = look inside the folder before deciding
        list.setOnItemLongClickListener { _, _, pos, _ ->
            startActivity(Intent(this, CategoryActivity::class.java)
                .putExtra(CategoryActivity.EXTRA_PATH, items[pos].dir.path)
                .putExtra(CategoryActivity.EXTRA_TITLE, items[pos].dir.name))
            true
        }
        deleteBtn = Button(this)
        deleteBtn.setTextColor(Color.RED)
        deleteBtn.setOnClickListener { confirmDelete() }
        root.addView(title); root.addView(summary)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(deleteBtn)
        setContentView(root)
        updateButton()
    }

    private fun requestAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onResume() {
        super.onResume()
        if (!Environment.isExternalStorageManager()) {
            summary.text = "Allow \"All files access\" for Storage Analyzer. (Tap here to open the setting.)"
            if (!askedPermission) { askedPermission = true; requestAccess() }
        } else if (!scanning) scan()
    }

    private fun scan() {
        scanning = true
        summary.text = "Scanning folders..."
        val keep = items.filter { it.checked }.map { it.dir.path }.toSet()
        Thread {
            val pm = packageManager
            val installed = pm.getInstalledApplications(0)
            val pkgs = installed.map { it.packageName }.toSet()
            val keys = mutableSetOf<String>()
            for (ai in installed) {
                keys.add(norm(ai.loadLabel(pm).toString()))
                ai.packageName.split(".").forEach { keys.add(norm(it)) }
            }
            keys.removeAll { it.length < 3 }

            fun belongsToInstalled(name: String): Boolean {
                val n = norm(name)
                if (n.length < 3) return true   // too short to judge: never flag
                return keys.any { it == n || (n.length >= 4 && it.contains(n)) || (it.length >= 4 && n.contains(it)) }
            }
            fun measure(d: File): Pair<Long, Int> {
                var size = 0L; var count = 0
                d.walkTopDown().forEach { f -> if (f.isFile) { size += f.length(); count++ } }
                return Pair(size, count)
            }

            val found = mutableListOf<Cand>()
            val root = Environment.getExternalStorageDirectory()

            // 1) Android/media/<package> where that package is no longer installed = very likely leftover
            File(root, "Android/media").listFiles()?.forEach { d ->
                if (d.isDirectory && d.name !in pkgs) {
                    val (s, c) = measure(d)
                    if (s > 0) found.add(Cand(d, "Android/media", s, c, true))
                }
            }
            // 2) top-level folders of internal storage that match no installed app
            root.listFiles()?.forEach { d ->
                if (d.isDirectory && d.name.lowercase() !in protectedNames && !belongsToInstalled(d.name)) {
                    val (s, c) = measure(d)
                    if (s > 0) found.add(Cand(d, "Internal storage", s, c, packageStyle.matches(d.name) && d.name !in pkgs))
                }
            }
            found.sortWith(compareByDescending<Cand> { it.likely }.thenByDescending { it.size })
            found.forEach { it.checked = it.dir.path in keep }
            runOnUiThread {
                scanning = false
                items.clear(); items.addAll(found)
                summary.text = if (found.isEmpty())
                    "No leftover folders found."
                else
                    "${found.size} folders (${fmt(found.sumOf { it.size })}).\n" +
                        "Likely leftover = folder of an app that is no longer installed.\n" +
                        "Check first = name matches no installed app, but could be your own folder.\n" +
                        "Tap to select, long-press to look inside. Nothing is selected automatically."
                adapter.notifyDataSetChanged()
                updateButton()
            }
        }.start()
    }

    private fun updateButton() {
        val sel = items.filter { it.checked }
        deleteBtn.isEnabled = sel.isNotEmpty()
        deleteBtn.text = "Delete selected (${sel.size} folders, ${fmt(sel.sumOf { it.size })})"
    }

    private fun confirmDelete() {
        val sel = items.filter { it.checked }
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${sel.size} folders?")
            .setMessage("This permanently deletes the folders and everything inside them (${fmt(sel.sumOf { it.size })}). There is no undo.")
            .setPositiveButton("Delete") { _, _ -> doDelete(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(sel: List<Cand>) {
        Thread {
            var freed = 0L; var failed = 0
            val gone = mutableListOf<Cand>()
            for (c in sel) {
                if (c.dir.deleteRecursively()) { freed += c.size; gone.add(c) } else failed++
            }
            runOnUiThread {
                items.removeAll(gone.toSet())
                FileIndex.scanned = false   // module pages rescan next time they open
                adapter.notifyDataSetChanged(); updateButton()
                Toast.makeText(this, "Freed ${fmt(freed)}" + if (failed > 0) " ($failed could not be deleted)" else "", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val c = items[p]
            val row = LinearLayout(this@LeftoverActivity)
            row.orientation = LinearLayout.HORIZONTAL; row.setPadding(8, 20, 8, 20)
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            val cb = CheckBox(this@LeftoverActivity)
            cb.isChecked = c.checked; cb.isClickable = false; cb.isFocusable = false
            val col = LinearLayout(this@LeftoverActivity)
            col.orientation = LinearLayout.VERTICAL; col.setPadding(16, 0, 0, 0)
            val t1 = TextView(this@LeftoverActivity)
            t1.text = "${c.dir.name}  -  ${fmt(c.size)}"; t1.textSize = 15f; t1.setTextColor(Color.BLACK)
            val t2 = TextView(this@LeftoverActivity)
            t2.text = (if (c.likely) "Likely leftover" else "Check first") + "  |  ${c.where}  |  ${c.count} files"
            t2.textSize = 12f
            t2.setTextColor(if (c.likely) Color.parseColor("#C62828") else Color.DKGRAY)
            col.addView(t1); col.addView(t2)
            row.addView(cb); row.addView(col)
            return row
        }
    }
}
