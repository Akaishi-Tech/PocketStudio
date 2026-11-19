package com.hcgstudio.pocketstudio

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/** One lazily started language server for an open editor, communicating over raw stdio. */
internal class LinuxCompletion(private val project: LinuxProject, private val path: String, private val language: EditorLanguage) {
    private val lock = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private var requestId = 0
    private var version = 0
    private var savedText: String? = null
    private var incremental = false
    private val root = Uri.Builder().scheme("file").encodedAuthority("").path("/home/studio/projects/${project.name}").build()
    private val document = root.buildUpon().apply { path.split('/').forEach { appendPath(it) } }.build().toString()

    fun close() {
        closed = true
        process?.let { thread(isDaemon = true, name = "lsp-close") { it.destroy() } }
    }

    private fun send(body: JSONObject) {
        val bytes = body.put("jsonrpc", "2.0").toString().toByteArray()
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun receive(): JSONObject {
        var length = -1
        var headerBytes = 0
        while (true) {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("Language server closed its output")
                if (++headerBytes > 8192) throw IOException("Invalid LSP headers")
                if (b == 10) break
                if (b != 13) line.append(b.toChar())
            }
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.toString().substringAfter(':').trim().toInt()
        }
        if (length !in 1..8_388_608) throw IOException("Invalid LSP body size")
        val bytes = ByteArray(length)
        java.io.DataInputStream(input).readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun notify(method: String, params: JSONObject) = send(json("method" to method, "params" to params))
    private fun call(method: String, params: JSONObject): Any? {
        val id = ++requestId
        send(json("id" to id, "method" to method, "params" to params))
        while (true) {
            val message = receive()
            if (message.has("method")) {
                if (message.has("id")) {
                    val response = json("id" to message.get("id"))
                    when (message.getString("method")) {
                        "workspace/configuration" -> {
                            val items = message.optJSONObject("params")?.optJSONArray("items")
                            response.put("result", JSONArray().apply { repeat(items?.length() ?: 0) { put(JSONObject.NULL) } })
                        }
                        "client/registerCapability", "window/workDoneProgress/create" -> response.put("result", JSONObject.NULL)
                        else -> response.put("error", json("code" to -32601, "message" to "Unsupported client method"))
                    }
                    send(response)
                }
            } else if (message.optInt("id", -1) == id) {
                message.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
                return message.opt("result")
            }
        }
    }

    private fun update(text: String) {
        val doc = json("uri" to document)
        if (savedText == null) {
            val capabilities = json("general" to json("positionEncodings" to JSONArray().put("utf-16")),
                "textDocument" to json("completion" to json("completionItem" to json("snippetSupport" to false))))
            val init = call("initialize", json("processId" to JSONObject.NULL, "rootUri" to root.toString(),
                "capabilities" to capabilities, "workspaceFolders" to JSONArray().put(json("uri" to root.toString(), "name" to project.name)))) as JSONObject
            val sync = init.getJSONObject("capabilities").opt("textDocumentSync")
            incremental = sync == 2 || (sync is JSONObject && sync.optInt("change") == 2)
            notify("initialized", JSONObject())
            notify("textDocument/didOpen", json("textDocument" to doc.put("languageId", language.id)
                .put("version", ++version).put("text", text)))
        } else if (savedText != text) {
            val change = json("text" to text)
            if (incremental) {
                val old = savedText!!
                val last = old.lastIndexOf('\n')
                change.put("range", json("start" to json("line" to 0, "character" to 0),
                    "end" to json("line" to old.count { it == '\n' }, "character" to old.length - last - 1)))
            }
            notify("textDocument/didChange", json("textDocument" to doc.put("version", ++version),
                "contentChanges" to JSONArray().put(change)))
        }
        savedText = text
    }

    suspend fun complete(text: String, line: Int, column: Int): List<String> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(!closed)
                if (process == null) {
                    val child = project.start(language.server.toTypedArray())
                    process = child
                    if (closed) { child.destroy(); throw CancellationException() }
                    input = child.inputStream.buffered()
                    output = child.outputStream
                    thread(isDaemon = true, name = "lsp-stderr") {
                        runCatching { child.errorStream.use { stream ->
                            val buffer = ByteArray(4096)
                            while (stream.read(buffer) >= 0) { /* keep server logging from blocking stdout */ }
                        } }
                    }
                }
                val child = process!!
                // Cancelling a read on a remote pipe requires closing the process.
                val finished = CompletableDeferred<Unit>()
                try {
                    withTimeout(60_000) {
                        coroutineScope {
                            val stop = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                                try { finished.await() } finally { if (!finished.isCompleted) child.destroy() }
                            }
                            try {
                                update(text)
                                val response = call("textDocument/completion", json("textDocument" to json("uri" to document),
                                    "position" to json("line" to line - 1, "character" to column - 1)))
                                val items = when (response) {
                                    is JSONArray -> response
                                    is JSONObject -> response.optJSONArray("items")
                                    else -> null
                                }
                                (0 until minOf(items?.length() ?: 0, 200)).mapNotNull {
                                    val item = items!!.optJSONObject(it) ?: return@mapNotNull null
                                    if (item.optInt("insertTextFormat") == 2) return@mapNotNull null
                                    item.optString("insertText").ifBlank { item.optString("label") }.takeIf { it.isNotBlank() }
                                }.distinct()
                            } finally { finished.complete(Unit); stop.cancel() }
                        }
                    }
                } catch (failure: Exception) {
                    child.destroy()
                    process = null
                    savedText = null
                    throw failure
                }
            }
        }
}
