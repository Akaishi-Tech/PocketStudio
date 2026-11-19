package com.hcgstudio.pocketstudio.codexplugin

import com.hcgstudio.pocketstudio.agent.*

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal class ApiConfigurationError(val reason: Int) : IllegalArgumentException(uiString(reason))

internal data class CodexAccount(val id: String, val label: String, val signedIn: Boolean, val subject: String)

internal data class CodexAuthState(
    val signedInAccounts: List<CodexAccount>, val selected: CodexAccount?, val hasApiKey: Boolean,
    val useChatGpt: Boolean, val apiEndpoint: String
) {
    val accountKey: String get() = when {
        useChatGpt -> "plan-${selected?.id.orEmpty()}"
        apiEndpoint == CodexAuth.DEFAULT_API_ENDPOINT -> "api"
        else -> "api-$apiEndpoint"
    }
}

internal class CodexAuth(context: Context) {
    private val app = context.applicationContext
    private val storage = CodexAuthStorage(app)

    fun snapshot(): CodexAuthState = synchronized(lock) {
        val state = storage.read()
        val profiles = state.optJSONObject("accounts") ?: JSONObject()
        // Earlier versions saved OAuth tokens issued to Pocket Studio. Keep their
        // account IDs for local history, and require a fresh grant to the CLI.
        var migrated = state.remove("host") != null
        profiles.keys().forEach { id ->
            val profile = profiles.getJSONObject(id)
            if (legacyFields.any(profile::has)) {
                migrateIdentity(profile)
                legacyFields.forEach(profile::remove)
                migrated = true
            }
        }
        if (migrated) storage.write(state)
        val accounts = profiles.keys().asSequence().map { id ->
            val profile = profiles.getJSONObject(id)
            CodexAccount(id, profile.getString("label"), profile.optBoolean("cliSignedIn"), profile.optString("sub", id))
        }.toList().sortedBy { it.label }
        // The last selected profile still identifies its local chat history after logout.
        CodexAuthState(accounts.filter { it.signedIn }, accounts.find { it.id == state.optString("selected") },
            state.optString("apiKey").isNotBlank(), state.optBoolean("useChatGpt", true),
            state.optString("apiEndpoint", DEFAULT_API_ENDPOINT))
    }

    fun saveApiConfiguration(endpoint: String, key: String) = synchronized(lock) {
        val url = normalizeApiEndpoint(endpoint)
        val replacement = key.trim()
        if (replacement.any { it.code !in 33..126 }) throw ApiConfigurationError(R.string.the_api_key_contains_invalid_characters)
        val state = storage.read()
        if (replacement.isEmpty()) {
            if (state.optString("apiKey").isBlank()) throw ApiConfigurationError(R.string.set_an_api_key_first)
            val previous = URI(state.optString("apiEndpoint", DEFAULT_API_ENDPOINT))
            val next = URI(url)
            if (previous.scheme != next.scheme || previous.rawAuthority != next.rawAuthority)
                throw ApiConfigurationError(R.string.enter_key_for_new_endpoint)
        } else state.put("apiKey", replacement)
        storage.write(state.put("apiEndpoint", url).put("useChatGpt", false))
    }

    fun removeApiKey() = synchronized(lock) {
        val state = storage.read()
        state.remove("apiKey")
        storage.write(state)
    }

    fun selectAccount(id: String) = synchronized(lock) {
        val state = storage.read()
        require(state.optJSONObject("accounts")?.optJSONObject(id)?.optBoolean("cliSignedIn") == true) {
            uiString(R.string.sign_in_to_use_this_account)
        }
        storage.write(state.put("selected", id).put("useChatGpt", true))
    }

    fun newLoginHome(): File = Files.createTempDirectory(app.cacheDir.toPath(), "codex-login-").toFile()

    private fun profileHome(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(app.noBackupFilesDir, "codex-cli/$hash")
    }

    fun cliHome(profile: CodexAccount): File? = profileHome(profile.id).takeIf { it.isDirectory }

    fun acceptCliLogin(home: File, account: CodexCliAccount): CodexAuthState = synchronized(lock) {
        val state = storage.read()
        val profiles = state.optJSONObject("accounts") ?: JSONObject()
        val matches = profiles.keys().asSequence().filter { id ->
            val profile = profiles.getJSONObject(id)
            profile.optString("cliUserId", profile.optString("sub")) == account.userId
        }.toList()
        val existingId = matches.firstOrNull { profiles.getJSONObject(it).string("workspaceId") == account.workspaceId.orEmpty() }
            ?: matches.singleOrNull { !profiles.getJSONObject(it).has("workspaceId") }
        val id = existingId ?: UUID.randomUUID().toString()
        val directory = Files.createDirectories(profileHome(id).toPath())
        // Both processes use the CLI's file credential store. Publish its completed
        // login atomically, leaving the previous login intact on cancellation.
        Files.move(File(home, "auth.json").toPath(), directory.resolve("auth.json"),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val profile = profiles.optJSONObject(id) ?: JSONObject()
        if (!profile.has("sub")) profile.put("sub", account.userId ?: id)
        profile.put("cliUserId", account.userId).put("workspaceId", account.workspaceId).put("cliSignedIn", true)
            .put("label", account.email ?: profile.optString("label", uiString(R.string.chatgpt_account)))
        profiles.put(id, profile)
        storage.write(state.put("accounts", profiles).put("selected", id).put("useChatGpt", true))
        snapshot()
    }

    fun markSignedOut(id: String) = synchronized(lock) {
        val state = storage.read()
        state.optJSONObject("accounts")?.optJSONObject(id)?.remove("cliSignedIn")
        storage.write(state)
    }

    fun credential(state: CodexAuthState): String = synchronized(lock) {
        check(!state.useChatGpt)
        val stored = storage.read()
        check(stored.optString("apiEndpoint", DEFAULT_API_ENDPOINT) == state.apiEndpoint) {
            uiString(R.string.api_endpoint_changed)
        }
        stored.optString("apiKey").ifBlank { throw IOException(uiString(R.string.set_an_api_key_first)) }
    }

    fun apiModels(state: CodexAuthState, runtimeModels: List<AgentModel>): List<AgentModel> {
        val catalog = apiCatalog(state.apiEndpoint, credential(state))
        // The API key catalog also contains audio and embedding models.
        val available = catalog.getJSONArray("data").objects().map { it.string("id") }.filter(String::isNotBlank).toSet()
        val known = runtimeModels.filter { it.id in available }.distinctBy { it.id }
        if (state.apiEndpoint == DEFAULT_API_ENDPOINT) return known
        val otherIds = available - known.map { it.id }.toSet()
        return known + otherIds.map { AgentModel(it, it, emptyList(), null, false) }
    }

    companion object {
        const val DEFAULT_API_ENDPOINT = "https://api.openai.com/v1"
        const val USAGE_URL = "https://chatgpt.com/codex/settings/usage"
        const val APP_NAME = "Pocket Studio"
        internal var debugHost = false
        private val lock = Any()
        private val legacyFields = listOf("identity", "access", "refresh", "scope", "expires")

        private fun migrateIdentity(profile: JSONObject) {
            // The old sign-in flow verified this ID token before storing it in the
            // encrypted profile. Its OAuth subject can differ from the CLI user ID.
            val payload = profile.string("identity").split('.').getOrNull(1) ?: return
            val claims = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)) }.getOrNull() ?: return
            if (claims.string("sub") != profile.string("sub")) return
            val identity = claims.optJSONObject("https://api.openai.com/auth") ?: return
            val user = identity.string("chatgpt_user_id").ifBlank { identity.string("user_id") }
            if (user.isNotBlank()) profile.put("cliUserId", user)
            identity.string("chatgpt_account_id").takeIf(String::isNotBlank)?.let { profile.put("workspaceId", it) }
        }
        private fun normalizeApiEndpoint(value: String): String {
            val uri = runCatching { URI(value.trim().ifEmpty { DEFAULT_API_ENDPOINT }).parseServerAuthority() }.getOrNull()
            val scheme = uri?.scheme?.lowercase(Locale.ROOT)
            val host = uri?.host?.lowercase(Locale.ROOT)
            val localFixture = debugHost && scheme == "http" && host == "127.0.0.1"
            if (uri == null || host == null || (scheme != "https" && !localFixture) ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.port != -1 && uri.port !in 1..65535) || uri.rawAuthority.endsWith(':')) {
                throw ApiConfigurationError(R.string.invalid_api_endpoint)
            }
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            return "$scheme://$host$port${uri.rawPath.trimEnd('/')}"
        }
    }
}
