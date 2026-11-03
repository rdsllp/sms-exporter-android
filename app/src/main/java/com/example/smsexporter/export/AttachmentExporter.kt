package com.example.smsexporter.export

import android.util.Log
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.model.Attachment
import java.io.IOException

class AttachmentExporter(private val repo: MessageRepository) {
    /** Returns bytes copied, or null when the attachment is unreadable (nothing is written then). */
    fun copy(a: Attachment, sink: ExportSink, path: String): Long? {
        return try {
            val input = repo.openAttachment(a) ?: return null
            input.use { sink.open(path, a.mimeType).use { out -> it.copyTo(out) } }
        } catch (e: IOException) {
            Log.w(TAG, "attachment ${a.partId} unreadable", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "attachment ${a.partId} not permitted", e)
            null
        }
    }

    private companion object {
        const val TAG = "AttachmentExporter"
    }
}
