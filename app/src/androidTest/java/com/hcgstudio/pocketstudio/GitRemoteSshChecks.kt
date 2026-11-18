package com.hcgstudio.pocketstudio

import android.content.Context
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

/** Uses a disposable SSH Git server and its test keys supplied by the host. */
object GitRemoteSshChecks {
    @JvmStatic fun run(context: Context, url: String) {
        val fixture = File(context.cacheDir, "git-ssh-test")
        fun identity(hosts: String): GitRemoteSsh = GitRemoteSsh.load(
            File(fixture, "identity").readBytes(), File(fixture, hosts).readBytes(),
            File(fixture, "passphrase").takeIf { it.isFile }?.readBytes() ?: byteArrayOf()
        )
        val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "git-ssh-check-")
        try {
            val imports = scratch.resolve("imports").toFile()
            val rejected = runCatching {
                identity("wrong_hosts").use { GitRemoteOperations.clone(imports, url, "", "", it) }
            }.exceptionOrNull()
            check(rejected != null && imports.listFiles().orEmpty().isEmpty()) { "Mismatched host key was accepted" }
            val checkout = identity("known_hosts").use { GitRemoteOperations.clone(imports, url, "", "", it) }
            Git.open(checkout).use { git ->
                val initial = GitRemoteOperations.list(git)
                val local = requireNotNull(initial.branch)
                val remote = initial.remotes.single()
                identity("known_hosts").use {
                    GitRemoteOperations.transfer(git, GitTransfer.Pull, remote, local, local, false, false, "", "", it)
                }
                val id = System.nanoTime().toString()
                File(checkout, "device-check").writeText(id)
                git.add().addFilepattern("device-check").call()
                val commit = git.commit().setAuthor("Studio", "studio@example.test")
                    .setCommitter("Studio", "studio@example.test").setMessage("Android SSH check").call()
                val target = "device-check-$id"
                identity("known_hosts").use {
                    GitRemoteOperations.transfer(git, GitTransfer.Push, remote, local, target, true, false, "", "", it)
                }
                identity("known_hosts").use {
                    GitRemoteOperations.transfer(git, GitTransfer.Fetch, remote, local, target, false, false, "", "", it)
                }
                val updated = GitRemoteOperations.list(git)
                check(updated.trackingBranch == target && updated.remotes.single().tips[target] == commit.name)
            }
        } finally {
            Project.removeTree(scratch)
        }
    }
}
