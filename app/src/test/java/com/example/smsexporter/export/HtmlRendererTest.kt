package com.example.smsexporter.export

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Attachment
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlRendererTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val renderer = HtmlRenderer("body{margin:0}", zone)
    private val exportedAt = ZonedDateTime.of(2026, 11, 2, 9, 5, 0, 0, zone)
    private val t0 = ZonedDateTime.of(2026, 10, 31, 18, 42, 0, 0, zone).toInstant().toEpochMilli()

    private val priya = Participant("num:9800997986", "+91 98009 97986", "Priya")
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
        attachments: List<Attachment> = emptyList(),
        reaction: Reaction? = null,
        address: String = "+91 98009 97986",
    ) = Message(
        MessageId(Source.SMS_TABLE, row), kind, dir, 1, address, t0, body, attachments, null, reaction,
    )

    private fun render(
        messages: List<Message>,
        filter: MessageFilter = MessageFilter(),
        c: Conversation = conv,
        image: (Attachment) -> String? = { null },
    ) = renderer.render(c, messages, filter, exportedAt, image)

    @Test
    fun escapesHtmlInBodies() {
        val html = render(listOf(msg(1, body = "<b>x</b> & \"q\"\nline2")))
        assertTrue(html, html.contains("&lt;b&gt;x&lt;/b&gt; &amp; &quot;q&quot;<br>line2"))
        assertFalse(html.contains("<b>x"))
        assertTrue(html.contains("RCS · 18:42"))
        assertTrue(html.contains("Sat, 31 Oct 2026"))
        assertTrue(html.contains("Exported 2 Nov 2026, 09:05"))
        assertTrue(html.contains("<style>body{margin:0}</style>"))
    }

    @Test
    fun embedsImageDataUri() {
        val photo = Attachment(7, "image/jpeg", "a.jpg", 10)
        val gone = Attachment(8, "image/png", null, 10)
        val video = Attachment(9, "video/mp4", "clip.mp4", 10)
        val html = render(listOf(msg(1, attachments = listOf(photo, gone, video))), image = {
            if (it.partId == 7L) "data:image/jpeg;base64,AAA" else null
        })
        assertTrue(html.contains("<img src=\"data:image/jpeg;base64,AAA\" alt=\"a.jpg\">"))
        assertTrue(html.contains("<span class=\"chip\">[photo missing]</span>"))
        assertTrue(html.contains("<span class=\"chip\">[video clip.mp4]</span>"))
    }

    @Test
    fun reactionBadgeInsideTargetBubble() {
        val target = msg(1, body = "dinner?", dir = Direction.SENT)
        val react = msg(
            2, body = null, kind = MessageKind.RCS,
            reaction = Reaction("👍", "dinner?", target.id),
        )
        val orphan = msg(3, body = null, reaction = Reaction("❤️", "old text", null))
        val html = render(listOf(target, react, orphan))
        val bubbleStart = html.indexOf("dinner?</div>")
        assertTrue(html, html.contains("<span class=\"react\">👍</span>"))
        assertTrue(html.indexOf("<span class=\"react\">👍</span>") > bubbleStart)
        assertEquals(2, Regex("class=\"bubble ").findAll(html).count())
        assertTrue(html.contains("❤️ to “old text”"))
    }

    @Test
    fun emptyConversationRendersNotice() {
        val html = render(emptyList())
        assertTrue(html.contains("<p class=\"empty\">No messages match the selected filter.</p>"))
        assertTrue(html.contains("0 messages"))
    }

    @Test
    fun headerShowsFilterWhenActive() {
        val active = render(listOf(msg(1)), MessageFilter(query = "axis"))
        assertTrue(active, active.contains("Filter: &quot;axis&quot;"))
        assertTrue(active.contains("1 message<"))
        assertFalse(render(listOf(msg(1))).contains("Filter:"))
    }

    @Test
    fun groupSenderAndFailedDirection() {
        val raj = Participant("num:9111111111", "+91 91111 11111", null)
        val group = conv.copy(participants = listOf(priya, raj))
        val html = render(
            listOf(msg(1, address = "+91 91111-11111"), msg(2, dir = Direction.FAILED, kind = MessageKind.SMS)),
            c = group,
        )
        assertTrue(html.contains("<div class=\"sender\">+91 91111 11111</div>"))
        assertTrue(html.contains("bubble out failed"))
        assertTrue(html.contains("SMS · 18:42 · Not sent"))
    }
}
