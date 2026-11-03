package com.example.smsexporter.export

import com.example.smsexporter.data.AddressNormalizer
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Attachment
import com.example.smsexporter.data.model.BotCard
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Turns one conversation into a self-contained HTML page (CSS and images inlined). */
class HtmlRenderer(
    private val css: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val headerDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val exportedFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    /** Convenience for tests: the whole page as one String. Exports use the streaming overload. */
    fun render(
        conversation: Conversation,
        messages: List<Message>,
        filter: MessageFilter,
        exportedAt: ZonedDateTime,
        image: (Attachment) -> String?,
    ): String = StringBuilder().also { render(it, conversation, messages, filter, exportedAt, image) }.toString()

    /**
     * Writes the page into [out] piece by piece, so the whole document is never held in memory
     * (peak: one inlined image). [messages] are oldest first; [image] returns a data URI for an
     * attachment, or null if unreadable.
     */
    fun render(
        out: Appendable,
        conversation: Conversation,
        messages: List<Message>,
        filter: MessageFilter,
        exportedAt: ZonedDateTime,
        image: (Attachment) -> String?,
    ) {
        val title = escapeHtml(conversation.title)
        out.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        out.append("<title>").append(title).append("</title><style>").append(css)
        pageHeaderRule(out, conversation.title, exportedAt)
        out.append("</style></head><body>")
        header(out, conversation, messages.size, filter, exportedAt, title)
        if (messages.isEmpty()) {
            out.append("<p class=\"empty\">No messages match the selected filter.</p>")
        } else {
            body(out, conversation, messages, image)
        }
        out.append("</body></html>")
    }

    /**
     * The PDF's running header, "<title> · Exported <date>", as an `@page` margin box. Android
     * WebView renders literal margin-box content but not string-set/string(), so the text is
     * written into the rule itself.
     */
    private fun pageHeaderRule(out: Appendable, rawTitle: String, exportedAt: ZonedDateTime) {
        val suffix = " · Exported " + headerDateFmt.format(exportedAt.withZoneSameInstant(zone))
        // every control char becomes a space: CSS also ends a string at a form feed, not just \r/\n
        val oneLine = rawTitle.map { if (it < ' ' || it == '\u007F') ' ' else it }.joinToString("")
        val room = HEADER_MAX - suffix.length
        val name = if (oneLine.length <= room) oneLine else cutBefore(oneLine, room - 1).trimEnd() + "…"
        out.append("\n@page { @top-center { content: \"")
        cssString(out, name + suffix)
        out.append("\"; font-size: 10px; color: #666; } }\n")
    }

    /** The first [maxChars] chars of [s], one fewer if that would split a surrogate pair. */
    private fun cutBefore(s: String, maxChars: Int): String {
        val end = if (maxChars in 1 until s.length && s[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
        return s.take(end)
    }

    /** Escapes [s] for a double-quoted CSS string inside a <style> element. */
    private fun cssString(out: Appendable, s: String) {
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '<' -> out.append("\\3c ") // so the text can never close the <style> element
                else -> out.append(ch)
            }
        }
    }

    private fun header(
        sb: Appendable, c: Conversation, count: Int, filter: MessageFilter,
        exportedAt: ZonedDateTime, title: String,
    ) {
        sb.append("<header class=\"pagehead\"><h1>").append(title).append("</h1>")
        val names = c.participants.joinToString(", ") {
            val extra = if (it.displayName != null && it.displayName != it.rawAddress) " (${it.rawAddress})" else ""
            escapeHtml(it.label + extra)
        }
        sb.append("<div class=\"participants\">").append(names).append("</div>")
        sb.append("<div class=\"exported\">Exported ")
            .append(exportedFmt.format(exportedAt.withZoneSameInstant(zone))).append("</div>")
        if (filter.isActive) {
            sb.append("<div class=\"filter\">Filter: ").append(escapeHtml(filter.describe())).append("</div>")
        }
        sb.append("<div class=\"count\">").append(count.toString())
            .append(if (count == 1) " message" else " messages").append("</div></header>")
    }

    private fun body(sb: Appendable, c: Conversation, messages: List<Message>, image: (Attachment) -> String?) {
        val ids = messages.mapTo(HashSet()) { it.id }
        fun isBadge(m: Message) =
            m.reaction?.targetId != null && m.direction == Direction.RECEIVED && m.reaction.targetId in ids
        val badges: Map<MessageId, List<String>> = messages.filter(::isBadge)
            .groupBy({ it.reaction!!.targetId!! }, { it.reaction!!.emoji })

        sb.append("<main>")
        var lastDay: LocalDate? = null
        for (m in messages) {
            if (isBadge(m)) continue
            val at = Instant.ofEpochMilli(m.timestampMs).atZone(zone)
            if (at.toLocalDate() != lastDay) {
                lastDay = at.toLocalDate()
                sb.append("<div class=\"day\">").append(dayFmt.format(at)).append("</div>")
            }
            bubble(sb, c, m, at, badges[m.id].orEmpty(), image)
        }
        sb.append("</main>")
    }

    private fun bubble(
        sb: Appendable, c: Conversation, m: Message, at: ZonedDateTime,
        reactions: List<String>, image: (Attachment) -> String?,
    ) {
        val cls = when (m.direction) {
            Direction.RECEIVED -> "in"
            Direction.SENT -> "out"
            Direction.FAILED -> "out failed"
        }
        sb.append("<div class=\"bubble ").append(cls).append("\">")
        if (c.isGroup && m.direction == Direction.RECEIVED) {
            val key = AddressNormalizer.normalize(m.address)
            val name = c.participants.firstOrNull { it.normalized == key }?.label ?: m.address
            sb.append("<div class=\"sender\">").append(escapeHtml(name)).append("</div>")
        }
        val text = textOf(m)
        if (!text.isNullOrEmpty()) sb.append("<div class=\"text\">").append(multiline(text)).append("</div>")
        // a bot card shows its own image, which is also one of the attachments (as in MessageBubble)
        if (m.botCard == null) m.attachments.forEach { attachment(sb, it, image) }
        m.botCard?.let { card(sb, it, image) }
        reactions.forEach { sb.append("<span class=\"react\">").append(escapeHtml(it)).append("</span>") }
        sb.append("<div class=\"meta\">").append(m.kind.name).append(" · ").append(timeFmt.format(at))
        if (m.direction == Direction.FAILED) sb.append(" · Not sent")
        sb.append("</div></div>")
    }

    private fun textOf(m: Message): String? {
        val r = m.reaction ?: return m.body
        return if (r.quotedText != null) "${r.emoji} to “${r.quotedText}”" else r.emoji
    }

    private fun attachment(sb: Appendable, a: Attachment, image: (Attachment) -> String?) {
        when {
            a.isImage -> imageOrChip(sb, a, image)
            a.isVideo -> chip(sb, "[video ${a.fileName ?: "video"}]")
            else -> chip(sb, "[${a.fileName ?: a.mimeType}]")
        }
    }

    private fun imageOrChip(sb: Appendable, a: Attachment, image: (Attachment) -> String?) {
        val uri = image(a)
        if (uri == null) {
            chip(sb, "[photo missing]")
        } else {
            sb.append("<img src=\"")
            appendEscaped(sb, uri) // straight into the output: no second copy of a large data URI
            sb.append("\" alt=\"")
                .append(escapeHtml(a.fileName ?: "photo")).append("\">")
        }
    }

    private fun card(sb: Appendable, card: BotCard, image: (Attachment) -> String?) {
        sb.append("<div class=\"card\">")
        card.imagePartId?.let { imageOrChip(sb, Attachment(it, "image/*", null, null), image) }
        card.title?.takeUnless { it.isBlank() }
            ?.let { sb.append("<div class=\"card-title\">").append(multiline(it)).append("</div>") }
        card.description?.takeUnless { it.isBlank() }
            ?.let { sb.append("<div class=\"card-desc\">").append(multiline(it)).append("</div>") }
        card.suggestions.filter { it.isNotBlank() }.forEach { chip(sb, it) }
        sb.append("</div>")
    }

    private fun chip(sb: Appendable, text: String) {
        sb.append("<span class=\"chip\">").append(escapeHtml(text)).append("</span>")
    }

    private fun multiline(s: String) = escapeHtml(s).replace("\r\n", "\n").replace("\n", "<br>")

    private fun escapeHtml(s: String): String =
        StringBuilder(s.length + 16).also { appendEscaped(it, s) }.toString()

    private fun appendEscaped(sb: Appendable, s: String) {
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
    }

    private companion object {
        /** Longest header text, in characters, before the title is cut with "…". */
        const val HEADER_MAX = 80
    }
}
