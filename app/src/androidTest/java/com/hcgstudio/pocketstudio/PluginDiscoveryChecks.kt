package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Bundle
import com.hcgstudio.pocketstudio.agent.AgentPlugin
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery

object PluginDiscoveryChecks {
    @JvmStatic fun androidEnvironment(context: Context, installed: Boolean): String {
        check(AndroidEnvironments.available(context) == installed)
        if (installed) {
            val plugin = discoverPlugins(context).single { it.packageName == AndroidEnvironments.PACKAGE }
            check(plugin.category == PluginDiscovery.DEVELOPMENT_ENVIRONMENT_CATEGORY && plugin.trusted)
            val provider = AndroidEnvironments.require(context)
            check(provider === AndroidEnvironments.require(context))
            check(provider.buildTools().version().isNotBlank())
        } else {
            check(runCatching { AndroidEnvironments.require(context) }.isFailure)
        }
        return "PASS Android environment discovery and loading (installed=$installed)"
    }

    /** Run against either the previously published Codex APK or the current marker declaration. */
    @JvmStatic fun run(context: Context, legacy: Boolean, trusted: Boolean): String {
        val pm = context.packageManager
        val pkg = "com.hcgstudio.pocketstudio.codexplugin"
        val plugin = discoverPlugins(context).single { it.packageName == pkg }
        check(plugin.category == PluginDiscovery.AGENT_CATEGORY && plugin.trusted == trusted)
        val current = PluginDiscovery.query(pm).filter { it.service.packageName == pkg }
        if (legacy) check(current.isEmpty()) else {
            val marker = current.single()
            check(marker.developer == "Akaishi Tech" && !marker.description.isNullOrBlank())
            check(marker.service.applicationInfo.icon != 0)
        }

        val sample = ServiceInfo(plugin.service).apply {
            metaData = Bundle().apply {
                putInt(PluginDiscovery.API, PluginDiscovery.VERSION)
                putString(PluginDiscovery.CATEGORY, "editor")
            }
        }
        check(PluginDiscovery.registration(pm, sample)?.category == "editor")
        sample.metaData.putInt(PluginDiscovery.API, PluginDiscovery.VERSION + 1)
        check(PluginDiscovery.registration(pm, sample) == null)
        sample.metaData.putInt(PluginDiscovery.API, PluginDiscovery.VERSION)
        sample.metaData.remove(PluginDiscovery.CATEGORY)
        check(PluginDiscovery.registration(pm, sample) == null)

        val agents = installedAgentPlugins(context).filter { it.packageName == pkg }
        if (trusted) check(agents.single().load(context).first.javaClass.name ==
            plugin.service.metaData.getString(AgentPlugin.ENTRY)) else check(agents.isEmpty())
        return "PASS plugin discovery, category metadata, version negotiation and agent signer checks"
    }
}
