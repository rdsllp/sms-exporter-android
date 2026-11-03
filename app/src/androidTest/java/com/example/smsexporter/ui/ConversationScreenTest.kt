package com.example.smsexporter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smsexporter.data.MessageFilter
import com.example.smsexporter.data.MessageRepository
import com.example.smsexporter.data.NameLookup
import com.example.smsexporter.data.source.FakeMessageSource
import com.example.smsexporter.data.source.RawAddr
import com.example.smsexporter.data.source.RawMms
import com.example.smsexporter.data.source.RawPart
import com.example.smsexporter.ui.conversation.ConversationScreen
import com.example.smsexporter.ui.conversation.ConversationViewModel
import com.example.smsexporter.ui.theme.SmsExporterTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingAttachmentDoesNotCrash() {
        // one received mms whose image part has no bytes and no provider row behind its content URI
        val partId = 999_999_901L
        val source = FakeMessageSource(
            sms = emptyList(),
            mms = listOf(RawMms(1, 1, 1, 1_700_000_000_000, "T1", "application/vnd.wap.multipart.related", false)),
            parts = listOf(RawPart(partId, 1, "image/jpeg", "a.jpg", null, null)),
            addrs = listOf(RawAddr(1, "+911234567890", 137)),
        )
        val repo = MessageRepository(source, NameLookup { _, _ -> null })
        val key = runBlocking { repo.conversations().single().key }
        val vm = ConversationViewModel(repo, key, MessageFilter())

        compose.setContent { SmsExporterTheme { ConversationScreen(vm, onBack = {}) } }

        // the image request fails, so the error slot shows the file chip
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("a.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("a.jpg").assertIsDisplayed()
        compose.onNodeWithText("1 message").assertIsDisplayed()
    }
}
