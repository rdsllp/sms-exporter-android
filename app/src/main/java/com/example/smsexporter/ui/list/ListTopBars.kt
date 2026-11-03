package com.example.smsexporter.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SORT_LABELS = listOf(
    SortOrder.DATE_NEWEST to "Date (newest)",
    SortOrder.DATE_OLDEST to "Date (oldest)",
    SortOrder.NAME_ASC to "Name (A–Z)",
    SortOrder.NAME_DESC to "Name (Z–A)",
)

/** Search field with a clear button, and the sort menu. */
@Composable
fun SearchTopBar(query: String, onQuery: (String) -> Unit, sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SORT_LABELS.forEach { (order, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onSort(order); menuOpen = false },
                            trailingIcon = { if (order == sort) Icon(Icons.Filled.Check, contentDescription = "Current") },
                        )
                    }
                }
            }
        }
    }
}

/** Shown while rows are selected: close (clears the selection), count, export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, onExport: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") } },
        title = { Text("$count selected") },
        actions = { IconButton(onClick = onExport) { Icon(Icons.Filled.IosShare, contentDescription = "Export") } },
    )
}
