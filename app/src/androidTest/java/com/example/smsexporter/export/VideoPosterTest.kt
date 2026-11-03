package com.example.smsexporter.export

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.smsexporter.data.model.Attachment
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPosterTest {
    @get:Rule val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstFrameFromRealVideoPart() {
        val part = context.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "ct"), "ct LIKE 'video/%'", null, "_id",
        )?.use { c -> if (c.moveToFirst()) Attachment(c.getLong(0), c.getString(1), null, null) else null }
        assumeTrue("needs a video mms part on the device", part != null)

        val logs = mutableListOf<String>()
        val uri = VideoPoster(context) { logs += it }.frameDataUri(part!!)
        assertTrue("no poster for part ${part.partId}: $logs", uri != null)
        val prefix = "data:image/jpeg;base64,"
        assertTrue(uri!!.startsWith(prefix))
        val bytes = Base64.decode(uri.removePrefix(prefix), Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertTrue("${bmp.width}x${bmp.height}", maxOf(bmp.width, bmp.height) <= 1600 && minOf(bmp.width, bmp.height) > 0)
        bmp.recycle()
    }

    @Test
    fun garbageUriGivesNull() {
        val logs = mutableListOf<String>()
        assertNull(VideoPoster(context) { logs += it }.frameDataUri(Attachment(partId = -1, mimeType = "video/mp4", null, null)))
        assertTrue("failure is logged", logs.isNotEmpty())
    }
}
