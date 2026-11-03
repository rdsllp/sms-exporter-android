package com.example.smsexporter.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import com.example.smsexporter.data.model.Reaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
// 18 dp corners; the bottom corner on the bubble's own side (the "tail") is 4 dp
private val RECEIVED_SHAPE = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
private val SENT_SHAPE = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
private val PILL = RoundedCornerShape(50)

/**
 * One message: received on the start side (surfaceVariant), sent/failed on the end side (primary).
 * A message that is itself a reaction renders as a small emoji bubble. [reactions] are badges of
 * received reactions targeting this message; [sender] is shown above received bubbles in groups.
 */
@Composable
fun MessageBubble(m: Message, reactions: List<Reaction>?, sender: String?, modifier: Modifier = Modifier) {
    val received = m.direction == Direction.RECEIVED
    val colors = MaterialTheme.colorScheme
    val meta = remember(m.kind, m.timestampMs) {
        "${m.kind.name} · ${TIME.format(Instant.ofEpochMilli(m.timestampMs).atZone(ZoneId.systemDefault()))}"
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (received) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = if (received) Alignment.Start else Alignment.End,
        ) {
            if (received && sender != null) {
                Text(sender, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
            }
            Box(Modifier.padding(bottom = if (reactions.isNullOrEmpty()) 0.dp else 16.dp)) {
                Surface(
                    shape = if (received) RECEIVED_SHAPE else SENT_SHAPE,
                    color = if (received) colors.surfaceVariant else colors.primary,
                    contentColor = if (received) colors.onSurfaceVariant else colors.onPrimary,
                    border = if (m.direction == Direction.FAILED) BorderStroke(1.5.dp, colors.error) else null,
                ) {
                    val reaction = m.reaction
                    if (reaction != null) ReactionContent(reaction) else MessageContent(m)
                }
                if (!reactions.isNullOrEmpty()) {
                    ReactionBadge(
                        reactions,
                        Modifier.align(if (received) Alignment.BottomEnd else Alignment.BottomStart)
                            .offset(x = if (received) 14.dp else (-14).dp, y = 14.dp),
                    )
                }
            }
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.direction == Direction.FAILED) {
                    Text("Not sent", style = MaterialTheme.typography.labelSmall, color = colors.error)
                }
                Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MessageContent(m: Message) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.botCard?.let { BotCardView(it) }
        if (m.botCard == null) {
            m.attachments.forEach { AttachmentView(it) }
        }
        m.body?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** Just the emoji; a received reaction also names the text it reacted to. */
@Composable
private fun ReactionContent(r: Reaction) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(r.emoji, fontSize = 22.sp)
        r.quotedText?.let { Text("to \"$it\"", style = MaterialTheme.typography.labelSmall) }
    }
}

/** Up to three emojis in a small pill. */
@Composable
private fun ReactionBadge(reactions: List<Reaction>, modifier: Modifier = Modifier) {
    val text = remember(reactions) { reactions.take(3).joinToString("") { it.emoji } }
    Surface(
        modifier,
        shape = PILL,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
