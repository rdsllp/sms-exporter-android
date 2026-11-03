package com.example.smsexporter.data

import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import com.example.smsexporter.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionParserTest {
    private val z = "​"

    private fun msg(id: String, ts: Long, dir: Direction, body: String) = Message(
        id = MessageId.parse(id),
        kind = MessageKind.RCS,
        direction = dir,
        threadId = 1,
        address = "+910000000000",
        timestampMs = ts,
        body = body,
    )

    @Test fun parsesReceivedReactionWithZeroWidthSpaces() {
        assertEquals("😂" to "I love you", parseReceivedReaction(" $z😂$z to ' I love you  ' "))
        assertNull(parseReceivedReaction("😂 to nothing"))
    }

    @Test fun attachesToNearestOlderMatch() {
        val m1 = msg("mms:1", 1000, Direction.SENT, "Okay"); val m2 = msg("mms:2", 2000, Direction.SENT, "Okay")
        val r = msg("mms:3", 3000, Direction.RECEIVED, "$z❤️$z to ' Okay '")
        val out = attachReactions(listOf(r, m2, m1))
        assertEquals(MessageId.parse("mms:2"), out[0].reaction?.targetId)
        assertEquals(listOf(r.id, m2.id, m1.id), out.map { it.id })
        assertNull(out[1].reaction)
    }

    @Test fun unmatchedReactionStaysAsMessage() {
        val r = msg("mms:3", 3000, Direction.RECEIVED, "$z❤️$z to ' gone '")
        val out = attachReactions(listOf(r))
        assertEquals("❤️", out[0].reaction?.emoji); assertNull(out[0].reaction?.targetId)
    }

    @Test fun sentEmojiOnlyIsReaction() {
        assertTrue(isSentReaction("👍")); assertTrue(isSentReaction("❤️")); assertFalse(isSentReaction("ok 👍")); assertFalse(isSentReaction("হ্যালো"))
    }

    @Test fun modifierAndJoinerSequencesAreSingleEmoji() {
        assertTrue(isSentReaction("👍🏽"))
        assertTrue(isSentReaction("👨‍👩‍👧"))
        assertTrue(isSentReaction(" 👍❤️ "))
    }

    @Test fun emptyOrLongEmojiRunsAreNotReactions() {
        assertFalse(isSentReaction(""))
        assertFalse(isSentReaction("   "))
        assertFalse(isSentReaction("👍👍👍"))
    }

    @Test fun sentEmojiGetsReactionWithoutTarget() {
        val s = msg("sms:5", 5000, Direction.SENT, "👍")
        val out = attachReactions(listOf(s))
        assertEquals("👍", out[0].reaction?.emoji)
        assertNull(out[0].reaction?.quotedText); assertNull(out[0].reaction?.targetId)
    }

    @Test fun plainMessagesAndEmptyBodiesAreUntouched() {
        val a = msg("sms:1", 1, Direction.RECEIVED, "hello")
        val b = a.copy(id = MessageId.parse("sms:2"), body = null)
        assertEquals(listOf(a, b), attachReactions(listOf(a, b)))
    }

    @Test fun parsesReactionToMultiLineQuote() {
        assertEquals("👍" to "line1\nline2", parseReceivedReaction("$z👍$z to ' line1\nline2 '"))
    }

    @Test fun attachesToMultiLineTarget() {
        val target = msg("mms:1", 1000, Direction.SENT, "line1\nline2")
        val r = msg("mms:2", 2000, Direction.RECEIVED, "$z👍$z to ' line1\nline2 '")
        val out = attachReactions(listOf(r, target))
        assertEquals(target.id, out[0].reaction?.targetId)
    }
}
