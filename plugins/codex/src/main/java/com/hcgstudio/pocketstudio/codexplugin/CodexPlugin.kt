package com.hcgstudio.pocketstudio.codexplugin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import com.hcgstudio.pocketstudio.agent.AgentHost
import com.hcgstudio.pocketstudio.agent.AgentPlugin
import com.hcgstudio.pocketstudio.agent.AgentSession

/** PackageManager discovery only. Studio loads the verified provider through the shared API. */
class CodexPluginMarker : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

private lateinit var resourcesContext: Context
internal var pluginVersion = "1"
    private set

internal fun uiString(id: Int, vararg args: Any?): String = resourcesContext.getString(id, *args)

internal fun initializeCodex(context: Context, resources: Context) {
    resourcesContext = resources
    pluginVersion = resources.packageManager.getPackageInfo(resources.packageName, 0).versionName ?: "1"
    CodexAuth.debugHost = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

class CodexPlugin : AgentPlugin {
    override fun open(host: AgentHost): AgentSession {
        initializeCodex(host.context, host.pluginContext)
        return CodexController(host)
    }
}
