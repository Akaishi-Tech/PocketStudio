package com.hcgstudio.pocketstudio

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files

object ApkInstallerChecks {
    @JvmStatic fun run(context: Context) {
        val outputs = Files.createDirectories(File(context.filesDir, "outputs").toPath())
        val built = File.createTempFile("install-test-", ".apk", outputs.toFile())
        val snapshots = mutableListOf<File>()
        val authority = "${context.packageName}.apks"
        try {
            val first = "first build".encodeToByteArray()
            built.writeBytes(first)
            val selected = prepareInstallApk(context, Uri.fromFile(built))
            snapshots += selected
            val replacement = AtomicFile(built)
            val stream = replacement.startWrite()
            try {
                stream.write("second build".encodeToByteArray())
                replacement.finishWrite(stream)
            } catch (e: Exception) {
                replacement.failWrite(stream)
                throw e
            }
            val shared = FileProvider.getUriForFile(context, authority, selected)
            check(shared.scheme == "content")
            check(context.contentResolver.openInputStream(shared)!!.use { it.readBytes() }.contentEquals(first))
            check(runCatching { FileProvider.getUriForFile(context, authority, built) }.isFailure)

            val fromDocument = prepareInstallApk(context, shared)
            snapshots += fromDocument
            check(fromDocument != selected && fromDocument.readBytes().contentEquals(first))
            check(runCatching { prepareInstallApk(context, Uri.fromFile(selected)) }.isFailure)
        } finally {
            snapshots.forEach { it.parentFile!!.deleteRecursively() }
            built.delete()
        }
    }
}
