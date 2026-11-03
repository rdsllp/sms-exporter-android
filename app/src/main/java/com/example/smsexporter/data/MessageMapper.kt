package com.example.smsexporter.data

import com.example.smsexporter.data.model.Attachment
import com.example.smsexporter.data.model.BotCard
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.model.Source
import com.example.smsexporter.data.source.MessageSource
import com.example.smsexporter.data.source.RawAddr
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawPart
import com.example.smsexporter.data.source.RawSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Turns raw provider rows into [Message]s. */
internal class MessageMapper(private val source: MessageSource) {

    fun sms(r: RawSms): Message = Message(
        id = MessageId(Source.SMS_TABLE, r.id),
        kind = MessageKind.SMS,
        direction = when (r.type) {
            1 -> Direction.RECEIVED
            5 -> Direction.FAILED
            else -> Direction.SENT
        },
        threadId = r.threadId,
        address = r.address ?: "",
        timestampMs = r.dateMs,
        body = r.body,
    )

    /** Maps [rows] in order, fetching their parts and addrs in one query each. */
    suspend fun mms(rows: List<RawMms>): List<Message> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val partsById = source.parts(ids)
        val addrsById = source.addrs(ids)
        return rows.map { r -> mms(r, partsById[r.id].orEmpty(), addrsById[r.id].orEmpty()) }
    }

    fun kindOf(r: RawMms): MessageKind =
        if (r.trId?.startsWith(RCS_TR_ID_PREFIX) == true) MessageKind.RCS else MessageKind.MMS

    private suspend fun mms(r: RawMms, parts: List<RawPart>, addrs: List<RawAddr>): Message {
        val kind = kindOf(r)
        val received = r.msgBox == 1
        val texts = parts.filter { it.contentType == TEXT }.mapNotNull { it.text }
        val botPart = parts.firstOrNull { it.contentType == BOT_JSON }
        return Message(
            id = MessageId(Source.MMS_TABLE, r.id),
            kind = kind,
            direction = if (received) Direction.RECEIVED else Direction.SENT,
            threadId = r.threadId,
            address = addrs.firstOrNull { it.type == if (received) ADDR_FROM else ADDR_TO }?.address ?: "",
            timestampMs = r.dateMs,
            body = texts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            attachments = parts.filter { it.contentType !in NOT_ATTACHMENTS }.map {
                Attachment(it.id, it.contentType ?: OCTET_STREAM, it.name, null)
            },
            botCard = if (kind == MessageKind.RCS && botPart != null) {
                botCard(botPart, parts.firstOrNull { it.contentType?.startsWith("image/") == true }?.id)
            } else null,
        )
    }

    private suspend fun botCard(part: RawPart, imagePartId: Long?): BotCard? {
        val json = withContext(Dispatchers.IO) {
            try {
                source.openPart(part.id)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                null
            }
        } ?: return null
        return BotCardParser.parse(json, imagePartId)
    }

    private companion object {
        const val RCS_TR_ID_PREFIX = "proto:"
        const val ADDR_FROM = 137
        const val ADDR_TO = 151
        const val TEXT = "text/plain"
        const val BOT_JSON = "application/vnd.gsma.botmessage.v1.0+json"
        const val OCTET_STREAM = "application/octet-stream"
        val NOT_ATTACHMENTS = setOf(TEXT, "application/smil", BOT_JSON)
    }
}
