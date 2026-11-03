package com.example.smsexporter.export

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.data.source.FakeMessageSource
import com.example.smsexporter.data.source.RawAddr
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawPart
import com.example.smsexporter.data.source.RawSms
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExportCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = ZonedDateTime.of(2026, 11, 2, 10, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))
    private val related = "application/vnd.wap.multipart.related"
    private val imageBytes = byteArrayOf(9, 8, 7, 6)
    private val a = "+911111111111"
    private val b = "+922222222222"
    private val folderA = "${a}_2026-11-02"
    private val folderB = "${b}_2026-11-02"

    /** Conversation A: an sms and an mms with a text part and an image part (part 20). B: one sms. */
    private fun repo(partBytes: Map<Long, ByteArray> = mapOf(20L to imageBytes)) = MessageRepository(
        FakeMessageSource(
            sms = listOf(RawSms(1, 1, a, 1, 1_000, "hello world"), RawSms(3, 2, b, 1, 1_500, "other chat")),
            mms = listOf(RawMms(2, 1, 1, 2_000, "T2", related, false)),
            parts = listOf(
                RawPart(20, 2, "image/jpeg", "p.jpg", null, "/x/p.jpg"),
                RawPart(21, 2, "text/plain", null, "pic caption", null),
            ),
            addrs = listOf(RawAddr(2, a, 137)),
            partBytes = partBytes,
        ),
        NameLookup { _, _ -> null },
        log = {},
    )

    private class FakeSink(private val failFinish: Boolean = false) : ExportSink {
        val files = LinkedHashMap<String, ByteArrayOutputStream>()
        val mimes = LinkedHashMap<String, String>()
        var finished = false
        var aborted = false

        override fun open(path: String, mime: String): OutputStream {
            mimes[path] = mime
            return ByteArrayOutputStream().also { files[path] = it }
        }

        override fun finish(): ExportResult {
            if (failFinish) throw IOException("publish failed")
            finished = true
            return ExportResult("mem://x", "mem/x")
        }

        override fun abort() {
            aborted = true
        }

        override fun close() {}

        fun text(path: String): String = files.getValue(path).toString(Charsets.UTF_8.name())
    }

    private class Harness(val coordinator: ExportCoordinator, val sink: FakeSink, val zipNames: MutableList<String?>)

    private fun TestScope.harness(
        repo: MessageRepository = repo(),
        sink: FakeSink = FakeSink(),
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        pdf: suspend (File, OutputStream) -> Unit = { html, out -> out.write("%PDF-fake ${html.readText()}".toByteArray()) },
    ): Harness {
        val zipNames = mutableListOf<String?>()
        val c = ExportCoordinator(
            repo = repo,
            html = HtmlRenderer(""),
            pdf = pdf,
            sinkFactory = { name -> zipNames += name; sink },
            clock = { now },
            cacheDir = tmp.root,
            scale = { "data:image/jpeg;base64,AAA" },
            attachments = AttachmentExporter(repo, {}),
            dispatcher = dispatcher,
            log = {},
        )
        return Harness(c, sink, zipNames)
    }

    private suspend fun keyOf(repo: MessageRepository, address: String) =
        repo.conversations().single { it.title == address }.key

    private fun request(keys: List<String>, format: ExportFormat, filter: MessageFilter = MessageFilter()) =
        ExportRequest(keys, format, filter)

    @Test
    fun singlePdfGoesToSingleFile() = runTest {
        val r = repo()
        val h = harness(r)
        val events = h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.PDF)).toList()
        assertEquals(listOf<String?>(null), h.zipNames)
        assertEquals(listOf("$folderA.pdf"), h.sink.files.keys.toList())
        assertEquals("application/pdf", h.sink.mimes.getValue("$folderA.pdf"))
        assertTrue(h.sink.text("$folderA.pdf").startsWith("%PDF-fake <!doctype html>"))
        assertTrue(h.sink.text("$folderA.pdf").contains("data:image/jpeg;base64,AAA"))
        assertEquals(ExportProgress.Done("mem://x", "mem/x", "application/pdf"), events.last())
        assertTrue(h.sink.finished)
        assertEquals("temp html deleted", emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun multiSelectProducesZipWithFolderPerConversation() = runTest {
        val r = repo()
        val h = harness(r)
        val events = h.coordinator.run(request(listOf(keyOf(r, a), keyOf(r, b)), ExportFormat.JSON)).toList()
        assertEquals(listOf<String?>("SmsExporter_2026-11-02_1015.zip"), h.zipNames)
        val paths = h.sink.files.keys.toList()
        assertTrue(paths.toString(), "$folderA/$folderA.json" in paths)
        assertTrue(paths.toString(), "$folderB/$folderB.json" in paths)
        assertTrue(paths.toString(), "$folderA/attachments/2_1.jpg" in paths)
        assertTrue(h.sink.text("$folderB/$folderB.json").contains("other chat"))
        assertEquals(ExportProgress.Done("mem://x", "mem/x", "application/zip"), events.last())
    }

    @Test
    fun duplicateFolderNamesAreDeduplicated() = runTest {
        val r = repo()
        val h = harness(r)
        val key = keyOf(r, b)
        h.coordinator.run(request(listOf(key, key), ExportFormat.HTML)).toList()
        assertEquals(listOf("$folderB/$folderB.html", "$folderB (2)/$folderB (2).html"), h.sink.files.keys.toList())
    }

    @Test
    fun csvWithAttachmentsIsZipped() = runTest {
        val r = repo()
        val h = harness(r)
        val events = h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.CSV)).toList()
        assertEquals(listOf<String?>("SmsExporter_2026-11-02_1015.zip"), h.zipNames)
        assertEquals(listOf("$folderA/attachments/2_1.jpg", "$folderA/$folderA.csv"), h.sink.files.keys.toList())
        assertTrue(h.sink.files.getValue("$folderA/attachments/2_1.jpg").toByteArray().contentEquals(imageBytes))
        val csv = h.sink.text("$folderA/$folderA.csv")
        assertTrue(csv, csv.lines().any { it.startsWith("mms:2,") && it.contains(",2_1.jpg,") })
        assertEquals("application/zip", (events.last() as ExportProgress.Done).mime)
    }

    @Test
    fun csvWithoutAttachmentsIsSingleFile() = runTest {
        val r = repo()
        val h = harness(r)
        val events = h.coordinator.run(request(listOf(keyOf(r, b)), ExportFormat.CSV)).toList()
        assertEquals(listOf<String?>(null), h.zipNames)
        assertEquals(listOf("$folderB.csv"), h.sink.files.keys.toList())
        assertTrue(h.sink.text("$folderB.csv").startsWith("﻿# SmsExporter export"))
        assertEquals(ExportProgress.Done("mem://x", "mem/x", "text/csv"), events.last())
    }

    @Test
    fun jsonMarksUnreadableAttachmentMissing() = runTest {
        val r = repo(partBytes = emptyMap())
        val h = harness(r)
        h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.JSON)).toList()
        assertEquals(listOf<String?>("SmsExporter_2026-11-02_1015.zip"), h.zipNames)
        assertEquals(listOf("$folderA/$folderA.json"), h.sink.files.keys.toList())
        val json = h.sink.text("$folderA/$folderA.json")
        assertTrue(json, json.contains("\"fileName\": \"2_1.jpg\""))
        assertTrue(json, json.contains("\"sizeBytes\": null"))
        assertTrue(json, json.contains("\"missing\": true"))
    }

    @Test
    fun jsonRecordsCopiedAttachmentSize() = runTest {
        val r = repo()
        val h = harness(r)
        h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.JSON)).toList()
        val json = h.sink.text("$folderA/$folderA.json")
        assertTrue(json, json.contains("\"sizeBytes\": 4"))
        assertTrue(json, json.contains("\"missing\": false"))
    }

    @Test
    fun filterIsAppliedToMessageSet() = runTest {
        val r = repo()
        val h = harness(r)
        h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.JSON, MessageFilter(query = "hello"))).toList()
        // the only matching message has no attachment, so this is a single file
        assertEquals(listOf<String?>(null), h.zipNames)
        val json = h.sink.text("$folderA.json")
        assertTrue(json, json.contains("hello world"))
        assertFalse(json, json.contains("pic caption"))
        assertTrue(json, json.contains("\"partial\": true"))
        assertTrue(json, json.contains("\"messageCount\": 1"))
    }

    @Test
    fun messagesAreWrittenOldestFirst() = runTest {
        val r = repo()
        val h = harness(r)
        h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.HTML)).toList()
        val html = h.sink.text("$folderA.html")
        assertTrue(html.indexOf("hello world") in 0 until html.indexOf("pic caption"))
    }

    @Test
    fun failureAbortsSink() = runTest {
        val r = repo()
        val h = harness(r, pdf = { _, _ -> throw IOException("printer jammed") })
        val events = h.coordinator.run(request(listOf(keyOf(r, a)), ExportFormat.PDF)).toList()
        assertTrue(h.sink.aborted)
        assertFalse(h.sink.finished)
        assertEquals(ExportProgress.Failed("printer jammed"), events.last())
        assertTrue(events.none { it is ExportProgress.Done })
        assertEquals("temp html deleted", emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun zipIsolatesPerConversationFailure() = runTest {
        val r = repo()
        var calls = 0
        val h = harness(r, pdf = { html, out ->
            if (++calls == 2) throw IOException("printer jammed")
            out.write("%PDF-fake ${html.readText()}".toByteArray())
        })
        val keyA = keyOf(r, a)
        val events = h.coordinator.run(request(listOf(keyA, keyOf(r, b), keyA), ExportFormat.PDF)).toList()
        val folderA2 = "$folderA (2)"
        val paths = h.sink.files.keys
        assertTrue(paths.toString(), h.sink.text("$folderA/$folderA.pdf").startsWith("%PDF-fake <!doctype html>"))
        assertTrue(paths.toString(), h.sink.text("$folderA2/$folderA2.pdf").startsWith("%PDF-fake <!doctype html>"))
        val note = h.sink.text("$folderB/EXPORT_FAILED.txt")
        assertEquals(
            listOf("Export of \"$b\" failed: printer jammed", "Other files in this folder may be incomplete."),
            note.lines().filter { it.isNotEmpty() },
        )
        assertEquals("text/plain", h.sink.mimes.getValue("$folderB/EXPORT_FAILED.txt"))
        assertTrue(h.sink.finished)
        assertFalse(h.sink.aborted)
        assertEquals(ExportProgress.Done("mem://x", "mem/x", "application/zip", failed = 1), events.last())
        assertEquals("temp html deleted", emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun finishFailureAbortsSink() = runTest {
        val r = repo()
        val h = harness(r, sink = FakeSink(failFinish = true))
        val events = h.coordinator.run(request(listOf(keyOf(r, b)), ExportFormat.HTML)).toList()
        assertTrue(h.sink.aborted)
        assertEquals(ExportProgress.Failed("publish failed"), events.last())
        assertTrue(events.none { it is ExportProgress.Done })
    }

    @Test
    fun cancellationAbortsSink() = runTest {
        val r = repo()
        val h = harness(r, dispatcher = StandardTestDispatcher(testScheduler), pdf = { _, _ -> awaitCancellation() })
        // keys first: the first conversations() load hops to the real Dispatchers.IO
        val req = request(listOf(keyOf(r, a), keyOf(r, b)), ExportFormat.PDF)
        val seen = mutableListOf<ExportProgress>()
        val job = backgroundScope.launch { h.coordinator.run(req).toList(seen) }
        runCurrent() // advanceUntilIdle ignores backgroundScope work
        assertEquals(listOf(0f, 0.25f), seen.map { (it as ExportProgress.Running).fraction })
        assertFalse(h.sink.aborted)
        job.cancel()
        runCurrent() // advanceUntilIdle ignores backgroundScope work
        assertTrue(h.sink.aborted)
        assertFalse(h.sink.finished)
        assertEquals(2, seen.size)
    }

    @Test
    fun progressSequence() = runTest {
        val r = repo()
        val h = harness(r)
        val events = h.coordinator.run(request(listOf(keyOf(r, b)), ExportFormat.JSON)).toList()
        assertEquals(
            listOf(
                ExportProgress.Running(0, 1, b, 0f),
                ExportProgress.Running(0, 1, b, 0.5f),
                ExportProgress.Done("mem://x", "mem/x", "application/json"),
            ),
            events,
        )
    }

    @Test
    fun unknownKeyFailsBeforeAnySink() = runTest {
        val h = harness()
        val events = h.coordinator.run(request(listOf("nope"), ExportFormat.PDF)).toList()
        assertEquals(listOf<ExportProgress>(ExportProgress.Failed("Conversation not found: nope")), events)
        assertTrue(h.zipNames.isEmpty())
    }

    @Test
    fun emptySelectionFails() = runTest {
        val h = harness()
        val events = h.coordinator.run(request(emptyList(), ExportFormat.PDF)).toList()
        assertEquals(listOf<ExportProgress>(ExportProgress.Failed("Nothing selected")), events)
        assertTrue(h.zipNames.isEmpty())
    }
}
