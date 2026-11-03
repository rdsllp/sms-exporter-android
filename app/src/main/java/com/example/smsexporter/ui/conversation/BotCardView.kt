package com.example.smsexporter.ui.conversation

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.smsexporter.data.model.BotCard

/** An RCS business rich card: image, title, description and (read-only) suggestion chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotCardView(card: BotCard, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.width(240.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            card.imagePartId?.let { id ->
                AsyncImage(
                    model = Uri.parse("content://mms/part/$id"),
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                )
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                card.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (card.suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        card.suggestions.forEach { s ->
                            AssistChip(onClick = {}, enabled = false, label = { Text(s, maxLines = 1) })
                        }
                    }
                }
            }
        }
    }
}
