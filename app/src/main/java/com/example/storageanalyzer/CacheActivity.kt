package com.example.storageanalyzer

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class CacheActivity : Activity() {
    class Row(val label: String, val pkg: String, val icon: Drawable?, val cache: Long)

    private val rows = mutableListOf<Row>()
    private var initialTotal = -1L
    private var askedPermission = false
    private var scanning = false
    private lateinit var summary: TextView
    private lateinit var nextBtn: Button
    private lateinit var adapter: Adapter

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 16)
        val title = TextView(this)
        title.text = "Cache cleaner"; title.textSize = 22f; title.setTextColor(Color.BLACK)
        summary = TextView(this); summary.textSize = 14f; summary.setPadding(8, 8, 8, 16)
        summary.setOnClickListener { if (!hasUsageAccess()) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        nextBtn = Button(this)
        nextBtn.text = "Clear biggest first"
        nextBtn.setOnClickListener { openSettings(0) }
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openSettings(pos) }
        root.addView(title); root.addView(summary); root.addView(nextBtn)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // Opens the app's settings page; there tap Storage > Clear cache, then press back.
    private fun openSettings(index: Int) {
        val r = rows.getOrNull(index) ?: return
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            summary.text = "Allow \"Usage access\" for Storage Analyzer so it can read each app's cache. (Tap here to open the setting.)"
            if (!askedPermission) { askedPermission = true; startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        } else if (!scanning) scan()   // also refreshes after you clear an app's cache and come back
    }

    private fun scan() {
        scanning = true
        if (rows.isEmpty()) summary.text = "Scanning cache..."
        Thread {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val result = mutableListOf<Row>()
            for (ai in pm.getInstalledApplications(0)) {
                try {
                    val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                    if (s.cacheBytes >= 1_048_576) result.add(Row(ai.loadLabel(pm).toString(), ai.packageName, ai.loadIcon(pm), s.cacheBytes))
                } catch (_: Exception) { }
            }
            result.sortByDescending { it.cache }
            runOnUiThread {
                scanning = false
                rows.clear(); rows.addAll(result)
                adapter.notifyDataSetChanged()
                val total = result.sumOf { it.cache }
                if (initialTotal < 0) initialTotal = total
                val freed = (initialTotal - total).coerceAtLeast(0)
                summary.text = "Clearable cache: ${fmt(total)} in ${result.size} apps (1 MB or more each).\n" +
                    "Freed so far in this session: ${fmt(freed)}\n" +
                    "Tap an app > Storage > Clear cache, then press back. Cache is temporary, so clearing it is safe."
                nextBtn.isEnabled = result.isNotEmpty()
            }
        }.start()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int): Any = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val r = rows[p]
            val biggest = rows.firstOrNull()?.cache ?: 1L
            val row = LinearLayout(this@CacheActivity)
            row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(8, 20, 8, 20)
            val icon = ImageView(this@CacheActivity)
            icon.setImageDrawable(r.icon); icon.layoutParams = LinearLayout.LayoutParams(96, 96)
            val col = LinearLayout(this@CacheActivity); col.orientation = LinearLayout.VERTICAL; col.setPadding(24, 0, 0, 0)
            val t1 = TextView(this@CacheActivity); t1.text = "${r.label}  -  ${fmt(r.cache)}"; t1.textSize = 16f; t1.setTextColor(Color.BLACK)
            val bar = ProgressBar(this@CacheActivity, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 100; bar.progress = (r.cache * 100 / biggest).toInt()
            col.addView(t1); col.addView(bar)
            row.addView(icon)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
    }
}
