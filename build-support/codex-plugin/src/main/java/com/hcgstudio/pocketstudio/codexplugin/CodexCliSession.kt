package com.hcgstudio.pocketstudio.codexplugin

import com.hcgstudio.pocketstudio.agent.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files

internal data class CodexCliAccount(val email: String?, val userId: String?, val workspaceId: String? = null)

// The app-server reports reqwest transport failures as text, without a separate error code.
private fun deviceRequestFailure(endpoint: String) =
    "error sending request for url (https://auth.openai.com/api/accounts/deviceauth/$endpoint)"
private class DeviceTokenRequestFailed : IOException(uiString(R.string.codex_login_network_error))

/** The CLI owns the browser callback, device authorization, credential store, refresh, and logout. */
internal class CodexCliSession(private val launch: (File) -> Process) {
    constructor(runtime: CodexRuntime) : this({ home ->
        Files.createDirectories(home.toPath())
        runtime.launch(home, mapOf("CODEX_HOME" to home.absolutePath),
            listOf("-c", "cli_auth_credentials_store=\"file\""))
    })

    suspend fun loginWithBrowser(home: File, openBrowser: suspend (String) -> Unit): CodexCliAccount = login(home) { rpc ->
        authorize(rpc, "chatgpt") { start -> openBrowser(httpsUrl(start, "authUrl")) }
    }

    suspend fun loginWithDeviceCode(home: File, awaitRetry: suspend () -> Unit = {},
                                    onCode: (DeviceCode) -> Unit): CodexCliAccount = login(home) { rpc ->
        try {
            authorizeDevice(rpc, onCode)
        } catch (_: DeviceTokenRequestFailed) {
            // The CLI has ended this grant. A fresh loginId and code are required.
            awaitRetry()
            authorizeDevice(rpc, onCode)
        }
    }

    private suspend fun authorizeDevice(rpc: CodexRpc, onCode: (DeviceCode) -> Unit) {
        authorize(rpc, "chatgptDeviceCode") { start ->
            val code = start.string("userCode")
            if (code.isBlank()) throw incomplete()
            onCode(DeviceCode(httpsUrl(start, "verificationUrl"), code))
        }
    }

    private fun httpsUrl(start: JSONObject, field: String): String {
        val url = start.string(field)
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) throw incomplete()
        return url
    }

    private suspend fun login(home: File, authorize: suspend (CodexRpc) -> Unit): CodexCliAccount = withServer(home) { rpc ->
        // Bound the entire sign-in, including time spent waiting to return to Studio.
        withTimeout(16 * 60_000L) {
            authorize(rpc)
            readAccount(rpc) ?: throw incomplete()
        }
    }

    private suspend fun authorize(rpc: CodexRpc, type: String, onStart: suspend (JSONObject) -> Unit) {
        val start = try {
            rpc.request("account/login/start", json("type" to type))
        } catch (e: IOException) {
            if (type == "chatgptDeviceCode" && e.message.orEmpty().endsWith(deviceRequestFailure("usercode"))) {
                throw IOException(uiString(R.string.codex_login_network_error), e)
            }
            throw e
        }
        val id = start.string("loginId")
        if (id.isBlank()) throw incomplete()
        var completed = false
        try {
            if (start.string("type") != type) throw incomplete()
            onStart(start)
            while (true) {
                val event = rpc.events.receive()
                if (event.string("method") != "account/login/completed") continue
                val result = event.optJSONObject("params") ?: continue
                if (result.string("loginId") != id) continue
                completed = true
                if (result.optBoolean("success")) return
                val error = result.string("error")
                if (type == "chatgptDeviceCode" && error == deviceRequestFailure("token")) {
                    throw DeviceTokenRequestFailed()
                }
                throw IOException(error.ifBlank { uiString(R.string.chatgpt_authorization_is_incomplete) })
            }
        } finally {
            if (!completed) withContext(NonCancellable) {
                withTimeoutOrNull(2_000L) {
                    try { rpc.request("account/login/cancel", json("loginId" to id)) }
                    catch (_: IOException) { /* Closing the owned process ends any remaining attempt. */ }
                }
            }
        }
    }

    suspend fun account(home: File): CodexCliAccount? = withServer(home, ::readAccount)

    suspend fun logout(home: File) {
        withServer(home) { it.request("account/logout") }
    }

    private suspend fun readAccount(rpc: CodexRpc): CodexCliAccount? {
        val result = rpc.request("account/read", json("refreshToken" to false))
        val account = result.optJSONObject("account") ?: return null
        if (account.string("type") != "chatgpt") return null
        return CodexCliAccount(account.string("email").takeIf { it.isNotBlank() },
            result.string("userId").takeIf { it.isNotBlank() },
            result.optJSONObject("workspaceRouting")?.string("chatgptAccountId")?.takeIf { it.isNotBlank() })
    }

    private suspend fun <T> withServer(home: File, action: suspend (CodexRpc) -> T): T {
        var opened: CodexRpc? = null
        try {
            val rpc = withContext(Dispatchers.IO) { CodexRpc(launch(home)).also { opened = it } }
            rpc.initialize()
            return action(rpc)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }

    private fun incomplete() = IOException(uiString(R.string.chatgpt_authorization_is_incomplete))
}
