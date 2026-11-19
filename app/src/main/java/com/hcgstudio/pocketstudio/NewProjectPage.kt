package com.hcgstudio.pocketstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun NewProjectPage(w: Workspace, openStore: () -> Unit) {
    var selectedAndroid by rememberSaveable { mutableStateOf(false) }
    val android = w.androidEnvironment
    if (selectedAndroid && android != null) {
        android.ui().NewProject(w) { selectedAndroid = false }
        return
    }
    val close = { w.newProjectVisible.value = false }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                StudioIconButton(R.drawable.studio_close, stringResource(R.string.cancel), onClick = close)
                Text(stringResource(R.string.wizard_project_type), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
            if (android != null) {
                ListItem(
                    modifier = Modifier.widthIn(max = 660.dp).fillMaxWidth()
                        .clickable(role = Role.Button) { selectedAndroid = true },
                    leadingContent = { Symbol(R.drawable.studio_android, null) },
                    headlineContent = { Text(android.ui().projectTypeLabel()) },
                    trailingContent = { Symbol(R.drawable.studio_chevron_right, null) }
                )
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.wizard_install_environment), textAlign = TextAlign.Center)
                    Button(openStore) { Text(stringResource(R.string.plugin_store)) }
                }
            }
        }
    }
}
