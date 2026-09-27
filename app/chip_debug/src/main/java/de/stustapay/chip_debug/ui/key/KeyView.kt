package de.stustapay.chip_debug.ui.key

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.stustapay.chip_debug.ui.nav.NavScaffold

@Composable
fun KeyView(navigateBack: () -> Unit, viewModel: KeyViewModel = hiltViewModel()) {
    val fingerprint by viewModel.fingerprint.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    NavScaffold(
        title = { Text("Schlüssel") },
        navigateBack = navigateBack,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = "Event-Schlüssel (key0)",
                fontSize = 20.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                label = { Text("Schlüssel (32 Hex-Zeichen)") },
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            )

            TextButton(onClick = { showKey = !showKey }) {
                Text(if (showKey) "Verbergen" else "Anzeigen")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                Button(onClick = {
                    if (viewModel.save(keyInput)) {
                        keyInput = ""
                    }
                }) {
                    Text("Speichern")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(onClick = {
                    viewModel.delete()
                    keyInput = ""
                }) {
                    Text("Löschen")
                }
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = Color.Red)
            }

            if (!viewModel.storageAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Schlüsselspeicher nicht verfügbar — Schlüssel gilt nur bis zum App-Neustart",
                    color = Color.Red,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            val fp = fingerprint
            Text(
                text = if (fp != null) {
                    "Schlüssel gesetzt: ja (Fingerprint $fp…)"
                } else {
                    "Schlüssel gesetzt: nein"
                }
            )
        }
    }
}
