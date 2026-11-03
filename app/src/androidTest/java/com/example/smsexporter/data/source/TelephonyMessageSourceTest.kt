package com.example.smsexporter.data.source

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
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
}
