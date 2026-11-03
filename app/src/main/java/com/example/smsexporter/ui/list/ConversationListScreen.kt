package com.example.smsexporter.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smsexporter.SmsExporterApp
import com.example.smsexporter.data.model.Conversation
import kotlinx.coroutines.launch

/** The merged conversation list with search, filter chips, sort and multi-select. */
@Composable
fun ConversationListScreen(onOpen: (key: String) -> Unit) {
    val app = LocalContext.current.applicationContext as SmsExporterApp
    val vm: ConversationListViewModel = viewModel(factory = viewModelFactory { initializer { ConversationListViewModel(app.repository) } })
    val state by vm.state.collectAsStateWithLifecycle()
    // the field owns its text so typing never waits for the view model
    var text by rememberSaveable { mutableStateOf(state.query) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val photoUri = remember(app) {
        { c: Conversation -> c.participants.singleOrNull()?.let { app.contacts.photoUri(it.normalized, it.rawAddress) } }
    }

    BackHandler(enabled = state.selectionMode) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selected.size,
                    onClose = vm::clearSelection,
                    onExport = { scope.launch { snackbar.showSnackbar("Export comes later") } },
                )
            } else {
                SearchTopBar(
                    query = text,
                    onQuery = { text = it; vm.setQuery(it) },
                    sort = state.sort,
                    onSort = vm::setSort,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterChipsRow(state.filter, vm::setFilter)
            when {
                state.loading -> Centered { CircularProgressIndicator() }
                state.conversations.isEmpty() -> Centered {
                    val narrowed = state.query.isNotBlank() || state.filter.isActive
                    Text(
                        if (narrowed) "No conversations match" else "No conversations",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.key }) { c ->
                        val selected = c.key in state.selected
                        ConversationRow(
                            conversation = c,
                            selected = selected,
                            photoUri = photoUri,
                            onClick = { if (state.selectionMode) vm.toggleSelected(c.key) else onOpen(c.key) },
                            onLongClick = { vm.toggleSelected(c.key) },
                            onSwipe = { vm.toggleSelected(c.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}
