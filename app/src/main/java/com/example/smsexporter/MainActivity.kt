package com.example.smsexporter

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smsexporter.spike.Dump
import com.example.smsexporter.ui.theme.SmsExporterTheme
import kotlin.concurrent.thread

// SPIKE: throwaway UI. Will be replaced by the real app.
class MainActivity : ComponentActivity() {
    private var output by mutableStateOf("tap Dump")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { dump() }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { dump() }

    private fun dump() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            output = "no READ_SMS"; return
        }
        output = "dumping..."
        thread { val r = runCatching { Dump.run(this) }.getOrElse { "ERR: $it" }; runOnUiThread { output = r } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsExporterTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding).padding(12.dp).verticalScroll(rememberScrollState())) {
                        Button(onClick = { permLauncher.launch(Manifest.permission.READ_SMS) }) { Text("1. Grant READ_SMS") }
                        Button(onClick = {
                            val rm = getSystemService(RoleManager::class.java)
                            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                        }) { Text("2. Become default SMS app") }
                        Button(onClick = { restoreGoogleMessages() }) { Text("3. Restore Google Messages as default") }
                        Button(onClick = { dump() }) { Text("Dump") }
                        Text(output)
                    }
                }
            }
        }
    }

    // SPIKE: legacy intent that asks the system to make ANOTHER package the default SMS app.
    private fun restoreGoogleMessages() {
        val i = android.content.Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, "com.google.android.apps.messaging")
        roleLauncher.launch(i)
    }

    // SPIKE: toggle our own SMS receiver so we stop/start qualifying for the SMS role.
    private fun setReceiverEnabled(enabled: Boolean) {
        val cn = android.content.ComponentName(this, com.example.smsexporter.spike.SmsDeliverReceiver::class.java)
        packageManager.setComponentEnabledSetting(cn,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        output = "receiver enabled=$enabled"
    }

    override fun onResume() {
        super.onResume()
        when {
            intent?.getBooleanExtra("restore", false) == true -> { intent.removeExtra("restore"); restoreGoogleMessages() }
            intent?.getBooleanExtra("droprole", false) == true -> { intent.removeExtra("droprole"); setReceiverEnabled(false) }
            intent?.getBooleanExtra("enablerole", false) == true -> { intent.removeExtra("enablerole"); setReceiverEnabled(true) }
            else -> dump()
        }
    }
}
