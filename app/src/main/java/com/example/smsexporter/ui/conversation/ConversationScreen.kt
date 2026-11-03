package com.example.smsexporter.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smsexporter.SmsExporterApp
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Conversation
import kotlinx.coroutines.launch

/** Drags that begin this close to the start edge are left to the system back gesture. */
private val BACK_EDGE = 24.dp

/**
 * Navigation entry: the conversations [keys] (the list's order) in a horizontal pager, opened at
 * [startIndex]. Each page has its own view model (stored on the nav entry under "conv:<key>"),
 * scoped by the list's [initialFilter]; only the visible page is composed.
 */
@Composable
fun ConversationScreen(keys: List<String>, startIndex: Int, initialFilter: MessageFilter, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SmsExporterApp
    val pager = rememberPagerState(initialPage = startIndex) { keys.size }
    val vmFor: @Composable (Int) -> ConversationViewModel = { page ->
        val key = keys[page]
        viewModel(
            key = "conv:$key",
            factory = viewModelFactory { initializer { ConversationViewModel(app.repository, key, initialFilter) } },
        )
    }
    ConversationPager(pager, pageKey = { keys[it] }, vmFor = vmFor, onBack = onBack)
}

/** Read-only chat view of one conversation (a one-page pager); used by the instrumented test. */
@Composable
fun ConversationScreen(vm: ConversationViewModel, onBack: () -> Unit) {
    val pager = rememberPagerState { 1 }
    ConversationPager(pager, pageKey = { "single" }, vmFor = { vm }, onBack = onBack)
}

/**
 * Scaffold with the current page's top bar (title crossfades on page change; search and export
 * act on the current page) over the pager. A drag starting on a bubble goes to its details reveal,
 * any other drag pages; a drag starting within [BACK_EDGE] of the start edge never pages.
 */
@Composable
private fun ConversationPager(
    pager: PagerState,
    pageKey: (Int) -> Any,
    vmFor: @Composable (Int) -> ConversationViewModel,
    onBack: () -> Unit,
) {
    // PagerState restores its saved page without checking it against the page count (measure clamps
    // it later), so clamp before indexing during composition
    val page = pager.currentPage.coerceIn(0, pager.pageCount - 1)
    val vm = vmFor(page)
    val state by vm.state.collectAsStateWithLifecycle()
    // per page: a page whose search is active shows it again when it becomes current
    var searchOpen by rememberSaveable(pageKey(page)) { mutableStateOf(vm.state.value.filter.query != null) }
    var text by rememberSaveable(pageKey(page)) { mutableStateOf(vm.state.value.filter.query.orEmpty()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val closeSearch = { searchOpen = false; text = ""; vm.setSearch(null) }
    var fromEdge by remember { mutableStateOf(false) }

    BackHandler(enabled = searchOpen) { closeSearch() }

    Scaffold(
        topBar = {
            val c = state.conversation
            ConversationTopBar(
                title = c?.title.orEmpty(),
                subtitle = subtitle(state.total, c, state.filter, state.loadingMore && state.messages.isEmpty()),
                searchOpen = searchOpen,
                query = text,
                onQuery = { text = it; vm.setSearch(it) },
                onOpenSearch = { searchOpen = true },
                onCloseSearch = closeSearch,
                onBack = onBack,
                onExport = { scope.launch { snackbar.showSnackbar("Export comes later") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.padding(padding).pointerInput(Unit) {
                val edge = BACK_EDGE.toPx()
                awaitEachGesture {
                    // observe only: never consumes, so bubbles and the list still see the touch
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fromEdge = down.position.x < edge
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    fromEdge = false
                }
            },
            beyondViewportPageCount = 0,
            userScrollEnabled = !fromEdge,
            key = pageKey,
        ) { page ->
            ConversationPage(vmFor(page))
        }
    }
}

/** "27 messages", or "4 of 27 match" when the filter narrows the conversation. */
private fun subtitle(total: Int, c: Conversation?, filter: MessageFilter, loading: Boolean): String = when {
    c == null || loading -> ""
    filter.isActive -> "$total of ${c.messageCount} match"
    total == 1 -> "1 message"
    else -> "$total messages"
}
