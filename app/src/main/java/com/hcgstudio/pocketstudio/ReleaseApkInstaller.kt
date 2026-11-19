package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal fun releaseVerificationError() = IOException(uiString(R.string.github_apk_verification_failed))

@Suppress("DEPRECATION")
internal fun packageVersionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

@Suppress("DEPRECATION")
internal fun PackageManager.signedPackageInfo(name: String): PackageInfo? {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    return try { getPackageInfo(name, flags) } catch (_: PackageManager.NameNotFoundException) { null }
}

@Suppress("DEPRECATION")
internal fun PackageInfo.hasReleaseSigner(signer: ByteArray): Boolean {
    val signers = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return signers?.singleOrNull()?.let { MessageDigest.isEqual(it.toByteArray(), signer) } == true
}

internal fun releaseFileDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal class ReleaseApkInstaller(private val app: Context, private val packageName: String, directoryName: String) {
    private val directory = File(app.cacheDir, "install-apks/$directoryName")
    // The release workflow pins the Studio APK and plugin store packages to this certificate.
    val trustedSigner by lazy { app.resources.openRawResource(R.raw.release_signer).use { it.readBytes() } }

    fun staged(release: AppRelease) = File(directory, "${release.sha256}.apk")

    fun prepare(release: AppRelease, source: GitHubApkReleases, progress: (Long, Long) -> Unit): Intent {
        val apk = prepareFile(release, progress) { destination -> source.download(release, destination, progress) }
        return installerIntent(apk)
    }

    fun prepareFile(release: AppRelease, progress: (Long, Long) -> Unit, download: (File) -> Unit): File {
        val target = staged(release)
        Files.createDirectories(directory.toPath())
        val cutoff = System.currentTimeMillis() - 86_400_000L
        directory.listFiles()?.filter { it != target && it.name.endsWith(".apk") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        val cached = target.isFile && try {
            verifyArchive(target, release)
            true
        } catch (e: InterruptedIOException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (cached) {
            progress(release.size, release.size)
        } else {
            val temp = Files.createTempFile(directory.toPath(), ".download-", ".apk").toFile()
            try {
                download(temp)
                verifyArchive(temp, release)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        return target
    }

    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.apks", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("DEPRECATION")
    fun verifyArchive(apk: File, release: AppRelease) {
        if (apk.length() != release.size || releaseFileDigest(apk) != release.sha256) throw releaseVerificationError()
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()
        val signer = result.signerCertificates.singleOrNull()
        if (!result.isVerified || !result.isVerifiedUsingV2Scheme || signer == null ||
            !MessageDigest.isEqual(signer.encoded, trustedSigner)) throw releaseVerificationError()
        val info = app.packageManager.getPackageArchiveInfo(apk.path, 0) ?: throw releaseVerificationError()
        if (info.packageName != packageName || info.versionName != release.version || packageVersionCode(info) != release.versionCode)
            throw releaseVerificationError()
    }
}
