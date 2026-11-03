package com.example.smsexporter.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.smsexporter.data.model.Attachment

private val MEDIA_SHAPE = RoundedCornerShape(12.dp)

/**
 * An mms/RCS part: images and videos (first frame plus a play glyph) through Coil, anything else
 * as a "name · size" chip. Tapping opens the system viewer. A part that cannot be loaded shows
 * the chip instead.
 */
@Composable
fun AttachmentView(a: Attachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val open = remember(a, context) { { openInViewer(context, a) } }
    if (!a.isImage && !a.isVideo) {
        FileChip(a, open, modifier)
        return
    }
    Box(modifier.clip(MEDIA_SHAPE).clickable(onClick = open), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = Uri.parse(a.contentUri),
            contentDescription = a.fileName ?: a.mimeType,
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp),
            loading = { Box(Modifier.size(240.dp, 160.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) },
            error = { FileChip(a, open) },
        )
        if (a.isVideo) {
            Icon(Icons.Filled.PlayCircle, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun FileChip(a: Attachment, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(a) {
        val size = a.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
        when {
            a.fileName != null -> listOfNotNull(a.fileName, size).joinToString(" · ")
            else -> listOfNotNull(a.mimeType, size).joinToString(" · ")
        }
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
        modifier = modifier,
    )
}

private fun openInViewer(context: Context, a: Attachment) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(a.contentUri), a.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Log.w("AttachmentView", "cannot open ${a.contentUri}", e)
        Toast.makeText(context, "Cannot open this file", Toast.LENGTH_SHORT).show()
    }
}
