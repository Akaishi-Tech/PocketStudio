package com.hcgstudio.pocketstudio

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

internal data class CodexAccount(
    val id: String, val label: String, val signedIn: Boolean, val planEnabled: Boolean
)

internal data class CodexAuthState(
    val accounts: List<CodexAccount>, val selected: CodexAccount?, val hasApiKey: Boolean, val useChatGpt: Boolean
)

/** Blocking operations belong on an IO dispatcher. The browser callback only needs to launch ACTION_VIEW. */
internal class CodexAuth(context: Context) {
    private val storage = CodexAuthStorage(context.applicationContext)

    fun snapshot(): CodexAuthState = synchronized(lock) {
        val state = storage.read()
        val profiles = state.optJSONObject("accounts") ?: JSONObject()
        val accounts = profiles.keys().asSequence().map { id ->
            val profile = profiles.getJSONObject(id)
            val label = "${profile.getString("label")} · ${id.takeLast(6)}"
            CodexAccount(id, label, profile.has("identity"), permitsPlan(profile))
        }.toList().sortedBy { it.label }
        CodexAuthState(accounts, accounts.find { it.id == state.optString("selected") },
            state.optString("apiKey").isNotBlank(), state.optBoolean("useChatGpt", true))
    }

    fun setUseChatGpt(value: Boolean) {
        cancelLogin()
        synchronized(lock) { storage.write(storage.read().put("useChatGpt", value)) }
    }

    fun saveApiKey(value: String) = synchronized(lock) {
        val key = value.trim()
        require(key.all { it.code in 33..126 }) { uiString(R.string.the_api_key_contains_invalid_characters) }
        val state = storage.read()
        if (key.isEmpty()) state.remove("apiKey") else state.put("apiKey", key)
        storage.write(state)
    }

    fun selectAccount(id: String) {
        cancelLogin()
        synchronized(lock) {
            val state = storage.read()
            require(state.optJSONObject("accounts")?.has(id) == true) { uiString(R.string.this_account_has_not_been_saved) }
            storage.write(state.put("selected", id))
        }
    }

    fun cancelLogin() = synchronized(loginLock) {
        pending?.close()
        pending = null
    }

    fun login(accountId: String? = null, openBrowser: (Uri) -> Unit): CodexAuthState {
        val server = ServerSocket()
        server.use {
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            synchronized(loginLock) {
                pending?.close()
                pending = server
            }
            try {
                val (host, previous) = synchronized(lock) {
                    val state = storage.read()
                    val host = state.optString("host").ifEmpty {
                        "urn:uuid:${UUID.randomUUID()}".also { storage.write(state.put("host", it)) }
                    }
                    val record = accountId?.let { state.optJSONObject("accounts")?.optJSONObject(it)
                        ?: throw IOException(uiString(R.string.this_account_has_not_been_saved)) }
                    host to record
                }
                val nonce = CodexAuthOAuth.secret()
                val state = CodexAuthOAuth.secret()
                val verifier = CodexAuthOAuth.secret()
                val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
                val authorize = Uri.parse("${CodexAuthOAuth.ISSUER}/api/accounts/authorize").buildUpon()
                val params = linkedMapOf("client_id" to (accountId ?: "dynamic_agent_client"),
                    "ext_agent_host_id" to host, "redirect_uri" to redirect, "response_type" to "code",
                    "scope" to "openid profile email offline_access resource.invoke ${CodexAuthOAuth.PLAN_SCOPE}",
                    "resource" to CodexAuthOAuth.RESOURCE, "state" to state, "nonce" to nonce,
                    "code_challenge_method" to "S256", "code_challenge" to CodexAuthOAuth.challenge(verifier))
                if (accountId == null) params["agent_name_hint"] = APP_NAME
                else if (previous != null && !permitsPlan(previous)) params["prompt"] = "consent"
                params.forEach { (k, v) -> authorize.appendQueryParameter(k, v) }
                // Keep identity tokens out of browser launch intents and platform diagnostics.
                openBrowser(authorize.build())
                val callback = CodexAuthOAuth.receiveCallback(server, state, 5 * 60_000L)
                if (callback.getQueryParameter("error") != null) throw IOException(uiString(R.string.chatgpt_authorization_is_incomplete))
                val code = callback.getQueryParameters("code").singleOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw IOException(uiString(R.string.no_valid_authorization_code_received))
                val returnedIds = callback.getQueryParameters("client_id")
                val issued = if (accountId != null && returnedIds.isEmpty()) accountId else returnedIds.singleOrNull()
                require(!issued.isNullOrBlank() && issued != "dynamic_agent_client" && (accountId == null || issued == accountId)) {
                    uiString(R.string.the_identity_returned_by_the_authorization_provider_does)
                }
                val tokens = CodexAuthOAuth.json(CodexAuthOAuth.TOKEN, mapOf("grant_type" to "authorization_code",
                    "client_id" to issued, "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect,
                    "resource" to CodexAuthOAuth.RESOURCE))
                val identity = tokens.getString("id_token")
                val claims = CodexAuthOAuth.verifyIdToken(identity, issued, nonce)
                val subject = claims.getString("sub")
                val label = claims.optString("email").ifBlank { claims.optString("name").ifBlank { uiString(R.string.chatgpt_account) } }
                val profile = JSONObject().put("sub", subject).put("label", label).put("identity", identity)
                updateTokens(profile, tokens, refreshing = false)
                synchronized(lock) {
                    val stored = storage.read()
                    val profiles = stored.optJSONObject("accounts") ?: JSONObject()
                    val old = profiles.optJSONObject(issued)
                    require(old == null || old.getString("sub") == subject) { uiString(R.string.the_signed_in_account_does_not_match_the) }
                    synchronized(loginLock) {
                        if (pending !== server || server.isClosed) throw IOException(uiString(R.string.sign_in_canceled))
                        profiles.put(issued, profile)
                        storage.write(stored.put("accounts", profiles).put("selected", issued).put("useChatGpt", true))
                    }
                }
                return snapshot()
            } finally {
                synchronized(loginLock) { if (pending === server) pending = null }
            }
        }
    }

    /** Confirm remote revocation where possible, always clear this local session. */
    fun signOut(): Boolean {
        cancelLogin()
        return synchronized(lock) {
            val stored = storage.read()
            val id = stored.optString("selected")
            val profile = stored.optJSONObject("accounts")?.optJSONObject(id) ?: return@synchronized true
            val refresh = profile.optString("refresh")
            val confirmed = if (refresh.isEmpty()) true else revoke(id, refresh)
            clearTokens(profile)
            storage.write(stored)
            confirmed
        }
    }

    private fun revoke(client: String, refresh: String): Boolean {
        for (attempt in 0..1) {
            try {
                val config = CodexAuthOAuth.json("${CodexAuthOAuth.ISSUER}/.well-known/openid-configuration")
                val endpoint = Uri.parse(config.getString("revocation_endpoint"))
                if (config.getString("issuer") != CodexAuthOAuth.ISSUER || endpoint.scheme != "https" ||
                    endpoint.encodedAuthority != "auth.openai.com") return false
                CodexAuthOAuth.json(endpoint.toString(), mapOf("token" to refresh,
                    "token_type_hint" to "refresh_token", "client_id" to client))
                return true
            } catch (error: Exception) {
                if (attempt > 0 || error is CodexAuthHttpError && error.status < 500) return false
                try { Thread.sleep(300) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    /** Provide only the selected provider credential to its child process; callers must never log the value. */
    fun credential(useChatGpt: Boolean): String = synchronized(lock) {
        val stored = storage.read()
        if (!useChatGpt) return@synchronized stored.optString("apiKey").ifBlank { throw IOException(uiString(R.string.set_an_api_key_first)) }
        val id = stored.optString("selected")
        val profile = stored.optJSONObject("accounts")?.optJSONObject(id) ?: throw IOException(uiString(R.string.sign_in_to_chatgpt_first))
        check(permitsPlan(profile)) { uiString(R.string.sign_in_and_authorize_access_to_your_chatgpt) }
        val now = System.currentTimeMillis()
        if (profile.getLong("expires") <= now + 120_000) {
            val refresh = profile.optString("refresh").ifBlank { throw IOException(uiString(R.string.your_session_expired_sign_in_again)) }
            try {
                val tokens = CodexAuthOAuth.json(CodexAuthOAuth.TOKEN, mapOf("grant_type" to "refresh_token",
                    "client_id" to id, "refresh_token" to refresh, "resource" to CodexAuthOAuth.RESOURCE))
                updateTokens(profile, tokens, refreshing = true)
                storage.write(stored)
            } catch (error: CodexAuthHttpError) {
                if (error.code in terminalRefreshErrors) {
                    clearTokens(profile)
                    storage.write(stored)
                    throw IOException(uiString(R.string.your_chatgpt_session_is_no_longer_valid_sign))
                }
                throw error
            }
        }
        check(permitsPlan(profile)) { uiString(R.string.this_account_has_not_authorized_access_to_its) }
        profile.getString("access")
    }

    fun models(plan: Boolean, baseUrl: String, runtimeModels: List<CodexModel> = emptyList()): List<CodexModel> {
        val catalog = CodexAuthOAuth.json("${baseUrl.trimEnd('/')}/models", bearer = credential(plan))
        if (plan) {
            // app-server drops priority when its own model descriptor lacks that tier.
            val runtimeFastModels = runtimeModels.filter { it.supportsFast }.map { it.id }.toSet()
            return catalog.getJSONArray("models").objects().mapNotNull { CodexModel.fromPlan(it, runtimeFastModels) }
                .distinctBy { it.id }
        }

        // The API key catalog includes audio and embedding models. Use the runtime's
        // Codex catalog for compatibility, capabilities and ordering.
        val available = catalog.getJSONArray("data").objects().map { it.string("id") }.toSet()
        return runtimeModels.filter { it.id in available }.distinctBy { it.id }
    }

    private fun updateTokens(profile: JSONObject, response: JSONObject, refreshing: Boolean) {
        if (response.has("scope") || !refreshing) profile.put("scope", response.optString("scope"))
        if (response.has("access_token")) {
            require(response.optString("token_type").equals("Bearer", true)) { uiString(R.string.unsupported_authorization_credential_type) }
            val lifetime = response.getLong("expires_in")
            require(lifetime in 1..Int.MAX_VALUE.toLong()) { uiString(R.string.invalid_credential_expiration) }
            profile.put("access", response.getString("access_token"))
                .put("expires", System.currentTimeMillis() + 1000L * lifetime)
        } else if (refreshing || profile.optString("scope").split(' ').contains(CodexAuthOAuth.PLAN_SCOPE)) {
            throw IOException(uiString(R.string.no_plan_access_token_received))
        }
        if (response.has("refresh_token")) profile.put("refresh", response.getString("refresh_token"))
    }

    private fun clearTokens(profile: JSONObject) {
        listOf("identity", "access", "refresh", "scope", "expires").forEach(profile::remove)
    }

    private fun permitsPlan(profile: JSONObject): Boolean =
        profile.has("identity") && profile.optString("access").isNotBlank() &&
            CodexAuthOAuth.PLAN_SCOPE in profile.optString("scope").split(' ')

    companion object {
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        const val APP_NAME = "Pocket Studio"
        private val lock = Any()
        private val loginLock = Any()
        private var pending: ServerSocket? = null
        private val terminalRefreshErrors = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    }
}
