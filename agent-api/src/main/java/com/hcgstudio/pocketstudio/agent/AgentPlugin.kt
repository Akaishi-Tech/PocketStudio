package com.hcgstudio.pocketstudio.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.io.Closeable

/** An installed provider supplies one session per open project. All session calls use the main thread.
 * Observable properties must notify Compose when their values change. Pocket Studio supplies Kotlin,
 * coroutines, the Compose runtime, and this API to the provider's class loader. */
interface AgentPlugin {
    fun open(host: AgentHost): AgentSession

    companion object {
        const val VERSION = 2
        const val ACTION = "com.hcgstudio.pocketstudio.AGENT_PLUGIN"
        const val ENTRY = "com.hcgstudio.pocketstudio.AGENT_ENTRY"
        const val API = "com.hcgstudio.pocketstudio.AGENT_API"
    }
}

interface AgentHost : AgentToolProvider {
    val context: Context
    val pluginContext: Context
    val scope: CoroutineScope
    val projectId: String
    val projectIdle: Boolean
    val tools: List<AgentTool>
    suspend fun callTool(name: String, arguments: JSONObject): String
    override val availableTools: List<AgentTool> get() = tools
    override suspend fun executeTool(name: String, arguments: JSONObject): List<AgentToolContent> =
        listOf(AgentToolContent.Text(callTool(name, arguments)))
    fun keepLoginAlive(): Closeable
    /** A short, user-readable current action. Null ends the ongoing notification. */
    fun reportStatus(status: String?)
}

/** A host operation and its JSON Schema input object. */
data class AgentTool(val name: String, val description: String, val inputSchema: JSONObject)

/** API 2 hosts expose their complete tool catalog and ordered, multimodal results here. */
interface AgentToolProvider {
    val availableTools: List<AgentTool>
    suspend fun executeTool(name: String, arguments: JSONObject): List<AgentToolContent>
}

sealed interface AgentToolContent {
    data class Text(val text: String) : AgentToolContent
    /** Encoded image bytes, such as a PNG file, and their media type. */
    class Image(val mimeType: String, val bytes: ByteArray) : AgentToolContent
}

data class DeviceCode(val verificationUrl: String, val userCode: String)
data class AgentProfile(val id: String, val label: String)
data class LoginOptions(val browserLabel: String? = null, val apiKey: Boolean = false)
data class AgentAccount(val profiles: List<AgentProfile>, val selectedId: String?, val hasApiKey: Boolean,
    val apiEndpoint: String, val options: LoginOptions)
enum class LoginField { Endpoint, Key }
data class LoginError(val field: LoginField, val message: String)

interface AgentLogin {
    val loginState: AgentAccount?
    val usable: Boolean
    val accountBusy: Boolean
    val signingIn: Boolean
    val deviceCode: DeviceCode?
    val replacingDeviceCode: Boolean
    val offerDeviceCode: Boolean
    val quota: List<String>
    val quotaAsOf: Long
    val usageUrl: String?
    fun login(openBrowser: (String) -> Unit)
    fun useDeviceCode()
    fun cancelLogin()
    fun logout()
    fun selectAccount(id: String)
    fun saveApiKey(endpoint: String, key: String, saved: () -> Unit, invalid: (LoginError) -> Unit)
    fun removeApiKey(saved: () -> Unit)
    fun restoreQuota()
}

data class AgentChat(val id: String, val title: String, val updated: Long)
enum class ChatKind { User, Assistant, Activity, Status, UsageLimit }
data class ChatLine(val id: String, val kind: ChatKind, val text: String = "", val detail: String = "",
    val category: String = "", val failed: Boolean = false, val command: Boolean = false)
data class AgentModel(val id: String, val displayName: String, val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null, val supportsFast: Boolean = false, val isDefault: Boolean = false)

interface AgentMessages {
    val chats: List<AgentChat>
    val lines: List<ChatLine>
    val working: Boolean
    val loading: Boolean
    val detail: Boolean
    val chatId: String?
    val title: String
    var input: String
    val offersModelSelection: Boolean get() = false
    val models: List<AgentModel>
    val modelsLoading: Boolean
    val modelsError: String?
    val selectedModel: AgentModel?
    val effort: String?
    val fast: Boolean
    fun newChat()
    fun back()
    fun open(chat: AgentChat)
    fun refreshModels()
    fun chooseModel(id: String)
    fun chooseEffort(value: String)
    fun setFast(enabled: Boolean)
    fun send()
    fun stop()
}

interface AgentSession : AgentLogin, AgentMessages, Closeable {
    var error: String?
    fun refresh()
    fun setActivityResumed(value: Boolean)
}
