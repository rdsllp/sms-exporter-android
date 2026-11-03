package com.example.smsexporter.ui.conversation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.PageCursor
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.MessageId
import com.example.smsexporter.data.model.Reaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConversationUiState(
    val conversation: Conversation?,
    val messages: List<Message>, // newest first; received reactions with a target are not here
    val reactionsByTarget: Map<MessageId, List<Reaction>>,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val filter: MessageFilter,
    val total: Int,
)

/**
 * One conversation's messages, paged newest first. All state changes happen on the main thread;
 * provider reads run on [dispatcher]. [setSearch] is debounced; [setFilter] applies at once.
 */
@OptIn(FlowPreview::class)
class ConversationViewModel(
    private val repo: MessageRepository,
    private val key: String,
    initialFilter: MessageFilter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ConversationUiState(null, emptyList(), emptyMap(), loadingMore = true, endReached = false, initialFilter, 0),
    )
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val search = MutableStateFlow(normalize(initialFilter.query))
    private var conversation: Conversation? = null
    /** Last raw message of the previous page (before reactions were split out). */
    private var cursor: PageCursor? = null
    private var pageJob: Job? = null
    private var countJob: Job? = null

    init {
        viewModelScope.launch {
            val c = try {
                withContext(dispatcher) { repo.conversation(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ConvVm", "conversation load failed", e)
                null
            }
            conversation = c
            if (c == null) {
                _state.update { it.copy(loadingMore = false, endReached = true) }
            } else {
                _state.update { it.copy(conversation = c) }
                reload(_state.value.filter)
            }
        }
        search.drop(1).debounce(DEBOUNCE_MS).onEach { q ->
            val f = _state.value.filter
            if (q != f.query) applyFilter(f.copy(query = q))
        }.launchIn(viewModelScope)
    }

    /** Loads the next older page unless one is loading or the end was reached. */
    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.endReached || conversation == null) return
        _state.update { it.copy(loadingMore = true) }
        pageJob = viewModelScope.launch { fetchPage() }
    }

    /** Sets the filter's text query (debounced); null or blank clears it. */
    fun setSearch(q: String?) { search.value = normalize(q) }

    fun setFilter(f: MessageFilter) {
        search.value = normalize(f.query)
        applyFilter(f.copy(query = normalize(f.query)))
    }

    private fun applyFilter(f: MessageFilter) {
        if (conversation == null) {
            _state.update { it.copy(filter = f) } // the initial load will use it
            return
        }
        reload(f)
    }

    /** Drops loaded messages, then loads the first page and the count for [f]. */
    private fun reload(f: MessageFilter) {
        val c = conversation ?: return
        pageJob?.cancel()
        countJob?.cancel()
        cursor = null
        _state.update {
            it.copy(messages = emptyList(), reactionsByTarget = emptyMap(), loadingMore = true, endReached = false, filter = f)
        }
        pageJob = viewModelScope.launch { fetchPage() }
        countJob = viewModelScope.launch {
            try {
                val n = withContext(dispatcher) { repo.count(c, f) }
                _state.update { it.copy(total = n) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ConvVm", "count failed", e)
            }
        }
    }

    private suspend fun fetchPage() {
        val c = conversation ?: return
        val f = _state.value.filter
        try {
            val page = withContext(dispatcher) { repo.page(c, f, cursor, PAGE_SIZE) }
            page.lastOrNull()?.let { cursor = PageCursor.of(it) }
            val (badges, inline) = page.partition { it.reaction?.targetId != null }
            _state.update { s ->
                val reactions = if (badges.isEmpty()) s.reactionsByTarget else {
                    s.reactionsByTarget.toMutableMap().apply {
                        badges.forEach { m ->
                            val r = m.reaction!!
                            put(r.targetId!!, getOrElse(r.targetId) { emptyList() } + r)
                        }
                    }
                }
                s.copy(
                    messages = s.messages + inline,
                    reactionsByTarget = reactions,
                    loadingMore = false,
                    endReached = page.size < PAGE_SIZE,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ConvVm", "page load failed", e)
            _state.update { it.copy(loadingMore = false, endReached = true) }
        }
    }

    private fun normalize(q: String?): String? = q?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val PAGE_SIZE = 200
        const val DEBOUNCE_MS = 150L
    }
}
