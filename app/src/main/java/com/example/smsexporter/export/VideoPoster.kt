package com.example.smsexporter.export

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.smsexporter.data.model.Attachment

/** Grabs a video attachment's first key frame for the HTML/PDF export ("video as a poster frame plus a note"). */
class VideoPoster(
    private val context: Context,
    private val log: (String) -> Unit = { Log.w("Export", it) },
) {
    /** The first key frame of [a] as a JPEG data URI scaled to [maxEdgePx], or null if it cannot be read. */
    fun frameDataUri(a: Attachment, maxEdgePx: Int = 1600, quality: Int = 85): String? =
        try {
            val frame = MediaMetadataRetriever().use {
                it.setDataSource(context, Uri.parse(a.contentUri))
                it.getFrameAtTime(-1 /* first key frame */, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.let { bmp ->
                try {
                    ImageScaler.bitmapToDataUri(bmp, maxEdgePx, quality)
                } finally {
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            log("video poster ${a.partId} failed: $e")
            null
        }
}
