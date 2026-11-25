package com.example.storageanalyzer

import android.app.Activity
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/** One app: App size, User data (tap to see what is inside), Cache. */
class AppDetailActivity : Activity() {
    companion object { const val EXTRA_PKG = "pkg" }

    private val typical = mapOf(
        "com.whatsapp" to "Chat database and local backups, stickers, profile photos and status cache, login and encryption keys.",
        "com.whatsapp.w4b" to "Chat database and local backups, catalogue and business data, login and encryption keys.",
        "com.instagram.android" to "Pre-loaded reels and feed cache, messages and drafts, login and account settings.",
        "com.google.android.youtube" to "Offline downloads, watch history, thumbnails and search data, login.",
        "com.android.chrome" to "History, cookies, saved logins, offline pages and site data, bookmarks and sync data.",
        "com.spotify.music" to "Offline downloaded songs and podcasts (encrypted), playlists, listening history, login.",
        "com.snapchat.android" to "Chat and snap cache, memories data, lens files, login.",
        "org.telegram.messenger" to "Chat cache and message database, stickers, voice messages, login session."
    )
    private lateinit var pkg: String
    private lateinit var holder: LinearLayout

    private fun fmt(x: Long) = Formatter.formatShortFileSize(this, x)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.setPadding(32, 64, 32, 48)
        val sv = ScrollView(this)
        sv.addView(holder)
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        Thread {
            try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                val cache = s.cacheBytes
                val data = (s.dataBytes - cache).coerceAtLeast(0)
                // files this app saved to shared storage (photos, videos, downloads...) via MediaStore owner
                val groups = linkedMapOf("Photos" to LongArray(2), "Videos" to LongArray(2), "Audio" to LongArray(2),
                    "Documents" to LongArray(2), "Others" to LongArray(2))
                contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?", arrayOf(pkg), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        val g = groups.getValue(FileItem.categoryOf(File(path)))
                        g[0] += c.getLong(1); g[1]++
                    }
                }
                val label = ai.loadLabel(pm).toString()
                val icon = ai.loadIcon(pm)
                runOnUiThread { build(label, icon, s.appBytes, data, cache, groups) }
            } catch (e: Exception) {
                runOnUiThread { finish() }
            }
        }.start()
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t; v.textSize = size; v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun card(title: String, sub: String, onClick: (() -> Unit)?): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(32, 28, 32, 28)
        val bg = GradientDrawable(); bg.setColor(Color.parseColor("#F1F3F4")); bg.cornerRadius = 28f
        r.background = bg
        r.addView(text(title, 16f, Color.BLACK))
        r.addView(text(sub, 12f, Color.DKGRAY))
        if (onClick != null) { r.isClickable = true; r.setOnClickListener { onClick() } }
        return r
    }

    private fun addTo(parent: LinearLayout, v: View) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 20
        parent.addView(v, lp)
    }

    private fun build(label: String, icon: Drawable?, apk: Long, data: Long, cache: Long, groups: Map<String, LongArray>) {
        holder.removeAllViews()
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL; head.gravity = Gravity.CENTER_VERTICAL
        val iv = ImageView(this); iv.setImageDrawable(icon)
        head.addView(iv, LinearLayout.LayoutParams(120, 120))
        val name = text(label, 22f, Color.BLACK, true); name.setPadding(24, 0, 0, 0)
        head.addView(name)
        holder.addView(head)

        addTo(holder, card("App size  -  ${fmt(apk)}", "The installed app itself", null))

        // ---- User data: tap to expand ----
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.visibility = View.GONE
        box.setPadding(16, 0, 16, 0)
        val userRow = card("User data  -  ${fmt(data)}   (tap to see what is inside)",
            "Accounts, settings, databases and offline files the app keeps for itself", null)
        userRow.isClickable = true
        userRow.setOnClickListener { box.visibility = if (box.visibility == View.GONE) View.VISIBLE else View.GONE }
        addTo(holder, userRow)
        holder.addView(box)

        addTo(box, text("Inside user data (private to the app)", 14f, Color.BLACK, true))
        box.addView(text(typical[pkg] ?: "Accounts and login, settings, databases and offline files the app keeps for itself.", 13f, Color.DKGRAY))
        box.addView(text("Android seals each app's private folder, so no other app can open or clear it. You can reset it with Clear storage in the app's settings; that logs you out and removes offline data.", 12f, Color.DKGRAY))

        addTo(box, text("Also saved in your storage by this app (counted separately)", 14f, Color.BLACK, true))
        val shared = groups.filter { it.value[1] > 0 }
        if (shared.isEmpty()) box.addView(text("No photos, videos, audio, documents or other files found.", 12f, Color.DKGRAY))
        for ((kind, v) in shared) {
            addTo(box, card("$kind  -  ${fmt(v[0])}", "${v[1]} files  |  tap to review and delete") {
                startActivity(Intent(this, CategoryActivity::class.java)
                    .putExtra(CategoryActivity.EXTRA_PKG, pkg)
                    .putExtra(CategoryActivity.EXTRA_KIND, kind)
                    .putExtra(CategoryActivity.EXTRA_TITLE, "$label - $kind"))
            })
        }

        addTo(holder, card("Cache  -  ${fmt(cache)}", "Temporary files, safe to clear", null))
        val btn = Button(this)
        btn.text = "Open App info (Clear cache / Clear storage)"
        btn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
        addTo(holder, btn)
    }
}
