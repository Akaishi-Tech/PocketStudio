package com.hcgstudio.pocketstudio.plugin

import java.io.File
import java.util.function.Consumer

/** A task invocation bound to the current host workspace and its file access grants. */
interface GradleTask {
    @Throws(Exception::class)
    fun run(tasks: String, log: Consumer<String>): List<File>
    fun cancel()
}
