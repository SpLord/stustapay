package de.stustapay.libssp.nfc

import de.stustapay.libssp.util.BitVector
import de.stustapay.libssp.util.asBitVector
import de.stustapay.libssp.util.cmac

/**
 * Per-band NTAG213 password/PACK, derived from the event key0 and the 7-byte UID:
 *   d = AES-CMAC(key0, "NTAG213-PWD" || uid)
 *   PWD = d[0..3], PACK = d[4..5]
 * No secret leaves the terminal; a cloned UID without key0 cannot compute PWD.
 */
object Ntag213Credentials {
    class Credentials(val pwd: ByteArray, val pack: ByteArray) {
        init { require(pwd.size == 4 && pack.size == 2) }
    }

    const val DOMAIN = "NTAG213-PWD"

    /** Transition switch: pretix30 = true (legacy bands still work, flagged), pretix31 = false. */
    const val ACCEPT_LEGACY_BANDS = true

    /** Bands provisioned before this change (upstream debug key 00..0f, first 4 / 2 bytes). */
    val LEGACY = Credentials(byteArrayOf(0x00, 0x01, 0x02, 0x03), byteArrayOf(0x00, 0x01))

    fun derive(key0: BitVector, uid: ByteArray): Credentials {
        require(uid.size == 7) { "NTAG213 UID must be 7 bytes" }
        require(key0.len == 128uL) { "key0 must be 16 bytes" }
        val msg = (DOMAIN.toByteArray(Charsets.US_ASCII) + uid).asBitVector()
        val d = msg.cmac(key0)
        val pwd = ByteArray(4) { d.gbe(it.toULong()).toByte() }
        val pack = ByteArray(2) { d.gbe((4 + it).toULong()).toByte() }
        return Credentials(pwd, pack)
    }
}
