package de.stustapay.chip_debug.repository

import android.content.Context
import android.content.SharedPreferences
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
 *
 * The encrypted store can fail to open or to read/write (Keystore corruption, OEM
 * Keystore bugs — these can surface as [java.security.GeneralSecurityException],
 * [java.io.IOException], or unchecked exceptions such as
 * [java.security.ProviderException] / [IllegalStateException] from `MasterKey.Builder`
 * or the Keystore itself). We never let any of that escape this class:
 * - at construction, we delete the possibly-corrupted prefs file and retry once; if it
 *   still fails we fall back to in-memory-only storage ([storageAvailable] is `false`).
 * - at runtime (read/write after a successful construction), a failure degrades the
 *   same way instead of crashing: the prefs handle is dropped and further access stays
 *   in-memory-only for the rest of the process lifetime.
 * In every fallback case, [key0] never survives a process restart and [setKey0Hex] only
 * updates the in-memory StateFlow.
 */
@Singleton
class KeyRepository @Inject constructor(@ApplicationContext context: Context) {
    private var prefs: SharedPreferences? = createPrefs(context)

    /** False when the encrypted store is unavailable; the key is then in-memory only. */
    val storageAvailable: Boolean
        get() = prefs != null

    private val _key0 = MutableStateFlow(readInitialKey())
    val key0: StateFlow<BitVector?> = _key0

    /** Returns false (and stores nothing) if [hex] is not a valid 16-byte hex key. */
    fun setKey0Hex(hex: String): Boolean {
        val parsed = parseKey0(hex) ?: return false
        persist(hex)
        _key0.value = parsed
        return true
    }

    fun clear() {
        val p = prefs
        if (p != null) {
            try {
                p.edit().remove(KEY_PREF).apply()
            } catch (e: Exception) {
                prefs = null
            }
        }
        _key0.value = null
    }

    private fun readInitialKey(): BitVector? {
        val p = prefs ?: return null
        return try {
            p.getString(KEY_PREF, null)?.let { parseKey0(it) }
        } catch (e: Exception) {
            prefs = null
            null
        }
    }

    private fun persist(hex: String) {
        val p = prefs ?: return
        try {
            p.edit().putString(KEY_PREF, KeyParsing.normalize(hex)).apply()
        } catch (e: Exception) {
            prefs = null
        }
    }

    companion object {
        private const val PREFS_NAME = "chip_debug_keys"
        private const val KEY_PREF = "key0"

        fun parseKey0(hex: String): BitVector? = KeyParsing.parseKey0(hex)

        /**
         * Creates the encrypted prefs. Never throws: catches any exception from building
         * the [MasterKey] or [EncryptedSharedPreferences] (checked or unchecked), deletes
         * the (possibly corrupted) prefs file and retries once; if that also fails it
         * returns null so callers can fall back to in-memory-only storage.
         */
        private fun createPrefs(context: Context): SharedPreferences? {
            return try {
                buildPrefs(context)
            } catch (e: Exception) {
                recreateAfterFailure(context)
            }
        }

        private fun recreateAfterFailure(context: Context): SharedPreferences? {
            context.deleteSharedPreferences(PREFS_NAME)
            return try {
                buildPrefs(context)
            } catch (e: Exception) {
                null
            }
        }

        private fun buildPrefs(context: Context): SharedPreferences {
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
