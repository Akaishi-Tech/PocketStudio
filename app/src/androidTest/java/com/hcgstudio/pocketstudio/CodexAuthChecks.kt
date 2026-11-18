package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the real loopback listener and Android Keystore without contacting a provider. */
object CodexAuthChecks {
    @JvmStatic fun run(context: Context) {
        checkPlanModels()
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "codex-auth-check-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
        }
        val auth = CodexAuth(isolated)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val key = "fixture-credential-only"
            // Accounts saved before custom endpoints have only a key and a mode.
            CodexAuthStorage(isolated).write(JSONObject().put("apiKey", key).put("useChatGpt", false))
            val reopened = CodexAuth(isolated)
            val legacy = reopened.snapshot()
            check(reopened.credential(legacy) == key && !legacy.useChatGpt && legacy.apiEndpoint == CodexAuthOAuth.RESOURCE)
            check(dir.listFiles().orEmpty().none { key in it.readText(Charsets.ISO_8859_1) })
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
            auth.setUseChatGpt(true)
            check(reopened.snapshot().responsesUrl == CodexAuthOAuth.RESOURCE)
            auth.saveApiConfiguration("", key)
            check(reopened.snapshot() == legacy)
            reopened.removeApiKey()
            check(!auth.snapshot().hasApiKey && runCatching { auth.credential(legacy) }.isFailure)
            check(CodexAuthOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") ==
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
            val browser = ArrayBlockingQueue<Uri>(1)
            val attempt = worker.submit(Callable { runCatching { auth.login { browser.put(it) } }.exceptionOrNull() })
            val request = checkNotNull(browser.poll(5, TimeUnit.SECONDS))
            check(request.host == "auth.openai.com" && request.getQueryParameter("client_id") == "dynamic_agent_client")
            check(request.getQueryParameter("code_challenge_method") == "S256")
            val host = request.getQueryParameter("ext_agent_host_id")
            check(host?.startsWith("urn:uuid:") == true)
            val redirect = Uri.parse(request.getQueryParameter("redirect_uri"))
            check(redirect.host == "127.0.0.1" && redirect.path == "/auth/callback")
            check(deny(redirect, "incorrect-state").startsWith("HTTP/1.1 400"))
            check(deny(redirect, checkNotNull(request.getQueryParameter("state"))).startsWith("HTTP/1.1 200"))
            check(attempt.get(5, TimeUnit.SECONDS)?.message == context.getString(R.string.chatgpt_authorization_is_incomplete))
            val next = worker.submit(Callable { runCatching { reopened.login { browser.put(it) } }.isFailure })
            check(browser.poll(5, TimeUnit.SECONDS)?.getQueryParameter("ext_agent_host_id") == host)
            auth.cancelLogin()
            check(next.get(5, TimeUnit.SECONDS) && auth.snapshot().accounts.isEmpty())
        } finally {
            auth.cancelLogin()
            worker.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun checkPlanModels() {
        val row = JSONObject("""{
            "visibility":"list","slug":"plan-model","display_name":"Plan model",
            "supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],
            "default_reasoning_level":"high","service_tiers":[{"id":"priority","name":"Fast"}]
        }""")
        val fastModels = setOf("plan-model", "new-model")
        val model = CodexModel("plan-model", "Plan model", listOf("low", "high"), "high", true)
        check(CodexModel.fromPlan(row, fastModels) == model)
        check(CodexModel.fromPlan(row, emptySet()) == model.copy(supportsFast = false))
        row.put("visibility", "hide")
        check(CodexModel.fromPlan(row, fastModels) == null)

        val sparse = JSONObject("""{"visibility":"list","slug":"new-model","default_reasoning_level":"high"}""")
        check(CodexModel.fromPlan(sparse, fastModels) == CodexModel("new-model", "new-model", emptyList(), null, false))
        sparse.put("additional_speed_tiers", JSONArray().put("fast"))
        check(CodexModel.fromPlan(sparse, fastModels)?.supportsFast == true)
        sparse.put("service_tiers", JSONArray().put(JSONObject().put("id", "ultrafast")))
        check(CodexModel.fromPlan(sparse, fastModels)?.supportsFast == false)
    }

    private fun deny(redirect: Uri, state: String): String = Socket("127.0.0.1", redirect.port).use { socket ->
        socket.soTimeout = 3000
        val path = "${redirect.encodedPath}?state=$state&error=access_denied"
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readLine()
    }
}
