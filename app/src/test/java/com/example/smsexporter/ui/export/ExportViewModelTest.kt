package com.example.smsexporter.ui.export

import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.export.ExportFormat
import com.example.smsexporter.export.ExportProgress
import com.example.smsexporter.export.ExportRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private val filter = MessageFilter(query = "axis")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun progressFlowsToState() = runTest(dispatcher) {
        val first = ExportProgress.Running(0, 2, "Alice", 0f)
        val second = ExportProgress.Running(1, 2, "Bob", 0.5f)
        val done = ExportProgress.Done("content://x/1", "Download/SmsExporter/a.zip", "application/zip")
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val requests = mutableListOf<ExportRequest>()
        val vm = ExportViewModel { req ->
            requests += req
            flow {
                emit(first); gate1.await()
                emit(second); gate2.await()
                emit(done)
            }
        }
        assertEquals(ExportUiState.Idle, vm.state.value)

        vm.open(listOf("k1", "k2"), filter, "2 conversations · \"axis\"")
        assertEquals(ExportUiState.Choosing(listOf("k1", "k2"), filter, "2 conversations · \"axis\""), vm.state.value)

        vm.start(ExportFormat.CSV)
        runCurrent()
        assertEquals(listOf(ExportRequest(listOf("k1", "k2"), ExportFormat.CSV, filter)), requests)
        assertEquals(ExportUiState.Running(first), vm.state.value)

        gate1.complete(Unit); runCurrent()
        assertEquals(ExportUiState.Running(second), vm.state.value)

        gate2.complete(Unit); runCurrent()
        assertEquals(ExportUiState.Done(done.uri, done.displayPath, done.mime), vm.state.value)

        vm.dismiss()
        assertEquals(ExportUiState.Idle, vm.state.value)
    }

    @Test
    fun cancelSetsCancelled() = runTest(dispatcher) {
        var cancelled = false
        val vm = ExportViewModel {
            flow {
                emit(ExportProgress.Running(0, 1, "Alice", 0f))
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        vm.open(listOf("k1"), filter, "1 conversation")
        vm.start(ExportFormat.PDF)
        runCurrent()
        assertTrue(vm.state.value is ExportUiState.Running)

        vm.cancel()
        advanceUntilIdle()
        assertEquals(ExportUiState.Cancelled, vm.state.value)
        assertTrue("the export flow is cancelled", cancelled)
    }

    @Test
    fun failedShowsMessage() = runTest(dispatcher) {
        val vm = ExportViewModel { flow<ExportProgress> { emit(ExportProgress.Failed("disk full")) } }
        vm.open(listOf("k1"), filter, "1 conversation")
        vm.start(ExportFormat.JSON)
        advanceUntilIdle()
        assertEquals(ExportUiState.Failed("disk full"), vm.state.value)
    }

    @Test
    fun dismissWhileRunningCancelsAndGoesIdle() = runTest(dispatcher) {
        var cancelled = false
        val vm = ExportViewModel {
            flow<ExportProgress> { try { awaitCancellation() } finally { cancelled = true } }
        }
        vm.open(listOf("k1"), filter, "1 conversation")
        vm.start(ExportFormat.PDF)
        runCurrent()
        vm.dismiss()
        advanceUntilIdle()
        assertEquals(ExportUiState.Idle, vm.state.value)
        assertTrue(cancelled)
    }

    @Test
    fun exceptionFromFlowShowsFailed() = runTest(dispatcher) {
        val vm = ExportViewModel { flow<ExportProgress> { throw IllegalStateException("boom") } }
        vm.open(listOf("k1"), filter, "1 conversation")
        vm.start(ExportFormat.PDF)
        advanceUntilIdle()
        assertEquals(ExportUiState.Failed("boom"), vm.state.value)
    }

    @Test
    fun doneCarriesFailedCount() = runTest(dispatcher) {
        val done = ExportProgress.Done("content://x/1", "Download/SmsExporter/a.zip", "application/zip", failed = 2)
        val vm = ExportViewModel { flow<ExportProgress> { emit(done) } }
        vm.open(listOf("k1", "k2", "k3"), filter, "3 conversations")
        vm.start(ExportFormat.PDF)
        advanceUntilIdle()
        assertEquals(ExportUiState.Done(done.uri, done.displayPath, done.mime, failed = 2), vm.state.value)
    }
}
