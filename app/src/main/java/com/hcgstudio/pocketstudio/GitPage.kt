package com.hcgstudio.pocketstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator

@Composable
internal fun GitPage(session: GitSession) {
    BackHandler { session.close() }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), !session.busy, session::close)
            Text(stringResource(R.string.git), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            session.head?.let { head ->
                TextButton({ session.tab = GitTab.Branches }, Modifier.widthIn(max = 204.dp), enabled = !session.busy) {
                    Symbol(R.drawable.studio_git_branch, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(buildString {
                        append(head.name)
                        if (head.ahead > 0) append("  ↑${head.ahead}")
                        if (head.behind > 0) append("  ↓${head.behind}")
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StudioIconButton(R.drawable.studio_refresh, stringResource(R.string.git_refresh), !session.busy, session::refresh)
        }
        if (session.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else HorizontalDivider()
        if (session.uninitialized) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Symbol(R.drawable.studio_git_branch, null, Modifier.size(40.dp), colors.primary)
                Text(session.project.root.name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                Button(session::init, enabled = !session.busy) { Text(stringResource(R.string.git_init)) }
            }
        } else if (session.head != null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tabs: @Composable () -> Unit = {
                    GitTab.entries.forEach { tab ->
                        Tab(session.tab == tab, { session.tab = tab }, enabled = !session.busy,
                            text = { Text(stringResource(tab.label), maxLines = 1) })
                    }
                }
                val scale = LocalDensity.current.fontScale * LocalStudioSettings.current.uiFontSize / 14f
                if (maxWidth < 390.dp * scale)
                    PrimaryScrollableTabRow(session.tab.ordinal, edgePadding = 0.dp, tabs = tabs)
                else PrimaryTabRow(session.tab.ordinal, tabs = tabs)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                StudioPageTransition(session.tab, { 0 }, Modifier.widthIn(max = 1080.dp).fillMaxSize()) { tab ->
                    when (tab) {
                        GitTab.Changes -> GitChangesTab(session)
                        GitTab.History -> GitHistoryTab(session)
                        GitTab.Branches -> GitBranchesTab(session)
                        GitTab.Remotes -> GitRemotesTab(session)
                    }
                }
            }
        } else if (!session.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OutlinedButton(session::refresh) { Text(stringResource(R.string.git_refresh)) }
            }
        }
    }
    if (session.head != null) {
        if (session.showDiff) GitDiffDialog(session) { session.showDiff = false }
        session.filePath?.let { path -> GitFileDialog(session, path) { session.filePath = null } }
    }
    session.problem?.let { message ->
        AlertDialog(onDismissRequest = session::clearError, title = { Text(stringResource(R.string.git)) },
            text = { SelectionContainer { Text(message) } },
            confirmButton = { TextButton(session::clearError) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun GitDiffDialog(session: GitSession, close: () -> Unit) {
    var staged by remember { mutableStateOf(false) }
    var patch by remember(staged) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, staged, session.revision) {
        try {
            patch = session.read { git ->
                val repo = git.repository
                val index = DirCacheIterator(repo.readDirCache())
                if (staged) repo.newObjectReader().use { reader ->
                    val head = repo.resolve("HEAD^{tree}")?.let { CanonicalTreeParser(null, reader, it) } ?: EmptyTreeIterator()
                    gitFilePatch(git, null, head, index)
                } else gitFilePatch(git, null, index, FileTreeIterator(repo))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close()
            session.error(e)
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.view_diff)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!staged, { staged = false }, label = { Text(stringResource(R.string.git_worktree)) })
                    FilterChip(staged, { staged = true }, label = { Text(stringResource(R.string.git_index)) })
                }
                val content = patch
                when {
                    content == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    content.isEmpty() -> Text(stringResource(R.string.git_change_no_diff), Modifier.padding(vertical = 10.dp))
                    else -> GitPatch(content, Modifier.heightIn(max = 440.dp))
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.close)) } })
}

/** Unified diff with shared horizontal scrolling and a bounded, lazy list of lines. */
@Composable
internal fun GitPatch(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val lines = remember(text) { text.trimEnd('\n').split('\n') }
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(max = 600.dp)) {
        val viewport = maxWidth
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                items(lines) { line ->
                    val (background, foreground) = when {
                        line.startsWith("+++ ") || line.startsWith("--- ") -> colors.surfaceVariant to colors.onSurfaceVariant
                        line.startsWith("+") -> colors.primaryContainer to colors.onPrimaryContainer
                        line.startsWith("-") -> colors.errorContainer to colors.onErrorContainer
                        line.startsWith("@@") -> colors.secondaryContainer to colors.onSecondaryContainer
                        else -> colors.surface to colors.onSurface
                    }
                    Text(line, Modifier.widthIn(min = viewport, max = maxOf(viewport, 1600.dp)).background(background)
                        .padding(horizontal = 8.dp), color = foreground, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
