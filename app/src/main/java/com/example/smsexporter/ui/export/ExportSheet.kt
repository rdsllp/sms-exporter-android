package com.example.smsexporter.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.export.ExportFormat

/** The format chooser: title, [ExportUiState.Choosing.scopeLine], PDF/HTML/CSV/JSON, Export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(state: ExportUiState.Choosing, onExport: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            Text(
                state.scopeLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            val formats = ExportFormat.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                formats.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = f == format,
                        onClick = { format = f },
                        shape = SegmentedButtonDefaults.itemShape(i, formats.size),
                    ) { Text(f.name) }
                }
            }
            Text(
                outputHint(format, state.keys.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = { onExport(format) }, modifier = Modifier.fillMaxWidth()) { Text("Export") }
        }
    }
}

/** What the export will produce, as the coordinator decides it. */
private fun outputHint(format: ExportFormat, count: Int): String = when {
    format == ExportFormat.CSV || format == ExportFormat.JSON -> "ZIP with original attachments"
    count > 1 -> "ZIP with one ${format.name} per conversation"
    else -> "Single ${format.name} file"
}

/** "3 conversations · 1 Jan 2026 – 31 Oct 2026", or "· all messages" when [filter] is inactive. */
fun listScopeLine(count: Int, filter: MessageFilter): String {
    val n = if (count == 1) "1 conversation" else "$count conversations"
    return "$n · " + if (filter.isActive) filter.describe() else "all messages"
}

/** "4 of 27 messages · \"axis\"" when [filter] narrows the chat, else "27 messages". */
fun chatScopeLine(total: Int, messageCount: Int, filter: MessageFilter): String = when {
    filter.isActive -> "$total of $messageCount messages · ${filter.describe()}"
    messageCount == 1 -> "1 message"
    else -> "$messageCount messages"
}
