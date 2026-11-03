package com.example.smsexporter.data

import com.example.smsexporter.data.model.Attachment
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.model.Source
import com.example.smsexporter.data.source.MessageSource
import com.example.smsexporter.data.source.PageKey
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawSms
import com.example.smsexporter.data.source.RowFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Reads conversations and messages from a [MessageSource], merging the sms and mms tables. */
class MessageRepository(private val source: MessageSource, private val names: NameLookup) {
    private val mapper = MessageMapper(source)
    private val cacheLock = Mutex()
    @Volatile private var cache: List<Conversation>? = null

    /**
     * All conversations, newest first. Loaded once and kept in memory until [refresh]. Only the
     * newest [EAGER_LAST_KIND] conversations get `lastKind`; call [fillLastKinds] for the rest.
     */
    suspend fun conversations(refresh: Boolean = false): List<Conversation> = cacheLock.withLock {
        cache?.takeIf { !refresh } ?: run {
            val built = ConversationIndex.build(source.threads(), source.canonicalAddresses(), names)
            val loaded = withLastKinds(built.take(EAGER_LAST_KIND)) + built.drop(EAGER_LAST_KIND)
            cache = loaded
            loaded
        }
    }

    suspend fun conversation(key: String): Conversation? = conversations().firstOrNull { it.key == key }

    /**
     * Returns [conversations] with `lastKind` set from each one's newest row (2 queries per
     * conversation that is still missing it), and stores the result in the cache.
     */
    suspend fun fillLastKinds(conversations: List<Conversation>): List<Conversation> {
        val filled = withLastKinds(conversations)
        val byKey = filled.associateBy { it.key }
        cacheLock.withLock { cache = cache?.map { byKey[it.key] ?: it } }
        return filled
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

    private suspend fun withLastKinds(conversations: List<Conversation>): List<Conversation> =
        conversations.map { c -> if (c.lastKind != null) c else c.copy(lastKind = newestKind(c.threadIds)) }

    private suspend fun newestKind(threadIds: List<Long>): MessageKind? {
        val sms = source.sms(threadIds, RowFilter(), 1, null).firstOrNull()
        val mms = source.mms(threadIds, RowFilter(), 1, null).firstOrNull()
        return when {
            mms != null && (sms == null || mms.dateMs >= sms.dateMs) -> mapper.kindOf(mms)
            sms != null -> MessageKind.SMS
            else -> null
        }
    }

    /** One row of either table, carrying what the merged order needs. */
    private class RawRow private constructor(val sms: RawSms?, val mms: RawMms?, val dateMs: Long, val source: Source, val rowId: Long) {
        constructor(s: RawSms) : this(s, null, s.dateMs, Source.SMS_TABLE, s.id)
        constructor(m: RawMms) : this(null, m, m.dateMs, Source.MMS_TABLE, m.id)
    }

    private companion object {
        const val EAGER_LAST_KIND = 100
        const val ALL_STEP = 500

        /** mms sorts before sms at equal timestamp. */
        fun tableRank(s: Source) = if (s == Source.MMS_TABLE) 0 else 1

        val MERGED_ORDER: Comparator<RawRow> = compareByDescending<RawRow> { it.dateMs }
            .thenBy { tableRank(it.source) }
            .thenByDescending { it.rowId }

        /**
         * Per-table provider key equivalent to this cursor. For the other table at the same
         * timestamp: if it sorts after the cursor's table its rows there are all unseen
         * (id < MAX), otherwise they were all shown already (id < MIN).
         */
        fun PageCursor.keyFor(table: Source): PageKey = when {
            table == source -> PageKey(timestampMs, rowId)
            tableRank(table) > tableRank(source) -> PageKey(timestampMs, Long.MAX_VALUE)
            else -> PageKey(timestampMs, Long.MIN_VALUE)
        }
    }
}
