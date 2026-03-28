package de.stustapay.chip_debug.ui.provision

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.stustapay.chip_debug.repository.NfcRepository
import de.stustapay.chip_debug.ui.write.NfcDebugScanResult
import de.stustapay.libssp.model.NfcScanFailure
import de.stustapay.libssp.model.NfcScanResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class NfcProvisionViewModel @Inject constructor(
    private val nfcRepository: NfcRepository,
) : ViewModel() {
    private val _result = MutableStateFlow<NfcDebugScanResult>(NfcDebugScanResult.None)
    val result = _result.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private var job: Job? = null

    fun stop() {
        job?.cancel()
        _result.value = NfcDebugScanResult.None
        _scanning.value = false
    }

    fun startProvision(pin: String, vibrator: Vibrator?) {
        job?.cancel()
        _result.value = NfcDebugScanResult.None
        _scanning.value = true

        job = viewModelScope.launch {
            // Retry loop — keep scanning until success or non-recoverable error
            while (true) {
                when (val res = nfcRepository.writeWithPin(pin)) {
                    is NfcScanResult.Write -> {
                        vibrator?.vibrate(VibrationEffect.createOneShot(300, 200))
                        _result.value = NfcDebugScanResult.WriteSuccess
                        _scanning.value = false
                        return@launch
                    }
                    is NfcScanResult.Fail -> {
                        when (res.reason) {
                            is NfcScanFailure.Lost -> {
                                // Tag lost — show hint but keep scanning
                                _result.value = NfcDebugScanResult.Failure(res.reason)
                            }
                            else -> {
                                // Other error — stop
                                _result.value = NfcDebugScanResult.Failure(res.reason)
                                _scanning.value = false
                                return@launch
                            }
                        }
                    }
                    else -> {
                        _scanning.value = false
                        return@launch
                    }
                }
            }
        }
    }
}
