package com.example.smsexporter.data.source

import com.example.smsexporter.data.model.MessageKind
import java.io.InputStream

/**
 * In-memory [MessageSource] for tests, with the same filtering/paging semantics as
 * [TelephonyMessageSource]. When [threads]/[canonical] are null they are derived from the rows:
 * - canonical: one id (1, 2, ...) per distinct address in `sms.address` and `addrs.address`,
 *   in first-seen order;
 * - threads: one per distinct `threadId`; `recipientIds` = canonical ids of the addresses seen
 *   in that thread; `messageCount` = sms + mms rows; `dateMs` from the newest row; `snippet` =
 *   the newest row's body when it is an sms, else null (as on the device for RCS threads);
 *   `hasAttachment` = any part of its mms that is not text/plain or application/smil.
 *
 * `bodyContains` is a literal substring match folding ASCII A-Z only, like SQLite LIKE.
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

    override suspend fun sms(threadIds: Collection<Long>, where: RowFilter, limit: Int, before: PageKey?): List<RawSms> =
        smsMatching(threadIds, where)
            .filter { isAfter(it.dateMs, it.id, before) }
            .sortedWith(compareByDescending<RawSms> { it.dateMs }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))

    override suspend fun mms(threadIds: Collection<Long>, where: RowFilter, limit: Int, before: PageKey?): List<RawMms> =
        mmsMatching(threadIds, where)
            .filter { isAfterInSeconds(it.dateMs, it.id, before) }
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

    override suspend fun newestPerThread(): Map<Long, ThreadNewest> {
        val out = HashMap<Long, ThreadNewest>()
        // newest first, like the provider's `date DESC, _id DESC`: the first row per thread wins
        mms.sortedWith(compareByDescending<RawMms> { it.dateMs }.thenByDescending { it.id }).forEach { m ->
            if (m.threadId in out) return@forEach
            val kind = if (m.trId?.startsWith("proto:") == true) MessageKind.RCS else MessageKind.MMS
            out[m.threadId] = ThreadNewest(m.dateMs, kind, m.id)
        }
        sms.forEach { s ->
            if ((out[s.threadId]?.dateMs ?: Long.MIN_VALUE) < s.dateMs) out[s.threadId] = ThreadNewest(s.dateMs, MessageKind.SMS, null)
        }
        return out
    }

    // ---- filtering ----

    /** True when the row comes after [before] in `(date DESC, id DESC)` order. */
    private fun isAfter(dateMs: Long, id: Long, before: PageKey?) =
        before == null || dateMs < before.dateMs || (dateMs == before.dateMs && id < before.id)

    /**
     * Like [isAfter] but compares whole seconds, as [TelephonyMessageSource] does for mms (the
     * provider stores seconds and the key's dateMs is truncated with `/ 1000`).
     */
    private fun isAfterInSeconds(dateMs: Long, id: Long, before: PageKey?): Boolean {
        if (before == null) return true
        val sec = Math.floorDiv(dateMs, 1000L)
        val keySec = before.dateMs / 1000L
        return sec < keySec || (sec == keySec && id < before.id)
    }

    private fun asciiLower(s: String): String =
        buildString(s.length) { s.forEach { c -> append(if (c in 'A'..'Z') c + 32 else c) } }

    private fun likeContains(haystack: String?, term: String) =
        haystack != null && asciiLower(haystack).contains(asciiLower(term))

    private fun inRange(dateMs: Long, where: RowFilter) =
        (where.startMs == null || dateMs >= where.startMs) && (where.endMs == null || dateMs <= where.endMs)

    private fun smsMatching(threadIds: Collection<Long>, where: RowFilter): List<RawSms> {
        val ids = threadIds.toSet()
        return sms.filter { s ->
            s.threadId in ids && inRange(s.dateMs, where) &&
                (where.bodyContains == null || likeContains(s.body, where.bodyContains))
        }
    }

    private fun mmsMatching(threadIds: Collection<Long>, where: RowFilter): List<RawMms> {
        val ids = threadIds.toSet()
        val textMids: Set<Long>? = where.bodyContains?.let { term ->
            parts.filter { it.contentType == "text/plain" && likeContains(it.text, term) }
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
            // like the device, the thread snippet is empty when the newest row is an mms/RCS one
            val snippet = if (newestIsSms) newestSms!!.body else null
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
