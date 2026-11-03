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
            assertTrue(log, log.contains("java.lang.IllegalStateException: boom"))
            assertTrue(log, log.contains("CrashLogTest.writesReadsAndClears"))
            assertEquals(boom, delegated)

            CrashLog.clear(dir)
            assertNull(CrashLog.read(dir))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
