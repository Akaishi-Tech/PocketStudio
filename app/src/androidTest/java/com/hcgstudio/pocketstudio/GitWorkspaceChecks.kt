package com.hcgstudio.pocketstudio

import android.content.Context
import android.net.Uri
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Exercises Git mutations across workspace writeback, repository reopening and conflict recovery. */
object GitWorkspaceChecks {
    @JvmStatic fun run(context: Context, tree: String?) {
        GitHunkChecks.run(context.cacheDir)
        if (tree != null) {
            val parent = Project(context, Uri.parse(tree))
            val child = parent.create(parent.root, "Git-Check-${UUID.randomUUID()}", true)
            try { exercise(Project(context, child.uri)) }
            finally { parent.delete(child) }
        } else {
            val imports = Files.createDirectories(File(context.filesDir, "imports").toPath())
            val temp = Files.createTempDirectory(imports, "git-check-")
            try { exercise(Project(context, Uri.fromFile(temp.toFile()))) }
            finally { Project.removeTree(temp) }
        }
    }

    private fun exercise(project: Project) {
        val featureTip = ProjectWorkspace.open(project).use { work ->
            Git.init().setInitialBranch("main").setDirectory(work.directory.toFile()).call().use { git ->
                fun put(path: String, text: String) = Files.write(work.directory.resolve(path), text.encodeToByteArray())
                fun stage(path: String) {
                    val unstaged = GitChangesStore.load(git).groups.getValue(GitArea.Unstaged)
                    GitChangesStore.stage(git, listOf(unstaged.single { it.path == path }))
                }
                fun commit(message: String) = GitChangesStore.commit(git, git.repository.resolve("HEAD")?.name,
                    message, "Test Author", "author@example.test", false)

                put("notes.txt", "base\n")
                stage("notes.txt")
                GitChangesStore.unstage(git, GitChangesStore.load(git).groups.getValue(GitArea.Staged))
                check(git.status().call().untracked == setOf("notes.txt"))
                stage("notes.txt")
                put("local.txt", "original\n")
                stage("local.txt")
                commit("Base")
                GitRefs.createBranch(git, "feature", "HEAD", switch = true)
                put("notes.txt", "feature\n")
                stage("notes.txt")
                put("new.txt", "from feature\n")
                stage("new.txt")
                commit("Feature")
                val feature = git.repository.resolve("HEAD").name
                git.checkout().setName("refs/heads/main").call()
                check(work.directory.resolve("notes.txt").toFile().readText() == "base\n")
                put("notes.txt", "main\n")
                stage("notes.txt")
                commit("Main")
                check(runCatching { GitRefs.merge(git, "refs/heads/feature") }.isFailure)
                check(git.repository.repositoryState == RepositoryState.MERGING)
                put("extra.txt", "separate staged change")
                stage("extra.txt")
                put("local.txt", "separate working change")
                GitRefs.recover(git, git.repository.repositoryState, RebaseCommand.Operation.ABORT)
                check(git.repository.repositoryState == RepositoryState.SAFE)
                check(!Files.exists(work.directory.resolve("new.txt")))
                feature
            }
        }

        check(project.read(project.resolve("notes.txt")) == "main\n")
        check(project.read(project.resolve(".git/HEAD")).trim() == "ref: refs/heads/main")
        check(project.read(project.resolve("local.txt")) == "separate working change")

        ProjectWorkspace.open(project).use { work ->
            Git.open(work.directory.toFile()).use { git ->
                val refs = GitRefs.list(git)
                check(refs.current == "refs/heads/main" && refs.branches.single { it.name == "feature" }.id == featureTip)
                val status = git.status().call()
                check(status.added == setOf("extra.txt") && status.modified == setOf("local.txt") && status.conflicting.isEmpty())
                check(GitRefs.detail(git, featureTip, 0).patch.contains("+feature"))
                val patch = GitRefs.detail(git, featureTip, 0, "notes.txt").patch
                check("+feature" in patch && "new.txt" !in patch)
                git.checkout().setName("refs/heads/feature").call()
                check(GitRefs.history(git, "HEAD", 10, "new.txt").map { it.id } == listOf(featureTip))
                val line = gitFileBlame(git, "notes.txt").lines.single()
                check(line.commit == featureTip && line.author == "Test Author" && line.text == "feature")
                check(gitFileBlame(git, "local.txt").lines.single().text == "original")
            }
        }
        check(project.read(project.resolve("notes.txt")) == "feature\n")
        check(project.read(project.resolve("new.txt")) == "from feature\n")
        check(project.read(project.resolve("extra.txt")) == "separate staged change")

        // A merge splits into two lanes. Both join again at their common root.
        fun row(id: String, vararg parents: String) = GitCommitItem(id, id, "", 0, parents.toList())
        val graph = gitGraph(listOf(row("merge", "left", "right"), row("left", "base"),
            row("right", "base"), row("base")))
        check(graph.first().incoming.isEmpty() && graph.first().outgoing.map { it.to }.toSet() == setOf(0, 1))
        check(graph[1].passing.single().from == 1 && graph[2].passing.single().from == 0)
        check(graph.last().incoming.map { it.from }.toSet() == setOf(0, 1) && graph.last().outgoing.isEmpty())
    }
}
