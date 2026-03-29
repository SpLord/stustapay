package de.stustapay.libssp.update

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Small version label for bottom-right corner.
 */
@Composable
fun VersionLabel(
    currentVersion: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = currentVersion,
        fontSize = 10.sp,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.25f),
        modifier = modifier
    )
}

/**
 * Update banner — shows as a full-width button when an update is available.
 * Place this in the button list, not overlapping other elements.
 */
@Composable
fun UpdateBanner(
    currentVersion: String,
    apkName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        updateInfo = try {
            checkForUpdate(currentVersion, apkName)
        } catch (e: Exception) {
            Log.w("UpdateCheck", "Failed to check for updates", e)
            null
        }
    }

    val info = updateInfo
    if (info != null && info.isUpdateAvailable) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (!isDownloading && info.downloadUrl != null) {
                        isDownloading = true
                        scope.launch {
                            try {
                                downloadAndInstallUpdate(
                                    context = context,
                                    downloadUrl = info.downloadUrl,
                                    onProgress = { downloadProgress = it }
                                )
                            } catch (e: Exception) {
                                Log.e("UpdateCheck", "Failed to download update", e)
                            } finally {
                                isDownloading = false
                            }
                        }
                    }
                },
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF4CAF50),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Updaten",
                    fontSize = 16.sp
                )
            }
            if (isDownloading) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = downloadProgress,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}
