package com.example.smsexporter

import android.app.Application
import com.example.smsexporter.data.ContactResolver
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.source.TelephonyMessageSource

/**
 * Holds the app-wide data objects. Both are created on first use, which happens only after the
 * permission gate has passed: a contact lookup made before READ_CONTACTS is granted would be
 * cached as a miss.
 */
class SmsExporterApp : Application() {
    val contacts: ContactResolver by lazy { ContactResolver(contentResolver) }
    val repository: MessageRepository by lazy { MessageRepository(TelephonyMessageSource(contentResolver), contacts) }
}
