package com.example.smsexporter.export

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPrinterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun printToBytes(html: String): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { PdfPrinter(context).print(html, out) }
        return out.toByteArray()
    }

    private fun pageCount(bytes: ByteArray): Int {
        val file = File.createTempFile("pdfprinter", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { return it.pageCount }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun producesValidPdf() {
        val bytes = printToBytes("<html><body><h1>Hi</h1></body></html>")
        assertTrue(bytes.size > 4)
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1, pageCount(bytes))
    }

    @Test
    fun longDocumentSpansPages() {
        val body = (1..400).joinToString("") { "<p>Line $it</p>" }
        val bytes = printToBytes("<html><body>$body</body></html>")
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))
        val pages = pageCount(bytes)
        assertTrue("expected >= 2 pages, got $pages", pages >= 2)
    }
}
