package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213
import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.nfc.TagAuthException
import de.stustapay.libssp.nfc.TagLockedException
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ntag213ReadTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private val otherKey = "0f0e0d0c0b0a09080706050403020100".decodeHex()
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())

    private fun tag(): FakeNtag213 = FakeNtag213(uid).also { it.connect() }

    @Test
    fun readTag_provisionedBand_returnsUidAndPin() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678")
        val t = Ntag213(f)
        val r = t.readTag(key0)
        assertEquals("ABCD1234EFGH5678", r.tag.pin)
        assertEquals(false, r.legacy)
        assertEquals("040EA89A332091", r.tag.uid.toString(16).uppercase().padStart(14, '0'))
    }

    @Test
    fun readTag_legacyBand_isAcceptedButFlagged() {
        val f = tag()
        f.pages[4] = "AB12".toByteArray(); f.setPwd(Ntag213Credentials.LEGACY.pwd); f.setPack(Ntag213Credentials.LEGACY.pack); f.setAuth0(4)
        val r = Ntag213(f).readTag(key0)
        assertEquals("AB12", r.tag.pin)
        assertEquals(true, r.legacy)
    }

    @Test
    fun readTag_legacyBand_rejectedWhenTransitionOver() {
        val f = tag()
        f.pages[4] = "AB12".toByteArray(); f.setPwd(Ntag213Credentials.LEGACY.pwd); f.setPack(Ntag213Credentials.LEGACY.pack); f.setAuth0(4)
        assertThrows(TagAuthException::class.java) { Ntag213(f).readTag(key0, acceptLegacy = false) }
    }

    @Test
    fun readTag_wrongKey_throwsAuth() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678")
        assertThrows(TagAuthException::class.java) { Ntag213(f).readTag(otherKey) }
    }

    @Test
    fun readTag_unprovisionedBand_throwsAuth_evenIfReadable() {
        val f = tag() // fresh: PROT=0, alles lesbar — trotzdem keine UID-only-Erkennung
        assertThrows(TagAuthException::class.java) { Ntag213(f).readTag(key0) }
    }

    @Test
    fun readTag_lockedTag_reportsLocked() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678"); f.setAuthLim(1)
        f.negativeAuthCount = 2 // Limit 2^1 erreicht
        assertThrows(TagLockedException::class.java) { Ntag213(f).readTag(key0) }
    }

    @Test
    fun readTag_pinShorterThan16_isTrimmed() {
        val f = tag(); f.provisionNew(key0, "AB12")
        assertEquals("AB12", Ntag213(f).readTag(key0).tag.pin)
    }
}
