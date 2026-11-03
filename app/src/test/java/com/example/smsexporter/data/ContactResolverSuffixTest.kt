package com.example.smsexporter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactResolverSuffixTest {
    private fun tail(n: String) = PhoneDigits.networkDigits(n).takeLast(7)

    @Test fun digitsAfterAPauseAreDropped() {
        assertEquals("6543210", tail("98765 43210,123"))
        assertEquals("6543210", tail("98765 43210;123"))
        assertEquals("6543210", tail("9876543210p123"))
        assertEquals("6543210", tail("9876543210W123"))
    }

    @Test fun uncutFormKeepsDigitsAfterThePause() {
        assertEquals("3210123", PhoneDigits.networkDigits("98765 43210,123", cutAtPause = false).takeLast(7))
    }

    @Test fun separatorsAndPlusAreDropped() {
        assertEquals("2312345", tail("+91 98123 12345"))
    }

    @Test fun keypadLettersBecomeDigits() {
        assertEquals("18003569377", PhoneDigits.networkDigits("1-800-FLOWERS"))
        assertEquals("3569377", tail("1-800-flowers"))
    }

    @Test fun nonAsciiDigitsBecomeAscii() {
        assertEquals("9876543210", PhoneDigits.networkDigits("৯৮৭৬৫৪৩২১০"))
    }

    @Test fun shortNumberIsKeptWhole() {
        assertEquals("12345", tail("12345"))
    }
}
