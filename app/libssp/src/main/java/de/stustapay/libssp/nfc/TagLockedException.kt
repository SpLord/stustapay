package de.stustapay.libssp.nfc

/** PWD_AUTH refused although the password is right: AUTHLIM reached, band is permanently locked. */
class TagLockedException(message: String) : Exception(message)
