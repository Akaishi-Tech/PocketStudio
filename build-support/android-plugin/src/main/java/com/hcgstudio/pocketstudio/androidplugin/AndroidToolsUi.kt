package com.hcgstudio.pocketstudio.androidplugin

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironmentUi
import com.hcgstudio.pocketstudio.plugin.ProjectWizardHost
import com.hcgstudio.pocketstudio.plugin.RemoteConnection
import com.hcgstudio.pocketstudio.plugin.GradleTask
import java.io.File

internal class AndroidToolsUi(private val environment: AndroidEnvironment, private val plugin: Context) : AndroidEnvironmentUi {
    @Composable override fun projectTypeLabel() = localizedPluginContext(plugin).getString(R.string.wizard_android_project)

    @Composable override fun NewProject(host: ProjectWizardHost, back: () -> Unit) =
        AndroidProjectWizard(host, environment, plugin, back)

    @Composable override fun SdkManager(dismiss: () -> Unit) = PluginSdkManager(environment, plugin, dismiss)

    @Composable override fun BuildSdkDialog(selected: String?, select: (String?) -> Unit,
                                            report: (Throwable) -> Unit, dismiss: () -> Unit) =
        PluginBuildSdkDialog(environment, plugin, selected, select, report, dismiss)

    @Composable override fun RemoteDevices(enabled: Boolean, usb: () -> Unit, network: (RemoteConnection) -> Unit) =
        RemoteDeviceList(localizedPluginContext(plugin), enabled, usb, network)

    @Composable override fun RemoteDeviceDialog(mode: RemoteConnection, connect: (String, Int, String) -> Unit, dismiss: () -> Unit) =
        RemoteDeviceForm(localizedPluginContext(plugin), mode, connect, dismiss)

    @Composable override fun LayoutPreview(files: AndroidEnvironment.LayoutFiles,
                                           xml: String, path: String, dismiss: () -> Unit) =
        AndroidLayoutPreview(plugin, files, xml, path, dismiss)

    @Composable override fun GradleDialog(initialTask: String, start: () -> GradleTask, onDismiss: () -> Unit,
                                         onOutput: (String) -> Unit, onApk: (File) -> Unit, onFinished: () -> Unit) =
        PluginGradleDialog(environment, plugin, initialTask, start, onDismiss, onOutput, onApk, onFinished)
}

@Composable
internal fun localizedPluginContext(plugin: Context): Context {
    val configuration = LocalConfiguration.current
    return remember(plugin, configuration) { plugin.createConfigurationContext(configuration) }
}

@Composable
internal fun pluginIcon(context: Context, id: Int): ImageVector =
    remember(context, id) { ImageVector.vectorResource(context.theme, context.resources, id) }

@Composable
internal fun PluginIconButton(context: Context, id: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled) { Icon(pluginIcon(context, id), label, Modifier.size(22.dp)) }
}
