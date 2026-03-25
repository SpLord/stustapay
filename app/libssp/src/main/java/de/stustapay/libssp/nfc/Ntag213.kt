package de.stustapay.libssp.nfc

import android.nfc.Tag
import android.nfc.tech.NfcA
import android.nfc.tech.TagTechnology
import com.ionspin.kotlin.bignum.integer.toBigInteger
import de.stustapay.libssp.model.NfcTag
import de.stustapay.libssp.util.BitVector
import java.io.IOException

/**
 * NTAG213 NFC chip support.
 *
 * Memory layout (4 bytes per page):
 *   Pages 0-1: UID (serial number, 7 bytes)
 *   Page 2: Lock bytes / internal
 *   Page 3: Capability container
 *   Pages 4-39: User memory (144 bytes)
 *   Page 40: Reserved
 *   Page 41: CFG0 (AUTH0 = first page requiring auth, at byte 3)
 *   Page 42: CFG1 (access configuration)
 *   Page 43: PWD  (4-byte password)
 *   Page 44: PACK (2-byte password acknowledge, bytes 0-1)
 *
 * Commands:
 *   READ:     0x30 + page  -> returns 16 bytes (4 pages)
 *   WRITE:    0xA2 + page + 4 bytes data
 *   PWD_AUTH: 0x1B + 4 bytes password -> returns 2 bytes PACK
 *   GET_VERSION: 0x60 -> returns chip identification
 */
class Ntag213(private val rawTag: Tag) : TagTechnology {
    val nfcaTag: NfcA = NfcA.get(rawTag)

    companion object {
        const val NTAG213_PAGE_COUNT = 45
        const val USER_PAGE_START = 4
        const val USER_PAGE_END = 39
        const val USER_BYTES = 144 // (39 - 4 + 1) * 4
        const val PIN_PAGE_START = 4  // store PIN in first user pages (4-7 = 16 bytes)
        const val PIN_MAX_LENGTH = 16
        const val AUTH0_PAGE = 41
        const val PWD_PAGE = 43
        const val PACK_PAGE = 44

        // NTAG213 GET_VERSION response: 00 04 04 02 01 00 0F 03
        // vendor=04(NXP), product type=04(NTAG), subtype=02, major=01, minor=00, size=0F, protocol=03
        val NTAG213_VERSION = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03)
    }

    /**
     * Read UID from pages 0-1 and PIN from user memory pages 4-7.
     * If [authenticate] is true, performs PWD_AUTH before reading protected pages.
     */
    fun readTag(key0: BitVector?, key1: BitVector?): NfcTag {
        if (!isConnected) { throw TagConnectionException() }

        // Read pages 0-3 (contains UID)
        val uidPages = cmdRead(0x00u)

        // NTAG213 UID layout in pages 0-1:
        // Page 0: UID0 UID1 UID2 BCC0
        // Page 1: UID3 UID4 UID5 UID6
        var uid = 0uL
        // bytes 0..2 from page 0
        uid = uid or (uidPages[0].toUByte().toULong() shl 48)
        uid = uid or (uidPages[1].toUByte().toULong() shl 40)
        uid = uid or (uidPages[2].toUByte().toULong() shl 32)
        // bytes 4..7 from page 1 (byte 3 is BCC0)
        uid = uid or (uidPages[4].toUByte().toULong() shl 24)
        uid = uid or (uidPages[5].toUByte().toULong() shl 16)
        uid = uid or (uidPages[6].toUByte().toULong() shl 8)
        uid = uid or (uidPages[7].toUByte().toULong())

        var pin: String? = null
        if (key0 != null) {
            // Authenticate with password before reading protected pages
            val pwd = ByteArray(4)
            for (i in 0 until 4) {
                pwd[i] = key0.gbe(i.toULong()).toByte()
            }

            val pack = if (key1 != null) {
                ByteArray(2) { key1.gbe(it.toULong()).toByte() }
            } else {
                null
            }

            cmdPwdAuth(pwd, pack)

            // Read PIN from user memory pages 4-7 (16 bytes)
            val pinPages = cmdRead(PIN_PAGE_START.toUByte())
            val sb = StringBuilder()
            for (i in 0 until PIN_MAX_LENGTH) {
                val c = pinPages[i].toInt().toChar()
                if (c != 0.toChar()) {
                    sb.append(c)
                }
            }
            pin = sb.toString()
        }

        return NfcTag(uid.toBigInteger(), pin)
    }

    /**
     * Write PIN to user memory pages 4-7, and optionally set PWD_AUTH password.
     */
    fun writeTag(pin: String, key0: BitVector, key1: BitVector) {
        if (!isConnected) { throw TagConnectionException() }

        // Authenticate first
        val pwd = ByteArray(4)
        for (i in 0 until 4) {
            pwd[i] = key0.gbe(i.toULong()).toByte()
        }
        val pack = ByteArray(2) { key1.gbe(it.toULong()).toByte() }

        cmdPwdAuth(pwd, pack)

        // Write PIN to pages 4-7 (16 bytes, padded with zeros)
        val pinBytes = ByteArray(PIN_MAX_LENGTH)
        for (i in pin.indices) {
            if (i < PIN_MAX_LENGTH) {
                pinBytes[i] = pin[i].code.toByte()
            }
        }
        for (page in 0 until 4) {
            val offset = page * 4
            cmdWrite(
                (PIN_PAGE_START + page).toUByte(),
                pinBytes[offset].toUByte(),
                pinBytes[offset + 1].toUByte(),
                pinBytes[offset + 2].toUByte(),
                pinBytes[offset + 3].toUByte()
            )
        }
    }

    /**
     * Provision a new NTAG213 tag: write password, PACK, set AUTH0 protection, then write PIN.
     */
    fun provisionTag(pin: String, key0: BitVector, key1: BitVector) {
        if (!isConnected) { throw TagConnectionException() }

        val pwd = ByteArray(4)
        for (i in 0 until 4) {
            pwd[i] = key0.gbe(i.toULong()).toByte()
        }
        val pack = ByteArray(2) { key1.gbe(it.toULong()).toByte() }

        // Write PWD (page 43)
        cmdWrite(
            PWD_PAGE.toUByte(),
            pwd[0].toUByte(), pwd[1].toUByte(), pwd[2].toUByte(), pwd[3].toUByte()
        )

        // Write PACK (page 44) - 2 bytes PACK + 2 bytes zero
        cmdWrite(
            PACK_PAGE.toUByte(),
            pack[0].toUByte(), pack[1].toUByte(), 0x00u, 0x00u
        )

        // Set AUTH0 in CFG0 (page 41): protect from page 4 onwards
        // CFG0 byte 3 = AUTH0 value
        // Read current CFG0 first
        val cfg0 = cmdRead(AUTH0_PAGE.toUByte())
        cmdWrite(
            AUTH0_PAGE.toUByte(),
            cfg0[0].toUByte(), cfg0[1].toUByte(), cfg0[2].toUByte(),
            PIN_PAGE_START.toUByte() // AUTH0 = page 4, protect user memory
        )

        // Now authenticate with the new password
        cmdPwdAuth(pwd, pack)

        // Write PIN to pages 4-7
        val pinBytes = ByteArray(PIN_MAX_LENGTH)
        for (i in pin.indices) {
            if (i < PIN_MAX_LENGTH) {
                pinBytes[i] = pin[i].code.toByte()
            }
        }
        for (page in 0 until 4) {
            val offset = page * 4
            cmdWrite(
                (PIN_PAGE_START + page).toUByte(),
                pinBytes[offset].toUByte(),
                pinBytes[offset + 1].toUByte(),
                pinBytes[offset + 2].toUByte(),
                pinBytes[offset + 3].toUByte()
            )
        }
    }

    // -- Low-level NFC commands --

    private fun cmdRead(page: UByte): ByteArray {
        val cmd = byteArrayOf(0x30, page.toByte())
        return nfcaTag.transceive(cmd)
    }

    private fun cmdWrite(page: UByte, a: UByte, b: UByte, c: UByte, d: UByte) {
        val cmd = byteArrayOf(0xA2.toByte(), page.toByte(), a.toByte(), b.toByte(), c.toByte(), d.toByte())
        nfcaTag.transceive(cmd)
    }

    private fun cmdPwdAuth(pwd: ByteArray, expectedPack: ByteArray?) {
        if (pwd.size != 4) throw IllegalArgumentException("PWD must be 4 bytes")

        val cmd = byteArrayOf(0x1B, pwd[0], pwd[1], pwd[2], pwd[3])
        val resp = nfcaTag.transceive(cmd)

        if (expectedPack != null && resp.size >= 2) {
            if (resp[0] != expectedPack[0] || resp[1] != expectedPack[1]) {
                throw TagAuthException("PACK mismatch")
            }
        }
    }

    private fun cmdGetVersion(): ByteArray {
        val cmd = byteArrayOf(0x60)
        return nfcaTag.transceive(cmd)
    }

    // -- TagTechnology interface --

    /**
     * Connect to the tag. If already connected (from NfcHandler probe), skip.
     */
    override fun connect() {
        if (!nfcaTag.isConnected) {
            nfcaTag.connect()
        }
    }

    override fun close() {
        nfcaTag.close()
    }

    override fun isConnected(): Boolean {
        return nfcaTag.isConnected
    }

    override fun getTag(): Tag {
        return rawTag
    }
}
