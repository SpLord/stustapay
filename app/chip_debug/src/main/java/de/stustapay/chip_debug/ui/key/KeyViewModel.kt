package de.stustapay.chip_debug.ui.key

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.stustapay.chip_debug.repository.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class KeyViewModel @Inject constructor(
    private val keyRepository: KeyRepository,
) : ViewModel() {
    /**
     * False when the encrypted store is unavailable; the key is then in-memory only.
     * Forwards live from [KeyRepository] since a runtime Keystore failure can flip this
     * after construction (not just at startup).
     */
    val storageAvailable: Boolean
        get() = keyRepository.storageAvailable

    /** Never the key itself: first 4 hex chars of SHA-256(key bytes), or null if unset. */
    val fingerprint: StateFlow<String?> = keyRepository.key0
        .map { key ->
            key?.let {
                val digest = MessageDigest.getInstance("SHA-256").digest(it.asByteArray())
                digest.joinToString("") { b -> "%02x".format(b) }.take(4)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    /** Returns true on success (caller should then clear the plaintext input). */
    fun save(hex: String): Boolean {
        val ok = keyRepository.setKey0Hex(hex)
        _errorMessage.value = if (ok) null else "Ungültig: 32 Hex-Zeichen erwartet"
        return ok
    }

    fun delete() {
        keyRepository.clear()
        _errorMessage.value = null
    }
}
