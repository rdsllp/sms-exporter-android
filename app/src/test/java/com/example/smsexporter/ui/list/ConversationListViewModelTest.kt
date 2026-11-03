package com.example.smsexporter.ui.list

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    private val related = "application/vnd.wap.multipart.related"
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    /**
     * Three conversations:
     * - thread 1: bank sender id, text-only sms, newest;
     * - thread 2: a saved contact ("Priya", +919876543210) whose last message is an image mms;
     * - thread 3: a group of two numbers with a text-only mms, oldest.
     */
    private val source = FakeMessageSource(
        sms = listOf(
            RawSms(1, 1, "JD-AxisBk", 1, 3000, "Your a/c is credited"),
            RawSms(2, 2, "+919876543210", 1, 1500, "hello"),
        ),
        mms = listOf(
            RawMms(10, 2, 1, 2000, "T10", related, false),
            RawMms(11, 3, 1, 1000, "T11", related, true),
        ),
        parts = listOf(
            RawPart(100, 10, "image/jpeg", "a.jpg", null, "/x/a.jpg"),
            RawPart(101, 11, "text/plain", null, "group plans", null),
        ),
        addrs = listOf(
            RawAddr(10, "+919876543210", 137),
            RawAddr(11, "+911111111111", 137),
            RawAddr(11, "+912222222222", 151),
        ),
    )
    private val names = NameLookup { norm, _ -> if (norm == "num:9876543210") "Priya" else null }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loadedVm(): ConversationListViewModel {
        val vm = ConversationListViewModel(MessageRepository(source, names), dispatcher, log = {})
        advanceUntilIdle()
        return vm
    }

    private fun ConversationListViewModel.titles() = state.value.conversations.map { it.title }

    @Test fun sortByNameDescending() = runTest(dispatcher) {
        val vm = loadedVm()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("JD-AxisBk", "Priya", "+911111111111, +912222222222"), vm.titles()) // date newest
        vm.setSort(SortOrder.NAME_DESC)
        advanceUntilIdle()
        assertEquals(listOf("Priya", "JD-AxisBk", "+911111111111, +912222222222"), vm.titles())
        vm.setSort(SortOrder.NAME_ASC)
        advanceUntilIdle()
        assertEquals(listOf("+911111111111, +912222222222", "JD-AxisBk", "Priya"), vm.titles())
    }

    @Test fun queryMatchesTitleAndAddress() = runTest(dispatcher) {
        val vm = loadedVm()
        vm.setQuery("AXIS")                     // title (sender id), case-insensitive
        advanceTimeBy(100)                      // still inside the 150 ms debounce
        assertEquals(3, vm.titles().size)
        advanceTimeBy(60)
        runCurrent()
        assertEquals("AXIS", vm.state.value.query)
        assertEquals(listOf("JD-AxisBk"), vm.titles())
        vm.setQuery("98765")                    // raw address of "Priya", not in the title
        advanceUntilIdle()
        assertEquals(listOf("Priya"), vm.titles())
        vm.setQuery("plans")                    // snippet / body of the group
        advanceUntilIdle()
        assertEquals(listOf("+911111111111, +912222222222"), vm.titles())
        vm.setQuery("credited")                 // body text, also the snippet
        advanceUntilIdle()
        assertEquals(listOf("JD-AxisBk"), vm.titles())
        vm.setQuery("hello")                    // body of an older sms; not a title, address or snippet
        advanceUntilIdle()
        assertEquals(listOf("Priya"), vm.titles())
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(3, vm.titles().size)
    }

    @Test fun secondRefreshWinsOverStaleKindFill() = runTest(dispatcher) {
        val repo = MessageRepository(source, names)
        val vm = ConversationListViewModel(repo, dispatcher, log = {})
        vm.refresh()
        vm.refresh()                            // cancels the earlier ones before they publish
        advanceUntilIdle()
        val latest = repo.conversations()       // the cache: the last refresh's list, kinds filled
        assertTrue(vm.state.value.conversations.all { c -> latest.any { it === c } })
        assertEquals(3, vm.state.value.conversations.size)
        assertTrue(vm.state.value.conversations.all { it.lastKind != null })
    }

    @Test fun toggleSelectionEntersAndLeavesSelectionMode() = runTest(dispatcher) {
        val vm = loadedVm()
        val key = vm.state.value.conversations.first().key
        vm.toggleSelected(key)
        advanceUntilIdle()
        assertTrue(vm.state.value.selectionMode)
        assertEquals(setOf(key), vm.state.value.selected)
        vm.toggleSelected(key)
        advanceUntilIdle()
        assertFalse(vm.state.value.selectionMode)
        assertEquals(emptySet<String>(), vm.state.value.selected)
        vm.toggleSelected(key)
        vm.clearSelection()
        advanceUntilIdle()
        assertFalse(vm.state.value.selectionMode)
    }

    @Test fun attachmentFilterHidesTextOnlyConversations() = runTest(dispatcher) {
        val vm = loadedVm()
        vm.setFilter(MessageFilter(hasAttachment = true))
        advanceUntilIdle()
        assertEquals(listOf("Priya"), vm.titles())
        vm.setFilter(MessageFilter(startMs = 2500))  // date range hides only rows older than the start
        advanceUntilIdle()
        assertEquals(listOf("JD-AxisBk"), vm.titles())
    }
}
