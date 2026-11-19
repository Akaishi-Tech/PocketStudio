package com.hcgstudio.pocketstudio.codexplugin

import com.hcgstudio.pocketstudio.agent.DeviceCode

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
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64

/** Uses isolated account storage and a local CLI protocol fixture. */
object CodexAuthChecks {
    @JvmStatic fun run(context: Context, resources: Context) {
        initializeCodex(context, resources)
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
        check(legacy.signedInAccounts.isEmpty())
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
        check(signedIn.signedInAccounts == listOf(profile))
        val savedHome = checkNotNull(auth.cliHome(profile))
        check(File(savedHome, "auth.json").readText() == "fixture CLI credentials")
        check(reopened.snapshot() == signedIn)
        check(runCatching { auth.credential(signedIn) }.isFailure)
        val both = login(cli.copy(workspaceId = "workspace-two"), "other workspace")
        val other = checkNotNull(both.selected)
        val otherHome = checkNotNull(auth.cliHome(other))
        check(other.id != profile.id && otherHome != savedHome)
        check(both.signedInAccounts.toSet() == setOf(profile, other))
        check(File(savedHome, "auth.json").readText() == "fixture CLI credentials")
        auth.selectAccount(profile.id)
        check(File(savedHome, "auth.json").delete()) // The CLI clears its credentials on logout.
        auth.markSignedOut(id)
        val signedOut = reopened.snapshot()
        check(signedOut.selected?.signedIn == false && signedOut.accountKey == signedIn.accountKey)
        check(signedOut.signedInAccounts == listOf(other))
        reopened.selectAccount(other.id)
        check(runCatching { auth.selectAccount(id) }.isFailure)
        check(auth.snapshot().selected == other)
        check(File(otherHome, "auth.json").delete())
        reopened.markSignedOut(other.id)
        check(auth.snapshot().signedInAccounts.isEmpty())
        val restored = login(cli, "new credentials")
        check(restored.selected == profile && auth.cliHome(profile) == savedHome)
        check(restored.signedInAccounts == listOf(profile) && reopened.snapshot() == restored)
        auth.saveApiConfiguration("", key)
        check(auth.credential(reopened.snapshot()) == key)
        reopened.removeApiKey()
        check(!auth.snapshot().hasApiKey && runCatching { auth.credential(legacy) }.isFailure)
    }

    private val cliAccount = CodexCliAccount("account@example.test", "fixture-user", "workspace-one")
    private const val verificationUrl = "https://auth.openai.com/codex/device"
    private const val networkError = "error sending request for url (https://auth.openai.com/api/accounts/deviceauth/token)"

    private suspend fun checkCliLogin(home: File) {
        val authorizationUrl = "https://auth.openai.com/oauth/authorize?state=fixture"
        withCli(home, {
            val request = receive("account/login/start")
            check(request.getJSONObject("params").getString("type") == "chatgpt")
            reply(request, json("type" to "chatgpt", "loginId" to "browser", "authUrl" to authorizationUrl))
            complete("other", "Unrelated sign-in failed")
            complete("browser")
            readAccount()
        }) { cli ->
            val opened = mutableListOf<String>()
            check(cli.loginWithBrowser(home) { opened.add(it) } == cliAccount)
            check(opened == listOf(authorizationUrl))
        }

        withCli(home, {
            start("first")
            complete("other", networkError)
            complete("first")
            readAccount()
        }) { cli ->
            val codes = mutableListOf<DeviceCode>()
            check(cli.loginWithDeviceCode(home) { codes.add(it) } == cliAccount)
            check(codes == listOf(DeviceCode(verificationUrl, "first")))
        }

        withCli(home, {
            start("first")
            val stop = receive("account/login/cancel")
            check(stop.getJSONObject("params").getString("loginId") == "first")
            reply(stop, json("status" to "canceled"))
        }) { cli ->
            coroutineScope {
                val displayed = CompletableDeferred<DeviceCode>()
                val login = async { cli.loginWithDeviceCode(home) { displayed.complete(it) } }
                check(displayed.await() == DeviceCode(verificationUrl, "first"))
                login.cancelAndJoin()
            }
        }

        for (error in listOf("Device authorization denied", "error sending request for url (https://auth.openai.com/oauth/token)")) {
            withCli(home, {
                start("first")
                complete("first", error)
            }) { cli ->
                val result = runCatching { cli.loginWithDeviceCode(home, awaitRetry = { error("Unexpected retry") }) { } }
                check(result.exceptionOrNull() is IOException && result.exceptionOrNull()?.message == error)
            }
        }

        checkInterruptedLogin(home)
        checkInterruptedLogin(home, cancelWhileWaiting = true)
        checkInterruptedLogin(home, failAgain = true)
    }

    private suspend fun checkInterruptedLogin(home: File, cancelWhileWaiting: Boolean = false, failAgain: Boolean = false) {
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        withCli(home, {
            start("first")
            complete("first", networkError)
            if (!cancelWhileWaiting) {
                start("second")
                check(resume.isCompleted)
                complete("first", "Late completion for the previous code")
                complete("second", if (failAgain) networkError else null)
                if (!failAgain) readAccount()
            }
        }) { cli ->
            coroutineScope {
                val codes = mutableListOf<DeviceCode>()
                val login = async {
                    runCatching {
                        cli.loginWithDeviceCode(home, awaitRetry = {
                            check(waiting.complete(Unit)) { "Login retried more than once" }
                            resume.await()
                        }) { codes.add(it) }
                    }
                }
                waiting.await()
                check(codes == listOf(DeviceCode(verificationUrl, "first")))
                if (cancelWhileWaiting) {
                    login.cancelAndJoin()
                } else {
                    resume.complete(Unit)
                    val result = login.await()
                    if (failAgain) {
                        val failure = result.exceptionOrNull()
                        check(failure is IOException && failure.message == uiString(R.string.codex_login_network_error))
                    } else {
                        check(result.getOrThrow() == cliAccount)
                    }
                    check(codes == listOf(DeviceCode(verificationUrl, "first"), DeviceCode(verificationUrl, "second")))
                }
            }
        }
    }

    private suspend fun withCli(home: File, serve: LoginFixture.() -> Unit, exercise: suspend (CodexCliSession) -> Unit) = coroutineScope {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 6_000
            val server = async(Dispatchers.IO) {
                listener.accept().use { socket ->
                    socket.soTimeout = 6_000
                    LoginFixture(socket).run {
                        reply(receive("initialize"), JSONObject())
                        receive("initialized")
                        serve()
                        expectClosed()
                    }
                }
            }
            val cli = CodexCliSession { directory ->
                check(directory == home)
                ProcessBuilder("/system/bin/toybox", "nc", "127.0.0.1", listener.localPort.toString()).start()
            }
            exercise(cli)
            server.await()
        }
    }

    private class LoginFixture(socket: Socket) {
        private val input = socket.getInputStream().bufferedReader()
        private val output = socket.getOutputStream().bufferedWriter()

        fun start(id: String) {
            val request = receive("account/login/start")
            check(request.getJSONObject("params").getString("type") == "chatgptDeviceCode")
            reply(request, json("type" to "chatgptDeviceCode", "loginId" to id,
                "userCode" to id, "verificationUrl" to verificationUrl))
        }

        fun complete(id: String, error: String? = null) {
            send(json("method" to "account/login/completed", "params" to json("loginId" to id,
                "success" to (error == null), "error" to error)))
        }

        fun readAccount() {
            reply(receive("account/read"), json("account" to json("type" to "chatgpt", "email" to cliAccount.email,
                "planType" to "plus"), "userId" to cliAccount.userId,
                "workspaceRouting" to json("chatgptAccountId" to cliAccount.workspaceId)))
        }

        fun expectClosed() {
            val extra = input.readLine()
            check(extra == null) { "Unexpected request after login ended: $extra" }
        }

        fun receive(method: String): JSONObject = JSONObject(input.readLine()).also {
            check(it.getString("method") == method) { "Expected $method, received $it" }
        }

        fun reply(request: JSONObject, result: JSONObject) = send(json("id" to request.get("id"), "result" to result))

        private fun send(message: JSONObject) {
            output.write(message.toString())
            output.newLine()
            output.flush()
        }
    }
}
