package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.io.IOException
import java.nio.charset.CharacterCodingException

@Composable
internal fun LayoutPreviewDialog(project: Project, xml: String, path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var preview by remember(project, xml, path) { mutableStateOf<LayoutPreview?>(null) }
    var error by remember(project, xml, path) { mutableStateOf("") }
    var notes by remember(project, xml, path) { mutableStateOf(emptyList<String>()) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(project, xml, path) {
        try {
            preview = runInterruptible(Dispatchers.IO) { LayoutPreview.load(context, project, xml, path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: e.javaClass.simpleName
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (details) uiString(R.string.preview_diff) else uiString(R.string.layout_preview)) },
        text = {
            val loaded = preview
            when {
                error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
                loaded == null -> CircularProgressIndicator()
                details -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    notes.forEach { Text(it, Modifier.padding(vertical = 4.dp)) }
                }
                else -> key(loaded) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                try {
                                    loaded.drawInto(this)
                                    notes = loaded.differences()
                                } catch (e: RuntimeException) {
                                    error = e.localizedMessage ?: e.javaClass.simpleName
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(.62f)
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (notes.isNotEmpty()) TextButton({ details = !details }) {
                Text(if (details) uiString(R.string.back_to_preview) else uiString(R.string.preview_diff))
            }
        }
    )
}

private data class SearchHit(val file: Project.Node, val line: Int, val excerpt: String)
private data class MatchedFile(val node: Project.Node, val digest: ByteArray)
private data class SearchResults(
    val query: String, val files: List<MatchedFile>, val hits: List<SearchHit>, val count: Long
)
private val ignoredFolders = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".cxx", ".kotlin")
private val binaryExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "zip", "jar", "aar", "apk", "aab",
    "keystore", "jks", "class", "dex", "so", "ttf", "otf", "mp3", "mp4", "pdf", "ico", "webm")

private fun searchProject(project: Project, query: String): SearchResults {
    val hits = mutableListOf<SearchHit>()
    val files = mutableListOf<MatchedFile>()
    val hash = MessageDigest.getInstance("SHA-256")
    var count = 0L
    val directories = ArrayDeque<Project.Node>()
    directories.add(project.root)
    while (directories.isNotEmpty()) {
        for (node in project.list(directories.removeFirst())) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (node.folder) {
                if (node.name !in ignoredFolders) directories.addLast(node)
                continue
            }
            if (node.name.substringAfterLast('.', "").lowercase() in binaryExtensions) continue
            val source = try {
                project.read(node)
            } catch (_: CharacterCodingException) {
                continue
            } catch (_: Project.UnsupportedText) {
                continue
            }
            val before = count
            source.lineSequence().forEachIndexed { index, line ->
                var pos = line.indexOf(query)
                if (pos >= 0 && hits.size < 100) {
                    hits += SearchHit(node, index + 1,
                        line.substring((pos - 28).coerceAtLeast(0), (pos + maxOf(90, query.length)).coerceAtMost(line.length)).trimEnd())
                }
                while (pos >= 0) {
                    count++
                    pos = line.indexOf(query, pos + query.length)
                }
            }
            if (count > before) files += MatchedFile(node, hash.digest(source.toByteArray()))
        }
    }
    return SearchResults(query, files, hits, count)
}

private data class ReplaceResult(val changed: List<Project.Node>, val error: String?)

private fun replaceMatches(project: Project, results: SearchResults, replacement: String): ReplaceResult {
    val changed = mutableListOf<Project.Node>()
    val hash = MessageDigest.getInstance("SHA-256")
    fun original(file: MatchedFile): String {
        val source = project.read(file.node)
        if (!hash.digest(source.toByteArray()).contentEquals(file.digest))
            throw IOException(uiString(R.string.changed_after_the_preview_search_again, file.node.path))
        return source
    }
    fun transformed(file: MatchedFile, source: String): ByteArray {
        var index = source.indexOf(results.query)
        var occurrences = 0
        while (index >= 0) {
            occurrences++
            index = source.indexOf(results.query, index + results.query.length)
        }
        val size = source.toByteArray().size.toLong() +
            occurrences.toLong() * (replacement.toByteArray().size - results.query.toByteArray().size)
        if (size > Project.MAX_TEXT) throw IOException(uiString(R.string.would_exceed_the_editor_file_size_limit_after, file.node.path))
        return source.replace(results.query, replacement).toByteArray()
    }
    try {
        // Check the complete plan before its first write and recheck each provider document immediately before writing.
        for (file in results.files) transformed(file, original(file))
        for (file in results.files) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val source = original(file)
            val bytes = transformed(file, source)
            try {
                project.write(file.node, bytes)
            } catch (failure: IOException) {
                // SAF providers can truncate a document before an IOException.
                try {
                    if (project.read(file.node) != source) project.write(file.node, source.toByteArray())
                } catch (restore: Exception) {
                    changed += file.node
                    throw IOException(uiString(R.string.failed_to_write_and_restore_the_original, file.node.path, restore.localizedMessage), failure)
                }
                throw failure
            }
            changed += file.node
        }
    } catch (e: Exception) {
        return ReplaceResult(changed, e.localizedMessage ?: e.javaClass.simpleName)
    }
    return ReplaceResult(changed, null)
}

@Composable
internal fun ProjectSearchDialog(
    project: Project,
    initialQuery: String = "",
    onOpen: (Project.Node, Int) -> Unit,
    onDismiss: () -> Unit,
    replace: Boolean = false,
    beforeReplace: suspend () -> Boolean = { true },
    onChanged: suspend (List<Project.Node>) -> Unit = {}
) {
    var query by remember(project) { mutableStateOf(initialQuery.substringBefore('\n').substringBefore('\r')) }
    var replacement by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, project, reload) {
        val needle = query
        results = null
        error = ""
        scanning = needle.isNotEmpty()
        if (!scanning) return@LaunchedEffect
        delay(250)
        try {
            results = runInterruptible(Dispatchers.IO) { searchProject(project, needle) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: e.javaClass.simpleName
        }
        scanning = false
    }
    val preview = results?.takeIf { it.query == query }
    fun select(hit: SearchHit) { onDismiss(); onOpen(hit.file, hit.line) }
    val canApply = !applying && !scanning && preview != null && preview.count > 0 && replacement != query
    val dismiss = { if (!applying) onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(if (replace) uiString(R.string.replace_in_project) else uiString(R.string.find_in_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it.replace("\n", "").replace("\r", ""); outcome = "" },
                    singleLine = true, enabled = !applying,
                    label = { Text(stringResource(R.string.find_text)) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = if (replace) ImeAction.Next else ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (!replace) preview?.hits?.firstOrNull()?.let(::select) }))
                if (replace) OutlinedTextField(value = replacement, onValueChange = { replacement = it; outcome = "" },
                    enabled = !applying, singleLine = true, label = { Text(stringResource(R.string.replace_with)) }, modifier = Modifier.fillMaxWidth())
                if (scanning || applying) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (outcome.isNotEmpty()) Text(outcome, style = MaterialTheme.typography.bodySmall)
                if (preview != null) {
                    val label = if (preview.count == 0L) uiString(R.string.no_matches)
                        else uiString(R.string.file_match_counts, preview.files.size, preview.count) + if (preview.hits.size == 100) uiString(R.string.previewing_first_100_lines) else ""
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(preview.hits) { hit ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !applying) { select(hit) }.padding(vertical = 6.dp)) {
                                Text("${hit.file.path}:${hit.line}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(hit.excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (replace && replacement != query) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val tint = MaterialTheme.colorScheme.tertiary
                                    Symbol(R.drawable.studio_arrow_right_alt, stringResource(R.string.replace_with),
                                        Modifier.padding(top = 1.dp).size(14.dp), tint)
                                    Text(hit.excerpt.replace(query, replacement.take(140)), color = tint,
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!replace) TextButton(dismiss) { Text(stringResource(R.string.close)) }
            else TextButton(onClick = {
                val plan = preview ?: return@TextButton
                val newText = replacement
                applying = true
                scope.launch {
                    try {
                        if (!beforeReplace()) {
                            outcome = uiString(R.string.replacement_canceled)
                        } else {
                            val result = runInterruptible(Dispatchers.IO) { replaceMatches(project, plan, newText) }
                            onChanged(result.changed)
                            outcome = uiString(R.string.files_updated, result.changed.size) + (result.error?.let { " · $it" } ?: "")
                            reload++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.localizedMessage ?: e.javaClass.simpleName
                    } finally {
                        applying = false
                    }
                }
            }, enabled = canApply) { Text(stringResource(R.string.replace_all)) }
        },
        dismissButton = if (replace) { { TextButton(dismiss, enabled = !applying) { Text(stringResource(R.string.close)) } } } else null
    )
}
