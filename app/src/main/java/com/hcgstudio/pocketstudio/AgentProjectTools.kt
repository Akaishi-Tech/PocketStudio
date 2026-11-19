package com.hcgstudio.pocketstudio

import kotlinx.coroutines.*
import com.hcgstudio.pocketstudio.agent.AgentTool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/** Android owns the selected directory grant. Every file call goes through that same Project. */
internal object AgentProjectTools {
    private val commonTools = listOf(
            spec("list_files", "List one project directory. Use an empty path for its root.", "path"),
            spec("search_project", "Search saved project text for a case-sensitive single-line literal. Returns matching paths, 1-based line numbers, excerpts and match counts.", "query"),
            spec("get_editor_context", "Read the active editor's live selection, cursor, nearby code and unsaved state. Lines and UTF-16 columns are 1-based and selection ends are exclusive."),
            spec("read_file", "Read a UTF-8 project file and its revision for editing.", "path"),
            spec("write_file", "Write a UTF-8 file. Supply revision from read_file or 'new' to create. Parent directory must exist.", "path", "revision", "text"),
            spec("move_file", "Move or rename a file to a new relative path. Supply its current revision.", "path", "destination", "revision"),
            spec("delete_file", "Delete one text file after user confirmation. Supply its current revision.", "path", "revision"),
            spec("create_directory", "Create a directory under an existing project directory.", "path")
        )

    val pressBack = spec("press_device_back", "Press Back on the connected Android device.")
    val pressHome = spec("press_device_home", "Press Home to show the launcher on the connected Android device.")
    val pressRecents = spec("press_device_recents", "Press the recent apps key to show the task overview on the connected Android device.")
    private val nodeRef = json("type" to "string", "description" to "Copy the control's ref from the latest inspect_device_ui result.")
    private val nodeTools = listOf(
        spec("click_device_node", "Click a control identified in the current Android layout.", "ref",
            properties = json("ref" to nodeRef)),
        spec("long_press_device_node", "Touch and hold a control identified in the current Android layout.", "ref",
            properties = json("ref" to nodeRef)),
        spec("type_device_node", "Focus an editable layout node and type printable ASCII at its cursor.", "ref", "text",
            properties = json("ref" to nodeRef, "text" to deviceTextSchema())),
        spec("scroll_device_node", "Scroll within a scrollable layout node. Down reveals content further down the page. Inspect again to read the new items.", "ref", "direction",
            properties = json("ref" to nodeRef, "direction" to json("type" to "string",
                "enum" to JSONArray(listOf("up", "down", "left", "right")))))
    )
    val nodeToolNames = nodeTools.map { it.name }.toSet()
    private val deviceTools = listOf(
        spec("read_logcat", "Read recent Logcat lines for an installed application on the connected device, including crash logs. Defaults to the current project's built APK; supply its application ID if needed.",
            properties = json(
                "packageName" to json("type" to "string", "description" to "Installed application ID on the connected device."),
                "maxLines" to json("type" to "integer", "minimum" to 1, "maximum" to 400, "default" to 150))),
        spec("inspect_device_ui", "Start here to interact with the connected Android device. Read the foreground layout, control labels, state, and node refs. Use a returned ref to click, long-press, type or scroll. Device input expires the refs. Inspect again after acting. Bounds are [left, top, right, bottom] in screen pixels."),
        deviceActionTool, pressBack, pressHome, pressRecents
    ) + nodeTools
    val captureScreen = spec("capture_device_screen", "Capture a screenshot when requested or when the layout lacks the visual information needed. Returns an image with screen and image dimensions. For a coordinate gesture, scale image coordinates to screen pixels.")
    private val gradleTool = spec("run_gradle", "Run this project's Gradle wrapper tasks and return the build output.", "tasks")
    private val shell = spec("run_shell", "Execute a Bash command in the selected Linux project and return its exit status and output.", "command")

    fun textTools(linux: Boolean, androidAvailable: Boolean): List<AgentTool> {
        if (linux) return commonTools + shell
        val tools = commonTools + deviceTools
        return if (androidAvailable) tools + gradleTool else tools
    }

    private fun spec(name: String, description: String, vararg names: String,
                     properties: JSONObject = JSONObject().apply {
                         for (field in names) put(field, json("type" to "string"))
                     }) = AgentTool(name, description, json("type" to "object", "properties" to properties,
            "required" to JSONArray(names.toList()), "additionalProperties" to false))

    private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun checkedNode(project: Project, path: String): Project.Node {
        if (project.tree == null) {
            val root = File(project.root.uri.path!!).canonicalFile.toPath()
            require(root.resolve(path).toFile().canonicalFile.toPath().startsWith(root)) { uiString(R.string.path_is_outside_the_project) }
        }
        return project.resolve(path)
    }

    private fun validatePath(path: String, rootAllowed: Boolean = false) {
        require(path.isEmpty() && rootAllowed || path.isNotEmpty() &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) { uiString(R.string.a_project_relative_path_is_required) }
    }

    fun perform(project: Project, tool: String, args: JSONObject): String {
        val path = args.string("path")
        validatePath(path, tool == "list_files")
        return when (tool) {
            "list_files" -> project.list(checkedNode(project, path)).joinToString("\n") {
                it.name + if (it.folder) "/" else ""
            }
            "read_file" -> project.read(checkedNode(project, path)).let { text ->
                json("revision" to digest(text), "text" to text).toString()
            }
            "create_directory" -> project.create(checkedNode(project, path.substringBeforeLast('/', "")),
                path.substringAfterLast('/'), true).path
            "move_file", "delete_file" -> {
                val node = checkedNode(project, path)
                require(!node.folder && digest(project.read(node)) == args.getString("revision")) { uiString(R.string.the_file_changed_read_it_again) }
                if (tool == "move_file") {
                    val dest = args.getString("destination")
                    validatePath(dest)
                    checkedNode(project, dest.substringBeforeLast('/', ""))
                    project.relocate(node, dest, false).path
                } else { project.delete(node); uiString(R.string.deleted, path) }
            }
            "write_file" -> {
                val text = args.getString("text")
                val bytes = text.toByteArray()
                require(bytes.size <= Project.MAX_TEXT) { uiString(R.string.the_text_file_exceeds_the_editor_size_limit) }
                val expected = args.getString("revision")
                val original = try { checkedNode(project, path) } catch (_: FileNotFoundException) { null }
                val before = original?.let(project::read)
                if (original == null && expected != "new" ||
                    before != null && digest(before) != expected) {
                    throw IOException(uiString(R.string.the_file_changed_read_again, path))
                }
                val node = original ?: project.create(checkedNode(project, path.substringBeforeLast('/', "")),
                    path.substringAfterLast('/'), false)
                try { project.write(node, bytes) }
                catch (failure: IOException) {
                    try {
                        if (before == null) project.delete(node)
                        else if (project.tree != null) project.write(node, before.toByteArray())
                    } catch (rollback: IOException) { failure.addSuppressed(rollback) }
                    throw failure
                }
                uiString(R.string.saved, node.path, digest(text))
            }
            else -> throw IOException(uiString(R.string.unsupported_tool))
        }
    }

    suspend fun gradle(project: Project, tasks: String): String = coroutineScope {
        val gradle = LocalGradle(project)
        val output = StringBuilder()
        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { gradle.cancel() }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                gradle.run(tasks) { line ->
                    output.appendLine(line)
                    if (output.length > 20000) output.delete(0, output.length - 16000)
                }
            }
            output.toString().ifBlank { uiString(R.string.gradle_tasks_completed) }
        } finally { stop.cancel() }
    }
}
