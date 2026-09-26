package de.stustapay.libssp.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

suspend fun downloadAndInstallUpdate(
    context: Context,
    downloadUrl: String,
    onProgress: (Float) -> Unit = {}
) {
    val apkFile = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        updatesDir.mkdirs()
        val apkFile = File(updatesDir, "update.apk")

        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("Update download failed: HTTP ${connection.responseCode}")
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L

        try {
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }

            // Integrity: refuse a truncated download instead of handing it to the installer.
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw IOException("Update download incomplete: $downloadedBytes of $totalBytes bytes")
            }
            if (downloadedBytes == 0L) {
                throw IOException("Update download empty")
            }
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        }
        apkFile
    }

    installApk(context, apkFile)
}

private fun installApk(context: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(intent)
}
