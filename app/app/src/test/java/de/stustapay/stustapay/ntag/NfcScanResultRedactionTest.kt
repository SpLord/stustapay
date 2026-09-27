package de.stustapay.stustapay.ntag

import com.ionspin.kotlin.bignum.integer.toBigInteger
import de.stustapay.libssp.model.NfcScanResult
import de.stustapay.libssp.model.NfcTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NfcDataSource logs every scan result (`Log.i("StuStaPay", "nfc scan result: $res")`), which
 * stringifies whatever NfcScanResult/NfcTag it was given. Neither must ever leak the PIN into
 * logcat, whether the result came back as [NfcScanResult.Read] (carries an [NfcTag]) or
 * [NfcScanResult.Status] (carries the PIN directly, chip_debug NTAG213 verify).
 */
class NfcScanResultRedactionTest {
    private val pin = "SECRETPIN12345"
    private val uid = 0x040EA89A332091uL

    @Test
    fun nfcTag_toString_hidesPinWhenSet() {
        val s = NfcTag(uid.toBigInteger(), pin).toString()
        assertFalse(s.contains(pin))
        assertTrue(s.contains("pin=<set>"))
    }

    @Test
    fun nfcTag_toString_reportsNoPin() {
        val s = NfcTag(uid.toBigInteger(), null).toString()
        assertTrue(s.contains("pin=null"))
    }

    @Test
    fun statusResult_toString_hidesPinWhenSet() {
        val s = NfcScanResult.Status(
            uid = uid,
            pin = pin,
            auth0 = 4,
            prot = true,
            authLim = 3,
            legacy = false,
        ).toString()
        assertFalse(s.contains(pin))
        assertTrue(s.contains("pin=<set>"))
    }

    @Test
    fun statusResult_toString_reportsNoPin() {
        val s = NfcScanResult.Status(
            uid = uid,
            pin = null,
            auth0 = 4,
            prot = true,
            authLim = 3,
            legacy = false,
        ).toString()
        assertTrue(s.contains("pin=null"))
    }

    @Test
    fun readResult_toString_hidesPinWhenSet() {
        // NfcScanResult.Read has no custom toString(); it relies on NfcTag's redaction.
        val s = NfcScanResult.Read(NfcTag(uid.toBigInteger(), pin)).toString()
        assertFalse(s.contains(pin))
    }
}
