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
     * Detect chip type via GET_VERSION command, then dispatch to the right handler.
     */
    private fun detectChipType(tag: Tag): String {
        val nfca = NfcA.get(tag) ?: return "unknown"
        try {
            nfca.connect()
            val version = nfca.transceive(byteArrayOf(0x60))
            nfca.close()
            if (version != null && version.size >= 8) {
                val productType = version[2].toInt() and 0xFF
                return when (productType) {
                    0x03 -> "mf0aes"   // MIFARE Ultralight AES
                    0x04 -> "ntag"     // NTAG family (213/215/216)
                    else -> "unknown"
                }
            }
        } catch (e: Exception) {
            try { nfca.close() } catch (_: Exception) {}
            Log.d("NfcHandler", "Chip detection failed: ${e.message}")
        }
        return "unknown"
    }

    private fun handleTag(tag: Tag) {
        if (!tag.techList.contains("android.nfc.tech.NfcA")) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("device has no NfcA support")))
            return
        }

        val chipType = detectChipType(tag)
        Log.d("NfcHandler", "Detected chip: $chipType")

        try {
            when (chipType) {
                "ntag" -> {
                    val ntag = Ntag213(tag)
                    handleNtag213Tag(ntag)
                    ntag.close()
                }
                "mf0aes" -> {
                    val mfTag = MifareUltralightAES(tag)
                    handleMfUlAesTag(mfTag)
                    mfTag.close()
                }
                else -> {
                    // Unknown chip — try MF0AES first, fallback to NTAG213
                    try {
                        val mfTag = MifareUltralightAES(tag)
                        handleMfUlAesTag(mfTag)
                        mfTag.close()
                    } catch (e: TagIncompatibleException) {
                        Log.d("NfcHandler", "MF0AES failed, trying NTAG213")
                        val ntag = Ntag213(tag)
                        handleNtag213Tag(ntag)
                        ntag.close()
                    }
                }
            }
        } catch (e: TagLostException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Tag lost")))
        } catch (e: TagAuthException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth(e.message ?: "Auth failed")))
        } catch (e: TagIncompatibleException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible(e.message ?: "Unsupported chip")))
        } catch (e: TagConnectionException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Connection failed")))
        } catch (e: IOException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "IO error")))
        } catch (e: SecurityException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost(e.message ?: "Security error")))
        } catch (e: Exception) {
            e.printStackTrace()
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other(e.localizedMessage ?: "Unknown error")))
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
                tag.connect()
                val nfcTag = tag.readTag(req.dataProtKey, req.uidRetrKey)
                dataSource.setScanResult(NfcScanResult.Read(nfcTag))
            }
            is NfcScanRequest.Write -> {
                tag.connect()
                if (req.dataProtKey == null) {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth("Key required for NTAG213 write")))
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
                dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("Test not supported for NTAG213")))
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
