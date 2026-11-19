package com.hcgstudio.pocketstudio.androidplugin

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PluginBuildSdkDialog(environment: AndroidEnvironment, plugin: Context, selected: String?,
                                  select: (String?) -> Unit, report: (Throwable) -> Unit, dismiss: () -> Unit) {
    val resources = localizedPluginContext(plugin)
    var platforms by remember(environment) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(environment) {
        try { platforms = withContext(Dispatchers.IO) { environment.platforms().installed() } }
        catch (e: java.io.IOException) { report(e) }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(resources.getString(R.string.build_sdk)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                (listOf<String?>(null) + platforms).forEach { api ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == api,
                        role = Role.RadioButton, onClick = {
                            select(api)
                            dismiss()
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == api, null)
                        Spacer(Modifier.width(16.dp))
                        Text(api?.let { "API $it" } ?: resources.getString(R.string.project_sdk))
                    }
                }
            }
        }, confirmButton = { TextButton(dismiss) { Text(resources.getString(R.string.close)) } })
}
