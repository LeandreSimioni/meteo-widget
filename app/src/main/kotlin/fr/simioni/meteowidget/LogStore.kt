package fr.simioni.meteowidget

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val FILE_NAME = "app_logs.txt"
    private const val MAX_AGE_MS = 2 * 60 * 60 * 1000L
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun append(context: Context, msg: String) {
        val now = System.currentTimeMillis()
        val formatted = "[${timeFmt.format(Date(now))}] $msg"
        try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            file.appendText("$now\t$formatted\n")
            pruneIfNeeded(file, now)
        } catch (e: Exception) {
            Log.e("LogStore", "Écriture échouée: ${e.message}")
        }
    }

    private fun pruneIfNeeded(file: File, now: Long) {
        val cutoff = now - MAX_AGE_MS
        val lines = file.readLines()
        val kept = lines.filter {
            it.substringBefore('\t').toLongOrNull()?.let { ts -> ts >= cutoff } == true
        }
        if (kept.size < lines.size) {
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        }
    }

    @Synchronized
    fun getLogs(context: Context): List<String> {
        return try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            file.readLines()
                .filter { it.contains('\t') }
                .map { it.substringAfter('\t') }
        } catch (e: Exception) {
            Log.e("LogStore", "Lecture échouée: ${e.message}")
            emptyList()
        }
    }
}
