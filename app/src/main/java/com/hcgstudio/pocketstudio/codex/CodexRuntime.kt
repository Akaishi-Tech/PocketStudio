package com.hcgstudio.pocketstudio.codex

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.hcgstudio.pocketstudio.ReleaseApkInstaller
import com.hcgstudio.pocketstudio.packageVersionCode
import com.hcgstudio.pocketstudio.R
import com.hcgstudio.pocketstudio.build.DeviceApkSigner
import com.hcgstudio.pocketstudio.uiString
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** All package, network and file operations here run on the caller's IO thread. */
internal class CodexRuntime(context: Context) {
    private val app = context.applicationContext
    private val releases = CodexReleases(app)
    private val installer = ReleaseApkInstaller(app, PACKAGE, "codex-runtime")
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
            if (MessageDigest.isEqual(signer, installer.trustedSigner)) return Installed(info, false)
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
        releases.cached()?.takeIf { installer.staged(it).isFile } ?: throw failure
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

    fun prepareInstaller(release: CodexRelease, progress: (Long, Long) -> Unit = { _, _ -> }): Intent {
        val current = installedPackage()
        if (current != null && !current.legacy && release.versionCode < packageVersionCode(current.info))
            throw IOException(uiString(R.string.a_newer_version_of_codex_is_already_installed))
        return installer.prepare(release, releases.source, progress)
    }

    internal fun verifyArchive(apk: File, release: CodexRelease) = installer.verifyArchive(apk, release)

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
    }
}
