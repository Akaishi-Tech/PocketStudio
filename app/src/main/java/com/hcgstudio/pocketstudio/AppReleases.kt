package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException

internal data class AppRelease(val version: String, val versionCode: Long, val size: Long, val sha256: String, val assetName: String? = null)

internal class AppReleases(context: Context) : AppUpdateSource {
    private val app = context.applicationContext
    private val releases = GitHubApkReleases(app)
    private val installer = ReleaseApkInstaller(app, app.packageName, "pocket-studio")

    override fun latest(): AppRelease? = releases.latest()

    internal fun parse(body: String): AppRelease = releases.parse(body)

    override fun prepareInstaller(release: AppRelease, progress: (Long, Long) -> Unit): Intent {
        val installed = app.packageManager.signedPackageInfo(app.packageName) ?: throw releaseVerificationError()
        if (release.versionCode <= packageVersionCode(installed)) throw IOException(uiString(R.string.app_release_already_installed))
        if (!installed.hasReleaseSigner(installer.trustedSigner))
            throw IOException(uiString(R.string.app_release_incompatible_signer))
        return installer.prepare(release, releases, progress)
    }

    internal fun verifyArchive(apk: File, release: AppRelease) = installer.verifyArchive(apk, release)
}
