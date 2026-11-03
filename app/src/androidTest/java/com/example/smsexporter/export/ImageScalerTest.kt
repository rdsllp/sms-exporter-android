package com.example.smsexporter.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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

    @Test
    fun transparentAreasBecomeWhite() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        for (x in 32 until 64) for (y in 0 until 64) bmp.setPixel(x, y, Color.rgb(200, 80, 40))
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bmp.recycle()

        val uri = ImageScaler.toDataUri(png)!!
        val bytes = Base64.decode(uri.removePrefix("data:image/jpeg;base64,"), Base64.DEFAULT)
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(64, out.width)
        val p = out.getPixel(8, 32)
        out.recycle()
        val rgb = "%08x".format(p)
        assertTrue("transparent half should be white, was $rgb", Color.red(p) >= 0xF0 && Color.green(p) >= 0xF0 && Color.blue(p) >= 0xF0)
    }
}
