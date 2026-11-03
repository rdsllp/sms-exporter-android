package com.example.smsexporter.export

import android.util.Log
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.model.Attachment
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Exports the conversations of an [ExportRequest] into one file or one zip.
 *
 * Output: a single conversation as PDF/HTML, or as CSV/JSON without attachments, is one file
 * (`sinkFactory(null)`); several conversations, or CSV/JSON with attachments, go into a zip
 * (`sinkFactory(zipName)`) with one folder per conversation holding `<folder>.<ext>` and
 * `attachments/`.
 *
 * In a multi-conversation zip, a conversation that fails (any [Exception] but cancellation) is
 * replaced by `<folder>/EXPORT_FAILED.txt` and the export goes on; [ExportProgress.Done.failed]
 * counts them. A single-conversation export fails as a whole.
 *
 * [run] is cold. It emits [ExportProgress.Running] before each conversation and again once its
 * messages are loaded, then [ExportProgress.Done] after the sink is published, or
 * [ExportProgress.Failed] after the sink is aborted. It never emits [ExportProgress.Cancelled]:
 * a cancelled flow cannot emit, so on cancellation it aborts the sink and rethrows, and the
 * collector (the ViewModel) maps job cancellation to `Cancelled`.
 *
 * PDF/HTML pages are streamed, never held whole in memory: HTML straight into the sink entry,
 * PDF into a temp `export-<random>.html` in [cacheDir] that [pdf] loads and that is deleted
 * afterwards on every path.
 *
 * Runs on [dispatcher]; [pdf] does its own main-thread hops.
 */
class ExportCoordinator(
    private val repo: MessageRepository,
    private val html: HtmlRenderer,
    /** Prints the HTML file to PDF into the stream. */
    private val pdf: suspend (File, OutputStream) -> Unit,
    /** null → a single-file sink (DownloadsSink); non-null → a ZipSink with that file name. */
    private val sinkFactory: (zipName: String?) -> ExportSink,
    private val clock: () -> ZonedDateTime,
    /** Where the PDF path writes its temporary HTML file. */
    private val cacheDir: File,
    private val scale: (ByteArray) -> String? = ImageScaler::toDataUri,
    private val attachments: AttachmentExporter = AttachmentExporter(repo),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = { Log.w("Export", it) },
) {
    fun run(request: ExportRequest): Flow<ExportProgress> = flow { export(request) }.flowOn(dispatcher)

    private suspend fun FlowCollector<ExportProgress>.export(request: ExportRequest) {
        if (request.conversationKeys.isEmpty()) {
            emit(ExportProgress.Failed("Nothing selected"))
            return
        }
        val conversations = request.conversationKeys.map { key ->
            repo.conversation(key) ?: run {
                emit(ExportProgress.Failed("Conversation not found: $key"))
                return
            }
        }
        val now = clock()
        val date = now.toLocalDate()
        val total = conversations.size
        val format = request.format
        val folders = FileNames.deduplicate(conversations.map { FileNames.baseName(it.title, date) })

        var sink: ExportSink? = null
        var zipped = false
        var emitting = false // an exception thrown by emit belongs to the collector, not to the export
        suspend fun report(p: ExportProgress) {
            emitting = true
            emit(p)
            emitting = false
        }

        fun openSink(zip: Boolean): ExportSink = sink ?: run {
            zipped = zip
            sinkFactory(if (zip) FileNames.zipName(now.toLocalDateTime()) else null).also { sink = it }
        }
        var failed = 0

        val result = try {
            for ((i, c) in conversations.withIndex()) {
                report(ExportProgress.Running(i, total, c.title, i.toFloat() / total))
                try {
                    val messages = repo.all(c, request.filter).reversed()
                    report(ExportProgress.Running(i, total, c.title, (i + 0.5f) / total))
                    val s = openSink(total > 1 || (format.copiesAttachments && messages.any { it.attachments.isNotEmpty() }))
                    val prefix = if (zipped) "${folders[i]}/" else ""
                    writeConversation(s, prefix, folders[i], format, c, messages, request.filter, now)
                } catch (e: Exception) {
                    // one bad conversation of several must not lose the others: note it and go on
                    if (e is CancellationException || emitting || total == 1) throw e
                    log("export of conversation ${i + 1} of $total failed: $e")
                    writeFailureNote(openSink(true), folders[i], c, e)
                    failed++
                }
            }
            checkNotNull(sink).finish()
        } catch (e: Throwable) {
            sink?.let { s -> runCatching { s.abort() } }
            if (e is CancellationException || emitting) throw e
            log("export failed: $e")
            emit(ExportProgress.Failed(e.message ?: e::class.simpleName ?: "Export failed"))
            return
        }
        emit(ExportProgress.Done(result.uri, result.displayPath, if (zipped) ZIP_MIME else format.mime, failed))
    }

    /** Puts `<folder>/EXPORT_FAILED.txt` in place of a conversation that could not be exported. */
    private fun writeFailureNote(sink: ExportSink, folder: String, c: Conversation, e: Exception) {
        val text = "Export of \"${c.title}\" failed: ${e.message ?: e::class.simpleName}\n" +
            "Other files in this folder may be incomplete.\n"
        sink.open("$folder/$FAILED_NOTE", "text/plain").use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Writes one conversation; [prefix] is its folder plus "/" in a zip, else "". */
    private suspend fun writeConversation(
        sink: ExportSink,
        prefix: String,
        baseName: String,
        format: ExportFormat,
        c: Conversation,
        messages: List<Message>,
        filter: MessageFilter,
        now: ZonedDateTime,
    ) {
        val mainPath = "$prefix$baseName.${format.ext}"
        when (format) {
            ExportFormat.HTML -> sink.open(mainPath, format.mime).use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).buffered().use { w ->
                    html.render(w, c, messages, filter, now, ::inlineImage)
                }
            }
            ExportFormat.PDF -> {
                val page = File.createTempFile("export-", ".html", cacheDir)
                try {
                    page.bufferedWriter(Charsets.UTF_8).use { w -> html.render(w, c, messages, filter, now, ::inlineImage) }
                    sink.open(mainPath, format.mime).use { out -> pdf(page, out) }
                } finally {
                    page.delete()
                }
            }
            ExportFormat.CSV, ExportFormat.JSON -> {
                // copied first so the main file only names what was actually attempted (R37)
                val copied = copyAttachments(sink, prefix, messages)
                val names = { m: Message -> copied[m.id].orEmpty() }
                sink.open(mainPath, format.mime).use { out ->
                    if (format == ExportFormat.CSV) {
                        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                            CsvWriter.write(w, c, messages, filter, now) { m -> names(m).map { it.fileName } }
                        }
                    } else {
                        val doc = JsonWriter.build(c, messages, filter, now, names)
                        out.write(JsonWriter.encode(doc).toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    /** Copies every attachment into `<prefix>attachments/`; unreadable ones are listed as missing. */
    private fun copyAttachments(sink: ExportSink, prefix: String, messages: List<Message>): Map<MessageId, List<JsonAttachment>> =
        messages.filter { it.attachments.isNotEmpty() }.associate { m ->
            m.id to m.attachments.mapIndexed { i, a ->
                val name = FileNames.attachmentName(m.id.rowId, i + 1, a.mimeType)
                val bytes = attachments.copy(a, sink, "${prefix}attachments/$name")
                JsonAttachment(name, a.mimeType, sizeBytes = bytes, missing = bytes == null)
            }
        }

    /** Only an [Exception] means "photo missing"; an OutOfMemoryError must fail the export loudly. */
    private fun inlineImage(a: Attachment): String? =
        try {
            repo.openAttachment(a)?.use { it.readBytes() }?.let(scale)
        } catch (e: Exception) {
            log("inline image ${a.partId} failed: $e")
            null
        }

    private val ExportFormat.copiesAttachments: Boolean
        get() = this == ExportFormat.CSV || this == ExportFormat.JSON

    private companion object {
        const val ZIP_MIME = "application/zip"
        const val FAILED_NOTE = "EXPORT_FAILED.txt"
    }
}
