package com.hcgstudio.pocketstudio

import android.content.Intent
import androidx.compose.runtime.*
import com.hcgstudio.pocketstudio.codex.CodexRelease
import com.hcgstudio.pocketstudio.codex.CodexRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class CodexApproval(val title: String, val description: String, val decision: CompletableDeferred<Boolean>)
private data class ModelSelection(val model: CodexModel, val effort: String?, val fast: Boolean)

internal class CodexController(private val w: Workspace, private val scope: CoroutineScope,
    private val fixtureHistory: File? = null) {
    private val runtime = CodexRuntime(w.app)
    private val auth = CodexAuth(w.app)
    private val cli = CodexCliSession(runtime)
    private val prefs = w.app.getSharedPreferences("codex-ui", 0)
    var account by mutableStateOf<CodexAuthState?>(null); private set
    var version by mutableStateOf<String?>(null); private set
    var legacyInstalled by mutableStateOf(false); private set
    private var installedVersionCode by mutableLongStateOf(0L)
    var checked by mutableStateOf(false); private set
    var release by mutableStateOf<CodexRelease?>(null); private set
    var downloading by mutableStateOf(false); private set
    var downloadProgress by mutableFloatStateOf(0f); private set
    var installIntent by mutableStateOf<Intent?>(null)
    var signingIn by mutableStateOf(false); private set
    private var deviceCodeRequest by mutableStateOf<CompletableDeferred<Unit>?>(null)
    private var browserLoginFailed by mutableStateOf(false)
    val offerDeviceCode get() = deviceCodeRequest != null || (browserLoginFailed && !signingIn && error != null)
    var deviceCode by mutableStateOf<CodexDeviceCode?>(null); private set
    var replacingDeviceCode by mutableStateOf(false); private set
    private val resumed = MutableStateFlow(false)
    private var updatingAccount by mutableStateOf(false)
    val accountBusy get() = signingIn || updatingAccount
    var error by mutableStateOf<String?>(null)
    var updateError by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var detail by mutableStateOf(false); private set
    var chatId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var input by mutableStateOf("")
    var models by mutableStateOf(emptyList<CodexModel>()); private set
    var modelsLoading by mutableStateOf(false); private set
    var modelsError by mutableStateOf<String?>(null); private set
    private var selection by mutableStateOf<ModelSelection?>(null)
    val selectedModel get() = selection?.model
    val effort get() = selection?.effort
    val fast get() = selection?.fast == true
    var quota by mutableStateOf(emptyList<String>()); private set
    var quotaAsOf by mutableLongStateOf(0L); private set
    var approval by mutableStateOf<CodexApproval?>(null); private set
    val chats = mutableStateListOf<CodexChat>()
    val lines = mutableStateListOf<ChatLine>()
    private var selectedProject: Project? = null
    private var viewJob: Job? = null
    private var modelsJob: Job? = null
    private var turnJob: Job? = null
    private var loginJob: Job? = null
    private var installationJob: Job? = null
    private var updateJob: Job? = null
    private var currentTurn = ""
    private var connection: CodexRpc? = null
    private var accountKey: String? = null
    val usable get() = account?.let { if (it.useChatGpt) it.selected?.signedIn == true else it.hasApiKey } == true
    val update get() = release?.takeIf { version != null && (legacyInstalled || it.versionCode > installedVersionCode) }

    private suspend fun <T> io(f: () -> T) = withContext(Dispatchers.IO) { f() }
    private fun failure(e: Throwable) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        error = e.localizedMessage ?: uiString(R.string.action_incomplete)
    }
    fun enter(p: Project) {
        if (p !== selectedProject) { reset(); selectedProject = p }
        refreshInstallation(true)
    }
    fun refreshInstallation(checkUpdate: Boolean = false) {
        val p = selectedProject ?: return
        if (accountBusy) return
        installationJob?.cancel()
        installationJob = scope.launch {
            try {
                val (installed, state) = io { runtime.installedVersion() to auth.snapshot() }
                if (p !== selectedProject) return@launch
                val changed = installed != version
                version = installed
                legacyInstalled = runtime.legacyInstalled
                installedVersionCode = runtime.installedVersionCode
                checked = true
                applyAccount(state, refreshCatalog = changed)
                if (installed != null && (checkUpdate || changed)) checkRelease()
                if (installed != null && !detail && !loading) list()
                if (installed != null) checkCliAccount(state)
            } catch (e: CancellationException) { failure(e) }
            catch (e: Exception) { checked = true; failure(e) }
        }
    }
    private suspend fun checkCliAccount(state: CodexAuthState) {
        val profile = state.selected?.takeIf { state.useChatGpt && it.signedIn } ?: return
        val home = io { auth.cliHome(profile) }
        if (home != null && cli.account(home) != null) return
        applyAccount(io { auth.markSignedOut(profile.id); auth.snapshot() })
    }

    private fun checkRelease() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            try { updateError = null; release = io { runtime.latestRelease() } }
            catch (e: IOException) { updateError = e.localizedMessage ?: uiString(R.string.update_check_failed) }
            catch (e: Exception) { if (e !is CancellationException) updateError = e.localizedMessage }
        }
    }
    fun download() {
        if (downloading || working) return
        downloading = true; error = null; downloadProgress = 0f
        scope.launch {
            try {
                val target = io { runtime.releaseForInstall() }
                release = target
                var last = -1
                installIntent = io {
                    runtime.prepareInstaller(target) { done, total ->
                        val pc = if (total > 0) (100 * done / total).toInt().coerceIn(0, 100) else 0
                        if (pc != last) { last = pc; scope.launch { downloadProgress = pc / 100f } }
                    }
                }
            } catch (e: Exception) { failure(e) }
            finally { downloading = false }
        }
    }

    private fun applyAccount(state: CodexAuthState, refreshCatalog: Boolean = false) {
        val key = state.accountKey
        val accountChanged = key != accountKey
        val wasUsable = usable
        if (accountChanged) {
            clearChat()
            chats.clear(); quota = emptyList()
        }
        accountKey = key
        account = state
        if (accountChanged || wasUsable != usable || refreshCatalog) {
            clearModels()
            if (detail) ensureModels()
        }
        if (state.useChatGpt && state.selected?.signedIn == true) restoreQuota()
        else { quota = emptyList(); quotaAsOf = 0L }
    }
    private fun clearModels() {
        modelsJob?.cancel()
        modelsJob = null
        models = emptyList()
        selection = null
        modelsError = null
        modelsLoading = false
    }

    private fun ensureModels() {
        if (models.isEmpty() && !modelsLoading) refreshModels()
    }

    fun refreshModels() {
        val project = selectedProject ?: return
        val state = account ?: return
        if (!usable || version == null || working || modelsLoading) return
        modelsLoading = true
        modelsError = null
        modelsJob = scope.launch {
            try {
                val runtimeModels = withServer(project, false) { rpc, _ ->
                    buildList {
                        pages(rpc, "model/list", json("limit" to 100)) { row -> CodexModel.fromRuntime(row)?.let(::add) }
                    }
                }
                val available = if (state.useChatGpt) runtimeModels else io { auth.apiModels(state, runtimeModels) }
                ensureActive()
                models = available
                val preferred = prefs.getString("model-$accountKey", null)
                val model = available.find { it.id == preferred } ?: available.find { it.isDefault } ?: available.firstOrNull()
                selection = model?.let(::restoreSelection)
                if (model == null) modelsError = uiString(R.string.no_models_available_for_this_account)
            } catch (e: Exception) {
                ensureActive()
                models = emptyList()
                selection = null
                modelsError = e.localizedMessage ?: uiString(R.string.action_incomplete)
            } finally {
                if (isActive) modelsLoading = false
            }
        }
    }

    private fun restoreSelection(model: CodexModel): ModelSelection {
        val key = "$accountKey-${model.id}"
        val stored = prefs.getString("effort-$key", null)
        val level = model.efforts.find { it == stored } ?: model.defaultEffort ?: model.efforts.firstOrNull()
        return ModelSelection(model, level, model.supportsFast && prefs.getBoolean("fast-$key", false))
    }

    fun chooseModel(id: String) {
        if (working || modelsLoading) return
        val model = models.find { it.id == id } ?: return
        selection = restoreSelection(model)
        prefs.edit().putString("model-$accountKey", model.id).apply()
    }

    fun chooseEffort(value: String) {
        val current = selection ?: return
        if (working || modelsLoading || value !in current.model.efforts) return
        selection = current.copy(effort = value)
        prefs.edit().putString("effort-$accountKey-${current.model.id}", value).apply()
    }

    fun setFast(enabled: Boolean) {
        val current = selection ?: return
        if (working || modelsLoading || !current.model.supportsFast) return
        selection = current.copy(fast = enabled)
        prefs.edit().putBoolean("fast-$accountKey-${current.model.id}", enabled).apply()
    }
    fun configure(onSaved: () -> Unit = {}, onInvalid: (ApiConfigurationError) -> Unit = ::failure,
                  f: (CodexAuth) -> Unit) {
        if (working || accountBusy) return
        installationJob?.cancel()
        updatingAccount = true
        error = null
        scope.launch {
            try {
                val state = io { f(auth); auth.snapshot() }
                applyAccount(state, refreshCatalog = true)
                onSaved()
                if (version != null && !detail) list()
            } catch (e: ApiConfigurationError) { onInvalid(e) }
            catch (e: Exception) { failure(e) }
            finally { updatingAccount = false }
        }
    }
    fun login(openBrowser: (String) -> Unit) = startLogin { home ->
        val identity = supervisorScope {
            val useCode = CompletableDeferred<Unit>()
            val browser = async {
                cli.loginWithBrowser(home) { url ->
                    resumed.first { it }
                    try {
                        openBrowser(url)
                    } catch (e: IllegalArgumentException) {
                        throw IOException(uiString(R.string.no_browser_available_to_open_this_link), e)
                    }
                }
            }
            deviceCodeRequest = useCode
            try {
                select<CodexCliAccount?> {
                    useCode.onAwait {
                        browser.cancel()
                        null
                    }
                    browser.onAwait { it }
                }
            } catch (e: Exception) {
                ensureActive()
                browserLoginFailed = true
                throw e
            } finally {
                deviceCodeRequest = null
            }
        }
        identity ?: loginWithDeviceCode(home)
    }

    fun useDeviceCode() {
        val request = deviceCodeRequest
        if (request != null) request.complete(Unit)
        else if (offerDeviceCode) startLogin(::loginWithDeviceCode)
    }

    private suspend fun loginWithDeviceCode(home: File) = cli.loginWithDeviceCode(home, awaitRetry = {
        deviceCode = null
        replacingDeviceCode = true
        resumed.first { it }
    }) { deviceCode = it }

    private fun startLogin(authorize: suspend (File) -> CodexCliAccount) {
        if (accountBusy || working) return
        if (version == null) {
            error = uiString(R.string.install_the_codex_runtime_first)
            return
        }
        installationJob?.cancel()
        signingIn = true
        browserLoginFailed = false
        error = null
        loginJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var home: File? = null
            try {
                CodexLoginService.start(w.app).use {
                    val candidate = io { auth.newLoginHome().also { home = it } }
                    val identity = authorize(candidate)
                    deviceCode = null
                    ensureActive()
                    // Once confirmed, finish recording the account even if the page closes.
                    withContext(NonCancellable) {
                        applyAccount(io { auth.acceptCliLogin(candidate, identity) }, refreshCatalog = true)
                        if (selectedProject != null) list()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                error = uiString(R.string.sign_in_timed_out_try_again)
            } catch (e: Exception) { failure(e) }
            finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) { home?.deleteRecursively() }
                } finally {
                    deviceCode = null
                    replacingDeviceCode = false
                    signingIn = false
                }
            }
        }
    }

    fun setActivityResumed(value: Boolean) { resumed.value = value }

    fun cancelLogin() {
        browserLoginFailed = false
        deviceCodeRequest = null
        loginJob?.cancel()
    }

    fun logout() {
        val profile = account?.selected ?: return
        if (working || accountBusy) return
        installationJob?.cancel()
        updatingAccount = true
        error = null
        scope.launch {
            try {
                io { auth.cliHome(profile) }?.let { cli.logout(it) }
                applyAccount(io { auth.markSignedOut(profile.id); auth.snapshot() })
            } catch (e: Exception) { failure(e) }
            finally { updatingAccount = false }
        }
    }

    private fun clearChat() {
        viewJob?.cancel()
        chatId = null; title = ""; detail = false; input = ""; loading = false
        lines.clear()
    }
    fun reset() {
        installationJob?.cancel()
        cancelLogin()
        turnJob?.cancel(); turnJob = null
        clearModels()
        clearChat(); chats.clear(); quota = emptyList(); error = null
        selectedProject = null
    }
    fun dispose() { reset() }
    fun back() {
        if (working) { detail = false; return }
        clearChat()
        list()
    }
    fun newChat() {
        if (working) { detail = true; return }
        clearChat(); detail = true; error = null
        ensureModels()
    }

    private fun home(p: Project, accountKey: String): File {
        val key = (p.tree ?: p.root.uri).toString() + "|" + accountKey
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return (fixtureHistory ?: File(w.app.noBackupFilesDir, "codex-projects/$hash")).apply { mkdirs() }
    }

    private suspend fun <T> withServer(p: Project, authenticate: Boolean, fn: suspend (CodexRpc, File) -> T): T {
        val state = checkNotNull(account)
        val plan = state.useChatGpt
        val directory = io { home(p, state.accountKey) }
        val cliHome = if (plan) state.selected?.let { io { auth.cliHome(it) } } else null
        val env = mutableMapOf("CODEX_HOME" to (cliHome ?: directory).absolutePath)
        val settings = mutableMapOf(
            "features.code_mode.direct_only_tool_namespaces" to "[\"studio\"]",
            "features.shell_tool" to "false", "features.incremental_tools" to "false",
            "shell_environment_policy.exclude" to "[\"ACCESS_TOKEN\",\"OPENAI_API_KEY\"]")
        if (plan) {
            settings["cli_auth_credentials_store"] = "\"file\""
            settings["sqlite_home"] = JSONObject.quote(directory.absolutePath)
            // Existing threads record their original provider name. Let Codex
            // authenticate them with the same managed account as new threads.
            settings["model_providers.chatgpt_plan.name"] = "\"OpenAI\""
            settings["model_providers.chatgpt_plan.wire_api"] = "\"responses\""
            settings["model_providers.chatgpt_plan.requires_openai_auth"] = "true"
        } else {
            if (authenticate) env["OPENAI_API_KEY"] = io { auth.credential(state) }
            settings["model_provider"] = "\"openai_byok\""
            settings["model_providers.openai_byok.name"] = "\"OpenAI API\""
            settings["model_providers.openai_byok.base_url"] = JSONObject.quote(state.apiEndpoint)
            settings["model_providers.openai_byok.env_key"] = "\"OPENAI_API_KEY\""
            settings["model_providers.openai_byok.wire_api"] = "\"responses\""
            settings["model_providers.openai_byok.supports_websockets"] = "false"
            settings["model_providers.openai_byok.requires_openai_auth"] = "false"
        }
        var opened: CodexRpc? = null
        try {
            val rpc = io {
                val arguments = settings.flatMap { (key, value) -> listOf("-c", "$key=$value") }
                CodexRpc(runtime.launch(directory, env, arguments)).also { opened = it }
            }
            rpc.initialize()
            return fn(rpc, directory)
        } finally { withContext(NonCancellable + Dispatchers.IO) { opened?.close() } }
    }

    private suspend fun pages(rpc: CodexRpc, method: String, params: JSONObject, consume: (JSONObject) -> Unit) {
        do {
            val page = rpc.request(method, params)
            for (row in page.optJSONArray("data").objects()) consume(row)
            val next = page.string("nextCursor")
            if (next.isEmpty()) return
            params.put("cursor", next)
        } while (true)
    }
    private fun list() {
        val p = selectedProject ?: return
        if (working || version == null) return
        viewJob?.cancel()
        loading = true
        viewJob = scope.launch {
            try {
                val found = mutableListOf<CodexChat>()
                withServer(p, false) { rpc, directory ->
                    pages(rpc, "thread/list", json("cwd" to directory.absolutePath,
                        "sortKey" to "updated_at", "modelProviders" to JSONArray(),
                        "sourceKinds" to JSONArray(listOf("appServer","cli","unknown","vscode")), "limit" to 50)) {
                        found.add(CodexChat(it.getString("id"), it.string("name").ifBlank {
                            it.string("preview").lineSequence().firstOrNull().orEmpty().take(90)
                        }, it.optLong("updatedAt")))
                    }
                }
                ensureActive(); chats.clear(); chats.addAll(found)
            } catch (e: Exception) { failure(e) } finally { if (isActive) loading = false }
        }
    }
    fun open(chat: CodexChat) {
        if (working) { if (chat.id == chatId) detail = true; return }
        val p = selectedProject ?: return
        clearChat(); detail = true; title = chat.title; chatId = chat.id; loading = true; error = null
        ensureModels()
        viewJob = scope.launch {
            try {
                val history = mutableListOf<ChatLine>()
                withServer(p, false) { rpc, _ ->
                    pages(rpc, "thread/turns/list", json("threadId" to chat.id,
                        "sortDirection" to "asc", "itemsView" to "full", "limit" to 10)) { turn ->
                        for (item in turn.optJSONArray("items").objects()) transcriptItem(item)?.let(history::add)
                        if (turn.string("status") == "failed") {
                            history.add(turnFailure(turn.string("id"), turn.optJSONObject("error"), account?.useChatGpt == true))
                        }
                    }
                }
                ensureActive(); lines.addAll(history)
            } catch (e: Exception) { failure(e) } finally { if (isActive) loading = false }
        }
    }
    private fun upsert(line: ChatLine) {
        val i = lines.indexOfFirst { it.id == line.id }
        if (i < 0) lines.add(line) else lines[i] = line
    }
    private suspend fun ask(title: String, body: String): Boolean {
        val request = CodexApproval(title, body, CompletableDeferred())
        approval = request
        return try { request.decision.await() } finally { if (approval === request) approval = null }
    }
    fun stop() {
        val rpc = connection
        val job = turnJob ?: return
        val turn = currentTurn
        approval?.decision?.complete(false)
        if (rpc == null || turn.isEmpty()) { job.cancel(); return }
        scope.launch {
            try { withTimeout(2500) { rpc.request("turn/interrupt", json("threadId" to chatId, "turnId" to turn)) } }
            catch (_: Exception) { job.cancel() }
            delay(3000)
            if (turnJob === job && job.isActive) job.cancel()
        }
    }
    fun send() {
        val p = selectedProject ?: return
        val chosen = selection ?: return
        val prompt = input.trim()
        if (!usable || accountBusy || working || loading || modelsLoading || prompt.isEmpty() || w.running || w.busy) return
        input = ""; working = true; detail = true; error = null
        val savedId = chatId
        if (title.isEmpty()) title = prompt.lineSequence().first().take(70)
        val placeholder = "local-${java.util.UUID.randomUUID()}"
        lines.add(ChatLine(placeholder, ChatKind.User, prompt))
        var sent = false
        turnJob = scope.launch {
            try {
                withServer(p, true) { rpc, dir ->
                    connection = rpc
                    val options = json("cwd" to dir.absolutePath, "sandbox" to "read-only",
                        "approvalPolicy" to "on-request", "developerInstructions" to
                        "Call the studio namespace tools directly to inspect and edit the current Android project. Paths are relative to the selected project. File tools use saved content. Use get_editor_context for the user's live selection and drafts, and run_gradle for builds. The app-server directory is private session storage.")
                    options.put("model", chosen.model.id)
                    if (savedId == null) {
                        options.put("dynamicTools", CodexProjectTools.tools).put("environments", JSONArray())
                    } else options.put("threadId", savedId).put("excludeTurns", true)
                    val thread = rpc.request(if (savedId == null) "thread/start" else "thread/resume", options)
                        .getJSONObject("thread").getString("id")
                    chatId = thread
                    chats.removeAll { it.id == thread }
                    chats.add(0, CodexChat(thread, title, System.currentTimeMillis()/1000))
                    val result = rpc.request("turn/start", json("threadId" to thread, "model" to chosen.model.id,
                        "effort" to chosen.effort, "serviceTier" to if (chosen.fast) "priority" else "default",
                        "input" to JSONArray().put(json("type" to "text", "text" to prompt))))
                    currentTurn = result.getJSONObject("turn").getString("id")
                    sent = true
                    var terminalError: JSONObject? = null
                    for (message in rpc.events) {
                        val params = message.optJSONObject("params") ?: continue
                        val method = message.string("method")
                        if (params.has("threadId") && params.string("threadId") != thread) continue
                        if (message.has("id")) { handleRequest(rpc, message, p); continue }
                        when (method) {
                            "item/started", "item/completed" -> params.optJSONObject("item")?.let(::transcriptItem)?.let { line ->
                                if (line.kind == ChatKind.User) lines.removeAll { it.id == placeholder }
                                upsert(line)
                            }
                            "item/agentMessage/delta", "item/plan/delta" -> {
                                val id = params.string("itemId")
                                val text = lines.lastOrNull { it.id == id }?.text.orEmpty()
                                upsert(ChatLine(id, ChatKind.Assistant, text + params.string("delta")))
                            }
                            "account/rateLimits/updated" -> if (account?.useChatGpt == true)
                                params.optJSONObject("rateLimits")?.let(::updateQuota)
                            "error" -> if (params.string("turnId") == currentTurn && !params.optBoolean("willRetry")) {
                                terminalError = params.optJSONObject("error")
                            }
                            "turn/completed" -> {
                                val turn = params.getJSONObject("turn")
                                if (turn.string("id") != currentTurn) continue
                                when (turn.string("status")) {
                                    "failed" -> {
                                        val line = turnFailure(currentTurn, turn.optJSONObject("error") ?: terminalError,
                                            account?.useChatGpt == true)
                                        if (line.kind == ChatKind.PlanUsageLimit) upsert(line)
                                        else throw IOException(line.text)
                                    }
                                    "interrupted" -> lines.add(ChatLine("stop-$currentTurn", ChatKind.Status, label = chatLabel(R.string.stopped)))
                                }
                                break
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (p === selectedProject) error = uiString(R.string.codex_timed_out_try_again)
            } catch (e: CancellationException) {
                if (p === selectedProject) lines.add(ChatLine("stop-$placeholder", ChatKind.Status, label = chatLabel(R.string.stopped)))
                throw e
            } catch (e: Exception) {
                failure(e)
                if (selectedProject === p) {
                    account?.let { runCatching { checkCliAccount(it) } }
                }
            }
            finally {
                if (p === selectedProject) {
                    if (!sent) {
                        if (input.isEmpty()) input = prompt
                        lines.removeAll { it.id == placeholder }
                    }
                    connection = null; currentTurn = ""; approval = null; working = false
                    if (!detail) list()
                }
            }
        }
    }

    private fun updateQuota(snapshot: JSONObject) {
        val key = accountKey ?: return
        if (account?.selected?.signedIn != true) return
        val now = System.currentTimeMillis()
        val stored = prefs.getString("quota-$key", null)?.let(::JSONObject) ?: JSONObject()
        var changed = false
        for (name in listOf("primary", "secondary")) {
            val window = snapshot.optJSONObject(name) ?: continue
            val used = window.optDouble("usedPercent")
            if (!used.isFinite() || used !in 0.0..100.0) continue
            stored.put(name, json("used" to used, "window" to window.optInt("windowDurationMins"),
                "reset" to window.optLong("resetsAt"), "at" to now))
            changed = true
        }
        if (changed) { prefs.edit().putString("quota-$key", stored.toString()).apply(); restoreQuota() }
    }

    fun restoreQuota() {
        if (account?.useChatGpt != true || account?.selected?.signedIn != true) {
            quota = emptyList(); quotaAsOf = 0L
            return
        }
        val stored = prefs.getString("quota-$accountKey", null)?.let(::JSONObject)
        val now = System.currentTimeMillis()
        val formatter = java.text.NumberFormat.getNumberInstance()
        quotaAsOf = 0L
        quota = listOf("primary", "secondary").mapNotNull { name ->
            val window = stored?.optJSONObject(name) ?: return@mapNotNull null
            val reset = window.optLong("reset") * 1000
            val at = window.optLong("at")
            if (reset > 0 && reset <= now || reset == 0L && now - at > 300_000) return@mapNotNull null
            quotaAsOf = maxOf(quotaAsOf, at)
            val minutes = window.optInt("window")
            val label = when {
                minutes == 10080 -> uiString(R.string.weekly)
                minutes > 0 && minutes % 60 == 0 -> uiString(R.string.quota_hours, minutes / 60)
                minutes > 0 -> uiString(R.string.quota_minutes, minutes)
                name == "primary" -> uiString(R.string.primary_limit)
                else -> uiString(R.string.secondary_limit)
            }
            uiString(R.string.remaining_quota, label, formatter.format(100 - window.getDouble("used")))
        }
    }
    private suspend fun handleRequest(rpc: CodexRpc, request: JSONObject, p: Project) {
        val id = request.get("id")
        val params = request.getJSONObject("params")
        when (request.string("method")) {
            "item/tool/call" -> {
                val name = params.string("tool"); val args = params.optJSONObject("arguments") ?: JSONObject()
                var ok = true
                val text = try {
                    check(params.string("namespace") == "studio") { uiString(R.string.unknown_project_tool) }
                    check(w.project === p) { uiString(R.string.project_closed) }
                    when (name) {
                        "search_project" -> w.projectOperation(p) {
                            runInterruptible { searchProjectForAgent(p, args.getString("query")) }
                        }
                        "get_editor_context" -> editorContext(w.active).toString()
                        "read_logcat" -> {
                            val limit = args.opt("maxLines") ?: 150
                            require(limit is Int) { uiString(R.string.agent_log_line_range) }
                            w.devices.readLogcat(w.apk, if (args.has("packageName")) args.getString("packageName") else null, limit)
                        }
                        "inspect_device_ui" -> w.devices.inspectDeviceUi()
                        "run_gradle" -> {
                            val tasks = args.getString("tasks")
                            check(ask(uiString(R.string.run_gradle_tasks), tasks)) { uiString(R.string.build_declined_by_the_user) }
                            check(w.saveDocuments()) { uiString(R.string.resolve_editing_conflicts_first) }
                            CodexProjectTools.gradle(p, tasks)
                        }
                        else -> {
                            if (name in setOf("delete_file", "move_file")) {
                                val path = args.string("path")
                                check(ask(if (name == "delete_file") uiString(R.string.delete_file) else uiString(R.string.move_file),
                                    listOf(path, args.string("destination")).filter(String::isNotEmpty).joinToString(" → "))) { uiString(R.string.declined_by_the_user) }
                            }
                            if (name in setOf("write_file", "delete_file", "move_file")) check(w.tabs.none { it.node.path == args.string("path") && it.dirty }) {
                                uiString(R.string.save_your_editor_changes_then_try_again)
                            }
                            w.projectOperation(p) { CodexProjectTools.perform(p, name, args) }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { ok = false; e.localizedMessage ?: uiString(R.string.project_operation_failed) }
                if (name in setOf("write_file", "create_directory", "run_gradle", "delete_file", "move_file") && w.project === p) w.refreshAfterExternalRun(p)
                rpc.answer(id, json("success" to ok, "contentItems" to JSONArray().put(json("type" to "inputText", "text" to text))))
            }
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                rpc.answer(id, json("decision" to "decline"))
            else -> rpc.unsupported(id)
        }
    }
}
