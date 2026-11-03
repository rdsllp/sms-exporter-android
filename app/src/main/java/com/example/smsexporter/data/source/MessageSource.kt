package com.example.smsexporter.data.source

import java.io.InputStream

/** A row of `content://mms-sms/conversations?simple=true`. `dateMs` is milliseconds. */
data class RawThread(val id: Long, val recipientIds: List<Long>, val dateMs: Long, val messageCount: Int, val snippet: String?, val hasAttachment: Boolean)

/** A row of `content://sms`. `dateMs` is milliseconds. */
data class RawSms(val id: Long, val threadId: Long, val address: String?, val type: Int, val dateMs: Long, val body: String?)

/** A row of `content://mms` (also holds RCS rows, whose `trId` starts with `proto:`). `dateMs` is milliseconds (the provider stores seconds). */
data class RawMms(val id: Long, val threadId: Long, val msgBox: Int, val dateMs: Long, val trId: String?, val contentType: String?, val textOnly: Boolean)

/** A row of `content://mms/part`. */
data class RawPart(val id: Long, val mid: Long, val contentType: String?, val name: String?, val text: String?, val dataPath: String?)

/** A row of `content://mms/<id>/addr`. `type` is 137 for from, 151 for to. */
data class RawAddr(val msgId: Long, val address: String, val type: Int)   // 137 from, 151 to

/**
 * Filter applied to sms/mms rows. All bounds are milliseconds and inclusive.
 * `bodyContains` is a case-insensitive substring match on the sms body / mms text/plain parts.
 */
data class RowFilter(val startMs: Long? = null, val endMs: Long? = null, val bodyContains: String? = null)

/**
 * Raw, read-only access to the Telephony provider.
 *
 * For `sms`/`mms`: rows are restricted to `threadIds` (empty collection = no rows), to `where`,
 * and to `date < beforeMs` when `beforeMs` is set; they are returned newest first, at most `limit`.
 */
interface MessageSource {
    suspend fun threads(): List<RawThread>
    suspend fun canonicalAddresses(): Map<Long, String>
    suspend fun sms(threadIds: Collection<Long>, where: RowFilter, limit: Int, beforeMs: Long?): List<RawSms>
    suspend fun mms(threadIds: Collection<Long>, where: RowFilter, limit: Int, beforeMs: Long?): List<RawMms>
    suspend fun parts(mmsIds: Collection<Long>): Map<Long, List<RawPart>>      // keyed by mid
    suspend fun addrs(mmsIds: Collection<Long>): Map<Long, List<RawAddr>>
    suspend fun smsCount(threadIds: Collection<Long>, where: RowFilter): Int
    suspend fun mmsCount(threadIds: Collection<Long>, where: RowFilter): Int
    fun openPart(partId: Long): InputStream?
}
