package app.suppliertracker.data

import android.content.Context
import android.os.Build
import app.suppliertracker.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the last crash on the phone so the next launch can offer to send it.
 * Only the error and the phone model are written – never supplier names or quantities.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Supplier Tracker – crash report")
            appendLine("When: ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace.take(16_000))
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
