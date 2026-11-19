package com.hcgstudio.pocketstudio.androidplugin

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

@Composable
internal fun AndroidLayoutPreview(
    plugin: Context, files: AndroidEnvironment.LayoutFiles,
    xml: String, path: String, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val strings = localizedPluginContext(plugin)
    var preview by remember(files, xml, path) { mutableStateOf<LayoutPreview?>(null) }
    var error by remember(files, xml, path) { mutableStateOf("") }
    var notes by remember(files, xml, path) { mutableStateOf(emptyList<String>()) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(files, xml, path) {
        try {
            preview = runInterruptible(Dispatchers.IO) {
                LayoutPreview.load(context, strings, files, xml, path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: e.javaClass.simpleName
        }
    }
    AlertDialog(
        onDismissRequest = { if (details) details = false else onDismiss() },
        title = { Text(if (details) strings.getString(R.string.preview_diff) else strings.getString(R.string.layout_preview)) },
        text = {
            val loaded = preview
            when {
                error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
                loaded == null -> CircularProgressIndicator()
                else -> key(details) {
                    if (details) Column(Modifier.verticalScroll(rememberScrollState())) {
                        notes.forEach { Text(it, Modifier.padding(vertical = 4.dp)) }
                    } else key(loaded) {
                        AndroidView(
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    try {
                                        loaded.drawInto(this)
                                        notes = loaded.differences()
                                    } catch (e: RuntimeException) {
                                        error = e.localizedMessage ?: e.javaClass.simpleName
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(.62f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(strings.getString(R.string.close)) } },
        dismissButton = {
            if (notes.isNotEmpty()) TextButton({ details = !details }) {
                Text(if (details) strings.getString(R.string.back_to_preview) else strings.getString(R.string.preview_diff))
            }
        }
    )
}
