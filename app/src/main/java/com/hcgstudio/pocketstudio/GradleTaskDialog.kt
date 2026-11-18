package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hcgstudio.pocketstudio.build.LocalAndroidSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun GradleTaskDialog(
    project: Project, initialTask: String, onDismiss: () -> Unit,
    onOutput: (String) -> Unit, onApk: (File) -> Unit, onFinished: () -> Unit
) {
    var task by remember { mutableStateOf(initialTask.ifBlank { ":app:assembleDebug" }) }
    val hasBuildTools = remember(project) { LocalAndroidSdk(project.context).installed() }
    var runner by remember { mutableStateOf<LocalGradle?>(null) }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<File>()) }
    val scope = rememberCoroutineScope()
    val output by rememberUpdatedState(onOutput)
    DisposableEffect(Unit) { onDispose { runner?.cancel(); runner = null } }

    fun start() {
        if (runner != null) return
        val service = LocalGradle()
        val tasks = task.trim()
        runner = service
        status = uiString(R.string.preparing_gradle_on_this_device)
        val main = Handler(Looper.getMainLooper())
        val pending = StringBuilder()
        var lastLine: String? = null
        val flush = Runnable {
            val batch = synchronized(pending) {
                val last = lastLine ?: return@Runnable
                val text = pending.toString()
                pending.setLength(0)
                lastLine = null
                text to last
            }
            if (runner === service) { output(batch.first); status = batch.second.takeLast(600) }
        }
        scope.launch {
            try {
                val apks = try {
                    withContext(Dispatchers.IO) {
                        service.run(project, tasks) { line ->
                            synchronized(pending) {
                                if (lastLine == null) main.postDelayed(flush, 80) else pending.append('\n')
                                pending.append(line)
                                lastLine = line
                            }
                        }
                    }
                } finally {
                    main.removeCallbacks(flush)
                    flush.run()
                }
                output(uiString(R.string.gradle_tasks_completed))
                if (apks.size > 1) { results = apks; status = "" }
                else { apks.firstOrNull()?.let(onApk); onDismiss() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = e.localizedMessage ?: e.javaClass.simpleName
                output(status)
            } finally { runner = null; onFinished() }
        }
    }

    AlertDialog(
        onDismissRequest = { if (runner == null) onDismiss() },
        title = { Text(if (results.isEmpty()) uiString(R.string.gradle_tasks) else uiString(R.string.select_build_output)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    results.isNotEmpty() -> results.forEach { apk ->
                        val modulePath = apk.path.substringAfter("/outputs/gradle/").substringAfter('/')
                        TextButton({ onApk(apk); onDismiss() }) { Text(modulePath) }
                    }
                    runner != null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status) }
                    else -> {
                        OutlinedTextField(task, { task = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.task_names)) }, supportingText = { Text(stringResource(R.string.separate_multiple_tasks_with_spaces)) }, singleLine = true)
                        if (!hasBuildTools) {
                            Text(stringResource(R.string.install_the_matching_sdk_platform_and_build_tools, LocalAndroidSdk.VERSION))
                        }
                        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (runner == null && results.isEmpty()) TextButton(
                onClick = { start() }, enabled = task.isNotBlank()
            ) { Text(stringResource(R.string.run_on_this_device)) }
        },
        dismissButton = {
            TextButton({ if (runner == null) onDismiss() else runner?.cancel() }) {
                Text(if (runner != null) uiString(R.string.stop) else uiString(R.string.close))
            }
        }
    )
}
