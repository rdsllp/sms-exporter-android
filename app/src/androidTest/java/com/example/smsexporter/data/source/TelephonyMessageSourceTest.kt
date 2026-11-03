package com.example.smsexporter.data.source

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import android.provider.ContactsContract
import com.example.smsexporter.data.ContactResolver
import com.example.smsexporter.data.ConversationIndex
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.model.MessageKind
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.diag.DeviceCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class TelephonyMessageSourceTest {
    @get:Rule val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
    private val src = TelephonyMessageSource(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver)
    @Test fun threadsAndAddressesLoadQuickly() = runBlocking {
        val t0 = System.nanoTime()
        val threads = src.threads(); val addrs = src.canonicalAddresses()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        Log.i("TelephonyMessageSourceTest", "threads=${threads.size} addrs=${addrs.size} elapsedMs=$elapsedMs")
        assertTrue(threads.size > 100); assertTrue(addrs.size > 100)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 800)
    }
    @Test fun mmsDatesAreMilliseconds() = runBlocking {
        val rows = src.mms(src.threads().take(50).map { it.id }, RowFilter(), 20, null)
        assertTrue(rows.all { it.dateMs > 1_000_000_000_000L })
    }
    @Test fun rcsRowsHaveProtoTrId() = runBlocking {
        val ids = src.threads().map { it.id }
        val rcs = src.mms(ids, RowFilter(), 50, null).filter { it.trId?.startsWith("proto:") == true }
        assertTrue(rcs.isNotEmpty())
        assertTrue(src.parts(rcs.map { it.id }).isNotEmpty())
    }
    @Test fun smsPagingAndLimit() = runBlocking {
        val ids = src.threads().map { it.id }
        val page1 = src.sms(ids, RowFilter(), limit = 5, before = null)
        assertTrue(page1.size <= 5)
        assertTrue(src.smsCount(ids, RowFilter()) >= page1.size)
        val last = page1.last()
        val page2 = src.sms(ids, RowFilter(), limit = 5, before = PageKey(last.dateMs, last.id))
        val page1Ids = page1.map { it.id }.toSet()
        assertTrue(page2.none { it.id in page1Ids })
        val newestMms = src.mms(ids, RowFilter(), 50, null)
        val addrs = src.addrs(newestMms.map { it.id })
        assertTrue(addrs.values.flatten().any { it.type == 137 && it.address.isNotBlank() })
    }
    @Test fun repositoryMergesAndPagesQuickly() = runBlocking {
        val repo = MessageRepository(src, NameLookup { _, _ -> null })
        val t0 = System.nanoTime()
        val conversations = repo.conversations()
        val convMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(conversations.size < src.threads().size)
        assertEquals(conversations.size, conversations.map { it.key }.toSet().size)
        val t1 = System.nanoTime()
        val filled = repo.fillLastKinds()
        val fillMs = (System.nanoTime() - t1) / 1_000_000
        val withKind = filled.count { it.lastKind != null }
        val mmsLast = filled.filter { it.lastKind == MessageKind.RCS || it.lastKind == MessageKind.MMS }
        val mmsWithSnippet = mmsLast.count { !it.snippet.isNullOrBlank() }
        val t2 = System.nanoTime()
        val page = repo.page(conversations.first(), MessageFilter(), null)
        val pageMs = (System.nanoTime() - t2) / 1_000_000
        Log.i("TelephonyMessageSourceTest", "conversations=${conversations.size} conversationsMs=$convMs fillLastKindsMs=$fillMs withKind=$withKind rcsOrMmsLast=${mmsLast.size} withSnippet=$mmsWithSnippet page=${page.size} pageMs=$pageMs")
        assertTrue("conversations took ${convMs}ms", convMs < 1000)
        assertTrue("fillLastKinds took ${fillMs}ms", fillMs < 2000)
        assertTrue("only $withKind of ${filled.size} have lastKind", withKind * 10 >= filled.size * 9)
        assertTrue("only $mmsWithSnippet of ${mmsLast.size} RCS/MMS-last conversations have a snippet", mmsWithSnippet * 10 >= mmsLast.size * 8)
        assertTrue("page took ${pageMs}ms", pageMs < 300)
    }
    @Test fun contactResolverResolvesSavedNumberAndSkipsSenderIds() {
        val resolver = ContactResolver(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver)
        val name = resolver.name("num:8617482941", "+918617482941")
        assertNotNull("saved contact not found", name)
        assertNull(resolver.name("raw:jd-axisbk", "JD-AxisBk"))
        val t0 = System.nanoTime()
        resolver.name("num:8617482941", "+918617482941")
        val elapsedNs = System.nanoTime() - t0
        Log.i("TelephonyMessageSourceTest", "contactNameLength=${name!!.length} cachedCallNs=$elapsedNs")
        assertTrue("cached call took ${elapsedNs}ns", elapsedNs < 1_000_000)
    }
    /** The preload and its suffix gate must give the same name as one PhoneLookup per number. */
    @Test fun contactResolverPreloadAgreesWithPhoneLookup() = runBlocking {
        val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val numbers = LinkedHashMap<String, String>()
        ConversationIndex.build(src.threads(), src.canonicalAddresses(), NameLookup { n, r -> if (n.startsWith("num:")) numbers.putIfAbsent(n, r); null })
        val resolver = ContactResolver(cr).also { it.preload() }
        val diffs = numbers.count { (norm, raw) ->
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(raw).build()
            val direct = cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            resolver.name(norm, raw) != direct
        }
        val skipped = resolver.skippedCount.get()
        Log.i("TelephonyMessageSourceTest", "numbers=${numbers.size} preloadVsPhoneLookupDiffs=$diffs skippedByGate=$skipped")
        assertTrue(numbers.size > 100)
        assertEquals(0, diffs)
        assertTrue("suffix gate never skipped a lookup", skipped > 0)
    }
    /** The device check report on real data: rows and RCS counted, an image part read. Logs the timings line only. */
    @Test fun deviceCheckRunsOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = DeviceCheck(context, src, MessageRepository(src, NameLookup { _, _ -> null })).run().text
        val lines = report.lines()
        Log.i("TelephonyMessageSourceTest", lines.first { it.startsWith("timings ms: ") })
        val rows = lines.first { it.startsWith("rows: sms=") }
        val rcs = Regex(""" rcs=(\d+) """).find(rows)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue("no RCS rows counted", rcs > 0)
        assertTrue("image part not read", lines.any { it.startsWith("image part read: ok") })
    }
}
