@file:JvmName("BuildSdk")

package com.hcgstudio.pocketstudio

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hcgstudio.pocketstudio.build.SdkPlatforms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Project.sdkKey() = "compileSdk:${tree ?: root.uri}"
internal fun selectedBuildSdk(project: Project): String? =
    project.context.getSharedPreferences("studio", Context.MODE_PRIVATE).getString(project.sdkKey(), null)

@Composable
internal fun BuildSdkDialog(w: Workspace, dismiss: () -> Unit) {
    val project = w.project ?: return
    var platforms by remember(project) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(project) {
        try { platforms = withContext(Dispatchers.IO) { SdkPlatforms(w.app).installed() } }
        catch (e: java.io.IOException) { w.report(e) }
    }
    val selected = remember(project) { selectedBuildSdk(project) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.build_sdk)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                (listOf<String?>(null) + platforms).forEach { api ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == api,
                        role = Role.RadioButton, onClick = {
                            project.context.getSharedPreferences("studio", Context.MODE_PRIVATE).edit()
                                .putString(project.sdkKey(), api).apply()
                            dismiss()
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == api, null)
                        Spacer(Modifier.width(16.dp))
                        Text(api?.let { "API $it" } ?: stringResource(R.string.project_sdk))
                    }
                }
            }
        }, confirmButton = { TextButton(dismiss) { Text(stringResource(R.string.close)) } })
}
