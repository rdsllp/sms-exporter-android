package com.example.smsexporter.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smsexporter.SmsExporterApp
import com.example.smsexporter.data.forConversation
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
            // navigation already URL-decodes path arguments (NavDeepLink.getMatchingPathArguments)
            val key = entry.arguments?.getString("key").orEmpty()
            // the list's date range and attachment filter when this conversation was opened; not
            // its query or Type chip (the chip matches only each conversation's last message)
            val filter = remember(entry) { app.listFilter.value.forConversation() }
            // the list's order when this conversation was opened, for swiping between conversations;
            // just this one when there is no list order (deep link) or it lacks the key. Saved, so a
            // restore after process death (when listOrder is empty again) gets the same pages back.
            val keys: ArrayList<String> = rememberSaveable(entry) {
                ArrayList(app.listOrder.value.takeIf { key in it } ?: listOf(key))
            }
            val start = rememberSaveable(entry) { keys.indexOf(key) } // present: see the fallback above
            ConversationScreen(keys = keys, startIndex = start, initialFilter = filter, onBack = { nav.popBackStack() })
        }
    }
}
