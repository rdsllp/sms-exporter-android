package com.example.smsexporter.data

import com.example.smsexporter.data.source.RawThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIndexTest {
    @Test fun caseVariantsMergeIntoOne() {
        val canonical = mapOf(679L to "JD-AXISBK", 693L to "JD-AxisBK", 809L to "JD-AxisBk")
        val threads = listOf(RawThread(679, listOf(679), 10, 192, "a", false), RawThread(693, listOf(693), 30, 8, "b", false), RawThread(809, listOf(809), 20, 257, "c", true))
        val out = ConversationIndex.build(threads, canonical, NameLookup { _, _ -> null })
        assertEquals(1, out.size)
        assertEquals(listOf(693L, 809L, 679L), out[0].threadIds)   // newest first
        assertEquals(457, out[0].messageCount); assertEquals(30, out[0].lastTimestampMs); assertEquals("b", out[0].snippet); assertTrue(out[0].hasAttachment)
    }

    @Test fun groupThreadsMergeBySet() {
        val canonical = mapOf(1L to "+911111111111", 2L to "+912222222222", 3L to "2222222222")
        val threads = listOf(RawThread(10, listOf(1, 2), 5, 1, null, false), RawThread(11, listOf(3, 1), 6, 1, null, false))
        assertEquals(1, ConversationIndex.build(threads, canonical, NameLookup { _, _ -> null }).size)
    }

    @Test fun unknownRecipientStillListed() {
        val threads = listOf(RawThread(10, listOf(999), 5, 3, "x", false))
        val out = ConversationIndex.build(threads, emptyMap(), NameLookup { _, _ -> null })
        assertEquals(1, out.size); assertEquals("Unknown", out[0].title)
    }

    @Test fun namesResolve() {
        val out = ConversationIndex.build(listOf(RawThread(1, listOf(1), 1, 1, null, false)), mapOf(1L to "+918617482941"), NameLookup { n, _ -> if (n == "num:8617482941") "Priya" else null })
        assertEquals("Priya", out[0].title)
    }
}
