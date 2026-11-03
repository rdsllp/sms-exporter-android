package com.example.smsexporter.data

import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.source.RowFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the user asked to see in a conversation. Bounds are inclusive milliseconds. [startMs],
 * [endMs] and [query] are pushed down to the provider; [hasAttachment] and [kind] are applied in
 * memory by [MessageRepository].
 */
data class MessageFilter(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val query: String? = null,
    val hasAttachment: Boolean = false,
    val kind: MessageKind? = null,
) {
    private val queryOrNull: String? get() = query?.takeIf { it.isNotEmpty() }

    val isActive: Boolean
        get() = startMs != null || endMs != null || queryOrNull != null || hasAttachment || kind != null

    /** True when some of the filter can only be checked after rows are mapped to messages. */
    internal val needsInMemoryCheck: Boolean get() = hasAttachment || kind != null

    fun toRowFilter(): RowFilter = RowFilter(startMs, endMs, queryOrNull)

    /** e.g. `1 Jan 2026 – 31 Oct 2026 · "axis" · attachments only · RCS`; "" when inactive. */
    fun describe(): String {
        val range = when {
            startMs != null && endMs != null -> "${day(startMs)} – ${day(endMs)}"
            startMs != null -> "from ${day(startMs)}"
            endMs != null -> "until ${day(endMs)}"
            else -> null
        }
        return listOfNotNull(
            range,
            queryOrNull?.let { "\"$it\"" },
            "attachments only".takeIf { hasAttachment },
            kind?.name,
        ).joinToString(" · ")
    }

    private fun day(ms: Long): String =
        DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}
