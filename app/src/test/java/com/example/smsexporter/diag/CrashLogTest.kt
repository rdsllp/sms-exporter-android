package com.example.smsexporter.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesReadsAndClears() {
        val dir = tmp.newFolder()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        try {
            assertNull(CrashLog.read(dir))
            val handler = CrashLog.install(dir, { _, e -> delegated = e }, trace = { it.stackTraceToString() })
            assertTrue(Thread.getDefaultUncaughtExceptionHandler() === handler)
            val boom = IllegalStateException("boom")
            handler.uncaughtException(Thread.currentThread(), boom)

            val log = CrashLog.read(dir)!!
            val firstLine = log.lineSequence().first()
            assertTrue(firstLine, Regex("""\d{4}-\d\d-\d\dT\S+ ${Regex.escape(Thread.currentThread().name)}""").matches(firstLine))
            assertTrue(log, log.lines()[1] == "java.lang.IllegalStateException")   // message "boom" stripped
            assertTrue(log, !log.contains("boom"))
            assertTrue(log, log.contains("CrashLogTest.writesReadsAndClears"))
            assertEquals(boom, delegated)

            CrashLog.clear(dir)
            assertNull(CrashLog.read(dir))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun sanitiseStripsMessagesAndMasksDigits() {
        val raw = """
            java.lang.IllegalStateException: call me at 9876543210
            	at com.example.smsexporter.Foo.bar(Foo.kt:12)
            	at android.os.Handler.dispatchMessage(Handler.java:132)
            Caused by: java.io.IOException: /data/user/0/com.example.smsexporter/files/12345678901.jpg
            second line of a multi-line message 5550001234
            	at java.io.FileInputStream.open(FileInputStream.java:219)
            	... 2 more
            	Suppressed: java.lang.RuntimeException: secret text
            		at com.example.smsexporter.Baz.qux(Baz.kt:7)
        """.trimIndent()
        val expected = """
            java.lang.IllegalStateException
            	at com.example.smsexporter.Foo.bar(Foo.kt:12)
            	at android.os.Handler.dispatchMessage(Handler.java:132)
            Caused by: java.io.IOException
            	at java.io.FileInputStream.open(FileInputStream.java:219)
            	... 2 more
            	Suppressed: java.lang.RuntimeException
            		at com.example.smsexporter.Baz.qux(Baz.kt:7)
        """.trimIndent()
        assertEquals(expected, CrashLog.sanitiseTrace(raw))
        assertEquals("at x.Y.z(Y.kt:1) ### ok", CrashLog.sanitiseTrace("\tat x.Y.z(Y.kt:1) 12345678 ok").trim())
    }
}
