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
        const val CFG0_PAGE = 41           // byte 3 = AUTH0
        const val CFG1_PAGE = 42           // byte 0 = ACCESS: PROT(0x80) CFGLCK(0x40) NFC_CNT_EN(0x10) NFC_CNT_PWD_PROT(0x08) AUTHLIM(0x07)
        const val ACCESS_PROT = 0x80
        const val ACCESS_CFGLCK = 0x40
        const val ACCESS_AUTHLIM_MASK = 0x07
        const val AUTHLIM_VALUE = 3        // datasheet: limit = 2^AUTHLIM negative attempts
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

    private fun bytesToULong(bytes: ByteArray): ULong {
        var uid = 0uL
        for (b in bytes) uid = (uid shl 8) or b.toUByte().toULong()
        return uid
    }

    fun readUid(): ULong = bytesToULong(readUidBytes())

    data class AuthResult(val creds: Ntag213Credentials.Credentials, val legacy: Boolean, val uid: ByteArray)
    data class ReadResult(val tag: NfcTag, val legacy: Boolean)

    /**
     * PWD_AUTH with the band-specific credentials; during the transition (ACCEPT_LEGACY_BANDS)
     * a band provisioned with the old global password is accepted too and flagged legacy.
     * A NAK on the right password can also mean "locked by AUTHLIM" — on real hardware both look
     * the same, so the caller shows one message naming both causes.
     */
    fun authenticate(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): AuthResult {
        if (!isConnected) throw TagConnectionException()
        val uid = readUidBytes()
        val creds = Ntag213Credentials.derive(key0, uid)
        try {
            cmdPwdAuth(creds.pwd, creds.pack)
            return AuthResult(creds, legacy = false, uid = uid)
        } catch (e: TagAuthException) {
            throw e // PACK mismatch: the band answered — right PWD, foreign PACK — never retry
        } catch (e: IOException) {
            // NAK, either as a transport exception or (some readers) a short data answer.
            if (looksLocked(e)) throw TagLockedException("Band gesperrt (AUTHLIM)")
        }
        if (acceptLegacy) {
            // A failed PWD_AUTH HALTs a real NTAG213 — it must be re-activated before the next
            // attempt, or the legacy PWD_AUTH would NAK regardless of whether it is correct.
            transport.close()
            transport.connect()
            // Legacy bands always have PROT = 0 (the pre-migration provisioning never set it),
            // so page 41 (CFG0) is always plain-readable there. A band that refuses even this
            // unauthenticated read is already protected under a foreign key0 -- trying the legacy
            // password on it would only burn another negative-auth attempt (AUTHLIM) on a band
            // that was never going to accept it, risking a lockout if the same band is rescanned
            // a few times (e.g. a guest's band from another event, presented at the till).
            if (canReadCfg0Unauthenticated()) {
                try {
                    cmdPwdAuth(Ntag213Credentials.LEGACY.pwd, Ntag213Credentials.LEGACY.pack)
                    return AuthResult(Ntag213Credentials.LEGACY, legacy = true, uid = uid)
                } catch (e: Exception) { /* fall through */ }
            } else {
                transport.close(); transport.connect()
            }
        }
        throw TagAuthException("PWD_AUTH rejected")
    }

    /** Emulator marks a locked band explicitly; real readers just NAK. */
    private fun looksLocked(e: IOException): Boolean = e.message?.contains("locked", ignoreCase = true) == true

    /**
     * True if page 41 (CFG0) can be read without authentication. Legacy bands (PROT = 0) and
     * fresh bands (AUTH0 = 0xFF) always answer; a band already protected under a foreign key0
     * NAKs. Used to skip the legacy PWD_AUTH attempt (and its negative-auth cost) entirely on
     * such a foreign protected band -- see authenticate()/provisionTag().
     */
    private fun canReadCfg0Unauthenticated(): Boolean =
        try {
            cmdRead(CFG0_PAGE.toUByte())
            true
        } catch (e: IOException) {
            false
        }

    /** Read UID + PIN. Always authenticates — a band that cannot authenticate is not ours. */
    fun readTag(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): ReadResult {
        val auth = authenticate(key0, acceptLegacy)
        val uid = bytesToULong(auth.uid)
        val pinPages = cmdRead(PIN_PAGE_START.toUByte())
        val sb = StringBuilder()
        for (i in 0 until PIN_MAX_LENGTH) {
            val c = pinPages[i].toInt().toChar()
            if (c != 0.toChar() && c.isLetterOrDigit()) sb.append(c)
        }
        return ReadResult(NfcTag(uid.toBigInteger(), sb.toString().ifEmpty { null }), auth.legacy)
    }

    data class Ntag213Status(val auth0: Int, val prot: Boolean, val authLim: Int)

    /**
     * Provision or migrate a band. Order is chosen so that every interruption leaves a state
     * from which a re-run converges:
     *   1. gain write access with derived creds (already migrated) -> else legacy creds (not yet
     *      migrated) -> else none (fresh band, AUTH0 = 0xFF, writes need no auth)
     *   2. write PIN (pages 4-7)                                     (readable/writable in every start state)
     *   3. write PWD (43) + PACK (44)                                 (from now on derived creds work)
     *   4. CFG1: PROT = 1, AUTHLIM = 3, CFGLCK untouched              (reads >= 4 need auth)
     *   5. CFG0: AUTH0 = 4                                            (writes >= 4 need auth)
     * CFG1 is written before CFG0 on purpose: AUTH0 gates writes to pages >= itself immediately,
     * including the config pages themselves, so setting it first would lock an unauthenticated
     * (fresh) band out of the following CFG1 write. Setting it last is always safe.
     * A re-run after step 3 succeeds via derived-PWD write access; before step 3 via legacy/none.
     */
    fun provisionTag(pin: String, key0: BitVector, legacy: Ntag213Credentials.Credentials? = Ntag213Credentials.LEGACY) {
        if (!isConnected) throw TagConnectionException()
        val creds = Ntag213Credentials.derive(key0, readUidBytes())

        // A failed PWD_AUTH HALTs a real NTAG213 -- it must be re-activated (close/connect)
        // before the next attempt, or every following command (including the next PWD_AUTH and
        // the writes below) would NAK regardless of whether the credential is correct.
        //
        // Legacy bands always have PROT = 0, so an unauthenticated read of page 41 (CFG0) tells
        // apart "may be legacy" from "already protected under a foreign key0" without spending a
        // second negative-auth attempt on a band that was never going to accept the legacy
        // password anyway (see authenticate()/canReadCfg0Unauthenticated()).
        if (!tryAuth(creds)) {
            transport.close(); transport.connect()
            val mayBeLegacy = legacy != null && canReadCfg0Unauthenticated()
            if (!mayBeLegacy || !tryAuth(legacy!!)) {
                transport.close(); transport.connect()
            }
        }

        writePin(pin)
        cmdWrite(PWD_PAGE.toUByte(), creds.pwd[0].toUByte(), creds.pwd[1].toUByte(), creds.pwd[2].toUByte(), creds.pwd[3].toUByte())
        cmdWrite(PACK_PAGE.toUByte(), creds.pack[0].toUByte(), creds.pack[1].toUByte(), 0x00u, 0x00u)

        val cfg1 = cmdRead(CFG1_PAGE.toUByte())
        // keep NFC_CNT_EN / NFC_CNT_PWD_PROT bits, never set CFGLCK, set PROT, set AUTHLIM
        val keepMask = (ACCESS_PROT or ACCESS_CFGLCK or ACCESS_AUTHLIM_MASK).inv() and 0xFF
        val newAccess = ((cfg1[0].toInt() and keepMask) or ACCESS_PROT or AUTHLIM_VALUE) and 0xFF
        cmdWrite(CFG1_PAGE.toUByte(), newAccess.toUByte(), cfg1[1].toUByte(), cfg1[2].toUByte(), cfg1[3].toUByte())

        val cfg0 = cmdRead(CFG0_PAGE.toUByte())
        cmdWrite(CFG0_PAGE.toUByte(), cfg0[0].toUByte(), cfg0[1].toUByte(), cfg0[2].toUByte(), PIN_PAGE_START.toUByte())
    }

    /** Rewrite the PIN on an already provisioned band (chip_debug "Rewrite"). */
    fun writeTag(pin: String, key0: BitVector) {
        authenticate(key0)
        writePin(pin)
    }

    /** Current protection configuration of a band. Requires authentication (band must be ours). */
    fun readStatus(key0: BitVector): Ntag213Status {
        authenticate(key0)
        val cfg0 = cmdRead(CFG0_PAGE.toUByte()); val cfg1 = cmdRead(CFG1_PAGE.toUByte())
        val access = cfg1[0].toInt() and 0xFF
        return Ntag213Status(cfg0[3].toInt() and 0xFF, (access and ACCESS_PROT) != 0, access and ACCESS_AUTHLIM_MASK)
    }

    /**
     * Attempt PWD_AUTH purely to gain write access while (re-)provisioning. Unlike
     * authenticate()/readTag(), the PACK is deliberately *not* validated here: a band that was
     * interrupted between writing PWD (43) and PACK (44) already has the new PWD but a stale
     * PACK, and a PWD match alone -- derived from our secret key0 -- is proof enough that this is
     * our own band for the purpose of resuming a write; the PACK gets (re)written right after.
     * Only IOException (incl. TagNakException) means "this credential is not it"; anything else
     * is unexpected and propagates.
     */
    private fun tryAuth(c: Ntag213Credentials.Credentials): Boolean =
        try {
            cmdPwdAuth(c.pwd, null)
            true
        } catch (e: IOException) {
            if (looksLocked(e)) throw TagLockedException("Band gesperrt (AUTHLIM)")
            false
        }

    private fun writePin(pin: String) {
        require(
            pin.isNotEmpty() && pin.length <= PIN_MAX_LENGTH &&
                pin.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        ) { "PIN must be 1-16 ASCII letters/digits" }
        val pinAscii = pin.toByteArray(Charsets.US_ASCII)
        val pinBytes = ByteArray(PIN_MAX_LENGTH)
        pinAscii.copyInto(pinBytes, endIndex = pinAscii.size)
        for (page in 0 until 4) {
            val o = page * 4
            cmdWrite(
                (PIN_PAGE_START + page).toUByte(),
                pinBytes[o].toUByte(), pinBytes[o + 1].toUByte(), pinBytes[o + 2].toUByte(), pinBytes[o + 3].toUByte()
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
        // Anything else (NAK as short data, empty, garbage) is a NAK, not a PACK mismatch —
        // some real readers surface a failed PWD_AUTH this way instead of an IOException.
        if (resp.size != 2) {
            throw TagNakException("PWD_AUTH rejected (response ${resp.size} bytes)")
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
