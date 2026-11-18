package com.hcgstudio.pocketstudio

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.JschSession
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS

/** Per-operation SSH client. Only the identity and host keys selected in the form are loaded. */
internal class GitRemoteSsh private constructor(private val client: JSch) : SshSessionFactory(), AutoCloseable {
    companion object {
        fun load(key: ByteArray, knownHosts: ByteArray, passphrase: ByteArray): GitRemoteSsh {
            val client = JSch()
            try {
                client.setKnownHosts(knownHosts.inputStream())
                client.addIdentity("Pocket Studio", key, null, passphrase.takeIf { it.isNotEmpty() })
                return GitRemoteSsh(client)
            } catch (e: Exception) {
                client.removeAllIdentity()
                throw e
            } finally {
                key.fill(0)
                passphrase.fill(0)
            }
        }

        fun matches(uri: URIish): Boolean = uri.host != null && (uri.scheme == "ssh" || uri.scheme == null)
    }

    override fun getType(): String = "SSH"
    override fun getSession(uri: URIish, credentialsProvider: CredentialsProvider?, fs: FS, tms: Int): RemoteSession {
        val session = client.getSession(uri.user ?: "git", uri.host, uri.port.takeIf { it > 0 } ?: 22)
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.timeout = tms
            session.connect(tms)
            return JschSession(session, uri)
        } catch (e: JSchException) {
            session.disconnect()
            throw TransportException(uri, e.localizedMessage ?: "SSH", e)
        }
    }

    override fun close() = client.removeAllIdentity()
}
