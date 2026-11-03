package com.example.smsexporter.export

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.model.Participant
import com.example.smsexporter.data.model.Reaction
import com.example.smsexporter.data.model.Source
import java.io.StringWriter
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvWriterTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val exportedAt = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 999, zone)
    private val t0 = ZonedDateTime.of(2026, 10, 31, 18, 42, 7, 123_000_000, zone).toInstant().toEpochMilli()
    private val header = "id,conversation,kind,direction,sender,recipients,timestamp_iso,body,attachments,reaction_to"

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
        address: String = "+919800997986",
    ) = Message(MessageId(Source.SMS_TABLE, row), kind, dir, 1, address, t0, body, emptyList(), null, reaction)

    private fun csv(
        messages: List<Message>,
        filter: MessageFilter = MessageFilter(),
        c: Conversation = conv,
        names: (Message) -> List<String> = { emptyList() },
    ): String {
        val w = StringWriter()
        CsvWriter.write(w, c, messages, filter, exportedAt, names)
        return w.toString()
    }

    @Test
    fun bomPresent() {
        assertTrue(csv(emptyList()).startsWith("\uFEFF# SmsExporter"))
    }

    @Test
    fun headerAndCommentLine() {
        val plain = csv(emptyList())
        assertEquals(
            "\uFEFF# SmsExporter export 2026-11-02T09:05:07+05:30 · filter: none\r\n$header\r\n",
            plain,
        )
        val filtered = csv(emptyList(), MessageFilter(query = "axis"))
        assertTrue(filtered, filtered.startsWith("\uFEFF# SmsExporter export 2026-11-02T09:05:07+05:30 · filter: \"axis\"\r\n$header\r\n"))
    }

    @Test
    fun csvEscapesDangerousCells() {
        val out = csv(listOf(msg(1, body = "=1+1,\"x\"\nnew"), msg(2, body = "plain")))
        assertTrue(out, out.contains(",\"'=1+1,\"\"x\"\"\nnew\",,\r\n"))
        assertTrue(out, out.contains(",plain,,\r\n"))
        val tab = csv(listOf(msg(3, body = "\t@x"), msg(4, body = " pad")))
        assertTrue(tab, tab.contains(",'\t@x,,\r\n"))
        assertTrue(tab, tab.contains(",\" pad\",,\r\n"))
    }

    @Test
    fun senderRecipientsAndReactionTo() {
        val group = conv.copy(participants = listOf(priya, raj))
        val received = msg(1, address = "+919800997986")
        val sent = msg(2, dir = Direction.SENT, kind = MessageKind.SMS)
        val react = msg(3, body = "Liked \"hi\"", reaction = Reaction("👍", "hi", received.id))
        val out = csv(listOf(received, sent, react), c = group, names = { if (it.id.rowId == 1L) listOf("a.jpg", "b.png") else emptyList() })
        val lines = out.split("\r\n")
        assertEquals(
            "sms:1,\"Priya, +91 91111 11111\",rcs,received,+919800997986,+91 91111 11111,2026-10-31T18:42:07.123+05:30,hi,\"a.jpg;b.png\",",
            lines[2],
        )
        assertEquals(
            "sms:2,\"Priya, +91 91111 11111\",sms,sent,me,\"+919800997986;+91 91111 11111\",2026-10-31T18:42:07.123+05:30,hi,,",
            lines[3],
        )
        assertTrue(lines[4], lines[4].endsWith(",\"Liked \"\"hi\"\"\",,sms:1"))
        assertFalse(out.contains("'+919800997986"))
    }

    @Test
    fun joinedCellsAreGuardedOnce() {
        val a = msg(1)
        val b = msg(2)
        val out = csv(listOf(a, b), names = { if (it.id.rowId == 1L) listOf("photo.jpg", "@2x.png", "-x") else listOf("=x", "y") })
        val lines = out.split("\r\n")
        assertTrue(lines[2], lines[2].endsWith(",\"photo.jpg;@2x.png;-x\","))
        assertTrue(lines[3], lines[3].endsWith(",\"'=x;y\","))
    }

    @Test
    fun filterNewlinesDoNotBreakCommentLine() {
        val out = csv(emptyList(), MessageFilter(query = "a\nb"))
        assertEquals(2, out.trimEnd().split("\r\n").size)
    }

    @Test
    fun emptyJoinedElementDoesNotCrash() {
        val out = csv(listOf(msg(1)), names = { listOf("") })
        assertTrue(out, out.split("\r\n")[2].endsWith(",hi,,"))
    }
}
