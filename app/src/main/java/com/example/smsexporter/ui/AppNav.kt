package com.example.smsexporter.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smsexporter.ui.list.ConversationListScreen

/** Routes: `list` and `conversation/{key}` (key URL-encoded). Default transitions, predictive back. */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "list") {
        composable("list") {
            ConversationListScreen(onOpen = { key -> nav.navigate("conversation/${Uri.encode(key)}") })
        }
        composable("conversation/{key}") { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            // placeholder until the conversation screen exists
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.safeDrawingPadding()) { Text("Conversation $key") }
            }
        }
    }
}
