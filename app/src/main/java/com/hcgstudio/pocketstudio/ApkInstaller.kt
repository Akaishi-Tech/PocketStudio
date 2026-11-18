package com.hcgstudio.pocketstudio

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ApkProvider : FileProvider()

internal fun prepareInstallApk(context: Context, source: Uri): File {
    val cache = Files.createDirectories(File(context.cacheDir, "install-apks").toPath())
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
    cache.toFile().listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    val directory = Files.createTempDirectory(cache, "install-").toFile()
    val apk = File(directory, "app.apk")
    try {
        when (source.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                val input = context.contentResolver.openInputStream(source) ?: throw IOException(context.getString(R.string.unable_to_read_the_selected_apk))
                input.use { Files.copy(it, apk.toPath()) }
            }
            ContentResolver.SCHEME_FILE -> {
                val file = File(requireNotNull(source.path)).canonicalFile
                val outputs = File(context.filesDir, "outputs").canonicalFile.toPath()
                val imports = File(context.filesDir, "imports").canonicalFile.toPath()
                require(file.isFile && file.extension.equals("apk", ignoreCase = true)) { context.getString(R.string.apk_file_not_found) }
                if (file.toPath().startsWith(outputs)) {
                    // Workspace replaces build outputs atomically. Keep this exact version for the installer.
                    try {
                        Files.createLink(apk.toPath(), file.toPath())
                        return apk
                    } catch (_: IOException) { /* The cache may live on a different filesystem. */ }
                    catch (_: UnsupportedOperationException) { /* Hard links are optional. */ }
                } else require(file.toPath().startsWith(imports)) { context.getString(R.string.select_an_apk_inside_the_project) }
                Files.copy(file.toPath(), apk.toPath())
            }
            else -> throw IOException(context.getString(R.string.unable_to_open_this_apk))
        }
        return apk
    } catch (e: Exception) {
        directory.deleteRecursively()
        throw e
    }
}

@Composable
internal fun rememberOpenApk(onError: (Throwable) -> Unit): (Uri) -> Unit {
    val context = LocalContext.current
    val cacheCleared = stringResource(R.string.the_apk_cache_was_cleared_open_the_apk)
    val installerMissing = stringResource(R.string.system_apk_installer_not_found)
    val permissionDenied = stringResource(R.string.allow_pocket_studio_to_install_unknown_apps_then)
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<File?>(null) }
    fun releaseSnapshot() {
        val directory = pending?.parentFile ?: return
        pending = null
        scope.launch(Dispatchers.IO) { directory.deleteRecursively() }
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        releaseSnapshot()
    }
    fun openInstaller() {
        val apk = pending ?: return
        try {
            if (!apk.isFile) throw IOException(cacheCleared)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.apks", apk)
            installer.launch(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            releaseSnapshot()
            onError(IllegalStateException(installerMissing, e))
        } catch (e: Exception) {
            releaseSnapshot()
            onError(e)
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pending != null) {
            if (context.packageManager.canRequestPackageInstalls()) openInstaller()
            else {
                releaseSnapshot()
                onError(IllegalStateException(permissionDenied))
            }
        }
    }
    return { source ->
        if (!preparing && pending == null) {
            preparing = true
            scope.launch {
                try {
                    pending = runInterruptible(Dispatchers.IO) { prepareInstallApk(context, source) }
                    if (context.packageManager.canRequestPackageInstalls()) openInstaller()
                    else {
                        settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.fromParts("package", context.packageName, null)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    releaseSnapshot()
                    onError(e)
                } finally {
                    preparing = false
                }
            }
        }
    }
}
