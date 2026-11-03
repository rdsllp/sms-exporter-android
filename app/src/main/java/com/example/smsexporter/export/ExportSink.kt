package com.example.smsexporter.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [uri] is a string so JVM tests can build it; the UI calls `Uri.parse`. */
data class ExportResult(val uri: String, val displayPath: String)

interface ExportSink : Closeable {
    /** [path] is relative to the export root, e.g. "Priya_2026-11-02/attachments/21562_1.jpg". */
    fun open(path: String, mime: String): OutputStream

    /** Publishes what was written; returns the single resulting item and where it is shown to the user. */
    fun finish(): ExportResult

    /** Deletes anything written. */
    fun abort()
}

/** One pending row in `MediaStore.Downloads`. */
private class PendingDownload(
    private val resolver: ContentResolver,
    name: String,
    mime: String,
    private val subDir: String,
) {
    val uri: Uri = resolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, subDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        },
    ) ?: throw IOException("MediaStore refused to create $name")

    fun openStream(): OutputStream =
        resolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")

    fun publish() {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /** MediaStore may rename on collision (`x (1).pdf`), so read the real name back. */
    fun displayPath(fallback: String): String {
        val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: fallback
        return "$subDir/$name"
    }

    fun delete() {
        runCatching { resolver.delete(uri, null, null) }
    }
}

/** One MediaStore entry per [open]; [finish] returns the last one. Use [ZipSink] for several files. */
class DownloadsSink(
    private val contentResolver: ContentResolver,
    private val subDir: String = "Download/SmsExporter",
) : ExportSink {
    private val rows = mutableListOf<PendingDownload>()
    private val names = mutableMapOf<PendingDownload, String>()
    private var stream: OutputStream? = null
    private var finished = false

    override fun open(path: String, mime: String): OutputStream {
        require('/' !in path) { "DownloadsSink needs a bare file name, got: $path" }
        closeStream()
        val row = PendingDownload(contentResolver, path, mime, subDir)
        rows += row
        names[row] = path
        val s = row.openStream()
        stream = s
        return s
    }

    override fun finish(): ExportResult {
        closeStream()
        rows.forEach { it.publish() }
        finished = true
        val last = rows.lastOrNull() ?: throw IllegalStateException("nothing was written")
        return ExportResult(last.uri.toString(), last.displayPath(names.getValue(last)))
    }

    override fun abort() {
        closeStream()
        rows.forEach { it.delete() }
        rows.clear()
        finished = true
    }

    override fun close() {
        if (!finished) abort()
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }
}

/** A single zip file; each [open] adds an entry. */
class ZipSink private constructor(private val target: Target) : ExportSink {
    private interface Target {
        val out: OutputStream
        fun finish(): ExportResult
        fun abort()
    }

    private class MemoryTarget(override val out: OutputStream, val displayPath: String) : Target {
        override fun finish() = ExportResult("", displayPath)
        override fun abort() {
            runCatching { out.close() }
        }
    }

    private class MediaTarget(val row: PendingDownload, val name: String) : Target {
        override val out: OutputStream = BufferedOutputStream(row.openStream())
        override fun finish(): ExportResult {
            row.publish()
            return ExportResult(row.uri.toString(), row.displayPath(name))
        }

        override fun abort() {
            runCatching { out.close() }
            row.delete()
        }
    }

    constructor(contentResolver: ContentResolver, zipFileName: String) :
        this(MediaTarget(PendingDownload(contentResolver, zipFileName, "application/zip", "Download/SmsExporter"), zipFileName))

    /** For tests: write the zip into any stream. */
    constructor(out: OutputStream, displayPath: String = "memory.zip") : this(MemoryTarget(out, displayPath))

    private val zip = ZipOutputStream(target.out)
    private var current: EntryStream? = null
    private var done = false

    /** Closing this ends the entry only; the zip stays open. */
    private inner class EntryStream : FilterOutputStream(zip) {
        private var closed = false
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            if (closed) return
            closed = true
            if (current === this) {
                current = null
                zip.closeEntry()
            }
        }
    }

    override fun open(path: String, mime: String): OutputStream {
        current?.close()
        zip.putNextEntry(ZipEntry(path))
        return EntryStream().also { current = it }
    }

    override fun finish(): ExportResult {
        current?.close()
        zip.finish()
        zip.close() // also closes the underlying file stream
        done = true
        return target.finish()
    }

    override fun abort() {
        runCatching { zip.close() }
        target.abort()
        done = true
    }

    override fun close() {
        if (!done) abort()
    }
}
