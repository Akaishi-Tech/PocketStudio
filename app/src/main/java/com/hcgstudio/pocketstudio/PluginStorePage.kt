package com.hcgstudio.pocketstudio

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@Composable
internal fun PluginStorePage(devices: DeviceController, dismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PluginStore(context) }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(emptyList<StorePlugin>()) }
    var installed by remember { mutableStateOf(emptyMap<String, InstalledPlugin>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf<String?>(null) }
    var percent by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<Intent?>(null) }

    suspend fun refreshInstalled() {
        installed = withContext(Dispatchers.IO) { store.installed(catalog) }
    }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            catalog = withContext(Dispatchers.IO) { store.cached() }
            refreshInstalled()
            catalog = runInterruptible(Dispatchers.IO) { store.catalog() }
            refreshInstalled()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.localizedMessage ?: e.javaClass.simpleName }
        finally { loading = false }
    }

    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending = null
        scope.launch { refreshInstalled() }
    }
    fun openInstaller(intent: Intent) {
        try { installer.launch(intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)) }
        catch (e: Exception) {
            pending = null
            error = e.localizedMessage ?: context.getString(R.string.system_apk_installer_not_found)
        }
    }
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val intent = pending
        if (intent != null && context.packageManager.canRequestPackageInstalls()) openInstaller(intent)
        else {
            pending = null
            error = context.getString(R.string.allow_pocket_studio_to_install_unknown_apps_then)
        }
    }

    fun install(plugin: StorePlugin) {
        if (installing != null || pending != null) return
        installing = plugin.packageName
        percent = 0
        error = null
        scope.launch {
            try {
                val apk = runInterruptible(Dispatchers.IO) {
                    store.download(plugin) { current, total -> percent = (current * 100 / total).toInt() }
                }
                if (devices.installPluginLocally(apk)) refreshInstalled()
                else {
                    val intent = ReleaseApkInstaller(context, plugin.packageName, plugin.packageName).installerIntent(apk)
                    pending = intent
                    if (context.packageManager.canRequestPackageInstalls()) openInstaller(intent)
                    else allowInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null)))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                pending = null
                error = e.localizedMessage ?: e.javaClass.simpleName
            } finally { installing = null }
        }
    }

    val ids = (catalog.map { it.packageName } + installed.keys).distinct()
    val busy = installing != null || pending != null
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), onClick = dismiss)
                    Text(stringResource(R.string.plugin_store), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    StudioIconButton(R.drawable.studio_refresh, stringResource(R.string.refresh_catalog), !loading && !busy) { refresh++ }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        error?.let { message -> item {
                            Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        } }
                        if (ids.isEmpty() && !loading && error == null) item {
                            Text(stringResource(R.string.plugin_store_empty), Modifier.padding(16.dp))
                        }
                        items(ids, key = { it }) { pkg ->
                            val plugin = catalog.firstOrNull { it.packageName == pkg }
                            val local = installed[pkg]
                            val canInstall = plugin != null && plugin.compatible && local?.trusted != false &&
                                plugin.release.versionCode > (local?.versionCode ?: 0)
                            val active = installing == pkg
                            val detail = when {
                                active && percent < 100 -> stringResource(R.string.plugin_downloading, percent)
                                active -> stringResource(R.string.installing_apk)
                                local?.trusted == false -> stringResource(R.string.plugin_untrusted)
                                plugin != null && !plugin.compatible -> stringResource(R.string.plugin_incompatible)
                                local != null && canInstall -> stringResource(R.string.plugin_version_update, local.version, plugin!!.release.version)
                                local != null -> stringResource(R.string.settings_installed_version, local.version)
                                plugin != null -> "${plugin.release.version} · ${Formatter.formatShortFileSize(context, plugin.release.size)}"
                                else -> ""
                            }
                            ListItem(headlineContent = { Text(plugin?.name ?: local?.name.orEmpty()) },
                                supportingContent = { Text(detail) },
                                trailingContent = if (canInstall && !active) { {
                                    TextButton({ install(requireNotNull(plugin)) }, enabled = !busy) {
                                        Text(stringResource(if (local == null) R.string.plugin_install else R.string.update))
                                    }
                                } } else if (active) { {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                } } else null)
                        }
                    }
                }
            }
        }
    }
}
