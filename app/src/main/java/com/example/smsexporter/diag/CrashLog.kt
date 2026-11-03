package com.example.smsexporter.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Keeps the last uncaught crash in `filesDir/crash.log` so the device check report can include it.
 * The file holds "<ISO-8601 time> <thread name>" then the stack trace (no message content).
 * The `dir` overloads exist for unit tests, which have no Android context.
 */
object CrashLog {
    private const val FILE = "crash.log"

    /** Installs the handler for this process, wrapping the current default handler. */
    fun install(context: Context) {
        install(context.filesDir, Thread.getDefaultUncaughtExceptionHandler())
    }

    fun read(context: Context): String? = read(context.filesDir)

    fun clear(context: Context) = clear(context.filesDir)

    /**
     * Sets a default handler that overwrites `dir/crash.log`, then calls [previous]. Returns it.
     * [trace] formats the throwable (replaced in unit tests, where android.util.Log is unavailable).
     */
    fun install(
        dir: File,
        previous: Thread.UncaughtExceptionHandler?,
        trace: (Throwable) -> String = Log::getStackTraceString,
    ): Thread.UncaughtExceptionHandler {
        val handler = Thread.UncaughtExceptionHandler { thread, e ->
            // never let a failed write hide the crash from the previous handler
            runCatching { File(dir, FILE).writeText("${Instant.now()} ${thread.name}\n${trace(e)}") }
            previous?.uncaughtException(thread, e)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        return handler
    }

    /** The captured crash, or null when there is none. */
    fun read(dir: File): String? = File(dir, FILE).takeIf { it.isFile }?.readText()

    fun clear(dir: File) {
        File(dir, FILE).delete()
    }
}
