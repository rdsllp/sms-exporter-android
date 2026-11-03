package com.example.smsexporter.export

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileNamesTest {

    @Test
    fun sanitizeAndDeduplicate() {
        assertEquals("Axis BankCards", FileNames.sanitize("Axis Bank/Cards?"))
        val phone = FileNames.sanitize("+91 98009 97986")
        assertEquals("+91 98009 97986", phone)
        assertFalse(phone.contains('/'))
        assertEquals("Bank", FileNames.sanitize("...Bank..."))
        assertEquals("a.b", FileNames.sanitize("a.....b"))
        assertEquals("conversation", FileNames.sanitize(""))
        assertEquals("conversation", FileNames.sanitize("///"))
        assertEquals("conversation", FileNames.sanitize("   "))
        assertEquals("a b", FileNames.sanitize("a \t  b"))
        assertEquals(60, FileNames.sanitize("x".repeat(100)).length)
        assertEquals(listOf("a", "a (2)", "a (3)"), FileNames.deduplicate(listOf("a", "a", "a")))
        assertEquals(listOf("Priya", "priya (2)"), FileNames.deduplicate(listOf("Priya", "priya")))
    }

    @Test
    fun keepsCombiningMarksAndEmoji() {
        assertEquals("বাংলা", FileNames.sanitize("বাংলা"))
        assertEquals("মা", FileNames.sanitize("মা"))
        val family = "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Family"
        assertEquals(family, FileNames.sanitize(family))
        assertEquals("\uD83D\uDE00", FileNames.sanitize("\uD83D\uDE00"))
    }

    @Test
    fun capNeverSplitsAClusterOrSurrogatePair() {
        val ascii = "x".repeat(59)
        val bengali = FileNames.sanitize(ascii + "মা")
        assertEquals(ascii, bengali)
        val emoji = FileNames.sanitize(ascii + "\uD83D\uDE00\uD83D\uDE00")
        assertEquals(60, emoji.codePointCount(0, emoji.length))
        assertFalse(Character.isSurrogate(emoji.last()) && !Character.isLowSurrogate(emoji.last()))
        val joined = FileNames.sanitize(ascii + "\uD83D\uDC69\u200D\uD83D\uDC69")
        assertEquals(ascii, joined)
        assertFalse(Character.isSurrogate(joined.last()))
    }

    @Test
    fun baseNameAppendsDate() {
        assertEquals("Mom_2026-11-02", FileNames.baseName("Mom", LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun attachmentNameUsesMimeExtension() {
        assertEquals("21562_1.jpg", FileNames.attachmentName(21562, 1, "image/jpeg"))
        assertEquals("21562_2.mov", FileNames.attachmentName(21562, 2, "video/quicktime"))
        assertEquals("21562_3.bin", FileNames.attachmentName(21562, 3, "application/x-foo"))
        assertEquals("7_1.png", FileNames.attachmentName(7, 1, "IMAGE/PNG; name=x"))
    }

    @Test
    fun zipNameFormat() {
        assertEquals(
            "SmsExporter_2026-11-02_2130.zip",
            FileNames.zipName(LocalDateTime.of(2026, 11, 2, 21, 30)),
        )
    }
}
