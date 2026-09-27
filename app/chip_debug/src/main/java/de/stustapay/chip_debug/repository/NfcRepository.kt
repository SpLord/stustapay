package de.stustapay.chip_debug.repository

import de.stustapay.libssp.model.NfcScanFailure
import de.stustapay.libssp.model.NfcScanRequest
import de.stustapay.libssp.model.NfcScanResult
import de.stustapay.libssp.nfc.NfcDataSource
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class NfcRepository @Inject constructor(
    private val nfcDataSource: NfcDataSource,
    private val keys: KeyRepository,
) {
    // chip_debug uses one key for both dataProtKey and uidRetrKey (MF0AES/NTAG213 alike),
    // sourced from the event key entered by staff via KeyRepository.

    suspend fun read(): NfcScanResult {
        val k = keys.key0.value ?: return NfcScanResult.Fail(NfcScanFailure.NoKey)
        return nfcDataSource.scan(
            NfcScanRequest.Read(uidRetrKey = k, dataProtKey = k)
        )
    }

    suspend fun write(): NfcScanResult {
        val k = keys.key0.value ?: return NfcScanResult.Fail(NfcScanFailure.NoKey)
        return nfcDataSource.scan(
            NfcScanRequest.Write(uidRetrKey = k, dataProtKey = k)
        )
    }

    suspend fun writeWithPin(pin: String): NfcScanResult {
        val k = keys.key0.value ?: return NfcScanResult.Fail(NfcScanFailure.NoKey)
        return nfcDataSource.scan(
            NfcScanRequest.Write(uidRetrKey = k, dataProtKey = k, pin = pin)
        )
    }

    suspend fun rewrite(): NfcScanResult {
        val k = keys.key0.value ?: return NfcScanResult.Fail(NfcScanFailure.NoKey)
        return nfcDataSource.scan(
            NfcScanRequest.Rewrite(uidRetrKey = k, dataProtKey = k, oldDataProtKey = k)
        )
    }

    suspend fun test(): NfcScanResult {
        val k = keys.key0.value ?: return NfcScanResult.Fail(NfcScanFailure.NoKey)
        return nfcDataSource.scan(
            NfcScanRequest.Test(uidRetrKey = k, dataProtKey = k)
        )
    }
}
