package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213Transport
import java.io.IOException

/**
 * In-memory NTAG213 (NXP datasheet rev 3.2): 45 pages x 4 bytes, PWD_AUTH with PACK,
 * AUTH0 (CFG0 byte 3), PROT (CFG1 byte 0 bit 7), AUTHLIM (CFG1 byte 0 bits 0-2, limit = 2^AUTHLIM).
 *
 * Starts already connected (isConnected = true): tests exercise transceive() directly,
 * without going through the Ntag213/TagTechnology connect() lifecycle.
 */
class FakeNtag213(uid: ByteArray) : Ntag213Transport {
    val pages: Array<ByteArray> = Array(45) { ByteArray(4) }
    var authenticated = false
    var negativeAuthCount = 0
    override var isConnected = true

    init {
        require(uid.size == 7)
        pages[0] = byteArrayOf(uid[0], uid[1], uid[2], (0x88 xor uid[0].toInt() xor uid[1].toInt() xor uid[2].toInt()).toByte())
        pages[1] = byteArrayOf(uid[3], uid[4], uid[5], uid[6])
        pages[3] = byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)      // capability container
        pages[41] = byteArrayOf(0x04, 0x00, 0x00, 0xFF.toByte())     // CFG0: AUTH0 = 0xFF (off)
        pages[42] = byteArrayOf(0x00, 0x05, 0x00, 0x00)              // CFG1: PROT=0, AUTHLIM=0
        pages[43] = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) // PWD default
        pages[44] = byteArrayOf(0x00, 0x00, 0x00, 0x00)              // PACK default
    }

    fun setPwd(p: ByteArray) { pages[43] = p.copyOf() }
    fun setPack(p: ByteArray) { pages[44] = byteArrayOf(p[0], p[1], 0, 0) }
    fun setAuth0(page: Int) { pages[41][3] = page.toByte() }
    fun setProt(on: Boolean) { pages[42][0] = ((pages[42][0].toInt() and 0x7F) or (if (on) 0x80 else 0)).toByte() }
    fun setAuthLim(v: Int) { pages[42][0] = ((pages[42][0].toInt() and 0xF8) or (v and 0x07)).toByte() }
    fun pwd(): ByteArray = pages[43].copyOf()
    fun pack(): ByteArray = pages[44].copyOfRange(0, 2)
    fun auth0(): Int = pages[41][3].toInt() and 0xFF
    fun prot(): Boolean = (pages[42][0].toInt() and 0x80) != 0
    fun authLim(): Int = pages[42][0].toInt() and 0x07
    fun pinBytes(): ByteArray = pages[4] + pages[5] + pages[6] + pages[7]
    private fun locked(): Boolean = authLim() > 0 && negativeAuthCount >= (1 shl authLim())

    fun provisionNew(key0: de.stustapay.libssp.util.BitVector, pin: String) {
        val c = de.stustapay.libssp.nfc.Ntag213Credentials.derive(key0, pages[0].copyOfRange(0, 3) + pages[1])
        val pinBytes = ByteArray(16); pin.toByteArray(Charsets.US_ASCII).copyInto(pinBytes)
        for (i in 0 until 4) pages[4 + i] = pinBytes.copyOfRange(i * 4, i * 4 + 4)
        setPwd(c.pwd); setPack(c.pack); setAuth0(4); setProt(true); setAuthLim(3)
    }

    override fun connect() { isConnected = true; authenticated = false }
    override fun close() { isConnected = false; authenticated = false }

    override fun transceive(cmd: ByteArray): ByteArray {
        if (!isConnected) throw IOException("not connected")
        return when (cmd[0].toInt() and 0xFF) {
            0x60 -> byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03)
            0x30 -> {
                val p = cmd[1].toInt() and 0xFF
                if (p >= 45) throw IOException("NAK invalid page")
                val out = ByteArray(16)
                for (i in 0 until 4) {
                    val page = (p + i) % 45
                    if (page >= auth0() && prot() && !authenticated) throw IOException("NAK read protected")
                    val src = if (page == 43 || page == 44) ByteArray(4) else pages[page] // PWD/PACK read as zeros
                    src.copyInto(out, i * 4)
                }
                out
            }
            0xA2 -> {
                val p = cmd[1].toInt() and 0xFF
                if (p >= 45 || cmd.size != 6) throw IOException("NAK invalid write")
                if (p >= auth0() && !authenticated) throw IOException("NAK write protected")
                pages[p] = cmd.copyOfRange(2, 6)
                byteArrayOf(0x0A)
            }
            0x1B -> {
                if (cmd.size != 5) throw IOException("NAK bad pwd frame")
                if (locked()) throw IOException("NAK auth locked")
                val ok = cmd.copyOfRange(1, 5).contentEquals(pages[43])
                if (!ok) { negativeAuthCount++; throw IOException("NAK wrong pwd") }
                authenticated = true
                negativeAuthCount = 0
                pages[44].copyOfRange(0, 2)
            }
            else -> throw IOException("NAK unknown command")
        }
    }
}
