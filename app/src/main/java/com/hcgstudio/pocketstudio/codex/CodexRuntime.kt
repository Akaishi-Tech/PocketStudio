package com.hcgstudio.pocketstudio.codex

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import com.hcgstudio.pocketstudio.R
import com.hcgstudio.pocketstudio.build.DeviceApkSigner
import com.hcgstudio.pocketstudio.uiString
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** All package, network and file operations here run on the caller's IO thread. */
internal class CodexRuntime(context: Context) {
    private val app = context.applicationContext
    private val releases = CodexReleases(app)
    private val trustedSigner by lazy { app.resources.openRawResource(R.raw.codex_plugin_signer).use { it.readBytes() } }
    private data class Installed(val info: PackageInfo, val legacy: Boolean)
    @Volatile private var installed: Installed? = null
    val legacyInstalled: Boolean get() = installed?.legacy == true
    val installedVersionCode: Long get() = installed?.let { packageVersionCode(it.info) } ?: 0L

    @Suppress("DEPRECATION")
    private fun installedPackage(): Installed? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = try { app.packageManager.getPackageInfo(PACKAGE, flags) }
        catch (_: PackageManager.NameNotFoundException) { return null }
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signer = signatures?.singleOrNull()?.toByteArray()
        if (signer != null) {
            if (MessageDigest.isEqual(signer, trustedSigner)) return Installed(info, false)
            // Only existing releases that this installation itself signed qualify for legacy execution.
            val oldKey = DeviceApkSigner.certificate(LEGACY_SIGNER)
            if (oldKey != null && MessageDigest.isEqual(signer, oldKey)) return Installed(info, true)
        }
        throw IOException(uiString(R.string.the_installed_codex_runtime_s_signature_does_not))
    }

    fun installedVersion(): String? {
        val current = installedPackage()
        installed = current
        val info = current?.info ?: return null
        if (!File(requireNotNull(info.applicationInfo).nativeLibraryDir, LIBRARY).canExecute())
            throw IOException(uiString(R.string.android_could_not_extract_a_codex_runtime_for))
        if (!current.legacy) return info.versionName ?: throw CodexReleases.verificationError()
        return ZipFile(requireNotNull(info.applicationInfo).sourceDir).use { apk ->
            val entry = apk.getEntry(VERSION_FILE) ?: throw IOException(uiString(R.string.the_codex_runtime_is_missing_version_information))
            if (entry.size !in 1..64) throw IOException(uiString(R.string.the_codex_runtime_s_version_information_is_invalid))
            apk.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    fun latestRelease(): CodexRelease {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" || it == "x86_64" })
            throw IOException(uiString(R.string.codex_runtimes_are_available_for_arm64_and_x86))
        return releases.latest()
    }

    fun releaseForInstall(): CodexRelease = try { latestRelease() } catch (failure: IOException) {
        // A cancelled Android installer can be reopened offline using the previously verified APK.
        releases.cached()?.takeIf { staged(it).isFile } ?: throw failure
    }

    /** Read the original notices from the installed, trusted companion on an IO thread. */
    fun installedNotices(): Map<String, String> {
        val info = installedPackage()?.info
        val apkPath = info?.applicationInfo?.sourceDir ?: return emptyMap()
        return ZipFile(apkPath).use { apk ->
            buildMap {
                for (name in NOTICE_FILES + "openssl/NOTICE.txt") {
                    val entry = apk.getEntry("assets/licenses/$name") ?: continue
                    if (entry.size !in 1..MAX_NOTICE_BYTES) throw IOException(uiString(R.string.invalid_runtime_package_contents))
                    DataInputStream(apk.getInputStream(entry)).use { input ->
                        val bytes = ByteArray(entry.size.toInt())
                        input.readFully(bytes)
                        if (input.read() != -1) throw IOException(uiString(R.string.invalid_runtime_package_contents))
                        put(name, bytes.toString(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    private fun staged(release: CodexRelease) =
        File(app.cacheDir, "install-apks/codex-runtime/${release.sha256}.apk")

    fun prepareInstaller(release: CodexRelease, progress: (Long, Long) -> Unit = { _, _ -> }): Intent {
        val current = installedPackage()
        if (current != null && !current.legacy && release.versionCode < packageVersionCode(current.info))
            throw IOException(uiString(R.string.a_newer_version_of_codex_is_already_installed))
        val target = staged(release)
        val directory = Files.createDirectories(requireNotNull(target.parentFile).toPath())
        if (target.exists() && runCatching { verifyArchive(target, release) }.isSuccess) {
            progress(release.size, release.size)
            return installer(target)
        }
        val temp = Files.createTempFile(directory, ".codex-", ".apk").toFile()
        try {
            releases.download(release, temp, progress)
            verifyArchive(temp, release)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        val cutoff = System.currentTimeMillis() - 86_400_000L
        directory.toFile().listFiles()?.filter { it != target && it.name.endsWith(".apk") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        return installer(target)
    }

    @Suppress("DEPRECATION")
    internal fun verifyArchive(apk: File, release: CodexRelease) {
        if (apk.length() != release.size || CodexReleases.digest(apk) != release.sha256) throw CodexReleases.verificationError()
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()
        val signer = result.signerCertificates.singleOrNull()
        if (!result.isVerified || !result.isVerifiedUsingV2Scheme || signer == null ||
            !MessageDigest.isEqual(signer.encoded, trustedSigner)) throw CodexReleases.verificationError()
        val info = app.packageManager.getPackageArchiveInfo(apk.path, 0) ?: throw CodexReleases.verificationError()
        if (info.packageName != PACKAGE || info.versionName != release.version || packageVersionCode(info) != release.versionCode)
            throw CodexReleases.verificationError()
    }

    private fun installer(apk: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.apks", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun launch(workspace: File, environment: Map<String, String> = emptyMap(), arguments: List<String> = emptyList()): Process {
        val info = installedPackage()?.info ?: throw IOException(uiString(R.string.install_the_codex_runtime_first))
        val libraryDir = File(requireNotNull(info.applicationInfo).nativeLibraryDir)
        val binary = File(libraryDir, LIBRARY)
        check(File(libraryDir, "libssl.so.3").isFile && File(libraryDir, "codex-code-mode-host").canExecute()) {
            uiString(R.string.the_android_codex_runtime_installation_is_incomplete_update)
        }
        val home = Files.createDirectories(File(app.noBackupFilesDir, "codex-home").toPath()).toFile()
        val temp = Files.createDirectories(File(app.cacheDir, "codex-tmp").toPath()).toFile()
        val builder = ProcessBuilder(listOf(binary.path, "app-server", "--listen", "stdio://") + arguments).directory(workspace)
        builder.environment().apply {
            put("HOME", app.filesDir.path)
            put("CODEX_HOME", home.path)
            put("TMPDIR", temp.path)
            put("SHELL", "/system/bin/sh")
            put("LD_LIBRARY_PATH", libraryDir.path)
            putAll(environment)
        }
        val roots = CodexTrustBundle.prepare(temp)
        builder.environment()["CODEX_CA_CERTIFICATE"] = roots.path
        builder.environment()["SSL_CERT_FILE"] = roots.path
        return builder.start()
    }

    companion object {
        const val PACKAGE = "com.hcgstudio.pocketstudio.codexruntime"
        private const val LEGACY_SIGNER = "pocketstudio-codex-runtime-v1"
        private const val LIBRARY = "libcodex_app_server.so"
        private const val VERSION_FILE = "assets/codex-version"
        private const val MAX_NOTICE_BYTES = 1_000_000L
        private val NOTICE_FILES = listOf("codex/LICENSE", "codex/NOTICE", "codex/license.md", "openssl/copyright")

        @Suppress("DEPRECATION")
        private fun packageVersionCode(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}
