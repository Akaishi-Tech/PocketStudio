package com.hcgstudio.pocketstudio

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import java.io.File

/** Git pages operate on documents. Imported repository configuration must not launch Android processes. */
internal class GitFilesystem : FS_POSIX() {
    override fun newInstance(): FS = GitFilesystem()
    override fun supportsSymlinks() = false
    override fun findHook(repository: Repository, hookName: String): File? = null

    override fun runInShell(command: String, args: Array<out String>): ProcessBuilder =
        throw UnsupportedOperationException(uiString(R.string.git_external_command))
}
