package de.stustapay.libssp.nfc

import android.nfc.tech.NfcA

/** Byte-level channel to an NTAG213. Production = NfcA, tests = FakeNtag213. */
interface Ntag213Transport {
    val isConnected: Boolean
    fun connect()
    fun close()
    /** Sends one command frame and returns the raw answer. A NAK surfaces as IOException. */
    fun transceive(cmd: ByteArray): ByteArray
}

class NfcATransport(val nfca: NfcA) : Ntag213Transport {
    override val isConnected: Boolean get() = nfca.isConnected
    override fun connect() { if (!nfca.isConnected) nfca.connect() }
    override fun close() { nfca.close() }
    override fun transceive(cmd: ByteArray): ByteArray = nfca.transceive(cmd)
}
