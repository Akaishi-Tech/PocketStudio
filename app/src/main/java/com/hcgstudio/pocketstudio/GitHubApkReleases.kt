package com.hcgstudio.pocketstudio

import android.content.Context
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Both Android packages use GitHub's asset digest and the version label set by release.py. */
internal class GitHubApkReleases(
    context: Context,
    preferenceName: String,
    private val endpoint: String,
    private val fileName: String,
    private val label: Regex,
    private val tagForVersion: (String) -> String
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun latest(): AppRelease? {
        val connection = connect(URL("https://api.github.com/repos/$REPO/releases/$endpoint"))
        try {
            val cached = preferences.getString("release", null)
            if (cached != null) preferences.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode == 304 && cached != null) return parse(cached)
            if (connection.responseCode == 404) return null
            checkResponse(connection)
            val json = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (output.size() + n > 2_000_000) throw invalidMetadata()
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
            val value = parse(json)
            preferences.edit {
                putString("etag", connection.getHeaderField("ETag"))
                putString("release", json)
            }
            return value
        } finally { connection.disconnect() }
    }

    fun cached(): AppRelease? = preferences.getString("release", null)?.let { runCatching { parse(it) }.getOrNull() }

    fun parse(body: String): AppRelease {
        try {
            val release = JSONObject(body)
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) throw invalidMetadata()
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map(assets::getJSONObject).singleOrNull { it.getString("name") == fileName }
                ?: throw invalidMetadata()
            val match = label.matchEntire(asset.getString("label")) ?: throw invalidMetadata()
            val version = match.groupValues[1]
            val code = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..2_100_000_000L } ?: throw invalidMetadata()
            if (release.getString("tag_name") != tagForVersion(version) || asset.getString("state") != "uploaded" ||
                asset.getString("browser_download_url") != downloadUrl(version)) throw invalidMetadata()
            val size = asset.getLong("size").takeIf { it in 1..MAX_APK } ?: throw invalidMetadata()
            val digest = asset.getString("digest").takeIf { HASH.matches(it) } ?: throw invalidMetadata()
            return AppRelease(version, code, size, digest.removePrefix("sha256:"))
        } catch (e: JSONException) { throw IOException(uiString(R.string.github_apk_invalid_release), e) }
    }

    fun download(release: AppRelease, destination: File, progress: (Long, Long) -> Unit) {
        var url = URL(downloadUrl(release.version))
        repeat(6) {
            val conn = connect(url)
            try {
                if (conn.responseCode in setOf(301, 302, 303, 307, 308)) {
                    url = URL(url, conn.getHeaderField("Location") ?: throw invalidMetadata())
                    if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
                        !(url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))) throw invalidMetadata()
                } else {
                    checkResponse(conn)
                    if (conn.contentLengthLong >= 0 && conn.contentLengthLong != release.size) throw releaseVerificationError()
                    var total = 0L
                    var reported = 0L
                    conn.inputStream.use { input ->
                        destination.outputStream().use { out ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > release.size) throw releaseVerificationError()
                                out.write(buffer, 0, n)
                                if (total - reported >= 512 * 1024) { progress(total, release.size); reported = total }
                            }
                            if (total != release.size) throw releaseVerificationError()
                            out.fd.sync()
                        }
                    }
                    progress(total, total)
                    return
                }
            } finally { conn.disconnect() }
        }
        throw invalidMetadata()
    }

    private fun downloadUrl(version: String) = "https://github.com/$REPO/releases/download/${tagForVersion(version)}/$fileName"

    companion object {
        private const val REPO = "Akaishi-Tech/PocketStudio"
        private const val MAX_APK = 1_500_000_000L
        private val HASH = Regex("sha256:[0-9a-f]{64}")
        private fun invalidMetadata() = IOException(uiString(R.string.github_apk_invalid_release))
        private fun connect(url: URL) = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PocketStudio")
            setRequestProperty("Accept-Encoding", "identity")
        }
        private fun checkResponse(connection: HttpsURLConnection) {
            if (connection.responseCode != 200) throw IOException(uiString(R.string.github_apk_http_error, connection.responseCode))
        }
    }
}
