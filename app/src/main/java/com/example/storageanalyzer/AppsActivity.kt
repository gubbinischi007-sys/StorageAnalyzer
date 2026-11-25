package com.example.storageanalyzer

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

data class AppStat(
    val label: String,
    val pkg: String,
    val apk: Long,
    val userData: Long,
    val cache: Long
) {
    val total get() = apk + userData + cache
}

class AppsActivity : Activity() {
    private val apps = mutableListOf<AppStat>()
    private var sortByCache = false
    private var scanning = false
    private lateinit var permissionBanner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var adapter: Adapter
    private val iconCache = LruCache<String, Drawable>(150)
    private val iconPool = Executors.newFixedThreadPool(3)

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.BG_DARK)
        val p = ThemeColors.dp(this, 16f)
        root.setPadding(p, ThemeColors.dp(this, 28f), p, 0)

        // Header Row: [←] Apps
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(0, 0, 0, ThemeColors.dp(this, 12f))

        val backBtn = TextView(this)
        backBtn.text = "←"
        backBtn.textSize = 20f
        backBtn.setTextColor(ThemeColors.TEXT_WHITE)
        backBtn.gravity = Gravity.CENTER
        val bSize = ThemeColors.dp(this, 38f)
        val bLp = LinearLayout.LayoutParams(bSize, bSize)
        bLp.rightMargin = ThemeColors.dp(this, 14f)
        backBtn.layoutParams = bLp
        backBtn.background = ThemeColors.roundedBg(ThemeColors.CARD_SECONDARY, 19f, this)
        backBtn.setOnClickListener { finish() }
        header.addView(backBtn)

        val title = TextView(this)
        title.text = "Apps"
        title.textSize = 22f
        title.setTextColor(ThemeColors.TEXT_WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title)
        root.addView(header)

        // Subtitle explanation
        val sub = TextView(this)
        sub.text = "App = the app itself · Data = logins, chats, downloads (keep) · Cache = temporary (safe to clear)"
        sub.textSize = 12f
        sub.setTextColor(ThemeColors.TEXT_MUTED)
        sub.setPadding(ThemeColors.dp(this, 2f), 0, 0, ThemeColors.dp(this, 12f))
        root.addView(sub)

        // Permission Banner (interactive)
        permissionBanner = LinearLayout(this)
        permissionBanner.orientation = LinearLayout.VERTICAL
        permissionBanner.background = ThemeColors.roundedBg(ThemeColors.CARD_SECONDARY, 14f, this)
        val bp = ThemeColors.dp(this, 14f)
        permissionBanner.setPadding(bp, bp, bp, bp)
        val bLp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bLp2.bottomMargin = ThemeColors.dp(this, 12f)
        permissionBanner.layoutParams = bLp2

        val bannerTitle = TextView(this)
        bannerTitle.text = "⚠️ Usage Access Required"
        bannerTitle.textSize = 14f
        bannerTitle.setTextColor(ThemeColors.COLOR_MEDIA)
        bannerTitle.setTypeface(bannerTitle.typeface, Typeface.BOLD)

        bannerText = TextView(this)
        bannerText.text = "Tap here to open Settings and permit \"Usage access\" for Storage Analyzer so it can read app storage and cache sizes."
        bannerText.textSize = 12f
        bannerText.setTextColor(ThemeColors.TEXT_WHITE)
        bannerText.setPadding(0, ThemeColors.dp(this, 4f), 0, 0)

        permissionBanner.addView(bannerTitle)
        permissionBanner.addView(bannerText)
        permissionBanner.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        permissionBanner.visibility = View.GONE
        root.addView(permissionBanner)

        // Loading Status Text
        statusText = TextView(this)
        statusText.textSize = 13f
        statusText.setTextColor(ThemeColors.TEXT_MUTED)
        statusText.setPadding(ThemeColors.dp(this, 4f), 0, 0, ThemeColors.dp(this, 8f))
        statusText.visibility = View.GONE
        root.addView(statusText)

        // Pill button: "Sorted by: Total size (tap to change)"
        toggleBtn = TextView(this)
        toggleBtn.text = "Sorted by: Total size (tap to change)"
        toggleBtn.textSize = 13f
        toggleBtn.setTextColor(ThemeColors.TEXT_WHITE)
        toggleBtn.background = ThemeColors.roundedBg(ThemeColors.CARD_SECONDARY, 20f, this)
        val tp = ThemeColors.dp(this, 10f)
        toggleBtn.setPadding(ThemeColors.dp(this, 16f), tp, ThemeColors.dp(this, 16f), tp)
        toggleBtn.isClickable = true
        toggleBtn.setOnClickListener {
            sortByCache = !sortByCache
            toggleBtn.text = if (sortByCache) "Sorted by: Cache (tap to change)" else "Sorted by: Total size (tap to change)"
            resort()
        }
        val tLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tLp.bottomMargin = ThemeColors.dp(this, 12f)
        toggleBtn.layoutParams = tLp
        root.addView(toggleBtn)

        // App List
        val list = ListView(this)
        list.divider = null
        list.dividerHeight = ThemeColors.dp(this, 10f)
        list.setBackgroundColor(ThemeColors.BG_DARK)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, AppDetailActivity::class.java).putExtra(AppDetailActivity.EXTRA_PKG, apps[pos].pkg))
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun checkUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            }
            if (mode == AppOpsManager.MODE_ALLOWED) {
                true
            } else {
                // Direct test query on our own package: if it succeeds without SecurityException, access is granted
                val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, packageName, Process.myUserHandle())
                true
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        val hasAccess = checkUsageAccess()
        if (!hasAccess) {
            permissionBanner.visibility = View.VISIBLE
            statusText.text = "Waiting for Usage Access permission in Settings..."
            statusText.visibility = View.VISIBLE
        } else {
            permissionBanner.visibility = View.GONE
            if (!scanning) {
                fastScan()
            }
        }
    }

    override fun onDestroy() {
        iconPool.shutdownNow()
        super.onDestroy()
    }

    private fun fastScan() {
        scanning = true
        statusText.text = "Loading apps..."
        statusText.visibility = View.VISIBLE

        Thread {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val userHandle = Process.myUserHandle()
            val installed = pm.getInstalledApplications(0)

            // Include all apps, prioritizing user and launchable apps
            val launchable = installed.filter {
                ((it.flags and ApplicationInfo.FLAG_SYSTEM) == 0) || (pm.getLaunchIntentForPackage(it.packageName) != null)
            }
            val targetApps = if (launchable.isNotEmpty()) launchable else installed

            val list = mutableListOf<AppStat>()
            for (ai in targetApps) {
                var apk = 0L
                var data = 0L
                var cache = 0L
                try {
                    val uuid = ai.storageUuid ?: StorageManager.UUID_DEFAULT
                    val s = ssm.queryStatsForPackage(uuid, ai.packageName, userHandle)
                    apk = s.appBytes
                    cache = s.cacheBytes
                    data = (s.dataBytes - cache).coerceAtLeast(0)
                } catch (_: Exception) {
                    try {
                        apk = File(ai.sourceDir).length()
                    } catch (_: Exception) {}
                }

                val label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { ai.packageName }
                if (apk > 0 || data > 0 || cache > 0) {
                    list.add(AppStat(label, ai.packageName, apk, data, cache))
                }
            }

            runOnUiThread {
                scanning = false
                apps.clear()
                apps.addAll(list)
                resort()
                if (apps.isEmpty()) {
                    statusText.text = "No apps found. Make sure Usage access is granted in Settings."
                    statusText.visibility = View.VISIBLE
                } else {
                    statusText.visibility = View.GONE
                }
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

        override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
            val item = apps[p]
            val card = (convertView as? LinearLayout) ?: createCardView()
            val iconView = card.getTag(100) as ImageView
            val badgeTv = card.getTag(101) as TextView
            val titleTv = card.getTag(102) as TextView
            val barView = card.getTag(103) as SegmentedBar
            val subTv = card.getTag(104) as TextView

            // Top title: "WhatsApp – 6.0 GB"
            titleTv.text = "${item.label} – ${fmt(item.total)}"

            // Segmented proportions: App (slate), Data (blue), Cache (orange)
            val total = item.total.toFloat().coerceAtLeast(1f)
            val apkRatio = item.apk / total
            val dataRatio = item.userData / total
            val cacheRatio = item.cache / total
            barView.segments = listOf(
                apkRatio to ThemeColors.COLOR_APK,
                dataRatio to ThemeColors.COLOR_DATA,
                cacheRatio to ThemeColors.COLOR_CACHE
            )

            // Bottom stats text: "App 210 MB · Data 4.0 GB · Cache 1.8 GB"
            subTv.text = "App ${fmt(item.apk)} · Data ${fmt(item.userData)} · Cache ${fmt(item.cache)}"

            // Asynchronous Icon Loading with Letter Badge Fallback
            val cachedIcon = iconCache.get(item.pkg)
            if (cachedIcon != null) {
                iconView.setImageDrawable(cachedIcon)
                iconView.visibility = View.VISIBLE
                badgeTv.visibility = View.GONE
            } else {
                val letter = item.label.firstOrNull()?.uppercase() ?: "A"
                badgeTv.text = letter
                badgeTv.setBackgroundColor(ThemeColors.appColor(item.pkg))
                badgeTv.visibility = View.VISIBLE
                iconView.visibility = View.GONE

                iconPool.submit {
                    try {
                        val icon = packageManager.getApplicationIcon(item.pkg)
                        iconCache.put(item.pkg, icon)
                        runOnUiThread {
                            if (p < apps.size && apps[p].pkg == item.pkg) {
                                iconView.setImageDrawable(icon)
                                iconView.visibility = View.VISIBLE
                                badgeTv.visibility = View.GONE
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            return card
        }

        private fun createCardView(): LinearLayout {
            val card = LinearLayout(this@AppsActivity)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            val pad = ThemeColors.dp(this@AppsActivity, 14f)
            card.setPadding(pad, pad, pad, pad)
            card.background = ThemeColors.roundedBg(ThemeColors.CARD_DARK, 16f, this@AppsActivity)

            // Icon container frame
            val iconFrame = FrameLayout(this@AppsActivity)
            val iconSize = ThemeColors.dp(this@AppsActivity, 46f)
            val iconLp = LinearLayout.LayoutParams(iconSize, iconSize)
            iconLp.rightMargin = ThemeColors.dp(this@AppsActivity, 14f)
            iconFrame.layoutParams = iconLp
            iconFrame.background = ThemeColors.roundedBg(ThemeColors.CARD_SECONDARY, 12f, this@AppsActivity)
            iconFrame.clipToOutline = true

            val iv = ImageView(this@AppsActivity)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iconFrame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val tvBadge = TextView(this@AppsActivity)
            tvBadge.gravity = Gravity.CENTER
            tvBadge.textSize = 18f
            tvBadge.setTextColor(Color.WHITE)
            tvBadge.setTypeface(tvBadge.typeface, Typeface.BOLD)
            iconFrame.addView(tvBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(iconFrame)

            // Right content column
            val col = LinearLayout(this@AppsActivity)
            col.orientation = LinearLayout.VERTICAL

            val tvTitle = TextView(this@AppsActivity)
            tvTitle.textSize = 15f
            tvTitle.setTextColor(ThemeColors.TEXT_WHITE)
            tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
            col.addView(tvTitle)

            val bar = SegmentedBar(this@AppsActivity)
            val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeColors.dp(this@AppsActivity, 6f))
            barLp.topMargin = ThemeColors.dp(this@AppsActivity, 6f)
            barLp.bottomMargin = ThemeColors.dp(this@AppsActivity, 6f)
            bar.layoutParams = barLp
            col.addView(bar)

            val tvSub = TextView(this@AppsActivity)
            tvSub.textSize = 12f
            tvSub.setTextColor(ThemeColors.TEXT_MUTED)
            col.addView(tvSub)

            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            card.setTag(100, iv)
            card.setTag(101, tvBadge)
            card.setTag(102, tvTitle)
            card.setTag(103, bar)
            card.setTag(104, tvSub)
            return card
        }
    }
}
