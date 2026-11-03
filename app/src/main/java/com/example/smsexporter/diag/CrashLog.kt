package com.example.smsexporter.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Keeps the last uncaught crash in `filesDir/crash.log` so the device check report can include it.
 * The file holds "<ISO-8601 time> <thread name>" then the stack trace passed through
 * [sanitiseTrace]: exception class names and frames only, since exception messages can carry
 * message text or phone numbers. The `dir` overloads exist for unit tests, which have no Android context.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private val LONG_DIGITS = Regex("""\d{7,}""")
    private val HEADER_PREFIXES = listOf("Caused by: ", "Suppressed: ")

    /** Installs the handler for this process, wrapping the current default handler. */
    fun install(context: Context) {
        install(context.filesDir, Thread.getDefaultUncaughtExceptionHandler())
    }

    fun read(context: Context): String? = read(context.filesDir)

    fun clear(context: Context) = clear(context.filesDir)

    /**
     * Sets a default handler that overwrites `dir/crash.log` with the sanitised trace, then calls [previous]. Returns it.
     * [trace] formats the throwable (replaced in unit tests, where android.util.Log is unavailable).
     */
    fun install(
        dir: File,
        previous: Thread.UncaughtExceptionHandler?,
        trace: (Throwable) -> String = Log::getStackTraceString,
    ): Thread.UncaughtExceptionHandler {
        val handler = Thread.UncaughtExceptionHandler { thread, e ->
            // never let a failed write hide the crash from the previous handler
            runCatching { File(dir, FILE).writeText("${Instant.now()} ${thread.name}\n${sanitiseTrace(trace(e))}") }
            previous?.uncaughtException(thread, e)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        return handler
    }

    /**
     * Keeps only exception class names and stack frames of a printed stack trace. On the first line
     * and on each `Caused by:` / `Suppressed:` line everything from the first ": " after the class
     * name is cut (the message); `at …` and `... n more` lines are kept; any other line (the rest
     * of a multi-line message) is dropped. Digit runs of 7 or more are then masked as `###`.
     */
    fun sanitiseTrace(raw: String): String = raw.lines().mapIndexedNotNull { i, line ->
        val trimmed = line.trimStart()
        val prefix = HEADER_PREFIXES.firstOrNull { trimmed.startsWith(it) }
        when {
            trimmed.startsWith("at ") || trimmed.startsWith("... ") -> line
            prefix != null -> line.substring(0, line.length - trimmed.length) + prefix + trimmed.removePrefix(prefix).substringBefore(": ")
            i == 0 -> line.substringBefore(": ")
            else -> null
        }
    }.joinToString("\n").replace(LONG_DIGITS, "###")

    /** The captured crash, or null when there is none. */
    fun read(dir: File): String? = File(dir, FILE).takeIf { it.isFile }?.readText()

    fun clear(dir: File) {
        File(dir, FILE).delete()
    }
}
