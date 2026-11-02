package com.example.smsexporter.data

/** Decides which raw addresses (phone numbers, sender IDs, RCS ids) belong to the same conversation. */
object AddressNormalizer {
    private val STRIP = setOf(' ', '\t', '-', '(', ')')

    fun normalize(raw: String): String {
        val stripped = raw.filterNot { it in STRIP }
        val digits = stripped.count { it.isDigit() }
        val startsOk = stripped.firstOrNull()?.let { it == '+' || it.isDigit() } == true
        return if (digits >= 10 && startsOk) {
            "num:" + stripped.filter { it.isDigit() }.takeLast(10)
        } else {
            "raw:" + raw.trim().lowercase()
        }
    }

    fun conversationKey(normalized: Collection<String>): String =
        normalized.distinct().sorted().joinToString("|")
}
