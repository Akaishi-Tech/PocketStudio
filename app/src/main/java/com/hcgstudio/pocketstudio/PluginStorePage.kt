package com.hcgstudio.pocketstudio

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@Composable
internal fun PluginStorePage(devices: DeviceController, initialCategory: String?, dismiss: () -> Unit) {
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
        catch (e: Exception) {
            if (catalog.isEmpty() || refresh != 0) error = e.localizedMessage ?: e.javaClass.simpleName
        } finally { loading = false }
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

    var category by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { if (details == null) dismiss() else details = null }
    val byPackage = catalog.associateBy { it.packageName }
    val ids = (byPackage.keys + installed.keys).toList()
    fun categoryOf(id: String) = byPackage[id]?.category ?: installed[id]?.category
    val categories = (listOfNotNull(initialCategory) + ids.mapNotNull(::categoryOf)).distinct().sorted()
    val filtered = if (category == null) ids else ids.filter { categoryOf(it) == category }
    val busy = installing != null || pending != null
    val listState = rememberLazyListState()
    Dialog(back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), onClick = back)
                    Text(stringResource(R.string.plugin_store), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (details == null) {
                        StudioIconButton(R.drawable.studio_refresh, stringResource(R.string.refresh_catalog), !loading && !busy) { refresh++ }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message ->
                    Text(message, Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                StudioPageTransition(details, { if (it == null) 0 else 1 }, Modifier.widthIn(max = 720.dp).fillMaxSize()) { id ->
                    if (id == null) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            if (categories.isNotEmpty()) item {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(category == null, { category = null }, label = { Text(stringResource(R.string.plugin_all)) })
                                    categories.forEach { value ->
                                        FilterChip(category == value, { category = value }, label = { Text(categoryLabel(value)) })
                                    }
                                }
                            }
                            if (filtered.isEmpty() && !loading && error == null) item {
                                Text(stringResource(if (category == null) R.string.plugin_store_empty else R.string.plugin_category_empty),
                                    Modifier.padding(16.dp))
                            }
                            items(filtered, key = { it }) { pkg ->
                                PluginEntry(store, pkg, byPackage[pkg], installed[pkg], installing == pkg, percent, busy,
                                    showDetails = false, open = { details = pkg }, install = ::install)
                            }
                        }
                    } else if (id in ids) {
                        PluginEntry(store, id, byPackage[id], installed[id], installing == id, percent, busy,
                            showDetails = true, open = {}, install = ::install)
                    } else if (!loading) {
                        LaunchedEffect(id) { details = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(value: String): String =
    if (value == PluginDiscovery.AGENT_CATEGORY) stringResource(R.string.agents_tab_label) else value

@Composable
private fun PluginEntry(store: PluginStore, pkg: String, plugin: StorePlugin?, local: InstalledPlugin?,
    active: Boolean, percent: Int, busy: Boolean, showDetails: Boolean, open: () -> Unit, install: (StorePlugin) -> Unit) {
    val name = plugin?.name ?: local?.name ?: pkg
    val artifact = plugin?.artifact()
    val canInstall = plugin != null && artifact != null && local?.trusted != false &&
        plugin.versionCode > (local?.versionCode ?: 0)
    val status = when {
        active && percent < 100 -> stringResource(R.string.plugin_downloading, percent)
        active -> stringResource(R.string.installing_apk)
        local?.trusted == false -> stringResource(R.string.plugin_untrusted)
        plugin != null && artifact == null -> stringResource(R.string.plugin_incompatible)
        local != null && canInstall -> stringResource(R.string.plugin_version_update, local.version, plugin!!.version)
        local != null -> stringResource(R.string.settings_installed_version, local.version)
        plugin != null && artifact != null -> "${plugin.version} · ${Formatter.formatShortFileSize(LocalContext.current, artifact.size)}"
        else -> ""
    }
    val actionLabel = stringResource(if (local == null) R.string.plugin_install else R.string.update)
    val icon = produceState<Bitmap?>(null, pkg, plugin?.versionCode, local?.versionCode) {
        value = try { runInterruptible(Dispatchers.IO) { store.icon(pkg, plugin, local != null) } }
        catch (_: IOException) { null }
    }.value
    val artwork: @Composable (Modifier) -> Unit = { modifier ->
        if (icon == null) Symbol(R.drawable.studio_android, null, modifier)
        else Image(icon.asImageBitmap(), contentDescription = null, modifier)
    }

    if (!showDetails) {
        ListItem(modifier = Modifier.clickable(role = Role.Button, onClick = open),
            leadingContent = { artwork(Modifier.size(44.dp)) },
            headlineContent = { Text(name) }, supportingContent = { Text(status) },
            trailingContent = {
                when {
                    active -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    canInstall -> TextButton({ install(requireNotNull(plugin)) }, enabled = !busy) { Text(actionLabel) }
                    else -> Symbol(R.drawable.studio_chevron_right, null, Modifier.size(20.dp))
                }
            })
        return
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            artwork(Modifier.size(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                (plugin?.developer ?: local?.developer)?.let {
                    Text(stringResource(R.string.plugin_developer, it), style = MaterialTheme.typography.bodyMedium)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (active) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        else if (canInstall) Button({ install(requireNotNull(plugin)) }, enabled = !busy) { Text(actionLabel) }
        (plugin?.description ?: local?.description)?.let {
            HorizontalDivider()
            Text(stringResource(R.string.plugin_about), style = MaterialTheme.typography.titleMedium)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
