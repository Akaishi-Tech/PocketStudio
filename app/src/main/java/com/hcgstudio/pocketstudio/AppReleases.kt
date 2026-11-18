package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class AppRelease(val version: String, val versionCode: Long, val size: Long, val sha256: String)

internal class AppReleases(context: Context) : AppUpdateSource {
    private val app = context.applicationContext
    private val releases = GitHubApkReleases(app, "app-github-release", "latest", "PocketStudio.apk",
        Regex("""Pocket Studio ((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)) \(([0-9]+)\)""")) { "v$it" }
    private val installer = ReleaseApkInstaller(app, app.packageName, "pocket-studio")

    override fun latest(): AppRelease? = releases.latest()

    internal fun parse(body: String): AppRelease = releases.parse(body)

    @Suppress("DEPRECATION")
    override fun prepareInstaller(release: AppRelease, progress: (Long, Long) -> Unit): Intent {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        if (release.versionCode <= packageVersionCode(installed)) throw IOException(uiString(R.string.app_release_already_installed))
        val signatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val signer = signatures?.singleOrNull()?.toByteArray()
        if (signer == null || !MessageDigest.isEqual(signer, installer.trustedSigner))
            throw IOException(uiString(R.string.app_release_incompatible_signer))
        return installer.prepare(release, releases, progress)
    }

    internal fun verifyArchive(apk: File, release: AppRelease) = installer.verifyArchive(apk, release)
}
