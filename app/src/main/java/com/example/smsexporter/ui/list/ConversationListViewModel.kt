package com.example.smsexporter.ui.list

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.model.Conversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortOrder { DATE_NEWEST, DATE_OLDEST, NAME_ASC, NAME_DESC }

data class ListUiState(
    val loading: Boolean,
    val conversations: List<Conversation>,
    val query: String,
    val filter: MessageFilter,
    val sort: SortOrder,
    val selected: Set<String>, // conversation keys
    val selectionMode: Boolean,
)

/**
 * Conversation list: keeps every conversation in memory and derives the visible, sorted list
 * from (query, filter, sort). [dispatcher] runs loading and list derivation; [log] receives the
 * refresh timing (replaced in unit tests, where android.util.Log is unavailable).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ConversationListViewModel(
    private val repo: MessageRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = { Log.i("ListVm", it) },
) : ViewModel() {
    /** Null while the first load is running. */
    private val all = MutableStateFlow<List<Conversation>?>(null)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(MessageFilter())
    private val sort = MutableStateFlow(SortOrder.DATE_NEWEST)
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    private var loadedOnce = false
    private var refreshJob: Job? = null
    @Volatile private var refreshStartNs = 0L
    @Volatile private var firstStateLogged = false

    /** Applied query (debounced, trimmed) plus conversations whose message bodies contain it. */
    private data class Search(val text: String, val bodyKeys: Set<String>)

    private val search = query
        .debounce(DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { q ->
            emit(Search(q, emptySet()))
            if (q.length >= BODY_SEARCH_MIN) {
                val keys = try {
                    repo.conversationKeysWithText(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ListVm", "body search failed", e)
                    emptySet()
                }
                emit(Search(q, keys))
            }
        }

    private val visible = combine(all, search, filter, sort) { list, s, f, order ->
        list?.let { visibleList(it, s, f, order) }
    }.flowOn(dispatcher).onEach { list ->
        if (!firstStateLogged && !list.isNullOrEmpty()) {
            firstStateLogged = true
            log("refresh to first non-empty state: ${(System.nanoTime() - refreshStartNs) / 1_000_000} ms (${list.size} visible)")
        }
    }

    val state: StateFlow<ListUiState> = combine(visible, query, filter, sort, selected) { list, q, f, order, sel ->
        ListUiState(
            loading = list == null,
            conversations = list.orEmpty(),
            query = q,
            filter = f,
            sort = order,
            selected = sel,
            selectionMode = sel.isNotEmpty(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ListUiState(true, emptyList(), "", MessageFilter(), SortOrder.DATE_NEWEST, emptySet(), false),
    )

    init {
        refresh()
    }

    fun setQuery(q: String) { query.value = q }
    fun setSort(s: SortOrder) { sort.value = s }
    fun setFilter(f: MessageFilter) { filter.value = f }
    fun toggleSelected(key: String) = selected.update { if (key in it) it - key else it + key }
    fun clearSelection() { selected.value = emptySet() }

    /**
     * Loads the conversations (re-reading the provider after the first time), then fills kind
     * tags. A new call cancels a running one; a kind fill is dropped if a newer list was published.
     */
    fun refresh() {
        refreshStartNs = System.nanoTime()
        firstStateLogged = false
        val reload = loadedOnce
        loadedOnce = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(dispatcher) {
            try {
                val list = repo.conversations(refresh = reload)
                all.value = list
                val filled = repo.fillLastKinds()
                if (all.value === list) all.value = filled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ListVm", "load failed", e)
                if (all.value == null) all.value = emptyList()
            }
        }
    }

    private fun visibleList(list: List<Conversation>, s: Search, f: MessageFilter, order: SortOrder): List<Conversation> {
        val needle = s.text.lowercase()
        val kept = list.filter { c ->
            (f.startMs == null || c.lastTimestampMs >= f.startMs) &&
                (!f.hasAttachment || c.hasAttachment) &&
                (f.kind == null || c.lastKind == f.kind) &&
                (needle.isEmpty() || c.key in s.bodyKeys || c.matches(needle))
        }
        return when (order) {
            SortOrder.DATE_NEWEST -> kept.sortedByDescending { it.lastTimestampMs }
            SortOrder.DATE_OLDEST -> kept.sortedBy { it.lastTimestampMs }
            SortOrder.NAME_ASC -> kept.sortedBy { it.title.lowercase() }
            SortOrder.NAME_DESC -> kept.sortedByDescending { it.title.lowercase() }
        }
    }

    private fun Conversation.matches(needle: String): Boolean =
        title.lowercase().contains(needle) ||
            participants.any { it.rawAddress.lowercase().contains(needle) } ||
            snippet?.lowercase()?.contains(needle) == true

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val BODY_SEARCH_MIN = 3
    }
}
