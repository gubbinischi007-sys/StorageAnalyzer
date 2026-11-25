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
import java.util.concurrent.Executors

class CacheActivity : Activity() {
    class Row(val label: String, val pkg: String, val cache: Long)

    private val rows = mutableListOf<Row>()
    private var initialTotal = -1L
    private var scanning = false
    private lateinit var summaryText: TextView
    private lateinit var freedText: TextView
    private lateinit var nextBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var permissionBanner: LinearLayout
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

        // Header: [←] Cache cleaner
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
        title.text = "Cache cleaner"
        title.textSize = 22f
        title.setTextColor(ThemeColors.TEXT_WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title)
        root.addView(header)

        // Permission Banner
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

        val bannerText = TextView(this)
        bannerText.text = "Tap here to grant \"Usage access\" in Settings so Storage Analyzer can read clearable app cache."
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

        // Top Summary Card
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = ThemeColors.roundedBg(ThemeColors.CARD_DARK, 16f, this)
        val cp = ThemeColors.dp(this, 14f)
        card.setPadding(cp, cp, cp, cp)

        summaryText = TextView(this)
        summaryText.text = "Scanning clearable cache..."
        summaryText.textSize = 15f
        summaryText.setTextColor(ThemeColors.TEXT_WHITE)
        summaryText.setTypeface(summaryText.typeface, Typeface.BOLD)

        freedText = TextView(this)
        freedText.text = "Tap an app to open its Settings > Storage > Clear cache."
        freedText.textSize = 12f
        freedText.setTextColor(ThemeColors.TEXT_MUTED)
        freedText.setPadding(0, ThemeColors.dp(this, 4f), 0, ThemeColors.dp(this, 10f))

        nextBtn = TextView(this)
        nextBtn.text = "Clear biggest first"
        nextBtn.textSize = 13f
        nextBtn.setTextColor(ThemeColors.TEXT_WHITE)
        nextBtn.setTypeface(nextBtn.typeface, Typeface.BOLD)
        nextBtn.gravity = Gravity.CENTER
        nextBtn.background = ThemeColors.roundedBg(ThemeColors.COLOR_APPS, 14f, this)
        val np = ThemeColors.dp(this, 10f)
        nextBtn.setPadding(ThemeColors.dp(this, 16f), np, ThemeColors.dp(this, 16f), np)
        nextBtn.isClickable = true
        nextBtn.setOnClickListener { openSettings(0) }

        card.addView(summaryText)
        card.addView(freedText)
        card.addView(nextBtn)
        val cardLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.bottomMargin = ThemeColors.dp(this, 12f)
        root.addView(card, cardLp)

        statusText = TextView(this)
        statusText.textSize = 13f
        statusText.setTextColor(ThemeColors.TEXT_MUTED)
        statusText.setPadding(ThemeColors.dp(this, 4f), 0, 0, ThemeColors.dp(this, 8f))
        statusText.visibility = View.GONE
        root.addView(statusText)

        // List
        val list = ListView(this)
        list.divider = null
        list.dividerHeight = ThemeColors.dp(this, 10f)
        list.setBackgroundColor(ThemeColors.BG_DARK)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openSettings(pos) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun openSettings(index: Int) {
        val r = rows.getOrNull(index) ?: return
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
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
            if (!scanning) scan()
        }
    }

    override fun onDestroy() {
        iconPool.shutdownNow()
        super.onDestroy()
    }

    private fun scan() {
        scanning = true
        statusText.text = "Scanning cache..."
        statusText.visibility = View.VISIBLE

        Thread {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val userHandle = Process.myUserHandle()
            val installed = pm.getInstalledApplications(0)

            val result = mutableListOf<Row>()
            for (ai in installed) {
                try {
                    val uuid = ai.storageUuid ?: StorageManager.UUID_DEFAULT
                    val s = ssm.queryStatsForPackage(uuid, ai.packageName, userHandle)
                    if (s.cacheBytes > 0) {
                        val label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { ai.packageName }
                        result.add(Row(label, ai.packageName, s.cacheBytes))
                    }
                } catch (_: Exception) { }
            }
            result.sortByDescending { it.cache }

            runOnUiThread {
                scanning = false
                statusText.visibility = View.GONE
                rows.clear()
                rows.addAll(result)
                adapter.notifyDataSetChanged()

                val total = result.sumOf { it.cache }
                if (initialTotal < 0) initialTotal = total
                val freed = (initialTotal - total).coerceAtLeast(0)

                summaryText.text = "Clearable cache: ${fmt(total)} in ${result.size} apps"
                freedText.text = if (freed > 0) "Freed so far in this session: ${fmt(freed)}" else "Tap an app to open Storage > Clear cache."
                nextBtn.isEnabled = result.isNotEmpty()
            }
        }.start()
    }

    inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int): Any = rows[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
            val r = rows[p]
            val biggest = rows.firstOrNull()?.cache ?: 1L

            val card = (convertView as? LinearLayout) ?: createCardView()
            val iconView = card.getTag(100) as ImageView
            val badgeTv = card.getTag(101) as TextView
            val titleTv = card.getTag(102) as TextView
            val subTv = card.getTag(103) as TextView
            val barView = card.getTag(104) as SegmentedBar

            titleTv.text = r.label
            subTv.text = "${fmt(r.cache)} cache"

            val ratio = (r.cache.toFloat() / biggest.toFloat()).coerceIn(0.02f, 1f)
            barView.segments = listOf(ratio to ThemeColors.COLOR_CACHE)

            val cached = iconCache.get(r.pkg)
            if (cached != null) {
                iconView.setImageDrawable(cached)
                iconView.visibility = View.VISIBLE
                badgeTv.visibility = View.GONE
            } else {
                val letter = r.label.firstOrNull()?.uppercase() ?: "A"
                badgeTv.text = letter
                badgeTv.setBackgroundColor(ThemeColors.appColor(r.pkg))
                badgeTv.visibility = View.VISIBLE
                iconView.visibility = View.GONE

                iconPool.submit {
                    try {
                        val icon = packageManager.getApplicationIcon(r.pkg)
                        iconCache.put(r.pkg, icon)
                        runOnUiThread {
                            if (p < rows.size && rows[p].pkg == r.pkg) {
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
            val card = LinearLayout(this@CacheActivity)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            val pad = ThemeColors.dp(this@CacheActivity, 14f)
            card.setPadding(pad, pad, pad, pad)
            card.background = ThemeColors.roundedBg(ThemeColors.CARD_DARK, 16f, this@CacheActivity)

            val iconFrame = FrameLayout(this@CacheActivity)
            val iconSize = ThemeColors.dp(this@CacheActivity, 44f)
            val iconLp = LinearLayout.LayoutParams(iconSize, iconSize)
            iconLp.rightMargin = ThemeColors.dp(this@CacheActivity, 14f)
            iconFrame.layoutParams = iconLp
            iconFrame.background = ThemeColors.roundedBg(ThemeColors.CARD_SECONDARY, 12f, this@CacheActivity)
            iconFrame.clipToOutline = true

            val iv = ImageView(this@CacheActivity)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iconFrame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val tvBadge = TextView(this@CacheActivity)
            tvBadge.gravity = Gravity.CENTER
            tvBadge.textSize = 18f
            tvBadge.setTextColor(Color.WHITE)
            tvBadge.setTypeface(tvBadge.typeface, Typeface.BOLD)
            iconFrame.addView(tvBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(iconFrame)

            val col = LinearLayout(this@CacheActivity)
            col.orientation = LinearLayout.VERTICAL

            val topRow = LinearLayout(this@CacheActivity)
            topRow.orientation = LinearLayout.HORIZONTAL
            val tvTitle = TextView(this@CacheActivity)
            tvTitle.textSize = 15f
            tvTitle.setTextColor(ThemeColors.TEXT_WHITE)
            tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
            topRow.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val tvSub = TextView(this@CacheActivity)
            tvSub.textSize = 13f
            tvSub.setTextColor(ThemeColors.COLOR_CACHE)
            tvSub.setTypeface(tvSub.typeface, Typeface.BOLD)
            topRow.addView(tvSub)
            col.addView(topRow)

            val bar = SegmentedBar(this@CacheActivity)
            val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeColors.dp(this@CacheActivity, 5f))
            barLp.topMargin = ThemeColors.dp(this@CacheActivity, 6f)
            bar.layoutParams = barLp
            col.addView(bar)

            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            card.setTag(100, iv)
            card.setTag(101, tvBadge)
            card.setTag(102, tvTitle)
            card.setTag(103, tvSub)
            card.setTag(104, bar)
            return card
        }
    }
}
