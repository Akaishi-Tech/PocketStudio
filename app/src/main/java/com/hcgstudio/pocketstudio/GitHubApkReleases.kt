package com.hcgstudio.pocketstudio

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Select an APK from the app release using Android's ABI preference order. */
internal class GitHubApkReleases(context: Context, abis: List<String> = Build.SUPPORTED_ABIS.toList()) {
    private val preferences = context.getSharedPreferences("app-github-release", Context.MODE_PRIVATE)
    private val supportedAbis = abis.toList()

    private fun candidates(version: String) = supportedAbis.map { abi -> "PocketStudio-$version-$abi.apk" }
    private fun universalApk(version: String) = "PocketStudio-$version.apk"

    fun latest(): AppRelease? {
        val connection = connect(URL("https://api.github.com/repos/$REPO/releases/latest"))
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
            val entries = release.getJSONArray("assets")
            val assets = (0 until entries.length()).map(entries::getJSONObject).groupBy { it.getString("name") }
            val tag = TAG.matchEntire(release.getString("tag_name")) ?: throw invalidMetadata()
            val version = tag.groupValues[1]
            val marker = RELEASE_MARK.findAll(release.getString("body")).singleOrNull() ?: throw invalidMetadata()
            val code = marker.groupValues[1].toLongOrNull()?.takeIf { it in 1..2_100_000_000L } ?: throw invalidMetadata()
            val fileName = candidates(version).firstOrNull { it in assets }
                ?: universalApk(version).takeIf { assets.keys.none { name -> ABI_APK.matches(name) } }
                ?: throw invalidMetadata()
            val asset = assets[fileName]?.singleOrNull() ?: throw invalidMetadata()
            if (asset.getString("state") != "uploaded" ||
                asset.getString("browser_download_url") != downloadUrl(version, fileName)) throw invalidMetadata()
            val size = asset.getLong("size").takeIf { it in 1..MAX_APK } ?: throw invalidMetadata()
            val digest = asset.getString("digest").takeIf { HASH.matches(it) } ?: throw invalidMetadata()
            return AppRelease(version, code, size, digest.removePrefix("sha256:"), fileName)
        } catch (e: JSONException) { throw IOException(uiString(R.string.github_apk_invalid_release), e) }
    }

    fun download(release: AppRelease, destination: File, progress: (Long, Long) -> Unit) {
        val name = release.assetName ?: throw invalidMetadata()
        if (!TAG.matches("v${release.version}") || (name != universalApk(release.version) && name !in candidates(release.version)))
            throw invalidMetadata()
        downloadGitHubApk(downloadUrl(release.version, name), release, destination, progress)
    }

    private fun downloadUrl(version: String, fileName: String) = "https://github.com/$REPO/releases/download/v$version/$fileName"

    companion object {
        internal const val REPO = "Akaishi-Tech/PocketStudio"
        private const val VERSION = """(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"""
        private val TAG = Regex("v($VERSION)")
        private val ABI_APK = Regex("""PocketStudio-(?:$VERSION-)?(?:arm64-v8a|armeabi-v7a|x86_64|x86)\.apk""")
        private val RELEASE_MARK = Regex("""<!-- pocketstudio-release run=[0-9]+ versionCode=([0-9]+) -->""")
        private const val MAX_APK = 1_500_000_000L
        private val HASH = Regex("sha256:[0-9a-f]{64}")
        private fun invalidMetadata() = IOException(uiString(R.string.github_apk_invalid_release))
        internal fun connect(url: URL) = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PocketStudio")
            setRequestProperty("Accept-Encoding", "identity")
        }
        internal fun checkResponse(connection: HttpsURLConnection) {
            if (connection.responseCode != 200) throw IOException(uiString(R.string.github_apk_http_error, connection.responseCode))
        }
    }
}

/** Follow GitHub release asset redirects only to GitHub's HTTPS download hosts. */
internal fun <T> readGitHubAsset(address: String, read: (HttpsURLConnection) -> T): T {
    var url = URL(address)
    repeat(6) {
        val connection = GitHubApkReleases.connect(url)
        try {
            if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: throw releaseVerificationError()
                url = URL(url, location)
                if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
                    !(url.host == "github.com" || url.host.endsWith(".githubusercontent.com")))
                    throw releaseVerificationError()
            } else {
                GitHubApkReleases.checkResponse(connection)
                return read(connection)
            }
        } finally { connection.disconnect() }
    }
    throw releaseVerificationError()
}

internal fun downloadGitHubApk(
    address: String, release: AppRelease, destination: File, progress: (Long, Long) -> Unit
) = readGitHubAsset(address) { conn ->
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
}
