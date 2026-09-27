package de.stustapay.libssp.model

sealed interface NfcScanResult {
    fun msg(): String
    data class Read(
        val tag: NfcTag,
        val legacy: Boolean = false
    ) : NfcScanResult {
        override fun msg(): String {
            return "read ${tag.uidHex()}"
        }
    }

    object Write : NfcScanResult {
        override fun msg(): String {
            return "write success"
        }
    }

    data class Fail(
        val reason: NfcScanFailure
    ) : NfcScanResult {
        override fun msg(): String {
            return "failed: $reason"
        }
    }

    data class Test(
        val log: List<Pair<String, Boolean>>
    ) : NfcScanResult {
        override fun msg(): String {
            return "got test results"
        }
    }

    /**
     * NTAG213 protection status (chip_debug "verify" screen). Never carries PWD/PACK/key0 —
     * only [pin] (nullable) is potentially sensitive and callers must not display it verbatim.
     */
    data class Status(
        val uid: ULong,
        val pin: String?,
        val auth0: Int,
        val prot: Boolean,
        val authLim: Int,
        val legacy: Boolean
    ) : NfcScanResult {
        override fun msg(): String {
            return "status read"
        }

        /** Never includes the PIN itself — only whether one is set (e.g. for log lines that
         *  stringify the whole result). */
        override fun toString(): String {
            return "Status(uid=$uid, pin=${if (pin == null) "null" else "<set>"}, auth0=$auth0, prot=$prot, authLim=$authLim, legacy=$legacy)"
        }
    }
}

sealed interface NfcScanFailure {
    fun msg(): String

    object NoKey : NfcScanFailure {
        override fun msg(): String {
            return "No Key"
        }
    }

    data class Other(val msg: String) : NfcScanFailure {
        override fun msg(): String {
            return "other error: $msg"
        }
    }

    data class Incompatible(val msg: String) : NfcScanFailure {
        override fun msg(): String {
            return "incompatible: $msg"
        }
    }

    data class Lost(val msg: String) : NfcScanFailure {
        override fun msg(): String {
            return "tag lost: $msg"
        }
    }

    data class Auth(val msg: String) : NfcScanFailure {
        override fun msg(): String {
            return "auth fail: $msg"
        }
    }

    data class Locked(val msg: String) : NfcScanFailure {
        override fun msg(): String {
            return "locked: $msg"
        }
    }
}