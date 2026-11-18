package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.hcgstudio.pocketstudio.build.EmbeddedBuilder
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** Run with a writable SAF tree already granted to the IDE and SDK Platform 34 installed. */
object ProjectCreationChecks {
    @JvmStatic fun run(context: Context, tree: String) {
        val parent = Project(context, Uri.parse(tree))
        val name = "测试 & Studio " + UUID.randomUUID()
        val packageName = "org.example.projectcheck"
        val project = Project.createProject(context, parent.tree, name, packageName)
        try {
            val reopened = Project(context, project.tree)
            check(reopened.root.name == name)
            check(reopened.root.uri == parent.resolve(name).uri)
            val activity = reopened.resolve("app/src/main/java/org/example/projectcheck/MainActivity.java")
            val original = reopened.read(activity)
            check(original.contains("package $packageName;"))

            ProjectWorkspace.open(reopened).use { workspace ->
                val apk = EmbeddedBuilder.build(context, workspace.directory.toFile()) {}
                val pm = context.packageManager
                val info = checkNotNull(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_ACTIVITIES or PackageManager.GET_SIGNATURES))
                check(info.packageName == packageName && info.signatures?.size == 1)
                check(info.activities.orEmpty().any { it.name == "$packageName.MainActivity" && it.exported })
                val app = checkNotNull(info.applicationInfo)
                app.sourceDir = apk.path
                app.publicSourceDir = apk.path
                check(app.loadLabel(pm).toString() == name)
            }
            reopened.input(reopened.resolve("app/build/outputs/apk/debug/app-debug.apk")).use { apk ->
                check(apk.read() == 0x50 && apk.read() == 0x4b) { "项目目录中缺少生成的 APK" }
            }

            val edited = "$original\n// Preserve this edit."
            reopened.write(activity, edited.encodeToByteArray())
            val assetDir = reopened.create(reopened.resolve("app/src/main"), "assets", true)
            val asset = reopened.create(assetDir, "workspace-check.txt", false)
            reopened.write(asset, name.encodeToByteArray())
            ProjectWorkspace.open(reopened).use { work ->
                EmbeddedBuilder.build(context, work.directory.toFile()) {}
            }
            ZipInputStream(reopened.input(reopened.resolve("app/build/outputs/apk/debug/app-debug.apk"))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != "assets/workspace-check.txt") entry = zip.nextEntry
                check(entry != null && zip.readBytes().decodeToString() == name) { "重新构建未使用当前项目中的资产" }
            }
            check(reopened.read(activity) == edited) { "重新构建覆盖了编辑器所作的修改" }
            val siblings = parent.list(parent.root).map { it.name }
            val duplicate = runCatching { Project.createProject(context, parent.tree, name, packageName) }
            check(duplicate.exceptionOrNull() is IOException && reopened.read(activity) == edited)
            val invalid = runCatching { Project.createProject(context, parent.tree, "$name invalid", "bad.class") }
            check(invalid.isFailure && parent.list(parent.root).map { it.name } == siblings)
        } finally {
            parent.delete(parent.resolve(name))
        }
    }
}
