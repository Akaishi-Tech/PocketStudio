package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.agent.AgentSession
import com.hcgstudio.pocketstudio.agent.ChatKind
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
    private class FixtureApp(real: Context, val state: File, val prefix: String, keep: Boolean) : Application() {
        private val preferenceFiles = mutableSetOf<String>()
        private val files = if (keep) real.filesDir else File(state, "files").apply { mkdirs() }
        init { attachBaseContext(real) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
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
            "codex-chat-check-$token-", keep)
        val imports = File(app.filesDir, "imports").apply { mkdirs() }
        val directory = Files.createTempDirectory(imports.toPath(), "Codex-Android-").toFile()
        val sample = File(directory, "sample.txt").apply { writeText("one") }
        val uri = Uri.fromFile(directory.canonicalFile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val workspace = withContext(Dispatchers.Main) { Workspace(app, SavedStateHandle()) }
        // A kept demo belongs in the default API partition so it remains visible after the
        // loopback server is gone. Its rollout/database must use the final path from the start.
        val endpoint = "http://127.0.0.1:$port/v1"
        val historyRoot = if (keep) ctx.noBackupFilesDir else app.state
        val provider = if (keep) "api" else "api-$endpoint"
        val partition = MessageDigest.getInstance("SHA-256").digest(("$uri|$provider").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val history = File(historyRoot, "codex-projects/$partition")
        check(!history.exists())
        val fixturePartition = MessageDigest.getInstance("SHA-256").digest(("$uri|api-$endpoint").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val providerHome = File(app.state, "codex-projects/$fixturePartition")
        var controller: AgentSession? = null
        fun open(project: Project): AgentSession {
            val plugin = installedAgentPlugins(app).single { it.packageName == "com.hcgstudio.pocketstudio.codexplugin" }
            val (provider, resources) = plugin.load(app)
            return provider.open(WorkspaceAgentHost(workspace, project, resources, scope, plugin.label) { false })
                .also { controller = it; it.refresh() }
        }
        var retained = false
        try {
            if (providerHome != history) {
                providerHome.parentFile!!.mkdirs()
                Files.createDirectories(history.toPath())
                Files.createSymbolicLink(providerHome.toPath(), history.toPath())
            }
            // This storage resolves exclusively inside the per-run test directory.
            withContext(Dispatchers.Main) {
                workspace.openProject(uri)
                await("project open") { !workspace.busy }
                val project = checkNotNull(workspace.project) { workspace.message.orEmpty() }
                check(project.root.uri == uri)
                var controller = open(project)
                await("agent account initialization") { controller.loginState != null }
                controller.saveApiKey(endpoint, "local-fixture-only", {}, { error(it.message) })
                await("standalone runtime and initial chat list") {
                    check(controller.error == null) { controller.error.orEmpty() }
                    controller.usable && !controller.loading
                }
                controller.newChat()
                await("failed catalog request") { controller.modelsError != null && !controller.modelsLoading }
                check(controller.selectedModel == null)
                controller.refreshModels()
                await("account model catalog") {
                    check(controller.modelsError == null) { controller.modelsError.orEmpty() }
                    controller.selectedModel != null && !controller.modelsLoading
                }
                check(controller.models.map { it.id }.toSet() == setOf("gpt-5.5", "gpt-6.2-sol", "my-deployment"))
                controller.chooseModel("my-deployment")
                check(controller.selectedModel?.id == "my-deployment" && controller.effort == null && !controller.fast)
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
                controller.back()
                check(!controller.detail && controller.working)
                await("tool calls and streamed response") { !controller.working }
                check(controller.error == null) { controller.error.orEmpty() }
                checkTranscript(controller)
                val id = checkNotNull(controller.chatId)
                suspend fun awaitHistory(stage: String) {
                    await(stage) {
                        check(controller.error == null) { controller.error.orEmpty() }
                        !controller.loading && controller.chats.any { it.id == id }
                    }
                }
                awaitHistory("fresh app-server history list")
                // The stored cwd and the reopened project directory can be aliases on Android.
                controller.close()
                controller = open(project)
                awaitHistory("persisted chat after reopening the project")
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
                if (!keep) {
                    workspace.openFile(project.resolve("sample.txt"))
                    await("opening the edited file") { workspace.active?.node?.path == "sample.txt" }
                    val editor = checkNotNull(workspace.active).editor
                    check(editor.text == "two")
                    editor.replace(0, editor.text.length, "draft\nselected")
                    editor.select(6, 14)
                }
                controller.input = "请确认修改已完成。"
                controller.send()
                check(controller.working)
                await("new settings on the resumed chat") { !controller.working }
                check(controller.error == null && controller.chatId == id) { controller.error.orEmpty() }
                if (!keep) {
                    val draft = checkNotNull(workspace.active)
                    check(draft.dirty && draft.editor.selected == "selected")
                    val activities = controller.lines.filter { it.kind == ChatKind.Activity }
                    val deviceErrors = activities.filter { it.failed }
                    check(deviceErrors.map { it.category }.sorted() == listOf(uiString(R.string.logcat), uiString(R.string.inspect_screen)).sorted()) {
                        "Expected two disconnected-device errors: " + activities.map { it.category to it.detail }
                    }
                    check(deviceErrors.all { it.detail == uiString(R.string.connect_to_a_target_device_first) })
                }
                controller.back()
                awaitHistory("resumed chat after the completed turn")
            }
            check(sample.readText() == "two") { "The selected project's file did not change" }
            check(File(history, "sessions").isDirectory)
            if (keep) {
                val rootUri = Project(ctx, uri).root.uri
                check(rootUri == uri)
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
            withContext(Dispatchers.Main) { controller?.close(); workspace.closeProject(); workspace.devices.close(); scope.cancel() }
            app.cleanPreferences()
            if (Files.isSymbolicLink(providerHome.toPath())) Files.delete(providerHome.toPath())
            app.state.deleteRecursively()
            if (!retained) { directory.deleteRecursively(); history.deleteRecursively() }
        }
    }

    private fun checkTranscript(controller: AgentSession) {
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
