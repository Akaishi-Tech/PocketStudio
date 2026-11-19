package com.hcgstudio.pocketstudio.plugin

import android.net.Uri
import java.io.IOException

interface ProjectWizardHost {
    val busy: Boolean
    var creationError: String?
    fun projectNameError(name: String): String?
    fun createProject(parent: Uri, name: String, starter: ProjectStarter)
}

interface ProjectStarter {
    val entryPath: String
    @Throws(IOException::class)
    fun writeFiles(writer: ProjectFileWriter)
}

fun interface ProjectFileWriter {
    @Throws(IOException::class)
    fun write(relativePath: String, content: ByteArray)
}
