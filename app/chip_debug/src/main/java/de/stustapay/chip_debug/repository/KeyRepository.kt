package de.stustapay.chip_debug.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.stustapay.libssp.util.BitVector
import de.stustapay.libssp.util.KeyParsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the event key0 entered by staff, persisted in EncryptedSharedPreferences.
 *
 * The key is never logged. Only [parseKey0] validation and storage happen here;
 * consumers (e.g. [NfcRepository]) read the current value from [key0].
 */
@Singleton
class KeyRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "chip_debug_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _key0 = MutableStateFlow(prefs.getString(KEY_PREF, null)?.let { parseKey0(it) })
    val key0: StateFlow<BitVector?> = _key0

    /** Returns false (and stores nothing) if [hex] is not a valid 16-byte hex key. */
    fun setKey0Hex(hex: String): Boolean {
        val parsed = parseKey0(hex) ?: return false
        prefs.edit().putString(KEY_PREF, KeyParsing.normalize(hex)).apply()
        _key0.value = parsed
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY_PREF).apply()
        _key0.value = null
    }

    companion object {
        private const val KEY_PREF = "key0"

        fun parseKey0(hex: String): BitVector? = KeyParsing.parseKey0(hex)
    }
}
