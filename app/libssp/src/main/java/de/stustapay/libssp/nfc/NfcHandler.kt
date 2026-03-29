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
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
            }
        )
    }

    /**
     * No-probe approach: try NTAG213 first (most common), fall back to MF0AES.
     * NTAG213 uses the same NfcA object — connect once, read once, done.
     */
    private fun handleTag(tag: Tag) {
        if (!tag.techList.contains("android.nfc.tech.NfcA")) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("NfcA nicht verfügbar")))
            return
        }

        try {
            // Try NTAG213 first — single connect, no probe
            val nfca = NfcA.get(tag)
            try {
                nfca.connect()
                val ntag = Ntag213(nfca)
                handleNtag213Tag(ntag)
                ntag.close()
                return
            } catch (e: TagIncompatibleException) {
                Log.d("NfcHandler", "Not NTAG, trying MF0AES: ${e.message}")
                try { nfca.close() } catch (_: Exception) {}
            } catch (e: TagAuthException) {
                try { nfca.close() } catch (_: Exception) {}
                throw e
            }

            // Fallback: MF0AES
            val mfTag = MifareUltralightAES(tag)
            handleMfUlAesTag(mfTag)
            mfTag.close()

        } catch (e: TagLostException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost("Band zu kurz gehalten")))
        } catch (e: TagAuthException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth("Authentifizierung fehlgeschlagen")))
        } catch (e: TagIncompatibleException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("Chip nicht unterstützt")))
        } catch (e: TagConnectionException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost("Verbindung verloren")))
        } catch (e: IOException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost("Bitte nochmal scannen")))
        } catch (e: Exception) {
            e.printStackTrace()
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other(e.localizedMessage ?: "Fehler")))
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
                tag.writePin(req.pin ?: "WWWWWWWWWWWWWWWW")
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
                tag.connect()
                val nfcTag = tag.readTag(req.dataProtKey, req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Read(nfcTag))
            }
            is NfcScanRequest.Write -> {
                tag.connect()
                if (req.dataProtKey == null) {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth("Key required for write")))
                    return
                }
                tag.provisionTag(req.pin ?: "WWWWWWWWWWWWWWWW", req.dataProtKey, req.uidRetrKey)
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
