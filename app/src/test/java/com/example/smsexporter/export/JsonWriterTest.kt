package com.example.smsexporter.export

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.BotCard
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.model.Participant
import com.example.smsexporter.data.model.Reaction
import com.example.smsexporter.data.model.Source
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonWriterTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val exportedAt = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 999, zone)
    private val t0 = ZonedDateTime.of(2026, 10, 31, 18, 42, 7, 123_000_000, zone).toInstant().toEpochMilli()

    private val priya = Participant("num:9800997986", "+919800997986", "Priya")
    private val raj = Participant("num:9111111111", "+91 91111 11111", null)
    private val conv = Conversation(
        key = "num:9800997986", threadIds = listOf(1), participants = listOf(priya),
        lastTimestampMs = t0, messageCount = 1, snippet = null, lastKind = MessageKind.RCS,
        hasAttachment = false,
    )

    private fun msg(
        row: Long,
        body: String? = "hi",
        dir: Direction = Direction.RECEIVED,
        kind: MessageKind = MessageKind.RCS,
        reaction: Reaction? = null,
        botCard: BotCard? = null,
        address: String = "+919800997986",
    ) = Message(MessageId(Source.SMS_TABLE, row), kind, dir, 1, address, t0, body, emptyList(), botCard, reaction)

    private fun build(
        messages: List<Message>,
        filter: MessageFilter = MessageFilter(),
        c: Conversation = conv,
        names: (Message) -> List<JsonAttachment> = { emptyList() },
    ) = JsonWriter.build(c, messages, filter, exportedAt, names)

    @Test
    fun partialIsTrueWhenFilterActive() {
        val plain = build(listOf(msg(1)))
        assertFalse(plain.conversation.partial)
        assertNull(plain.conversation.filter)
        assertEquals("2026-11-02T09:05:07+05:30", plain.conversation.exportedAt)
        assertEquals(1, plain.conversation.messageCount)
        assertEquals("Priya", plain.conversation.title)
        assertEquals(listOf(JsonParticipant("+919800997986", "Priya")), plain.conversation.participants)

        val filtered = build(listOf(msg(1)), MessageFilter(query = "axis"))
        assertTrue(filtered.conversation.partial)
        assertEquals(JsonFilter(null, null, "axis", false, null), filtered.conversation.filter)

        val blankQuery = build(emptyList(), MessageFilter(hasAttachment = true, query = ""))
        assertNull(blankQuery.conversation.filter!!.query)
    }

    @Test
    fun reactionCarriesTargetId() {
        val target = msg(1)
        val react = msg(2, body = "Liked \"hi\"", reaction = Reaction("👍", "hi", target.id))
        val orphan = msg(3, reaction = Reaction("😂", null, null))
        val out = build(listOf(target, react, orphan)).messages
        assertNull(out[0].reaction)
        assertEquals(JsonReaction("👍", "sms:1"), out[1].reaction)
        assertEquals(JsonReaction("😂", null), out[2].reaction)
    }

    @Test
    fun senderAndRecipientsFollowCsvRules() {
        val group = conv.copy(participants = listOf(priya, raj))
        val received = msg(1, address = "+919800997986")
        val sent = msg(2, dir = Direction.SENT, kind = MessageKind.SMS)
        val failed = msg(3, dir = Direction.FAILED, kind = MessageKind.MMS)
        val out = build(listOf(received, sent, failed), c = group).messages
        assertEquals("sms:1", out[0].id)
        assertEquals("rcs", out[0].kind)
        assertEquals("received", out[0].direction)
        assertEquals("+919800997986", out[0].sender)
        assertEquals(listOf("+91 91111 11111"), out[0].recipients)
        assertEquals("sent", out[1].direction)
        assertEquals("me", out[1].sender)
        assertEquals(listOf("+919800997986", "+91 91111 11111"), out[1].recipients)
        assertEquals("failed", out[2].direction)
        assertEquals("mms", out[2].kind)
        assertEquals("me", out[2].sender)
        assertEquals("2026-10-31T18:42:07.123+05:30", out[0].timestamp)
    }

    @Test
    fun filterFieldsAreIsoInExportZone() {
        val start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 10, 31, 23, 59, 59, 0, zone).toInstant().toEpochMilli()
        val f = build(
            emptyList(),
            MessageFilter(startMs = start, endMs = end, query = "axis", hasAttachment = true, kind = MessageKind.RCS),
        ).conversation.filter
        assertEquals(
            JsonFilter("2026-01-01T00:00:00+05:30", "2026-10-31T23:59:59+05:30", "axis", true, "rcs"),
            f,
        )
    }

    @Test
    fun bodyBotCardAndAttachmentsMapThrough() {
        val card = BotCard("T", null, 5L, listOf("Yes", "No"))
        val m = msg(1, body = null, botCard = card)
        val att = JsonAttachment("a.jpg", "image/jpeg", 10L)
        val out = build(listOf(m), names = { listOf(att) }).messages.single()
        assertNull(out.body)
        assertEquals(JsonBotCard("T", null, listOf("Yes", "No")), out.botCard)
        assertEquals(listOf(att), out.attachments)
    }

    @Test
    fun roundTripsThroughKotlinxJson() {
        val group = conv.copy(participants = listOf(priya, raj))
        val target = msg(1, body = "café \"quoted\"\nline")
        val react = msg(2, reaction = Reaction("👍", "hi", target.id))
        val card = msg(3, body = null, botCard = BotCard("T", "D", null, listOf("Yes")))
        val built = build(
            listOf(target, react, card),
            MessageFilter(query = "x", kind = MessageKind.RCS),
            group,
            names = { if (it.id.rowId == 1L) listOf(JsonAttachment("a.jpg", "image/jpeg", null, true)) else emptyList() },
        )
        val text = JsonWriter.encode(built)
        assertEquals(built, Json.decodeFromString<JsonExport>(text))
        assertTrue(text, text.contains("\"missing\": true"))
        assertTrue(text, text.contains("\"filter\": null") .not())
        val plain = JsonWriter.encode(build(listOf(msg(9, body = null)), names = { listOf(JsonAttachment("b", "x/y", 1L)) }))
        assertTrue(plain, plain.contains("\"missing\": false"))
        assertTrue(plain, plain.contains("\"filter\": null"))
        assertTrue(plain, plain.contains("\"body\": null"))
    }
}
