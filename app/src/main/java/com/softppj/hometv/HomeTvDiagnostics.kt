package com.softppj.hometv

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, bounded, private diagnostic log store.
 * Logs remain in app-private storage and are retained for at most 30 days.
 */
internal object HomeTvDiagnostics {
    private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_DAILY_BYTES = 512L * 1024L
    private const val MAX_REPORT_CHARS = 1_000_000
    @Volatile private var appContext: Context? = null
    @Volatile private var crashHandlerInstalled = false

    @Synchronized fun initialize(context: Context): Boolean {
        appContext = context.applicationContext
        val prefs = appContext!!.getSharedPreferences("hometv", Context.MODE_PRIVATE)
        val previousStartupWasUnclean = prefs.getBoolean("startup_in_progress", false)
        val crashCount = if (previousStartupWasUnclean) {
            prefs.getInt("startup_crash_count", 0) + 1
        } else {
            prefs.getInt("startup_crash_count", 0)
        }
        val safeMode = prefs.getBoolean("safe_startup_mode", false) || crashCount >= 2
        prefs.edit()
            .putBoolean("startup_in_progress", true)
            .putInt("startup_crash_count", crashCount)
            .putBoolean("safe_startup_mode", safeMode)
            .apply()
        prune()
        if (!crashHandlerInstalled) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                e("HomeTV", "Uncaught crash on thread " + thread.name, error)
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
            crashHandlerInstalled = true
        }
        return safeMode
    }

    @Synchronized fun markCleanExit(context: Context) {
        context.applicationContext.getSharedPreferences("hometv", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("startup_in_progress", false)
            .putInt("startup_crash_count", 0)
            .putBoolean("safe_startup_mode", false)
            .apply()
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        record("WARN", tag, message, error)
        if (error == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        record("ERROR", tag, message, error)
        if (error == null) android.util.Log.e(tag, message) else android.util.Log.e(tag, message, error)
    }

    fun i(tag: String, message: String, error: Throwable? = null) {
        record("INFO", tag, message, error)
        if (error == null) android.util.Log.i(tag, message) else android.util.Log.i(tag, message, error)
    }

    @Synchronized private fun record(level: String, tag: String, message: String, error: Throwable?) {
        val context = appContext ?: return
        try {
            val dir = File(context.filesDir, "diagnostics")
            if (!dir.exists() && !dir.mkdirs()) return
            prune(dir)
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "hometv-$day.log")
            val detail = if (error == null) "" else {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                "\n" + writer.toString()
            }
            val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date()) + " [" + level + "] " + tag + ": " + message + detail + "\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (file.length() + bytes.size > MAX_DAILY_BYTES) return
            file.appendBytes(bytes)
        } catch (_: Exception) {
            // Diagnostics must never interrupt playback or app startup.
        }
    }

    @Synchronized private fun prune(directory: File? = null) {
        val dir = directory ?: appContext?.let { File(it.filesDir, "diagnostics") } ?: return
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("hometv-") && it.name.endsWith(".log") } ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        files.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    @Synchronized fun readReport(): String {
        val dir = appContext?.let { File(it.filesDir, "diagnostics") } ?: return ""
        prune(dir)
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("hometv-") && it.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: return ""
        val all = files.joinToString("\n") { file ->
            "===== " + file.name + " =====\n" + try { file.readText(Charsets.UTF_8) } catch (_: Exception) { "[Unable to read log]\n" }
        }
        return if (all.length > MAX_REPORT_CHARS) all.takeLast(MAX_REPORT_CHARS) else all
    }
}
