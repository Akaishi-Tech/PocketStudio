package com.hcgstudio.pocketstudio

import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

internal object GitHunkChecks {
    fun run(parent: File) {
        val dir = Files.createTempDirectory(parent.toPath(), "git-hunks-").toFile()
        try {
            Git.init().setDirectory(dir).call().use { git ->
                val repo = git.repository
                repo.config.setBoolean("core", null, "autocrlf", false)
                repo.config.save()
                val file = File(dir, "example.txt")
                fun contents(first: String = "2", middle: String = "15", last: String = "32") =
                    (1..32).joinToString("\r\n") { when (it) {
                        2 -> first
                        15 -> middle
                        32 -> last
                        else -> it.toString()
                    } }
                fun preview(area: GitArea) =
                    GitChangesStore.preview(git, GitChange(file.name, area, GitChangeKind.Modified)).hunks
                fun indexText() = repo.open(repo.readDirCache().getEntry(file.name).objectId).bytes.decodeToString()

                file.writeText(contents())
                file.setExecutable(true)
                git.add().addFilepattern(file.name).call()
                git.commit().setAuthor("Tester", "test@example.test").setCommitter("Tester", "test@example.test")
                    .setMessage("Base").call()
                val mode = repo.readDirCache().getEntry(file.name).fileMode
                val modified = contents("first", "middle", "last")
                file.writeText(modified)
                val forward = checkNotNull(preview(GitArea.Unstaged))
                check(forward.hunks.size == 3)
                forward.apply(git, setOf(0, 2))
                check(indexText() == contents("first", last = "last"))
                check(file.readText() == modified && repo.readDirCache().getEntry(file.name).fileMode == mode)
                check(runCatching { forward.apply(git, setOf(1)) }.isFailure)

                val backward = checkNotNull(preview(GitArea.Staged))
                check(backward.hunks.size == 2)
                backward.apply(git, setOf(1))
                check(indexText() == contents("first") && file.readText() == modified)
                val stale = checkNotNull(preview(GitArea.Unstaged))
                file.writeText(contents("first", "external change", "last"))
                check(runCatching { stale.apply(git, setOf(0)) }.isFailure && indexText() == contents("first"))

                File(dir, ".gitattributes").writeText("*.txt text eol=lf\n")
                check(preview(GitArea.Unstaged) == null)
            }
        } finally {
            Project.removeTree(dir.toPath())
        }
    }
}
