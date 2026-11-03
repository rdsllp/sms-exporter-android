package com.example.smsexporter.data

import com.example.smsexporter.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFilterTest {
    @Test fun forConversationDropsQueryAndKind() {
        val list = MessageFilter(startMs = 1, endMs = 2, query = "axis", hasAttachment = true, kind = MessageKind.RCS)
        assertEquals(MessageFilter(startMs = 1, endMs = 2, hasAttachment = true), list.forConversation())
    }
}
