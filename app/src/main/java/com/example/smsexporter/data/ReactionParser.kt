package com.example.smsexporter.data

import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.Reaction
import java.text.BreakIterator

private const val ZWSP = "​"
private val RECEIVED_REACTION = Regex("^$ZWSP(.+?)$ZWSP to '(.*)'$", RegexOption.DOT_MATCHES_ALL)

/** Returns (emoji, quotedText) for a received RCS reaction body, or null if it is not one. */
fun parseReceivedReaction(body: String): Pair<String, String>? {
    val m = RECEIVED_REACTION.matchEntire(body.trim()) ?: return null
    return m.groupValues[1] to m.groupValues[2].trim()
}

/** True when the body is only 1-2 emoji (a sent reaction is stored as a bare emoji message). */
fun isSentReaction(body: String): Boolean {
    val text = body.trim()
    if (text.isEmpty()) return false
    if (!text.codePoints().allMatch { isEmojiCodePoint(it) }) return false
    return graphemeCount(text) in 1..2
}

/**
 * Tags reaction messages. Input is newest-first; order is preserved. A received reaction
 * targets the nearest older message (later in the list) whose trimmed body equals the quoted text.
 */
fun attachReactions(messagesNewestFirst: List<Message>): List<Message> =
    messagesNewestFirst.mapIndexed { i, m ->
        val body = m.body ?: return@mapIndexed m
        when (m.direction) {
            Direction.RECEIVED -> {
                val (emoji, quoted) = parseReceivedReaction(body) ?: return@mapIndexed m
                var target = null as Message?
                for (j in i + 1 until messagesNewestFirst.size) {
                    if (messagesNewestFirst[j].body?.trim() == quoted) { target = messagesNewestFirst[j]; break }
                }
                m.copy(reaction = Reaction(emoji, quoted, target?.id))
            }
            Direction.SENT ->
                if (isSentReaction(body)) m.copy(reaction = Reaction(body.trim(), null, null)) else m
            else -> m
        }
    }

private fun graphemeCount(text: String): Int {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    var n = 0
    while (it.next() != BreakIterator.DONE) n++
    return n
}

private fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
    in 0x1F000..0x1FAFF, in 0x2600..0x27BF, in 0x2B00..0x2BFF,
    0x3030, 0x303D, 0x3297, 0x3299, 0xA9, 0xAE, 0x203C, 0x2049, 0x2122, 0x2139,
    in 0x2194..0x21AA, in 0x231A..0x23FF, 0x24C2, in 0x25AA..0x25FE,
    0xFE0F, 0xFE0E, 0x200D, 0x20E3, in 0xE0020..0xE007F -> true
    else -> false
}
