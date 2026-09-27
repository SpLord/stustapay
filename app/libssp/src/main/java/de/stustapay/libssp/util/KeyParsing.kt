package de.stustapay.libssp.util

/**
 * Pure hex-string parsing for 16-byte (128-bit) keys, e.g. the NTAG213 event key0
 * or the MIFARE Ultralight AES key entered by staff in the chip_debug app.
 *
 * Lives in libssp (rather than chip_debug) so it can be unit-tested from the
 * `app` module without introducing an app -> chip_debug test dependency.
 */
object KeyParsing {
    /**
     * Parses [hex] as a 16-byte key. Spaces, colons, and any other non-alphanumeric
     * characters are stripped before validation; the remainder must be exactly 32
     * hex characters (case-insensitive). Returns `null` for anything else.
     */
    fun parseKey0(hex: String): BitVector? {
        val normalized = normalize(hex)
        if (normalized.length != 32 || !normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            return null
        }
        return normalized.decodeHex()
    }

    /** Strips separators and lowercases, without validating length or alphabet. */
    fun normalize(hex: String): String {
        return hex.filter { it.isLetterOrDigit() }.lowercase()
    }
}
