package com.example.storageanalyzer

import android.app.Activity
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

class WhatsAppActivity : Activity() {
    private class Folder(val title: String, val dir: File, val size: Long, val count: Int, val sent: Long)

    private val folders = mutableListOf<Folder>()
    private lateinit var summary: TextView
    private lateinit var adapter: Adapter
    private var askedPermission = false
    private var scanning = false

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 16)
        val title = TextView(this)
        title.text = "WhatsApp media"; title.textSize = 22f; title.setTextColor(Color.BLACK)
        summary = TextView(this); summary.textSize = 13f; summary.setPadding(8, 8, 8, 16)
        summary.setOnClickListener { if (!Environment.isExternalStorageManager()) requestAccess() }
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val f = folders[pos]
            startActivity(Intent(this, CategoryActivity::class.java)
                .putExtra(CategoryActivity.EXTRA_PATH, f.dir.path)
                .putExtra(CategoryActivity.EXTRA_TITLE, f.title))
        }
        root.addView(title); root.addView(summary)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
        summary.text = "Scanning WhatsApp folders..."
        Thread {
            val root = Environment.getExternalStorageDirectory()
            val bases = listOf(
                "WhatsApp" to File(root, "Android/media/com.whatsapp/WhatsApp/Media"),
                "WhatsApp" to File(root, "WhatsApp/Media"),
                "WA Business" to File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"),
                "WA Business" to File(root, "WhatsApp Business/Media")
            )
            val result = mutableListOf<Folder>()
            for ((label, base) in bases) {
                val subs = base.listFiles() ?: continue
                for (d in subs) {
                    if (!d.isDirectory) continue
                    var size = 0L; var count = 0; var sent = 0L
                    d.walkTopDown().forEach { f ->
                        if (f.isFile) { val l = f.length(); size += l; count++; if (f.path.contains("/Sent/")) sent += l }
                    }
                    if (count > 0) result.add(Folder("$label - ${d.name.removePrefix("WhatsApp ").removePrefix(".")}", d, size, count, sent))
                }
            }
            result.sortByDescending { it.size }
            runOnUiThread {
                scanning = false
                folders.clear(); folders.addAll(result)
                adapter.notifyDataSetChanged()
                summary.text = if (result.isEmpty())
                    "No WhatsApp media folder found on this phone."
                else
                    "WhatsApp media: ${fmt(result.sumOf { it.size })} in ${result.size} folders.\n" +
                        "Sent copies (media you sent, saved again on this phone): ${fmt(result.sumOf { it.sent })}\n" +
                        "Tap a folder to review and delete files. Deleted media disappears from chats on this phone; other people's copies are not affected."
            }
        }.start()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = folders.size
        override fun getItem(p: Int) = folders[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val f = folders[p]
            val col = LinearLayout(this@WhatsAppActivity)
            col.orientation = LinearLayout.VERTICAL; col.setPadding(16, 24, 16, 24)
            val t1 = TextView(this@WhatsAppActivity); t1.text = "${f.title}  -  ${fmt(f.size)}"; t1.textSize = 16f; t1.setTextColor(Color.BLACK)
            val t2 = TextView(this@WhatsAppActivity); t2.text = "${f.count} files  |  Sent copies: ${fmt(f.sent)}"; t2.textSize = 12f; t2.setTextColor(Color.DKGRAY)
            col.addView(t1); col.addView(t2)
            return col
        }
    }
}
