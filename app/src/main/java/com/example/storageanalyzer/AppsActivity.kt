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
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

data class AppStat(
    val label: String, val pkg: String, val icon: Drawable?,
    val apk: Long,        // the installed app itself
    val userData: Long,   // accounts, downloads, databases (IMPORTANT - don't wipe)
    val cache: Long       // temporary files (safe to clear)
) {
    val total get() = apk + userData + cache
}

class AppsActivity : Activity() {
    private val apps = mutableListOf<AppStat>()
    private var sortByCache = false
    private var askedPermission = false
    private var scanning = false
    private lateinit var summary: TextView
    private lateinit var toggle: Button
    private lateinit var adapter: Adapter

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 0)
        val title = TextView(this)
        title.text = "Apps"; title.textSize = 22f; title.setTextColor(Color.BLACK)
        summary = TextView(this); summary.textSize = 14f; summary.setPadding(8, 8, 8, 16)
        summary.setOnClickListener { if (!hasUsageAccess()) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        toggle = Button(this)
        toggle.text = "Sorted by: Total size (tap to sort by Cache)"
        toggle.setOnClickListener {
            sortByCache = !sortByCache
            toggle.text = if (sortByCache) "Sorted by: Cache (tap for Total size)" else "Sorted by: Total size (tap to sort by Cache)"
            resort()
        }
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, AppDetailActivity::class.java).putExtra(AppDetailActivity.EXTRA_PKG, apps[pos].pkg))
        }
        root.addView(title); root.addView(summary); root.addView(toggle)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            summary.text = "Allow \"Usage access\" for Storage Analyzer so it can read each app's size. (Tap here to open the setting.)"
            if (!askedPermission) { askedPermission = true; startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        } else if (!scanning) scan()
    }

    private fun scan() {
        scanning = true
        summary.text = "Scanning apps..."
        Thread {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val result = mutableListOf<AppStat>()
            for (ai in pm.getInstalledApplications(0)) {
                try {
                    val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                    // dataBytes already INCLUDES cacheBytes
                    result.add(AppStat(ai.loadLabel(pm).toString(), ai.packageName, ai.loadIcon(pm),
                        s.appBytes, (s.dataBytes - s.cacheBytes).coerceAtLeast(0), s.cacheBytes))
                } catch (_: Exception) { }
            }
            val st = StatFs(Environment.getDataDirectory().path)
            runOnUiThread {
                scanning = false
                apps.clear(); apps.addAll(result); resort()
                val appsTotal = result.sumOf { it.total }
                summary.text = "All apps combined: ${fmt(appsTotal)}\n" +
                    "Not from apps (system, media, other): ~${fmt((st.totalBytes - st.availableBytes - appsTotal).coerceAtLeast(0))}\n" +
                    "Total cache you can safely clear: ${fmt(result.sumOf { it.cache })}\n" +
                    "Tap an app to see what is stored inside it (App, User data, Cache)."
            }
        }.start()
    }

    private fun resort() {
        apps.sortByDescending { if (sortByCache) it.cache else it.total }
        adapter.notifyDataSetChanged()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int): Any = apps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val a = apps[p]
            val row = LinearLayout(this@AppsActivity)
            row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(8, 20, 8, 20)
            val icon = ImageView(this@AppsActivity)
            icon.setImageDrawable(a.icon); icon.layoutParams = LinearLayout.LayoutParams(96, 96)
            val col = LinearLayout(this@AppsActivity); col.orientation = LinearLayout.VERTICAL; col.setPadding(24, 0, 0, 0)
            val t1 = TextView(this@AppsActivity); t1.text = "${a.label}  -  ${fmt(a.total)}"; t1.textSize = 16f; t1.setTextColor(Color.BLACK)
            val t2 = TextView(this@AppsActivity)
            t2.text = "App: ${fmt(a.apk)}  |  Data (keep): ${fmt(a.userData)}  |  Cache (clearable): ${fmt(a.cache)}"
            t2.textSize = 12f; t2.setTextColor(Color.DKGRAY)
            col.addView(t1); col.addView(t2)
            row.addView(icon); row.addView(col)
            return row
        }
    }
}
