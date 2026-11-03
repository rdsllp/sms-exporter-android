package com.example.smsexporter.export

import com.example.smsexporter.data.AddressNormalizer
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import java.io.Writer
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Writes one conversation as UTF-8-with-BOM CSV (the caller owns and closes [Writer]). */
object CsvWriter {
    private const val EOL = "\r\n"
    private val COLUMNS = listOf(
        "id", "conversation", "kind", "direction", "sender", "recipients",
        "timestamp_iso", "body", "attachments", "reaction_to",
    )
    private val PHONE_LIKE = Regex("^\\+?[0-9][0-9 ()-]*$")
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    fun write(
        out: Writer,
        conversation: Conversation,
        messages: List<Message>,
        filter: MessageFilter,
        exportedAt: ZonedDateTime,
        attachmentNames: (Message) -> List<String>,
    ) {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        out.write("\uFEFF")
        val stamp = exportedAt.truncatedTo(ChronoUnit.SECONDS).format(fmt)
        val filterText = if (filter.isActive) filter.describe().replace('\r', ' ').replace('\n', ' ') else "none"
        out.write("# SmsExporter export $stamp · filter: $filterText$EOL")
        out.write(COLUMNS.joinToString(",") + EOL)
        for (m in messages) {
            val sent = m.direction != Direction.RECEIVED
            val sender = if (sent) "me" else m.address
            val recipients = if (sent) {
                conversation.participants.map { it.rawAddress }
            } else {
                val from = AddressNormalizer.normalize(m.address)
                conversation.participants.filter { it.normalized != from }.map { it.rawAddress }
            }
            val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(m.timestampMs), exportedAt.zone).format(fmt)
            val cells = listOf(
                guard(m.id.toString()),
                guard(conversation.title),
                m.kind.name.lowercase(),
                m.direction.name.lowercase(),
                guard(sender),
                guardJoined(recipients),
                time,
                guard(m.body ?: ""),
                guardJoined(attachmentNames(m)),
                guard(m.reaction?.targetId?.toString() ?: ""),
            )
            out.write(cells.joinToString(",") { cell(it) } + EOL)
        }
    }

    /** Neutralises a leading formula character; phone numbers are left intact. */
    private fun guard(raw: String): String =
        if (raw.isNotEmpty() && raw[0] in FORMULA_START && !PHONE_LIKE.matches(raw)) "'$raw" else raw

    /** Joins with `;` then guards once: only the cell's first character matters to a spreadsheet. */
    private fun guardJoined(items: List<String>): String {
        val joined = items.joinToString(";")
        val first = items.firstOrNull() ?: return joined
        return if (joined.isNotEmpty() && joined[0] in FORMULA_START && !PHONE_LIKE.matches(first)) "'$joined" else joined
    }

    /** Quotes an already-guarded cell if it needs it. */
    private fun cell(safe: String): String {
        val needsQuotes = safe.any { it == '"' || it == ',' || it == '\n' || it == '\r' || it == ';' } ||
            safe.startsWith(' ') || safe.endsWith(' ')
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
