package de.stustapay.chip_debug.ui.verify

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.stustapay.chip_debug.repository.NfcRepository
import de.stustapay.libssp.model.NfcScanFailure
import de.stustapay.libssp.model.NfcScanResult
import de.stustapay.libssp.model.NfcTag
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NfcVerifyViewModel @Inject constructor(
    private val nfcRepository: NfcRepository,
) : ViewModel() {
    private val _result = MutableStateFlow<NfcDebugScanResult>(NfcDebugScanResult.None)
    val result = _result.asStateFlow()

    private var job: Job? = null

    fun stop() {
        if (job?.isActive == true) {
            job?.cancel()
        }
        _result.update { NfcDebugScanResult.None }
    }

    /**
     * Continuously scans for a band and shows its NTAG213 protection status. A MIFARE-Ultralight
     * AES band cannot answer a Status request (see [NfcScanFailure.STATUS_NTAG_ONLY]) — for those
     * we fall back to the original UID/PIN read used before this screen understood NTAG213 status,
     * so MIFARE-AES verification behaviour is unchanged. That fallback needs a second tap of the
     * same band, since the first tap's scan request ("Status") already produced a result.
     */
    fun scan(vibrator: Vibrator) {
        stop()

        job = viewModelScope.launch {
            val trying = true
            while (trying) {
                when (val res = nfcRepository.status()) {
                    is NfcScanResult.Status -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(300, 200))
                        _result.emit(
                            NfcDebugScanResult.StatusSuccess(
                                uid = res.uid,
                                // Never keep/display the PIN itself — only whether one is set.
                                hasPin = !res.pin.isNullOrEmpty(),
                                auth0 = res.auth0,
                                prot = res.prot,
                                authLim = res.authLim,
                                legacy = res.legacy,
                            )
                        )
                    }

                    is NfcScanResult.Fail -> {
                        val reason = res.reason
                        if (reason is NfcScanFailure.Other && reason.msg == NfcScanFailure.STATUS_NTAG_ONLY) {
                            emitLegacyRead(vibrator)
                        } else {
                            _result.emit(NfcDebugScanResult.Failure(reason))
                        }
                    }

                    else -> _result.emit(NfcDebugScanResult.None)
                }
            }
        }
    }

    private suspend fun emitLegacyRead(vibrator: Vibrator) {
        when (val res = nfcRepository.read()) {
            is NfcScanResult.Read -> {
                vibrator.vibrate(VibrationEffect.createOneShot(300, 200))
                _result.emit(NfcDebugScanResult.ReadSuccess(res.tag))
            }

            is NfcScanResult.Fail -> _result.emit(NfcDebugScanResult.Failure(res.reason))
            else -> _result.emit(NfcDebugScanResult.None)
        }
    }
}

sealed interface NfcDebugScanResult {
    object None : NfcDebugScanResult

    /** MIFARE-Ultralight AES verify result (unchanged behaviour, kept for that chip family). */
    data class ReadSuccess(
        val tag: NfcTag
    ) : NfcDebugScanResult

    /** NTAG213 protection status. */
    data class StatusSuccess(
        val uid: ULong,
        val hasPin: Boolean,
        val auth0: Int,
        val prot: Boolean,
        val authLim: Int,
        val legacy: Boolean,
    ) : NfcDebugScanResult

    data class Failure(
        val reason: NfcScanFailure
    ) : NfcDebugScanResult
}
