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
     * Keeps letters, digits, combining marks (Indic vowel signs), ZWJ/ZWNJ, emoji and variation
     * selectors, space and `+ - _ .` ('+' so phone-number titles survive); whitespace runs become
     * one space, dot runs one dot, edges lose dots and spaces. Capped at 60 code points without
     * splitting a surrogate pair or a base character from the marks that follow it.
     */
    fun sanitize(title: String): String {
        val kept = StringBuilder()
        title.codePoints().forEach { cp ->
            when {
                Character.isWhitespace(cp) -> kept.append(' ')
                isKept(cp) -> kept.appendCodePoint(cp)
            }
        }
        val collapsed = kept.toString()
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\.{2,}"), ".")
        val trimmed = capCodePoints(collapsed.trimEdges()).trimEdges()
        return trimmed.ifEmpty { FALLBACK }
    }

    private fun isKept(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp) || isContinuation(cp)) return true
        if (cp == ' '.code || cp == '+'.code || cp == '-'.code || cp == '_'.code || cp == '.'.code) return true
        return when (Character.getType(cp)) {
            Character.OTHER_SYMBOL.toInt() -> true
            Character.MODIFIER_SYMBOL.toInt() -> Character.isSupplementaryCodePoint(cp)
            else -> false
        }
    }

    /** Code points that attach to the one before them: marks, ZWJ/ZWNJ, variation selector, skin tones. */
    private fun isContinuation(cp: Int): Boolean {
        if (cp == 0x200D || cp == 0x200C || cp == 0xFE0F) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            Character.MODIFIER_SYMBOL.toInt() -> Character.isSupplementaryCodePoint(cp)
            else -> false
        }
    }

    private fun capCodePoints(text: String): String {
        val cps = text.codePoints().toArray()
        if (cps.size <= MAX_LENGTH) return text
        var cut = MAX_LENGTH
        while (cut > 0 && (isContinuation(cps[cut]) || cps[cut - 1] == 0x200D)) cut--
        return String(cps, 0, cut)
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
