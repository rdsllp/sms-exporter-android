package com.example.smsexporter.data.source

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import com.example.smsexporter.data.model.MessageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * [MessageSource] backed by the Telephony content provider. Read-only: it only ever calls
 * [ContentResolver.query] and [ContentResolver.openInputStream].
 *
 * The provider rejects sub-selects, so id lists are sent as literal `IN (...)` lists,
 * chunked to [CHUNK] ids per query. `content://mms` dates are seconds and are converted
 * to milliseconds on the way out (and filter bounds converted to seconds on the way in).
 */
class TelephonyMessageSource(private val resolver: ContentResolver) : MessageSource {

    override suspend fun threads(): List<RawThread> = withContext(Dispatchers.IO) {
        val out = ArrayList<RawThread>()
        resolver.query(
            THREADS_URI,
            arrayOf("_id", "recipient_ids", "date", "message_count", "snippet", "has_attachment"),
            null, null, null,
        )?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val rec = c.getColumnIndexOrThrow("recipient_ids")
            val date = c.getColumnIndexOrThrow("date")
            val count = c.getColumnIndexOrThrow("message_count")
            val snippet = c.getColumnIndexOrThrow("snippet")
            val att = c.getColumnIndexOrThrow("has_attachment")
            while (c.moveToNext()) {
                out += RawThread(
                    id = c.getLong(id),
                    recipientIds = parseRecipientIds(c.getStringOrNull(rec)),
                    dateMs = c.getLong(date),
                    messageCount = c.getInt(count),
                    snippet = c.getStringOrNull(snippet),
                    hasAttachment = c.getInt(att) == 1,
                )
            }
        }
        out
    }

    override suspend fun canonicalAddresses(): Map<Long, String> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, String>()
        resolver.query(CANONICAL_URI, arrayOf("_id", "address"), null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val addr = c.getColumnIndexOrThrow("address")
            while (c.moveToNext()) {
                val a = c.getStringOrNull(addr) ?: continue
                out[c.getLong(id)] = a
            }
        }
        out
    }

    override suspend fun sms(threadIds: Collection<Long>, where: RowFilter, limit: Int, before: PageKey?): List<RawSms> =
        withContext(Dispatchers.IO) {
            if (threadIds.isEmpty() || limit <= 0) return@withContext emptyList()
            val out = ArrayList<RawSms>()
            for (chunk in threadIds.distinct().chunked(CHUNK)) {
                val (sel, args) = smsSelection(chunk, where, before)
                resolver.query(SMS_URI, SMS_COLUMNS, sel, args, "date DESC, _id DESC LIMIT $limit")?.use { c ->
                    while (c.moveToNext()) out += c.toRawSms()
                }
            }
            out.sortedWith(compareByDescending<RawSms> { it.dateMs }.thenByDescending { it.id }).take(limit)
        }

    override suspend fun smsCount(threadIds: Collection<Long>, where: RowFilter): Int = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext 0
        var total = 0
        for (chunk in threadIds.distinct().chunked(CHUNK)) {
            val (sel, args) = smsSelection(chunk, where, null)
            resolver.query(SMS_URI, arrayOf("_id"), sel, args, null)?.use { total += it.count }
        }
        total
    }

    override suspend fun mms(threadIds: Collection<Long>, where: RowFilter, limit: Int, before: PageKey?): List<RawMms> =
        withContext(Dispatchers.IO) {
            if (threadIds.isEmpty() || limit <= 0) return@withContext emptyList()
            val out = ArrayList<RawMms>()
            forEachMmsQuery(threadIds, where, before) { sel, args, threadSet ->
                // With a thread set, thread filtering happens in memory, so a SQL LIMIT could be
                // used up by rows from other threads; only limit in SQL when SQL does the filtering.
                val order = if (threadSet == null) "date DESC, _id DESC LIMIT $limit" else "date DESC, _id DESC"
                resolver.query(MMS_URI, MMS_COLUMNS, sel, args, order)?.use { c ->
                    while (c.moveToNext()) {
                        val row = c.toRawMms()
                        if (threadSet == null || row.threadId in threadSet) out += row
                    }
                }
            }
            out.sortedWith(compareByDescending<RawMms> { it.dateMs }.thenByDescending { it.id }).take(limit)
        }

    override suspend fun mmsCount(threadIds: Collection<Long>, where: RowFilter): Int = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext 0
        var total = 0
        forEachMmsQuery(threadIds, where, null) { sel, args, threadSet ->
            resolver.query(MMS_URI, arrayOf("_id", "thread_id"), sel, args, null)?.use { c ->
                if (threadSet == null) {
                    total += c.count
                } else {
                    val t = c.getColumnIndexOrThrow("thread_id")
                    while (c.moveToNext()) if (c.getLong(t) in threadSet) total++
                }
            }
        }
        total
    }

    override suspend fun parts(mmsIds: Collection<Long>): Map<Long, List<RawPart>> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, MutableList<RawPart>>()
        for (chunk in mmsIds.distinct().chunked(CHUNK)) {
            resolver.query(
                PART_URI,
                arrayOf("_id", "mid", "seq", "ct", "name", "text", "_data"),
                "mid IN (${chunk.joinToString(",")})", null, "mid, seq, _id",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow("_id")
                val mid = c.getColumnIndexOrThrow("mid")
                val ct = c.getColumnIndexOrThrow("ct")
                val name = c.getColumnIndexOrThrow("name")
                val text = c.getColumnIndexOrThrow("text")
                val data = c.getColumnIndexOrThrow("_data")
                while (c.moveToNext()) {
                    val part = RawPart(
                        id = c.getLong(id),
                        mid = c.getLong(mid),
                        contentType = c.getStringOrNull(ct),
                        name = c.getStringOrNull(name),
                        text = c.getStringOrNull(text),
                        dataPath = c.getStringOrNull(data),
                    )
                    out.getOrPut(part.mid) { ArrayList() } += part
                }
            }
        }
        out
    }

    override suspend fun addrs(mmsIds: Collection<Long>): Map<Long, List<RawAddr>> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, MutableList<RawAddr>>()
        for (chunk in mmsIds.distinct().chunked(CHUNK)) {
            resolver.query(
                ADDR_URI,
                arrayOf("_id", "msg_id", "address", "type"),
                "msg_id IN (${chunk.joinToString(",")})", null, "msg_id, _id",
            )?.use { c ->
                val msgId = c.getColumnIndexOrThrow("msg_id")
                val addr = c.getColumnIndexOrThrow("address")
                val type = c.getColumnIndexOrThrow("type")
                while (c.moveToNext()) {
                    val a = c.getStringOrNull(addr) ?: continue
                    val row = RawAddr(msgId = c.getLong(msgId), address = a, type = c.getInt(type))
                    out.getOrPut(row.msgId) { ArrayList() } += row
                }
            }
        }
        out
    }

    override suspend fun rcsCount(): Int = withContext(Dispatchers.IO) {
        resolver.query(MMS_URI, arrayOf("_id"), "tr_id LIKE 'proto:%'", null, null)?.use { it.count } ?: 0
    }

    override fun openPart(partId: Long): InputStream? =
        resolver.openInputStream(Uri.parse("content://mms/part/$partId"))

    // ---- selection building ----

    private fun smsSelection(threadChunk: List<Long>, where: RowFilter, before: PageKey?): Pair<String, Array<String>> {
        val clauses = mutableListOf("thread_id IN (${threadChunk.joinToString(",")})")
        val args = mutableListOf<String>()
        where.startMs?.let { clauses += "date >= ?"; args += it.toString() }
        where.endMs?.let { clauses += "date <= ?"; args += it.toString() }
        before?.let {
            clauses += "(date < ? OR (date = ? AND _id < ?))"
            args += listOf(it.dateMs.toString(), it.dateMs.toString(), it.id.toString())
        }
        where.bodyContains?.let { clauses += "body LIKE ? ESCAPE '\\'"; args += likeContains(it) }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    /**
     * Calls [block] once per `content://mms` query needed. Without a body filter, queries are
     * chunked by thread id. With one, the matching message ids are first collected from
     * text/plain parts and queries are chunked by message id; [block] then receives the
     * thread-id set to filter on in memory.
     */
    private inline fun forEachMmsQuery(
        threadIds: Collection<Long>,
        where: RowFilter,
        before: PageKey?,
        block: (sel: String, args: Array<String>, threadSet: Set<Long>?) -> Unit,
    ) {
        val dateClauses = mutableListOf<String>()
        val dateArgs = mutableListOf<String>()
        where.startMs?.let { dateClauses += "date >= ?"; dateArgs += ceilSeconds(it).toString() }
        where.endMs?.let { dateClauses += "date <= ?"; dateArgs += Math.floorDiv(it, 1000L).toString() }
        before?.let {
            // MMS dateMs values are whole seconds (date * 1000), so this division is exact.
            val sec = (it.dateMs / 1000L).toString()
            dateClauses += "(date < ? OR (date = ? AND _id < ?))"
            dateArgs += listOf(sec, sec, it.id.toString())
        }

        val term = where.bodyContains
        if (term == null) {
            for (chunk in threadIds.distinct().chunked(CHUNK)) {
                val sel = (listOf("thread_id IN (${chunk.joinToString(",")})") + dateClauses).joinToString(" AND ")
                block(sel, dateArgs.toTypedArray(), null)
            }
        } else {
            val mids = matchingTextMids(term)
            val threadSet = threadIds.toHashSet()
            for (chunk in mids.chunked(CHUNK)) {
                val sel = (listOf("_id IN (${chunk.joinToString(",")})") + dateClauses).joinToString(" AND ")
                block(sel, dateArgs.toTypedArray(), threadSet)
            }
        }
    }

    private fun matchingTextMids(term: String): List<Long> {
        val mids = LinkedHashSet<Long>()
        resolver.query(PART_URI, arrayOf("mid"), "ct = 'text/plain' AND text LIKE ? ESCAPE '\\'", arrayOf(likeContains(term)), null)?.use { c ->
            val mid = c.getColumnIndexOrThrow("mid")
            while (c.moveToNext()) mids += c.getLong(mid)
        }
        return mids.toList()
    }

    override suspend fun newestPerThread(): Map<Long, ThreadNewest> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, ThreadNewest>()
        // Rows come newest first, so the first row seen for a thread is its newest.
        resolver.query(MMS_URI, arrayOf("_id", "thread_id", "date", "tr_id"), null, null, "date DESC, _id DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val t = c.getColumnIndexOrThrow("thread_id")
            val d = c.getColumnIndexOrThrow("date")
            val tr = c.getColumnIndexOrThrow("tr_id")
            while (c.moveToNext()) {
                val tid = c.getLong(t)
                if (tid in out) continue
                val trId = c.getStringOrNull(tr)
                val kind = if (trId?.startsWith("proto:") == true) MessageKind.RCS else MessageKind.MMS
                out[tid] = ThreadNewest(c.getLong(d) * 1000L, kind, c.getLong(id), trId)
            }
        }
        val smsSeen = HashSet<Long>()
        resolver.query(SMS_URI, arrayOf("thread_id", "date"), null, null, "date DESC, _id DESC")?.use { c ->
            val t = c.getColumnIndexOrThrow("thread_id")
            val d = c.getColumnIndexOrThrow("date")
            while (c.moveToNext()) {
                val tid = c.getLong(t)
                if (!smsSeen.add(tid)) continue
                val date = c.getLong(d)
                val mms = out[tid]
                if (mms == null || date > mms.dateMs) out[tid] = ThreadNewest(date, MessageKind.SMS, null)
            }
        }
        out
    }

    private fun Cursor.toRawSms() = RawSms(
        id = getLong(getColumnIndexOrThrow("_id")),
        threadId = getLong(getColumnIndexOrThrow("thread_id")),
        address = getStringOrNull(getColumnIndexOrThrow("address")),
        type = getInt(getColumnIndexOrThrow("type")),
        dateMs = getLong(getColumnIndexOrThrow("date")),
        body = getStringOrNull(getColumnIndexOrThrow("body")),
    )

    private fun Cursor.toRawMms() = RawMms(
        id = getLong(getColumnIndexOrThrow("_id")),
        threadId = getLong(getColumnIndexOrThrow("thread_id")),
        msgBox = getInt(getColumnIndexOrThrow("msg_box")),
        dateMs = getLong(getColumnIndexOrThrow("date")) * 1000L,
        trId = getStringOrNull(getColumnIndexOrThrow("tr_id")),
        contentType = getStringOrNull(getColumnIndexOrThrow("ct_t")),
        textOnly = getInt(getColumnIndexOrThrow("text_only")) == 1,
    )

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        const val CHUNK = 500
        private val THREADS_URI: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        private val CANONICAL_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private val SMS_URI: Uri = Uri.parse("content://sms")
        private val MMS_URI: Uri = Uri.parse("content://mms")
        private val PART_URI: Uri = Uri.parse("content://mms/part")
        private val ADDR_URI: Uri = Uri.parse("content://mms/addr")
        private val SMS_COLUMNS = arrayOf("_id", "thread_id", "address", "type", "date", "body")
        private val MMS_COLUMNS = arrayOf("_id", "thread_id", "msg_box", "date", "tr_id", "ct_t", "text_only")

        /** Parses the space-separated `recipient_ids` column; blank/NULL gives an empty list. */
        internal fun parseRecipientIds(raw: String?): List<Long> =
            raw?.trim()?.split(' ')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

        /** `%term%` with `\`, `%` and `_` escaped, for use with `LIKE ? ESCAPE '\'`. */
        internal fun likeContains(term: String): String =
            "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        private fun ceilSeconds(ms: Long): Long = Math.floorDiv(ms + 999L, 1000L)
    }
}
