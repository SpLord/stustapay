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
class Ntag213(
    private val transport: Ntag213Transport,
    private val rawTag: Tag? = null,
) : TagTechnology {

    /** Production path: reuse an already connected NfcA (NfcHandler probe). */
    constructor(nfca: NfcA) : this(NfcATransport(nfca), nfca.tag)

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

        /**
         * True if a GET_VERSION response identifies an NTAG213 (the only NTAG variant whose
         * memory layout — config pages 41-44 — this class knows).
         * Checks vendor (NXP), product type (NTAG) and storage size (0x0F = 144 bytes) so that
         * NTAG213 sub-variants (F/TT) still match, while NTAG215/216 and MF0AES do not.
         */
        fun matchesVersion(version: ByteArray?): Boolean {
            if (version == null || version.size < 8) return false
            return version[1] == 0x04.toByte() &&
                version[2] == 0x04.toByte() &&
                version[6] == 0x0F.toByte()
        }
    }

    /** 7-byte UID from pages 0-1 (always readable). */
    fun readUidBytes(): ByteArray {
        if (!isConnected) throw TagConnectionException()
        val p = cmdRead(0x00u)
        if (p.size < 8) throw TagIncompatibleException("short read of UID pages")
        return byteArrayOf(p[0], p[1], p[2], p[4], p[5], p[6], p[7])
    }

    fun readUid(): ULong {
        var uid = 0uL
        for (b in readUidBytes()) uid = (uid shl 8) or b.toUByte().toULong()
        return uid
    }

    data class AuthResult(val creds: Ntag213Credentials.Credentials, val legacy: Boolean)
    data class ReadResult(val tag: NfcTag, val legacy: Boolean)

    /**
     * PWD_AUTH with the band-specific credentials; during the transition (ACCEPT_LEGACY_BANDS)
     * a band provisioned with the old global password is accepted too and flagged legacy.
     * A NAK on the right password can also mean "locked by AUTHLIM" — on real hardware both look
     * the same, so the caller shows one message naming both causes.
     */
    fun authenticate(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): AuthResult {
        if (!isConnected) throw TagConnectionException()
        val creds = Ntag213Credentials.derive(key0, readUidBytes())
        try {
            cmdPwdAuth(creds.pwd, creds.pack)
            return AuthResult(creds, legacy = false)
        } catch (e: TagAuthException) {
            throw e // PACK mismatch: the band answers with a foreign PACK
        } catch (e: IOException) {
            if (looksLocked(e)) throw TagLockedException("Band gesperrt (AUTHLIM)")
        }
        if (acceptLegacy) {
            try {
                cmdPwdAuth(Ntag213Credentials.LEGACY.pwd, Ntag213Credentials.LEGACY.pack)
                return AuthResult(Ntag213Credentials.LEGACY, legacy = true)
            } catch (e: Exception) { /* fall through */ }
        }
        throw TagAuthException("PWD_AUTH rejected")
    }

    /** Emulator marks a locked band explicitly; real readers just NAK. */
    private fun looksLocked(e: IOException): Boolean = e.message?.contains("locked", ignoreCase = true) == true

    /** Read UID + PIN. Always authenticates — a band that cannot authenticate is not ours. */
    fun readTag(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): ReadResult {
        val auth = authenticate(key0, acceptLegacy)
        val uid = readUid()
        val pinPages = cmdRead(PIN_PAGE_START.toUByte())
        val sb = StringBuilder()
        for (i in 0 until PIN_MAX_LENGTH) {
            val c = pinPages[i].toInt().toChar()
            if (c != 0.toChar() && c.isLetterOrDigit()) sb.append(c)
        }
        return ReadResult(NfcTag(uid.toBigInteger(), sb.toString().ifEmpty { null }), auth.legacy)
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
     * Handles both fresh tags (no auth) and already-provisioned tags (auth required).
     */
    fun provisionTag(pin: String, key0: BitVector, key1: BitVector) {
        if (!isConnected) { throw TagConnectionException() }

        val pwd = ByteArray(4)
        for (i in 0 until 4) {
            pwd[i] = key0.gbe(i.toULong()).toByte()
        }
        val pack = ByteArray(2) { key1.gbe(it.toULong()).toByte() }

        // Try PWD_AUTH first — tag might already be provisioned from a previous attempt
        try {
            cmdPwdAuth(pwd, pack)
        } catch (_: Exception) {
            // Auth failed or not needed (fresh tag) — continue without auth
        }

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
        val cfg0 = cmdRead(AUTH0_PAGE.toUByte())
        cmdWrite(
            AUTH0_PAGE.toUByte(),
            cfg0[0].toUByte(), cfg0[1].toUByte(), cfg0[2].toUByte(),
            PIN_PAGE_START.toUByte() // AUTH0 = page 4
        )

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
        return transport.transceive(cmd)
    }

    private fun cmdWrite(page: UByte, a: UByte, b: UByte, c: UByte, d: UByte) {
        val cmd = byteArrayOf(0xA2.toByte(), page.toByte(), a.toByte(), b.toByte(), c.toByte(), d.toByte())
        transport.transceive(cmd)
    }

    private fun cmdPwdAuth(pwd: ByteArray, expectedPack: ByteArray?) {
        if (pwd.size != 4) throw IllegalArgumentException("PWD must be 4 bytes")

        val cmd = byteArrayOf(0x1B, pwd[0], pwd[1], pwd[2], pwd[3])
        val resp = transport.transceive(cmd)

        // A successful PWD_AUTH answers with exactly the 2-byte PACK.
        // Anything else (NAK, empty, garbage) means the password was rejected.
        if (resp.size != 2) {
            throw TagAuthException("PWD_AUTH rejected (response ${resp.size} bytes)")
        }
        if (expectedPack != null) {
            if (expectedPack.size != 2) throw IllegalArgumentException("PACK must be 2 bytes")
            if (resp[0] != expectedPack[0] || resp[1] != expectedPack[1]) {
                throw TagAuthException("PACK mismatch")
            }
        }
    }

    private fun cmdGetVersion(): ByteArray {
        val cmd = byteArrayOf(0x60)
        return transport.transceive(cmd)
    }

    // -- TagTechnology interface --

    /**
     * Connect to the tag. If already connected (from NfcHandler probe), skip.
     */
    override fun connect() {
        transport.connect()
    }

    override fun close() {
        transport.close()
    }

    override fun isConnected(): Boolean {
        return transport.isConnected
    }

    override fun getTag(): Tag = rawTag ?: throw IllegalStateException("no android Tag (test transport)")
}
