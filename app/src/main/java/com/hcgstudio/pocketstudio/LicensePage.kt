package com.hcgstudio.pocketstudio

import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hcgstudio.pocketstudio.codex.CodexRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private data class Component(val name: String, val license: String, val files: List<String>)

private val bundledComponents = listOf(
    Component("Inter", "SIL Open Font License 1.1", listOf("Inter.txt")),
    Component("JetBrains Mono", "SIL Open Font License 1.1", listOf("JetBrainsMono.txt")),
    Component("Pocket Studio", "MIT", listOf("PocketStudio.txt")),
    Component("Android Studio · ddmlib, sdk-common, annotations", "Apache 2.0", listOf("AOSP-tools.txt", "AOSP-annotations.txt")),
    Component("AAPT2", "AOSP / Iconify", listOf("TOOLS.txt", "AAPT2.txt", "Iconify.txt")),
    Component("Android Debug Bridge", "Apache 2.0 · NOTICE", listOf("wireless-adb/SOURCES.txt",
        "wireless-adb/arm64-NOTICE.txt", "wireless-adb/NOTICE-34.0.4.txt", "wireless-adb/NOTICE-35.0.2.txt")),
    Component("OpenJDK 17", "GPLv2 + Classpath Exception", listOf("TOOLS.txt", "OpenJDK.txt")),
    Component("AndroidX, Compose, Kotlin, Material Symbols, Gradle Wrapper", "Apache 2.0",
        listOf("Apache-components.txt", "Apache-2.0.txt")),
    Component("Android apksig", "Apache 2.0", listOf("apksig.txt")),
    Component("Dalvik DX", "Apache 2.0", listOf("TOOLS.txt", "Apache-2.0.txt")),
    Component("Eclipse Java Compiler", "EPL 1.0", listOf("ECJ.html", "EPL-1.0.html")),
    Component("Eclipse JGit · SSH", "Eclipse Distribution License 1.0", listOf("JGit.html")),
    Component("JSch", "BSD · ISC", listOf("JSch.txt")),
    Component("JavaEWAH", "Apache 2.0", listOf("JavaEWAH.txt")),
    Component("JavaParser · Symbol Solver", "Apache 2.0", listOf("JavaParser.txt", "Apache-2.0.txt")),
    Component("Javassist", "Apache 2.0", listOf("Javassist.html", "Apache-2.0.txt")),
    Component("commonmark-java · GFM", "BSD 2-Clause", listOf("CommonMark.txt")),
    Component("vcdiff-java", "Apache 2.0", listOf("Apache-components.txt", "Apache-2.0.txt")),
    Component("Guava, JSpecify, Error Prone, J2ObjC", "Apache 2.0", listOf("Apache-components.txt", "Apache-2.0.txt")),
    Component("SLF4J", "MIT", listOf("SLF4J.txt")),
    Component("Apache Commons Compress, Codec, IO, Lang", "Apache 2.0", listOf("ApacheCommons.txt")),
    Component("Bouncy Castle · PGP", "MIT", listOf("BouncyCastle.txt")),
    Component("XZ for Java", "0BSD", listOf("XZ.txt")),
    Component("Checker Framework qualifiers", "MIT", listOf("Checker-Qual.txt"))
)

private fun noticeBlocks(documents: Sequence<Pair<String, String>>): List<String> = buildList {
    for ((file, raw) in documents) {
        add(file)
        val text = if (file.endsWith(".html")) {
            val body = raw.substringAfter(Regex("(?i)<body[^>]*>").find(raw)?.value ?: "<body>")
            Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT).toString()
        } else raw
        // A single ADB NOTICE is over a megabyte. Lay out only visible blocks.
        val block = StringBuilder()
        for (line in text.lineSequence()) {
            block.appendLine(line)
            if (block.length >= 2400) {
                add(block.toString().trimEnd())
                block.clear()
            }
        }
        if (block.isNotBlank()) add(block.toString().trimEnd())
    }
}

@Composable
internal fun LicensePage(dismiss: () -> Unit) {
    val context = LocalContext.current
    val runtimeNotices by produceState<Map<String, String>>(emptyMap(), context) {
        value = withContext(Dispatchers.IO) {
            try { CodexRuntime(context).installedNotices() } catch (_: IOException) { emptyMap() }
        }
    }
    val components = remember(runtimeNotices) {
        bundledComponents + runtimeNotices.keys.groupBy { it.substringBefore('/') }.map { (name, files) ->
            Component(if (name == "codex") "Codex CLI" else "OpenSSL", "Termux", files)
        }
    }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val component = components.firstOrNull { it.name == selection }
    val listState = rememberLazyListState()
    val back = { if (component == null) dismiss() else selection = null }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().widthIn(max = 800.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), onClick = back)
                    Text(component?.name ?: stringResource(R.string.open_source_licenses),
                        style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                StudioPageTransition(component?.name, { if (it == null) 0 else 1 }, Modifier.fillMaxSize()) { name ->
                    if (name == null) {
                        LazyColumn(state = listState) {
                            items(components, key = { it.name }) { item ->
                                ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.license) },
                                    modifier = Modifier.clickable { selection = item.name })
                            }
                        }
                    } else {
                        val entry = components.first { it.name == name }
                        val error = stringResource(R.string.license_read_error)
                        val blocks by produceState<List<String>?>(null, entry) {
                            value = withContext(Dispatchers.IO) {
                                try {
                                    noticeBlocks(entry.files.asSequence().map { file ->
                                        file to (runtimeNotices[file] ?: context.assets.open("licenses/$file")
                                            .bufferedReader().use { it.readText() })
                                    })
                                } catch (_: IOException) { listOf(error) }
                            }
                        }
                        val content = blocks
                        if (content == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LazyColumn(contentPadding = PaddingValues(16.dp)) {
                            items(content) { block ->
                                SelectionContainer { Text(block, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
}
