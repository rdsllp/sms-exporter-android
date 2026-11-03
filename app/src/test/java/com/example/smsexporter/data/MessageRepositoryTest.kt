package com.example.smsexporter.data

import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.source.FakeMessageSource
import com.example.smsexporter.data.source.RawAddr
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawPart
import com.example.smsexporter.data.source.RawSms
import com.example.smsexporter.data.source.RawThread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryTest {
    private val related = "application/vnd.wap.multipart.related"
    private val noNames = NameLookup { _, _ -> null }

    @Test fun mergesTablesNewestFirstAndClassifies() = runTest {
        val fake = FakeMessageSource(
            sms = listOf(RawSms(1, 177, "+918617482941", 1, 1000, "Hello")),
            mms = listOf(RawMms(2, 177, 2, 2000, "proto:abc", related, true), RawMms(3, 177, 1, 1500, "T6a47ce96", related, false)),
            parts = listOf(RawPart(10, 2, "text/plain", null, "Hiiiii", null), RawPart(11, 3, "image/jpeg", "a.jpg", null, "/x/a.jpg")),
            addrs = listOf(RawAddr(2, "+918617482941", 151), RawAddr(3, "+918617482941", 137)),
        )
        val repo = MessageRepository(fake, noNames)
        val conv = repo.conversations().single()
        val page = repo.page(conv, MessageFilter(), null)
        assertEquals(listOf("mms:2", "mms:3", "sms:1"), page.map { it.id.toString() })
        assertEquals(listOf(MessageKind.RCS, MessageKind.MMS, MessageKind.SMS), page.map { it.kind })
        assertEquals("Hiiiii", page[0].body); assertEquals(1, page[1].attachments.size); assertEquals(Direction.SENT, page[0].direction)
    }

    @Test fun searchIsCaseInsensitiveAndUnicode() = runTest {
        val fake = FakeMessageSource(
            sms = listOf(RawSms(1, 5, "+911111111111", 1, 1000, "Hello, \"you\"\nthere"), RawSms(2, 5, "+911111111111", 2, 2000, "হ্যালো")),
            mms = emptyList(), parts = emptyList(), addrs = emptyList(),
        )
        val repo = MessageRepository(fake, noNames)
        val conv = repo.conversations().single()
        assertEquals(listOf("sms:1"), repo.page(conv, MessageFilter(query = "hello,"), null).map { it.id.toString() })
        assertEquals(listOf("sms:2"), repo.page(conv, MessageFilter(query = "হ্যা"), null).map { it.id.toString() })
    }

    @Test fun attachmentFilterKeepsPagingUntilLimit() = runTest {
        val textRows = (1L..5L).map { RawMms(it + 1, 7, 1, 2000 + it, "T$it", related, true) }
        val imageRow = RawMms(1, 7, 1, 1000, "T0", related, false)
        val fake = FakeMessageSource(
            sms = emptyList(),
            mms = textRows + imageRow,
            parts = textRows.map { RawPart(100 + it.id, it.id, "text/plain", null, "text ${it.id}", null) } +
                RawPart(50, 1, "image/png", "p.png", null, "/x/p.png"),
            addrs = (1L..6L).map { RawAddr(it, "+912222222222", 137) },
        )
        val repo = MessageRepository(fake, noNames)
        val conv = repo.conversations().single()
        val page = repo.page(conv, MessageFilter(hasAttachment = true), null, limit = 1)
        assertEquals(listOf("mms:1"), page.map { it.id.toString() })
        assertEquals(1, repo.count(conv, MessageFilter(hasAttachment = true)))
        assertEquals(6, repo.count(conv, MessageFilter()))
    }

    @Test fun pagingWithCursorDoesNotSkipSameSecondRows() = runTest {
        val fake = FakeMessageSource(
            sms = listOf(RawSms(9, 3, "+913333333333", 1, 5000, "s")),
            mms = (1L..3L).map { RawMms(it, 3, 1, 5000, "T$it", related, true) },
            parts = (1L..3L).map { RawPart(20 + it, it, "text/plain", null, "m$it", null) },
            addrs = (1L..3L).map { RawAddr(it, "+913333333333", 137) },
        )
        val repo = MessageRepository(fake, noNames)
        val conv = repo.conversations().single()
        val first = repo.page(conv, MessageFilter(), null, limit = 2)
        val second = repo.page(conv, MessageFilter(), PageCursor.of(first.last()), limit = 2)
        assertEquals(listOf("mms:3", "mms:2", "mms:1", "sms:9"), (first + second).map { it.id.toString() })
        assertEquals(listOf("mms:3", "mms:2", "mms:1", "sms:9"), repo.all(conv, MessageFilter()).map { it.id.toString() })
    }

    @Test fun fillLastKindsUsesNewestRowAcrossMergedThreads() = runTest {
        // threads 10 and 11 share one participant, so they merge; thread 10 is the "older"
        // thread by its thread date but holds the newest row (an RCS one). Thread 12 has no rows.
        val fake = FakeMessageSource(
            sms = listOf(RawSms(1, 11, "+914444444444", 1, 2000, "sms")),
            mms = listOf(RawMms(2, 10, 1, 3000, "proto:x", related, true)),
            parts = emptyList(),
            addrs = emptyList(),
            threads = listOf(
                RawThread(10, listOf(1), 100, 1, null, false),
                RawThread(11, listOf(1), 200, 1, null, false),
                RawThread(12, listOf(2), 50, 0, null, false),
            ),
            canonical = mapOf(1L to "+914444444444", 2L to "+915555555555"),
        )
        val repo = MessageRepository(fake, noNames)
        assertTrue(repo.conversations().all { it.lastKind == null })
        val filled = repo.fillLastKinds()
        assertEquals(2, filled.size)
        val merged = filled.single { it.threadIds.size == 2 }
        assertEquals(MessageKind.RCS, merged.lastKind)
        assertNull(filled.single { it.threadIds == listOf(12L) }.lastKind)
        assertEquals(filled, repo.conversations())   // cached
    }

    @Test fun describeFilter() {
        assertEquals("", MessageFilter().describe())
        assertTrue(MessageFilter(query = "axis", hasAttachment = true).describe().contains("axis"))
    }
}
