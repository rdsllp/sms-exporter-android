package com.example.smsexporter.ui.conversation

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.data.model.MessageId
import com.example.smsexporter.data.model.Source
import com.example.smsexporter.data.source.FakeMessageSource
import com.example.smsexporter.data.source.RawAddr
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawPart
import com.example.smsexporter.data.source.RawSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val related = "application/vnd.wap.multipart.related"
    private val addr = "+918617482941"
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    /**
     * One conversation (thread 1), 255 rows, newest first:
     * - mms:2004 received image mms (a.jpg);
     * - mms:2003 sent RCS "👍" (a sent reaction);
     * - mms:2002 received RCS reaction "❤️ to ' Okay '" (targets sms:1001 on the same page);
     * - mms:2001 sent RCS "I love you";
     * - sms:1001 sent "Okay";
     * - sms:250 .. sms:1 received "msg N" (enough for a second page at limit 200).
     */
    private val source = FakeMessageSource(
        sms = (1L..250L).map { RawSms(it, 1, addr, 1, it * 1000, "msg $it") } +
            RawSms(1001, 1, addr, 2, 300_000, "Okay"),
        mms = listOf(
            RawMms(2001, 1, 2, 301_000, "proto:a", related, true),
            RawMms(2002, 1, 1, 302_000, "proto:b", related, true),
            RawMms(2003, 1, 2, 303_000, "proto:c", related, true),
            RawMms(2004, 1, 1, 304_000, "T4", related, false),
        ),
        parts = listOf(
            RawPart(1, 2001, "text/plain", null, "I love you", null),
            RawPart(2, 2002, "text/plain", null, "​❤️​ to ' Okay '", null),
            RawPart(3, 2003, "text/plain", null, "👍", null),
            RawPart(4, 2004, "image/jpeg", "a.jpg", null, "/x/a.jpg"),
        ),
        addrs = listOf(
            RawAddr(2001, addr, 151), RawAddr(2002, addr, 137), RawAddr(2003, addr, 151), RawAddr(2004, addr, 137),
        ),
    )
    private val repo = MessageRepository(source, NameLookup { _, _ -> null })
    private val okay = MessageId(Source.SMS_TABLE, 1001)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.loadedVm(filter: MessageFilter = MessageFilter()): ConversationViewModel {
        val key = repo.conversations().single().key
        val vm = ConversationViewModel(repo, key, filter, dispatcher)
        advanceUntilIdle()
        return vm
    }

    private fun ConversationViewModel.ids() = state.value.messages.map { it.id.toString() }

    @Test fun receivedReactionsBecomeBadges() = runTest(dispatcher) {
        val vm = loadedVm()
        val s = vm.state.value
        assertTrue("mms:2002" !in vm.ids())
        assertTrue("sms:1001" in vm.ids())
        assertEquals(listOf("❤️"), s.reactionsByTarget[okay]?.map { it.emoji })
        assertEquals("Okay", s.reactionsByTarget[okay]?.single()?.quotedText)
    }

    @Test fun sentReactionStaysInline() = runTest(dispatcher) {
        val vm = loadedVm()
        val thumbs = vm.state.value.messages.single { it.id.toString() == "mms:2003" }
        assertEquals("👍", thumbs.reaction?.emoji)
        assertNull(thumbs.reaction?.targetId)
        assertEquals(listOf("mms:2004", "mms:2003", "mms:2001", "sms:1001"), vm.ids().take(4))
    }

    @Test fun loadMoreAppendsOlderAndStopsAtEnd() = runTest(dispatcher) {
        val vm = loadedVm()
        assertEquals(199, vm.state.value.messages.size)     // 200 rows minus the badge
        assertFalse(vm.state.value.endReached)
        assertEquals(255, vm.state.value.total)
        assertEquals("sms:56", vm.ids().last())

        vm.loadMore()
        vm.loadMore()                                       // ignored while the first is running
        assertTrue(vm.state.value.loadingMore)
        advanceUntilIdle()
        assertFalse(vm.state.value.loadingMore)
        assertTrue(vm.state.value.endReached)
        assertEquals(254, vm.state.value.messages.size)
        assertEquals(vm.ids().distinct(), vm.ids())         // no duplicates across pages
        assertEquals("sms:1", vm.ids().last())

        vm.loadMore()                                       // at the end: no-op
        advanceUntilIdle()
        assertEquals(254, vm.state.value.messages.size)
    }

    @Test fun searchFiltersMessagesAndUpdatesTotal() = runTest(dispatcher) {
        val vm = loadedVm()
        vm.setSearch("LOVE")
        advanceTimeBy(100)                                  // inside the 150 ms debounce
        runCurrent()
        assertEquals(199, vm.state.value.messages.size)
        advanceUntilIdle()
        assertEquals("LOVE", vm.state.value.filter.query)
        assertEquals(listOf("mms:2001"), vm.ids())
        assertEquals(1, vm.state.value.total)
        assertTrue(vm.state.value.endReached)

        vm.setSearch(null)
        advanceUntilIdle()
        assertNull(vm.state.value.filter.query)
        assertEquals(199, vm.state.value.messages.size)
        assertEquals(255, vm.state.value.total)
    }

    @Test fun initialFilterScopesTheConversation() = runTest(dispatcher) {
        val vm = loadedVm(MessageFilter(hasAttachment = true))
        assertEquals(listOf("mms:2004"), vm.ids())
        assertEquals(1, vm.state.value.total)
        vm.setFilter(MessageFilter(startMs = 303_000))
        advanceUntilIdle()
        assertEquals(listOf("mms:2004", "mms:2003"), vm.ids())
        assertEquals(2, vm.state.value.total)
    }
}
