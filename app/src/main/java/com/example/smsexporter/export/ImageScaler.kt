package com.example.smsexporter.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ImageScaler {
    /**
     * Downscales to [maxEdgePx] on the long edge and returns a JPEG data URI (EXIF orientation
     * applied, transparency on white); null if undecodable.
     */
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
        val oriented = orient(decoded, orientationOf(bytes))
        try {
            return scaledJpeg(oriented, maxEdgePx, quality)
        } finally {
            oriented.recycle()
        }
    }

    /** The EXIF orientation tag of [bytes], or normal if there is none or it is unreadable. */
    private fun orientationOf(bytes: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    /** [src] turned upright for EXIF [orientation]; recycles [src] when a new bitmap is made. */
    private fun orient(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Downscales [src] to [maxEdgePx] on the long edge and returns a JPEG data URI. Does not recycle [src]. */
    private fun scaledJpeg(src: Bitmap, maxEdgePx: Int, quality: Int): String {
        var scaled: Bitmap = src
        try {
            val edge = maxOf(src.width, src.height)
            if (edge > maxEdgePx) {
                val f = maxEdgePx.toFloat() / edge
                scaled = Bitmap.createScaledBitmap(
                    src,
                    (src.width * f).toInt().coerceAtLeast(1),
                    (src.height * f).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val out = ByteArrayOutputStream()
            if (scaled.hasAlpha()) {
                // JPEG has no alpha: transparent pixels would turn black, so flatten onto white first
                val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                    opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
                } finally {
                    opaque.recycle()
                }
            } else {
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }
}
