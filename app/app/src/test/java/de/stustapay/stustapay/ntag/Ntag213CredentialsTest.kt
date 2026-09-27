package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.util.asBitVector
import de.stustapay.libssp.util.cmac
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Ntag213CredentialsTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun cmacImplementation_matchesRfc4493() {
        // RFC 4493 Example 2: K=2b7e1516..., M=6bc1bee2...
        val k = "2b7e151628aed2a6abf7158809cf4f3c".decodeHex()
        val m = hex("6bc1bee22e409f96e93d7e117393172a").asBitVector()
        val out = ByteArray(16) { m.cmac(k).gbe(it.toULong()).toByte() }
        assertArrayEquals(hex("070a16b46b4d4144f79bdd9dd04a287c"), out)
    }

    @Test
    fun derive_goldenVector_staffBand() {
        val c = Ntag213Credentials.derive(key0, hex("040EA89A332091"))
        assertArrayEquals(hex("f577bbb3"), c.pwd)
        assertArrayEquals(hex("cb50"), c.pack)
    }

    @Test
    fun derive_goldenVector_secondUid() {
        val c = Ntag213Credentials.derive(key0, hex("04A1B2C3D4E5F6"))
        assertArrayEquals(hex("e6195ac7"), c.pwd)
        assertArrayEquals(hex("f5c4"), c.pack)
    }

    @Test
    fun derive_dependsOnKeyAndUid() {
        val a = Ntag213Credentials.derive(key0, hex("040EA89A332091"))
        val b = Ntag213Credentials.derive("0f0e0d0c0b0a09080706050403020100".decodeHex(), hex("040EA89A332091"))
        val c = Ntag213Credentials.derive(key0, hex("040EA89A332092"))
        assertFalse(a.pwd.contentEquals(b.pwd))
        assertFalse(a.pwd.contentEquals(c.pwd))
    }

    @Test
    fun derive_rejectsBadInput() {
        try { Ntag213Credentials.derive(key0, hex("0102")); assert(false) } catch (e: IllegalArgumentException) {}
        assertEquals(4, Ntag213Credentials.LEGACY.pwd.size)
        assertArrayEquals(hex("00010203"), Ntag213Credentials.LEGACY.pwd)
        assertArrayEquals(hex("0001"), Ntag213Credentials.LEGACY.pack)
    }
}
