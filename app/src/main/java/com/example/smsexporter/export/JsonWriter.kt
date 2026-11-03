package com.example.smsexporter.export

import com.example.smsexporter.data.AddressNormalizer
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class JsonExport(val conversation: JsonConversation, val messages: List<JsonMessage>)

@Serializable
data class JsonConversation(
    val title: String,
    val participants: List<JsonParticipant>,
    val exportedAt: String,
    val filter: JsonFilter?,
    val partial: Boolean,
    val messageCount: Int,
)

@Serializable data class JsonParticipant(val address: String, val name: String?)

@Serializable
data class JsonFilter(val start: String?, val end: String?, val query: String?, val hasAttachment: Boolean, val kind: String?)

@Serializable
data class JsonMessage(
    val id: String,
    val kind: String,
    val direction: String,
    val sender: String,
    val recipients: List<String>,
    val timestamp: String,
    val body: String?,
    val attachments: List<JsonAttachment>,
    val botCard: JsonBotCard?,
    val reaction: JsonReaction?,
)

@Serializable
data class JsonAttachment(val fileName: String, val mimeType: String, val sizeBytes: Long?, val missing: Boolean = false)

@Serializable data class JsonBotCard(val title: String?, val description: String?, val suggestions: List<String>)

@Serializable data class JsonReaction(val emoji: String, val reactionTo: String?)

/** Builds and encodes one conversation as JSON. Sender/recipient/timestamp rules match [CsvWriter]. */
object JsonWriter {
    private val FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }

    fun build(
        conversation: Conversation,
        messages: List<Message>,
        filter: MessageFilter,
        exportedAt: ZonedDateTime,
        attachmentNames: (Message) -> List<JsonAttachment>,
    ): JsonExport {
        val zone = exportedAt.zone
        return JsonExport(
            conversation = JsonConversation(
                title = conversation.title,
                participants = conversation.participants.map { JsonParticipant(it.rawAddress, it.displayName) },
                exportedAt = exportedAt.truncatedTo(ChronoUnit.SECONDS).format(FMT),
                filter = if (filter.isActive) {
                    JsonFilter(
                        start = filter.startMs?.let { iso(it, zone) },
                        end = filter.endMs?.let { iso(it, zone) },
                        query = filter.query?.takeIf { it.isNotBlank() },
                        hasAttachment = filter.hasAttachment,
                        kind = filter.kind?.name?.lowercase(),
                    )
                } else {
                    null
                },
                partial = filter.isActive,
                messageCount = messages.size,
            ),
            messages = messages.map { toJson(it, conversation, zone, attachmentNames) },
        )
    }

    fun encode(e: JsonExport): String = json.encodeToString(JsonExport.serializer(), e)

    private fun toJson(
        m: Message,
        conversation: Conversation,
        zone: ZoneId,
        attachmentNames: (Message) -> List<JsonAttachment>,
    ): JsonMessage {
        val sent = m.direction != Direction.RECEIVED
        val recipients = if (sent) {
            conversation.participants.map { it.rawAddress }
        } else {
            val from = AddressNormalizer.normalize(m.address)
            conversation.participants.filter { it.normalized != from }.map { it.rawAddress }
        }
        return JsonMessage(
            id = m.id.toString(),
            kind = m.kind.name.lowercase(),
            direction = m.direction.name.lowercase(),
            sender = if (sent) "me" else m.address,
            recipients = recipients,
            timestamp = iso(m.timestampMs, zone),
            body = m.body,
            attachments = attachmentNames(m),
            botCard = m.botCard?.let { JsonBotCard(it.title, it.description, it.suggestions) },
            reaction = m.reaction?.let { JsonReaction(it.emoji, it.targetId?.toString()) },
        )
    }

    private fun iso(ms: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(FMT)
}
