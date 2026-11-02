package com.tormeo.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Crash reporting without any server: Java crashes, native crashes / OOM kills (via a "player running" marker
 * and, on Android 11+, the system's exit reasons) are turned into a readable report shown on the next launch.
 * Breadcrumbs from the player are persisted so we know how far playback got.
 */
object Diagnostics {
    private const val TAG = "Tormeo"
    private const val MAX_CRUMBS = 80
    private lateinit var dir: File
    private lateinit var appContext: Context
    private val crumbs = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val crashFile get() = File(dir, "crash.txt")
    private val crumbFile get() = File(dir, "crumbs.txt")
    private val sessionFile get() = File(dir, "player-session")
    private val lastReportFile get() = File(dir, "last-report.txt")

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(context.filesDir, "diag").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                log("CRASH in thread '${t.name}': $e")
                crashFile.writeText(report("Aplikacija je pala (nit: ${t.name})", stackTrace(e), crumbsText()))
                sessionFile.delete()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Adds a breadcrumb (also written to disk, so it survives native crashes and OOM kills). */
    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${time.format(Date())}  $msg"
        val snapshot = synchronized(crumbs) {
            crumbs.addLast(line)
            while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
            crumbs.joinToString("\n")
        }
        writer.execute { runCatching { crumbFile.writeText(snapshot) } }
    }

    fun error(msg: String, e: Throwable) = log("$msg: ${e.javaClass.simpleName}: ${e.message}")

    fun playerStarted() = runCatching { sessionFile.writeText(System.currentTimeMillis().toString()) }
    fun playerEnded() = runCatching { sessionFile.delete() }

    /** Report about the previous run, or null if it ended normally. Call once at startup. */
    fun takePendingReport(): String? {
        val text = when {
            crashFile.exists() -> crashFile.readText().also { crashFile.delete() }
            sessionFile.exists() -> {
                val exit = exitReason()
                report(
                    "Plejer se neočekivano zatvorio",
                    exit ?: "Proces je ugašen bez Java greške (nativni pad ili nedostatak memorije).",
                    runCatching { crumbFile.readText() }.getOrDefault("(nema zapisa)"),
                )
            }
            else -> null
        }
        sessionFile.delete()
        if (text != null) runCatching { lastReportFile.writeText(text) }
        return text
    }

    fun lastReport(): String? = runCatching { lastReportFile.readText() }.getOrNull()

    private fun exitReason(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 1).firstOrNull() ?: return null
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Java pad"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVNI pad (libtorrent / ffmpeg / dekoder)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Sistem ugasio aplikaciju – nedostatak memorije"
                ApplicationExitInfo.REASON_ANR -> "Aplikacija nije odgovarala (ANR)"
                ApplicationExitInfo.REASON_SIGNALED -> "Ugašena signalom ${info.status}"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Prekomerno korišćenje resursa"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "Korisnik/sistem zatvorio aplikaciju"
                else -> "Razlog #${info.reason}"
            }
            "$reason\n${info.description ?: ""}\nPSS: ${info.pss / 1024} MB · RSS: ${info.rss / 1024} MB"
        }.getOrNull()
    }

    private fun crumbsText() = synchronized(crumbs) { crumbs.joinToString("\n") }

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        .lineSequence().take(60).joinToString("\n")

    private fun report(title: String, details: String, trail: String): String = buildString {
        appendLine("== $title ==")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}")
        runCatching {
            val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            appendLine("Tormeo ${pi.versionName} · ${appContext.packageName}")
        }
        appendLine()
        appendLine(details.trim())
        appendLine()
        appendLine("-- poslednji koraci --")
        append(trail)
    }
}
