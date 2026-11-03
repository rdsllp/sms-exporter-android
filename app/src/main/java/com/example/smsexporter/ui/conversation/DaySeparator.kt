package com.example.smsexporter.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SAME_YEAR = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private val OTHER_YEAR = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

/** Centred date label between messages of different days: "Today", "Yesterday", "Mon, 3 Nov". */
@Composable
fun DaySeparator(date: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val label = when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> SAME_YEAR.format(date)
        else -> OTHER_YEAR.format(date)
    }
    Box(modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
