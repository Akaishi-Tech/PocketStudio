package com.hcgstudio.pocketstudio

import android.content.Context
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
import dalvik.system.PathClassLoader

internal object AndroidEnvironments {
    internal const val PACKAGE = "com.hcgstudio.pocketstudio.androidplugin"
    private var loadedPath: String? = null
    private var loaded: AndroidEnvironment? = null

    private fun installed(context: Context) = discoverPlugins(context).singleOrNull { plugin ->
        plugin.packageName == PACKAGE && plugin.trusted &&
            plugin.category == PluginDiscovery.DEVELOPMENT_ENVIRONMENT_CATEGORY &&
            plugin.service.metaData.getInt(AndroidEnvironment.API) == AndroidEnvironment.VERSION &&
            !plugin.service.metaData.getString(AndroidEnvironment.ENTRY).isNullOrBlank()
    }

    @JvmStatic fun available(context: Context): Boolean = installed(context) != null

    @JvmStatic fun require(context: Context): AndroidEnvironment =
        find(context) ?: throw IllegalStateException(context.getString(R.string.android_environment_required))

    @JvmStatic @Synchronized fun find(context: Context): AndroidEnvironment? {
        val host = context.applicationContext
        val registration = installed(host) ?: run {
            loadedPath = null
            loaded = null
            return null
        }
        loaded?.takeIf { loadedPath == registration.sourceDir }?.let { return it }
        val plugin = host.createPackageContext(registration.packageName, 0)
        check(plugin.applicationInfo.sourceDir == registration.sourceDir) { host.getString(R.string.android_environment_required) }
        val entry = requireNotNull(registration.service.metaData.getString(AndroidEnvironment.ENTRY))
        val loader = PathClassLoader(registration.sourceDir, plugin.applicationInfo.nativeLibraryDir,
            AndroidEnvironment::class.java.classLoader)
        val provider = loader.loadClass(entry).asSubclass(AndroidEnvironment::class.java)
            .getConstructor(Context::class.java, Context::class.java).newInstance(host, plugin)
        loadedPath = registration.sourceDir
        loaded = provider
        return provider
    }
}
