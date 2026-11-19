package com.hcgstudio.pocketstudio

import android.content.Context
import com.hcgstudio.pocketstudio.agent.AgentPlugin
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
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

internal fun installedAgentPlugins(context: Context): List<InstalledAgentPlugin> =
    discoverPlugins(context).mapNotNull { plugin ->
        if (!plugin.trusted || plugin.category != PluginDiscovery.AGENT_CATEGORY) return@mapNotNull null
        val metadata = plugin.service.metaData
        if (metadata.getInt(AgentPlugin.API) != AgentPlugin.VERSION) return@mapNotNull null
        val entry = metadata.getString(AgentPlugin.ENTRY)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        InstalledAgentPlugin(plugin.packageName, plugin.label, plugin.version, plugin.versionCode, plugin.sourceDir, entry)
    }
