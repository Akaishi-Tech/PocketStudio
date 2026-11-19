package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.hcgstudio.pocketstudio.build.EmbeddedBuilder
import com.hcgstudio.pocketstudio.build.SdkPlatforms
import com.hcgstudio.pocketstudio.build.KotlinTools
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** Run with a writable SAF tree already granted to the IDE and SDK Platform 34 installed. */
object ProjectCreationChecks {
    @JvmStatic fun installedCompileSdk(context: Context) {
        val sdk = SdkPlatforms(context).installed().firstOrNull { it.substringBefore('.').toInt() > 34 } ?: return
        val parent = File(context.filesDir, "imports/sdk-check-${UUID.randomUUID()}")
        check(parent.mkdirs())
        try {
            val project = Project.createProject(context, Uri.fromFile(parent), "SDK Project", "org.example.sdk",
                ProjectTemplate.EMPTY_VIEWS, ProjectLanguage.JAVA, 35, sdk)
            ProjectWorkspace.open(project).use { workspace ->
                val apk = EmbeddedBuilder.build(context, workspace.directory.toFile()) {}
                val info = checkNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0)?.applicationInfo)
                check(info.minSdkVersion == 35 && info.targetSdkVersion == sdk.substringBefore('.').toInt())
            }
        } finally { parent.deleteRecursively() }
    }

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
            check(original.contains("package $packageName;") && original.contains("setOnClickListener"))

            ProjectWorkspace.open(reopened).use { workspace ->
                checkApk(context, workspace.directory.toFile(), name, packageName, 26, hasActivity = true)
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
            for (sdk in listOf(23, 35)) {
                val invalidSdk = runCatching {
                    Project.createProject(context, parent.tree, "$name invalid", packageName, ProjectTemplate.EMPTY_VIEWS, ProjectLanguage.JAVA, sdk, "34")
                }
                check(invalidSdk.isFailure && parent.list(parent.root).map { it.name } == siblings)
            }
            for (template in listOf(ProjectTemplate.EMPTY_VIEWS, ProjectTemplate.BUTTON)) {
                val reservedPackage = runCatching {
                    Project.createProject(context, parent.tree, "$name invalid", "kotlin.example", template, ProjectLanguage.KOTLIN, 26)
                }
                check(reservedPackage.isFailure && parent.list(parent.root).map { it.name } == siblings)
            }
        } finally {
            parent.delete(parent.resolve(name))
        }
        checkTemplate(context, parent, ProjectTemplate.EMPTY_VIEWS, ProjectLanguage.JAVA, 24, "kotlin.example")
        checkTemplate(context, parent, ProjectTemplate.EMPTY_VIEWS, ProjectLanguage.KOTLIN, 34, "org.example.when")
        checkTemplate(context, parent, ProjectTemplate.BUTTON, ProjectLanguage.KOTLIN, 26)
        checkTemplate(context, parent, ProjectTemplate.NO_ACTIVITY, ProjectLanguage.KOTLIN, 34, "kotlin.example")
    }

    private fun checkTemplate(
        context: Context, parent: Project, template: ProjectTemplate, language: ProjectLanguage,
        minSdk: Int, packageName: String = "org.example.wizard",
    ) {
        val name = "Wizard-" + UUID.randomUUID()
        val project = Project.createProject(context, parent.tree, name, packageName, template, language, minSdk)
        try {
            ProjectWorkspace.open(Project(context, project.tree)).use { workspace ->
                val root = workspace.directory.toFile()
                val module = root.resolve("app")
                val main = module.resolve("src/main")
                val sources = main.walkTopDown().filter { it.extension in setOf("java", "kt") }.toList()
                val layouts = main.resolve("res/layout").listFiles().orEmpty()
                val hasActivity = template != ProjectTemplate.NO_ACTIVITY
                val manifest = main.resolve("AndroidManifest.xml").readText()
                check(manifest.contains("android.intent.category.LAUNCHER") == hasActivity)
                check(Regex("""\bminSdk\s*(?:=\s*)?$minSdk\b""").containsMatchIn(module.resolve("build.gradle").readText()))
                if (hasActivity) {
                    val source = sources.single()
                    val extension = if (language == ProjectLanguage.KOTLIN) "kt" else "java"
                    check(source.path.endsWith("/${packageName.replace('.', '/')}/MainActivity.$extension"))
                    val declaration = source.readLines().first { it.startsWith("package ") }
                    check(declaration.removePrefix("package ").removeSuffix(";").replace("`", "") == packageName)
                    if (packageName.endsWith(".when")) check("`when`" in declaration)
                    check(layouts.isNotEmpty())
                    check(layouts.any { it.readText().contains("<Button") } == (template == ProjectTemplate.BUTTON))
                } else {
                    check(sources.isEmpty() && layouts.isEmpty())
                }
                if (!hasActivity || language == ProjectLanguage.JAVA || KotlinTools(context).installed()) {
                    checkApk(context, root, name, packageName, minSdk, hasActivity)
                }
            }
        } finally {
            parent.delete(parent.resolve(name))
        }
    }

    private fun checkApk(context: Context, root: File, name: String, packageName: String, minSdk: Int, hasActivity: Boolean) {
        val apk = EmbeddedBuilder.build(context, root) {}
        val pm = context.packageManager
        val info = checkNotNull(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_ACTIVITIES or PackageManager.GET_SIGNATURES))
        check(info.packageName == packageName && info.signatures?.size == 1)
        val activities = info.activities.orEmpty()
        if (hasActivity) {
            check(activities.any { it.name == "$packageName.MainActivity" && it.exported })
        } else {
            check(activities.isEmpty())
        }
        val app = checkNotNull(info.applicationInfo)
        check(app.minSdkVersion == minSdk)
        app.sourceDir = apk.path
        app.publicSourceDir = apk.path
        check(app.loadLabel(pm).toString() == name)
    }
}
