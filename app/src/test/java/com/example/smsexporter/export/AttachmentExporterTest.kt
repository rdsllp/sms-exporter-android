package com.example.smsexporter.export

import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.data.model.Attachment
import com.example.smsexporter.data.source.FakeMessageSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.example.smsexporter.data.source.MessageSource
import org.junit.Test

class AttachmentExporterTest {
    private fun repo(bytes: Map<Long, ByteArray>) = MessageRepository(
        FakeMessageSource(emptyList(), emptyList(), emptyList(), emptyList(), partBytes = bytes),
        NameLookup { _, _ -> null },
    )

    private val att = Attachment(7L, "image/jpeg", "p.jpg", null)

    @Test
    fun missingAttachmentIsSkippedNotFatal() {
        val buf = ByteArrayOutputStream()
        val sink = ZipSink(buf)
        assertNull(AttachmentExporter(repo(emptyMap())).copy(att, sink, "attachments/7.jpg"))
        sink.finish()
        val names = ZipInputStream(ByteArrayInputStream(buf.toByteArray())).use { z ->
            generateSequence { z.nextEntry }.map { it.name }.toList()
        }
        assertEquals(emptyList<String>(), names)
    }

    @Test
    fun copiesBytesIntoSinkEntry() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val buf = ByteArrayOutputStream()
        val sink = ZipSink(buf)
        assertEquals(5L, AttachmentExporter(repo(mapOf(7L to data))).copy(att, sink, "attachments/7.jpg"))
        sink.finish()
        ZipInputStream(ByteArrayInputStream(buf.toByteArray())).use { z ->
            assertEquals("attachments/7.jpg", z.nextEntry.name)
            assertArrayEquals(data, z.readBytes())
        }
    }

    @Test
    fun sinkWriteFailurePropagates() {
        val failing = object : ExportSink {
            override fun open(path: String, mime: String): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
            }
            override fun finish() = ExportResult("", "")
            override fun abort() {}
            override fun close() {}
        }
        val r = repo(mapOf(7L to byteArrayOf(1, 2, 3)))
        assertThrows(IOException::class.java) { AttachmentExporter(r).copy(att, failing, "x") }
    }

    @Test
    fun inputFailureMidCopyReturnsNull() {
        val fake = FakeMessageSource(emptyList(), emptyList(), emptyList(), emptyList())
        val src = object : MessageSource by fake {
            override fun openPart(partId: Long): InputStream = object : InputStream() {
                var n = 0
                override fun read(): Int {
                    if (n++ >= 2) throw IOException("provider died")
                    return 1
                }
            }
        }
        val r = MessageRepository(src, NameLookup { _, _ -> null })
        val sink = ZipSink(ByteArrayOutputStream())
        assertNull(AttachmentExporter(r).copy(att, sink, "x"))
    }
}
