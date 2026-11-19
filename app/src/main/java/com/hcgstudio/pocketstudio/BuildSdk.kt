@file:JvmName("BuildSdk")

package com.hcgstudio.pocketstudio

import android.content.Context
import androidx.compose.runtime.Composable

private fun Project.sdkKey() = "compileSdk:${tree ?: root.uri}"
internal fun selectedBuildSdk(project: Project): String? =
    project.context.getSharedPreferences("studio", Context.MODE_PRIVATE).getString(project.sdkKey(), null)

@Composable
internal fun BuildSdkDialog(w: Workspace, dismiss: () -> Unit) {
    val project = w.project ?: return
    w.androidEnvironment?.ui()?.BuildSdkDialog(selectedBuildSdk(project), { api ->
        project.context.getSharedPreferences("studio", Context.MODE_PRIVATE).edit().putString(project.sdkKey(), api).apply()
    }, w::report, dismiss)
}
