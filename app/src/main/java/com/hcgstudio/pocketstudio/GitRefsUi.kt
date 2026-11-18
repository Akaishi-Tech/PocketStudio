package com.hcgstudio.pocketstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import java.text.DateFormat
import java.util.Date

private enum class RefSection(val title: Int, val empty: Int) {
    Local(R.string.gr_local, R.string.gr_no_local),
    Remote(R.string.gr_remote, R.string.gr_no_remote),
    Tags(R.string.gr_tags, R.string.gr_no_tags),
    Stashes(R.string.gr_stashes, R.string.gr_no_stashes)
}

private data class RefAction(val label: String, val invoke: () -> Unit)
private sealed interface RefPrompt {
    data class Confirm(val title: String, val detail: String, val action: String, val run: (Git) -> Unit) : RefPrompt
    data class Branch(val start: String, val initialName: String = "", val switch: Boolean = true) : RefPrompt
    data class Tag(val start: String) : RefPrompt
    data class Rename(val branch: GitRefItem) : RefPrompt
    data object Stash : RefPrompt
    data class Reset(val id: String, val subject: String) : RefPrompt
}

@Composable
private fun <T : Any> rememberRefData(session: GitSession, key: Any?, load: (Git) -> T): T? {
    var result by remember(session, session.revision, key) { mutableStateOf<T?>(null) }
    LaunchedEffect(session, session.revision, key) {
        try {
            result = session.read(load)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.error(e)
        }
    }
    return result
}

@Composable
private fun RefDropdown(show: Boolean, close: () -> Unit, actions: List<RefAction>) {
    DropdownMenu(expanded = show, onDismissRequest = close, modifier = Modifier.heightIn(max = 440.dp)) {
        actions.forEach { action ->
            DropdownMenuItem(text = { Text(action.label) }, onClick = { close(); action.invoke() })
        }
    }
}

@Composable
private fun RefItem(title: String, leading: Int, actions: List<RefAction>, current: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = actions.isNotEmpty()) { menu = true }
        .heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol(leading, if (current) uiString(R.string.gr_current) else null,
            Modifier.size(18.dp), if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Box {
            if (actions.isNotEmpty()) Symbol(R.drawable.studio_more_vert, null)
            RefDropdown(menu, { menu = false }, actions)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun GitBranchesTab(session: GitSession) {
    var section by remember { mutableStateOf(RefSection.Local) }
    var prompt by remember { mutableStateOf<RefPrompt?>(null) }
    var inspect by remember { mutableStateOf<String?>(null) }
    var compare by remember { mutableStateOf<GitRefItem?>(null) }
    val refs = rememberRefData(session, Unit, GitRefs::list)
    if (refs == null) {
        if (session.problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val head = refs.head
    val safe = refs.state == RepositoryState.SAFE && !session.busy
    val current = Repository.shortenRefName(refs.current).let { if (it == head) it.take(8) else it }

    fun confirm(title: Int, message: String, run: (Git) -> Unit) {
        val label = uiString(title)
        prompt = RefPrompt.Confirm(label, message, label, run)
    }

    val target = compare
    if (target != null) {
        BackHandler { compare = null }
        val patch = rememberRefData(session, target.fullName) { GitRefs.compare(it, Constants.HEAD, target.fullName) }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudioIconButton(R.drawable.studio_arrow_back, uiString(R.string.back)) { compare = null }
                Text("$current → ${target.name}", style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider()
            if (patch == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (patch.isEmpty()) Text(uiString(R.string.gr_no_diff), Modifier.padding(16.dp))
            else GitPatch(patch, Modifier.fillMaxSize())
        }
    } else if (inspect != null) {
        RefCommitDetail(session, inspect!!, showActions = false, close = { inspect = null }, prompt = { prompt = it })
    } else Column(Modifier.fillMaxSize()) {
        GitRefs.recoveryTitle(refs.state)?.let { title ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(uiString(title), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { session.mutate { GitRefs.recover(it, refs.state, RebaseCommand.Operation.CONTINUE) } },
                        enabled = !session.busy && (refs.state.isRebasing || refs.state.canCommit())) {
                        Text(uiString(R.string.gr_continue))
                    }
                    if (refs.state.isRebasing) TextButton(onClick = {
                        confirm(R.string.gr_skip, uiString(R.string.gr_skip_reason)) {
                            GitRefs.recover(it, refs.state, RebaseCommand.Operation.SKIP)
                        }
                    }, enabled = !session.busy) { Text(uiString(R.string.gr_skip)) }
                    TextButton(onClick = {
                        val warning = if (refs.state.isRebasing) R.string.gr_abort_rebase else R.string.gr_abort_reset
                        confirm(R.string.gr_abort, uiString(warning)) {
                            GitRefs.recover(it, refs.state, RebaseCommand.Operation.ABORT)
                        }
                    }, enabled = !session.busy) { Text(uiString(R.string.gr_abort)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RefSection.entries.forEach { group ->
                    FilterChip(selected = section == group, onClick = { section = group }, label = { Text(uiString(group.title)) })
                }
            }
            if (section != RefSection.Remote) {
                val title = when (section) {
                    RefSection.Tags -> R.string.gr_create_tag
                    RefSection.Stashes -> R.string.gr_stash_changes
                    else -> R.string.gr_create_branch
                }
                StudioIconButton(R.drawable.studio_add, uiString(title), enabled = safe && head != null) {
                    prompt = when (section) {
                        RefSection.Tags -> RefPrompt.Tag(Constants.HEAD)
                        RefSection.Stashes -> RefPrompt.Stash
                        else -> RefPrompt.Branch(Constants.HEAD)
                    }
                }
            }
        }
        val entries = when (section) {
            RefSection.Local -> refs.branches.filterNot { it.remote }
            RefSection.Remote -> refs.branches.filter { it.remote }
            RefSection.Tags -> refs.tags
            RefSection.Stashes -> emptyList()
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (entries.isEmpty() && (section != RefSection.Stashes || refs.stashes.isEmpty())) {
                item { Text(uiString(section.empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries, key = { it.fullName }) { ref ->
                val selected = ref.fullName == refs.current
                val actions = buildList {
                    add(RefAction(uiString(R.string.gr_view_commit)) { inspect = ref.id })
                    if (head != null && !selected) add(RefAction(uiString(R.string.gr_compare)) { compare = ref })
                    if (safe && section == RefSection.Local && !selected) add(RefAction(uiString(R.string.gr_switch)) {
                        session.mutate { it.checkout().setName(ref.fullName).call() }
                    })
                    if (safe && section == RefSection.Remote) add(RefAction(uiString(R.string.gr_track)) {
                        val name = ref.name.substringAfter('/')
                        prompt = RefPrompt.Branch(ref.fullName, name)
                    })
                    if (safe && section != RefSection.Remote) add(RefAction(uiString(R.string.gr_create_branch)) {
                        prompt = RefPrompt.Branch(ref.fullName, switch = false)
                    })
                    if (safe && !selected && head != null && section != RefSection.Tags) {
                        add(RefAction(uiString(R.string.gr_merge)) {
                            confirm(R.string.gr_merge, uiString(R.string.gr_merge_target, ref.name, current)) { GitRefs.merge(it, ref.fullName) }
                        })
                        add(RefAction(uiString(R.string.gr_rebase)) {
                            confirm(R.string.gr_rebase, uiString(R.string.gr_rebase_target, current, ref.name)) { GitRefs.rebase(it, ref.fullName) }
                        })
                    }
                    if (safe && section == RefSection.Local) {
                        add(RefAction(uiString(R.string.rename)) { prompt = RefPrompt.Rename(ref) })
                        if (!selected) add(RefAction(uiString(R.string.delete)) {
                            confirm(R.string.delete, uiString(R.string.gr_delete_branch, ref.name)) {
                                it.branchDelete().setBranchNames(ref.fullName).call()
                            }
                        })
                    }
                    if (safe && section == RefSection.Tags) add(RefAction(uiString(R.string.gr_checkout_commit)) {
                        confirm(R.string.gr_checkout_commit, uiString(R.string.gr_checkout_detached, ref.name)) {
                            it.checkout().setName(ref.id).call()
                        }
                    })
                    if (safe && section == RefSection.Tags) add(RefAction(uiString(R.string.delete)) {
                        confirm(R.string.delete, uiString(R.string.gr_delete_tag, ref.name)) { it.tagDelete().setTags(ref.fullName).call() }
                    })
                }
                val icon = if (selected) R.drawable.studio_check else if (section == RefSection.Tags)
                    R.drawable.studio_link else R.drawable.studio_git_branch
                RefItem(ref.name, icon, actions, current = selected)
            }
            if (section == RefSection.Stashes) items(refs.stashes, key = { it.index }) { stash ->
                val actions = buildList {
                    add(RefAction(uiString(R.string.gr_view_commit)) { inspect = stash.id })
                    if (safe) {
                        add(RefAction(uiString(R.string.apply)) { session.mutate { GitRefs.applyStash(it, stash, pop = false) } })
                        add(RefAction(uiString(R.string.gr_pop)) { session.mutate { GitRefs.applyStash(it, stash, pop = true) } })
                        add(RefAction(uiString(R.string.delete)) {
                            confirm(R.string.delete, uiString(R.string.gr_drop_stash, stash.message)) { GitRefs.dropStash(it, stash) }
                        })
                    }
                }
                RefItem(stash.message, R.drawable.studio_inventory_2, actions)
            }
        }
    }
    prompt?.let { RefPromptDialog(it, session) { prompt = null } }
}

private data class RefHistory(
    val branches: List<GitRefItem>, val commits: List<GitCommitItem>, val selectedRef: String, val graph: List<GitGraphRow>
)

@Composable
internal fun GitHistoryTab(session: GitSession) {
    var source by remember { mutableStateOf(Constants.HEAD) }
    var count by remember(source) { mutableIntStateOf(50) }
    val scroll = key(source) { rememberLazyListState() }
    var selected by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<RefPrompt?>(null) }
    if (selected != null) {
        RefCommitDetail(session, selected!!, showActions = true, close = { selected = null }, prompt = { prompt = it })
    } else {
        val data = rememberRefData(session, source to count) { git ->
            val branches = GitRefs.branches(git)
            val ref = source.takeIf { target -> branches.any { it.fullName == target } } ?: Constants.HEAD
            val commits = GitRefs.history(git, ref, count + 1)
            RefHistory(branches, commits, ref, gitGraph(commits))
        }
        if (data == null) {
            if (session.problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            return
        }
        var choose by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 4.dp)) {
                TextButton({ choose = true }) {
                    Symbol(R.drawable.studio_git_branch, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (data.selectedRef == Constants.HEAD) uiString(R.string.gr_checked_out) else Repository.shortenRefName(data.selectedRef))
                    Symbol(R.drawable.studio_expand_more, null)
                }
                val options = listOf(RefAction(uiString(R.string.gr_checked_out)) { source = Constants.HEAD }) +
                    data.branches.map { RefAction(it.name) { source = it.fullName } }
                RefDropdown(choose, { choose = false }, options)
            }
            val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            val lanes = data.graph.maxOfOrNull { it.lanes } ?: 1
            LazyColumn(Modifier.fillMaxSize(), state = scroll) {
                if (data.commits.isEmpty()) item {
                    Text(uiString(R.string.no_commits_yet), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(data.commits.take(count), key = { _, commit -> commit.id }) { index, commit ->
                    RefHistoryRow(commit, data.graph[index], lanes, date, open = { selected = commit.id })
                }
                if (data.commits.size > count) item {
                    TextButton({ count += 50 }, Modifier.fillMaxWidth()) { Text(uiString(R.string.gr_load_more)) }
                }
            }
        }
    }
    prompt?.let { RefPromptDialog(it, session) { prompt = null } }
}

@Composable
private fun RefHistoryRow(commit: GitCommitItem, graph: GitGraphRow, lanes: Int, date: DateFormat, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    fun color(index: Int) = when (index) {
        0 -> colors.primary
        1 -> colors.tertiary
        2 -> colors.secondary
        else -> colors.outline
    }
    val gutter = (14 + lanes * 10).dp
    Row(Modifier.fillMaxWidth().clickable(onClick = open).drawBehind {
        fun x(lane: Int) = (10 + lane * 10).dp.toPx()
        fun line(edge: GitGraphLine, start: Float, end: Float) {
            drawLine(color(edge.color), Offset(x(edge.from), start), Offset(x(edge.to), end), 1.5.dp.toPx())
        }
        val mid = size.height / 2
        graph.passing.forEach { line(it, 0f, size.height) }
        graph.incoming.forEach { line(it, 0f, mid) }
        graph.outgoing.forEach { line(it, mid, size.height) }
        val center = Offset(x(graph.node), mid)
        drawCircle(colors.surface, 4.5.dp.toPx(), center)
        drawCircle(color(graph.color), 3.2.dp.toPx(), center)
    }) {
        Spacer(Modifier.width(gutter))
        Column(Modifier.weight(1f)) {
            Column(Modifier.padding(top = 9.dp, bottom = 9.dp, end = 12.dp)) {
                Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${commit.id.take(8)}  ·  ${commit.author}  ·  ${date.format(Date(commit.date))}",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

@Composable
private fun RefCommitDetail(session: GitSession, id: String, showActions: Boolean, close: () -> Unit, prompt: (RefPrompt) -> Unit) {
    BackHandler(onBack = close)
    var parent by remember(id) { mutableIntStateOf(0) }
    var menu by remember(id) { mutableStateOf(false) }
    var parentMenu by remember(id) { mutableStateOf(false) }
    val data = rememberRefData(session, id to parent) { git ->
        GitRefs.detail(git, id, parent) to (git.repository.repositoryState == RepositoryState.SAFE)
    }
    val detail = data?.first
    val safe = data?.second == true && !session.busy
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioIconButton(R.drawable.studio_arrow_back, uiString(R.string.back), onClick = close)
            SelectionContainer(Modifier.weight(1f)) { Text(id.take(12), fontFamily = FontFamily.Monospace) }
            if (showActions && detail != null) Box {
                StudioIconButton(R.drawable.studio_more_vert, uiString(R.string.gr_commit_actions), enabled = safe) { menu = true }
                val summary = detail.message.lineSequence().firstOrNull().orEmpty()
                val short = id.take(8)
                val choices = buildList {
                    add(RefAction(uiString(R.string.gr_create_branch)) { prompt(RefPrompt.Branch(id, switch = false)) })
                    add(RefAction(uiString(R.string.gr_create_tag)) { prompt(RefPrompt.Tag(id)) })
                    add(RefAction(uiString(R.string.gr_checkout_commit)) {
                        val action = uiString(R.string.gr_checkout_commit)
                        prompt(RefPrompt.Confirm(action, uiString(R.string.gr_checkout_detached, short), action) {
                            it.checkout().setName(id).call()
                        })
                    })
                    add(RefAction(uiString(R.string.gr_cherry_pick)) {
                        val mainline = if (detail.parents.size > 1) parent + 1 else null
                        val target = if (mainline == null) "$short · $summary" else uiString(R.string.gr_pick_parent, short, mainline)
                        prompt(RefPrompt.Confirm(uiString(R.string.gr_cherry_pick), target, uiString(R.string.gr_cherry_pick)) {
                            GitRefs.cherryPick(it, id, mainline)
                        })
                    })
                    if (detail.parents.size == 1) add(RefAction(uiString(R.string.gr_revert)) {
                        prompt(RefPrompt.Confirm(uiString(R.string.gr_revert), "$short · $summary", uiString(R.string.gr_revert)) {
                            GitRefs.revert(it, id)
                        })
                    })
                    add(RefAction(uiString(R.string.gr_reset)) { prompt(RefPrompt.Reset(id, summary)) })
                }
                RefDropdown(menu, { menu = false }, choices)
            }
        }
        if (detail == null) {
            if (session.problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 132.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SelectionContainer { Text(detail.message, style = MaterialTheme.typography.bodyMedium) }
            SelectionContainer { Text(detail.identity, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (detail.parents.size > 1) Box {
            TextButton({ parentMenu = true }) {
                Text(uiString(R.string.gr_compare_parent, parent + 1, detail.parents[parent].take(8)))
                Symbol(R.drawable.studio_expand_more, null)
            }
            RefDropdown(parentMenu, { parentMenu = false }, detail.parents.mapIndexed { index, hash ->
                RefAction(uiString(R.string.gr_compare_parent, index + 1, hash.take(8))) { parent = index }
            })
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        if (detail.patch.isNotEmpty()) GitPatch(detail.patch, Modifier.fillMaxSize())
        else Text(uiString(R.string.gr_no_diff), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RefPromptDialog(prompt: RefPrompt, session: GitSession, close: () -> Unit) {
    if (prompt is RefPrompt.Confirm) {
        AlertDialog(onDismissRequest = close, title = { Text(prompt.title) }, text = { Text(prompt.detail) },
            confirmButton = { TextButton({ close(); session.mutate(block = prompt.run) }, enabled = !session.busy) { Text(prompt.action) } },
            dismissButton = { TextButton(close) { Text(uiString(R.string.cancel)) } })
        return
    }
    val initial = when (prompt) {
        is RefPrompt.Rename -> prompt.branch.name
        is RefPrompt.Branch -> prompt.initialName
        else -> ""
    }
    var name by remember(prompt) { mutableStateOf(initial) }
    var message by remember(prompt) { mutableStateOf("") }
    var checked by remember(prompt) { mutableStateOf((prompt as? RefPrompt.Branch)?.switch == true) }
    var mode by remember(prompt) { mutableStateOf(ResetCommand.ResetType.MIXED) }
    val title = when (prompt) {
        is RefPrompt.Rename -> R.string.rename
        is RefPrompt.Branch -> R.string.gr_create_branch
        is RefPrompt.Tag -> R.string.gr_create_tag
        RefPrompt.Stash -> R.string.gr_stash_changes
        is RefPrompt.Reset -> R.string.gr_reset
        else -> error("Unexpected prompt")
    }
    val enabled = when (prompt) {
        is RefPrompt.Branch, is RefPrompt.Tag -> name.isNotBlank()
        is RefPrompt.Rename -> name.isNotBlank() && name.trim() != initial
        else -> true
    }
    AlertDialog(onDismissRequest = close, title = { Text(uiString(title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt is RefPrompt.Branch || prompt is RefPrompt.Tag || prompt is RefPrompt.Rename) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiString(if (prompt is RefPrompt.Rename) R.string.new_name else R.string.name)) }, singleLine = true)
                }
                if (prompt is RefPrompt.Tag || prompt == RefPrompt.Stash) {
                    OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiString(R.string.gr_optional_message)) }, maxLines = 3)
                }
                if (prompt is RefPrompt.Branch || prompt == RefPrompt.Stash) {
                    val label = if (prompt is RefPrompt.Branch) R.string.gr_switch_new else R.string.gr_include_untracked
                    Row(Modifier.fillMaxWidth().clickable { checked = !checked }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(uiString(label))
                    }
                }
                if (prompt is RefPrompt.Reset) {
                    Text("${prompt.id.take(8)} · ${prompt.subject}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val modes = listOf(ResetCommand.ResetType.SOFT to R.string.gr_reset_soft,
                        ResetCommand.ResetType.MIXED to R.string.gr_reset_mixed, ResetCommand.ResetType.HARD to R.string.gr_reset_hard)
                    modes.forEach { (type, label) ->
                        Row(Modifier.fillMaxWidth().clickable { mode = type }.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = type == mode, onClick = null, modifier = Modifier.padding(end = 12.dp))
                            Text(uiString(label), color = if (type == ResetCommand.ResetType.HARD && type == mode)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val label = when (prompt) {
                is RefPrompt.Branch, is RefPrompt.Tag -> R.string.create
                else -> title
            }
            TextButton(onClick = {
                val value = name.trim()
                close()
                session.mutate { git ->
                    when (prompt) {
                        is RefPrompt.Branch -> GitRefs.createBranch(git, value, prompt.start, checked)
                        is RefPrompt.Tag -> GitRefs.createTag(git, value, prompt.start, message)
                        is RefPrompt.Rename -> git.branchRename().setOldName(prompt.branch.fullName).setNewName(value).call()
                        RefPrompt.Stash -> GitRefs.stash(git, message, checked)
                        is RefPrompt.Reset -> git.reset().setRef(prompt.id).setMode(mode).call()
                        is RefPrompt.Confirm -> Unit
                    }
                }
            }, enabled = enabled && !session.busy) { Text(uiString(label)) }
        }, dismissButton = { TextButton(close) { Text(uiString(R.string.cancel)) } })
}
