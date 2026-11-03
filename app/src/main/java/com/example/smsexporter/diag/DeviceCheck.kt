package com.example.smsexporter.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Telephony
import com.example.smsexporter.BuildConfig
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.source.MessageSource
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RowFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The full plain-text device check report. */
data class DeviceReport(val text: String)

/** What the report reads from the device itself; an interface so unit tests run without Android. */
interface DeviceEnv {
    /** "<manufacturer> <model> · Android <release> (SDK <n>)". */
    fun device(): String
    fun defaultSmsApp(): String?
    fun granted(permission: String): Boolean
    fun crashLog(): String?
}

class AndroidDeviceEnv(private val context: Context) : DeviceEnv {
    override fun device() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    override fun defaultSmsApp(): String? = Telephony.Sms.getDefaultSmsPackage(context)
    override fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    override fun crashLog(): String? = CrashLog.read(context)
}

/** Width and height read from an image's header (nothing is decoded), or null when it is not an image. */
fun bitmapBounds(input: InputStream): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(input, null, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/**
 * Builds a shareable report of counts, timings and masked metadata: never message bodies, contact
 * names or full numbers. One private function per line; each runs on its own, so a failing line is
 * written as "<label>: ERROR <Exception>: <message>" (digit runs over 6 masked) and the rest still
 * appear. [run] never throws, except to propagate a cancellation of itself.
 */
class DeviceCheck(
    private val env: DeviceEnv,
    private val source: MessageSource,
    private val repo: MessageRepository,
    private val readBounds: (InputStream) -> Pair<Int, Int>? = ::bitmapBounds,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    constructor(context: Context, source: MessageSource, repo: MessageRepository) : this(AndroidDeviceEnv(context), source, repo)

    private var threadIds: List<Long>? = null

    suspend fun run(onProgress: (String) -> Unit = {}): DeviceReport = withContext(Dispatchers.IO) {
        threadIds = null
        suspend fun line(label: String, sep: String = ": ", body: suspend () -> String): String {
            onProgress("$label…")
            return try {
                "$label$sep${body()}"
            } catch (e: Throwable) {
                // rethrow only a real cancellation of this run; a library's stray one is an error line
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                "$label: ERROR ${className(e)}: ${mask(e.message)}"
            }
        }
        val lines = listOf(
            line("SmsExporter", " ") { header() },
            line("permissions") { permissions() },
            line("rows") { rows() },
            line("rcs last 7 days") { rcsLastWeek() },
            line("newest rcs rows") { newestRcsRows() },
            line("image part read") { imagePartRead() },
            line("timings ms") { timings() },
            line("crash log") { env.crashLog() ?: "none" },
        )
        onProgress("Done")
        DeviceReport(lines.joinToString("\n"))
    }

    private fun header(): String =
        "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · ${env.device()} · default SMS app: ${env.defaultSmsApp() ?: "unknown"}"

    private fun permissions(): String {
        fun yesNo(p: String) = if (env.granted(p)) "yes" else "no"
        return "READ_SMS=${yesNo(Manifest.permission.READ_SMS)} READ_CONTACTS=${yesNo(Manifest.permission.READ_CONTACTS)}"
    }

    private suspend fun rows(): String {
        val ids = ids()
        val sms = source.smsCount(ids, RowFilter())
        val mms = source.mmsCount(ids, RowFilter())
        return "sms=$sms mms=$mms rcs=${source.rcsCount()} threads=${ids.size} conversations=${repo.conversations().size}"
    }

    /** Sent = outbox-sent (`msg_box` 2), received = inbox (`msg_box` 1); drafts/outbox/failed count only in the total. */
    private suspend fun rcsLastWeek(): String {
        val since = now() - 7 * DAY_MS
        val rcs = source.mms(ids(), RowFilter(startMs = since), Int.MAX_VALUE, null).filter { it.isRcs }
        return "${rcs.size} (sent ${rcs.count { it.msgBox == 2 }}, received ${rcs.count { it.msgBox == 1 }})"
    }

    private suspend fun newestRcsRows(): String {
        val rcs = source.mms(ids(), RowFilter(), NEWEST, null).filter { it.isRcs }.take(5)
        if (rcs.isEmpty()) return "none"
        val parts = source.parts(rcs.map { it.id })
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        return rcs.joinToString(" · ") { m ->
            val p = parts[m.id].orEmpty()
            val textLength = p.filter { it.contentType == "text/plain" }.sumOf { it.text?.length ?: 0 }
            val direction = if (m.msgBox == 2) "sent" else "recv"
            "${format.format(Instant.ofEpochMilli(m.dateMs))} $direction thread ${m.threadId} parts ${p.size} text $textLength chars"
        }
    }

    /** Reads the first image part among the newest mms rows; KB counts every byte read (rounded up). */
    private suspend fun imagePartRead(): String {
        val rows = source.mms(ids(), RowFilter(), NEWEST, null)
        val parts = source.parts(rows.map { it.id })
        val image = rows.firstNotNullOfOrNull { m -> parts[m.id]?.firstOrNull { it.contentType?.startsWith("image/") == true } }
            ?: return "none found"
        val bytes = (source.openPart(image.id) ?: error("openPart returned null")).use { it.readBytes() }
        val kb = (bytes.size + 1023) / 1024
        val (w, h) = readBounds(ByteArrayInputStream(bytes)) ?: return "ERROR not decodable ($kb KB)"
        return "ok (${w}x$h, $kb KB)"
    }

    private suspend fun timings(): String {
        val t0 = System.nanoTime()
        source.threads(); source.canonicalAddresses()
        val t1 = System.nanoTime()
        repo.conversations(refresh = true)
        val t2 = System.nanoTime()
        val filled = repo.fillLastKinds()
        val t3 = System.nanoTime()
        val page = filled.firstOrNull()?.let { repo.page(it, MessageFilter(), null) }.orEmpty()
        val t4 = System.nanoTime()
        return "threads+addresses=${ms(t0, t1)} conversations=${ms(t1, t2)} lastKinds=${ms(t2, t3)} firstPage=${ms(t3, t4)} (${page.size} rows)"
    }

    private suspend fun ids(): List<Long> = threadIds ?: source.threads().map { it.id }.distinct().also { threadIds = it }

    private val RawMms.isRcs get() = trId?.startsWith("proto:") == true

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NEWEST = 50
        val LONG_DIGITS = Regex("""\d{7,}""")

        fun ms(from: Long, to: Long) = (to - from) / 1_000_000

        /** The simple class name, or the last part of the binary name for an anonymous class (whose simple name is empty). */
        fun className(e: Throwable) = e.javaClass.simpleName.ifEmpty { e.javaClass.name.substringAfterLast('.') }

        /** Hides digit runs longer than 6 so an exception message cannot leak a phone number. */
        fun mask(message: String?) = message?.replace(LONG_DIGITS, "###") ?: ""
    }
}
