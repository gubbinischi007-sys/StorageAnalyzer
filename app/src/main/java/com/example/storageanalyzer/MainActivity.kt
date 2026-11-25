package com.example.storageanalyzer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var usedText: TextView
    private lateinit var freeText: TextView
    private lateinit var storageBar: SegmentedBar
    private lateinit var noticeCard: LinearLayout
    private lateinit var noticeText: TextView
    private val subtitles = mutableMapOf<String, TextView>()
    private var askedPermission = false

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.BG_DARK)
        val p = ThemeColors.dp(this, 16f)
        root.setPadding(p, ThemeColors.dp(this, 32f), p, p)

        // Header Title
        val title = TextView(this)
        title.text = "Storage Analyzer"
        title.textSize = 24f
        title.setTextColor(ThemeColors.TEXT_WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setPadding(ThemeColors.dp(this, 4f), 0, 0, ThemeColors.dp(this, 16f))
        root.addView(title)

        // Top Storage Card
        val topCard = LinearLayout(this)
        topCard.orientation = LinearLayout.VERTICAL
        topCard.background = ThemeColors.roundedBg(ThemeColors.CARD_DARK, 18f, this)
        topCard.setPadding(p, p, p, p)

        usedText = TextView(this)
        usedText.textSize = 18f
        usedText.setTextColor(ThemeColors.TEXT_WHITE)
        usedText.setTypeface(usedText.typeface, Typeface.BOLD)

        freeText = TextView(this)
        freeText.textSize = 13f
        freeText.setTextColor(ThemeColors.TEXT_MUTED)
        freeText.setPadding(0, ThemeColors.dp(this, 2f), 0, ThemeColors.dp(this, 12f))

        storageBar = SegmentedBar(this)
        val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeColors.dp(this, 8f))
        storageBar.layoutParams = barLp

        // Legend row below bar
        val legend = LinearLayout(this)
        legend.orientation = LinearLayout.HORIZONTAL
        legend.setPadding(0, ThemeColors.dp(this, 12f), 0, 0)
        legend.addView(createLegendItem("System", ThemeColors.COLOR_SYSTEM))
        legend.addView(createLegendItem("Apps", ThemeColors.COLOR_APPS))
        legend.addView(createLegendItem("Photos/Videos", ThemeColors.COLOR_MEDIA))
        legend.addView(createLegendItem("Other", ThemeColors.COLOR_OTHER))

        topCard.addView(usedText)
        topCard.addView(freeText)
        topCard.addView(storageBar)
        topCard.addView(legend)
        root.addView(topCard)

        // Notice / Permission banner
        noticeCard = LinearLayout(this)
        noticeCard.orientation = LinearLayout.VERTICAL
        noticeCard.background = ThemeColors.roundedBg(ThemeColors.CARD_DARK, 14f, this)
        val np = ThemeColors.dp(this, 12f)
        noticeCard.setPadding(np, np, np, np)
        val nLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nLp.topMargin = ThemeColors.dp(this, 12f)
        noticeCard.layoutParams = nLp

        noticeText = TextView(this)
        noticeText.textSize = 12f
        noticeText.setTextColor(ThemeColors.TEXT_MUTED)
        noticeCard.addView(noticeText)
        noticeCard.setOnClickListener {
            if (!Environment.isExternalStorageManager()) requestAccess()
        }
        root.addView(noticeCard)

        // Section Title: "Clean up by category"
        val secTitle = TextView(this)
        secTitle.text = "Clean up by category"
        secTitle.textSize = 16f
        secTitle.setTextColor(ThemeColors.TEXT_WHITE)
        secTitle.setTypeface(secTitle.typeface, Typeface.BOLD)
        val secLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        secLp.topMargin = ThemeColors.dp(this, 20f)
        secLp.bottomMargin = ThemeColors.dp(this, 10f)
        secLp.leftMargin = ThemeColors.dp(this, 4f)
        root.addView(secTitle, secLp)

        // Category Cards
        val categories = LinearLayout(this)
        categories.orientation = LinearLayout.VERTICAL
        root.addView(categories)

        addCategoryCard(categories, "Apps", "📱", "Apps", "Tap to inspect app storage") {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        addCategoryCard(categories, "Cache", "🧹", "Cache cleaner", "Apps with clearable cache") {
            startActivity(Intent(this, CacheActivity::class.java))
        }
        addCategoryCard(categories, "Photos", "🖼️", "Photos", "Scanning files...") {
            startActivity(Intent(this, CategoryActivity::class.java).putExtra(CategoryActivity.EXTRA, "Photos"))
        }
        addCategoryCard(categories, "Videos", "🎬", "Videos", "Scanning files...") {
            startActivity(Intent(this, CategoryActivity::class.java).putExtra(CategoryActivity.EXTRA, "Videos"))
        }
        addCategoryCard(categories, "Audio", "🎵", "Audio", "Scanning files...") {
            startActivity(Intent(this, CategoryActivity::class.java).putExtra(CategoryActivity.EXTRA, "Audio"))
        }
        addCategoryCard(categories, "Documents", "📄", "Documents", "Scanning files...") {
            startActivity(Intent(this, CategoryActivity::class.java).putExtra(CategoryActivity.EXTRA, "Documents"))
        }
        addCategoryCard(categories, "Others", "📦", "Others", "APKs, archives, misc") {
            startActivity(Intent(this, CategoryActivity::class.java).putExtra(CategoryActivity.EXTRA, "Others"))
        }
        addCategoryCard(categories, "WhatsApp", "💬", "WhatsApp media", "Chat media & sent copies") {
            startActivity(Intent(this, WhatsAppActivity::class.java))
        }
        addCategoryCard(categories, "Leftover", "🗑️", "Leftover folders", "Folders from uninstalled apps") {
            startActivity(Intent(this, LeftoverActivity::class.java))
        }
        addCategoryCard(categories, "Hidden", "👻", "Hidden & trash", "Gallery trash & hidden media") {
            startActivity(Intent(this, HiddenActivity::class.java))
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(ThemeColors.BG_DARK)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun createLegendItem(label: String, color: Int): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.background = ThemeColors.roundedBg(color, 4f, this)
        val dotSize = ThemeColors.dp(this, 7f)
        val dotLp = LinearLayout.LayoutParams(dotSize, dotSize)
        dotLp.rightMargin = ThemeColors.dp(this, 4f)
        dot.layoutParams = dotLp

        val tv = TextView(this)
        tv.text = label
        tv.textSize = 11f
        tv.setTextColor(ThemeColors.TEXT_MUTED)

        item.addView(dot)
        item.addView(tv)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        item.layoutParams = lp
        return item
    }

    private fun addCategoryCard(parent: LinearLayout, key: String, emoji: String, title: String, sub: String, onClick: () -> Unit) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        val p = ThemeColors.dp(this, 14f)
        card.setPadding(p, p, p, p)
        card.background = ThemeColors.roundedBg(ThemeColors.CARD_DARK, 16f, this)
        card.isClickable = true
        card.setOnClickListener { onClick() }

        // Left Icon Box
        val iconBox = FrameLayout(this)
        val boxSize = ThemeColors.dp(this, 44f)
        val boxLp = LinearLayout.LayoutParams(boxSize, boxSize)
        boxLp.rightMargin = ThemeColors.dp(this, 14f)
        iconBox.layoutParams = boxLp
        iconBox.background = ThemeColors.roundedBg(ThemeColors.CARD_SECONDARY, 12f, this)

        val iconTv = TextView(this)
        iconTv.text = emoji
        iconTv.textSize = 20f
        iconTv.gravity = Gravity.CENTER
        val fLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        iconBox.addView(iconTv, fLp)
        card.addView(iconBox)

        // Middle Text Column
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.setTextColor(ThemeColors.TEXT_WHITE)
        t.setTypeface(t.typeface, Typeface.BOLD)

        val s = TextView(this)
        s.text = sub
        s.textSize = 12f
        s.setTextColor(ThemeColors.TEXT_MUTED)
        s.setPadding(0, ThemeColors.dp(this, 2f), 0, 0)
        col.addView(t)
        col.addView(s)
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        subtitles[key] = s

        // Right Chevron
        val chevron = TextView(this)
        chevron.text = "›"
        chevron.textSize = 20f
        chevron.setTextColor(ThemeColors.TEXT_CHEVRON)
        card.addView(chevron)

        val cardLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.bottomMargin = ThemeColors.dp(this, 10f)
        parent.addView(card, cardLp)
    }

    private fun requestAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun updateStorageStats() {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes
        val free = st.availableBytes
        val used = (total - free).coerceAtLeast(0)

        usedText.text = "${fmt(used)} used of ${fmt(total)}"
        freeText.text = "${fmt(free)} free"

        val photosVideosBytes = (FileIndex.totalBytes["Photos"] ?: 0L) + (FileIndex.totalBytes["Videos"] ?: 0L)
        val mediaRatio = (photosVideosBytes.toFloat() / total).coerceIn(0f, 0.45f)
        val usedRatio = (used.toFloat() / total).coerceIn(0f, 1f)

        val sysRatio = 0.15f.coerceAtMost(usedRatio)
        val appsRatio = ((usedRatio - sysRatio - mediaRatio) * 0.65f).coerceAtLeast(0.08f)
        val otherRatio = (usedRatio - sysRatio - appsRatio - mediaRatio).coerceAtLeast(0.02f)

        storageBar.segments = listOf(
            sysRatio to ThemeColors.COLOR_SYSTEM,
            appsRatio to ThemeColors.COLOR_APPS,
            mediaRatio to ThemeColors.COLOR_MEDIA,
            otherRatio to ThemeColors.COLOR_OTHER
        )
    }

    override fun onResume() {
        super.onResume()
        updateStorageStats()
        if (!Environment.isExternalStorageManager()) {
            noticeText.text = "Tap here to grant \"All files access\" so Storage Analyzer can scan your files and photos."
            if (!askedPermission) {
                askedPermission = true
                requestAccess()
            }
        } else {
            noticeText.text = "Tap any category below to inspect, analyze, and free up space."
            FileIndex.ensure {
                runOnUiThread {
                    updateStorageStats()
                    val cats = setOf("Photos", "Videos", "Audio", "Documents", "Others")
                    for ((k, tv) in subtitles) {
                        if (k in cats) {
                            tv.text = "${fmt(FileIndex.totalBytes[k] ?: 0L)} · ${FileIndex.totalCount[k] ?: 0} files"
                        }
                    }
                }
            }
        }
    }
}
