package com.example.smsexporter.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smsexporter.SmsExporterApp
import com.example.smsexporter.ui.conversation.ConversationScreen
import com.example.smsexporter.ui.list.ConversationListScreen

/** Routes: `list` and `conversation/{key}` (key URL-encoded). Default transitions, predictive back. */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as SmsExporterApp
    NavHost(nav, startDestination = "list") {
        composable("list") {
            ConversationListScreen(onOpen = { key -> nav.navigate("conversation/${Uri.encode(key)}") })
        }
        composable("conversation/{key}") { entry ->
            val key = Uri.decode(entry.arguments?.getString("key").orEmpty())
            // the list's filter (date range, attachments, kind) when this conversation was opened
            val filter = remember(entry) { app.listFilter.value.copy(query = null) }
            // the list's order when this conversation was opened, for swiping between conversations;
            // just this one when there is no list order (deep link, process death) or it lacks the key
            val keys = remember(entry) { app.listOrder.value.takeIf { key in it } ?: listOf(key) }
            val start = remember(entry) { keys.indexOf(key).coerceAtLeast(0) }
            ConversationScreen(keys = keys, startIndex = start, initialFilter = filter, onBack = { nav.popBackStack() })
        }
    }
}
