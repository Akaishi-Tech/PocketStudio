package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hcgstudio.pocketstudio.build.KotlinTools
import com.hcgstudio.pocketstudio.build.LocalAndroidSdk
import com.hcgstudio.pocketstudio.build.SdkPlatforms
import kotlinx.coroutines.*

@Composable
internal fun SdkManager(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val sdk = remember { SdkPlatforms(context) }
    val kotlin = remember { KotlinTools(context) }
    val gradleSdk = remember { LocalAndroidSdk(context) }
    val links = LocalUriHandler.current
    var buildToolsInstalled by remember { mutableStateOf(false) }
    var buildToolsDialog by remember { mutableStateOf(false) }
    var toolsInstalled by remember { mutableStateOf(false) }
    var toolsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(emptyMap<String, String>()) }
    var available by remember { mutableStateOf(emptyList<SdkPlatforms.Platform>()) }
    var install by remember { mutableStateOf<SdkPlatforms.Platform?>(null) }
    var remove by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var catalogError by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    suspend fun refreshInstalled() {
        buildToolsInstalled = withContext(Dispatchers.IO) { gradleSdk.installed() }
        toolsInstalled = withContext(Dispatchers.IO) { kotlin.installed() }
        installed = withContext(Dispatchers.IO) { sdk.installed().associateWith { sdk.installedRevision(it) } }
    }
    fun change(label: String, block: () -> Unit) {
        if (working) return
        working = true; progress = label; error = ""
        scope.launch {
            try { runInterruptible(Dispatchers.IO, block); refreshInstalled() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.localizedMessage ?: e.toString() }
            finally { working = false; progress = "" }
        }
    }
    LaunchedEffect(revision) {
        loading = true; catalogError = ""
        try {
            refreshInstalled()
            available = runInterruptible(Dispatchers.IO) { sdk.catalog() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { catalogError = context.getString(R.string.unable_to_load_the_platform_catalog, e.localizedMessage ?: e.javaClass.simpleName) }
        finally { loading = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) change(context.getString(R.string.importing_platform)) {
            context.contentResolver.openInputStream(uri)?.use { sdk.importArchive(it) } ?: error(context.getString(R.string.unable_to_read_platform_zip))
        }
    }
    val toolsFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) change(context.getString(R.string.importing_kotlin_d8)) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                kotlin.install(input) { msg -> scope.launch { progress = msg } }
            } ?: error(context.getString(R.string.unable_to_read_toolkit))
        }
    }
    val buildToolsFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) change(context.getString(R.string.verifying_build_tools)) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                gradleSdk.install(input) { msg -> scope.launch { progress = msg } }
            } ?: error(context.getString(R.string.unable_to_read_build_tools_zip))
        }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sdk_manager), Modifier.weight(1f))
            StudioIconButton(R.drawable.studio_refresh, context.getString(R.string.refresh_catalog), !loading && !working) { revision++ }
        } },
        text = {
            Column {
                if (working || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progress.isNotBlank()) Text(progress, Modifier.padding(vertical = 6.dp))
                for (problem in listOf(error, catalogError).filter(String::isNotEmpty)) {
                    Text(problem, Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item { SectionTitle(context.getString(R.string.compiler_tools)) }
                    item {
                        SdkRow("Android SDK Build Tools", "${LocalAndroidSdk.VERSION} · " +
                            if (buildToolsInstalled) context.getString(R.string.installed) else context.getString(R.string.label_61_mib_local_gradle),
                            if (buildToolsInstalled) R.drawable.studio_delete else R.drawable.studio_download,
                            if (buildToolsInstalled) context.getString(R.string.uninstall_build_tools) else context.getString(R.string.install_build_tools), !working) { buildToolsDialog = true }
                    }
                    item {
                        SdkRow("Kotlin / D8", if (toolsInstalled) context.getString(R.string.installed_with_detail, KotlinTools.VERSION) else "231 MiB · ${KotlinTools.VERSION}",
                            if (toolsInstalled) R.drawable.studio_delete else R.drawable.studio_download,
                            if (toolsInstalled) context.getString(R.string.uninstall_kotlin_d8) else context.getString(R.string.install_kotlin_d8), !working) { toolsDialog = true }
                    }
                    if (installed.isNotEmpty()) {
                        item { SectionTitle(context.getString(R.string.installed)) }
                        items(installed.keys.toList(), key = { "installed-$it" }) { api ->
                            SdkRow("Android API $api", context.getString(R.string.revision, installed[api]), R.drawable.studio_delete, context.getString(R.string.uninstall_api, api), !working) { remove = api }
                        }
                    }
                    val downloads = available.filter { installed[it.api] != it.revision }
                    if (downloads.isNotEmpty()) {
                        item { SectionTitle(context.getString(R.string.available_platforms)) }
                        items(downloads, key = { it.api }) { platform ->
                            SdkRow(platform.name, "API ${platform.api} · ${platform.size / 1048576} MiB",
                                R.drawable.studio_download, context.getString(R.string.install_api, platform.api), !working) { install = platform }
                        }
                    }
                    if (installed.isEmpty() && downloads.isEmpty() && !loading) item {
                        Text(stringResource(R.string.no_platforms_installed_import_an_android_sdk_platform),
                            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (!working) onDismiss() }, enabled = !working) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton({ picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !working) { Text(stringResource(R.string.import_platform_zip)) } }
    )
    if (buildToolsDialog) {
        AlertDialog(onDismissRequest = { buildToolsDialog = false },
            title = { Text(if (buildToolsInstalled) context.getString(R.string.uninstall_android_sdk_build_tools) else context.getString(R.string.install_android_sdk_build_tools)) },
            text = {
                if (buildToolsInstalled) Text(stringResource(R.string.gradle_needs_these_tools_to_build_android_projects))
                else Column {
                    Text(stringResource(R.string.download_61_mib_from_google_or_import_setup, LocalAndroidSdk.VERSION, LocalAndroidSdk.ARCHIVE))
                    TextButton({ links.openUri("https://developer.android.com/studio/terms") }) { Text(stringResource(R.string.android_sdk_license_agreement)) }
                }
            },
            confirmButton = { TextButton({
                buildToolsDialog = false
                if (buildToolsInstalled) change(context.getString(R.string.uninstalling_build_tools)) { gradleSdk.remove() }
                else change(context.getString(R.string.downloading_build_tools)) { gradleSdk.download { msg -> scope.launch { progress = msg } } }
            }) { Text(if (buildToolsInstalled) context.getString(R.string.uninstall) else context.getString(R.string.accept_and_download)) } },
            dismissButton = { Row {
                if (!buildToolsInstalled) TextButton({ buildToolsDialog = false; buildToolsFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.accept_and_import)) }
                TextButton({ buildToolsDialog = false }) { Text(stringResource(R.string.cancel)) }
            } }
        )
    }
    if (toolsDialog) {
        AlertDialog(onDismissRequest = { toolsDialog = false },
            title = { Text(if (toolsInstalled) context.getString(R.string.uninstall_kotlin_d8_label) else context.getString(R.string.install_kotlin_d8)) },
            text = { Text(if (toolsInstalled) context.getString(R.string.you_can_reinstall_it_here_later) else
                context.getString(R.string.download_the_release_from_github_231_mib_verify, KotlinTools.VERSION)) },
            confirmButton = { TextButton({
                toolsDialog = false
                if (toolsInstalled) change(context.getString(R.string.uninstalling)) { kotlin.remove() }
                else change(context.getString(R.string.preparing_download)) { kotlin.download { msg -> scope.launch { progress = msg } } }
            }) { Text(if (toolsInstalled) context.getString(R.string.uninstall) else context.getString(R.string.download)) } },
            dismissButton = {
                Row {
                    if (!toolsInstalled) TextButton({ toolsDialog = false; toolsFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.select_file)) }
                    TextButton({ toolsDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }
    install?.let { platform ->
        AlertDialog(onDismissRequest = { install = null }, title = { Text(stringResource(R.string.install, platform.name)) },
            text = { Text(platform.license, Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton({
                install = null
                change(context.getString(R.string.downloading_api, platform.api)) { sdk.install(platform) { msg -> scope.launch { progress = msg } } }
            }) { Text(stringResource(R.string.accept_and_install)) } },
            dismissButton = { TextButton({ install = null }) { Text(stringResource(R.string.cancel)) } })
    }
    remove?.let { api ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(R.string.uninstall_android_api, api)) },
            confirmButton = { TextButton({ remove = null; change(context.getString(R.string.uninstalling)) { sdk.remove(api) } }) { Text(stringResource(R.string.uninstall)) } },
            dismissButton = { TextButton({ remove = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
@Composable
private fun SdkRow(title: String, info: String, icon: Int, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StudioIconButton(icon, action, enabled, onClick)
    }
}
