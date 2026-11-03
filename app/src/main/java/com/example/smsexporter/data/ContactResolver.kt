package com.example.smsexporter.data

import android.content.ContentResolver
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves phone numbers to saved contact names and photo thumbnails. Misses are cached too.
 * [preload] fills the cache from every contact phone number in one query; numbers it could not
 * match still fall back to one PhoneLookup query each, unless no contact number ends in the same
 * last [MIN_MATCH] digits (PhoneLookup matches on that suffix, so such a query cannot find anyone).
 */
class ContactResolver(private val contentResolver: ContentResolver) : NameLookup {
    private data class Entry(val name: String?, val photoUri: String?)

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile private var preloaded = false

    /** Digit suffixes of every contact number, set only after a complete [preload]; null = unknown. */
    @Volatile private var suffixes: Suffixes? = null

    /** Last [MIN_MATCH] digits of contact numbers, plus whole digit strings of shorter numbers. */
    private class Suffixes(val tails: Set<String>, val short: Set<String>)

    /** Reads all contact phone numbers once into the cache. Idempotent; a no-op without READ_CONTACTS. */
    override fun preload() {
        if (preloaded) return
        synchronized(this) {
            if (preloaded) return
            try {
                bulkLoad()
            } catch (e: Exception) {
                // SecurityException when contacts are not granted: leave the cache as it is
            }
            preloaded = true
        }
    }

    private fun bulkLoad() {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        val tails = HashSet<String>()
        val short = HashSet<String>()
        fun addSuffix(number: String) {
            val digits = number.filter { it.isDigit() }
            when {
                digits.length >= MIN_MATCH -> tails += digits.takeLast(MIN_MATCH)
                digits.isNotEmpty() -> short += digits
            }
        }
        val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)
            ?: return
        cursor.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0) ?: continue
                val entry = Entry(c.getString(2), c.getString(3))
                val key = AddressNormalizer.normalize(number)
                putPreloaded(key, entry)
                addSuffix(number)
                val e164 = c.getString(1) ?: continue
                addSuffix(e164)
                val altKey = AddressNormalizer.normalize(e164)
                if (altKey != key) putPreloaded(altKey, entry)
            }
        }
        suffixes = Suffixes(tails, short)
    }

    /** False only when a complete preload saw no contact number PhoneLookup could match [raw] against. */
    private fun mayMatch(raw: String): Boolean {
        val known = suffixes ?: return true
        if (raw.any { it.isLetter() }) return true
        val digits = raw.filter { it.isDigit() }
        if (digits.length < MIN_MATCH) return true
        return digits.takeLast(MIN_MATCH) in known.tails || known.short.any { digits.endsWith(it) }
    }

    /** First row wins; an entry that already has a name (e.g. from an earlier PhoneLookup) is kept. */
    private fun putPreloaded(key: String, entry: Entry) {
        cache.compute(key) { _, old -> if (old?.name != null) old else entry }
    }

    override fun name(normalized: String, raw: String): String? = entry(normalized, raw)?.name

    fun photoUri(normalized: String, raw: String): String? = entry(normalized, raw)?.photoUri

    private fun entry(normalized: String, raw: String): Entry? {
        if (!normalized.startsWith("num:")) return null
        return cache.getOrPut(normalized) { if (mayMatch(raw)) query(raw) else Entry(null, null) }
    }

    private fun query(raw: String): Entry {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(raw).build()
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) Entry(c.getString(0), c.getString(1)) else null
            } ?: Entry(null, null)
        } catch (e: Exception) {
            Entry(null, null)
        }
    }

    private companion object {
        /** Digits PhoneLookup's loose match compares (PhoneNumberUtils' caller-id min match). */
        const val MIN_MATCH = 7
    }
}
