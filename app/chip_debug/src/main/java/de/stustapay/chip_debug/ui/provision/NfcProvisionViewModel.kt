package de.stustapay.chip_debug.ui.provision

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.stustapay.chip_debug.repository.NfcRepository
import de.stustapay.chip_debug.ui.write.NfcDebugScanResult
import de.stustapay.libssp.model.NfcScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class NfcProvisionViewModel @Inject constructor(
    private val nfcRepository: NfcRepository,
) : ViewModel() {
    private val _result = MutableStateFlow<NfcDebugScanResult>(NfcDebugScanResult.None)
    val result = _result.asStateFlow()

    fun stop() {
        _result.value = NfcDebugScanResult.None
    }

    suspend fun provisionWithPin(pin: String, vibrator: Vibrator) {
        _result.value = NfcDebugScanResult.None
        when (val res = nfcRepository.writeWithPin(pin)) {
            is NfcScanResult.Write -> {
                vibrator.vibrate(VibrationEffect.createOneShot(300, 200))
                _result.value = NfcDebugScanResult.WriteSuccess
            }
            is NfcScanResult.Fail -> {
                _result.value = NfcDebugScanResult.Failure(res.reason)
            }
            else -> {}
        }
    }
}
