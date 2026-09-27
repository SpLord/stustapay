package de.stustapay.libssp.nfc

import java.io.IOException

/**
 * PWD_AUTH was NAKed via a short data answer (not exactly 2 bytes) instead of a transport
 * IOException. Some real readers surface a failed PWD_AUTH this way; treat it the same as any
 * other NAK (eligible for the legacy fallback / the "locked" heuristic), never as a PACK mismatch.
 */
class TagNakException(message: String) : IOException(message)
