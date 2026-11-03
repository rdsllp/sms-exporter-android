package com.example.smsexporter.export

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object FileNames {
    private const val MAX_LENGTH = 60
    private const val FALLBACK = "conversation"

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/heic" to "heic",
        "video/mp4" to "mp4",
        "video/3gpp" to "3gp",
        "video/quicktime" to "mov",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/amr" to "amr",
        "audio/ogg" to "ogg",
        "text/x-vcard" to "vcf",
        "text/vcard" to "vcf",
        "application/pdf" to "pdf",
    )

    private val ZIP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.ENGLISH)

    /**
     * Keeps letters, digits, space and `+ - _ .` ('+' so phone-number titles survive); whitespace
     * runs become one space, dot runs one dot, edges lose dots and spaces, capped at 60 chars.
     */
    fun sanitize(title: String): String {
        val kept = title
            .map { if (it.isWhitespace()) ' ' else it }
            .filter { it.isLetterOrDigit() || it in " +-_." }
            .joinToString("")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\.{2,}"), ".")
        val trimmed = kept.trimEdges().take(MAX_LENGTH).trimEdges()
        return trimmed.ifEmpty { FALLBACK }
    }

    fun baseName(title: String, date: LocalDate): String = "${sanitize(title)}_$date"

    /** Case-insensitive (FAT/exFAT storage): `a`, `A` become `a`, `A (2)`. */
    fun deduplicate(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { name ->
            var candidate = name
            var n = 2
            while (!taken.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$name (${n++})"
            }
            candidate
        }
    }

    fun attachmentName(messageRowId: Long, index: Int, mimeType: String): String {
        val key = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return "${messageRowId}_$index.${EXTENSIONS[key] ?: "bin"}"
    }

    fun zipName(now: LocalDateTime): String = "SmsExporter_${ZIP_STAMP.format(now)}.zip"

    private fun String.trimEdges(): String = trim { it == '.' || it == ' ' }
}
