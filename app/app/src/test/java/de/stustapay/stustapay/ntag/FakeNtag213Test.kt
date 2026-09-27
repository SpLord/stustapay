package de.stustapay.stustapay.ntag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FakeNtag213Test {
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())

    @Test
    fun readPage0_containsUidAndBcc() {
        val t = FakeNtag213(uid)
        val r = t.transceive(byteArrayOf(0x30, 0x00))
        assertEquals(16, r.size)
        assertArrayEquals(byteArrayOf(0x04, 0x0E, 0xA8.toByte()), r.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(0x9A.toByte(), 0x33, 0x20, 0x91.toByte()), r.copyOfRange(4, 8))
    }

    @Test
    fun getVersion_isNtag213() {
        val t = FakeNtag213(uid)
        assertArrayEquals(byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03), t.transceive(byteArrayOf(0x60)))
    }

    @Test
    fun freshTag_readsUserMemoryWithoutAuth() {
        val t = FakeNtag213(uid)
        val r = t.transceive(byteArrayOf(0x30, 0x04))
        assertEquals(16, r.size)
    }

    @Test
    fun protectedTag_readOfUserMemoryNeedsAuth() {
        val t = FakeNtag213(uid)
        t.setPwd(byteArrayOf(1, 2, 3, 4)); t.setPack(byteArrayOf(9, 9)); t.setAuth0(4); t.setProt(true)
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x30, 0x04)) }
        val pack = t.transceive(byteArrayOf(0x1B, 1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(9, 9), pack)
        assertEquals(16, t.transceive(byteArrayOf(0x30, 0x04)).size)
    }

    @Test
    fun wrongPassword_countsAndLocksAtLimit() {
        val t = FakeNtag213(uid)
        t.setPwd(byteArrayOf(1, 2, 3, 4)); t.setPack(byteArrayOf(9, 9)); t.setAuth0(4); t.setProt(true); t.setAuthLim(1) // 2^1 = 2 Versuche
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x1B, 0, 0, 0, 0)) }
        t.connect() // real hardware HALTs after a failed PWD_AUTH — re-activate before the next attempt
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x1B, 0, 0, 0, 0)) }
        t.connect()
        // ab jetzt auch mit richtigem Passwort gesperrt
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x1B, 1, 2, 3, 4)) }
    }

    @Test
    fun writeAbovAuth0_needsAuth_belowDoesNot() {
        val t = FakeNtag213(uid)
        t.setPwd(byteArrayOf(1, 2, 3, 4)); t.setPack(byteArrayOf(9, 9)); t.setAuth0(4)
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0xA2.toByte(), 0x05, 1, 1, 1, 1)) }
        t.transceive(byteArrayOf(0x1B, 1, 2, 3, 4))
        t.transceive(byteArrayOf(0xA2.toByte(), 0x05, 1, 1, 1, 1))
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), t.pages[5])
    }
}
