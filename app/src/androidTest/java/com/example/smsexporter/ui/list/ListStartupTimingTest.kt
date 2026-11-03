package com.example.smsexporter.ui.list

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.smsexporter.data.ContactResolver
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.source.TelephonyMessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real provider, real contacts: the home list must be non-empty within 1 s of the first refresh. */
@RunWith(AndroidJUnit4::class) class ListStartupTimingTest {
    @get:Rule val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
    private val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test fun listIsNonEmptyWithinOneSecondAndNewestPageWithin300ms() = runBlocking {
        val repo = MessageRepository(TelephonyMessageSource(cr), ContactResolver(cr))
        val t0 = System.nanoTime()
        val vm = ConversationListViewModel(repo, Dispatchers.Default) // init runs refresh()
        val state = withTimeoutOrNull(1000) { vm.state.first { it.conversations.isNotEmpty() } }
        val listMs = (System.nanoTime() - t0) / 1_000_000
        Log.i("ListStartupTimingTest", "refresh to first non-empty state: ${listMs}ms (${state?.conversations?.size} visible)")
        assertNotNull("list still empty after ${listMs}ms", state)

        val t1 = System.nanoTime()
        val page = repo.page(state!!.conversations.first(), MessageFilter(), null)
        val pageMs = (System.nanoTime() - t1) / 1_000_000
        Log.i("ListStartupTimingTest", "newest page: ${page.size} messages in ${pageMs}ms")
        assertTrue("page took ${pageMs}ms", pageMs < 300)
    }
}
