package com.example.smsexporter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressNormalizerTest {
    @Test fun indianNumberVariantsCollapse() {
        assertEquals("num:8617482941", AddressNormalizer.normalize("+918617482941"))
        assertEquals("num:8617482941", AddressNormalizer.normalize("08617482941"))
        assertEquals("num:8617482941", AddressNormalizer.normalize("86174 82941"))
    }

    @Test fun senderIdsAreCaseInsensitive() {
        assertEquals("raw:jd-axisbk", AddressNormalizer.normalize("JD-AXISBK"))
        assertEquals("raw:jd-axisbk", AddressNormalizer.normalize("JD-AxisBk "))
    }

    @Test fun shortCodesAndEmailsStayRaw() {
        assertEquals("raw:59039211", AddressNormalizer.normalize("59039211"))
        assertEquals(
            "raw:wecredit_loans_5gzl7qkm_agent@rbm.goog",
            AddressNormalizer.normalize("wecredit_loans_5gzl7qkm_agent@rbm.goog")
        )
    }

    @Test fun keyIsSortedAndDistinct() {
        assertEquals("num:1|raw:b", AddressNormalizer.conversationKey(listOf("raw:b", "num:1", "raw:b")))
    }

    @Test fun emptyCollectionGivesEmptyKey() {
        assertEquals("", AddressNormalizer.conversationKey(emptyList()))
    }
}
