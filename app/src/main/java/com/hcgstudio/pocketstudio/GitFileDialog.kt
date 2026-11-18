package com.hcgstudio.pocketstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import java.text.DateFormat
import java.util.Date

private sealed interface FilePage {
    data object History : FilePage
    data object Blame : FilePage
    data class Commit(val id: String, val path: String) : FilePage
}

@Composable
internal fun GitFileDialog(session: GitSession, path: String, onDismiss: () -> Unit) {
    var blame by remember(path) { mutableStateOf(false) }
    var selected by remember(path) { mutableStateOf<FilePage.Commit?>(null) }
    var limit by remember(path) { mutableIntStateOf(50) }
    val historyScroll = key(path) { rememberLazyListState() }
    val blameScroll = key(path) { rememberLazyListState() }
    Dialog(onDismissRequest = { if (selected == null) onDismiss() else selected = null }) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
                Row(Modifier.fillMaxWidth().padding(start = if (selected == null) 16.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back)) { selected = null }
                    Text(selected?.path ?: path, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    StudioIconButton(R.drawable.studio_close, stringResource(R.string.close), onClick = onDismiss)
                }
                if (selected == null) {
                    PrimaryTabRow(selectedTabIndex = if (blame) 1 else 0) {
                        Tab(!blame, { blame = false }, text = { Text(stringResource(R.string.git_file_history)) })
                        Tab(blame, { blame = true }, text = { Text(stringResource(R.string.git_file_blame)) })
                    }
                }
                val page: FilePage = selected ?: if (blame) FilePage.Blame else FilePage.History
                StudioPageTransition(page, { if (it is FilePage.Commit) 1 else 0 }, Modifier.fillMaxSize()) { shown ->
                    when (shown) {
                        is FilePage.Commit -> FileResult(session, shown, { GitRefs.detail(it, shown.id, 0, shown.path) }) { detail ->
                            Column(Modifier.fillMaxSize()) {
                                Text(detail.message.lineSequence().firstOrNull().orEmpty(), Modifier.padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${shown.id.take(8)}  ·  ${detail.identity}", Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (detail.patch.isEmpty()) Text(stringResource(R.string.git_change_no_diff), Modifier.padding(12.dp))
                                else GitPatch(detail.patch, Modifier.fillMaxSize())
                            }
                        }
                        FilePage.Blame -> FileResult(session, path, { gitFileBlame(it, path) }) { data ->
                            if (data.emptyReason != null) Text(stringResource(data.emptyReason), Modifier.padding(12.dp))
                            else BlameLines(data.lines, blameScroll) { id, original -> selected = FilePage.Commit(id, original) }
                        }
                        FilePage.History -> FileHistory(session, path, limit, historyScroll, more = { limit += 50 },
                            open = { selected = FilePage.Commit(it, path) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileHistory(session: GitSession, path: String, limit: Int, scroll: LazyListState, more: () -> Unit, open: (String) -> Unit) {
    FileResult(session, path to limit, { GitRefs.history(it, Constants.HEAD, limit + 1, path) }) { commits ->
        if (commits.isEmpty()) Text(stringResource(R.string.git_file_no_history), Modifier.padding(12.dp))
        else {
            val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            LazyColumn(state = scroll) {
                items(commits.take(limit), key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().clickable { open(item.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(item.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("${item.id.take(8)}  ·  ${item.author}  ·  ${date.format(Date(item.date))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                if (commits.size > limit) item {
                    TextButton(more) { Text(stringResource(R.string.gr_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> FileResult(session: GitSession, key: Any, read: (Git) -> T, content: @Composable (T) -> Unit) {
    var result by remember(session, key) { mutableStateOf<T?>(null) }
    var problem by remember(session, key) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, session.revision, key) {
        try {
            problem = null
            result = session.read(read)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.localizedMessage ?: e.javaClass.simpleName
        }
    }
    val value = result
    when {
        problem != null -> Text(problem.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        value == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> content(value)
    }
}

@Composable
private fun BlameLines(lines: List<GitBlameLine>, scroll: LazyListState, open: (String, String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val viewport = maxWidth
        LazyColumn(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), state = scroll,
            contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(lines) { index, line ->
                if (index == 0 || lines[index - 1].commit != line.commit || lines[index - 1].path != line.path) {
                    Text("${line.commit.take(8)}  ·  ${line.author}",
                        Modifier.widthIn(min = viewport).clickable { open(line.commit, line.path) }.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text((index + 1).toString().padStart(lines.size.toString().length), Modifier.padding(end = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(line.text, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
