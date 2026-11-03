package com.example.smsexporter.ui.list

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smsexporter.data.model.Conversation
import com.example.smsexporter.ui.components.Avatar
import com.example.smsexporter.ui.components.KindTag

/**
 * One conversation. Tap → [onClick], long-press → [onLongClick], swipe right past a quarter of
 * the width → [onSwipe] (the row springs back; it is never dismissed).
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION") // confirmValueChange keeps the row from settling in the swiped position
@Composable
fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    photoUri: (Conversation) -> String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: () -> Unit,
) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { false }, positionalThreshold = { it * 0.25f })
    val currentOnSwipe = rememberUpdatedState(onSwipe)
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.targetValue }.collect { if (it == SwipeToDismissBoxValue.StartToEnd) currentOnSwipe.value() }
    }
    // a cache hit: names (and photos) were looked up when the conversation list was built
    val photo = remember(conversation.key) { photoUri(conversation) }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
    ) {
        val surface = MaterialTheme.colorScheme.surface
        val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(surface) else surface
        Row(
            Modifier.fillMaxWidth().background(background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(conversation.title, conversation.key, photo, selected)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    KindTag(conversation.lastKind)
                    Text(
                        relativeTime(conversation.lastTimestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    conversation.snippet.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun relativeTime(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
