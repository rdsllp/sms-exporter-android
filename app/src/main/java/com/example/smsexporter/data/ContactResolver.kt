package com.example.smsexporter.data

import android.content.ContentResolver
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/** Resolves phone numbers to saved contact names and photo thumbnails. Misses are cached too. */
class ContactResolver(private val contentResolver: ContentResolver) : NameLookup {
    private data class Entry(val name: String?, val photoUri: String?)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun name(normalized: String, raw: String): String? = entry(normalized, raw)?.name

    fun photoUri(normalized: String, raw: String): String? = entry(normalized, raw)?.photoUri

    private fun entry(normalized: String, raw: String): Entry? {
        if (!normalized.startsWith("num:")) return null
        return cache.getOrPut(normalized) { query(raw) }
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
}
