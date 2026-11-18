package com.hcgstudio.pocketstudio

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** The controller uses real app-server processes. The only model endpoint is a loopback fixture. */
object CodexChatChecks {
    private class FixtureApp(real: Context, val state: File, val prefix: String) : Application() {
        private val preferenceFiles = mutableSetOf<String>()
        init { attachBaseContext(real) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = state
        @Synchronized override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceFiles.add(prefix + name)
            return baseContext.getSharedPreferences(prefix + name, mode)
        }
        @Synchronized fun cleanPreferences() { preferenceFiles.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    @JvmStatic fun run(ctx: Context, port: Int, keep: Boolean): String = runBlocking {
        require(port in 1..65535) { "A loopback fixture port is required" }
        val token = UUID.randomUUID().toString()
        val app = FixtureApp(ctx.applicationContext, Files.createTempDirectory(ctx.noBackupFilesDir.toPath(), "chat-check-").toFile(),
            "codex-chat-check-$token-")
        val imports = File(ctx.filesDir, "imports").apply { mkdirs() }
        val directory = Files.createTempDirectory(imports.toPath(), "Codex-Android-").toFile()
        val sample = File(directory, "sample.txt").apply { writeText("one") }
        val uri = Uri.fromFile(directory.canonicalFile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val workspace = withContext(Dispatchers.Main) { Workspace(app, SavedStateHandle()) }
        // Rollout and database entries include absolute paths. Kept histories must start in
        // their final project partition. Credentials and all other test state stay isolated.
        val historyRoot = if (keep) ctx.noBackupFilesDir else app.state
        val partition = MessageDigest.getInstance("SHA-256").digest(("$uri|api").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val history = File(historyRoot, "codex-projects/$partition")
        check(!history.exists())
        val controller = CodexController(workspace, scope, "http://127.0.0.1:$port/v1", historyRoot)
        var retained = false
        try {
            // This storage resolves exclusively inside the per-run test directory.
            CodexAuth(app).apply { setUseChatGpt(false); saveApiKey("local-fixture-only") }
            withContext(Dispatchers.Main) {
                workspace.openProject(uri)
                await("project open") { !workspace.busy }
                val project = checkNotNull(workspace.project) { workspace.message.orEmpty() }
                check(project.root.uri == uri)
                controller.enter(project)
                await("standalone runtime and initial chat list") {
                    check(controller.error == null) { controller.error.orEmpty() }
                    controller.checked && controller.version != null && controller.usable && !controller.loading
                }
                controller.newChat()
                await("failed catalog request") { controller.modelsError != null && !controller.modelsLoading }
                check(controller.selectedModel == null)
                controller.refreshModels()
                await("account model catalog") {
                    check(controller.modelsError == null) { controller.modelsError.orEmpty() }
                    controller.selectedModel != null && !controller.modelsLoading
                }
                check(controller.models.map { it.id }.toSet() == setOf("gpt-5.5", "gpt-6.2-sol"))
                controller.chooseModel("gpt-6.2-sol")
                check(controller.selectedModel?.supportsFast == true)
                controller.chooseModel("tts-1")
                check(controller.selectedModel?.id == "gpt-6.2-sol")
                controller.chooseEffort("high")
                controller.setFast(true)
                check(controller.effort == "high" && controller.fast)
                controller.input = "请将 sample.txt 从 one 修改为 two。"
                controller.send()
                check(controller.working) { "Controller did not start the turn" }
                await("tool calls and streamed response") { !controller.working }
                check(controller.error == null) { controller.error.orEmpty() }
                checkTranscript(controller)
                val id = checkNotNull(controller.chatId)
                controller.back()
                await("fresh app-server history list") {
                    check(controller.error == null) { controller.error.orEmpty() }
                    !controller.loading && controller.chats.any { it.id == id }
                }
                controller.open(controller.chats.single { it.id == id })
                await("history items and model choices") { !controller.loading && !controller.modelsLoading }
                check(controller.error == null) { controller.error.orEmpty() }
                checkTranscript(controller)
                controller.chooseModel("gpt-5.5")
                check(controller.selectedModel?.id == "gpt-5.5")
                check("ultra" !in checkNotNull(controller.selectedModel).efforts)
                controller.chooseEffort("low")
                controller.chooseEffort("ultra")
                controller.setFast(false)
                check(controller.effort == "low" && !controller.fast)
                controller.chooseModel("gpt-6.2-sol")
                check(controller.effort == "high" && controller.fast)
                controller.chooseModel("gpt-5.5")
                check(controller.effort == "low" && !controller.fast)
                controller.input = "请确认修改已完成。"
                controller.send()
                check(controller.working)
                await("new settings on the resumed chat") { !controller.working }
                check(controller.error == null && controller.chatId == id) { controller.error.orEmpty() }
            }
            check(sample.readText() == "two") { "The selected project's file did not change" }
            if (keep) {
                val rootUri = Project(ctx, uri).root.uri
                check(rootUri == uri && File(history, "sessions").isDirectory)
                withContext(Dispatchers.Main) {
                    val library = Workspace(ctx.applicationContext as Application, SavedStateHandle())
                    try { library.rememberProject(rootUri, directory.name) }
                    finally { library.devices.close() }
                }
                val prefs = ctx.getSharedPreferences("studio", Context.MODE_PRIVATE)
                val recent = checkNotNull(prefs.getString("recentProjects", null))
                check(org.json.JSONArray(recent).getJSONArray(0).getString(0) == rootUri.toString())
                // Instrumentation terminates its process immediately after finish(), bypassing Activity.onStop.
                check(prefs.edit().putString("recentProjects", recent).commit())
                val onDisk = File(ctx.applicationInfo.dataDir, "shared_prefs/studio.xml")
                check(directory.name in onDisk.readText()) { "Recent project was not committed to the target app's preferences" }
                retained = true
            }
            "PASS model catalog retry and filtering, turn settings, project read/write, Markdown and resumed chat; " +
                if (retained) "fixture project ${directory.name}" else "fixture removed"
        } finally {
            withContext(Dispatchers.Main) { controller.dispose(); workspace.closeProject(); workspace.devices.close(); scope.cancel() }
            app.cleanPreferences()
            app.state.deleteRecursively()
            if (!retained) { directory.deleteRecursively(); history.deleteRecursively() }
        }
    }

    private fun checkTranscript(controller: CodexController) {
        val lines = controller.lines
        check(lines.any { it.kind == ChatKind.User && it.text.contains("sample.txt") })
        val fileTools = lines.filter { it.kind == ChatKind.Activity && "sample.txt" in it.text }
        check(fileTools.any { "revision" in it.detail } && fileTools.any { it.detail == "two" }) {
            "Tool activities missing: " + lines.map { it.kind to it.category }
        }
        check(lines.none { it.failed })
        check(lines.any { it.kind == ChatKind.Assistant && it.text.startsWith("### ") }) { "Markdown reply missing" }
    }

    private suspend fun await(stage: String, ready: () -> Boolean) {
        val complete = withTimeoutOrNull(90_000) { while (!ready()) delay(50); true } ?: false
        check(complete) { "Timed out waiting for $stage" }
    }
}
