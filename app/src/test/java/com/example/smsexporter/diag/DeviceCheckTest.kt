package com.example.smsexporter.diag

import android.Manifest
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.data.source.FakeMessageSource
import com.example.smsexporter.data.source.MessageSource
import com.example.smsexporter.data.source.RawAddr
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawPart
import com.example.smsexporter.data.source.RawSms
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class DeviceCheckTest {
    private val related = "application/vnd.wap.multipart.related"
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    /** A real 2x2 RGBA PNG (74 bytes). */
    private val png = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 6, 0, 0, 0, 114, -74, 13, 36,
        0, 0, 0, 17, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -16, 31, -124, 25, 96, 12, 0, 71, -54, 7, -7, 103, 89, 110, -73,
        0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
    )

    private val fake = FakeMessageSource(
        sms = listOf(
            RawSms(1, 1, "+911111111111", 1, now - 5 * hour, "Secret sms body"),
            RawSms(2, 1, "+911111111111", 2, now - 4 * hour, "Another secret"),
        ),
        mms = listOf(
            RawMms(10, 1, 2, now - hour, "proto:abc", related, true),
            RawMms(11, 2, 1, now - 2 * hour, "T1", related, false),
            RawMms(12, 2, 1, now - 3 * hour, "T2", related, true),
        ),
        parts = listOf(
            RawPart(100, 10, "text/plain", null, "Hello there", null),
            RawPart(101, 11, "image/png", "p.png", null, "/x/p.png"),
            RawPart(102, 12, "text/plain", null, "x", null),
        ),
        addrs = listOf(RawAddr(10, "+911111111111", 151), RawAddr(11, "+922222222222", 137), RawAddr(12, "+922222222222", 137)),
        partBytes = mapOf(101L to png),
    )

    private val env = object : DeviceEnv {
        override fun device() = "Acme Phone1 · Android 16 (SDK 36)"
        override fun defaultSmsApp() = "com.example.sms"
        override fun granted(permission: String) = permission == Manifest.permission.READ_SMS
        override fun crashLog(): String? = null
    }

    private val labels = listOf(
        "SmsExporter ", "permissions: ", "rows: ", "rcs last 7 days: ", "newest rcs rows: ",
        "image part read: ", "timings ms: ", "crash log: ",
    )

    private fun check(source: MessageSource) = DeviceCheck(
        env, source, MessageRepository(source, NameLookup { _, _ -> null }, log = {}),
        readBounds = { _: InputStream -> 2 to 2 }, now = { now },
    )

    private fun assertLabelsInOrder(lines: List<String>) {
        assertEquals(labels.size, lines.size)
        labels.zip(lines).forEach { (label, line) -> assertTrue("'$line' should start with '$label'", line.startsWith(label)) }
    }

    @Test fun reportHasEveryLabelInOrder() = runTest {
        val progress = mutableListOf<String>()
        val text = check(fake).run { progress += it }.text
        val lines = text.lines()
        assertLabelsInOrder(lines)
        assertTrue(lines[0], lines[0].endsWith(" · Acme Phone1 · Android 16 (SDK 36) · default SMS app: com.example.sms"))
        assertEquals("permissions: READ_SMS=yes READ_CONTACTS=no", lines[1])
        assertEquals("rows: sms=2 mms=3 rcs=1 threads=2 conversations=2", lines[2])
        assertEquals("rcs last 7 days: 1 (sent 1, received 0)", lines[3])
        assertTrue(lines[4], Regex("""newest rcs rows: \d{4}-\d\d-\d\d \d\d:\d\d sent thread 1 parts 1 text 11 chars""").matches(lines[4]))
        assertEquals("image part read: ok (2x2, 1 KB)", lines[5])
        assertTrue(lines[6], Regex("""timings ms: threads\+addresses=\d+ conversations=\d+ lastKinds=\d+ firstPage=\d+ \(\d+ rows\)""").matches(lines[6]))
        assertEquals("crash log: none", lines[7])
        assertFalse(text.contains("secret", ignoreCase = true) || text.contains("Hello") || text.contains("1111111"))
        assertTrue(progress.isNotEmpty())
    }

    @Test fun failingSectionIsReportedNotThrown() = runTest {
        val broken = object : MessageSource by fake {
            override fun openPart(partId: Long): InputStream? = throw IllegalStateException("cannot open part 12345678901")
        }
        val lines = check(broken).run().text.lines()
        assertLabelsInOrder(lines)
        assertEquals("image part read: ERROR IllegalStateException: cannot open part ###", lines[5])
        assertEquals("rows: sms=2 mms=3 rcs=1 threads=2 conversations=2", lines[2])
    }
}
