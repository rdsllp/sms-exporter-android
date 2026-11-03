package com.example.smsexporter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private val PERMISSIONS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Shows [content] once READ_SMS is granted. Asks for READ_SMS and READ_CONTACTS together after
 * an explanation; when READ_CONTACTS alone is refused the app continues without names. When
 * READ_SMS is refused the explanation offers the app's system settings page instead.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var smsGranted by remember { mutableStateOf(context.granted(Manifest.permission.READ_SMS)) }
    var contactsGranted by remember { mutableStateOf(context.granted(Manifest.permission.READ_CONTACTS)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        smsGranted = context.granted(Manifest.permission.READ_SMS)
        contactsGranted = context.granted(Manifest.permission.READ_CONTACTS)
    }
    // the user may grant from the settings page and come back
    LifecycleResumeEffect(Unit) {
        smsGranted = context.granted(Manifest.permission.READ_SMS)
        contactsGranted = context.granted(Manifest.permission.READ_CONTACTS)
        onPauseOrDispose { }
    }

    if (smsGranted && (contactsGranted || asked)) {
        content()
        return
    }
    val smsRefused = asked && !smsGranted
    Explanation(
        buttonLabel = if (smsRefused) "Open settings" else "Continue",
        onClick = {
            if (smsRefused) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                launcher.launch(PERMISSIONS)
            }
        },
        note = if (smsRefused) "SMS access was refused. Allow it in the app's settings to continue." else null,
    )
}

@Composable
private fun Explanation(buttonLabel: String, onClick: () -> Unit, note: String?) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "This app reads your SMS, MMS and RCS messages to let you browse and export them. " +
                    "It never sends messages. Contacts are used only to show names.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Button(onClick = onClick) { Text(buttonLabel) }
        }
    }
}
