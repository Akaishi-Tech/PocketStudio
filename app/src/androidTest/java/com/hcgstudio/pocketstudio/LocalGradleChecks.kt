package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors

object LocalGradleChecks {
    @JvmStatic fun run(context: Context, androidTemplate: Boolean, tree: String?, tasks: String) {
        if (androidTemplate) prepareAndroidSdk(context)
        if (tree != null) {
            val parent = Project(context, Uri.parse(tree))
            val project = createAndroidTestProject(context, parent.tree, "Gradle Check ${UUID.randomUUID()}", "org.example.gradlecheck")
            try {
                if (androidTemplate) checkTemplate(context, project, tasks) else checkSelectedFolder(project)
            } finally { parent.delete(parent.resolve(project.root.name)) }
            return
        }
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val parent = Files.createTempDirectory(imports.toPath(), "gradle-check-").toFile()
        try {
            val project = createAndroidTestProject(context, Uri.fromFile(parent), "Gradle Check", "org.example.gradlecheck")
            if (androidTemplate) checkTemplate(context, project, tasks) else exerciseTasks(project, parent)
        } finally {
            Project.removeTree(parent.toPath())
        }
    }

    private fun checkSelectedFolder(project: Project) {
        project.write(project.resolve("settings.gradle"), "rootProject.name = 'SelectedFolder'".encodeToByteArray())
        val script = """
            tasks.register('verifyWorkspace') {
                doLast {
                    def report = file('build/reports/check.txt')
                    report.parentFile.mkdirs()
                    def value = file('input.txt').text
                    report.text = value
                    file('generated.txt').text = value
                    file('obsolete.txt').delete()
                }
            }
        """.trimIndent()
        project.write(project.resolve("build.gradle"), script.encodeToByteArray())
        val input = project.create(project.root, "input.txt", false)
        project.create(project.root, "obsolete.txt", false)
        for (value in listOf("first", "second")) {
            project.write(input, value.encodeToByteArray())
            LocalGradle(project).run("verifyWorkspace", ::log)
            check(project.read(project.resolve("build/reports/check.txt")) == value)
            check(project.read(project.resolve("generated.txt")) == value)
        }
        check(runCatching { project.resolve("obsolete.txt") }.exceptionOrNull() is java.io.FileNotFoundException)
    }

    private fun prepareAndroidSdk(context: Context) {
        val platforms = AndroidEnvironments.require(context).platforms()
        val platformZip = File(context.cacheDir, "platform-34-test.zip")
        if ("34" !in platforms.installed() && platformZip.isFile) {
            platformZip.inputStream().use { check(platforms.importArchive(it) == "34") }
        }
        val tools = AndroidEnvironments.require(context).buildTools()
        val toolsZip = File(context.cacheDir, "build-tools-test.zip")
        if (!tools.installed() && toolsZip.isFile) toolsZip.inputStream().use { tools.install(it, ::log) }
    }

    private fun checkTemplate(context: Context, project: Project, tasks: String) {
        val targets = tasks.split(' ').map { it.substringAfterLast(':') }
        val unitTests = listOf("testDebugUnitTest", "testReleaseUnitTest").filter {
            it in targets || (it == "testDebugUnitTest" && "test" in targets)
        }
        val marks = if (unitTests.isNotEmpty()) Files.createTempDirectory(context.cacheDir.toPath(), "gradle-junit-").toFile() else null
        val token = UUID.randomUUID().toString()
        try {
            if (marks != null) addUnitTest(project, marks, token)
            val apk = LocalGradle(project).run(tasks, ::log).single()
            try {
                val info = checkNotNull(context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_ACTIVITIES))
                check(info.packageName == "org.example.gradlecheck") { "Unexpected APK: ${apk.path}" }
                check(info.activities.orEmpty().any { it.name == "org.example.gradlecheck.MainActivity" })
                for (task in unitTests) {
                    val marker = File(marks, task)
                    check(marker.isFile && marker.readText() == token) { "JUnit did not execute in :app:$task" }
                    log("JUnit executed · :app:$task")
                }
            } finally {
                apk.delete()
            }
        } finally {
            if (marks != null) Project.removeTree(marks.toPath())
        }
    }

    private fun addUnitTest(project: Project, marks: File, token: String) {
        val script = project.resolve("app/build.gradle")
        val testConfig = """
            dependencies { testImplementation 'junit:junit:4.13.2' }
            tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                useJUnit()
                systemProperty 'studio.test.task', name
            }
        """.trimIndent()
        project.write(script, (project.read(script) + "\n" + testConfig + "\n").encodeToByteArray())

        var directory = project.resolve("app/src")
        for (name in listOf("test", "java", "org", "example", "gradlecheck")) {
            directory = project.create(directory, name, true)
        }
        val test = project.create(directory, "MainActivityTest.java", false)
        project.write(test, """
            package org.example.gradlecheck;

            import org.junit.Test;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import static org.junit.Assert.assertEquals;

            public class MainActivityTest {
                @Test public void templateActivityIsOnTheTestClasspath() throws Exception {
                    assertEquals("org.example.gradlecheck.MainActivity", MainActivity.class.getName());
                    Files.write(Paths.get("${marks.absolutePath}", System.getProperty("studio.test.task")),
                            "$token".getBytes(StandardCharsets.UTF_8));
                }
            }
        """.trimIndent().encodeToByteArray())
    }

    private fun exerciseTasks(project: Project, parent: File) {
        val root = File(checkNotNull(project.root.uri.path))
        File(root, "settings.gradle").writeText("rootProject.name = 'GradleCheck'\ninclude ':app'\n")
        File(root, "build.gradle").writeText("")
        val localProperties = File(root, "local.properties")
        val originalProperties = "# Retain this project setting.\nexample.value=keep\n"
        localProperties.writeText(originalProperties)
        val marker = UUID.randomUUID().toString()
        // Keep the marker outside the project so a remote project copy could never read it.
        val localInput = File(parent, "local-input").apply { writeText(marker) }
        val afterCancel = File(parent, "task-survived-cancel")
        File(root, "app/build.gradle").writeText("""
            tasks.register('writeFixture') {
                doLast {
                    def apk = file('build/outputs/apk/debug/fixture.apk')
                    apk.parentFile.mkdirs()
                    apk.text = new File('${localInput.absolutePath}').text
                    println('LOCAL_GRADLE_OUTPUT')
                }
            }
            tasks.register('cancelFixture') {
                doLast {
                    def childBuilder = new ProcessBuilder('/system/bin/setsid', '/system/bin/sh', '-c',
                        'echo ready; /system/bin/sleep 10; echo orphan > "$1"', 'gradle-worker', '${afterCancel.absolutePath}')
                    childBuilder.environment().clear()
                    def child = childBuilder.start()
                    assert child.inputStream.newReader().readLine() == 'ready'
                    println('LOCAL_GRADLE_WAITING')
                    child.waitFor()
                    new File('${afterCancel.absolutePath}').text = 'still running'
                }
            }
            tasks.register('failFixture') {
                doLast { throw new GradleException('LOCAL_GRADLE_FAILURE') }
            }
        """.trimIndent())

        var sawOutput = false
        val apk = LocalGradle(project).run("tasks :app:writeFixture") { line ->
            log(line)
            if (line.contains("LOCAL_GRADLE_OUTPUT")) sawOutput = true
        }.single()
        try {
            check(sawOutput && apk.readText() == marker) { "Local task failed to produce its APK and output" }
            check(File(root, "app/build/outputs/apk/debug/fixture.apk").readText() == marker)
            check(localProperties.readText() == originalProperties) { "Build overwrote the project SDK settings" }
        } finally {
            apk.delete()
        }

        val run = LocalGradle(project)
        val stopper = Executors.newSingleThreadExecutor()
        var started = 0L
        try {
            val failure = runCatching {
                run.run(":app:cancelFixture") { line ->
                    log(line)
                    if (line.contains("LOCAL_GRADLE_WAITING") && started == 0L) {
                        started = SystemClock.elapsedRealtime()
                        stopper.execute(run::cancel)
                    }
                }
            }.exceptionOrNull()
            check(started > 0 && failure is Exception && SystemClock.elapsedRealtime() - started < 12000) {
                "Stopping a running Gradle task failed: $failure"
            }
        } finally {
            stopper.shutdownNow()
        }

        var sawFailure = false
        val failure = runCatching {
            LocalGradle(project).run(":app:failFixture") { line ->
                log(line)
                if (line.contains("LOCAL_GRADLE_FAILURE")) sawFailure = true
            }
        }.exceptionOrNull()
        check(failure is Exception && sawFailure) { "Gradle task failure did not reach its caller: $failure" }
        // Allow the cancelled task's sleep to elapse, even if the next build reused a warm JVM.
        SystemClock.sleep((started + 14000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        check(!afterCancel.exists()) { "Gradle kept executing the task after cancellation" }
    }

    private fun log(line: String) { Log.i("LocalGradleTest", line) }
}
