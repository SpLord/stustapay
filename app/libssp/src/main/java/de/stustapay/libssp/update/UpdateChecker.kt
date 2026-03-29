package de.stustapay.libssp.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String?,
    val isUpdateAvailable: Boolean
)

suspend fun checkForUpdate(currentVersion: String, apkName: String): UpdateInfo {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/SpLord/stustapay/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode != 200) {
                return@withContext UpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                    downloadUrl = null,
                    isUpdateAvailable = false
                )
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)

            val tagName = json.getString("tag_name")
            val assets = json.getJSONArray("assets")

            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == apkName) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }

            // Compare: update available if latest tag is not contained in current version
            // Handles git describe formats like "v2026.2.1-pretix23-2-gabcdef"
            val isUpdateAvailable = !currentVersion.startsWith(tagName) && downloadUrl != null

            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = tagName,
                downloadUrl = downloadUrl,
                isUpdateAvailable = isUpdateAvailable
            )
        } catch (e: Exception) {
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                downloadUrl = null,
                isUpdateAvailable = false
            )
        }
    }
}
