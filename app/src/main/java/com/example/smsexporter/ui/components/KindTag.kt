package com.example.smsexporter.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smsexporter.data.model.MessageKind

/** Small "SMS"/"MMS"/"RCS" label; nothing while [kind] is unknown. */
@Composable
fun KindTag(kind: MessageKind?, modifier: Modifier = Modifier) {
    if (kind == null) return
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        MessageKind.SMS -> colors.secondaryContainer to colors.onSecondaryContainer
        MessageKind.MMS -> colors.tertiaryContainer to colors.onTertiaryContainer
        MessageKind.RCS -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Surface(modifier, shape = RoundedCornerShape(4.dp), color = container, contentColor = content) {
        Text(kind.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}
