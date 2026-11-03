package com.example.smsexporter.data

import android.util.Log
import com.example.smsexporter.data.model.Attachment
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.Source
import com.example.smsexporter.data.source.MessageSource
import com.example.smsexporter.data.source.PageKey
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawSms
import com.example.smsexporter.data.source.RowFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Position in the merged message stream: the last message of the previous page.
 * Merged order is `timestampMs` desc, then mms before sms at equal timestamp, then `rowId` desc.
 */
data class PageCursor(val timestampMs: Long, val source: Source, val rowId: Long) {
    companion object {
        fun of(m: Message) = PageCursor(m.timestampMs, m.id.source, m.id.rowId)
    }
}

/**
 * Reads conversations and messages from a [MessageSource], merging the sms and mms tables.
 * [log] receives load-phase timings (replaced in unit tests, where android.util.Log is unavailable).
 */
class MessageRepository(
    private val source: MessageSource,
    private val names: NameLookup,
    private val log: (String) -> Unit = { Log.i("Perf", it) },
) {
    private val mapper = MessageMapper(source)
    private val cacheLock = Mutex()
    @Volatile private var cache: List<Conversation>? = null

    /**
     * All conversations, newest first, with `lastKind = null`. Loaded once and kept in memory
     * until [refresh]. Call [fillLastKinds] afterwards to set `lastKind`.
     */
    suspend fun conversations(refresh: Boolean = false): List<Conversation> = cacheLock.withLock {
        cache?.takeIf { !refresh } ?: load().also { cache = it }
    }

    private suspend fun load(): List<Conversation> {
        val t0 = System.nanoTime()
        val threads = source.threads()
        val canonical = source.canonicalAddresses()
        val t1 = System.nanoTime()
        var t2 = 0L
        // contact queries block, so they run on IO whatever the caller's dispatcher
        val built = withContext(Dispatchers.IO) {
            names.preload()
            t2 = System.nanoTime()
            ConversationIndex.build(threads, canonical, names)
        }
        val t3 = System.nanoTime()
        log("threads+canonical ${ms(t0, t1)} ms, preload ${ms(t1, t2)} ms, build ${ms(t2, t3)} ms (${built.size} conversations)")
        return built
    }

    suspend fun conversation(key: String): Conversation? = conversations().firstOrNull { it.key == key }

    /**
     * Sets `lastKind` on every cached conversation from the newest row among its threads (null
     * when none of its threads has rows), using one scan per table. Stores and returns the result.
     */
    suspend fun fillLastKinds(): List<Conversation> {
        val conversations = conversations()
        val t0 = System.nanoTime()
        val newest = source.newestPerThread()
        val filled = conversations.map { c ->
            c.copy(lastKind = c.threadIds.mapNotNull { newest[it] }.maxByOrNull { it.dateMs }?.kind)
        }
        log("fillLastKinds ${ms(t0, System.nanoTime())} ms")
        // skip the store if a refresh replaced the cache meanwhile
        cacheLock.withLock { if (cache === conversations) cache = filled }
        return filled
    }

    /**
     * Keys of the cached conversations that have an sms body or mms text part containing [query]
     * (literal, ASCII case-insensitive), looking at up to [rowLimit] newest matching rows per table.
     */
    suspend fun conversationKeysWithText(query: String, rowLimit: Int = 500): Set<String> {
        val keyOf = HashMap<Long, String>()
        conversations().forEach { c -> c.threadIds.forEach { keyOf[it] = c.key } }
        val where = RowFilter(bodyContains = query)
        val threadIds = source.sms(keyOf.keys, where, rowLimit, null).map { it.threadId } +
            source.mms(keyOf.keys, where, rowLimit, null).map { it.threadId }
        return threadIds.mapNotNullTo(HashSet()) { keyOf[it] }
    }

    /**
     * Up to [limit] messages after [before] (from the newest when null), newest first, both
     * tables merged, reactions attached. Pass `PageCursor.of(page.last())` to get the next page.
     */
    suspend fun page(conversation: Conversation, filter: MessageFilter, before: PageCursor?, limit: Int = 200): List<Message> {
        if (limit <= 0) return emptyList()
        val rowFilter = filter.toRowFilter()
        val out = ArrayList<Message>(limit)
        var cursor = before
        while (out.size < limit) {
            val step = mergedStep(conversation.threadIds, rowFilter, cursor, limit)
            if (step.isEmpty()) break
            step.filterTo(out) { matches(it, filter) }
            if (step.size < limit) break          // both tables exhausted
            cursor = PageCursor.of(step.last())   // last raw row, not last matching row
        }
        return attachReactions(out.take(limit))
    }

    /** Number of matching messages. O(n) (reads every message) when `kind` or `hasAttachment` is set. */
    suspend fun count(conversation: Conversation, filter: MessageFilter): Int {
        if (filter.needsInMemoryCheck) return all(conversation, filter).size
        val rowFilter = filter.toRowFilter()
        return source.smsCount(conversation.threadIds, rowFilter) + source.mmsCount(conversation.threadIds, rowFilter)
    }

    /** Every matching message, newest first (for export). */
    suspend fun all(conversation: Conversation, filter: MessageFilter): List<Message> {
        val out = ArrayList<Message>()
        var cursor: PageCursor? = null
        while (true) {
            val p = page(conversation, filter, cursor, ALL_STEP)
            out += p
            if (p.size < ALL_STEP) return out
            cursor = PageCursor.of(p.last())
        }
    }

    fun openAttachment(a: Attachment): InputStream? = source.openPart(a.partId)

    /** Fetches [limit] rows from each table after [cursor], keeps the first [limit] in merged order. */
    private suspend fun mergedStep(threadIds: List<Long>, where: RowFilter, cursor: PageCursor?, limit: Int): List<Message> {
        val sms = source.sms(threadIds, where, limit, cursor?.keyFor(Source.SMS_TABLE))
        val mms = source.mms(threadIds, where, limit, cursor?.keyFor(Source.MMS_TABLE))
        val kept = (sms.map(::RawRow) + mms.map(::RawRow)).sortedWith(MERGED_ORDER).take(limit)
        val keptMms = kept.mapNotNull { it.mms }
        val mappedMms = mapper.mms(keptMms).associateBy { it.id.rowId }
        return kept.map { r -> r.sms?.let(mapper::sms) ?: mappedMms.getValue(r.rowId) }
    }

    private fun matches(m: Message, filter: MessageFilter): Boolean =
        (filter.kind == null || m.kind == filter.kind) && (!filter.hasAttachment || m.attachments.isNotEmpty())

    /** One row of either table, carrying what the merged order needs. */
    private class RawRow private constructor(val sms: RawSms?, val mms: RawMms?, val dateMs: Long, val source: Source, val rowId: Long) {
        constructor(s: RawSms) : this(s, null, s.dateMs, Source.SMS_TABLE, s.id)
        constructor(m: RawMms) : this(null, m, m.dateMs, Source.MMS_TABLE, m.id)
    }

    private companion object {
        const val ALL_STEP = 500

        fun ms(fromNs: Long, toNs: Long) = (toNs - fromNs) / 1_000_000

        /** mms sorts before sms at equal timestamp. */
        fun tableRank(s: Source) = if (s == Source.MMS_TABLE) 0 else 1

        val MERGED_ORDER: Comparator<RawRow> = compareByDescending<RawRow> { it.dateMs }
            .thenBy { tableRank(it.source) }
            .thenByDescending { it.rowId }

        /**
         * Per-table provider key equivalent to this cursor. For the other table at the same
         * timestamp: if it sorts after the cursor's table its rows there are all unseen
         * (id < MAX), otherwise they were all shown already (id < MIN).
         *
         * Invariant: an mms PageKey must carry a whole-second dateMs, because the provider
         * stores mms dates in seconds and the source truncates the key with `/ 1000`. An sms
         * cursor at 5_000_500 ms therefore becomes 5_001_000 (`date < 5001` admits mms at
         * 5000 s, which are older); an exact 5_000_000 stays put (mms at 5000 s sort before
         * that sms and were already shown).
         */
        fun PageCursor.keyFor(table: Source): PageKey = when {
            table == source -> PageKey(timestampMs, rowId)
            tableRank(table) > tableRank(source) -> PageKey(timestampMs, Long.MAX_VALUE)
            table == Source.MMS_TABLE -> PageKey(ceilToSecond(timestampMs), Long.MIN_VALUE)
            else -> PageKey(timestampMs, Long.MIN_VALUE)
        }

        fun ceilToSecond(ms: Long): Long = Math.floorDiv(ms + 999, 1000L) * 1000L
    }
}
