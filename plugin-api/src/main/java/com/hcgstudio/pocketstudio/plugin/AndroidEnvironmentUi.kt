package com.hcgstudio.pocketstudio.plugin

import androidx.compose.runtime.Composable
import java.io.File

/** Project tools shown in the host's current composition and theme. */
interface AndroidEnvironmentUi {
    @Composable fun RemoteDevices(enabled: Boolean, usb: () -> Unit, network: (RemoteConnection) -> Unit)
    @Composable fun RemoteDeviceDialog(mode: RemoteConnection, connect: (String, Int, String) -> Unit, dismiss: () -> Unit)
    @Composable fun projectTypeLabel(): String
    @Composable fun NewProject(host: ProjectWizardHost, back: () -> Unit)
    @Composable fun SdkManager(dismiss: () -> Unit)
    @Composable fun BuildSdkDialog(selected: String?, select: (String?) -> Unit, report: (Throwable) -> Unit, dismiss: () -> Unit)
    @Composable fun LayoutPreview(files: AndroidEnvironment.LayoutFiles, xml: String, path: String, dismiss: () -> Unit)
    @Composable fun GradleDialog(initialTask: String, start: () -> GradleTask, onDismiss: () -> Unit,
                                onOutput: (String) -> Unit, onApk: (File) -> Unit, onFinished: () -> Unit)
}

enum class RemoteConnection { TCP, PAIR, WIRELESS }
