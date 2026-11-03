package com.example.smsexporter.ui.diag

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smsexporter.SmsExporterApp
import com.example.smsexporter.data.source.TelephonyMessageSource
import com.example.smsexporter.diag.CrashLog
import com.example.smsexporter.diag.DeviceCheck
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsState(val report: String? = null, val progress: String = "")

/** Runs the device check on creation and on [runAgain]; the run survives rotation and stops when the screen is left. */
class DiagnosticsViewModel(private val check: DeviceCheck) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state
    private var job: Job? = null

    init { runAgain() }

    fun runAgain() {
        job?.cancel()
        _state.value = DiagnosticsState()
        job = viewModelScope.launch {
            val report = check.run { p -> _state.update { it.copy(progress = p) } }   // run() moves to Dispatchers.IO
            Log.i("DeviceCheck", report.text)   // counts, timings and masked metadata only
            _state.update { it.copy(report = report.text) }
        }
    }
}

/** "Device check": the plain-text report with Copy and Share, for sending back from a test phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SmsExporterApp
    val vm: DiagnosticsViewModel = viewModel(
        factory = viewModelFactory {
            // the repository does not expose its source, so the check gets its own (same provider)
            initializer { DiagnosticsViewModel(DeviceCheck(app, TelephonyMessageSource(app.contentResolver), app.repository)) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val report = state.report
    val currentReport by rememberUpdatedState(report)

    // The chooser calls this back only when the user actually picks a target (not on cancel); only
    // then has the report been shared, so only then may the crash log go.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                currentReport?.let { clearCrashLogIfIncluded(c, it) }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SHARE_CHOSEN), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Device check") },
                actions = {
                    IconButton(onClick = vm::runAgain, enabled = report != null) { Icon(Icons.Filled.Refresh, contentDescription = "Run again") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        report ?: return@Button
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Device check", report))
                        clearCrashLogIfIncluded(context, report)
                        scope.launch { snackbar.showSnackbar("Copied") }
                    },
                    enabled = report != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                Button(
                    onClick = {
                        report ?: return@Button
                        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }
                        // package-scoped and immutable: only our own non-exported receiver gets it
                        val chosen = PendingIntent.getBroadcast(
                            context, 0, Intent(ACTION_SHARE_CHOSEN).setPackage(context.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        runCatching { context.startActivity(Intent.createChooser(send, "Share device check", chosen.intentSender)) }
                            .onFailure { scope.launch { snackbar.showSnackbar("Could not share") } }
                    },
                    enabled = report != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (report == null) {
                Text("Running…", style = MaterialTheme.typography.titleMedium)
                Text(state.progress, style = MaterialTheme.typography.bodyMedium)
            } else {
                SelectionContainer {
                    Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private const val ACTION_SHARE_CHOSEN = "com.example.smsexporter.action.DEVICE_CHECK_SHARE_CHOSEN"

/** Clears the crash log only once it has gone out in a copied or shared report (a newer crash is kept). Never throws. */
private fun clearCrashLogIfIncluded(context: Context, report: String) {
    runCatching {
        val crash = CrashLog.read(context) ?: return
        if (report.contains(crash)) CrashLog.clear(context)
    }
}
