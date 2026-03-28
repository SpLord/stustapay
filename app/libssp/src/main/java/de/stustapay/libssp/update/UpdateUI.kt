package de.stustapay.libssp.update

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

@Composable
fun VersionWithUpdateCheck(
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        val info = updateInfo
        if (info != null && info.isUpdateAvailable) {
            Text(
                text = "${info.currentVersion} \u2192 ${info.latestVersion}",
                fontSize = 10.sp,
                color = Color(0xFF4CAF50),
                modifier = Modifier
                    .clickable(enabled = !isDownloading) {
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
                    }
                    .padding(4.dp)
            )
            if (isDownloading) {
                LinearProgressIndicator(
                    progress = downloadProgress,
                    modifier = Modifier.width(100.dp),
                    color = Color(0xFF4CAF50)
                )
            } else {
                Text(
                    text = "Tap to update",
                    fontSize = 8.sp,
                    color = Color(0xFF4CAF50).copy(alpha = 0.7f)
                )
            }
        } else {
            Text(
                text = currentVersion,
                fontSize = 10.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.25f),
            )
        }
    }
}
