package com.hcgstudio.pocketstudio

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files

internal data class GitRemoteRow(val name: String, val urls: List<String>, val pushUrls: List<String>, val tips: Map<String, String>)
internal data class GitRemoteList(val remotes: List<GitRemoteRow>, val branch: String?, val trackingRemote: String?, val trackingBranch: String?)
internal enum class GitTransfer(val label: Int) {
    Fetch(R.string.git_remote_fetch), Pull(R.string.git_remote_pull), Push(R.string.git_remote_push),
    ForcePush(R.string.git_remote_force_lease)
}

internal object GitRemoteOperations {
    private fun display(uri: URIish): String =
        uri.setPass(null).setUser(uri.user.takeIf { GitRemoteSsh.matches(uri) }).toString().substringBefore('?').substringBefore('#')

    private fun endpoint(raw: String): URIish {
        val uri = try { URIish(raw.trim()) } catch (_: Exception) {
            throw IllegalArgumentException(uiString(R.string.git_remote_invalid_url))
        }
        val local = (uri.scheme == "file" || uri.scheme == null) && uri.host == null && File(uri.path.orEmpty()).isAbsolute
        val loopback = uri.scheme == "http" && uri.host == "127.0.0.1"
        val ssh = GitRemoteSsh.matches(uri)
        require(local || ssh || loopback || (uri.scheme == "https" && !uri.host.isNullOrBlank())) { uiString(R.string.git_remote_invalid_url) }
        require((ssh || uri.user == null) && uri.pass == null && (local || (!raw.contains('?') && !raw.contains('#')))) {
            uiString(R.string.git_remote_url_credentials)
        }
        return uri
    }

    fun list(git: Git): GitRemoteList {
        val repo = git.repository
        val branch = repo.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }?.removePrefix(Constants.R_HEADS)
        val config = branch?.let { BranchConfig(repo.config, it) }
        val names = repo.remoteNames
        val remotes = git.remoteList().call().map { remote ->
            val prefix = Constants.R_REMOTES + remote.name + "/"
            val tips = repo.refDatabase.getRefsByPrefix(prefix).filter { ref ->
                !ref.isSymbolic && ownerOfRef(ref.name, names) == remote.name
            }.associate { it.name.removePrefix(prefix) to it.objectId.name }.toSortedMap()
            GitRemoteRow(remote.name, remote.urIs.map(::display), remote.pushURIs.map(::display), tips)
        }
        return GitRemoteList(remotes.sortedBy { it.name }, branch, config?.remote, config?.merge?.removePrefix(Constants.R_HEADS))
    }

    private fun ownerOfRef(ref: String, remotes: Set<String>): String? =
        remotes.filter { ref.startsWith(Constants.R_REMOTES + it + "/") }.maxByOrNull { it.length }

    fun save(git: Git, name: String, urls: List<String>, pushUrls: List<String>, previous: GitRemoteRow?) {
        require(Repository.isValidRefName(Constants.R_REMOTES + name + "/branch") && !name.startsWith('-') && name != ".") {
            uiString(R.string.git_remote_invalid_name)
        }
        require(urls.isNotEmpty()) { uiString(R.string.git_remote_invalid_url) }
        val config = git.repository.config
        if (previous == null) {
            require(name !in git.repository.remoteNames) { uiString(R.string.git_remote_name_exists, name) }
        } else {
            require(name in git.repository.remoteNames) { uiString(R.string.git_remote_missing) }
            val current = RemoteConfig(config, name)
            require(current.urIs.map(::display) == previous.urls && current.pushURIs.map(::display) == previous.pushUrls) {
                uiString(R.string.git_remote_url_changed)
            }
        }
        val fetch = urls.map { endpoint(it).toString() }
        val push = pushUrls.map { endpoint(it).toString() }
        if (previous == null)
            config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
        if (previous?.urls != urls) config.setStringList("remote", name, "url", fetch)
        if (previous?.pushUrls != pushUrls) config.setStringList("remote", name, "pushurl", push)
        config.save()
    }

    fun remove(git: Git, name: String) {
        val repo = git.repository
        val names = repo.remoteNames
        require(name in names) { uiString(R.string.git_remote_missing) }
        val refs = repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES + name + "/")
        for (ref in refs.filter { ownerOfRef(it.name, names) == name }.sortedByDescending { it.isSymbolic }) {
            val update = repo.updateRef(ref.name, true)
            update.isForceUpdate = true
            val result = update.delete()
            check(result in setOf(RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE, RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD)) {
                uiString(R.string.git_remote_ref_error, ref.name, result.name)
            }
        }
        val config = repo.config
        for (branch in config.getSubsections("branch")) {
            if (config.getString("branch", branch, "remote") == name) {
                config.unset("branch", branch, "remote")
                config.unset("branch", branch, "merge")
            }
            if (config.getString("branch", branch, "pushRemote") == name)
                config.unset("branch", branch, "pushRemote")
        }
        if (config.getString("remote", null, "pushDefault") == name)
            config.unset("remote", null, "pushDefault")
        config.unsetSection("remote", name)
        config.save()
    }

    fun track(git: Git, localBranch: String, remote: String, branch: String?) {
        val repo = git.repository
        require(repo.fullBranch == Constants.R_HEADS + localBranch) { uiString(R.string.git_remote_branch_changed) }
        val config = repo.config
        if (branch == null) {
            config.unset("branch", localBranch, "remote")
            config.unset("branch", localBranch, "merge")
        } else {
            require(remote in repo.remoteNames) { uiString(R.string.git_remote_missing) }
            require(Repository.isValidRefName(Constants.R_HEADS + branch)) { uiString(R.string.git_remote_invalid_branch) }
            config.setString("branch", localBranch, "remote", remote)
            config.setString("branch", localBranch, "merge", Constants.R_HEADS + branch)
        }
        config.save()
    }

    fun transfer(git: Git, action: GitTransfer, selected: GitRemoteRow, localBranch: String?, branch: String,
                 trackBranch: Boolean, option: Boolean, username: String, password: String, ssh: GitRemoteSsh? = null) {
        val repo = git.repository
        require(selected.name in repo.remoteNames) { uiString(R.string.git_remote_missing) }
        val remote = RemoteConfig(repo.config, selected.name)
        val push = action == GitTransfer.Push || action == GitTransfer.ForcePush
        val urls = if (push && remote.pushURIs.isNotEmpty()) remote.pushURIs else remote.urIs
        val shown = if (push && selected.pushUrls.isNotEmpty()) selected.pushUrls else selected.urls
        val targets = if (push) urls else urls.take(1)
        require(urls.map(::display) == shown && targets.isNotEmpty()) { uiString(R.string.git_remote_url_changed) }
        val endpoints = targets.map { endpoint(it.toPrivateString()) }
        endpoints.forEach { checkHttpSettings(it, repo.config) }

        if (action != GitTransfer.Fetch) {
            require(localBranch != null && repo.fullBranch == Constants.R_HEADS + localBranch) { uiString(R.string.git_remote_branch_changed) }
            require(Repository.isValidRefName(Constants.R_HEADS + branch)) { uiString(R.string.git_remote_invalid_branch) }
        }

        when (action) {
            GitTransfer.Fetch -> {
                val result = git.fetch().setRemote(selected.name).setRemoveDeletedRefs(option)
                    .setProgressMonitor(interruptMonitor()).callWithCredentials(endpoints, username, password, ssh)
                val rejected = result.trackingRefUpdates.firstOrNull { it.result !in successfulFetch }
                if (rejected != null) throw IOException(uiString(R.string.git_remote_ref_error, rejected.localName, rejected.result))
            }
            GitTransfer.Pull -> {
                val pull = git.pull().setRemote(selected.name).setRemoteBranchName(branch)
                    .setRebase(option).setProgressMonitor(interruptMonitor())
                    .callWithCredentials(endpoints, username, password, ssh)
                if (!pull.isSuccessful) {
                    val status = pull.mergeResult?.mergeStatus?.toString() ?: pull.rebaseResult?.status?.toString().orEmpty()
                    throw IOException(uiString(R.string.git_remote_pull_incomplete, status))
                }
            }
            GitTransfer.Push, GitTransfer.ForcePush -> {
                val destination = Constants.R_HEADS + branch
                val force = action == GitTransfer.ForcePush
                val refspec = RefSpec(Constants.R_HEADS + localBranch + ":" + destination).setForceUpdate(force)
                val cmd = git.push().setRemote(selected.name).setRefSpecs(refspec).setProgressMonitor(interruptMonitor())
                if (force) {
                    val expected = selected.tips[branch]
                    val tracking = repo.exactRef(Constants.R_REMOTES + selected.name + "/" + branch)
                    require(expected != null && tracking?.objectId?.name == expected) { uiString(R.string.git_remote_lease_changed) }
                    cmd.setRefLeaseSpecs(RefLeaseSpec(destination, expected))
                } else if (option) cmd.setPushTags()
                val results = cmd.callWithCredentials(endpoints, username, password, ssh)
                for (result in results) for (update in result.remoteUpdates) {
                    if (update.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED)
                        throw IOException(uiString(R.string.git_remote_lease_changed))
                    if (update.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD)
                        throw IOException(uiString(R.string.git_remote_nonfastforward, branch))
                    if (update.status != RemoteRefUpdate.Status.OK && update.status != RemoteRefUpdate.Status.UP_TO_DATE)
                        throw IOException(uiString(R.string.git_remote_ref_error, update.remoteName, update.message ?: update.status.name))
                }
            }
        }
        if (action != GitTransfer.Fetch && trackBranch) track(git, requireNotNull(localBranch), selected.name, branch)
    }

    private val successfulFetch = setOf(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE, RefUpdate.Result.FORCED)

    fun clone(imports: File, url: String, username: String, password: String, ssh: GitRemoteSsh? = null): File {
        val remote = endpoint(url)
        checkHttpSettings(remote)
        val name = runCatching { remote.humanishName }.getOrNull().orEmpty()
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim(' ', '.').take(60).ifEmpty { "Project" }
        val container = Files.createTempDirectory(Files.createDirectories(imports.toPath()), "clone-")
        var complete = false
        try {
            val worktree = container.resolve(name).toFile()
            Git.cloneRepository().setURI(remote.toString()).setDirectory(worktree).setFs(GitFilesystem())
                .setProgressMonitor(interruptMonitor()).callWithCredentials(listOf(remote), username, password, ssh).close()
            complete = true
            return worktree
        } finally {
            if (!complete) Project.removeTree(container)
        }
    }

    private fun checkHttpSettings(uri: URIish, config: Config? = null) {
        if (uri.scheme != "https" && uri.scheme != "http") return
        val http = if (config == null) HttpConfig(uri) else HttpConfig(config, uri)
        require(uri.scheme != "https" || http.isSslVerify) { uiString(R.string.git_tls_verification) }
        // Imported configuration cannot select an Android file to use as a cookie store.
        require(http.cookieFile.isNullOrEmpty()) { uiString(R.string.git_remote_cookie_file) }
    }

    private fun interruptMonitor(): ProgressMonitor {
        val worker = Thread.currentThread()
        return object : ProgressMonitor {
            override fun start(totalTasks: Int) = Unit
            override fun beginTask(title: String, totalWork: Int) = Unit
            override fun update(completed: Int) = Unit
            override fun endTask() = Unit
            override fun isCancelled() = worker.isInterrupted
        }
    }

    private fun <T> TransportCommand<*, T>.callWithCredentials(
        targets: List<URIish>, username: String, password: String, ssh: GitRemoteSsh?
    ): T {
        setTimeout(60)
        require(targets.none(GitRemoteSsh::matches) || ssh != null) { uiString(R.string.git_remote_ssh_files) }
        setTransportConfigCallback { transport ->
            require(transport.uri in targets) { uiString(R.string.git_remote_url_changed) }
            // Local transports use in-process JGit for the standard pack services.
            transport.optionUploadPack = "git-upload-pack"
            transport.optionReceivePack = "git-receive-pack"
            if (transport is SshTransport) {
                transport.sshSessionFactory = requireNotNull(ssh) { uiString(R.string.git_remote_ssh_files) }
            }
        }
        if (username.isEmpty() && password.isEmpty()) return call()
        require(targets.all { it.scheme == "https" }) { uiString(R.string.git_remote_auth_https) }
        // A redirect or credential challenge on another host must never receive this form's token.
        val credentials = object : UsernamePasswordCredentialsProvider(username, password) {
            override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
                val matches = targets.any {
                    uri.scheme == "https" && it.host.equals(uri.host, ignoreCase = true) &&
                        (if (it.port == -1) 443 else it.port) == (if (uri.port == -1) 443 else uri.port)
                }
                return matches && super.get(uri, *items)
            }
        }
        try {
            setCredentialsProvider(credentials)
            return call()
        } finally {
            credentials.clear()
        }
    }
}
