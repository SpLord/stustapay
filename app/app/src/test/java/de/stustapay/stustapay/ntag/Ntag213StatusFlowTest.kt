package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213
import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * chip_debug "verify" screen: readTag() then readStatus() on the same connection (no
 * reconnect in between) must report the band's actual protection state.
 */
class Ntag213StatusFlowTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())

    private fun tag(): FakeNtag213 = FakeNtag213(uid).also { it.connect() }

    @Test
    fun readTagThenReadStatus_provisionedBand_reportsCurrentProtection() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678")
        val t = Ntag213(f)

        val r = t.readTag(key0)
        val s = t.readStatus(key0)

        assertEquals("ABCD1234EFGH5678", r.tag.pin)
        assertEquals(false, r.legacy)
        assertEquals(4, s.auth0)
        assertEquals(true, s.prot)
        assertEquals(3, s.authLim)
    }

    @Test
    fun readTagThenReadStatus_legacyBand_flagsLegacyAndUnprotected() {
        val f = tag()
        f.pages[4] = "AB12".toByteArray()
        f.setPwd(Ntag213Credentials.LEGACY.pwd)
        f.setPack(Ntag213Credentials.LEGACY.pack)
        f.setAuth0(4)
        val t = Ntag213(f)

        val r = t.readTag(key0)
        val s = t.readStatus(key0)

        assertEquals("AB12", r.tag.pin)
        assertEquals(true, r.legacy)
        assertEquals(false, s.prot)
    }
}
