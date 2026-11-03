package com.example.smsexporter.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.smsexporter.data.model.Direction
import com.example.smsexporter.data.model.Message
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val REVEAL_WIDTH = 96.dp
private val DETAIL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DETAIL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * A leftward drag that starts on [content] slides it up to 96 dp toward the start and shows
 * [details] in the space it leaves on the end side; releasing springs it back. Only a drag whose
 * first horizontal motion is leftward is claimed: a rightward one is left unconsumed so a parent
 * (the conversation pager) can take it. (`Modifier.draggable` cannot do this: once past touch slop
 * it consumes the drag in either direction.) [details] is composed only while revealed, faded in
 * with the offset, and may be taller than [content].
 */
@Composable
fun RevealOnDrag(details: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val maxPx = with(LocalDensity.current) { REVEAL_WIDTH.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val revealing by remember { derivedStateOf { offset.value < 0f } }

    val gesture = Modifier.pointerInput(maxPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var x = offset.value
            val move = { dx: Float ->
                x = (x + dx).coerceIn(-maxPx, 0f)
                val target = x
                scope.launch { offset.snapTo(target) }
            }
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                if (over < 0f || x < 0f) { change.consume(); move(over) }
            } ?: return@awaitEachGesture
            horizontalDrag(start.id) { change -> move(change.positionChange().x); change.consume() }
            scope.launch { offset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
        }
    }

    Layout(
        content = {
            Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
            if (revealing) {
                Box(Modifier.graphicsLayer { alpha = (-offset.value / maxPx).coerceIn(0f, 1f) }) { details() }
            }
        },
        modifier = modifier.then(gesture),
    ) { measurables, constraints ->
        val c = measurables[0].measure(constraints)
        val panelWidth = REVEAL_WIDTH.roundToPx()
        val d = measurables.getOrNull(1)?.measure(Constraints(maxWidth = panelWidth))
        layout(c.width, c.height) {
            c.place(0, 0)
            // just after the bubble's moving end edge, so it fills only the space already vacated
            // (read here, in placement, so dragging re-places without recomposing)
            d?.place(c.width + offset.value.roundToInt(), (c.height - d.height) / 2)
        }
    }
}

/** Exact time (system zone; date and time on two lines to fit the 96 dp strip), kind, direction, raw address and id of [m]. */
@Composable
fun MessageDetails(m: Message) {
    val lines = remember(m) {
        val t = Instant.ofEpochMilli(m.timestampMs).atZone(ZoneId.systemDefault())
        val direction = when (m.direction) {
            Direction.RECEIVED -> "Received"
            Direction.SENT -> "Sent"
            Direction.FAILED -> "Failed"
        }
        listOf(DETAIL_DATE.format(t), DETAIL_TIME.format(t), m.kind.name, direction, m.address, m.id.toString())
    }
    Column(Modifier.padding(start = 8.dp)) {
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
