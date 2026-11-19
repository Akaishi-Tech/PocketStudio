package com.hcgstudio.pocketstudio.codexplugin

import android.content.Context
import java.io.File
import java.nio.file.Files

internal class CodexRuntime(private val app: Context, private val plugin: Context) {
    private val LIBRARY = "libcodex_app_server.so"

    fun requireInstalled() = check(File(plugin.applicationInfo.nativeLibraryDir, LIBRARY).canExecute()) {
        uiString(R.string.android_could_not_extract_a_codex_runtime_for)
    }

    fun launch(workspace: File, environment: Map<String, String> = emptyMap(), arguments: List<String> = emptyList()): Process {
        val libraryDir = File(plugin.applicationInfo.nativeLibraryDir)
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

}
