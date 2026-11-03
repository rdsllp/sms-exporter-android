package com.example.smsexporter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotCardParserTest {
    @Test fun parsesGeneralPurposeCard() {
        val json = """{"message":{"generalPurposeCard":{"content":{"title":"Diwali Data Offer","description":"2GB/day","suggestions":[{"action":{"displayText":"View offer"}},{"reply":{"displayText":"Stop"}}]}}}}"""
        val c = BotCardParser.parse(json, 42)!!
        assertEquals("Diwali Data Offer", c.title); assertEquals("2GB/day", c.description); assertEquals(42L, c.imagePartId); assertEquals(listOf("View offer", "Stop"), c.suggestions)
    }

    @Test fun malformedBotJsonFallsBack() { assertNull(BotCardParser.parse("{not json", null)); assertNull(BotCardParser.parse("{}", null)) }

    @Test fun carouselUsesFirstCardOnly() {
        val json = """{"message":{"generalPurposeCardCarousel":{"content":[{"title":"First"},{"title":"Second"}]}}}"""
        val c = BotCardParser.parse(json, null)!!
        assertEquals("First", c.title); assertNull(c.description); assertNull(c.imagePartId)
    }

    @Test fun missingSuggestionsAndUnlabelledOnesAreSkipped() {
        assertEquals(emptyList<String>(), BotCardParser.parse("""{"message":{"generalPurposeCard":{"content":{"description":"d"}}}}""", null)!!.suggestions)
        val json = """{"message":{"generalPurposeCard":{"content":{"title":"t","suggestions":[{"other":{}},{"reply":{"displayText":"Yes"}}]}}}}"""
        assertEquals(listOf("Yes"), BotCardParser.parse(json, null)!!.suggestions)
    }

    @Test fun nonObjectJsonReturnsNull() { assertNull(BotCardParser.parse("[1,2]", null)); assertNull(BotCardParser.parse("", null)) }
}
