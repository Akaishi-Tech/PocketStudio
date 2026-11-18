package com.hcgstudio.pocketstudio.codex

import android.content.Context
import com.hcgstudio.pocketstudio.R
import com.hcgstudio.pocketstudio.uiString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class CodexRelease(val version: String, val versionCode: Long, val size: Long, val sha256: String)

/** The fixed release tag is an update channel; the asset digest identifies the exact download. */
internal class CodexReleases(context: Context) {
    private val preferences = context.getSharedPreferences("codex-github-release", Context.MODE_PRIVATE)

    fun latest(): CodexRelease {
        val connection = connect(URL(API_URL))
        try {
            val cached = preferences.getString("release", null)
            if (cached != null) preferences.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode == 304 && cached != null) return parse(cached)
            if (connection.responseCode == 404) throw IOException(uiString(R.string.codex_release_not_published))
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
            preferences.edit().putString("etag", connection.getHeaderField("ETag")).putString("release", json).apply()
            return value
        } finally { connection.disconnect() }
    }

    fun cached(): CodexRelease? = preferences.getString("release", null)?.let { runCatching { parse(it) }.getOrNull() }

    internal fun parse(body: String): CodexRelease {
        val release = JSONObject(body)
        if (release.getString("tag_name") != TAG || release.getBoolean("draft") || release.getBoolean("prerelease"))
            throw invalidMetadata()
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map(assets::getJSONObject).singleOrNull { it.getString("name") == FILE_NAME }
            ?: throw invalidMetadata()
        if (asset.optString("state") != "uploaded" || asset.getString("browser_download_url") != DOWNLOAD_URL) throw invalidMetadata()
        val label = LABEL.matchEntire(asset.optString("label")) ?: throw invalidMetadata()
        val code = label.groupValues[2].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE } ?: throw invalidMetadata()
        val size = asset.getLong("size").takeIf { it in 1..MAX_APK } ?: throw invalidMetadata()
        val digest = asset.getString("digest").takeIf { HASH.matches(it) } ?: throw invalidMetadata()
        return CodexRelease(label.groupValues[1], code, size, digest.removePrefix("sha256:"))
    }

    fun download(release: CodexRelease, destination: File, progress: (Long, Long) -> Unit) {
        var url = URL(DOWNLOAD_URL)
        repeat(6) {
            val conn = connect(url)
            try {
                if (conn.responseCode in setOf(301, 302, 303, 307, 308)) {
                    url = URL(url, conn.getHeaderField("Location") ?: throw invalidMetadata())
                    if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
                        !(url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))) throw invalidMetadata()
                } else {
                    checkResponse(conn)
                    if (conn.contentLengthLong >= 0 && conn.contentLengthLong != release.size) throw verificationError()
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
                                if (total > release.size) throw verificationError()
                                out.write(buffer, 0, n)
                                if (total - reported >= 512 * 1024) { progress(total, release.size); reported = total }
                            }
                            if (total != release.size) throw verificationError()
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

    companion object {
        private const val TAG = "codex-runtime"
        private const val REPO = "Akaishi-Tech/PocketStudio"
        internal const val FILE_NAME = "PocketStudio-Codex.apk"
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/tags/$TAG"
        private const val DOWNLOAD_URL = "https://github.com/$REPO/releases/download/$TAG/$FILE_NAME"
        private const val MAX_APK = 1_500_000_000L
        private val LABEL = Regex("""Codex ([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9]+)?) \(([0-9]+)\)""")
        private val HASH = Regex("sha256:[0-9a-f]{64}")
        private fun invalidMetadata() = IOException(uiString(R.string.codex_invalid_release))
        fun verificationError() = IOException(uiString(R.string.codex_download_verification_failed_try_again))
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun connect(url: URL) = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PocketStudio-Codex-Runtime")
            setRequestProperty("Accept-Encoding", "identity")
        }
        private fun checkResponse(connection: HttpsURLConnection) {
            if (connection.responseCode != 200) throw IOException(uiString(R.string.codex_github_release_error, connection.responseCode))
        }
    }
}
