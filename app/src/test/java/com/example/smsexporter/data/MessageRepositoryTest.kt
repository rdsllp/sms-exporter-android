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
import java.time.LocalDate
import java.time.ZoneId

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

    @Test fun smsCursorDoesNotSkipSameSecondMms() = runTest {
        // the sms is half a second newer than two mms rows stored as whole seconds
        val fake = FakeMessageSource(
            sms = listOf(RawSms(9, 4, "+916666666666", 1, 5_000_500, "s")),
            mms = (1L..2L).map { RawMms(it, 4, 1, 5_000_000, "T$it", related, true) },
            parts = emptyList(),
            addrs = (1L..2L).map { RawAddr(it, "+916666666666", 137) },
        )
        val repo = MessageRepository(fake, noNames)
        val conv = repo.conversations().single()
        val seen = mutableListOf<String>()
        var cursor: PageCursor? = null
        repeat(5) {
            val p = repo.page(conv, MessageFilter(), cursor, limit = 1)
            if (p.isEmpty()) return@repeat
            seen += p.map { it.id.toString() }
            cursor = PageCursor.of(p.last())
        }
        assertEquals(listOf("sms:9", "mms:2", "mms:1"), seen)
    }

    @Test fun conversationKeysWithTextFindsBodiesInOnlyMatchingConversation() = runTest {
        val fake = FakeMessageSource(
            sms = listOf(
                RawSms(1, 20, "JD-AxisBk", 1, 1000, "Your OTP is 1234"),
                RawSms(2, 21, "+917777777777", 1, 2000, "see you at lunch"),
            ),
            mms = listOf(RawMms(3, 21, 1, 3000, "T3", related, true)),
            parts = listOf(RawPart(30, 3, "text/plain", null, "photo later", null)),
            addrs = listOf(RawAddr(3, "+917777777777", 137)),
        )
        val repo = MessageRepository(fake, noNames)
        val keys = repo.conversations().associate { it.threadIds.single() to it.key }
        assertEquals(setOf(keys.getValue(20)), repo.conversationKeysWithText("otp"))
        assertEquals(setOf(keys.getValue(21)), repo.conversationKeysWithText("PHOTO"))
        assertEquals(emptySet<String>(), repo.conversationKeysWithText("nothing here"))
    }

    @Test fun describeFilter() {
        assertEquals("", MessageFilter().describe())
        val zone = ZoneId.systemDefault()
        val jan1 = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val oct31 = LocalDate.of(2026, 10, 31).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            "1 Jan 2026 – 31 Oct 2026 · \"axis\" · attachments only · RCS",
            MessageFilter(startMs = jan1, endMs = oct31, query = "axis", hasAttachment = true, kind = MessageKind.RCS).describe(),
        )
        assertEquals("from 1 Jan 2026", MessageFilter(startMs = jan1).describe())
    }
}
