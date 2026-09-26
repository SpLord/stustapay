package de.stustapay.libssp.update

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Fork release number parsed from a tag or `git describe` string such as
 * "v2026.2.1-pretix23", "v2026.2.1-pretix23-rc2" or "v2026.2.1-pretix23-2-gabcdef".
 * A release candidate sorts below the final release with the same number.
 */
data class ForkVersion(val pretix: Int, val rc: Int?) : Comparable<ForkVersion> {
    override fun compareTo(other: ForkVersion): Int {
        if (pretix != other.pretix) return pretix.compareTo(other.pretix)
        // final (rc == null) is newer than any rc of the same number
        return (rc ?: Int.MAX_VALUE).compareTo(other.rc ?: Int.MAX_VALUE)
    }

    companion object {
        private val PATTERN = Regex("""pretix(\d+)(?:-rc(\d+))?""")

        fun parse(version: String): ForkVersion? {
            val m = PATTERN.find(version) ?: return null
            val pretix = m.groupValues[1].toIntOrNull() ?: return null
            val rc = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return ForkVersion(pretix, rc)
        }
    }
}

/**
 * True if [remoteTag] is a strictly newer fork release than [currentVersion].
 * Unparsable versions on either side never yield an update.
 */
fun isNewerForkVersion(currentVersion: String, remoteTag: String): Boolean {
    val current = ForkVersion.parse(currentVersion) ?: return false
    val remote = ForkVersion.parse(remoteTag) ?: return false
    return remote > current
}

/**
 * Process-wide cache so that recompositions / re-entering the start page do not
 * hit the GitHub API every time. At most one request per [TTL_MS].
 */
object UpdateCheckCache {
    private const val TTL_MS = 30L * 60L * 1000L

    private val mutex = Mutex()
    private var cached: UpdateInfo? = null
    private var cachedApkName: String? = null
    private var checkedAt: Long = 0L

    suspend fun get(currentVersion: String, apkName: String): UpdateInfo {
        return mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val hit = cached
            if (hit != null &&
                hit.currentVersion == currentVersion &&
                cachedApkName == apkName &&
                now - checkedAt < TTL_MS
            ) {
                return@withLock hit
            }
            val fresh = checkForUpdate(currentVersion, apkName)
            cached = fresh
            cachedApkName = apkName
            checkedAt = now
            fresh
        }
    }
}

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

            // Numeric comparison of the fork release number (pretixNN, optional -rcM).
            // Only offer an update when the remote release is strictly newer; a tag we
            // cannot parse never triggers an update.
            val isUpdateAvailable = downloadUrl != null && isNewerForkVersion(currentVersion, tagName)

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
