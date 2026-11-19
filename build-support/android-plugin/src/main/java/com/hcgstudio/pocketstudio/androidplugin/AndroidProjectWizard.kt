package com.hcgstudio.pocketstudio.androidplugin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment
import com.hcgstudio.pocketstudio.plugin.ProjectWizardHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AndroidProjectWizard(host: ProjectWizardHost, environment: AndroidEnvironment, plugin: Context, back: () -> Unit) {
    CompositionLocalProvider(LocalWizardContext provides localizedPluginContext(plugin)) {
        AndroidProjectPage(host, environment, back)
    }
}

private val LocalWizardContext = staticCompositionLocalOf<Context> { error("Missing wizard resources") }

@Composable
private fun label(id: Int): String = LocalWizardContext.current.getString(id)

@Composable
private fun Symbol(id: Int, description: String?, modifier: Modifier = Modifier.size(22.dp),
                   tint: Color = LocalContentColor.current) {
    Icon(pluginIcon(LocalWizardContext.current, id), description, modifier, tint)
}

@Composable
private fun AndroidProjectPage(w: ProjectWizardHost, environment: AndroidEnvironment, chooseType: () -> Unit) {
    var configuring by rememberSaveable { mutableStateOf(false) }
    var templateName by rememberSaveable { mutableStateOf(ProjectTemplate.EMPTY_VIEWS.name) }
    val template = ProjectTemplate.valueOf(templateName)
    var name by rememberSaveable { mutableStateOf("My Application") }
    var customPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val packageName = customPackage ?: suggestedPackage(name)
    var languageName by rememberSaveable { mutableStateOf(ProjectLanguage.JAVA.name) }
    val language = ProjectLanguage.valueOf(languageName)
    var minSdk by rememberSaveable { mutableIntStateOf(ProjectTemplate.DEFAULT_MIN_SDK) }
    var installed by remember { mutableStateOf(emptyList<String>()) }
    var selectedSdk by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            installed = withContext(Dispatchers.IO) { environment.platforms().installed() }
                .filter { it.substringBefore('.').toInt() >= ProjectTemplate.MIN_SDK }
        } catch (e: java.io.IOException) { w.creationError = e.localizedMessage }
    }
    val platforms = installed.ifEmpty { listOf(android.os.Build.VERSION.SDK_INT.toString()) }
    val compileSdk = selectedSdk?.takeIf { it in platforms } ?: platforms.first()
    val majorSdk = compileSdk.substringBefore('.').toInt()
    val effectiveMin = minSdk.coerceAtMost(majorSdk)
    var parent by rememberSaveable { mutableStateOf<Uri?>(null) }
    val focus = LocalFocusManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            parent = uri
            w.creationError = null
        }
    }
    val enabled = !w.busy
    val back = {
        if (enabled) {
            focus.clearFocus()
            w.creationError = null
            if (configuring) configuring = false else chooseType()
        }
    }
    BackHandler(onBack = back)
    val nameError = w.projectNameError(name.trim())
    val packageError = template.packageNameError(packageName.trim(), language)
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, enabled = enabled) {
                    Symbol(R.drawable.studio_arrow_back, label(R.string.back))
                }
                Text(label(if (configuring) template.title else R.string.wizard_select_template),
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (configuring) {
                    Column(Modifier.widthIn(max = 540.dp).fillMaxSize()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(name, { name = it; w.creationError = null }, Modifier.fillMaxWidth(),
                            singleLine = true, enabled = enabled, label = { Text(label(R.string.project_name)) },
                            isError = nameError != null, supportingText = nameError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(packageName, { customPackage = it; w.creationError = null }, Modifier.fillMaxWidth(),
                            singleLine = true, enabled = enabled, label = { Text(label(R.string.package_name)) },
                            isError = packageError != null, supportingText = packageError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false, imeAction = ImeAction.Done))
                        ProjectLocation(parent, name.trim(), enabled) {
                            focus.clearFocus()
                            picker.launch(parent)
                        }
                        if (template != ProjectTemplate.NO_ACTIVITY) {
                            ProjectChoice(label(R.string.wizard_language), language, ProjectLanguage.entries,
                                { if (it == ProjectLanguage.JAVA) "Java" else "Kotlin" }, enabled) {
                                languageName = it.name
                                w.creationError = null
                            }
                        }
                        ProjectChoice(label(R.string.wizard_compile_sdk), compileSdk, platforms,
                            { "API $it" }, enabled) { selectedSdk = it }
                        ProjectChoice(label(R.string.wizard_min_sdk), effectiveMin,
                            (ProjectTemplate.MIN_SDK..majorSdk).toList(), { "API $it" }, enabled) {
                            minSdk = it
                            w.creationError = null
                        }
                    }
                } else {
                    TemplateGallery(template, Modifier.fillMaxHeight()) { templateName = it.name }
                }
            }
            if (w.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            Column(Modifier.widthIn(max = 740.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                w.creationError?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(bottom = 4.dp),
                        color = colors.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    if (configuring) {
                        parent?.let {
                            focus.clearFocus()
                            w.creationError = null
                            val starter = environment.projectTemplate(template.starter, name.trim(), packageName.trim(),
                                language.name, effectiveMin, compileSdk)
                            w.createProject(it, name.trim(), starter)
                        }
                    } else configuring = true
                }, enabled = enabled && (!configuring || (nameError == null && packageError == null && parent != null)),
                    modifier = Modifier.align(Alignment.End)) {
                    Text(label(if (configuring) R.string.wizard_finish else R.string.wizard_next))
                    if (!configuring) {
                        Spacer(Modifier.width(8.dp))
                        Symbol(R.drawable.studio_arrow_right_alt, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private fun suggestedPackage(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "com.example.myapp"
    val slug = trimmed.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(240)
    val suffix = slug.ifEmpty { "app" + trimmed.hashCode().toUInt().toString(16) }
    val candidate = "com.example.$suffix"
    return if (javaPackageError(candidate) == null) candidate else "com.example.app$suffix"
}

@Composable
private fun ProjectLocation(parent: Uri?, name: String, enabled: Boolean, browse: () -> Unit) {
    val context = LocalContext.current
    val folder by produceState<String?>(null, parent) {
        value = null
        val uri = parent ?: return@produceState
        val id = DocumentsContract.getTreeDocumentId(uri)
        val path = if (uri.authority == "com.android.externalstorage.documents") id.substringAfter(':') else ""
        value = path.ifEmpty {
            withContext(Dispatchers.IO) {
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                try {
                    context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: id
                } catch (_: SecurityException) { id }
            }
        }
    }
    val shape = MaterialTheme.shapes.extraSmall
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = browse)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label(R.string.wizard_save_location), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(folder?.let { if (name.isEmpty()) it else "$it/$name" } ?: label(R.string.wizard_choose_parent),
                style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.width(12.dp))
        Symbol(R.drawable.studio_folder_open, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ProjectChoice(label: String, value: T, choices: List<T>, display: (T) -> String,
                              enabled: Boolean, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = it }) {
        OutlinedTextField(display(value), {}, Modifier.fillMaxWidth()
            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled), enabled = enabled, readOnly = true,
            singleLine = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded, { expanded = false }) {
            choices.forEach {
                DropdownMenuItem(text = { Text(display(it)) }, onClick = { expanded = false; select(it) },
                    trailingIcon = if (it == value) { { Symbol(R.drawable.studio_check, null) } } else null)
            }
        }
    }
}

private val ProjectTemplate.title: Int get() = when (this) {
    ProjectTemplate.EMPTY_VIEWS -> R.string.wizard_empty_views
    ProjectTemplate.BUTTON -> R.string.wizard_button_counter
    ProjectTemplate.NO_ACTIVITY -> R.string.wizard_no_activity
}

@Composable
private fun TemplateGallery(selected: ProjectTemplate, modifier: Modifier, select: (ProjectTemplate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tileWidth = 148.dp * (LocalDensity.current.fontScale).coerceAtLeast(1f)
    LazyVerticalGrid(GridCells.Adaptive(tileWidth), modifier.widthIn(max = 660.dp).fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ProjectTemplate.entries, key = { it.name }) { item ->
            val active = item == selected
            val shape = MaterialTheme.shapes.medium
            Column(Modifier.fillMaxWidth().clip(shape)
                .background(if (active) colors.primaryContainer else colors.surfaceContainerLow)
                .border(if (active) 2.dp else 1.dp, if (active) colors.primary else colors.outlineVariant, shape)
                .selectable(active, role = Role.RadioButton, onClick = { select(item) })
                .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(114.dp), contentAlignment = Alignment.Center) {
                    TemplatePreview(item)
                    if (active) Box(Modifier.align(Alignment.TopEnd).size(20.dp).background(colors.primary, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Symbol(R.drawable.studio_check, null, Modifier.size(14.dp), colors.onPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(label(item.title), style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center, color = if (active) colors.onPrimaryContainer else colors.onSurface)
            }
        }
    }
}

@Composable
private fun TemplatePreview(template: ProjectTemplate) {
    val colors = MaterialTheme.colorScheme
    if (template == ProjectTemplate.NO_ACTIVITY) {
        Symbol(R.drawable.studio_folder_open, null, Modifier.size(60.dp), colors.onSurfaceVariant)
        return
    }
    val frame = RoundedCornerShape(7.dp)
    Box(Modifier.size(58.dp, 100.dp).clip(frame).background(colors.surface)
        .border(2.dp, colors.onSurfaceVariant, frame).padding(4.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(5.dp).background(colors.onSurfaceVariant))
            Box(Modifier.fillMaxWidth().height(10.dp).background(colors.surfaceContainerHighest))
        }
        if (template == ProjectTemplate.BUTTON) {
            Box(Modifier.align(Alignment.Center).size(39.dp, 9.dp).background(colors.primary, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center) {
                Spacer(Modifier.size(16.dp, 2.dp).background(colors.onPrimary))
            }
        } else Spacer(Modifier.align(Alignment.Center).size(18.dp, 2.dp).background(colors.outline))
        Spacer(Modifier.align(Alignment.BottomCenter).size(14.dp, 2.dp).background(colors.outline, CircleShape))
    }
}
