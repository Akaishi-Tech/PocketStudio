package com.hcgstudio.pocketstudio

import android.content.Context
import com.hcgstudio.pocketstudio.codex.CodexRuntime
import com.hcgstudio.pocketstudio.codex.CodexReleases
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Opt-in tests. Download prepares a user-installable APK; the handshake uses the installed package. */
object CodexRuntimeChecks {
    @JvmStatic fun run(context: Context, scenario: String): String {
        val runtime = CodexRuntime(context)
        if (scenario == "codexVerifyRelease" || scenario == "codexOfflineInstall") {
            val fixture = File(context.cacheDir, "codex-release-test")
            val meta = JSONObject(File(fixture, "codex-plugin.json").readText())
            val name = "PocketStudio-Codex.apk"
            val url = "https://github.com/Akaishi-Tech/PocketStudio/releases/download/codex-runtime/$name"
            val asset = JSONObject().put("name", name).put("state", "uploaded")
                .put("label", "Codex ${meta.getString("version")} (${meta.getLong("versionCode")})")
                .put("size", meta.getLong("size")).put("browser_download_url", url)
                .put("digest", "sha256:${meta.getString("sha256")}")
            val document = JSONObject().put("tag_name", "codex-runtime").put("prerelease", false).put("draft", false)
                .put("assets", JSONArray().put(asset))
            val client = CodexReleases(context)
            val release = client.parse(document.toString())
            val apk = File(fixture, name)
            if (scenario == "codexOfflineInstall") {
                val cache = File(context.cacheDir, "install-apks/codex-runtime").apply { mkdirs() }
                val verified = File(cache, "${release.sha256}.apk")
                val created = !verified.exists()
                val prefs = context.getSharedPreferences("codex-github-release", Context.MODE_PRIVATE)
                val oldMetadata = prefs.getString("release", null)
                val oldEtag = prefs.getString("etag", null)
                try {
                    if (created) apk.copyTo(verified)
                    check(prefs.edit().putString("release", document.toString()).remove("etag").commit())
                    val chosen = runtime.releaseForInstall()
                    val intent = runtime.prepareInstaller(chosen)
                    check(chosen == release && intent.action == android.content.Intent.ACTION_VIEW)
                    check(context.contentResolver.openFileDescriptor(requireNotNull(intent.data), "r")!!.use { it.statSize } == release.size)
                    return "PASS reopened a verified cached release through Android's installer"
                } finally {
                    prefs.edit().putString("release", oldMetadata).putString("etag", oldEtag).commit()
                    if (created) verified.delete()
                }
            }
            runtime.verifyArchive(apk, release)
            check(runCatching { runtime.verifyArchive(apk, release.copy(versionCode = release.versionCode + 1)) }.isFailure)
            check(runCatching { runtime.verifyArchive(apk, release.copy(sha256 = "0".repeat(64))) }.isFailure)
            val other = File(context.applicationInfo.sourceDir)
            val otherRelease = release.copy(size = other.length(), sha256 = CodexReleases.digest(other))
            check(runCatching { runtime.verifyArchive(other, otherRelease) }.isFailure)
            asset.put("browser_download_url", "https://example.invalid/foreign.apk")
            check(runCatching { client.parse(document.toString()) }.isFailure)
            return "PASS signed GitHub APK, version, digest, and canonical asset URL checks"
        }
        if (scenario == "codexDownload") {
            val release = runtime.latestRelease()
            val installer = runtime.prepareInstaller(release)
            val uri = requireNotNull(installer.data)
            check(context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize } > 0)
            return "PASS verified Codex ${release.version} installer: $uri"
        }
        checkNotNull(runtime.installedVersion())
        val cwd = File(context.cacheDir, "codex-handshake").apply { mkdirs() }
        if (scenario == "codexNetwork") {
            // An explicitly invalid key exercises DNS, TLS and streamed errors without charging an account.
            val settings = listOf("model_provider=\"probe\"", "model_providers.probe.name=\"Probe\"",
                "model_providers.probe.base_url=\"https://api.openai.com/v1\"", "model_providers.probe.env_key=\"PROBE_KEY\"",
                "model_providers.probe.supports_websockets=false", "model_providers.probe.request_max_retries=0")
            val process = runtime.launch(cwd, mapOf("PROBE_KEY" to "invalid-test-credential"), settings.flatMap { listOf("-c", it) })
            CodexRpc(process).use { rpc ->
                runBlocking {
                    rpc.initialize()
                    val thread = rpc.request("thread/start", json("cwd" to cwd.path, "model" to "gpt-5",
                        "ephemeral" to true)).getJSONObject("thread").getString("id")
                    rpc.request("turn/start", json("threadId" to thread, "input" to JSONArray().put(json("type" to "text", "text" to "test"))))
                    withTimeout(45_000) {
                        while (true) {
                            val event = rpc.events.receive()
                            val params = event.getJSONObject("params")
                            val failure = when (event.string("method")) {
                                "error" -> if (params.optBoolean("willRetry")) null else params.getJSONObject("error")
                                "turn/completed" -> params.getJSONObject("turn").optJSONObject("error")
                                else -> null
                            }
                            if (failure != null) {
                                val detail = failure.toString()
                                check("401" in detail || "unauthorized" in detail.lowercase()) { detail }
                                break
                            }
                        }
                    }
                }
            }
            return "PASS OpenAI API rejected the deliberately invalid key over authenticated HTTPS"
        }
        val boot = runtime.launch(cwd)
        if (boot.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            error("Codex exited ${boot.exitValue()}: " + boot.errorStream.bufferedReader().readText().takeLast(1500))
        }
        CodexRpc(boot).use { rpc ->
            runBlocking {
                rpc.initialize()
                check(rpc.request("model/list").has("data"))
            }
        }
        return "PASS installed Codex ${runtime.installedVersion()} initialize + model/list"
    }
}
