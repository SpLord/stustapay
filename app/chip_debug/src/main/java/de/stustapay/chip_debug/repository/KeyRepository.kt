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
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the event key0 entered by staff, persisted in EncryptedSharedPreferences.
 *
 * The key is never logged. Only [parseKey0] validation and storage happen here;
 * consumers (e.g. [NfcRepository]) read the current value from [key0].
 *
 * The encrypted store can fail to open (Keystore corruption / OEM Keystore bugs). In that
 * case we delete the possibly-corrupted prefs file and retry once; if it still fails we
 * fall back to in-memory-only storage instead of crashing: [storageAvailable] is then
 * `false`, [key0] never survives a process restart, and [setKey0Hex] only updates the
 * StateFlow without persisting anything.
 */
@Singleton
class KeyRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences? = createPrefs(context)

    /** False when the encrypted store could not be created; the key is then in-memory only. */
    val storageAvailable: Boolean = prefs != null

    private val _key0 = MutableStateFlow(prefs?.getString(KEY_PREF, null)?.let { parseKey0(it) })
    val key0: StateFlow<BitVector?> = _key0

    /** Returns false (and stores nothing) if [hex] is not a valid 16-byte hex key. */
    fun setKey0Hex(hex: String): Boolean {
        val parsed = parseKey0(hex) ?: return false
        prefs?.edit()?.putString(KEY_PREF, KeyParsing.normalize(hex))?.apply()
        _key0.value = parsed
        return true
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_PREF)?.apply()
        _key0.value = null
    }

    companion object {
        private const val PREFS_NAME = "chip_debug_keys"
        private const val KEY_PREF = "key0"

        fun parseKey0(hex: String): BitVector? = KeyParsing.parseKey0(hex)

        /**
         * Creates the encrypted prefs. Never throws: on Keystore/IO failure it deletes the
         * (possibly corrupted) prefs file and retries once; if that also fails it returns
         * null so callers can fall back to in-memory-only storage.
         */
        private fun createPrefs(context: Context): SharedPreferences? {
            return try {
                buildPrefs(context)
            } catch (e: GeneralSecurityException) {
                recreateAfterFailure(context)
            } catch (e: IOException) {
                recreateAfterFailure(context)
            }
        }

        private fun recreateAfterFailure(context: Context): SharedPreferences? {
            context.deleteSharedPreferences(PREFS_NAME)
            return try {
                buildPrefs(context)
            } catch (e: GeneralSecurityException) {
                null
            } catch (e: IOException) {
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
