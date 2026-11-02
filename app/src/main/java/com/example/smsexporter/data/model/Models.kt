package com.example.smsexporter.data.model

enum class MessageKind { SMS, MMS, RCS }
enum class Direction { RECEIVED, SENT, FAILED }
enum class Source { SMS_TABLE, MMS_TABLE }

data class MessageId(val source: Source, val rowId: Long) {
    override fun toString(): String = "${if (source == Source.SMS_TABLE) "sms" else "mms"}:$rowId"

    companion object {
        fun parse(s: String): MessageId {
            val prefix = s.substringBefore(':', "")
            val source = when (prefix) {
                "sms" -> Source.SMS_TABLE
                "mms" -> Source.MMS_TABLE
                else -> throw IllegalArgumentException("Invalid MessageId: $s")
            }
            val rowId = s.substringAfter(':', "").toLongOrNull()
                ?: throw IllegalArgumentException("Invalid MessageId: $s")
            return MessageId(source, rowId)
        }
    }
}

data class Attachment(
    val partId: Long,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long?,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val contentUri: String get() = "content://mms/part/$partId"
}

data class BotCard(
    val title: String?,
    val description: String?,
    val imagePartId: Long?,
    val suggestions: List<String>,
)

data class Reaction(val emoji: String, val quotedText: String?, val targetId: MessageId?)

data class Message(
    val id: MessageId,
    val kind: MessageKind,
    val direction: Direction,
    val threadId: Long,
    val address: String,            // raw sender for received, raw first recipient for sent
    val timestampMs: Long,
    val body: String?,
    val attachments: List<Attachment> = emptyList(),
    val botCard: BotCard? = null,
    val reaction: Reaction? = null, // non-null when this row IS a reaction
)

data class Participant(val normalized: String, val rawAddress: String, val displayName: String?) {
    val label: String get() = displayName ?: rawAddress
}

data class Conversation(
    val key: String,                // sorted normalized addresses joined by "|"
    val threadIds: List<Long>,
    val participants: List<Participant>,
    val lastTimestampMs: Long,
    val messageCount: Int,
    val snippet: String?,
    val lastKind: MessageKind?,
    val hasAttachment: Boolean,
) {
    val title: String get() = participants.joinToString(", ") { it.label }
    val isGroup: Boolean get() = participants.size > 1
}
