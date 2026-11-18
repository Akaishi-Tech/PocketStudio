package com.hcgstudio.pocketstudio

import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal enum class GitArea { Conflicts, Staged, Unstaged }

internal enum class GitChangeKind(val code: String, val label: Int) {
    Added("A", R.string.git_change_added), Modified("M", R.string.git_change_modified),
    Deleted("D", R.string.git_change_deleted), Untracked("?", R.string.git_change_untracked),
    Conflict("U", R.string.git_change_conflict)
}

internal data class GitChange(val path: String, val area: GitArea, val kind: GitChangeKind)

internal data class GitChanges(
    val groups: Map<GitArea, List<GitChange>>,
    val head: String?,
    val previousMessage: String,
    val mergeMessage: String,
    val canCommit: Boolean,
    val canAmend: Boolean,
    val name: String,
    val email: String
)

internal object GitChangesStore {
    private fun files(status: Status, area: GitArea): List<GitChange> {
        fun entries(paths: Set<String>, kind: GitChangeKind) = paths.map { GitChange(it, area, kind) }
        val changes = when (area) {
            GitArea.Conflicts -> entries(status.conflicting, GitChangeKind.Conflict)
            GitArea.Staged -> entries(status.added, GitChangeKind.Added) +
                entries(status.changed, GitChangeKind.Modified) + entries(status.removed, GitChangeKind.Deleted)
            GitArea.Unstaged -> entries(status.untracked, GitChangeKind.Untracked) +
                entries(status.modified, GitChangeKind.Modified) + entries(status.missing, GitChangeKind.Deleted)
        }
        return changes.filter { area == GitArea.Conflicts || it.path !in status.conflicting }.sortedBy { it.path }
    }

    fun load(git: Git): GitChanges {
        val repo = git.repository
        val status = git.status().call()
        val groups = GitArea.entries.associateWith { files(status, it) }
        val head = repo.resolve(Constants.HEAD)
        val message = head?.let { RevWalk(repo).use { walk -> walk.parseCommit(it).fullMessage } }.orEmpty()
        val state = repo.repositoryState
        val merging = state == RepositoryState.MERGING_RESOLVED
        return GitChanges(groups, head?.name, message,
            if (state == RepositoryState.SAFE) "" else repo.readMergeCommitMsg().orEmpty(),
            state.canCommit() && !state.isRebasing && status.conflicting.isEmpty() &&
                (groups.getValue(GitArea.Staged).isNotEmpty() || merging),
            state == RepositoryState.SAFE && head != null,
            repo.config.getString("user", null, "name").orEmpty(),
            repo.config.getString("user", null, "email").orEmpty())
    }

    internal fun checkedPath(git: Git, path: String): Path {
        val root = git.repository.workTree.toPath()
        val parts = path.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) })
        var result = root
        for ((index, part) in parts.withIndex()) {
            result = result.resolve(part)
            if (index != parts.lastIndex && Files.isSymbolicLink(result))
                throw IOException(uiString(R.string.git_change_path_changed, path))
        }
        return result
    }

    private fun ensureFile(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw IOException(uiString(R.string.git_change_path_changed, path.fileName))
    }

    fun stage(git: Git, entries: List<GitChange>) {
        if (entries.isEmpty()) return
        val status = git.status().call()
        val available = files(status, GitArea.Unstaged).toSet()
        val index = git.repository.readDirCache()
        val present = git.add()
        val deleted = git.add().setUpdate(true)
        var hasPresent = false
        var hasDeleted = false
        for (entry in entries) {
            require(entry in available) { uiString(R.string.git_change_path_changed, entry.path) }
            val path = checkedPath(git, entry.path)
            // Updating a missing gitlink is a valid index-only operation.
            if (entry.kind == GitChangeKind.Deleted) {
                deleted.addFilepattern(entry.path)
                hasDeleted = true
            } else {
                if (index.getEntry(entry.path)?.fileMode != FileMode.GITLINK)
                    ensureFile(path)
                present.addFilepattern(entry.path)
                hasPresent = true
            }
        }
        if (hasPresent) present.call()
        if (hasDeleted) deleted.call()
    }

    fun unstage(git: Git, entries: List<GitChange>) {
        if (entries.isEmpty()) return
        val available = files(git.status().call(), GitArea.Staged).toSet()
        val cmd = git.reset()
        for (entry in entries) {
            require(entry in available) { uiString(R.string.git_change_path_changed, entry.path) }
            checkedPath(git, entry.path)
            cmd.addPath(entry.path)
        }
        cmd.call()
    }

    fun discard(git: Git, entry: GitChange) {
        require(entry in files(git.status().call(), GitArea.Unstaged)) {
            uiString(R.string.git_change_path_changed, entry.path)
        }
        val path = checkedPath(git, entry.path)
        ensureFile(path)
        if (entry.kind == GitChangeKind.Untracked) Files.delete(path)
        else git.checkout().addPath(entry.path).call()
    }

    fun ignore(git: Git, entry: GitChange) {
        require(entry.kind == GitChangeKind.Untracked && entry.path in git.status().call().untracked) {
            uiString(R.string.git_change_path_changed, entry.path)
        }
        checkedPath(git, entry.path)
        require('\n' !in entry.path && '\r' !in entry.path) { uiString(R.string.git_change_ignore_newline) }
        val pattern = buildString {
            append('/')
            for (c in entry.path) {
                if (c in "\\*?[] ") append('\\')
                append(c)
            }
        }
        val ignoreFile = git.repository.workTree.toPath().resolve(".gitignore")
        if (Files.exists(ignoreFile, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS))
            throw IOException(uiString(R.string.git_change_ignore_file))
        FileChannel.open(ignoreFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            val tail = ByteBuffer.allocate(minOf(size, 4096L).toInt())
            channel.position(size - tail.capacity())
            while (tail.hasRemaining() && channel.read(tail) > 0) { /* read the last line ending */ }
            val text = String(tail.array(), 0, tail.position(), Charsets.ISO_8859_1)
            val newline = text.lastIndexOf('\n')
            val ending = if (newline > 0 && text[newline - 1] == '\r') "\r\n" else "\n"
            val prefix = if (size != 0L && !text.endsWith("\n")) ending else ""
            val addition = ByteBuffer.wrap((prefix + pattern + ending).toByteArray(Charsets.UTF_8))
            channel.position(size)
            while (addition.hasRemaining()) channel.write(addition)
        }
    }

    fun resolve(git: Git, path: String, side: CheckoutCommand.Stage?) {
        require(path in git.status().call().conflicting) { uiString(R.string.git_change_path_changed, path) }
        val file = checkedPath(git, path)
        ensureFile(file)
        if (side != null) {
            val stageNumber = if (side == CheckoutCommand.Stage.OURS) 2 else 3
            val index = git.repository.readDirCache()
            val first = index.findEntry(path)
            val present = first >= 0 && (first until index.nextEntry(first)).any { index.getEntry(it).stage == stageNumber }
            if (present) git.checkout().setStage(side).addPath(path).call()
            else Files.deleteIfExists(file)
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) git.add().addFilepattern(path).call()
        else git.rm().setCached(true).addFilepattern(path).call()
    }

    fun commit(git: Git, expectedHead: String?, message: String, name: String, email: String, amend: Boolean) {
        val changes = load(git)
        require(changes.head == expectedHead && if (amend) changes.canAmend else changes.canCommit) {
            uiString(R.string.git_change_commit_changed)
        }
        require(message.isNotBlank() && name.isNotBlank() && email.isNotBlank())
        val config = git.repository.config
        if (changes.name != name.trim() || changes.email != email.trim()) {
            config.setString("user", null, "name", name.trim())
            config.setString("user", null, "email", email.trim())
            config.save()
        }
        git.commit().setAmend(amend).setAllowEmpty(amend || git.repository.repositoryState == RepositoryState.MERGING_RESOLVED)
            .setMessage(message.trim()).setCommitter(name.trim(), email.trim()).call()
    }

    data class Preview(val text: String, val editable: Boolean, val oursDeleted: Boolean = false, val theirsDeleted: Boolean = false,
        val hunks: GitHunkPlan? = null)

    fun preview(git: Git, entry: GitChange): Preview {
        val file = checkedPath(git, entry.path)
        val editable = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        if (entry.area == GitArea.Conflicts) {
            val index = git.repository.readDirCache()
            val first = index.findEntry(entry.path)
            require(first >= 0 && index.getEntry(first).stage > 0) { uiString(R.string.git_change_path_changed, entry.path) }
            val versions = (first until index.nextEntry(first)).map(index::getEntry).associateBy { it.stage }
            fun side(stage: Int): DirCacheIterator {
                val cache = DirCache.newInCore()
                val builder = cache.builder()
                versions[stage]?.let { version ->
                    builder.add(DirCacheEntry(entry.path).apply {
                        fileMode = version.fileMode
                        setObjectId(version.objectId)
                    })
                }
                builder.finish()
                return DirCacheIterator(cache)
            }
            return Preview(gitFilePatch(git, entry.path, side(2), side(3)), editable, 2 !in versions, 3 !in versions)
        }
        val repo = git.repository
        repo.newObjectReader().use { reader ->
            val old: AbstractTreeIterator = when {
                entry.kind == GitChangeKind.Untracked -> EmptyTreeIterator()
                entry.area == GitArea.Unstaged -> DirCacheIterator(repo.readDirCache())
                else -> repo.resolve("HEAD^{tree}")?.let { CanonicalTreeParser(null, reader, it) } ?: EmptyTreeIterator()
            }
            val new = if (entry.area == GitArea.Staged) DirCacheIterator(repo.readDirCache()) else FileTreeIterator(repo)
            val patch = gitFilePatch(git, entry.path, old, new)
            return Preview(patch, editable, hunks = GitHunkPlan.preview(git, entry, patch))
        }
    }
}

/** Render only this file when selected and bound the retained output even for a large commit. */
internal fun gitFilePatch(git: Git, path: String?, old: AbstractTreeIterator, new: AbstractTreeIterator): String {
    val limit = 128 * 1024
    val bytes = ByteArrayOutputStream()
    val full = IOException()
    val output = object : OutputStream() {
        override fun write(value: Int) {
            if (bytes.size() == limit) throw full
            bytes.write(value)
        }

        override fun write(data: ByteArray, off: Int, len: Int) {
            val count = minOf(limit - bytes.size(), len)
            bytes.write(data, off, count)
            if (count != len) throw full
        }
    }
    try {
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setBinaryFileThreshold(2 * 1024 * 1024)
            if (path != null) formatter.setPathFilter(PathFilter.create(path))
            formatter.format(old, new)
        }
    } catch (e: IOException) {
        if (e !== full) throw e
        return uiString(R.string.git_output_truncated, bytes.toString("UTF-8"))
    }
    return bytes.toString("UTF-8")
}
