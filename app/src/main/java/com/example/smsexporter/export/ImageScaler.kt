package com.example.smsexporter.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageScaler {
    /** Downscales to [maxEdgePx] on the long edge and returns a JPEG data URI; null if undecodable. */
    fun toDataUri(bytes: ByteArray, maxEdgePx: Int = 1600, quality: Int = 85): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdgePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        var scaled: Bitmap = decoded
        try {
            val edge = maxOf(decoded.width, decoded.height)
            if (edge > maxEdgePx) {
                val f = maxEdgePx.toFloat() / edge
                scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * f).toInt().coerceAtLeast(1),
                    (decoded.height * f).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
