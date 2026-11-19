package com.hcgstudio.pocketstudio.codexplugin

import android.content.Context
import android.util.Base64
import com.hcgstudio.pocketstudio.agent.AgentToolContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.io.File

object CodexProtocolChecks {
    fun run(context: Context, plugin: Context, resources: Context, scenario: String): String {
        initializeCodex(context, resources)
        val runtime = CodexRuntime(context, plugin)
        runtime.requireInstalled()
        val cwd = File(context.cacheDir, "codex-handshake").apply { mkdirs() }
        if (scenario == "codexNetwork") {
            // An explicitly invalid key exercises DNS, TLS and streamed errors without charging an account.
            val settings = listOf("model_provider=\"probe\"", "model_providers.probe.name=\"Probe\"",
                "model_providers.probe.base_url=\"https://api.openai.com/v1\"", "model_providers.probe.env_key=\"PROBE_KEY\"",
                "model_providers.probe.supports_websockets=false", "model_providers.probe.request_max_retries=0")
            val process = runtime.launch(cwd, mapOf("PROBE_KEY" to "invalid-test-credential"), settings.flatMap { listOf("-c", it) })
            CodexRpc(process).use { rpc ->
                runBlocking {
                    rpc.initialize()
                    val thread = rpc.request("thread/start", json("cwd" to cwd.path, "model" to "gpt-5",
                        "ephemeral" to true)).getJSONObject("thread").getString("id")
                    rpc.request("turn/start", json("threadId" to thread, "input" to JSONArray().put(json("type" to "text", "text" to "test"))))
                    withTimeout(45_000) {
                        while (true) {
                            val event = rpc.events.receive()
                            val params = event.getJSONObject("params")
                            val failure = when (event.string("method")) {
                                "error" -> if (params.optBoolean("willRetry")) null else params.getJSONObject("error")
                                "turn/completed" -> params.getJSONObject("turn").optJSONObject("error")
                                else -> null
                            }
                            if (failure != null) {
                                val detail = failure.toString()
                                check("401" in detail || "unauthorized" in detail.lowercase()) { detail }
                                break
                            }
                        }
                    }
                }
            }
            return "PASS OpenAI API rejected the deliberately invalid key over authenticated HTTPS"
        }
        val boot = runtime.launch(cwd)
        if (boot.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            error("Codex exited ${boot.exitValue()}: " + boot.errorStream.bufferedReader().readText().takeLast(1500))
        }
        CodexRpc(boot).use { rpc ->
            runBlocking {
                rpc.initialize()
                check(rpc.request("model/list").has("data"))
            }
        }
        val bytes = ByteArray(128) { it.toByte() }
        val items = toolContentItems(listOf(AgentToolContent.Text("screen"), AgentToolContent.Image("image/png", bytes)))
        val text = items.getJSONObject(0)
        check(text.getString("type") == "inputText" && text.getString("text") == "screen")
        val image = items.getJSONObject(1)
        val url = image.getString("imageUrl")
        check(image.getString("type") == "inputImage" && url.startsWith("data:image/png;base64,") && '\n' !in url)
        check(Base64.decode(url.substringAfter(','), Base64.DEFAULT).contentEquals(bytes))
        return "PASS installed Codex $pluginVersion initialize, model/list and multimodal tool output"
    }
}
