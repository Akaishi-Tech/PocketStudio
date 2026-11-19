package com.hcgstudio.pocketstudio.codexplugin

import com.hcgstudio.pocketstudio.agent.*

import org.json.JSONObject

internal fun chatLabel(id: Int, vararg args: String): () -> String = { uiString(id, *args) }

internal fun turnFailure(turnId: String, error: JSONObject?, chatGptPlan: Boolean): ChatLine {
    val id = "failure-$turnId"
    if (chatGptPlan && error?.string("codexErrorInfo") == "usageLimitExceeded")
        return ChatLine(id, ChatKind.UsageLimit, failed = true, text = uiString(R.string.chatgpt_usage_limit_reached))
    val message = error?.string("message").orEmpty()
    return ChatLine(id, ChatKind.Status, message.ifBlank { uiString(R.string.request_failed) }, failed = true)
}

internal fun transcriptItem(item: JSONObject): ChatLine? {
    val id = item.string("id")
    fun activity(title: () -> String, detail: String = "", category: Int = R.string.tools) =
        ChatLine(id, ChatKind.Activity, detail = detail, category = uiString(category),
            command = category == R.string.run_build || category == R.string.run_command,
            failed = item.string("status") in listOf("failed", "declined"), text = title())
    fun listDirectory(path: String, fallback: Int): () -> String =
        { uiString(R.string.activity_list_path, path.ifEmpty { uiString(fallback) }) }
    return when (item.string("type")) {
        "userMessage" -> ChatLine(id, ChatKind.User, item.optJSONArray("content").objects()
            .filter { it.string("type") == "text" }.joinToString("\n") { it.string("text") })
        "agentMessage", "plan" -> ChatLine(id, ChatKind.Assistant, item.string("text"))
        "reasoning" -> {
            val summary = item.optJSONArray("summary")
            if (summary == null || summary.length() == 0) null
            else activity(chatLabel(R.string.reasoning_summary), (0 until summary.length()).joinToString("\n") { summary.optString(it) }, R.string.reasoning)
        }
        "commandExecution" -> {
            val command = item.string("command")
            val action = item.optJSONArray("commandActions").objects().firstOrNull()
            val (title, category) = when (action?.string("type")) {
                "read" -> chatLabel(R.string.activity_read_path, action.string("name")) to R.string.read_file
                "listFiles" -> listDirectory(action.string("path"), R.string.directory) to R.string.list_directory
                "search" -> chatLabel(R.string.activity_search_query, action.string("query")) to R.string.search_files
                else -> ({ command.ifBlank { uiString(R.string.run_command) } }) to R.string.run_command
            }
            activity(title, listOf(command, item.string("aggregatedOutput").takeLast(16000)).filter(String::isNotBlank).joinToString("\n"), category)
        }
        "fileChange" -> {
            val changes = item.optJSONArray("changes").objects()
            val names = changes.map { it.string("path").substringAfterLast('/') }
            activity({ uiString(R.string.activity_edit_path, names.joinToString(uiString(R.string.list_separator))) },
                changes.joinToString("\n") { it.string("diff") }.take(24000), R.string.edit_file)
        }
        "dynamicToolCall", "mcpToolCall" -> {
            val args = item.optJSONObject("arguments") ?: JSONObject()
            val path = args.string("path")
            val (title, category) = when (val tool = item.string("tool")) {
                "search_project" -> chatLabel(R.string.activity_search_query, args.string("query")) to R.string.search_files
                "get_editor_context" -> chatLabel(R.string.read_editor_context) to R.string.read_editor_context
                "read_logcat" -> chatLabel(R.string.read_app_logcat) to R.string.logcat
                "inspect_device_ui" -> chatLabel(R.string.inspect_screen) to R.string.inspect_screen
                "read_file" -> chatLabel(R.string.activity_read_path, path) to R.string.read_file
                "list_files" -> listDirectory(path, R.string.project_root) to R.string.list_directory
                "move_file" -> chatLabel(R.string.activity_move_path, path) to R.string.move_file_label
                "delete_file" -> chatLabel(R.string.activity_delete_path, path) to R.string.delete_file_label
                "write_file" -> chatLabel(R.string.activity_write_path, path) to R.string.edit_file
                "create_directory" -> chatLabel(R.string.activity_create_path, path) to R.string.create_directory
                "run_gradle" -> {
                    val tasks = args.string("tasks")
                    ({ "Gradle $tasks" }) to R.string.run_build
                }
                else -> ({ tool }) to R.string.call_tool
            }
            val result = item.optJSONArray("contentItems").objects().joinToString("\n") { it.string("text") }
            val detail = if (args.has("text")) args.string("text") else result
            activity(title, detail.take(20000), category)
        }
        "webSearch" -> activity(chatLabel(R.string.activity_search_query, item.string("query")), category = R.string.search_the_web)
        "contextCompaction" -> activity(chatLabel(R.string.conversation_context_compacted), category = R.string.compact_context)
        "collabAgentToolCall", "subAgentActivity" -> {
            val tool = item.string("tool")
            activity({ tool.ifBlank { uiString(R.string.subtask) } }, item.string("prompt"), R.string.subtask)
        }
        else -> null
    }
}
