package com.example.smsexporter.ui.export

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Progress and result of an export: Running (progress bar, Cancel), Done (path, Share, Close),
 * Failed or Cancelled (message, Close). While running it cannot be swiped, tapped or backed away;
 * only Cancel stops it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportProgressSheet(state: ExportUiState, onCancel: () -> Unit, onClose: () -> Unit) {
    val running by rememberUpdatedState(state is ExportUiState.Running)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !running },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!running) onClose() },
        sheetState = sheetState,
        sheetGesturesEnabled = !running,
    ) {
        // not ModalBottomSheetProperties(shouldDismissOnBackPress): the sheet's dialog reads it only
        // once, so Done would stay un-backable. This callback is newer, so it wins while enabled.
        BackHandler(enabled = running) {}
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                is ExportUiState.Running -> Running(state, onCancel)
                is ExportUiState.Done -> Done(state, onClose)
                is ExportUiState.Failed -> Message("Export failed", state.message, onClose)
                ExportUiState.Cancelled -> Message("Export cancelled", null, onClose)
                else -> Unit
            }
        }
    }
}

@Composable
private fun Running(state: ExportUiState.Running, onCancel: () -> Unit) {
    val p = state.progress
    Text("Exporting", style = MaterialTheme.typography.titleLarge)
    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
    Text(
        if (p.title.isEmpty()) "Preparing…" else "Exporting ${p.title} (${p.index + 1} of ${p.total})",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun Done(state: ExportUiState.Done, onClose: () -> Unit) {
    val context = LocalContext.current
    Text("Export complete", style = MaterialTheme.typography.titleLarge)
    Text("Saved to ${state.displayPath}", style = MaterialTheme.typography.bodyMedium)
    if (state.failed > 0) {
        val n = if (state.failed == 1) "1 conversation" else "${state.failed} conversations"
        Text(
            "$n could not be exported — see EXPORT_FAILED.txt",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType(state.mime)
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(state.uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, "Share export")) }
                    .onFailure { Toast.makeText(context, "Could not share", Toast.LENGTH_SHORT).show() }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Share") }
    }
}

@Composable
private fun Message(title: String, detail: String?, onClose: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
}

/**
 * Renders the sheet for [vm]'s state: the chooser while choosing, the progress sheet after. When
 * the sheet of a completed export is closed, [onDoneClosed] runs before the view model goes idle.
 */
@Composable
fun ExportSheets(vm: ExportViewModel, onDoneClosed: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ExportUiState.Idle -> Unit
        is ExportUiState.Choosing -> ExportSheet(s, onExport = vm::start, onDismiss = vm::dismiss)
        else -> ExportProgressSheet(
            s,
            onCancel = vm::cancel,
            onClose = {
                if (s is ExportUiState.Done) onDoneClosed()
                vm.dismiss()
            },
        )
    }
}
