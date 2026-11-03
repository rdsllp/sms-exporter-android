package com.example.smsexporter.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Names RCS business-bot conversations. The provider stores a bot's canonical address as an opaque
 * blob, but every RCS message's `tr_id` is `proto:` + base64(protobuf), and that protobuf contains
 * a sub-message `{1: "<agent>@rbm.goog", 2: "<display name>", 3: "<colour>"}`.
 */
object RcsBotIdentity {
    private val OPAQUE = Regex("[a-z0-9+/_-]{16,}=*@bot\\.rcs\\.google\\.com", RegexOption.IGNORE_CASE)
    private const val PREFIX = "proto:"
    private const val AGENT_SUFFIX = "@rbm.goog"
    private const val MAX_DEPTH = 8

    /** True for `<base32 or base64-ish blob of 16+ chars>@bot.rcs.google.com` (case-insensitive). */
    fun isOpaque(rawAddress: String): Boolean = OPAQUE.matches(rawAddress)

    /** Display name from a `proto:` tr_id, or null when the value is absent, not a proto, undecodable, or has no name. Never throws. */
    fun agentName(trId: String?): String? {
        if (trId == null || !trId.startsWith(PREFIX)) return null
        return try {
            findName(Base64.getDecoder().decode(trId.substring(PREFIX.length)), 0)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** One protobuf field: its number and, for length-delimited fields, its payload (else null). */
    private class Field(val number: Int, val bytes: ByteArray?)

    private fun findName(message: ByteArray, depth: Int): String? {
        val fields = parse(message) ?: return null
        val agent = fields.firstOrNull { it.number == 1 }?.bytes?.let(::text)
        if (agent != null && agent.endsWith(AGENT_SUFFIX)) {
            fields.firstOrNull { it.number == 2 }?.bytes?.let(::text)?.let { return it }
        }
        if (depth >= MAX_DEPTH) return null
        for (f in fields) f.bytes?.let { b -> findName(b, depth + 1)?.let { return it } }
        return null
    }

    /** The fields of [b] read as a protobuf message, or null when it is not one (the whole input must parse). */
    private fun parse(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var pos = 0
        fun varint(): Long? {
            var result = 0L
            for (shift in 0 until 70 step 7) {
                if (pos >= b.size) return null
                val v = b[pos++].toInt()
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return result
            }
            return null
        }
        while (pos < b.size) {
            val tag = varint() ?: return null
            val number = (tag ushr 3).toInt()
            if (number <= 0) return null
            when ((tag and 7).toInt()) {
                0 -> { varint() ?: return null; out += Field(number, null) }
                1 -> { pos += 8; if (pos > b.size) return null; out += Field(number, null) }
                5 -> { pos += 4; if (pos > b.size) return null; out += Field(number, null) }
                2 -> {
                    val len = varint() ?: return null
                    if (len < 0 || len > b.size - pos) return null
                    out += Field(number, b.copyOfRange(pos, pos + len.toInt()))
                    pos += len.toInt()
                }
                else -> return null
            }
        }
        return out
    }

    /** [b] as UTF-8 when it is valid, non-blank and has no control characters, else null. */
    private fun text(b: ByteArray): String? {
        val s = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return null
        }
        return s.takeIf { it.isNotBlank() && s.none { c -> c.isISOControl() } }
    }
}
