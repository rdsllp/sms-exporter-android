package com.example.smsexporter.data.source

import java.io.InputStream

/**
 * In-memory [MessageSource] for tests, with the same filtering/paging semantics as
 * [TelephonyMessageSource]. When [threads]/[canonical] are null they are derived from the rows:
 * - canonical: one id (1, 2, ...) per distinct address in `sms.address` and `addrs.address`,
 *   in first-seen order;
 * - threads: one per distinct `threadId`; `recipientIds` = canonical ids of the addresses seen
 *   in that thread; `messageCount` = sms + mms rows; `dateMs`/`snippet` from the newest row;
 *   `hasAttachment` = any part of its mms that is not text/plain or application/smil.
 */
class FakeMessageSource(
    private val sms: List<RawSms>,
    private val mms: List<RawMms>,
    private val parts: List<RawPart>,
    private val addrs: List<RawAddr>,
    threads: List<RawThread>? = null,
    canonical: Map<Long, String>? = null,
    private val partBytes: Map<Long, ByteArray> = emptyMap(),
) : MessageSource {

    private val canonicalMap: Map<Long, String> = canonical ?: deriveCanonical()
    private val threadList: List<RawThread> = threads ?: deriveThreads()

    override suspend fun threads(): List<RawThread> = threadList

    override suspend fun canonicalAddresses(): Map<Long, String> = canonicalMap

    override suspend fun sms(threadIds: Collection<Long>, where: RowFilter, limit: Int, beforeMs: Long?): List<RawSms> =
        smsMatching(threadIds, where)
            .filter { beforeMs == null || it.dateMs < beforeMs }
            .sortedWith(compareByDescending<RawSms> { it.dateMs }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))

    override suspend fun mms(threadIds: Collection<Long>, where: RowFilter, limit: Int, beforeMs: Long?): List<RawMms> =
        mmsMatching(threadIds, where)
            .filter { beforeMs == null || it.dateMs < beforeMs }
            .sortedWith(compareByDescending<RawMms> { it.dateMs }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))

    override suspend fun parts(mmsIds: Collection<Long>): Map<Long, List<RawPart>> {
        val ids = mmsIds.toSet()
        return parts.filter { it.mid in ids }.groupBy { it.mid }
    }

    override suspend fun addrs(mmsIds: Collection<Long>): Map<Long, List<RawAddr>> {
        val ids = mmsIds.toSet()
        return addrs.filter { it.msgId in ids }.groupBy { it.msgId }
    }

    override suspend fun smsCount(threadIds: Collection<Long>, where: RowFilter): Int = smsMatching(threadIds, where).size

    override suspend fun mmsCount(threadIds: Collection<Long>, where: RowFilter): Int = mmsMatching(threadIds, where).size

    override fun openPart(partId: Long): InputStream? = partBytes[partId]?.inputStream()

    // ---- filtering ----

    private fun inRange(dateMs: Long, where: RowFilter) =
        (where.startMs == null || dateMs >= where.startMs) && (where.endMs == null || dateMs <= where.endMs)

    private fun smsMatching(threadIds: Collection<Long>, where: RowFilter): List<RawSms> {
        val ids = threadIds.toSet()
        return sms.filter { s ->
            s.threadId in ids && inRange(s.dateMs, where) &&
                (where.bodyContains == null || s.body?.contains(where.bodyContains, ignoreCase = true) == true)
        }
    }

    private fun mmsMatching(threadIds: Collection<Long>, where: RowFilter): List<RawMms> {
        val ids = threadIds.toSet()
        val textMids: Set<Long>? = where.bodyContains?.let { term ->
            parts.filter { it.contentType == "text/plain" && it.text?.contains(term, ignoreCase = true) == true }
                .map { it.mid }.toSet()
        }
        return mms.filter { m ->
            m.threadId in ids && inRange(m.dateMs, where) && (textMids == null || m.id in textMids)
        }
    }

    // ---- derivation ----

    private fun deriveCanonical(): Map<Long, String> {
        val distinct = LinkedHashSet<String>()
        sms.forEach { s -> s.address?.let { distinct += it } }
        addrs.forEach { distinct += it.address }
        return distinct.withIndex().associate { (i, a) -> (i + 1).toLong() to a }
    }

    private fun deriveThreads(): List<RawThread> {
        val idOf = canonicalMap.entries.associate { (id, a) -> a to id }
        val threadIds = LinkedHashSet<Long>().apply { sms.forEach { add(it.threadId) }; mms.forEach { add(it.threadId) } }
        return threadIds.map { tid ->
            val tSms = sms.filter { it.threadId == tid }
            val tMms = mms.filter { it.threadId == tid }
            val mmsIds = tMms.map { it.id }.toSet()
            val tParts = parts.filter { it.mid in mmsIds }
            val addresses = LinkedHashSet<String>().apply {
                tSms.forEach { s -> s.address?.let { add(it) } }
                addrs.filter { it.msgId in mmsIds }.forEach { add(it.address) }
            }
            val newestSms = tSms.maxByOrNull { it.dateMs }
            val newestMms = tMms.maxByOrNull { it.dateMs }
            val newestIsSms = newestSms != null && (newestMms == null || newestSms.dateMs >= newestMms.dateMs)
            val snippet = if (newestIsSms) newestSms!!.body
            else tParts.firstOrNull { it.mid == newestMms!!.id && it.contentType == "text/plain" }?.text
            RawThread(
                id = tid,
                recipientIds = addresses.mapNotNull { idOf[it] },
                dateMs = maxOf(newestSms?.dateMs ?: Long.MIN_VALUE, newestMms?.dateMs ?: Long.MIN_VALUE),
                messageCount = tSms.size + tMms.size,
                snippet = snippet,
                hasAttachment = tParts.any { it.contentType != "text/plain" && it.contentType != "application/smil" },
            )
        }
    }
}
