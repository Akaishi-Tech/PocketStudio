package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

object PluginStoreChecks {
    @JvmStatic fun catalog(context: Context): String {
        val store = PluginStore(context)
        val cert = context.resources.openRawResource(R.raw.release_signer).use { it.readBytes() }
        val signer = java.security.MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        val catalog = """{"schemaVersion":2,"plugins":[{"package":"com.hcgstudio.pocketstudio.sample","category":"agent",
            "name":"Sample","developer":"Sample tools","description":"An example agent.","icon":"Sample.png",
            "version":"1.0","versionCode":1,"signerSha256":"$signer","artifacts":[
            {"abi":"x86_64","file":"Sample-x86_64.apk","sha256":"${"1".repeat(64)}","size":1234},
            {"abi":"arm64-v8a","file":"Sample-arm64-v8a.apk","sha256":"${"2".repeat(64)}","size":4567}]}]}"""
        val plugin = store.parse(catalog).single()
        check(plugin.category == "agent" && plugin.developer == "Sample tools" && plugin.description == "An example agent.")
        check(plugin.icon == "Sample.png")
        check(plugin.artifact(listOf("arm64-v8a", "x86_64"))?.size == 4567L)
        check(plugin.artifact(listOf("x86", "x86_64"))?.file == "Sample-x86_64.apk")
        check(plugin.artifact(listOf("armeabi-v7a")) == null)
        check(runCatching { store.parse(catalog.replace("Sample.png", "../Sample.png")) }.isFailure)
        check(runCatching { store.parse(catalog.replace("arm64-v8a", "x86_64")) }.isFailure)
        check(runCatching { store.parse(catalog.replace("arm64-v8a", "universal")) }.isFailure)
        return "PASS plugin catalog metadata, asset names and ABI selection"
    }

    /** The test emulator exposes its own adbd on the supplied loopback port. */
    @JvmStatic fun installLocally(context: Context, port: Int): String = runBlocking {
        val fixture = File(context.cacheDir, "codex-release-test")
        val store = PluginStore(context)
        val plugin = store.parse(File(fixture, "plugin-store.json").readText()).single()
        val artifact = checkNotNull(plugin.artifact())
        val release = plugin.release(artifact)
        val apk = File(fixture, artifact.file)
        ReleaseApkInstaller(context, plugin.packageName, plugin.packageName).verifyArchive(apk, release)
        withContext(Dispatchers.Main) {
            DeviceController(context).use { devices ->
                if (port == 5555) {
                    context.getSharedPreferences("studio", Context.MODE_PRIVATE).edit().remove("localAdbTcpPort").apply()
                    check(devices.installPluginLocally(apk))
                }
                devices.network(DeviceController.Connection.TCP)
                (devices.dialog as DeviceDialog.Form).submit(listOf("127.0.0.1", port.toString()))
                withTimeout(20_000) { while (!devices.connected || devices.busy) delay(100) }
                check(devices.installPluginLocally(apk))
                devices.disconnect()
                check(devices.installPluginLocally(apk))
            }
        }
        val installed = store.installed(emptyList())[plugin.packageName]
        check(installed?.trusted == true && installed.versionCode == plugin.versionCode)
        "PASS installed through unconfigured local adbd, active connection and reconnection"
    }

    @JvmStatic fun run(context: Context, scenario: String): String {
        val store = PluginStore(context)
        if (scenario == "codexDownload") {
            val plugin = store.catalog().single { it.packageName == "com.hcgstudio.pocketstudio.codexplugin" }
            val apk = store.download(plugin) { _, _ -> }
            check(apk.length() == checkNotNull(plugin.artifact()).size)
            return "PASS downloaded and verified plugin from the store"
        }
        val fixture = File(context.cacheDir, "codex-release-test")
        val catalog = File(fixture, "plugin-store.json").readText()
        val plugin = store.parse(catalog).single { it.packageName == "com.hcgstudio.pocketstudio.codexplugin" }
        val artifact = checkNotNull(plugin.artifact())
        val release = plugin.release(artifact)
        val apk = File(fixture, artifact.file)
        val installer = ReleaseApkInstaller(context, plugin.packageName, plugin.packageName)

        if (scenario == "codexOfflineInstall") {
            val staged = installer.staged(release)
            val created = !staged.exists()
            val prefs = context.getSharedPreferences("plugin-store", Context.MODE_PRIVATE)
            val previous = prefs.getString("catalog", null)
            try {
                staged.parentFile!!.mkdirs()
                if (created) apk.copyTo(staged)
                check(prefs.edit().putString("catalog", catalog).commit())
                check(plugin in store.cached())
                val verified = installer.prepareFile(release, { _, _ -> }) { error("Unexpected network download") }
                val intent = installer.installerIntent(verified)
                check(intent.action == Intent.ACTION_VIEW)
                check(context.contentResolver.openFileDescriptor(requireNotNull(intent.data), "r")!!.use { it.statSize } == release.size)
                return "PASS reopened verified plugin from cached catalog and APK"
            } finally {
                prefs.edit().putString("catalog", previous).commit()
                if (created) staged.delete()
            }
        }

        installer.verifyArchive(apk, release)
        check(runCatching { installer.verifyArchive(apk, release.copy(versionCode = release.versionCode + 1)) }.isFailure)
        check(runCatching { installer.verifyArchive(apk, release.copy(sha256 = "0".repeat(64))) }.isFailure)
        val foreignPackage = ReleaseApkInstaller(context, context.packageName, "plugin-store-test")
        check(runCatching { foreignPackage.verifyArchive(apk, release) }.isFailure)
        for ((field, value) in listOf("file" to "../foreign.apk", "file" to "https://example.invalid/p.apk",
            "signerSha256" to "0".repeat(64), "package" to context.packageName)) {
            val document = JSONObject(catalog)
            val entry = document.getJSONArray("plugins").getJSONObject(0)
            val target = if (field == "file") entry.optJSONArray("artifacts")?.getJSONObject(0) ?: entry else entry
            target.put(field, value)
            check(runCatching { store.parse(document.toString()) }.isFailure) { "Accepted invalid $field" }
        }
        val document = JSONObject(catalog)
        val entries = document.getJSONArray("plugins")
        entries.put(entries.getJSONObject(0))
        check(runCatching { store.parse(document.toString()) }.isFailure)
        return "PASS plugin identity, version, digest, signature, asset path and duplicate entry checks"
    }
}
