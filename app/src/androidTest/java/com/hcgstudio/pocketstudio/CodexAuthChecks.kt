package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64

/** Uses isolated account storage and a local CLI protocol fixture. */
object CodexAuthChecks {
    @JvmStatic fun run(context: Context) {
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "codex-auth-check-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getCacheDir(): File = dir
        }
        try {
            checkAccounts(isolated)
            runBlocking { withTimeout(20_000) { checkCliLogin(dir) } }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun checkAccounts(context: Context) {
        val key = "fixture-credential-only"
        val id = "saved-client"
        val claims = json("sub" to "oidc-subject",
            "https://api.openai.com/auth" to json("chatgpt_user_id" to "chatgpt-user", "chatgpt_account_id" to "workspace-one"))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray())
        val oldProfile = json("label" to "Account", "sub" to "oidc-subject", "identity" to "e30.$payload.fixture",
            "access" to "old-access", "refresh" to "old-refresh", "expires" to Long.MAX_VALUE)
        val storage = CodexAuthStorage(context)
        storage.write(json("apiKey" to key, "useChatGpt" to false, "selected" to id, "accounts" to json(id to oldProfile)))
        val auth = CodexAuth(context)
        val reopened = CodexAuth(context)
        val legacy = reopened.snapshot()
        check(reopened.credential(legacy) == key && !legacy.useChatGpt && legacy.apiEndpoint == CodexAuth.DEFAULT_API_ENDPOINT)
        val saved = checkNotNull(legacy.selected)
        check(saved.id == id && !saved.signedIn)
        val migrated = storage.read().getJSONObject("accounts").getJSONObject(id)
        check(listOf("identity", "access", "refresh").none(migrated::has))
        check(context.noBackupFilesDir.listFiles().orEmpty().filter { it.isFile }.none { key in it.readText(Charsets.ISO_8859_1) })
        val endpoint = "https://agent.example/v1"
        for (invalid in listOf("agent.example/v1", "http://agent.example/v1", "https://user@agent.example/v1",
                "$endpoint?k=secret", "$endpoint#fragment", "https://agent.example:99999/v1")) {
            check(runCatching { auth.saveApiConfiguration(invalid, "new-key") }.isFailure)
        }
        check(runCatching { auth.saveApiConfiguration(endpoint, "") }.isFailure)
        check(auth.snapshot() == legacy && auth.credential(legacy) == key)
        auth.saveApiConfiguration("HTTPS://Agent.Example:443/v1///", "new-key")
        val custom = reopened.snapshot()
        check(custom.apiEndpoint == endpoint && reopened.credential(custom) == "new-key")
        check(runCatching { reopened.credential(legacy) }.isFailure)
        auth.saveApiConfiguration("https://agent.example/service/v1", "")
        check(reopened.credential(reopened.snapshot()) == "new-key")

        fun login(identity: CodexCliAccount, credential: String): CodexAuthState {
            val home = auth.newLoginHome()
            File(home, "auth.json").writeText(credential)
            return auth.acceptCliLogin(home, identity)
        }
        val cli = CodexCliAccount("account@example.test", "chatgpt-user", "workspace-one")
        val signedIn = login(cli, "fixture CLI credentials")
        val profile = checkNotNull(signedIn.selected)
        check(signedIn.useChatGpt && profile.signedIn && profile.id == id && signedIn.accountKey == "plan-$id")
        val savedHome = checkNotNull(auth.cliHome(profile))
        check(File(savedHome, "auth.json").readText() == "fixture CLI credentials")
        check(reopened.snapshot() == signedIn)
        check(runCatching { auth.credential(signedIn) }.isFailure)
        val other = checkNotNull(login(cli.copy(workspaceId = "workspace-two"), "other workspace").selected)
        check(other.id != profile.id && auth.cliHome(other) != savedHome)
        check(File(savedHome, "auth.json").readText() == "fixture CLI credentials")
        auth.selectAccount(profile.id)
        check(File(savedHome, "auth.json").delete()) // The CLI clears its credentials on logout.
        auth.markSignedOut(id)
        val signedOut = reopened.snapshot()
        check(signedOut.selected?.signedIn == false && signedOut.accounts.single { it.id == other.id }.signedIn)
        check(login(cli, "new credentials").selected == profile && auth.cliHome(profile) == savedHome)
        auth.saveApiConfiguration("", key)
        check(auth.credential(reopened.snapshot()) == key)
        reopened.removeApiKey()
        check(!auth.snapshot().hasApiKey && runCatching { auth.credential(legacy) }.isFailure)
    }

    private suspend fun checkCliLogin(home: File) = coroutineScope {
        val verification = CodexDeviceCode("https://auth.openai.com/codex/device", "TEST-CODE")
        val account = CodexCliAccount("account@example.test", "fixture-user", "workspace-one")
        for (cancel in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 6_000
                val fixture = async(Dispatchers.IO) {
                    listener.accept().use { socket ->
                        socket.soTimeout = 6_000
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream().bufferedWriter()
                        fun send(message: JSONObject) {
                            output.write(message.toString())
                            output.newLine()
                            output.flush()
                        }
                        fun receive(method: String): JSONObject = JSONObject(input.readLine()).also {
                            check(it.getString("method") == method) { "Expected $method, received $it" }
                        }
                        fun reply(request: JSONObject, result: JSONObject) = send(json("id" to request.get("id"), "result" to result))
                        reply(receive("initialize"), JSONObject())
                        receive("initialized")
                        val start = receive("account/login/start")
                        check(start.getJSONObject("params").getString("type") == "chatgptDeviceCode")
                        reply(start, json("type" to "chatgptDeviceCode", "loginId" to "fixture", "userCode" to verification.userCode,
                            "verificationUrl" to verification.verificationUrl))
                        if (cancel) {
                            val stop = receive("account/login/cancel")
                            check(stop.getJSONObject("params").getString("loginId") == "fixture")
                            reply(stop, json("status" to "canceled"))
                        } else {
                            send(json("method" to "account/login/completed", "params" to json("loginId" to "other", "success" to false)))
                            send(json("method" to "account/login/completed", "params" to json("loginId" to "fixture", "success" to true)))
                            reply(receive("account/read"), json("account" to json("type" to "chatgpt", "email" to account.email,
                                "planType" to "plus"), "userId" to account.userId,
                                "workspaceRouting" to json("chatgptAccountId" to account.workspaceId)))
                        }
                    }
                }
                val cli = CodexCliSession { directory ->
                    check(directory == home)
                    ProcessBuilder("/system/bin/toybox", "nc", "127.0.0.1", listener.localPort.toString()).start()
                }
                val displayed = CompletableDeferred<CodexDeviceCode>()
                val login = async { cli.login(home) { displayed.complete(it) } }
                check(displayed.await() == verification)
                if (cancel) login.cancelAndJoin() else check(login.await() == account)
                fixture.await()
            }
        }
    }
}
