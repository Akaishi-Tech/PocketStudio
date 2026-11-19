package com.hcgstudio.pocketstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.IOException

/** Keep the process in the environment while Studio owns cancellation and the output window. */
internal suspend fun runLinuxProcess(project: LinuxProject, argv: Array<String>, terminal: Boolean,
                                     started: suspend (Process) -> Unit, output: suspend (String) -> Unit): Int =
    withContext(Dispatchers.IO) {
        ensureActive()
        val process = project.start(argv, terminal)
        try {
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { process.destroy() }
                }
                suspend fun drain(stream: InputStream) {
                    stream.reader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            output(String(buffer, 0, n))
                        }
                    }
                }
                try {
                    val stdout = launch { drain(process.inputStream) }
                    val stderr = launch { drain(process.errorStream) }
                    started(process)
                    val status = runInterruptible { process.waitFor() }
                    joinAll(stdout, stderr)
                    status
                } finally { cancellation.cancel() }
            }
        } finally { process.destroy() }
    }

internal class LinuxShell(private val w: Workspace, private val scope: CoroutineScope) {
    var text by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var process: Process? = null
    private var generation = 0

    fun start(project: Project) {
        val guest = project.linux ?: return
        if (running || project !== w.project) return
        running = true
        val current = ++generation
        job = scope.launch {
            try {
                if (!w.saveDocuments()) return@launch
                val status = runLinuxProcess(guest, arrayOf("env", "TERM=dumb", "PS1=\\w $ ",
                    "bash", "--noprofile", "--norc", "-i"), true, { child ->
                    withContext(Dispatchers.Main) { if (current == generation) process = child }
                }, { chunk -> withContext(Dispatchers.Main) {
                    if (current == generation) text = (text + chunk.replace("\r\n", "\n")).takeLast(100_000)
                } })
                if (current == generation) text += "\n" + w.app.getString(R.string.linux_exit, status) + "\n"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) w.report(e) }
            finally {
                if (current == generation) {
                    running = false
                    process = null
                    if (w.project === project) w.action { w.refreshAfterExternalRun(project) }
                }
            }
        }
    }

    fun send(value: String) {
        val child = process ?: return
        w.action {
            withContext(Dispatchers.IO) {
                synchronized(child) { child.outputStream.write(value.toByteArray()); child.outputStream.flush() }
            }
        }
    }

    fun stop() { job?.cancel() }
    fun reset() {
        generation++
        stop()
        running = false
        process = null
        text = ""
    }
}
