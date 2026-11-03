package com.example.smsexporter.data.source

import com.example.smsexporter.data.model.MessageKind
import java.io.InputStream

/** A row of `content://mms-sms/conversations?simple=true`. `dateMs` is milliseconds. */
data class RawThread(val id: Long, val recipientIds: List<Long>, val dateMs: Long, val messageCount: Int, val snippet: String?, val hasAttachment: Boolean)

/** A row of `content://sms`. `dateMs` is milliseconds. */
data class RawSms(val id: Long, val threadId: Long, val address: String?, val type: Int, val dateMs: Long, val body: String?)

/** A row of `content://mms` (also holds RCS rows, whose `trId` starts with `proto:`). `dateMs` is milliseconds (the provider stores seconds). */
data class RawMms(val id: Long, val threadId: Long, val msgBox: Int, val dateMs: Long, val trId: String?, val contentType: String?, val textOnly: Boolean)

/** A row of `content://mms/part`. */
data class RawPart(val id: Long, val mid: Long, val contentType: String?, val name: String?, val text: String?, val dataPath: String?)

/** A row of `content://mms/addr`. `type` is 137 for from, 151 for to. */
data class RawAddr(val msgId: Long, val address: String, val type: Int)   // 137 from, 151 to

/**
 * Filter applied to sms/mms rows. All bounds are milliseconds and inclusive.
 * `bodyContains` is a literal substring match (`%`, `_` and `\` have no special meaning) on the
 * sms body / mms text/plain parts. It is case-insensitive for ASCII letters only (SQLite LIKE
 * semantics): "É" does not match "é".
 */
/** Paging cursor: the `(dateMs, id)` of the last row of the previous page. */
data class PageKey(val dateMs: Long, val id: Long)

/**
 * Date and kind of the newest row of a thread across both tables. `dateMs` is milliseconds.
 * [mmsId] is that row's `_id` when it is an mms/RCS row, null when it is an sms.
 */
data class ThreadNewest(val dateMs: Long, val kind: MessageKind, val mmsId: Long?)

data class RowFilter(val startMs: Long? = null, val endMs: Long? = null, val bodyContains: String? = null)

/**
 * Raw, read-only access to the Telephony provider.
 *
 * For `sms`/`mms`: rows are restricted to `threadIds` (empty collection = no rows), to `where`,
 * and, when `before` is set, to rows strictly after it in `(date DESC, _id DESC)` order, i.e.
 * `date < before.dateMs OR (date == before.dateMs AND id < before.id)`. Rows are returned in that
 * order (newest first), at most `limit`.
 */
interface MessageSource {
    suspend fun threads(): List<RawThread>
    suspend fun canonicalAddresses(): Map<Long, String>
    suspend fun sms(threadIds: Collection<Long>, where: RowFilter, limit: Int, before: PageKey?): List<RawSms>
    suspend fun mms(threadIds: Collection<Long>, where: RowFilter, limit: Int, before: PageKey?): List<RawMms>
    suspend fun parts(mmsIds: Collection<Long>): Map<Long, List<RawPart>>      // keyed by mid
    suspend fun addrs(mmsIds: Collection<Long>): Map<Long, List<RawAddr>>
    suspend fun smsCount(threadIds: Collection<Long>, where: RowFilter): Int
    suspend fun mmsCount(threadIds: Collection<Long>, where: RowFilter): Int
    fun openPart(partId: Long): InputStream?

    /** Newest row per thread id across sms and mms (mms wins a same-millisecond tie), from one scan per table. */
    suspend fun newestPerThread(): Map<Long, ThreadNewest>
}
