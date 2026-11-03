package com.example.smsexporter.data

import com.example.smsexporter.data.model.BotCard
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Extracts title, description and suggestion labels from a GSMA botmessage v1.0 JSON part. */
object BotCardParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(json: String, imagePartId: Long?): BotCard? {
        val root = try {
            this.json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val message = root.obj("message") ?: return null
        val content = message.obj("generalPurposeCard")?.obj("content")
            ?: (message.obj("generalPurposeCardCarousel")?.get("content") as? JsonArray)
                ?.firstOrNull() as? JsonObject
            ?: return null
        val title = content.text("title")
        val description = content.text("description")
        if (title == null && description == null) return null
        val suggestions = (content["suggestions"] as? JsonArray).orEmpty().mapNotNull {
            val s = it as? JsonObject
            s?.obj("reply")?.text("displayText") ?: s?.obj("action")?.text("displayText")
        }
        return BotCard(title, description, imagePartId, suggestions)
    }

    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
