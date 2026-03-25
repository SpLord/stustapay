package de.stustapay.libssp.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.Bundle
import android.util.Log
import de.stustapay.libssp.model.NfcScanFailure
import de.stustapay.libssp.model.NfcScanRequest
import de.stustapay.libssp.model.NfcScanResult
import de.stustapay.libssp.util.BitVector
import de.stustapay.libssp.util.asBitVector
import de.stustapay.libssp.util.bv
import java.io.IOException
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class NfcHandler @Inject constructor(
    private val dataSource: NfcDataSource
) {
    private lateinit var device: NfcAdapter
    private lateinit var uid_map: Map<ULong, String>

    fun onCreate(activity: Activity, uid_map: Map<ULong, String>) {
        device = NfcAdapter.getDefaultAdapter(activity)
        this.uid_map = uid_map
    }

    fun onPause(activity: Activity) {
        device.disableReaderMode(activity)
    }

    fun onResume(activity: Activity) {
        device.enableReaderMode(
            activity,
            { tag -> handleTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
            }
        )
    }

    /**
     * Single-connection approach:
     * 1. Connect NfcA once
     * 2. GET_VERSION to detect chip type (stays connected)
     * 3. Dispatch to handler (reuses the open connection)
     * 4. Close once at the end
     *
     * This eliminates the tag-lost problem caused by disconnect+reconnect.
     */
    private fun handleTag(tag: Tag) {
        if (!tag.techList.contains("android.nfc.tech.NfcA")) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("NfcA nicht verfügbar")))
            return
        }

        try {
            // For NTAG chips: connect once, detect, read — all on same connection
            // For MF0AES: let it handle its own connection (complex crypto needs it)
            val nfca = NfcA.get(tag)
            if (nfca == null) {
                dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("NfcA nicht verfügbar")))
                return
            }

            nfca.connect()

            // Detect chip type — connection stays OPEN
            var chipType = "unknown"
            try {
                val version = nfca.transceive(byteArrayOf(0x60))
                if (version != null && version.size >= 8) {
                    chipType = when (version[2].toInt() and 0xFF) {
                        0x03 -> "mf0aes"
                        0x04 -> "ntag"
                        else -> "unknown"
                    }
                }
            } catch (e: Exception) {
                Log.d("NfcHandler", "GET_VERSION failed: ${e.message}")
            }

            Log.d("NfcHandler", "Detected chip: $chipType")

            when (chipType) {
                "ntag" -> {
                    // NTAG: reuse the OPEN connection — no reconnect!
                    val ntag = Ntag213(tag)
                    // ntag.connect() will skip because nfcaTag.isConnected == true
                    handleNtag213Tag(ntag)
                    ntag.close()
                }
                "mf0aes" -> {
                    // MF0AES needs its own connect for crypto setup
                    nfca.close()
                    val mfTag = MifareUltralightAES(tag)
                    handleMfUlAesTag(mfTag)
                    mfTag.close()
                }
                else -> {
                    // Unknown — try as NTAG (more common), reuse connection
                    try {
                        val ntag = Ntag213(tag)
                        handleNtag213Tag(ntag)
                        ntag.close()
                    } catch (e: Exception) {
                        Log.d("NfcHandler", "NTAG failed: ${e.message}, trying MF0AES")
                        try {
                            val mfTag = MifareUltralightAES(tag)
                            handleMfUlAesTag(mfTag)
                            mfTag.close()
                        } catch (e2: Exception) {
                            dataSource.setScanResult(NfcScanResult.Fail(
                                NfcScanFailure.Incompatible("Chip nicht unterstützt")))
                        }
                    }
                }
            }
        } catch (e: TagLostException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Band zu kurz gehalten — bitte nochmal scannen")))
        } catch (e: TagAuthException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth(e.message ?: "Authentifizierung fehlgeschlagen")))
        } catch (e: TagIncompatibleException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible(e.message ?: "Chip nicht unterstützt")))
        } catch (e: TagConnectionException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Verbindung verloren")))
        } catch (e: IOException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Verbindungsfehler — bitte nochmal scannen")))
        } catch (e: SecurityException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Sicherheitsfehler")))
        } catch (e: Exception) {
            e.printStackTrace()
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other(e.localizedMessage ?: "Unbekannter Fehler")))
        }
    }

    private fun handleMfUlAesTag(tag: MifareUltralightAES) {
        val req = dataSource.getScanRequest() ?: return
        when (req) {
            is NfcScanRequest.Read -> {
                tag.connect()
                dataSource.setScanResult(NfcScanResult.Read(tag.fastRead(req.uidRetrKey, req.dataProtKey)))
            }
            is NfcScanRequest.Write -> {
                tag.connect()
                authenticate(tag, true, true, req.dataProtKey!!)
                tag.setCMAC(true)
                tag.setAuth0(0x10u)
                tag.writeUserMemory("StuStaPay\n".toByteArray(Charset.forName("UTF-8")).asBitVector())
                tag.writePin("WWWWWWWWWWWW")
                tag.writeDataProtKey(req.dataProtKey)
                tag.writeUidRetrKey(req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Rewrite -> {
                tag.connect()
                tag.authenticate(req.dataProtKey, MifareUltralightAES.KeyType.DATA_PROT_KEY, true)
                val ser = tag.readSerialNumber()
                if (uid_map[ser] == null) {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("UID not found")))
                    return
                }
                tag.setCMAC(true)
                tag.writeDataProtKey(req.dataProtKey)
                tag.writeUidRetrKey(req.uidRetrKey)
                tag.writePin(uid_map[ser] + "\u0000\u0000\u0000\u0000")
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Test -> {
                val log = tag.test(req.dataProtKey, req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Test(log))
            }
        }
    }

    private fun handleNtag213Tag(tag: Ntag213) {
        val req = dataSource.getScanRequest() ?: return
        when (req) {
            is NfcScanRequest.Read -> {
                tag.connect()  // skips if already connected
                val nfcTag = tag.readTag(req.dataProtKey, req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Read(nfcTag))
            }
            is NfcScanRequest.Write -> {
                tag.connect()
                if (req.dataProtKey == null) {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth("Key required for write")))
                    return
                }
                tag.provisionTag("WWWWWWWWWWWWWWWW", req.dataProtKey, req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Rewrite -> {
                tag.connect()
                tag.writeTag("WWWWWWWWWWWWWWWW", req.dataProtKey, req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Test -> {
                dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("Test not supported for NTAG")))
            }
        }
    }

    private fun authenticate(
        tag: MifareUltralightAES,
        auth: Boolean,
        cmac: Boolean,
        key: BitVector
    ): Boolean {
        try {
            if (auth) {
                tag.authenticate(key, MifareUltralightAES.KeyType.DATA_PROT_KEY, cmac)
            }
        } catch (e: Exception) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth(e.message ?: "Auth error")))
            return false
        }
        return true
    }
}
