package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213
import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ntag213ProvisionTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private val otherKey = "0f0e0d0c0b0a09080706050403020100".decodeHex()
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())
    private val creds = Ntag213Credentials.derive(key0, uid)
    private fun tag() = FakeNtag213(uid).also { it.connect() }

    private fun assertProvisioned(f: FakeNtag213, pin: String) {
        assertArrayEquals(creds.pwd, f.pwd()); assertArrayEquals(creds.pack, f.pack())
        assertEquals(4, f.auth0()); assertTrue(f.prot()); assertEquals(3, f.authLim())
        assertEquals((f.pages[42][0].toInt() and 0x40), 0) // CFGLCK nie gesetzt
        assertEquals(pin, Ntag213(f).readTag(key0).tag.pin)
    }

    @Test
    fun provision_freshTag() {
        val f = tag(); Ntag213(f).provisionTag("ABCD1234EFGH5678", key0); assertProvisioned(f, "ABCD1234EFGH5678")
    }

    @Test
    fun provision_legacyTag_keepsPin() {
        val f = tag()
        // Legacy-Zustand: PIN "AB12" (kürzer, Nullbytes), PWD 00010203, PACK 0001, AUTH0=4, PROT=0
        f.pages[4] = "AB12".toByteArray(); f.setPwd(Ntag213Credentials.LEGACY.pwd); f.setPack(Ntag213Credentials.LEGACY.pack); f.setAuth0(4)
        Ntag213(f).provisionTag("AB12", key0)
        assertProvisioned(f, "AB12")
    }

    @Test
    fun provision_isIdempotentOnAlreadyNewTag() {
        val f = tag(); Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
        f.close(); f.connect()
        Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
        assertProvisioned(f, "ABCD1234EFGH5678")
    }

    @Test
    fun provision_resumesFromEveryIntermediateState() {
        // Simuliert Abbruch nach jedem Schreibschritt: danach muss ein zweiter Lauf sauber enden.
        for (failAfter in 1..8) {
            val f = tag()
            val flaky = FlakyTransport(f, failAfterWrites = failAfter)
            try { Ntag213(flaky).provisionTag("ABCD1234EFGH5678", key0) } catch (e: java.io.IOException) {}
            f.close(); f.connect()
            Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
            assertProvisioned(f, "ABCD1234EFGH5678")
        }
    }

    @Test
    fun readStatus_reportsProtection() {
        val f = tag(); Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
        val s = Ntag213(f).readStatus(key0)
        assertEquals(4, s.auth0); assertTrue(s.prot); assertEquals(3, s.authLim)
    }

    @Test
    fun writeTag_rewritesPinOnProvisionedBand() {
        val f = tag(); Ntag213(f).provisionTag("OLDPIN", key0)
        Ntag213(f).writeTag("NEWPIN99", key0)
        assertEquals("NEWPIN99", Ntag213(f).readTag(key0).tag.pin)
        assertFalse(f.pinBytes().contentEquals(ByteArray(16)))
    }

    @Test
    fun provision_foreignBand_writesNothing() {
        // Band provisioned for a completely different event key0 (different PWD/PACK than ours).
        val f = tag(); f.provisionNew(otherKey, "XYZ")
        val pinBefore = f.pinBytes(); val pwdBefore = f.pwd(); val packBefore = f.pack()

        assertThrows(java.io.IOException::class.java) { Ntag213(f).provisionTag("SOMEPIN", key0) }

        assertArrayEquals(pinBefore, f.pinBytes())
        assertArrayEquals(pwdBefore, f.pwd())
        assertArrayEquals(packBefore, f.pack())
    }
}

/** Wraps a FakeNtag213 and throws IOException on the N-th WRITE (tag pulled away). */
class FlakyTransport(private val inner: FakeNtag213, private val failAfterWrites: Int) : de.stustapay.libssp.nfc.Ntag213Transport {
    private var writes = 0
    override val isConnected get() = inner.isConnected
    override fun connect() = inner.connect()
    override fun close() = inner.close()
    override fun transceive(cmd: ByteArray): ByteArray {
        if ((cmd[0].toInt() and 0xFF) == 0xA2) { writes++; if (writes == failAfterWrites) throw java.io.IOException("tag lost") }
        return inner.transceive(cmd)
    }
}
