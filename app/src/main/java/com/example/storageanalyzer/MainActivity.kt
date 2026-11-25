package com.example.storageanalyzer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Home: 1) scan first  2) then show the storage bar  3) then every module, from Apps to the last one.
 */
class MainActivity : Activity() {
    private lateinit var storageText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var note: TextView
    private lateinit var scanBox: LinearLayout
    private lateinit var scanText: TextView
    private lateinit var content: LinearLayout
    private val subtitles = mutableMapOf<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var askedPermission = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!FileIndex.scanned) {
                scanText.text = "Scanning your storage...\n${FileIndex.progress} files checked"
                handler.postDelayed(this, 300)
            }
        }
    }

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(32, 64, 32, 48)

        val title = TextView(this)
        title.text = "Storage Analyzer"; title.textSize = 26f
        title.setTextColor(Color.BLACK); title.setTypeface(title.typeface, Typeface.BOLD)
        col.addView(title)

        // ---- step 1: scanning (shown first) ----
        scanBox = LinearLayout(this)
        scanBox.orientation = LinearLayout.VERTICAL
        scanBox.gravity = Gravity.CENTER_HORIZONTAL
        scanBox.setPadding(0, 120, 0, 0)
        scanBox.addView(ProgressBar(this))
        scanText = TextView(this)
        scanText.text = "Scanning your storage..."; scanText.textSize = 15f; scanText.gravity = Gravity.CENTER
        scanText.setPadding(0, 24, 0, 0)
        scanBox.addView(scanText)
        scanBox.visibility = View.GONE
        col.addView(scanBox)

        // ---- step 2 + 3: storage bar, then all modules (hidden until the scan is done) ----
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        storageText = TextView(this); storageText.textSize = 15f; storageText.setPadding(0, 16, 0, 8)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.max = 100
        note = TextView(this); note.textSize = 13f; note.setPadding(0, 16, 0, 8)
        note.setOnClickListener { if (!Environment.isExternalStorageManager()) requestAccess() }
        content.addView(storageText); content.addView(bar); content.addView(note)

        addCard(content, "Apps", "📱  Apps", "Size, data and cache of every app") {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        addCard(content, "Cache", "🧹  Cache cleaner", "Apps with the most clearable cache") {
            startActivity(Intent(this, CacheActivity::class.java))
        }
        listOf("Photos" to "🖼️  Photos", "Videos" to "🎬  Videos", "Audio" to "🎵  Audio",
               "Documents" to "📄  Documents", "Others" to "📦  Others (APKs, zips, misc)").forEach { (key, label) ->
            addCard(content, key, label, "...") {
                startActivity(Intent(this, CategoryActivity::class.java).putExtra(CategoryActivity.EXTRA, key))
            }
        }
        addCard(content, "WhatsApp", "💬  WhatsApp media", "Chat images, videos, voice notes, statuses") {
            startActivity(Intent(this, WhatsAppActivity::class.java))
        }
        addCard(content, "Leftover", "🗑️  Leftover folders", "Folders left behind by uninstalled apps") {
            startActivity(Intent(this, LeftoverActivity::class.java))
        }
        addCard(content, "Hidden", "👻  Hidden & trash", "Why Photos/Videos look bigger than you can see") {
            startActivity(Intent(this, HiddenActivity::class.java))
        }
        content.visibility = View.GONE
        col.addView(content)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun addCard(parent: LinearLayout, key: String, title: String, sub: String, onClick: () -> Unit) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(40, 36, 40, 36)
        val bg = GradientDrawable(); bg.setColor(Color.parseColor("#F1F3F4")); bg.cornerRadius = 32f
        card.background = bg
        card.isClickable = true
        card.setOnClickListener { onClick() }
        val t = TextView(this); t.text = title; t.textSize = 18f; t.setTextColor(Color.BLACK)
        val s = TextView(this); s.text = sub; s.textSize = 13f; s.setTextColor(Color.DKGRAY)
        card.addView(t); card.addView(s)
        subtitles[key] = s
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 24
        parent.addView(card, lp)
    }

    private fun requestAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun updateDevice() {
        val st = StatFs(Environment.getDataDirectory().path)
        val used = st.totalBytes - st.availableBytes
        storageText.text = "${fmt(used)} used of ${fmt(st.totalBytes)}  (${fmt(st.availableBytes)} free)"
        bar.progress = (used * 100 / st.totalBytes).toInt()
    }

    override fun onResume() {
        super.onResume()
        if (!Environment.isExternalStorageManager()) {
            // cannot scan files without permission: show the bar and the modules that still work
            updateDevice()
            scanBox.visibility = View.GONE; content.visibility = View.VISIBLE
            note.text = "Allow \"All files access\" to scan photos, videos and other files. Tap here to open the setting."
            if (!askedPermission) { askedPermission = true; requestAccess() }
        } else if (FileIndex.scanned) {
            showContent()
        } else {
            content.visibility = View.GONE; scanBox.visibility = View.VISIBLE
            handler.post(ticker)
            FileIndex.ensure { showContent() }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun showContent() {
        updateDevice()
        updateCards()
        scanBox.visibility = View.GONE
        content.visibility = View.VISIBLE
    }

    private fun updateCards() {
        note.text = "Tap a module to see details and free up space."
        val cats = setOf("Photos", "Videos", "Audio", "Documents", "Others")
        for ((key, tv) in subtitles) {
            if (key !in cats) continue
            tv.text = "${fmt(FileIndex.totalBytes[key] ?: 0L)}  •  ${FileIndex.totalCount[key] ?: 0} files"
        }
    }
}
