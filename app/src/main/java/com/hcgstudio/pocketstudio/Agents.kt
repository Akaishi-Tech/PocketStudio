package com.hcgstudio.pocketstudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.hcgstudio.pocketstudio.agent.AgentHost
import com.hcgstudio.pocketstudio.agent.AgentSession
import kotlinx.coroutines.*
import org.json.JSONObject

internal data class AgentApproval(val title: String, val description: String, val decision: CompletableDeferred<Boolean>)

internal class Agents(private val w: Workspace, private val scope: CoroutineScope) {
    private val prefs = w.app.getSharedPreferences("agent-plugins", 0)
    private var project: Project? = null
    private var resumed = false
    private var discovery: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    var plugins by mutableStateOf(emptyList<InstalledAgentPlugin>()); private set
    var selected by mutableStateOf<InstalledAgentPlugin?>(null); private set
    var session by mutableStateOf<AgentSession?>(null); private set
    var checked by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var approval by mutableStateOf<AgentApproval?>(null); private set
    val working get() = session?.working == true

    fun dismissError() { error = null; session?.error = null }

    fun enter(p: Project) {
        if (project !== p) { reset(); project = p }
        if (receiver == null) {
            val observer = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { refresh() }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(w.app, observer, filter, ContextCompat.RECEIVER_EXPORTED)
            receiver = observer
        }
        refresh()
    }

    fun setActivityResumed(value: Boolean) {
        resumed = value
        session?.setActivityResumed(value)
        if (value && project != null) refresh()
    }

    private fun refresh() {
        val p = project ?: return
        discovery?.cancel()
        discovery = scope.launch {
            try {
                plugins = withContext(Dispatchers.IO) { installedAgentPlugins(w.app) }
                val preferred = prefs.getString("selected", null) ?: selected?.packageName
                val next = plugins.find { it.packageName == preferred } ?: plugins.firstOrNull()
                if (next != selected || session == null) open(p, next)
                else if (!working && session?.accountBusy != true) session?.refresh()
                checked = true
            } catch (e: Exception) {
                ensureActive()
                error = e.localizedMessage ?: uiString(R.string.action_incomplete)
                checked = true
            }
        }
    }

    fun select(plugin: InstalledAgentPlugin) {
        val p = project ?: return
        if (working || session?.accountBusy == true || plugin !in plugins) return
        prefs.edit().putString("selected", plugin.packageName).apply()
        discovery?.cancel()
        discovery = scope.launch {
            try { open(p, plugin) }
            catch (e: Exception) { ensureActive(); error = e.localizedMessage ?: uiString(R.string.action_incomplete) }
        }
    }

    private suspend fun open(p: Project, plugin: InstalledAgentPlugin?) {
        closeSession()
        selected = plugin
        error = null
        if (plugin == null) return
        val (provider, resources) = try { withContext(Dispatchers.IO) { plugin.load(w.app) } }
        catch (e: LinkageError) { throw IllegalStateException(uiString(R.string.agent_plugin_changed), e) }
        val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = jobs
        val host = WorkspaceAgentHost(w, p, resources, jobs, plugin.label) { request ->
            approval = request
            try { request.decision.await() } finally { if (approval === request) approval = null }
        }
        try {
            val opened = provider.open(host)
            session = opened
            opened.setActivityResumed(resumed)
            opened.refresh()
        } catch (e: LinkageError) {
            closeSession()
            throw IllegalStateException(uiString(R.string.agent_plugin_changed), e)
        } catch (e: Exception) {
            closeSession()
            throw e
        }
    }

    private fun closeSession() {
        approval?.decision?.complete(false)
        approval = null
        try { session?.close() }
        catch (e: LinkageError) { error = uiString(R.string.agent_plugin_changed) }
        catch (e: Exception) { error = e.localizedMessage ?: uiString(R.string.action_incomplete) }
        finally {
            sessionScope?.cancel()
            sessionScope = null
            session = null
            selected?.let { AgentRunNotifications.update(w.app, it.label, null) }
        }
    }

    fun reset() {
        discovery?.cancel()
        receiver?.let(w.app::unregisterReceiver)
        receiver = null
        closeSession()
        selected = null
        project = null
        checked = false
        error = null
    }
}

internal class WorkspaceAgentHost(private val w: Workspace, private val p: Project,
    override val pluginContext: Context, override val scope: CoroutineScope, private val label: String,
    private val approve: suspend (AgentApproval) -> Boolean) : AgentHost {
    override val context get() = w.app
    override val projectId = (p.tree ?: p.root.uri).toString()
    override val projectIdle get() = w.project === p && !w.running && !w.busy
    override val tools get() = if (p.linux == null) {
        AgentProjectTools.tools.filter { it.name != "run_gradle" || w.androidAvailable }
    } else AgentProjectTools.tools.filterNot { it.name in setOf("run_gradle", "read_logcat", "inspect_device_ui") } + AgentProjectTools.shell
    override fun keepLoginAlive() = AgentLoginService.start(context)
    private var lastStatus: String? = null
    override fun reportStatus(status: String?) {
        if (scope.isActive && status != lastStatus) {
            lastStatus = status
            AgentRunNotifications.update(context, label, status)
        }
    }

    private suspend fun ask(title: String, details: String): Boolean {
        reportStatus(title)
        return approve(AgentApproval(title, details, CompletableDeferred()))
    }

    override suspend fun callTool(name: String, arguments: JSONObject): String {
        check(w.project === p) { uiString(R.string.project_closed) }
        val args = arguments
        try {
            return when (name) {
                "search_project" -> w.projectOperation(p) { runInterruptible { searchProjectForAgent(p, args.getString("query")) } }
                "get_editor_context" -> editorContext(w.active).toString()
                "inspect_device_ui" -> w.devices.inspectDeviceUi()
                "read_logcat" -> {
                    val limit = args.opt("maxLines") ?: 150
                    require(limit is Int) { uiString(R.string.agent_log_line_range) }
                    w.devices.readLogcat(w.apk, if (args.has("packageName")) args.getString("packageName") else null, limit)
                }
                "run_shell" -> {
                    val guest = requireNotNull(p.linux)
                    val command = args.getString("command")
                    require(command.isNotBlank())
                    check(ask(uiString(R.string.linux_run_command), command)) { uiString(R.string.declined_by_the_user) }
                    check(w.saveDocuments()) { uiString(R.string.resolve_editing_conflicts_first) }
                    val tail = StringBuilder()
                    val status = runLinuxProcess(guest, arrayOf("bash", "-c", command), false,
                        { it.outputStream.close() }, { chunk -> withContext(Dispatchers.Main) {
                            tail.append(chunk)
                            if (tail.length > 100_000) tail.delete(0, tail.length - 100_000)
                        } })
                    json("exitCode" to status, "output" to tail.toString()).toString()
                }
                "run_gradle" -> {
                    check(w.androidAvailable) { uiString(R.string.android_environment_required) }
                    val tasks = args.getString("tasks")
                    check(ask(uiString(R.string.run_gradle_tasks), tasks)) { uiString(R.string.build_declined_by_the_user) }
                    check(w.saveDocuments()) { uiString(R.string.resolve_editing_conflicts_first) }
                    AgentProjectTools.gradle(p, tasks)
                }
                else -> {
                    if (name in setOf("delete_file", "move_file")) {
                        val title = if (name == "delete_file") R.string.delete_file else R.string.move_file
                        val paths = listOf(args.string("path"), args.string("destination")).filter(String::isNotEmpty)
                        check(ask(uiString(title), paths.joinToString(" → "))) { uiString(R.string.declined_by_the_user) }
                    }
                    if (name in setOf("write_file", "delete_file", "move_file")) {
                        check(w.tabs.none { it.node.path == args.string("path") && it.dirty }) {
                            uiString(R.string.save_your_editor_changes_then_try_again)
                        }
                    }
                    w.projectOperation(p) { AgentProjectTools.perform(p, name, args) }
                }
            }
        } finally {
            if (name in setOf("write_file", "create_directory", "run_gradle", "run_shell", "delete_file", "move_file") && w.project === p)
                w.refreshAfterExternalRun(p)
        }
    }
}
