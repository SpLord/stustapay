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
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1500)
            }
        )
    }

    /**
     * Single-connection dispatch:
     * 1. Connect NfcA once.
     * 2. GET_VERSION (0x60) on that connection to identify the chip.
     * 3. NTAG213 -> reuse the already open NfcA (no second connect, that caused tag-lost).
     *    MF0AES  -> close our NfcA and hand the tag to MifareUltralightAES, which needs its
     *               own connection for the crypto session (and re-validates GET_VERSION).
     *    Anything else -> TagIncompatibleException.
     *
     * Dispatching on GET_VERSION is what enforces AES auth for MF0AES bands: without it an
     * MF0AES band would be read via the plain NTAG path and bypass authentication.
     */
    private fun handleTag(tag: Tag) {
        if (!tag.techList.contains("android.nfc.tech.NfcA")) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("NfcA nicht verfügbar")))
            return
        }

        val nfca: NfcA? = NfcA.get(tag)
        if (nfca == null) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("NfcA nicht verfügbar")))
            return
        }

        // set once the NfcA connection has been closed and ownership passed to MF0AES
        var handedOver = false

        try {
            nfca.connect()

            val version = nfca.transceive(byteArrayOf(0x60))
            Log.d("NfcHandler", "GET_VERSION: ${version?.joinToString(" ") { "%02X".format(it) }}")

            when {
                Ntag213.matchesVersion(version) -> {
                    val ntag = Ntag213(nfca)
                    handleNtag213Tag(ntag)
                }

                version != null && version.size >= 8 && version[2] == 0x03.toByte() -> {
                    // MF0AES: needs its own NfcA instance -> release ours first
                    nfca.close()
                    handedOver = true
                    val mfTag = MifareUltralightAES(tag)
                    handleMfUlAesTag(mfTag)
                }

                else -> {
                    throw TagIncompatibleException("unknown GET_VERSION response")
                }
            }
        } catch (e: TagLostException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost("Band zu kurz gehalten")))
        } catch (e: TagAuthException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth("Band nicht für dieses Event provisioniert oder gesperrt")))
        } catch (e: TagLockedException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Locked("Band gesperrt — bitte an der Kasse tauschen")))
        } catch (e: TagIncompatibleException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Incompatible("Chip nicht unterstützt")))
        } catch (e: TagConnectionException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost("Verbindung verloren")))
        } catch (e: TagNakException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Auth("Band nicht für dieses Event provisioniert oder gesperrt")))
        } catch (e: IOException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Lost("Bitte nochmal scannen")))
        } catch (e: IllegalArgumentException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other(e.message ?: "Ungültige Eingabe")))
        } catch (e: Exception) {
            e.printStackTrace()
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other(e.localizedMessage ?: "Fehler")))
        } finally {
            // always release the NfcA we opened (unless MF0AES took it over and closes itself)
            if (!handedOver) {
                try { nfca.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleMfUlAesTag(tag: MifareUltralightAES) {
        val req = dataSource.getScanRequest() ?: return
        try {
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
                is NfcScanRequest.Status -> {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other(NfcScanFailure.STATUS_NTAG_ONLY)))
                }
            }
        } finally {
            try { tag.close() } catch (_: Exception) {}
        }
    }

    private fun handleNtag213Tag(tag: Ntag213) {
        val req = dataSource.getScanRequest() ?: return
        tag.connect()
        when (req) {
            is NfcScanRequest.Read -> {
                val key0 = req.dataProtKey ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.NoKey))
                    return
                }
                val r = tag.readTag(key0)
                if (r.legacy) {
                    Log.w("NfcHandler", "legacy NTAG213 band (uid ${r.tag.uid.toString(16).take(6)}…) — bitte neu provisionieren")
                }
                dataSource.setScanResult(NfcScanResult.Read(r.tag, legacy = r.legacy))
            }
            is NfcScanRequest.Write -> {
                val key0 = req.dataProtKey ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.NoKey))
                    return
                }
                val pin = req.pin ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("PIN required for NTAG213")))
                    return
                }
                tag.provisionTag(pin, key0)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Rewrite -> {
                val ser = tag.readUid()
                val pin = uid_map[ser] ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("UID not found")))
                    return
                }
                tag.writeTag(pin, req.dataProtKey)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Test -> {
                dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("Test not supported for NTAG")))
            }
            is NfcScanRequest.Status -> {
                val r = tag.readTag(req.dataProtKey)
                val s = tag.readStatus(req.dataProtKey)
                dataSource.setScanResult(
                    NfcScanResult.Status(
                        uid = tag.readUid(),
                        pin = r.tag.pin,
                        auth0 = s.auth0,
                        prot = s.prot,
                        authLim = s.authLim,
                        legacy = r.legacy
                    )
                )
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
