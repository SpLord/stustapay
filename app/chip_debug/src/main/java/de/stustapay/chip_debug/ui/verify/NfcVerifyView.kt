package de.stustapay.chip_debug.ui.verify

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.stustapay.chip_debug.ui.nav.NavScaffold
import de.stustapay.libssp.model.NfcScanFailure

/** A fully-provisioned, current-generation NTAG213 band. */
private fun isFullyProtected(s: NfcDebugScanResult.StatusSuccess): Boolean =
    !s.legacy && s.prot && s.authLim == 3 && s.auth0 == 4

@Composable
fun NfcVerifyView(navigateBack: () -> Unit, viewModel: NfcVerifyViewModel = hiltViewModel()) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val vibrator = LocalContext.current.getSystemService(Vibrator::class.java)

    LaunchedEffect(null) {
        viewModel.scan(vibrator)
    }

    NavScaffold(
        title = {
            Text("Verify")
        },
        navigateBack = {
            viewModel.stop()
            navigateBack()
        }
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(20.dp)
            ) {
                Box(Modifier.size(300.dp, 300.dp)) {
                    Card(modifier = Modifier.padding(20.dp)) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Scan a Chip!", textAlign = TextAlign.Center, fontSize = 48.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Results", fontSize = 24.sp)

            Column(
                horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()
            ) {
                when (val r = result) {
                    is NfcDebugScanResult.None -> Text("No results yet")

                    // MIFARE-Ultralight AES band: unchanged legacy verify display.
                    is NfcDebugScanResult.ReadSuccess -> {
                        Text("PIN: ${r.tag.pin}")
                        Text("UID: ${r.tag.uid.toString(16)}")
                    }

                    // NTAG213 band: protection status, never the PIN itself.
                    is NfcDebugScanResult.StatusSuccess -> {
                        val ok = isFullyProtected(r)
                        val color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)
                        Text("UID: ${r.uid.toString(16).uppercase()}", color = color)
                        Text("PIN vorhanden: ${if (r.hasPin) "ja" else "nein"}", color = color)
                        Text("Leseschutz: ${if (r.prot) "AN" else "AUS"}", color = color)
                        Text("Fehlversuchslimit: ${if (r.authLim == 3) "3" else "aus"}", color = color)
                        Text("AUTH0: ${r.auth0}", color = color)
                        Text(
                            "Band-Version: ${if (r.legacy) "veraltet (Legacy)" else "aktuell"}",
                            color = color
                        )
                        if (!ok) {
                            Text(
                                "Band veraltet — bitte neu provisionieren",
                                color = Color(0xFFC62828)
                            )
                        }
                    }

                    is NfcDebugScanResult.Failure -> {
                        when (val reason = r.reason) {
                            is NfcScanFailure.NoKey -> Text("Kein Schlüssel eingetragen — bitte unter „Schlüssel“ setzen")
                            is NfcScanFailure.Other -> Text("Failure: ${reason.msg}")
                            is NfcScanFailure.Incompatible -> Text("Tag incompatible")
                            is NfcScanFailure.Lost -> Text("Tag lost")
                            is NfcScanFailure.Auth -> Text("Band gehört nicht zu diesem Schlüssel — prüfe den eingetragenen Schlüssel oder provisioniere das Band neu")
                            is NfcScanFailure.Locked -> Text("Gesperrt: ${reason.msg}")
                        }
                    }
                }
            }
        }
    }
}
