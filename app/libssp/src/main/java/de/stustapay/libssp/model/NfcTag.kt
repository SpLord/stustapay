package de.stustapay.libssp.model

import com.ionspin.kotlin.bignum.integer.BigInteger

data class NfcTag(
    val uid: BigInteger,
    val pin: String?,
) {
    /** Never includes the PIN itself — only whether one is set. Used implicitly by any log line
     *  that stringifies an [NfcScanResult] carrying this tag (e.g. [NfcScanResult.Read]). */
    override fun toString(): String {
        return "${uidHex()} pin=${if (pin == null) "null" else "<set>"}"
    }

    fun uidHex(): String {
        return uid.toString(16).uppercase()
    }
}