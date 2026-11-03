package com.example.smsexporter.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipSinkTest {
    private fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                out += e.name to zin.readBytes()
            }
        }
        return out
    }

    @Test
    fun entriesAppearWithGivenPathsAndBytes() {
        val buf = ByteArrayOutputStream()
        val sink = ZipSink(buf, "x.zip")
        sink.open("a/one.txt", "text/plain").use { it.write("hello".toByteArray()) }
        sink.open("a/attachments/1_1.jpg", "image/jpeg").use { it.write(byteArrayOf(1, 2, 3)) }
        val result = sink.finish()
        sink.close()
        assertEquals(ExportResult("", "x.zip"), result)
        val e = entries(buf.toByteArray())
        assertEquals(listOf("a/one.txt", "a/attachments/1_1.jpg"), e.map { it.first })
        assertArrayEquals("hello".toByteArray(), e[0].second)
        assertArrayEquals(byteArrayOf(1, 2, 3), e[1].second)
    }

    @Test
    fun openingSecondEntryClosesFirst() {
        val buf = ByteArrayOutputStream()
        val sink = ZipSink(buf)
        sink.open("a.txt", "text/plain").write(byteArrayOf(9))
        sink.open("b.txt", "text/plain").write(byteArrayOf(8))
        sink.finish()
        val e = entries(buf.toByteArray())
        assertEquals(listOf("a.txt", "b.txt"), e.map { it.first })
        assertArrayEquals(byteArrayOf(9), e[0].second)
        assertArrayEquals(byteArrayOf(8), e[1].second)
    }
}
