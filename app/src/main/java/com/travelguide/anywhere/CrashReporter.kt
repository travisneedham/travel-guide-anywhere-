package com.travelguide.anywhere

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE = "crash_report.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash report", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE)

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val report = buildString {
            appendLine("=== Travel Guide Anywhere Crash Report ===")
            appendLine("Time:    ${sdf.format(Date())}")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread:  ${thread.name}")
            appendLine()
            appendLine(throwable.stackTraceToString())
            var cause = throwable.cause
            while (cause != null) {
                appendLine("Caused by:")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
            }
        }
        crashFile(context).writeText(report)
    }
}
