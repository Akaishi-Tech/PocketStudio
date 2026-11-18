package com.hcgstudio.pocketstudio

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

object ProjectSearchChecks {
    @JvmStatic fun run(context: Context) {
        val imports = File(context.filesDir, "imports")
        imports.mkdirs()
        val fixture = Files.createTempDirectory(imports.toPath(), "search-check-")
        try {
            val root = Files.createDirectory(fixture.resolve("project")).toFile()
            val project = Project(context, Uri.fromFile(root))
            val source = File(root, "src/Main.kt")
            source.parentFile!!.mkdir()
            source.writeText("first\r\nneedle needle\r\nNeedle")
            File(root, "build").mkdir()
            File(root, "build/generated.txt").writeText("needle")
            File(root, "binary.txt").writeBytes("needle\u0000".toByteArray())
            val external = fixture.resolve("external.txt").toFile().apply { writeText("needle") }
            Files.createSymbolicLink(root.toPath().resolve("external"), external.toPath())
            Files.createSymbolicLink(root.toPath().resolve("cycle"), root.toPath())

            fun search(query: String) = JSONObject(searchProjectForAgent(project, query))
            val first = search("needle")
            val hit = first.getJSONArray("matches").getJSONObject(0)
            check(hit.getString("path") == "src/Main.kt" && hit.getInt("line") == 2)
            check(hit.getString("excerpt") == "needle needle")
            check(first.getLong("totalOccurrences") == 2L && first.getLong("totalMatchingLines") == 1L)
            check(!first.getBoolean("truncated") && first.getJSONArray("matches").length() == 1)
            val replacement = searchProject(project, "needle", forReplacement = true)
            check(replacement.fileCount == 1 && replacement.files.single().node.path == "src/Main.kt")
            check(search("missing").getJSONArray("matches").length() == 0)
            source.writeText("needle needle\n".repeat(100))
            check(!search("needle").getBoolean("truncated"))
            source.appendText("needle")
            val many = search("needle")
            check(many.getBoolean("truncated") && many.getJSONArray("matches").length() == 100)
            check(many.getLong("totalOccurrences") == 201L && many.getLong("totalMatchingLines") == 101L)
            val longQuery = "x".repeat(600)
            source.writeText(longQuery)
            val longHit = search(longQuery).getJSONArray("matches").getJSONObject(0)
            check(longHit.getString("excerpt").length == 256)
            for (query in listOf("", "a\nb")) {
                check(runCatching { search(query) }.exceptionOrNull() is IllegalArgumentException)
            }
        } finally {
            Project.removeTree(fixture)
        }
    }
}
