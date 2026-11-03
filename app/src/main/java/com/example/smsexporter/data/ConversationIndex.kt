package com.example.smsexporter.data

import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Participant
import com.example.smsexporter.data.source.RawThread

fun interface NameLookup {
    fun name(normalized: String, raw: String): String?

    /** Optional bulk warm-up before many [name] calls. */
    fun preload() {}
}

object ConversationIndex {
    private val UNKNOWN = Participant("unknown", "Unknown", null)
    private val NEWEST_FIRST = compareByDescending<RawThread> { it.dateMs }.thenByDescending { it.id }

    fun build(threads: List<RawThread>, canonical: Map<Long, String>, names: NameLookup): List<Conversation> {
        // normalized address -> raw form, per thread; recipient ids missing from the map are skipped
        val resolved = threads.map { t ->
            t to t.recipientIds.mapNotNull { canonical[it] }.associateBy { AddressNormalizer.normalize(it) }
        }
        val groups = resolved.groupBy { (t, addrs) ->
            if (addrs.isEmpty()) "unknown:${t.id}" else AddressNormalizer.conversationKey(addrs.keys)
        }
        return groups.map { (key, members) ->
            val sorted = members.sortedWith { a, b -> NEWEST_FIRST.compare(a.first, b.first) }
            val newest = sorted.first().first
            val participants = if (sorted.first().second.isEmpty()) {
                listOf(UNKNOWN)
            } else {
                // newest thread's raw form wins when a normalized address appears in several threads
                val rawByNorm = LinkedHashMap<String, String>()
                for ((_, addrs) in sorted.asReversed()) rawByNorm.putAll(addrs)
                rawByNorm.toSortedMap().map { (norm, raw) -> Participant(norm, raw, names.name(norm, raw)) }
            }
            Conversation(
                key = key,
                threadIds = sorted.map { it.first.id },
                participants = participants,
                lastTimestampMs = newest.dateMs,
                messageCount = sorted.sumOf { it.first.messageCount },
                snippet = newest.snippet,
                lastKind = null,
                hasAttachment = sorted.any { it.first.hasAttachment },
            )
        }.sortedWith(compareByDescending<Conversation> { it.lastTimestampMs }.thenBy { it.key })
    }
}
