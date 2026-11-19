package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import android.os.Build
import com.hcgstudio.pocketstudio.agent.AgentPlugin
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class StorePlugin(
    val packageName: String, val name: String, val file: String, val release: AppRelease, val abis: List<String>
) {
    val compatible: Boolean get() = abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }
}

internal data class InstalledPlugin(val name: String, val version: String, val versionCode: Long, val trusted: Boolean)

internal class PluginStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("plugin-store", Context.MODE_PRIVATE)
    private val signer by lazy { app.resources.openRawResource(R.raw.release_signer).use { it.readBytes() } }
    private val fingerprint by lazy {
        MessageDigest.getInstance("SHA-256").digest(signer).joinToString("") { "%02x".format(it) }
    }
    private val baseUrl = "https://github.com/${GitHubApkReleases.REPO}/releases/download/plugin-store/"

    fun catalog(): List<StorePlugin> {
        val json = readGitHubAsset(baseUrl + "plugin-store.json") { connection ->
            if (connection.contentLengthLong > 262_144) throw invalidCatalog()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > 262_144) throw invalidCatalog()
                    bytes.write(buffer, 0, n)
                }
                bytes.toString("UTF-8")
            }
        }
        return parse(json).also { prefs.edit().putString("catalog", json).apply() }
    }

    fun cached(): List<StorePlugin> = prefs.getString("catalog", null)
        ?.let { runCatching { parse(it) }.getOrNull() }.orEmpty()

    fun parse(body: String): List<StorePlugin> {
        try {
            val catalog = JSONObject(body)
            if (catalog.getInt("schemaVersion") != 1) throw invalidCatalog()
            val packages = mutableSetOf<String>()
            val files = mutableSetOf<String>()
            val plugins = catalog.getJSONArray("plugins")
            return List(plugins.length()) { i ->
                val entry = plugins.getJSONObject(i)
                val pkg = entry.getString("package")
                val file = entry.getString("file")
                val locale = app.resources.configuration.locales[0]
                val names = entry.optJSONObject("names")
                val name = names?.optString(locale.toLanguageTag()).orEmpty().ifBlank {
                    names?.optString(locale.language).orEmpty().ifBlank { entry.getString("name") }
                }
                val release = AppRelease(entry.getString("version"), entry.getLong("versionCode"),
                    entry.getLong("size"), entry.getString("sha256"))
                if (!PACKAGE.matches(pkg) || pkg == app.packageName || !packages.add(pkg) ||
                    !FILE.matches(file) || file == "PocketStudio.apk" || !files.add(file) || name.isBlank() ||
                    release.version.isBlank() || release.versionCode !in 1..2_100_000_000L ||
                    release.size !in 1..1_500_000_000L || !SHA.matches(release.sha256) ||
                    entry.getString("signerSha256") != fingerprint) throw invalidCatalog()
                val abis = entry.getJSONArray("abis")
                StorePlugin(pkg, name, file, release, List(abis.length()) { abis.getString(it) })
            }
        } catch (e: JSONException) { throw invalidCatalog(e) }
    }

    @Suppress("DEPRECATION")
    fun installed(catalog: List<StorePlugin>): Map<String, InstalledPlugin> {
        val pm = app.packageManager
        val declared = pm.queryIntentServices(Intent(AgentPlugin.ACTION), 0)
            .map { it.serviceInfo.packageName }
        return (declared + catalog.map { it.packageName }).distinct().mapNotNull { pkg ->
            val info = pm.signedPackageInfo(pkg) ?: return@mapNotNull null
            val trusted = info.hasReleaseSigner(signer)
            val name = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
            pkg to InstalledPlugin(name, info.versionName.orEmpty(), packageVersionCode(info), trusted)
        }.toMap()
    }

    fun download(plugin: StorePlugin, progress: (Long, Long) -> Unit): File {
        val current = installed(listOf(plugin))[plugin.packageName]
        if (!plugin.compatible || current?.trusted == false || (current?.versionCode ?: 0) >= plugin.release.versionCode)
            throw IOException(app.getString(R.string.plugin_cannot_install))
        val installer = ReleaseApkInstaller(app, plugin.packageName, plugin.packageName)
        return installer.prepareFile(plugin.release, progress) { destination ->
            downloadGitHubApk(baseUrl + plugin.file, plugin.release, destination, progress)
        }
    }

    companion object {
        private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
        private val FILE = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.apk""")
        private val SHA = Regex("[0-9a-f]{64}")
        private fun invalidCatalog(cause: Throwable? = null) = IOException(uiString(R.string.plugin_catalog_invalid), cause)
    }
}
