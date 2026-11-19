package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

internal fun createAndroidTestProject(
    context: Context, parent: Uri, name: String, packageName: String, template: String = "button",
    language: String = "JAVA", minSdk: Int = 26, compileSdk: String? = null
): Project {
    val environment = AndroidEnvironments.require(context)
    val sdk = compileSdk ?: environment.platforms().installed().firstOrNull() ?: android.os.Build.VERSION.SDK_INT.toString()
    return Project.createProject(context, parent, name, environment.projectTemplate(template, name, packageName, language, minSdk, sdk))
}

/** Run with a writable SAF tree already granted to the IDE and SDK Platform 34 installed. */
object ProjectCreationChecks {
    @JvmStatic fun installedCompileSdk(context: Context) {
        val sdk = AndroidEnvironments.require(context).platforms().installed().firstOrNull { it.substringBefore('.').toInt() > 34 } ?: return
        val parent = File(context.filesDir, "imports/sdk-check-${UUID.randomUUID()}")
        check(parent.mkdirs())
        try {
            val project = createAndroidTestProject(context, Uri.fromFile(parent), "SDK Project", "org.example.sdk",
                "empty-views", "JAVA", 35, sdk)
            ProjectWorkspace.open(project).use { workspace ->
                val apk = AndroidEnvironments.require(context).build(workspace.directory.toFile(), null) {}
                val info = checkNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0)?.applicationInfo)
                check(info.minSdkVersion == 35 && info.targetSdkVersion == sdk.substringBefore('.').toInt())
            }
        } finally { parent.deleteRecursively() }
    }

    @JvmStatic fun run(context: Context, tree: String) {
        val parent = Project(context, Uri.parse(tree))
        val name = "测试 & Studio " + UUID.randomUUID()
        val packageName = "org.example.projectcheck"
        val project = createAndroidTestProject(context, parent.tree, name, packageName)
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
                AndroidEnvironments.require(context).build(work.directory.toFile(), null) {}
            }
            ZipInputStream(reopened.input(reopened.resolve("app/build/outputs/apk/debug/app-debug.apk"))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != "assets/workspace-check.txt") entry = zip.nextEntry
                check(entry != null && zip.readBytes().decodeToString() == name) { "重新构建未使用当前项目中的资产" }
            }
            check(reopened.read(activity) == edited) { "重新构建覆盖了编辑器所作的修改" }
            val siblings = parent.list(parent.root).map { it.name }
            val duplicate = runCatching { createAndroidTestProject(context, parent.tree, name, packageName) }
            check(duplicate.exceptionOrNull() is IOException && reopened.read(activity) == edited)
            val invalid = runCatching { createAndroidTestProject(context, parent.tree, "$name invalid", "bad.class") }
            check(invalid.isFailure && parent.list(parent.root).map { it.name } == siblings)
            for (sdk in listOf(23, 35)) {
                val invalidSdk = runCatching {
                    createAndroidTestProject(context, parent.tree, "$name invalid", packageName, "empty-views", "JAVA", sdk, "34")
                }
                check(invalidSdk.isFailure && parent.list(parent.root).map { it.name } == siblings)
            }
            for (template in listOf("empty-views", "button")) {
                val reservedPackage = runCatching {
                    createAndroidTestProject(context, parent.tree, "$name invalid", "kotlin.example", template, "KOTLIN", 26)
                }
                check(reservedPackage.isFailure && parent.list(parent.root).map { it.name } == siblings)
            }
        } finally {
            parent.delete(parent.resolve(name))
        }
        checkTemplate(context, parent, "empty-views", "JAVA", 24, "kotlin.example")
        checkTemplate(context, parent, "empty-views", "KOTLIN", 34, "org.example.when")
        checkTemplate(context, parent, "button", "KOTLIN", 26)
        checkTemplate(context, parent, "no-activity", "KOTLIN", 34, "kotlin.example")
    }

    private fun checkTemplate(
        context: Context, parent: Project, template: String, language: String,
        minSdk: Int, packageName: String = "org.example.wizard",
    ) {
        val name = "Wizard-" + UUID.randomUUID()
        val project = createAndroidTestProject(context, parent.tree, name, packageName, template, language, minSdk)
        try {
            ProjectWorkspace.open(Project(context, project.tree)).use { workspace ->
                val root = workspace.directory.toFile()
                val module = root.resolve("app")
                val main = module.resolve("src/main")
                val sources = main.walkTopDown().filter { it.extension in setOf("java", "kt") }.toList()
                val layouts = main.resolve("res/layout").listFiles().orEmpty()
                val hasActivity = template != "no-activity"
                val manifest = main.resolve("AndroidManifest.xml").readText()
                check(manifest.contains("android.intent.category.LAUNCHER") == hasActivity)
                check(Regex("""\bminSdk\s*(?:=\s*)?$minSdk\b""").containsMatchIn(module.resolve("build.gradle").readText()))
                if (hasActivity) {
                    val source = sources.single()
                    val extension = if (language == "KOTLIN") "kt" else "java"
                    check(source.path.endsWith("/${packageName.replace('.', '/')}/MainActivity.$extension"))
                    val declaration = source.readLines().first { it.startsWith("package ") }
                    check(declaration.removePrefix("package ").removeSuffix(";").replace("`", "") == packageName)
                    if (packageName.endsWith(".when")) check("`when`" in declaration)
                    check(layouts.isNotEmpty())
                    check(layouts.any { it.readText().contains("<Button") } == (template == "button"))
                } else {
                    check(sources.isEmpty() && layouts.isEmpty())
                }
                if (!hasActivity || language == "JAVA" || AndroidEnvironments.require(context).kotlinTools().installed()) {
                    checkApk(context, root, name, packageName, minSdk, hasActivity)
                }
            }
        } finally {
            parent.delete(parent.resolve(name))
        }
    }

    private fun checkApk(context: Context, root: File, name: String, packageName: String, minSdk: Int, hasActivity: Boolean) {
        val apk = AndroidEnvironments.require(context).build(root, null) {}
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
