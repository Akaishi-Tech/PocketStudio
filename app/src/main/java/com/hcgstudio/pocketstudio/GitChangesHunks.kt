package com.hcgstudio.pocketstudio

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.CoreConfig
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.Patch
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant

internal data class GitHunk(val patch: String, val edits: EditList)

private data class HunkInputs(val old: ByteArray, val new: ByteArray, val mode: Int, val head: ObjectId?)

internal class GitHunkPlan private constructor(
    private val change: GitChange, private val original: HunkInputs, val hunks: List<GitHunk>
) {
    companion object {
        private const val maxBytes = 128 * 1024

        fun preview(git: Git, change: GitChange, patch: String): GitHunkPlan? {
            if (change.kind != GitChangeKind.Modified || patch.lineSequence().count { it.startsWith("@@ ") } < 2) return null
            val inputs = inputs(git, change, git.repository.readDirCache()) ?: return null
            val bytes = patch.toByteArray(Charsets.UTF_8)
            if (bytes.size >= maxBytes) return null
            val parsed = Patch().apply { parse(bytes, 0, bytes.size) }
            val ranges = parsed.files.singleOrNull()?.hunks ?: return null
            if (parsed.errors.isNotEmpty() || ranges.size < 2) return null

            val old = RawText(inputs.old)
            val new = RawText(inputs.new)
            val output = ByteArrayOutputStream()
            val hunks = DiffFormatter(output).use { formatter ->
                formatter.setRepository(git.repository)
                ranges.map { range ->
                    val edits = range.toEditList()
                    if (edits.isEmpty() || edits.any { it.beginA < 0 || it.endA > old.size() ||
                                it.beginB < 0 || it.endB > new.size() }) return null
                    output.reset()
                    formatter.format(edits, old, new)
                    val shown = String(bytes, range.startOffset, range.endOffset - range.startOffset, Charsets.UTF_8)
                    if (output.toString("UTF-8") != shown) return null
                    GitHunk(shown.trimEnd('\n'), edits)
                }
            }
            val plan = GitHunkPlan(change, inputs, hunks)
            // An edit outside the displayed context between reads also invalidates the preview.
            return plan.takeIf { it.combine(hunks).contentEquals(inputs.new) }
        }

        private fun blob(repo: Repository, id: ObjectId): ByteArray? {
            val source = repo.open(id, Constants.OBJ_BLOB)
            return if (source.size <= maxBytes) source.getCachedBytes(maxBytes) else null
        }

        private fun inputs(git: Git, change: GitChange, cache: DirCache): HunkInputs? {
            val repo = git.repository
            val entry = cache.getEntry(change.path) ?: return null
            if (entry.stage != 0 || entry.isIntentToAdd || entry.isSkipWorkTree || entry.isAssumeValid ||
                (entry.fileMode != FileMode.REGULAR_FILE && entry.fileMode != FileMode.EXECUTABLE_FILE)) return null
            val index = blob(repo, entry.objectId) ?: return null
            val head = repo.resolve(Constants.HEAD)
            val pair = if (change.area == GitArea.Staged) {
                val tree = repo.resolve("HEAD^{tree}") ?: return null
                val committed = TreeWalk.forPath(repo, change.path, tree)?.use {
                    if (it.getRawMode(0) != entry.rawMode) return null
                    blob(repo, it.getObjectId(0))
                } ?: return null
                committed to index
            } else {
                val path = GitChangesStore.checkedPath(git, change.path)
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) return null
                TreeWalk(repo).use { walk ->
                    walk.operationType = TreeWalk.OperationType.CHECKIN_OP
                    val work = FileTreeIterator(repo)
                    work.setDirCacheIterator(walk, walk.addTree(DirCacheIterator(cache)))
                    walk.addTree(work)
                    walk.filter = PathFilter.create(change.path)
                    walk.isRecursive = true
                    if (!walk.next() || walk.pathString != change.path) return null
                    val tracked = walk.getTree(0, DirCacheIterator::class.java) ?: return null
                    val file = walk.getTree(1, FileTreeIterator::class.java) ?: return null
                    if (file.getIndexFileMode(tracked).bits != entry.rawMode || file.cleanFilterCommand != null ||
                        file.eolStreamType != CoreConfig.EolStreamType.DIRECT ||
                        listOf("ident", "working-tree-encoding").any(walk.attributes::containsKey)) return null
                }
                // Bounded read also handles files growing after the size check.
                val work = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
                    val buffer = ByteArray(maxBytes + 1)
                    var size = 0
                    while (size < buffer.size) {
                        val n = stream.read(buffer, size, buffer.size - size)
                        if (n < 0) break
                        size += n
                    }
                    if (size > maxBytes) return null
                    buffer.copyOf(size)
                }
                index to work
            }
            if (RawText.isBinary(pair.first) || RawText.isBinary(pair.second)) return null
            return HunkInputs(pair.first, pair.second, entry.rawMode, head)
        }
    }

    // Splice byte ranges at line boundaries. Both CRLF and a missing final newline remain exact.
    private fun combine(chosen: List<GitHunk>): ByteArray {
        val old = RawText(original.old)
        val new = RawText(original.new)
        fun offset(text: RawText, line: Int) = if (line == text.size()) text.rawContent.size else text.getRawString(line).position()
        val output = ByteArrayOutputStream()
        var cursor = 0
        for (hunk in chosen) for (edit in hunk.edits) {
            val begin = offset(old, edit.beginA)
            output.write(original.old, cursor, begin - cursor)
            val replacement = offset(new, edit.beginB)
            output.write(original.new, replacement, offset(new, edit.endB) - replacement)
            cursor = offset(old, edit.endA)
        }
        output.write(original.old, cursor, original.old.size - cursor)
        return output.toByteArray()
    }

    fun apply(git: Git, selected: Set<Int>) {
        require(selected.isNotEmpty() && selected.all { it in hunks.indices })
        val repo = git.repository
        val cache = repo.lockDirCache()
        try {
            val now = inputs(git, change, cache)
            check(now != null && now.mode == original.mode && now.head == original.head &&
                now.old.contentEquals(original.old) && now.new.contentEquals(original.new)) {
                uiString(R.string.git_change_path_changed, change.path)
            }
            val keep = hunks.filterIndexed { index, _ -> (index in selected) == (change.area == GitArea.Unstaged) }
            val blob = repo.newObjectInserter().use { inserter ->
                inserter.insert(Constants.OBJ_BLOB, combine(keep)).also { inserter.flush() }
            }
            val editor = cache.editor()
            editor.add(object : DirCacheEditor.PathEdit(change.path) {
                override fun apply(entry: DirCacheEntry) {
                    entry.setObjectId(blob)
                    entry.setLastModified(Instant.EPOCH)
                    entry.setLength(0)
                }
            })
            check(editor.commit()) { uiString(R.string.git_change_path_changed, change.path) }
        } finally {
            cache.unlock()
        }
    }
}
