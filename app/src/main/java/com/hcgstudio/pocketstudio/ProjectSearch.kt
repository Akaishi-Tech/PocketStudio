package com.hcgstudio.pocketstudio

import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal data class SearchHit(val file: Project.Node, val line: Int, val excerpt: String)
internal data class MatchedFile(val node: Project.Node, val digest: ByteArray)
internal data class SearchResults(
    val query: String, val files: List<MatchedFile>, val hits: List<SearchHit>,
    val fileCount: Int, val count: Long, val matchingLines: Long
) {
    val truncated get() = matchingLines > hits.size
}

private val ignoredFolders = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".cxx", ".kotlin")
private val binaryExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "zip", "jar", "aar", "apk", "aab",
    "keystore", "jks", "class", "dex", "so", "ttf", "otf", "mp3", "mp4", "pdf", "ico", "webm")

internal fun searchProject(project: Project, query: String, forReplacement: Boolean = false,
                          maxExcerptLength: Int = Project.MAX_TEXT): SearchResults {
    require(query.isNotEmpty() && '\n' !in query && '\r' !in query) { uiString(R.string.search_query_single_line) }
    val hits = mutableListOf<SearchHit>()
    val files = mutableListOf<MatchedFile>()
    val hash = if (forReplacement) MessageDigest.getInstance("SHA-256") else null
    var fileCount = 0
    var count = 0L
    var matchingLines = 0L
    val directories = ArrayDeque<Project.Node>()
    val visited = mutableSetOf<Uri>()
    directories.add(project.root)
    while (directories.isNotEmpty()) {
        val dir = directories.removeFirst()
        if (!visited.add(dir.uri)) continue
        for (node in project.list(dir)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val local = if (project.tree == null) File(node.uri.path!!).toPath() else null
            if (node.folder) {
                if (node.name !in ignoredFolders && (local == null || Files.isDirectory(local, NOFOLLOW_LINKS)))
                    directories.addLast(node)
                continue
            }
            if (node.name.substringAfterLast('.', "").lowercase() in binaryExtensions) continue
            if (local != null && !Files.isRegularFile(local, NOFOLLOW_LINKS)) continue
            val source = try {
                project.read(node)
            } catch (_: CharacterCodingException) {
                continue
            } catch (_: Project.UnsupportedText) {
                continue
            }
            val before = count
            source.lineSequence().forEachIndexed { index, line ->
                var pos = line.indexOf(query)
                if (pos < 0) return@forEachIndexed
                matchingLines++
                if (hits.size < 100) {
                    val start = (pos - 28).coerceAtLeast(0)
                    val end = minOf(line.length, pos + maxOf(90, query.length), start + maxExcerptLength)
                    hits += SearchHit(node, index + 1, line.substring(start, end).trimEnd())
                }
                while (pos >= 0) {
                    count++
                    pos = line.indexOf(query, pos + query.length)
                }
            }
            if (count > before) {
                fileCount++
                if (hash != null) files += MatchedFile(node, hash.digest(source.toByteArray()))
            }
        }
    }
    return SearchResults(query, files, hits, fileCount, count, matchingLines)
}

internal fun searchProjectForAgent(project: Project, query: String): String {
    val result = searchProject(project, query, maxExcerptLength = 256)
    val matches = JSONArray()
    for (hit in result.hits) {
        matches.put(json("path" to hit.file.path, "line" to hit.line, "excerpt" to hit.excerpt))
    }
    return json("matches" to matches, "totalOccurrences" to result.count,
        "totalMatchingLines" to result.matchingLines, "truncated" to result.truncated).toString()
}
