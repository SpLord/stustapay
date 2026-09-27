package de.stustapay.stustapay.ntag

import de.stustapay.libssp.util.KeyParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyParsingTest {
    @Test
    fun parse_acceptsPlainHex() {
        assertEquals(128uL, KeyParsing.parseKey0("000102030405060708090a0b0c0d0e0f")!!.len)
    }

    @Test
    fun parse_acceptsSeparators() {
        assertEquals(
            128uL,
            KeyParsing.parseKey0("00:01:02:03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F")!!.len
        )
    }

    @Test
    fun parse_rejectsWrongLength() {
        assertNull(KeyParsing.parseKey0("0001"))
    }

    @Test
    fun parse_rejectsNonHex() {
        assertNull(KeyParsing.parseKey0("zz0102030405060708090a0b0c0d0e0f"))
    }
}
