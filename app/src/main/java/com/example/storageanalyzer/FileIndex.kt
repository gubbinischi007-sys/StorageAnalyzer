package com.example.storageanalyzer

import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File

class FileItem(val file: File, val size: Long, val modified: Long) {
    var checked = false
    val category: String = categoryOf(file)

    companion object {
        fun categoryOf(f: File): String = when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "Photos"
            "mp4", "mkv", "3gp", "webm", "avi", "mov" -> "Videos"
            "mp3", "m4a", "aac", "wav", "ogg", "opus", "amr" -> "Audio"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> "Documents"
            else -> "Others"   // APK, zip/rar archives and everything else
        }
    }
}

/** One scan of phone storage, shared by the home page and every module page. */
object FileIndex {
    const val MIN_SIZE = 1L * 1024 * 1024          // files of 1 MB+ are listed
    val items = mutableListOf<FileItem>()
    val totalBytes = mutableMapOf<String, Long>()  // ALL files per module (even small ones)
    val totalCount = mutableMapOf<String, Int>()
    @Volatile var scanned = false
    @Volatile var progress = 0                    // files checked so far (for the scanning screen)
    @Volatile private var scanning = false
    private val callbacks = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun ensure(onDone: () -> Unit) {
        if (scanned) { onDone(); return }
        callbacks.add(onDone)
        if (scanning) return
        scanning = true
        progress = 0
        Thread {
            val root = Environment.getExternalStorageDirectory()
            val list = mutableListOf<FileItem>()
            val bytes = mutableMapOf<String, Long>()
            val counts = mutableMapOf<String, Int>()
            root.walkTopDown()
                .onEnter { d -> !(d.parentFile == root && d.name == "Android") }
                .forEach { f ->
                    if (f.isFile) {
                        progress++
                        val len = f.length()
                        val cat = FileItem.categoryOf(f)
                        bytes[cat] = (bytes[cat] ?: 0L) + len
                        counts[cat] = (counts[cat] ?: 0) + 1
                        if (len >= MIN_SIZE) list.add(FileItem(f, len, f.lastModified()))
                    }
                }
            main.post {
                items.clear(); items.addAll(list)
                totalBytes.clear(); totalBytes.putAll(bytes)
                totalCount.clear(); totalCount.putAll(counts)
                scanned = true; scanning = false
                val cbs = callbacks.toList(); callbacks.clear()
                cbs.forEach { it() }
            }
        }.start()
    }

    fun remove(deleted: List<FileItem>) {
        items.removeAll(deleted.toSet())
        for (d in deleted) {
            totalBytes[d.category] = ((totalBytes[d.category] ?: 0L) - d.size).coerceAtLeast(0)
            totalCount[d.category] = ((totalCount[d.category] ?: 0) - 1).coerceAtLeast(0)
        }
    }
}
