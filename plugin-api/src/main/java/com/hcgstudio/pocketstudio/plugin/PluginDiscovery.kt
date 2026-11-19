package com.hcgstudio.pocketstudio.plugin

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo

/** Versioned manifest protocol for plugin discovery. Inspecting a registration never starts the service. */
object PluginDiscovery {
    const val VERSION = 1
    const val ACTION = "com.hcgstudio.pocketstudio.PLUGIN"
    const val API = "com.hcgstudio.pocketstudio.PLUGIN_API"
    const val CATEGORY = "com.hcgstudio.pocketstudio.PLUGIN_CATEGORY"
    const val DEVELOPER = "com.hcgstudio.pocketstudio.PLUGIN_DEVELOPER"
    const val AGENT_CATEGORY = "agent"

    /** Returns compatible declarations, regardless of their category or the application's signer.
     * The host verifies signatures and the category-specific API before executing any provider. */
    @Suppress("DEPRECATION")
    fun query(manager: PackageManager): List<PluginRegistration> =
        manager.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)
            .mapNotNull { registration(manager, it.serviceInfo) }

    fun registration(manager: PackageManager, service: ServiceInfo): PluginRegistration? {
        val metadata = service.metaData ?: return null
        if (metadata.getInt(API) != VERSION) return null
        val category = metadata.getString(CATEGORY)?.takeIf(categoryId::matches) ?: return null
        val developer = when (val value = metadata.get(DEVELOPER)) {
            is String -> value
            is Int -> manager.getText(service.packageName, value, service.applicationInfo)?.toString()
            else -> null
        }
        val description = if (service.descriptionRes == 0) null else
            manager.getText(service.packageName, service.descriptionRes, service.applicationInfo)?.toString()
        return PluginRegistration(service, category, developer?.takeIf { it.isNotBlank() },
            description?.takeIf { it.isNotBlank() })
    }

    private val categoryId = Regex("[a-z][a-z0-9._-]*")
}

/** The service metadata contains any additional API version or entry point specific to its category. */
data class PluginRegistration(val service: ServiceInfo, val category: String, val developer: String?,
    val description: String?)
