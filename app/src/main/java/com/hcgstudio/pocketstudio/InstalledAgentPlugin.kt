package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.hcgstudio.pocketstudio.agent.AgentPlugin
import dalvik.system.PathClassLoader

internal data class InstalledAgentPlugin(val packageName: String, val label: String, val version: String,
    val versionCode: Long, val sourceDir: String, val entry: String) {
    fun load(context: Context): Pair<AgentPlugin, Context> {
        val plugin = context.createPackageContext(packageName, 0)
        check(plugin.applicationInfo.sourceDir == sourceDir) { uiString(R.string.agent_plugin_changed) }
        // Only PackageManager-owned immutable APKs verified in installedAgentPlugins reach this point.
        val providerNamespace = entry.substringBeforeLast('.') + "."
        val loader = object : PathClassLoader(sourceDir, AgentPlugin::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith(providerNamespace)) return super.loadClass(name, resolve)
                return synchronized(this) {
                    val type = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(type)
                    type
                }
            }
        }
        val provider = loader.loadClass(entry).asSubclass(AgentPlugin::class.java).getDeclaredConstructor().newInstance()
        return provider to plugin
    }
}

@Suppress("DEPRECATION")
internal fun installedAgentPlugins(context: Context): List<InstalledAgentPlugin> {
    val pm = context.packageManager
    val signer = context.resources.openRawResource(R.raw.release_signer).use { it.readBytes() }
    return pm.queryIntentServices(Intent(AgentPlugin.ACTION), PackageManager.GET_META_DATA).mapNotNull { resolved ->
        val service = resolved.serviceInfo
        val metadata = service.metaData ?: return@mapNotNull null
        if (metadata.getInt(AgentPlugin.API) != AgentPlugin.VERSION) return@mapNotNull null
        val entry = metadata.getString(AgentPlugin.ENTRY)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val info = pm.signedPackageInfo(service.packageName) ?: return@mapNotNull null
        if (!info.hasReleaseSigner(signer)) return@mapNotNull null
        val app = info.applicationInfo ?: return@mapNotNull null
        InstalledAgentPlugin(info.packageName, service.loadLabel(pm).toString(), info.versionName.orEmpty(),
            packageVersionCode(info), app.sourceDir, entry)
    }.distinctBy { it.packageName }.sortedBy { it.label }
}
