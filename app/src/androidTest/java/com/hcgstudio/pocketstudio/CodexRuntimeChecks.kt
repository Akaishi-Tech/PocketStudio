package com.hcgstudio.pocketstudio

import android.content.Context
import com.hcgstudio.pocketstudio.codexplugin.CodexProtocolChecks

object CodexRuntimeChecks {
    @JvmStatic fun run(context: Context, resources: Context, scenario: String): String {
        if (scenario in setOf("codexVerifyRelease", "codexOfflineInstall", "codexDownload"))
            return PluginStoreChecks.run(context, scenario)
        val provider = installedAgentPlugins(context).single { it.packageName == "com.hcgstudio.pocketstudio.codexplugin" }
        val (_, plugin) = provider.load(context)
        return CodexProtocolChecks.run(context, plugin, resources, scenario)
    }
}
