package com.hcgstudio.pocketstudio

import android.text.TextUtils

internal enum class ProjectLanguage(val activityFile: String) {
    JAVA("MainActivity.java"),
    KOTLIN("MainActivity.kt");

    fun sourcePackage(name: String): String {
        if (this == JAVA) return name
        return name.split('.').joinToString(".") { if (it in kotlinKeywords) "`$it`" else it }
    }

    companion object {
        // Hard Kotlin keywords that remain legal in a Java package name.
        private val kotlinKeywords = setOf("as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when")
    }
}

internal enum class ProjectTemplate(private val starter: String) {
    EMPTY_VIEWS("empty-views"),
    BUTTON("button"),
    NO_ACTIVITY("no-activity");

    fun packageNameError(name: String, language: ProjectLanguage): String? {
        Project.packageNameError(name)?.let { return it }
        if (this != NO_ACTIVITY && language == ProjectLanguage.KOTLIN && name.startsWith("kotlin."))
            return uiString(R.string.wizard_reserved_kotlin_package)
        return null
    }

    fun entryPath(packageName: String, language: ProjectLanguage): String =
        if (this == NO_ACTIVITY) MANIFEST else "$MAIN/java/${packageName.replace('.', '/')}/${language.activityFile}"

    fun writeFiles(project: Project, name: String, packageName: String, language: ProjectLanguage, minSdk: Int, compileSdk: String) {
        val directories = mutableMapOf("" to project.root)
        fun directory(path: String): Project.Node = directories.getOrPut(path) {
            project.createExact(directory(path.substringBeforeLast('/', "")), path.substringAfterLast('/'), true)
        }
        fun copy(path: String, asset: String = "template/$path", render: ((String) -> String)? = null) {
            val original = project.context.assets.open(asset).use { it.readBytes() }
            val content = if (render == null) original else render(original.decodeToString()).encodeToByteArray()
            val file = project.createExact(directory(path.substringBeforeLast('/', "")), path.substringAfterLast('/'), false)
            project.write(file, content)
        }

        for (path in listOf("settings.gradle", "build.gradle", "gradlew", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar")) {
            copy(path)
        }
        val major = compileSdk.substringBefore('.')
        val minor = compileSdk.substringAfter('.', "0").toInt()
        val compile = if (minor == 0) "compileSdk $major"
            else "compileSdk { version = release($major) { minorApiLevel = $minor } }"
        copy("app/build.gradle") {
            it.replace("__PACKAGE__", packageName).replace("__MIN_SDK__", minSdk.toString())
                .replace("__COMPILE_SDK__", compile).replace("__TARGET_SDK__", major)
        }
        copy("$MAIN/res/values/strings.xml") { it.replace("__APP_NAME__", TextUtils.htmlEncode(name)) }

        val starterDir = "project-starters/$starter"
        if (this == NO_ACTIVITY) {
            copy(MANIFEST, "$starterDir/AndroidManifest.xml")
        } else {
            copy(MANIFEST, "project-starters/AndroidManifest.xml")
            copy("$MAIN/res/layout/main.xml", "$starterDir/main.xml")
            copy(entryPath(packageName, language), "$starterDir/${language.activityFile}") {
                it.replace("__PACKAGE__", language.sourcePackage(packageName))
            }
        }
    }

    companion object {
        const val MIN_SDK = 24
        const val DEFAULT_MIN_SDK = 26
        private const val MAIN = "app/src/main"
        private const val MANIFEST = "$MAIN/AndroidManifest.xml"
    }
}
