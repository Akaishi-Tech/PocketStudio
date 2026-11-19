package com.hcgstudio.pocketstudio.androidplugin

import android.content.res.AssetManager
import android.text.TextUtils
import com.hcgstudio.pocketstudio.plugin.ProjectFileWriter
import com.hcgstudio.pocketstudio.plugin.ProjectStarter
import java.io.IOException

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

internal enum class ProjectTemplate(val starter: String) {
    EMPTY_VIEWS("empty-views"),
    BUTTON("button"),
    NO_ACTIVITY("no-activity");

    fun packageNameError(name: String, language: ProjectLanguage): String? {
        javaPackageError(name)?.let { return it }
        if (this != NO_ACTIVITY && language == ProjectLanguage.KOTLIN && name.startsWith("kotlin."))
            return PluginStrings.uiString(R.string.wizard_reserved_kotlin_package)
        return null
    }

    fun entryPath(packageName: String, language: ProjectLanguage): String =
        if (this == NO_ACTIVITY) MANIFEST else "$MAIN/java/${packageName.replace('.', '/')}/${language.activityFile}"

    companion object {
        const val MIN_SDK = 24
        const val DEFAULT_MIN_SDK = 26
        private const val MAIN = "app/src/main"
        private const val MANIFEST = "$MAIN/AndroidManifest.xml"
    }
}

private val javaKeywords = ("abstract assert boolean break byte case catch char class const continue default do double else " +
    "enum extends final finally float for goto if implements import instanceof int interface long " +
    "native new package private protected public return short static strictfp super switch " +
    "synchronized this throw throws transient try void volatile while true false null").split(' ').toSet()

internal fun javaPackageError(name: String): String? = when {
    !name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) ->
        PluginStrings.uiString(R.string.use_at_least_two_package_segments_start_each)
    name.length > 255 -> PluginStrings.uiString(R.string.package_name_is_too_long)
    name.split('.').any { it in javaKeywords } -> PluginStrings.uiString(R.string.package_names_cannot_contain_java_keywords)
    else -> null
}

internal fun androidProjectTemplate(assets: AssetManager, starter: String, name: String, packageName: String,
                                   languageName: String, minSdk: Int, compileSdk: String): ProjectStarter {
    val template = ProjectTemplate.entries.single { it.starter == starter }
    val language = ProjectLanguage.valueOf(languageName)
    template.packageNameError(packageName, language)?.let { throw IOException(it) }
    if (!compileSdk.matches(Regex("[1-9][0-9]{0,2}(?:\\.[0-9]{1,2})?")) ||
        minSdk !in ProjectTemplate.MIN_SDK..compileSdk.substringBefore('.').toInt())
        throw IOException(PluginStrings.uiString(R.string.invalid_sdk_platform_number))
    return object : ProjectStarter {
        override val entryPath = template.entryPath(packageName, language)
        override fun writeFiles(writer: ProjectFileWriter) {
            writeAndroidTemplate(assets, starter, name, packageName, language.sourcePackage(packageName),
                language.activityFile, minSdk, compileSdk, writer)
        }
    }
}

private fun writeAndroidTemplate(assets: AssetManager, starter: String, name: String, packageName: String,
                                 sourcePackage: String, activityFile: String, minSdk: Int, compileSdk: String,
                                 writer: ProjectFileWriter) {
    fun copy(path: String, asset: String = "template/$path", render: ((String) -> String)? = null) {
        val original = assets.open(asset).use { it.readBytes() }
        writer.write(path, if (render == null) original else render(original.decodeToString()).encodeToByteArray())
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
    val main = "app/src/main"
    copy("$main/res/values/strings.xml") { it.replace("__APP_NAME__", TextUtils.htmlEncode(name)) }

    val manifest = "$main/AndroidManifest.xml"
    val starterDir = "project-starters/$starter"
    if (starter == "no-activity") {
        copy(manifest, "$starterDir/AndroidManifest.xml")
    } else {
        copy(manifest, "project-starters/AndroidManifest.xml")
        copy("$main/res/layout/main.xml", "$starterDir/main.xml")
        copy("$main/java/${packageName.replace('.', '/')}/$activityFile", "$starterDir/$activityFile") {
            it.replace("__PACKAGE__", sourcePackage)
        }
    }
}
