package com.hcgstudio.pocketstudio

import org.eclipse.jgit.api.CherryPickResult
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.nio.file.Files
import java.nio.file.LinkOption

internal data class GitRefItem(val fullName: String, val id: String) {
    val name: String get() = Repository.shortenRefName(fullName)
    val remote: Boolean get() = fullName.startsWith(Constants.R_REMOTES)
}

internal data class GitCommitItem(val id: String, val subject: String, val author: String, val date: Long, val parents: List<String>)
internal data class GitCommitDetail(val message: String, val identity: String, val parents: List<String>, val patch: String)
internal data class GitStashItem(val index: Int, val id: String, val message: String)
internal data class GitRefList(
    val branches: List<GitRefItem>, val tags: List<GitRefItem>, val stashes: List<GitStashItem>,
    val current: String, val head: String?, val state: RepositoryState
)

internal object GitRefs {
    fun branches(git: Git): List<GitRefItem> =
        git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call().filterNot { it.isSymbolic }
            .map { GitRefItem(it.name, it.objectId.name) }

    fun list(git: Git): GitRefList {
        val repo = git.repository
        return GitRefList(
            branches(git), git.tagList().call().map { ref ->
                val peeled = repo.refDatabase.peel(ref)
                GitRefItem(ref.name, (peeled.peeledObjectId ?: peeled.objectId).name)
            },
            git.stashList().call().mapIndexed { index, commit -> GitStashItem(index, commit.name, commit.shortMessage) },
            repo.fullBranch.orEmpty(), repo.resolve(Constants.HEAD)?.name, repo.repositoryState
        )
    }

    fun history(git: Git, ref: String, limit: Int, path: String? = null): List<GitCommitItem> {
        val repo = git.repository
        val tip = repo.resolve("${ref}^{commit}") ?: return emptyList()
        return RevWalk(repo).use { walk ->
            walk.sort(RevSort.TOPO, true)
            if (path != null) walk.treeFilter = AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF)
            walk.markStart(walk.parseCommit(tip))
            walk.take(limit).map { commit ->
                GitCommitItem(commit.name, commit.shortMessage, commit.authorIdent.name,
                    commit.authorIdent.`when`.time, commit.parents.map { it.name })
            }
        }
    }

    fun detail(git: Git, id: String, parent: Int, path: String? = null): GitCommitDetail = RevWalk(git.repository).use { walk ->
        val commit = walk.parseCommit(ObjectId.fromString(id))
        val parentTree = commit.parents.getOrNull(parent)?.let { walk.parseCommit(it).tree }
        val patch = git.repository.newObjectReader().use { reader ->
            val before = parentTree?.let { CanonicalTreeParser(null, reader, it) } ?: EmptyTreeIterator()
            gitFilePatch(git, path, before, CanonicalTreeParser(null, reader, commit.tree))
        }
        GitCommitDetail(commit.fullMessage.trimEnd(), "${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>",
            commit.parents.map { it.name }, patch)
    }

    fun createBranch(git: Git, name: String, start: String, switch: Boolean) {
        val track = start.startsWith(Constants.R_REMOTES)
        if (switch) {
            val cmd = git.checkout().setCreateBranch(true).setName(name).setStartPoint(start)
            if (track) cmd.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            cmd.call()
        } else {
            val cmd = git.branchCreate().setName(name).setStartPoint(start)
            if (track) cmd.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            cmd.call()
        }
    }

    fun createTag(git: Git, name: String, start: String, message: String) {
        RevWalk(git.repository).use { walk ->
            val target = walk.parseCommit(git.repository.resolve(start))
            val cmd = git.tag().setName(name).setObjectId(target).setAnnotated(message.isNotBlank())
            if (message.isNotBlank()) cmd.setMessage(message)
            cmd.call()
        }
    }

    fun merge(git: Git, name: String) {
        val ref = requireNotNull(git.repository.exactRef(name)) { uiString(R.string.gr_ref_gone) }
        checkMerge(git.merge().include(ref).call())
    }

    private fun checkMerge(result: MergeResult) {
        if (result.mergeStatus == MergeResult.MergeStatus.CONFLICTING) conflict()
        if (!result.mergeStatus.isSuccessful) failed(result.mergeStatus.toString(),
            result.checkoutConflicts.orEmpty() + result.failingPaths.orEmpty().keys)
    }

    fun rebase(git: Git, onto: String) = checkRebase(git.rebase().setUpstream(onto).call())

    private fun checkRebase(result: RebaseResult) {
        when (result.status) {
            RebaseResult.Status.STOPPED, RebaseResult.Status.EDIT -> conflict()
            RebaseResult.Status.NOTHING_TO_COMMIT -> error(uiString(R.string.gr_empty_rebase))
            RebaseResult.Status.ABORTED -> Unit
            else -> if (!result.status.isSuccessful) failed(result.status.toString(),
                result.uncommittedChanges.orEmpty() + result.conflicts.orEmpty() + result.failingPaths.orEmpty().keys)
        }
    }

    fun cherryPick(git: Git, id: String, mainline: Int?) {
        val cmd = git.cherryPick().include(ObjectId.fromString(id))
        if (mainline != null) cmd.setMainlineParentNumber(mainline)
        val result = cmd.call()
        when (result.status) {
            CherryPickResult.CherryPickStatus.OK -> Unit
            CherryPickResult.CherryPickStatus.CONFLICTING -> conflict()
            else -> failed(result.status.toString(), result.failingPaths.orEmpty().keys)
        }
    }

    fun revert(git: Git, id: String) {
        val cmd = git.revert().include(ObjectId.fromString(id))
        if (cmd.call() == null) cmd.failingResult?.let(::checkMerge) ?: conflict()
    }

    private fun conflict(): Nothing = error(uiString(R.string.gr_conflict))
    private fun failed(status: String, paths: Collection<String>): Nothing =
        error(listOf(status, paths.distinct().take(8).joinToString("\n")).filter(String::isNotBlank).joinToString("\n"))

    fun recoveryTitle(state: RepositoryState): Int? = when {
        state.isRebasing -> R.string.gr_rebasing
        state in listOf(RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED) -> R.string.gr_merging
        state in listOf(RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED) -> R.string.gr_picking
        state in listOf(RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED) -> R.string.gr_reverting
        else -> null
    }

    fun recover(git: Git, expected: RepositoryState, op: RebaseCommand.Operation) {
        val repo = git.repository
        val state = repo.repositoryState
        check(recoveryTitle(state) != null && recoveryTitle(state) == recoveryTitle(expected)) { uiString(R.string.gr_state_changed) }
        if (state.isRebasing) {
            checkRebase(git.rebase().setOperation(op).call())
        } else if (op == RebaseCommand.Operation.ABORT) {
            abortOperation(git)
        } else {
            check(op == RebaseCommand.Operation.CONTINUE && state.canCommit()) { uiString(R.string.gr_resolve_first) }
            val cmd = git.commit()
            repo.readMergeCommitMsg()?.let { cmd.setMessage(it) }
            repo.readCherryPickHead()?.let { id ->
                RevWalk(repo).use { walk -> cmd.setAuthor(walk.parseCommit(id).authorIdent) }
            }
            cmd.call()
        }
    }

    // Restore only incoming changes and conflict paths. A whole-tree reset would also
    // overwrite independent staged and unstaged work that survived the merge.
    private fun abortOperation(git: Git) {
        val repo = git.repository
        val head = repo.resolve(Constants.HEAD)
        val status = git.status().call()
        val affected = status.conflicting.toMutableSet()
        val headTree = RevWalk(repo).use { walk ->
            val current = walk.parseCommit(head)
            val mergeHeads = repo.readMergeHeads().orEmpty()
            val sourceIds = mergeHeads.ifEmpty { listOfNotNull(repo.readCherryPickHead(), repo.readRevertHead()) }
            check(sourceIds.isNotEmpty()) { uiString(R.string.gr_state_changed) }
            for (sourceId in sourceIds) {
                val source = walk.parseCommit(sourceId)
                val bases = if (sourceId in mergeHeads) {
                    RevWalk(repo).use { bases ->
                        bases.revFilter = RevFilter.MERGE_BASE
                        bases.markStart(bases.parseCommit(head))
                        bases.markStart(bases.parseCommit(sourceId))
                        bases.map { it.tree }
                    }
                } else source.parents.map { walk.parseCommit(it).tree }
                // With unrelated histories or a root commit, the source is compared to an empty tree.
                for (base in bases.ifEmpty { listOf(null) }) {
                    TreeWalk(repo).use { diff ->
                        if (base == null) diff.addTree(EmptyTreeIterator()) else diff.addTree(base)
                        diff.addTree(source.tree)
                        diff.isRecursive = true
                        diff.filter = TreeFilter.ANY_DIFF
                        while (diff.next()) affected += diff.pathString
                    }
                }
            }
            current.tree
        }
        val staged = status.added + status.changed + status.removed + status.conflicting
        val restore = mutableListOf<String>()
        val remove = mutableListOf<String>()
        val dirty = status.modified + status.missing + status.untracked + status.ignoredNotInIndex
        val index = repo.readDirCache()
        for (path in staged.intersect(affected)) {
            val file = GitChangesStore.checkedPath(git, path)
            // Refuse an obstructing directory, another edit made after staging, or a
            // submodule. A path checkout may otherwise replace private working files.
            check(!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) &&
                dirty.none { it == path || path.startsWith("$it/") || it.startsWith("$path/") } &&
                index.getEntry(path)?.fileMode != FileMode.GITLINK) { uiString(R.string.gr_obstructed, path) }
            TreeWalk.forPath(repo, path, headTree).use { entry ->
                if (entry != null) {
                    check(!entry.isSubtree && entry.getFileMode(0) != FileMode.GITLINK) {
                        uiString(R.string.gr_obstructed, path)
                    }
                    restore += path
                } else remove += path
            }
        }
        if (remove.isNotEmpty()) git.rm().apply { remove.forEach(::addFilepattern) }.call()
        if (restore.isNotEmpty()) git.checkout().setStartPoint(head.name).apply { restore.forEach(::addPath) }.call()
        repo.writeMergeHeads(null)
        repo.writeCherryPickHead(null)
        repo.writeRevertHead(null)
        repo.writeMergeCommitMsg(null)
    }

    fun compare(git: Git, from: String, to: String): String {
        val repo = git.repository
        return repo.newObjectReader().use { reader ->
            val before = requireNotNull(repo.resolve("${from}^{tree}")) { uiString(R.string.gr_ref_gone) }
            val after = requireNotNull(repo.resolve("${to}^{tree}")) { uiString(R.string.gr_ref_gone) }
            gitFilePatch(git, null, CanonicalTreeParser(null, reader, before), CanonicalTreeParser(null, reader, after))
        }
    }

    fun stash(git: Git, message: String, untracked: Boolean) {
        val cmd = git.stashCreate().setIncludeUntracked(untracked)
        // JGit passes stash messages through MessageFormat.
        if (message.isNotBlank()) cmd.setWorkingDirectoryMessage("'" + message.replace("'", "''") + "'")
        check(cmd.call() != null) { uiString(R.string.gr_nothing_stash) }
    }

    fun applyStash(git: Git, stash: GitStashItem, pop: Boolean) {
        checkStash(git, stash)
        git.stashApply().setStashRef(stash.id).call()
        if (pop) dropStash(git, stash)
    }

    fun dropStash(git: Git, stash: GitStashItem) {
        checkStash(git, stash)
        git.stashDrop().setStashRef(stash.index).call()
    }

    private fun checkStash(git: Git, stash: GitStashItem) {
        check(git.stashList().call().elementAtOrNull(stash.index)?.name == stash.id) { uiString(R.string.gr_ref_gone) }
    }
}
