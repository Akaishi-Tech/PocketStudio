package com.hcgstudio.pocketstudio

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

internal data class GitBlameLine(val text: String, val commit: String, val author: String, val path: String)
internal data class GitFileBlame(val lines: List<GitBlameLine> = emptyList(), val emptyReason: Int? = null)

/** Read HEAD explicitly so staged or local edits never shift the displayed line numbers. */
internal fun gitFileBlame(git: Git, path: String): GitFileBlame {
    val repo = git.repository
    val tip = repo.resolve(Constants.HEAD) ?: return GitFileBlame(emptyReason = R.string.git_file_no_committed_version)
    RevWalk(repo).use { walk ->
        val tree = walk.parseCommit(tip).tree
        val file = TreeWalk.forPath(repo, path, tree) ?: return GitFileBlame(emptyReason = R.string.git_file_no_committed_version)
        file.use {
            if (it.getFileMode(0).objectType != Constants.OBJ_BLOB) return GitFileBlame(emptyReason = R.string.git_file_no_text)
            val blob = repo.open(it.getObjectId(0))
            val limit = 512 * 1024
            if (blob.size > limit) return GitFileBlame(emptyReason = R.string.git_file_large)
            val bytes = blob.getCachedBytes(limit)
            if (RawText.isBinary(bytes)) return GitFileBlame(emptyReason = R.string.git_file_no_text)
            val text = RawText(bytes)
            if (text.size() == 0) return GitFileBlame(emptyReason = R.string.git_file_empty)
            if (text.size() > 8000 || (0 until text.size()).any { line -> text.getRawString(line).remaining() > 4096 })
                return GitFileBlame(emptyReason = R.string.git_file_large)
        }
    }
    val blame = git.blame().setStartCommit(tip).setFilePath(path).setFollowFileRenames(true).call()
        ?: return GitFileBlame(emptyReason = R.string.git_file_no_committed_version)
    val text = blame.resultContents
    return GitFileBlame(List(text.size()) { i ->
        GitBlameLine(text.getString(i), blame.getSourceCommit(i).name, blame.getSourceAuthor(i).name, blame.getSourcePath(i))
    })
}
