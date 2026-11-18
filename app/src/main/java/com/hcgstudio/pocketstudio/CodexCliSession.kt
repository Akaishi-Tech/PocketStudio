package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.codex.CodexRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files

internal data class CodexDeviceCode(val verificationUrl: String, val userCode: String)
internal data class CodexCliAccount(val email: String?, val userId: String?, val workspaceId: String? = null)

/** The CLI owns the device authorization, credential store, refresh, and logout. */
internal class CodexCliSession(private val launch: (File) -> Process) {
    constructor(runtime: CodexRuntime) : this({ home ->
        Files.createDirectories(home.toPath())
        runtime.launch(home, mapOf("CODEX_HOME" to home.absolutePath),
            listOf("-c", "cli_auth_credentials_store=\"file\""))
    })

    suspend fun login(home: File, onCode: (CodexDeviceCode) -> Unit): CodexCliAccount = withServer(home) { rpc ->
        val start = rpc.request("account/login/start", json("type" to "chatgptDeviceCode"))
        val id = start.string("loginId")
        if (start.string("type") != "chatgptDeviceCode" || id.isBlank()) throw incomplete()
        var completed = false
        try {
            val url = start.string("verificationUrl")
            val uri = runCatching { URI(url) }.getOrNull()
            val code = start.string("userCode")
            if (uri?.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || code.isBlank()) {
                throw incomplete()
            }
            onCode(CodexDeviceCode(url, code))
            // Device codes normally expire after 15 minutes. Also bound the wait if a
            // stalled runtime never sends the terminal notification.
            withTimeout(16 * 60_000L) {
                while (true) {
                    val event = rpc.events.receive()
                    if (event.string("method") != "account/login/completed") continue
                    val result = event.optJSONObject("params") ?: continue
                    if (result.string("loginId") != id) continue
                    completed = true
                    if (!result.optBoolean("success")) {
                        throw IOException(result.string("error").ifBlank { uiString(R.string.chatgpt_authorization_is_incomplete) })
                    }
                    break
                }
            }
            readAccount(rpc) ?: throw incomplete()
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
