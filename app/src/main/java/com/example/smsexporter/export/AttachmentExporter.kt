package com.example.smsexporter.export

import android.util.Log
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.model.Attachment
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class AttachmentExporter(private val repo: MessageRepository) {
    /**
     * Returns bytes copied, or null when the provider cannot supply the bytes; an entry already
     * opened may be left truncated. Failures on the sink side (open or write) propagate.
     */
    fun copy(a: Attachment, sink: ExportSink, path: String): Long? {
        val raw = try {
            repo.openAttachment(a)
        } catch (e: IOException) {
            Log.w(TAG, "attachment ${a.partId} unreadable", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "attachment ${a.partId} not permitted", e)
            null
        } ?: return null
        return try {
            ReadErrors(raw).use { input -> sink.open(path, a.mimeType).use { out -> input.copyTo(out) } }
        } catch (e: AttachmentReadException) {
            Log.w(TAG, "attachment ${a.partId} failed while reading", e)
            null
        }
    }

    private class AttachmentReadException(cause: IOException) : IOException(cause)

    /** Tags input-side IOExceptions so they can be told apart from output-side ones. */
    private class ReadErrors(input: InputStream) : FilterInputStream(input) {
        private inline fun <T> tag(block: () -> T): T = try {
            block()
        } catch (e: AttachmentReadException) {
            throw e
        } catch (e: IOException) {
            throw AttachmentReadException(e)
        }

        override fun read(): Int = tag { super.read() }
        override fun read(b: ByteArray): Int = tag { super.read(b) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = tag { super.read(b, off, len) }
    }

    private companion object {
        const val TAG = "AttachmentExporter"
    }
}
