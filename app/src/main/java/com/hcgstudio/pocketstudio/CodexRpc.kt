package com.hcgstudio.pocketstudio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value) }
}
internal fun JSONObject.string(key: String): String =
    if (isNull(key)) "" else optString(key)
internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

/** One NDJSON connection. Responses, notifications and server requests share the same stream. */
internal class CodexRpc(private val process: Process) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = process.outputStream.bufferedWriter()
    private val writing = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicInteger()
    val events = Channel<JSONObject>(Channel.UNLIMITED)
    @Volatile private var failure: Throwable? = null

    init {
        scope.launch { runCatching { process.errorStream.use { stream ->
            val buffer = ByteArray(4096)
            while (stream.read(buffer) != -1) Unit
        } } }
        scope.launch {
            var end: Throwable = IOException(uiString(R.string.the_codex_process_exited))
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val message = JSONObject(line)
                        if (message.has("method")) events.send(message)
                        else pending.remove(message.optInt("id", -1))?.let { request ->
                            val error = message.optJSONObject("error")
                            if (error == null) request.complete(message.optJSONObject("result") ?: JSONObject())
                            else request.completeExceptionally(IOException(error.optString("message", uiString(R.string.codex_request_failed))))
                        }
                    }
                }
            } catch (e: Exception) { end = e }
            finally {
                failure = end
                pending.values.forEach { it.completeExceptionally(end) }
                events.close(end)
            }
        }
    }

    private suspend fun send(message: JSONObject) = withContext(Dispatchers.IO) {
        writing.withLock {
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }
    }
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = sequence.incrementAndGet()
        val answer = CompletableDeferred<JSONObject>()
        pending[id] = answer
        try {
            failure?.let { throw it }
            send(json("id" to id, "method" to method, "params" to params))
            return withTimeout(60_000) { answer.await() }
        } finally { pending.remove(id) }
    }
    suspend fun initialize() {
        request("initialize", json("clientInfo" to json("name" to CodexAuth.APP_NAME,
            "title" to uiString(R.string.app_name), "version" to BuildConfig.VERSION_NAME),
            "capabilities" to json("experimentalApi" to true)))
        send(json("method" to "initialized"))
    }
    suspend fun answer(id: Any, result: JSONObject) = send(json("id" to id, "result" to result))
    suspend fun unsupported(id: Any) = send(json("id" to id,
        "error" to json("code" to -32601, "message" to uiString(R.string.this_client_does_not_support_the_request))))
    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        scope.cancel()
    }
}
