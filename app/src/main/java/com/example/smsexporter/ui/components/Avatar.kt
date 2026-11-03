package com.example.smsexporter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private val PALETTE = listOf(
    Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFF43A047), Color(0xFFD81B60), Color(0xFF1E88E5), Color(0xFF6D4C41),
)

/**
 * Round avatar: a check mark when [selected], else the contact photo at [photoUri] when there is
 * one, else the first letter of [title] on a colour derived from [colorSeed].
 */
@Composable
fun Avatar(title: String, colorSeed: String, photoUri: String?, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val shape = Modifier.size(size).clip(CircleShape)
    when {
        selected -> Box(modifier.then(shape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
        }
        photoUri != null -> AsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.then(shape),
        )
        else -> Box(
            modifier.then(shape).background(PALETTE[Math.floorMod(colorSeed.hashCode(), PALETTE.size)]),
            contentAlignment = Alignment.Center,
        ) {
            val initial = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
            Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
    }
}
