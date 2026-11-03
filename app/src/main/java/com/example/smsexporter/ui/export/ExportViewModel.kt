package com.example.smsexporter.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.export.ExportCoordinator
import com.example.smsexporter.export.ExportFormat
import com.example.smsexporter.export.ExportProgress
import com.example.smsexporter.export.ExportRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the export sheets show; [ExportUiState.Idle] shows none. */
sealed interface ExportUiState {
    data object Idle : ExportUiState

    /** The format chooser for [keys] with [filter]; [scopeLine] says what will be exported. */
    data class Choosing(val keys: List<String>, val filter: MessageFilter, val scopeLine: String) : ExportUiState

    data class Running(val progress: ExportProgress.Running) : ExportUiState

    data class Done(val uri: String, val displayPath: String, val mime: String) : ExportUiState

    data class Failed(val message: String) : ExportUiState

    data object Cancelled : ExportUiState
}

/**
 * Drives one export from a screen: [open] shows the chooser, [start] runs the export and maps its
 * progress to [state], [cancel] stops it ([ExportUiState.Cancelled]), [dismiss] closes the sheets.
 *
 * One instance per screen, created with the app's coordinator. The export runs in
 * [viewModelScope], so it survives rotation but is cancelled when the screen is left (its
 * navigation entry is popped and this view model cleared); the coordinator then aborts its sink,
 * so no partial file is left in Downloads.
 */
class ExportViewModel(private val run: (ExportRequest) -> Flow<ExportProgress>) : ViewModel() {
    constructor(coordinator: ExportCoordinator) : this(coordinator::run)

    private val _state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun open(keys: List<String>, filter: MessageFilter, scopeLine: String) {
        if (job?.isActive == true) return
        _state.value = ExportUiState.Choosing(keys, filter, scopeLine)
    }

    fun start(format: ExportFormat) {
        val choosing = _state.value as? ExportUiState.Choosing ?: return
        val request = ExportRequest(choosing.keys, format, choosing.filter)
        // shown until the first emission arrives; the sheet reads an empty title as "Preparing"
        _state.value = ExportUiState.Running(ExportProgress.Running(0, choosing.keys.size, "", 0f))
        job = viewModelScope.launch {
            try {
                run(request).collect { p ->
                    // cancel() runs on the main thread too; never overwrite its Cancelled
                    currentCoroutineContext().ensureActive()
                    _state.value = when (p) {
                        is ExportProgress.Running -> ExportUiState.Running(p)
                        is ExportProgress.Done -> ExportUiState.Done(p.uri, p.displayPath, p.mime)
                        is ExportProgress.Failed -> ExportUiState.Failed(p.message)
                        ExportProgress.Cancelled -> ExportUiState.Cancelled
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = ExportUiState.Failed(e.message ?: e::class.simpleName ?: "Export failed")
            }
        }
    }

    /** Stops a running export; the sheet then shows "Export cancelled". */
    fun cancel() {
        val j = job ?: return
        if (!j.isActive) return
        j.cancel()
        job = null
        _state.value = ExportUiState.Cancelled
    }

    /** Closes the sheets, cancelling a running export. */
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = ExportUiState.Idle
    }
}
