package com.example.smsexporter.data.source

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class TelephonyMessageSourceTest {
    @get:Rule val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS)
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
        val t2 = System.nanoTime()
        val page = repo.page(conversations.first(), MessageFilter(), null)
        val pageMs = (System.nanoTime() - t2) / 1_000_000
        Log.i("TelephonyMessageSourceTest", "conversations=${conversations.size} conversationsMs=$convMs fillLastKindsMs=$fillMs withKind=$withKind page=${page.size} pageMs=$pageMs")
        assertTrue("conversations took ${convMs}ms", convMs < 1000)
        assertTrue("fillLastKinds took ${fillMs}ms", fillMs < 2000)
        assertTrue("only $withKind of ${filled.size} have lastKind", withKind * 10 >= filled.size * 9)
        assertTrue("page took ${pageMs}ms", pageMs < 300)
    }
}
