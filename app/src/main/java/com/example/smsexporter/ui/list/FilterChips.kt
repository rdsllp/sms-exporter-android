package com.example.smsexporter.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.model.MessageKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CHIP_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** All / Has attachments / Date range / Type chips, each producing a new [MessageFilter]. */
@Composable
fun FilterChipsRow(filter: MessageFilter, onFilter: (MessageFilter) -> Unit) {
    var dateDialog by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    val hasRange = filter.startMs != null || filter.endMs != null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = !filter.isActive, onClick = { onFilter(MessageFilter(query = filter.query)) }, label = { Text("All") })
        FilterChip(
            selected = filter.hasAttachment,
            onClick = { onFilter(filter.copy(hasAttachment = !filter.hasAttachment)) },
            label = { Text("Has attachments") },
        )
        FilterChip(
            selected = hasRange,
            onClick = { dateDialog = true },
            label = { Text(if (hasRange) rangeLabel(filter.startMs, filter.endMs) else "Date range") },
            trailingIcon = if (hasRange) {
                {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Clear date range",
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                            .clickable { onFilter(filter.copy(startMs = null, endMs = null)) },
                    )
                }
            } else null,
        )
        Box {
            FilterChip(
                selected = filter.kind != null,
                onClick = { typeMenu = true },
                label = { Text(filter.kind?.name ?: "Type") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                (listOf<MessageKind?>(null) + MessageKind.entries).forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind?.name ?: "Any") },
                        onClick = { onFilter(filter.copy(kind = kind)); typeMenu = false },
                    )
                }
            }
        }
    }
    if (dateDialog) {
        DateRangeDialog(
            onDismiss = { dateDialog = false },
            onConfirm = { start, end -> onFilter(filter.copy(startMs = start, endMs = end)); dateDialog = false },
        )
    }
}

/** Picks a day range; reports local start of the first day and local end of the last day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(onDismiss: () -> Unit, onConfirm: (Long?, Long?) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val zone = ZoneId.systemDefault()
                    val start = state.selectedStartDateMillis?.let { utcDay(it).atStartOfDay(zone).toInstant().toEpochMilli() }
                    val end = state.selectedEndDateMillis?.let { utcDay(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 }
                    onConfirm(start, end)
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state, Modifier.weight(1f))
    }
}

/** The picker reports days as UTC midnight. */
private fun utcDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

private fun rangeLabel(startMs: Long?, endMs: Long?): String {
    fun day(ms: Long) = CHIP_DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    return when {
        startMs != null && endMs != null -> "${day(startMs)} – ${day(endMs)}"
        startMs != null -> "From ${day(startMs)}"
        else -> "Until ${day(endMs!!)}"
    }
}
