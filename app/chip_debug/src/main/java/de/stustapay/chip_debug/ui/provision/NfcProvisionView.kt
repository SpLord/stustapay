package de.stustapay.chip_debug.ui.provision

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.stustapay.chip_debug.ui.nav.NavScaffold
import de.stustapay.chip_debug.ui.write.NfcDebugScanResult
import de.stustapay.libssp.model.NfcScanFailure
import kotlinx.coroutines.launch

@Composable
fun NfcProvisionView(
    navigateBack: () -> Unit,
    viewModel: NfcProvisionViewModel = hiltViewModel()
) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val vibrator = LocalContext.current.getSystemService(Vibrator::class.java)
    var pinInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    NavScaffold(
        title = { Text("Provision Band") },
        navigateBack = {
            viewModel.stop()
            navigateBack()
        }
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PIN eingeben und Band scannen",
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                label = { Text("PIN (max. 16 Zeichen)") },
                value = pinInput,
                onValueChange = { if (it.length <= 16) pinInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        viewModel.provisionWithPin(pinInput, vibrator)
                    }
                },
                enabled = pinInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Band beschreiben", fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Schreibt PIN + Passwort + Schutz auf NTAG213",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Ergebnis", fontSize = 24.sp)
            Spacer(modifier = Modifier.height(8.dp))

            when (val r = result) {
                is NfcDebugScanResult.None -> Text("Noch kein Ergebnis")
                is NfcDebugScanResult.WriteSuccess -> Text(
                    "✓ Band beschrieben mit PIN: $pinInput",
                    color = Color(0xFF4CAF50),
                    fontSize = 18.sp
                )
                is NfcDebugScanResult.Failure -> {
                    when (val reason = r.reason) {
                        is NfcScanFailure.NoKey -> Text("Kein Secret-Key vorhanden", color = Color.Red)
                        is NfcScanFailure.Other -> Text("Fehler: ${reason.msg}", color = Color.Red)
                        is NfcScanFailure.Incompatible -> Text("Chip nicht kompatibel", color = Color.Red)
                        is NfcScanFailure.Lost -> Text("Band zu kurz gehalten", color = Color.Red)
                        is NfcScanFailure.Auth -> Text("Authentifizierung fehlgeschlagen", color = Color.Red)
                    }
                }
            }
        }
    }
}
