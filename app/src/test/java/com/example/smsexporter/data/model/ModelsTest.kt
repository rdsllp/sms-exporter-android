package com.example.smsexporter.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun messageIdRoundTrips() {
        val id = MessageId(Source.MMS_TABLE, 21562)
        assertEquals("mms:21562", id.toString())
        assertEquals(id, MessageId.parse("mms:21562"))
    }
    @Test fun attachmentFlags() {
        assertTrue(Attachment(1, "image/heic", null, null).isImage)
        assertTrue(Attachment(2, "video/quicktime", null, null).isVideo)
        assertEquals("content://mms/part/7", Attachment(7, "image/png", null, null).contentUri)
    }
    @Test fun conversationTitleJoinsParticipantLabels() {
        val c = Conversation("a|b", listOf(1), listOf(
            Participant("num:9800997986", "+919800997986", "Priya"),
            Participant("raw:jd-axisbk", "JD-AxisBk", null)), 0, 0, null, null, false)
        assertEquals("Priya, JD-AxisBk", c.title); assertTrue(c.isGroup)
    }
}
