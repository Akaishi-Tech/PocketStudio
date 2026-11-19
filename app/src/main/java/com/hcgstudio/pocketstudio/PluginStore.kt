package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class PluginArtifact(val abi: String, val file: String, val size: Long, val sha256: String)

internal data class StorePlugin(
    val packageName: String, val name: String, val category: String, val developer: String?,
    val description: String?, val icon: String?, val version: String, val versionCode: Long,
    val artifacts: List<PluginArtifact>, val legacy: Boolean = false
) {
    fun artifact(abis: List<String> = Build.SUPPORTED_ABIS.toList()): PluginArtifact? =
        abis.firstNotNullOfOrNull { abi -> artifacts.find { it.abi == abi } } ?: artifacts.find { it.abi == "universal" }

    fun release(artifact: PluginArtifact) = AppRelease(version, versionCode, artifact.size, artifact.sha256)
}

internal data class InstalledPlugin(val name: String, val version: String, val versionCode: Long,
    val trusted: Boolean, val category: String, val developer: String?, val description: String?)

internal class PluginStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("plugin-store", Context.MODE_PRIVATE)
    private val signer by lazy { app.resources.openRawResource(R.raw.release_signer).use { it.readBytes() } }
    private val fingerprint by lazy {
        MessageDigest.getInstance("SHA-256").digest(signer).joinToString("") { "%02x".format(it) }
    }

    private fun assetBytes(file: String, limit: Int): ByteArray = readGitHubAsset(BASE_URL + file) { connection ->
        if (connection.contentLengthLong > limit) throw invalidCatalog()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            val bytes = ByteArrayOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > limit) throw invalidCatalog()
                bytes.write(buffer, 0, n)
            }
            bytes.toByteArray()
        }
    }

    fun catalog(): List<StorePlugin> {
        val json = assetBytes("plugin-store.json", 262_144).toString(Charsets.UTF_8)
        return parse(json).also { prefs.edit().putString("catalog", json).apply() }
    }

    fun cached(): List<StorePlugin> = prefs.getString("catalog", null)
        ?.let { runCatching { parse(it) }.getOrNull() }.orEmpty().filter { plugin ->
            // Old cached catalogs point to the retired host. Only its already-staged APK is installable offline.
            !plugin.legacy || plugin.artifact()?.let {
                ReleaseApkInstaller(app, plugin.packageName, plugin.packageName).staged(plugin.release(it)).isFile
            } == true
        }

    private fun localized(entry: JSONObject, field: String): String {
        val locale = app.resources.configuration.locales[0]
        val translations = entry.optJSONObject("${field}s")
        return translations?.optString(locale.toLanguageTag()).orEmpty().ifBlank {
            translations?.optString(locale.language).orEmpty().ifBlank { entry.getString(field) }
        }
    }

    fun parse(body: String): List<StorePlugin> {
        try {
            val catalog = JSONObject(body)
            val schema = catalog.getInt("schemaVersion")
            if (schema !in 1..2) throw invalidCatalog()
            val packages = mutableSetOf<String>()
            val files = mutableSetOf<String>()
            val plugins = catalog.getJSONArray("plugins")
            return List(plugins.length()) { i ->
                val entry = plugins.getJSONObject(i)
                val pkg = entry.getString("package")
                val name = localized(entry, "name")
                val version = entry.getString("version")
                val code = entry.getLong("versionCode")
                if (!PACKAGE.matches(pkg) || pkg == app.packageName || !packages.add(pkg) || name.isBlank() ||
                    version.isBlank() || code !in 1..2_100_000_000L || entry.getString("signerSha256") != fingerprint)
                    throw invalidCatalog()
                fun artifact(obj: JSONObject, abi: String): PluginArtifact {
                    val file = obj.getString("file")
                    val size = obj.getLong("size")
                    val sha256 = obj.getString("sha256")
                    if (!APK.matches(file) || file == "PocketStudio.apk" || !files.add(file) ||
                        size !in 1..1_500_000_000L || !SHA.matches(sha256)) throw invalidCatalog()
                    return PluginArtifact(abi, file, size, sha256)
                }
                if (schema == 1) {
                    val single = artifact(entry, "universal")
                    val abis = entry.getJSONArray("abis")
                    val variants = List(abis.length()) { single.copy(abi = abis.getString(it)) }.ifEmpty { listOf(single) }
                    return@List StorePlugin(pkg, name, PluginDiscovery.AGENT_CATEGORY, null, null, null,
                        version, code, variants, legacy = true)
                }
                val category = entry.getString("category")
                val developer = entry.getString("developer")
                val description = localized(entry, "description")
                val icon = entry.getString("icon")
                if (!CATEGORY.matches(category) || developer.isBlank() || description.isBlank() || !ICON.matches(icon))
                    throw invalidCatalog()
                val items = entry.getJSONArray("artifacts")
                val seen = mutableSetOf<String>()
                if (items.length() == 0) throw invalidCatalog()
                val variants = List(items.length()) { index ->
                    val obj = items.getJSONObject(index)
                    val abi = obj.getString("abi")
                    if (abi == "universal" || !ABI.matches(abi) || !seen.add(abi)) throw invalidCatalog()
                    artifact(obj, abi)
                }
                StorePlugin(pkg, name, category, developer, description, icon, version, code, variants)
            }
        } catch (e: JSONException) { throw invalidCatalog(e) }
    }

    fun installed(catalog: List<StorePlugin>): Map<String, InstalledPlugin> {
        val pm = app.packageManager
        val installed = discoverPlugins(app).associate { plugin ->
            plugin.packageName to InstalledPlugin(plugin.label, plugin.version, plugin.versionCode, plugin.trusted,
                plugin.category, plugin.developer, plugin.description)
        }.toMutableMap()
        // Recognize an existing package even if its version lacks a compatible discovery declaration.
        for (plugin in catalog) {
            if (plugin.packageName in installed) continue
            val info = pm.signedPackageInfo(plugin.packageName) ?: continue
            installed[plugin.packageName] = InstalledPlugin(info.applicationInfo?.loadLabel(pm)?.toString() ?: plugin.name,
                info.versionName.orEmpty(), packageVersionCode(info), info.hasReleaseSigner(signer),
                plugin.category, null, null)
        }
        return installed
    }

    fun icon(pkg: String, plugin: StorePlugin?, installed: Boolean): Bitmap? {
        val pm = app.packageManager
        if (installed) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (info.icon != 0) return info.loadIcon(pm).toBitmap(160, 160)
            } catch (_: PackageManager.NameNotFoundException) { /* Package removed since discovery. */ }
        }
        val icon = plugin?.icon ?: return null
        val directory = File(app.cacheDir, "plugin-icons").apply { mkdirs() }
        val cached = File(directory, "$pkg-${plugin.versionCode}-$icon")
        if (cached.isFile) BitmapFactory.decodeFile(cached.path)?.let { return it }
        val data = assetBytes(icon, 1_048_576)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val temp = File.createTempFile("icon-", ".tmp", directory)
        try {
            temp.writeBytes(data)
            Files.move(temp.toPath(), cached.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        return bitmap
    }

    fun download(plugin: StorePlugin, progress: (Long, Long) -> Unit): File {
        val artifact = plugin.artifact() ?: throw IOException(app.getString(R.string.plugin_incompatible))
        val release = plugin.release(artifact)
        val current = installed(listOf(plugin))[plugin.packageName]
        if (current?.trusted == false || (current?.versionCode ?: 0) >= release.versionCode)
            throw IOException(app.getString(R.string.plugin_cannot_install))
        val installer = ReleaseApkInstaller(app, plugin.packageName, plugin.packageName)
        return installer.prepareFile(release, progress) { destination ->
            if (plugin.legacy) throw IOException(app.getString(R.string.plugin_cannot_install))
            downloadGitHubApk(BASE_URL + artifact.file, release, destination, progress)
        }
    }

    companion object {
        private const val BASE_URL = "https://github.com/Akaishi-Tech/PocketStudio-Plugins/releases/download/plugin-store/"
        private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
        private val APK = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.apk""")
        private val ICON = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.png""")
        private val CATEGORY = Regex("[a-z][a-z0-9._-]*")
        private val ABI = Regex("[a-z0-9][a-z0-9_-]*")
        private val SHA = Regex("[0-9a-f]{64}")
        private fun invalidCatalog(cause: Throwable? = null) = IOException(uiString(R.string.plugin_catalog_invalid), cause)
    }
}
