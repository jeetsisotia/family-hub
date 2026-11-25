package com.familyhub.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Saves the details of a crash on the phone so the app can show them on the next launch.
 * The person can then share the text, which makes remote debugging possible without a cable.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("Time: ").append(Date().toString()).append('\n')
                    append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(sw.toString())
                }
                File(app.filesDir, FILE_NAME).writeText(text.take(20_000))
            } catch (_: Throwable) {
                // Never let the crash logger itself fail.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = try {
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) f.readText().ifBlank { null } else null
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
