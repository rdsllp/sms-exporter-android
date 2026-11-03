package com.example.smsexporter

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.example.smsexporter.data.ContactResolver
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.source.TelephonyMessageSource
import com.example.smsexporter.diag.CrashLog
import com.example.smsexporter.export.DownloadsSink
import com.example.smsexporter.export.ExportCoordinator
import com.example.smsexporter.export.HtmlRenderer
import com.example.smsexporter.export.PdfPrinter
import com.example.smsexporter.export.ZipSink
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds the app-wide data objects. Both are created on first use, which happens only after the
 * permission gate has passed: a contact lookup made before READ_CONTACTS is granted would be
 * cached as a miss.
 */
class SmsExporterApp : Application(), ImageLoaderFactory {
    val contacts: ContactResolver by lazy { ContactResolver(contentResolver) }
    val repository: MessageRepository by lazy { MessageRepository(TelephonyMessageSource(contentResolver), contacts) }

    /** Exports conversations to Downloads; shared by the list's and the chat's export view models. */
    val exportCoordinator: ExportCoordinator by lazy {
        ExportCoordinator(
            repository,
            HtmlRenderer(assets.open("export/chat.css").bufferedReader().use { it.readText() }),
            pdf = PdfPrinter(this)::print,
            sinkFactory = { name -> if (name == null) DownloadsSink(contentResolver) else ZipSink(contentResolver, name) },
            clock = { ZonedDateTime.now() },
        )
    }

    /** The conversation list's current filter; a conversation opened from the list starts with it. */
    val listFilter = MutableStateFlow(MessageFilter())

    /** Keys of the conversation list's visible conversations in display order; the chat pager pages through them. */
    val listOrder = MutableStateFlow<List<String>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)   // keeps the last crash in filesDir/crash.log for the device check report
    }

    /** Coil's singleton loader, able to decode a video's first frame. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
