package com.example.smsexporter.data

import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    /** Lookups answered "no contact" by the suffix gate without a PhoneLookup query. */
    @VisibleForTesting internal val skippedCount = AtomicInteger()

    /** Digit suffixes of every contact number, set only after a complete [preload]; null = unknown. */
    @Volatile private var suffixes: Suffixes? = null

    /** Last [MIN_MATCH] digits of contact numbers, plus whole digit strings of shorter numbers. */
    private class Suffixes(val tails: Set<String>, val short: Set<String>)

    /**
     * Reads all contact phone numbers once into the cache. Idempotent once it has succeeded; without
     * READ_CONTACTS it does nothing and is retried on the next call.
     */
    override fun preload() {
        if (preloaded) return
        synchronized(this) {
            if (preloaded) return
            preloaded = try {
                bulkLoad()
            } catch (e: Exception) {
                false // SecurityException when contacts are not granted: leave the cache as it is
            }
        }
    }

    /** True when the whole contact list was read. */
    private fun bulkLoad(): Boolean {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        val tails = HashSet<String>()
        val short = HashSet<String>()
        fun addSuffix(number: String) {
            // the uncut form too, in case the provider does not treat a p/w as a pause: an extra suffix only means an extra query
            for (digits in setOf(PhoneDigits.networkDigits(number), PhoneDigits.networkDigits(number, cutAtPause = false))) {
                when {
                    digits.length >= MIN_MATCH -> tails += digits.takeLast(MIN_MATCH)
                    digits.isNotEmpty() -> short += digits
                }
            }
        }
        val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)
            ?: return false
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
        return true
    }

    /** False only when a complete preload saw no contact number PhoneLookup could match [raw] against. */
    private fun mayMatch(raw: String): Boolean {
        val known = suffixes ?: return true
        if (raw.any { it.isLetter() }) return true
        val digits = PhoneDigits.networkDigits(raw)
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
        return cache.getOrPut(normalized) {
            if (mayMatch(raw)) query(raw) else Entry(null, null).also { skippedCount.incrementAndGet() }
        }
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

/** The digits PhoneLookup compares, built the way the platform builds its match key (pure Kotlin, JVM-testable). */
internal object PhoneDigits {
    private const val KEYPAD = "22233344455566677778889999" // A..Z

    /**
     * Digits of [number] in dialing order: any Unicode decimal digit becomes ASCII, ASCII letters
     * become keypad digits, everything else is dropped. With [cutAtPause], stops at the first
     * `,` or `;`, or at a `p`/`w` (any case) that directly follows a digit (a pause, as in
     * "98765 43210p123", not the W in "1-800-FLOWERS").
     */
    fun networkDigits(number: String, cutAtPause: Boolean = true): String {
        val out = StringBuilder(number.length)
        var prevWasDigit = false
        for (c in number) {
            if (cutAtPause && (c == ',' || c == ';')) break
            val lower = c.lowercaseChar()
            if (cutAtPause && prevWasDigit && (lower == 'p' || lower == 'w')) break
            when {
                c.isDigit() -> { out.append('0' + Character.digit(c, 10)); prevWasDigit = true }
                lower in 'a'..'z' -> { out.append(KEYPAD[lower - 'a']); prevWasDigit = false }
            }
        }
        return out.toString()
    }
}
