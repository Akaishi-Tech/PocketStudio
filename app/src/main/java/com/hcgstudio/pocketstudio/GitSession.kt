package com.hcgstudio.pocketstudio

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal enum class GitTab(val label: Int) {
    Changes(R.string.git_tab_changes), History(R.string.git_tab_history),
    Branches(R.string.git_tab_branches), Remotes(R.string.git_tab_remotes)
}

internal data class GitHead(val name: String, val ahead: Int = 0, val behind: Int = 0)

/** Holds the same native workspace lease used by Gradle until the user leaves the Git page. */
internal class GitSession(private val workspace: Workspace, val project: Project, initialTab: GitTab, file: String?, diff: Boolean) {
    var tab by mutableStateOf(initialTab)
    var filePath by mutableStateOf(file)
    var showDiff by mutableStateOf(diff)
    var busy by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var head by mutableStateOf<GitHead?>(null)
        private set
    var uninitialized by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    private var pendingReads by mutableIntStateOf(0)
    val loading get() = busy || pendingReads > 0

    private var work: ProjectWorkspace? = null
    private val leaving = CompletableDeferred<String?>()

    suspend fun untilClosed(): String? {
        try {
            refresh()
            return leaving.await()
        } finally {
            withContext(NonCancellable) {
                workspace.projectOperation(project) { work?.close() }
            }
        }
    }

    fun close() = leave(null)
    fun edit(path: String) = leave(path)
    private fun leave(path: String?) {
        if (!busy) {
            busy = true
            leaving.complete(path)
        }
    }

    fun error(e: Exception) {
        problem = e.localizedMessage ?: e.javaClass.simpleName
    }

    fun clearError() { problem = null }

    // Always open exactly the project selected in Studio. A repository's core.worktree
    // or a parent repository must never redirect edits outside that project.
    private fun <T> withGit(native: ProjectWorkspace, block: (Git) -> T): T {
        val metadata = native.directory.resolve(Constants.DOT_GIT)
        check(Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)) { uiString(R.string.git_root_required) }
        val repository = FileRepositoryBuilder().setFS(GitFilesystem()).setGitDir(metadata.toFile())
            .setWorkTree(native.directory.toFile()).setMustExist(true).build()
        return repository.use { repo ->
            // Existing repositories may request native links even on document storage.
            // Use ordinary Git link-text files, as we do on creation and clone.
            if (repo.config.getBoolean("core", "symlinks", true))
                repo.config.setBoolean("core", null, "symlinks", false)
            // Document providers and ZIP imports do not preserve Unix executable bits.
            val imports = File(project.context.filesDir, "imports").toPath()
            val zipImport = native.directory.startsWith(imports) &&
                imports.relativize(native.directory).getName(0).toString().startsWith("project-")
            if (project.tree != null || zipImport) {
                repo.config.setBoolean("core", null, "filemode", false)
            }
            Git.wrap(repo).use(block)
        }
    }

    suspend fun <T> read(block: (Git) -> T): T {
        pendingReads++
        try {
            return workspace.projectOperation(project) { withGit(checkNotNull(work), block) }
        } finally {
            pendingReads--
        }
    }

    fun refresh() = update()
    fun init() = update { native ->
        check(!Files.exists(native.directory.resolve(Constants.DOT_GIT), LinkOption.NOFOLLOW_LINKS)) {
            uiString(R.string.git_repository_exists)
        }
        Git.init().setFs(GitFilesystem()).setDirectory(native.directory.toFile()).setInitialBranch("main").call().close()
    }

    fun mutate(onSuccess: () -> Unit = {}, block: (Git) -> Unit) =
        update(onSuccess) { native -> withGit(native, block) }

    private fun update(onSuccess: () -> Unit = {}, operation: ((ProjectWorkspace) -> Unit)? = null) {
        if (busy || leaving.isCompleted) return
        busy = true
        problem = null
        workspace.action {
            try {
                var operationError: Exception? = null
                val current = workspace.projectOperation(project) {
                    val native = work?.also { it.sync() } ?: ProjectWorkspace.open(project).also { work = it }
                    if (operation != null) {
                        try {
                            operation(native)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            operationError = e
                        } finally {
                            // A stopped rebase or conflicted pull can change both HEAD and the worktree.
                            native.sync()
                        }
                    }
                    if (!Files.exists(native.directory.resolve(Constants.DOT_GIT), LinkOption.NOFOLLOW_LINKS)) null
                    else withGit(native) { git ->
                        val full = git.repository.fullBranch ?: throw IllegalStateException(uiString(R.string.git_root_required))
                        if (full.startsWith(Constants.R_HEADS)) {
                            val name = Repository.shortenRefName(full)
                            val tracking = BranchTrackingStatus.of(git.repository, name)
                            GitHead(name, tracking?.aheadCount ?: 0, tracking?.behindCount ?: 0)
                        } else GitHead(uiString(R.string.git_detached_head, full.take(8)))
                    }
                }
                head = current
                uninitialized = current == null
                operationError?.let { throw it }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e)
            } finally {
                try {
                    if (operation != null) workspace.refreshAfterExternalRun(project)
                } finally {
                    revision++
                    busy = false
                }
            }
        }
    }
}
