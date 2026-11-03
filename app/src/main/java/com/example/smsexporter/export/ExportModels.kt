package com.example.smsexporter.export

import com.example.smsexporter.data.MessageFilter

enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    HTML("html", "text/html"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
}

data class ExportRequest(
    val conversationKeys: List<String>,
    val format: ExportFormat,
    val filter: MessageFilter,
)

sealed class ExportProgress {
    data class Running(val index: Int, val total: Int, val title: String, val fraction: Float) : ExportProgress()

    /**
     * [uri] is a string so JVM tests can build it; the UI calls `Uri.parse`. [failed] counts the
     * conversations of a multi-conversation zip that could not be exported (each has an
     * EXPORT_FAILED.txt in its folder instead).
     */
    data class Done(val uri: String, val displayPath: String, val mime: String, val failed: Int = 0) : ExportProgress()

    data class Failed(val message: String) : ExportProgress()

    object Cancelled : ExportProgress()
}
