package com.example.smsexporter.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageScalerTest {
    private fun sampleJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(1536, 2048, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(200, 80, 40))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun largeImageIsScaledToMaxEdge() {
        val uri = ImageScaler.toDataUri(sampleJpeg())!!
        val prefix = "data:image/jpeg;base64,"
        assertTrue(uri.startsWith(prefix))
        val bytes = Base64.decode(uri.removePrefix(prefix), Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertTrue(maxOf(bmp.width, bmp.height) <= 1600)
        assertTrue(minOf(bmp.width, bmp.height) > 0)
        bmp.recycle()
    }

    @Test
    fun undecodableInputGivesNull() {
        assertNull(ImageScaler.toDataUri(byteArrayOf(1, 2, 3)))
    }
}
