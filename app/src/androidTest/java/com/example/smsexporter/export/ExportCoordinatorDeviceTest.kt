package com.example.smsexporter.export

import android.Manifest
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.data.source.TelephonyMessageSource
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportCoordinatorDeviceTest {
    @get:Rule val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS)

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test
    fun cancelLeavesNoPendingFiles() = runBlocking {
        val repo = MessageRepository(TelephonyMessageSource(resolver), NameLookup { _, _ -> null })
        val keys = repo.conversations().take(2).map { it.key }
        assumeTrue("needs at least 2 conversations", keys.size == 2)

        val zipNames = mutableListOf<String>()
        val firstRunning = CompletableDeferred<Unit>()
        val coordinator = ExportCoordinator(
            repo = repo,
            html = HtmlRenderer(""),
            pdf = { _, _ -> awaitCancellation() },
            cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            sinkFactory = { name ->
                val zip = checkNotNull(name) { "two conversations must go to a zip" }
                zipNames += zip
                ZipSink(resolver, zip)
            },
            clock = { ZonedDateTime.now() },
        )
        val seen = mutableListOf<ExportProgress>()
        val job = launch(Dispatchers.Default) {
            coordinator.run(ExportRequest(keys, ExportFormat.PDF, MessageFilter())).collect {
                seen += it
                if (it is ExportProgress.Running) firstRunning.complete(Unit)
            }
        }
        withTimeout(30_000) { firstRunning.await() }
        // wait for the zip row to exist, so the cancel lands while it is pending and pdf is hanging
        withTimeout(30_000) { while (zipNames.isEmpty() || pendingCount() == 0) kotlinx.coroutines.delay(50) }
        job.cancel()
        job.join()

        assertEquals(1, zipNames.size)
        assertTrue(seen.toString(), seen.none { it is ExportProgress.Done || it is ExportProgress.Failed })
        assertEquals(0, pendingCount())
        assertEquals(0, rowsNamed(zipNames.single()))
    }

    private fun pendingCount(): Int = count(
        "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'SmsExporter_%'",
        null,
    )

    private fun rowsNamed(name: String): Int = count("${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name))

    private fun count(where: String, args: Array<String>?): Int {
        val uri = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), where, args, null)?.use { it.count } ?: -1
    }
}
