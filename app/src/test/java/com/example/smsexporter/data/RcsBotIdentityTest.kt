package com.example.smsexporter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcsBotIdentityTest {
    @Test fun parsesRealTrId() {
        assertEquals("JioPC Offers", RcsBotIdentity.agentName(SAMPLE_TR_ID))
    }

    @Test fun rejectsNonProto() {
        listOf(null, "T6a892fe1", "proto:", "proto:!!!", "proto:AAAA").forEach {
            assertNull("input $it", RcsBotIdentity.agentName(it))
        }
    }

    @Test fun isOpaqueMatchesBotBlobs() {
        assertTrue(RcsBotIdentity.isOpaque("njuw64ddl5xwmztfojzv6oddm55gsolrnnpwcz3f@bot.rcs.google.com"))
        assertTrue(RcsBotIdentity.isOpaque("mjqwg5dfmq2tcmrzgi4tmmbq====@bot.rcs.google.com"))
        assertTrue(RcsBotIdentity.isOpaque("NJUW64DDL5XWMZTFOJZV6ODDM55GSOLR@BOT.RCS.GOOGLE.COM"))
        assertFalse(RcsBotIdentity.isOpaque("+919800997986"))
        assertFalse(RcsBotIdentity.isOpaque("jiopc_offers_8cgzi9qk_agent@rbm.goog"))
        assertFalse(RcsBotIdentity.isOpaque("reliancejewelsios1@botplatform.sarcs.jio.com"))
        assertFalse(RcsBotIdentity.isOpaque("short@bot.rcs.google.com"))
    }

    companion object {
        const val SAMPLE_TR_ID = "proto:CrkBCiJjb20uZ29vZ2xlLmFuZHJvaWQuYXBwcy5tZXNzYWdpbmcuEpIBEmwaPQokamlvcGNfb2ZmZXJzXzhjZ3ppOXFrX2FnZW50QHJibS5nb29nEgxKaW9QQyBPZmZlcnMaByM2RDE3Q0UqK2Q3ZjM3MGNiLWU3NjYtNDkxMy1hNTg5LTdlMmFmZTJlYTMwYiQkbXIuUi4qEET3wI6cL0EcrgIT21O8KakyEOsE4ahZNk+AiAmDkBpSLAI="
    }
}
