package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.hcgstudio.pocketstudio.agent.AgentPlugin
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
import com.hcgstudio.pocketstudio.plugin.PluginRegistration

internal data class DiscoveredPlugin(val packageName: String, val label: String, val category: String,
    val version: String, val versionCode: Long, val sourceDir: String, val trusted: Boolean,
    val developer: String?, val description: String?, val service: ServiceInfo)

@Suppress("DEPRECATION")
internal fun discoverPlugins(context: Context): List<DiscoveredPlugin> {
    val pm = context.packageManager
    val registrations = PluginDiscovery.query(pm)
    val currentPackages = registrations.mapTo(mutableSetOf()) { it.service.packageName }
    // A v1 agent APK already installed before the general plugin API update still supplies a usable provider.
    val legacy = pm.queryIntentServices(Intent(AgentPlugin.ACTION), PackageManager.GET_META_DATA).mapNotNull {
        val service = it.serviceInfo
        val metadata = service.metaData ?: return@mapNotNull null
        if (service.packageName in currentPackages || metadata.containsKey(PluginDiscovery.API) ||
            metadata.getInt(AgentPlugin.API) != 1 ||
            metadata.getString(AgentPlugin.ENTRY).isNullOrBlank()) return@mapNotNull null
        PluginRegistration(service, PluginDiscovery.AGENT_CATEGORY, null, null)
    }
    val signer = context.resources.openRawResource(R.raw.release_signer).use { it.readBytes() }
    return (registrations + legacy).groupBy { it.service.packageName }.mapNotNull { (pkg, matches) ->
        // One marker per APK. Ambiguous declarations cannot select a reliable entry point.
        val item = matches.singleOrNull() ?: return@mapNotNull null
        val service = item.service
        val info = pm.signedPackageInfo(pkg) ?: return@mapNotNull null
        val app = info.applicationInfo ?: return@mapNotNull null
        if (app.sourceDir != service.applicationInfo.sourceDir) return@mapNotNull null
        val localDebugBuild = BuildConfig.DEBUG && (pkg == AndroidEnvironments.PACKAGE || pkg in LinuxProjects.packageNames) &&
            pm.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH
        DiscoveredPlugin(pkg, service.loadLabel(pm).toString(), item.category, info.versionName.orEmpty(),
            packageVersionCode(info), app.sourceDir, info.hasReleaseSigner(signer) || localDebugBuild, item.developer,
            item.description, service)
    }.sortedBy { it.label }
}
