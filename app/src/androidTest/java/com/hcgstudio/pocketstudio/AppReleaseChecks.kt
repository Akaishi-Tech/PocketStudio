package com.hcgstudio.pocketstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AppReleaseChecks {
    private const val version = "1.10.0"
    private const val url = "https://github.com/Akaishi-Tech/PocketStudio/releases/download/v1.10.0/PocketStudio.apk"
    private val hash = "ab".repeat(32)

    private fun releaseDocument(): JSONObject {
        val app = JSONObject().put("name", "PocketStudio.apk").put("state", "uploaded")
            .put("label", "Pocket Studio $version (18)").put("browser_download_url", url)
            .put("size", 245000000L).put("digest", "sha256:$hash")
        val runtime = JSONObject().put("name", "PocketStudio-Codex-Plugin.apk").put("state", "uploaded")
            .put("label", "Codex 0.170.0 (1700000)")
            .put("browser_download_url", url.replace("PocketStudio.apk", "PocketStudio-Codex-Plugin.apk"))
            .put("size", 200000000L).put("digest", "sha256:" + "cd".repeat(32))
        return JSONObject().put("tag_name", "v$version").put("draft", false).put("prerelease", false)
            .put("body", "<!-- pocketstudio-release run=123 versionCode=18 -->")
            .put("assets", JSONArray().put(runtime).put(app))
    }

    @JvmStatic fun run(context: Context, verifyArchive: Boolean) {
        val client = AppReleases(context)
        val release = client.parse(releaseDocument().toString())
        check(release == AppRelease(version, 18L, 245000000L, hash, "PocketStudio.apk"))

        val split = releaseDocument().apply {
            val files = getJSONArray("assets")
            val legacy = files.remove(1) as JSONObject
            for (abi in listOf("x86", "arm64-v8a", "x86_64")) {
                val name = "PocketStudio-$abi.apk"
                files.put(JSONObject(legacy.toString()).put("name", name)
                    .put("browser_download_url", url.substringBeforeLast('/') + "/" + name))
            }
        }
        fun selected(vararg abis: String) = GitHubApkReleases(context, abis.toList()).parse(split.toString()).assetName
        check(selected("x86_64", "x86") == "PocketStudio-x86_64.apk")
        check(selected("armeabi-v7a", "x86") == "PocketStudio-x86.apk")
        check(runCatching { selected("riscv64") }.isFailure)
        val files = split.getJSONArray("assets")
        files.put(files.getJSONObject(1))
        check(runCatching { selected("x86") }.isFailure)

        fun reject(reason: String, mutate: (JSONObject, JSONObject) -> Unit) {
            val document = releaseDocument()
            mutate(document, document.getJSONArray("assets").getJSONObject(1))
            check(runCatching { client.parse(document.toString()) }.isFailure) { "Accepted $reason" }
        }
        reject("plugin store channel") { doc, _ -> doc.put("tag_name", "plugin-store") }
        reject("draft release") { doc, _ -> doc.put("draft", true) }
        reject("prerelease") { doc, _ -> doc.put("prerelease", true) }
        reject("missing app asset") { doc, _ -> doc.getJSONArray("assets").remove(1) }
        reject("ambiguous app asset") { doc, app -> doc.getJSONArray("assets").put(app) }
        reject("incomplete asset") { _, app -> app.put("state", "open") }
        reject("version different from tag") { _, app -> app.put("label", "Pocket Studio 1.9.0 (18)") }
        reject("invalid version code") { _, app -> app.put("label", "Pocket Studio $version (0)") }
        reject("invalid size") { _, app -> app.put("size", 0) }
        reject("invalid digest") { _, app -> app.put("digest", "sha256:abcd") }
        reject("different release URL") { _, app -> app.put("browser_download_url", url.replace("v1.10.0", "v1.9.0")) }

        val installed = packageVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        val error = runCatching { client.prepareInstaller(release.copy(versionCode = installed)) { _, _ -> } }.exceptionOrNull()
        check(error?.message == context.getString(R.string.app_release_already_installed))

        if (verifyArchive) {
            // Supply a matching GitHub API response and release APK in the app's test cache.
            val dir = File(context.cacheDir, "app-release-test")
            val published = client.parse(File(dir, "release.json").readText())
            val apk = File(dir, requireNotNull(published.assetName))
            client.verifyArchive(apk, published)
            check(runCatching { client.verifyArchive(apk, published.copy(versionCode = published.versionCode + 1)) }.isFailure)
            check(runCatching { client.verifyArchive(apk, published.copy(sha256 = "0".repeat(64))) }.isFailure)
        }
    }
}
