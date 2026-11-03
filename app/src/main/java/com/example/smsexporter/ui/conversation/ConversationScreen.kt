package com.example.smsexporter.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smsexporter.SmsExporterApp
import com.example.smsexporter.data.AddressNormalizer
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Navigation entry: builds the view model for [key], scoped by the list's [initialFilter]. */
@Composable
fun ConversationScreen(key: String, initialFilter: MessageFilter, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SmsExporterApp
    val vm: ConversationViewModel = viewModel(
        factory = viewModelFactory { initializer { ConversationViewModel(app.repository, key, initialFilter) } },
    )
    ConversationScreen(vm, onBack)
}

/** Read-only chat view of one conversation, newest message at the bottom. */
@Composable
fun ConversationScreen(vm: ConversationViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(state.filter.query.orEmpty()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val closeSearch = { searchOpen = false; text = ""; vm.setSearch(null) }

    BackHandler(enabled = searchOpen) { closeSearch() }

    val conversation = state.conversation
    val rows = remember(state.messages) { dayRows(state.messages) }
    val senders = remember(conversation) { conversation?.let(::senderNames).orEmpty() }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 20
        }
    }
    LaunchedEffect(nearEnd, rows.size) { if (nearEnd) vm.loadMore() }

    Scaffold(
        topBar = {
            ConversationTopBar(
                title = conversation?.title.orEmpty(),
                subtitle = subtitle(state.total, conversation, state.filter, state.loadingMore && state.messages.isEmpty()),
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.messages.isEmpty() && state.loadingMore ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.messages.isEmpty() -> Text(
                    when {
                        conversation == null -> "Conversation not found"
                        state.filter.isActive -> "No messages match"
                        else -> "No messages"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true) {
                    items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                        when (row) {
                            is DayRow -> DaySeparator(row.date)
                            is MessageRow -> MessageBubble(
                                m = row.message,
                                reactions = state.reactionsByTarget[row.message.id],
                                sender = if (conversation?.isGroup == true && row.message.direction == Direction.RECEIVED) {
                                    senders[AddressNormalizer.normalize(row.message.address)] ?: row.message.address
                                } else null,
                            )
                        }
                    }
                    if (state.loadingMore) {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    }
                }
            }
        }
    }
}

/** One LazyColumn item: a message or the date label above a day's messages. */
private sealed interface Row { val key: String; val contentType: String }
private class MessageRow(val message: Message) : Row {
    override val key = message.id.toString()
    override val contentType get() = "message"
}
private class DayRow(val date: LocalDate) : Row {
    override val key = "day:$date"
    override val contentType get() = "day"
}

/**
 * Items for the reversed list from newest-first [messages]: each day's messages followed by the
 * day's label (which shows above them). The label after the oldest loaded message moves up as
 * older pages arrive.
 */
private fun dayRows(messages: List<Message>): List<Row> {
    val zone = ZoneId.systemDefault()
    val out = ArrayList<Row>(messages.size + 16)
    var current: LocalDate? = null
    for (m in messages) {
        val date = Instant.ofEpochMilli(m.timestampMs).atZone(zone).toLocalDate()
        if (current != null && date != current) out += DayRow(current)
        current = date
        out += MessageRow(m)
    }
    current?.let { out += DayRow(it) }
    return out
}

/** Participant label by normalized address. */
private fun senderNames(c: Conversation): Map<String, String> = c.participants.associate { it.normalized to it.label }

/** "27 messages", or "4 of 27 match" when the filter narrows the conversation. */
private fun subtitle(total: Int, c: Conversation?, filter: MessageFilter, loading: Boolean): String = when {
    c == null || loading -> ""
    filter.isActive -> "$total of ${c.messageCount} match"
    total == 1 -> "1 message"
    else -> "$total messages"
}
