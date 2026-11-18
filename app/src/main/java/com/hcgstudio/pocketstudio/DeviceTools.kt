package com.hcgstudio.pocketstudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DeviceToolWindow(
    controller: DeviceController,
    className: String,
    line: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val panelHeight = maxHeight
        val wide = maxWidth >= 640.dp
        Column(
            Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (controller.connected) {
                ConnectedDevice(controller, className, line)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_manager),
                        Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (controller.busy) {
                        TextButton(controller::disconnect) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
            if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (controller.status.isNotEmpty()) {
                Text(controller.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!controller.connected) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        LocalDeviceButton(controller, Modifier.weight(1f))
                        RemoteDeviceList(controller, Modifier.weight(1.35f))
                    }
                } else {
                    LocalDeviceButton(controller, Modifier.fillMaxWidth())
                    RemoteDeviceList(controller, Modifier.fillMaxWidth())
                }
            } else if (controller.output.isNotEmpty() || controller.logging) {
                val scroll = rememberScrollState()
                val settings = LocalStudioSettings.current
                val horizontal = rememberScrollState()
                val wrap = if (settings.consoleSoftWraps) Modifier else Modifier.horizontalScroll(horizontal)
                LaunchedEffect(controller.output, controller.logging, scroll.maxValue) {
                    if (controller.logging) scroll.scrollTo(scroll.maxValue)
                }
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    SelectionContainer {
                        Text(controller.output.ifEmpty { stringResource(R.string.waiting_for_logs) },
                            Modifier.heightIn(min = 96.dp, max = (panelHeight - 156.dp).coerceAtLeast(96.dp))
                                .verticalScroll(scroll).then(wrap).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            fontSize = settings.consoleFontSize.sp, lineHeight = (settings.consoleFontSize * 1.42f).sp,
                            softWrap = settings.consoleSoftWraps)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalDeviceButton(controller: DeviceController, modifier: Modifier) {
    FilledTonalButton(
        onClick = controller::localDevice, enabled = !controller.busy,
        modifier = modifier, shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Symbol(R.drawable.studio_smartphone, null, Modifier.size(24.dp))
        Text(stringResource(R.string.debug_this_device), Modifier.weight(1f).padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium)
        Symbol(R.drawable.studio_chevron_right, null, Modifier.size(20.dp))
    }
}

@Composable
private fun RemoteDeviceList(controller: DeviceController, modifier: Modifier) {
    val enabled = !controller.busy
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.other_devices), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                ConnectionRow(R.drawable.studio_usb, R.string.connect_usb, enabled, controller::chooseUsb)
                ConnectionRow(R.drawable.studio_wifi, R.string.pair_over_wi_fi, enabled) {
                    controller.network(DeviceController.Connection.PAIR)
                }
                ConnectionRow(R.drawable.studio_link, R.string.connect_paired_device, enabled) {
                    controller.network(DeviceController.Connection.WIRELESS)
                }
                ConnectionRow(R.drawable.studio_lan, R.string.connect_over_adb_tcp, enabled) {
                    controller.network(DeviceController.Connection.TCP)
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = .38f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Symbol(icon, null, Modifier.size(24.dp), tint)
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) colors.onSurface else tint)
        Symbol(R.drawable.studio_chevron_right, null, Modifier.size(20.dp), tint)
    }
}

@Composable
private fun ConnectedDevice(controller: DeviceController, className: String, line: Int) {
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.install(uri)
    }
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = colors.primaryContainer) {
            Symbol(R.drawable.studio_smartphone, null, Modifier.padding(10.dp).size(24.dp), colors.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(controller.deviceName, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (controller.attached) {
                Text(controller.process + stringResource(if (controller.paused) R.string.paused else R.string.attached),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Box {
            StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.device_actions)) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun invoke(action: () -> Unit) { menu = false; action() }
                if (controller.paused) {
                    DropdownMenuItem({ Text(stringResource(R.string.switch_process)) }, { invoke(controller::processes) }, enabled = !controller.busy)
                    DropdownMenuItem({ Text(stringResource(if (controller.logging) R.string.stop_logging else R.string.logcat)) }, { invoke(controller::toggleLog) })
                }
                DropdownMenuItem({ Text(stringResource(R.string.install_apk)) }, { invoke {
                    pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                } }, enabled = !controller.busy)
                DropdownMenuItem({ Text(stringResource(R.string.launch_app)) }, { invoke(controller::requestLaunch) }, enabled = !controller.busy)
                DropdownMenuItem({ Text(stringResource(R.string.inspect_screen)) }, { invoke(controller::inspect) }, enabled = !controller.busy)
                DropdownMenuItem({ Text(stringResource(R.string.filter_logs)) }, { invoke(controller::filterLog) }, enabled = !controller.busy)
                DropdownMenuItem({ Text(stringResource(R.string.target_device_shell)) }, { invoke(controller::shell) }, enabled = !controller.busy)
                if (controller.attached) {
                    HorizontalDivider()
                    DropdownMenuItem({ Text(stringResource(R.string.loaded_classes)) }, { invoke { controller.classes(className, line) } }, enabled = !controller.busy)
                    DropdownMenuItem({ Text(stringResource(R.string.set_line_breakpoint)) }, { invoke { controller.breakpoint(className, line) } }, enabled = !controller.busy)
                    if (controller.hasBreakpoints) {
                        DropdownMenuItem({ Text(stringResource(R.string.remove_breakpoint)) }, { invoke(controller::removeBreakpoint) }, enabled = !controller.busy)
                    }
                    DropdownMenuItem({ Text(stringResource(R.string.detach_debugger)) }, { invoke(controller::detach) }, enabled = !controller.busy)
                }
                HorizontalDivider()
                DropdownMenuItem({ Text(stringResource(R.string.disconnect)) }, { invoke(controller::disconnect) })
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (controller.paused) {
            Button(controller::resume, enabled = !controller.busy) {
                Symbol(R.drawable.studio_play_arrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.resume))
            }
            OutlinedButton(controller::stepOver, enabled = !controller.busy) { Text(stringResource(R.string.step_over)) }
            TextButton(controller::stack, enabled = !controller.busy) { Text(stringResource(R.string.call_stack)) }
            TextButton(controller::locals, enabled = !controller.busy) { Text(stringResource(R.string.variables)) }
        } else {
            Button(controller::processes, enabled = !controller.busy) {
                Symbol(R.drawable.studio_bug_report, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (controller.attached) R.string.switch_process else R.string.attach_process))
            }
            OutlinedButton(controller::toggleLog) {
                Text(stringResource(if (controller.logging) R.string.stop_logging else R.string.logcat))
            }
        }
    }
}

@Composable
internal fun DeviceDialogs(controller: DeviceController) {
    val dialog = controller.dialog ?: return
    val dismiss = { controller.dialog = null }
    key(dialog) {
        when (dialog) {
            DeviceDialog.LocalSetup -> LocalDeviceDialog(controller)
            is DeviceDialog.Message -> AlertDialog(onDismissRequest = dismiss, text = { Text(stringResource(dialog.text)) },
                confirmButton = { TextButton(dismiss) { Text(stringResource(R.string.close)) } })
            is DeviceDialog.Form -> {
                val values = remember { dialog.fields.map { it.initial }.toMutableStateList() }
                var error by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = dismiss, title = { Text(stringResource(dialog.title)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dialog.fields.forEachIndexed { index, field ->
                                OutlinedTextField(value = values[index], onValueChange = { values[index] = it; error = "" },
                                    label = { Text(stringResource(field.label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text),
                                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
                            }
                            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    confirmButton = { TextButton({
                        try {
                            dialog.submit(values.toList())
                            if (controller.dialog === dialog) dismiss()
                        } catch (e: IllegalArgumentException) {
                            error = e.localizedMessage ?: uiString(R.string.invalid_input)
                        }
                    }) { Text(stringResource(dialog.action)) } },
                    dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } }
                )
            }
            is DeviceDialog.ListPicker -> AlertDialog(
                onDismissRequest = dismiss, title = { Text(stringResource(dialog.title)) },
                text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                        itemsIndexed(dialog.items) { index, label ->
                            Text(label, Modifier.fillMaxWidth().clickable { dismiss(); dialog.select(index) }
                                .padding(horizontal = 4.dp, vertical = 12.dp))
                        }
                    }
                },
                confirmButton = { TextButton(dismiss) { Text(stringResource(R.string.close)) } }
            )
            is DeviceDialog.Inspection -> InspectorDialog(dialog.snapshot, dismiss)
        }
    }
}

@Composable
private fun LocalDeviceDialog(controller: DeviceController) {
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.localSettings()
    }
    AlertDialog(
        onDismissRequest = controller::disconnect,
        text = { Text(stringResource(R.string.local_pairing_instructions), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    controller.localSettings()
                }
            }) { Text(stringResource(R.string.open_pairing_settings)) }
        },
        dismissButton = { TextButton(controller::disconnect) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun InspectorDialog(snapshot: DeviceInspector.Snapshot, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DeviceInspector.Node?>(null) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (list) uiString(R.string.target_view) else uiString(R.string.inspect_screen)) },
        text = {
            if (list) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(snapshot.nodes) { _, node ->
                        Text(node.details, Modifier.fillMaxWidth().clickable { selected = node; list = false }.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else Column {
                Box(Modifier.fillMaxWidth().fillMaxHeight(.53f).background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.aspectRatio(snapshot.width.toFloat() / snapshot.height)
                        .pointerInput(snapshot) {
                            detectTapGestures { tap ->
                                val x = (tap.x * snapshot.width / size.width).toInt()
                                val y = (tap.y * snapshot.height / size.height).toInt()
                                selected = snapshot.nodes.asSequence().filter { it.area.contains(x, y) }
                                    .minByOrNull { it.area.width().toLong() * it.area.height() }
                            }
                        }) {
                        Image(remember(snapshot) { snapshot.bitmap.asImageBitmap() }, uiString(R.string.target_device_screen),
                            Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        val area = selected?.area
                        if (area != null) Canvas(Modifier.matchParentSize()) {
                            val sx = size.width / snapshot.width
                            val sy = size.height / snapshot.height
                            val origin = Offset(area.left * sx, area.top * sy)
                            val rect = Size(area.width() * sx, area.height() * sy)
                            drawRect(colors.primary.copy(alpha = .25f), origin, rect)
                            drawRect(colors.primary, origin, rect, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                SelectionContainer {
                    Text(selected?.details ?: snapshot.hierarchyError.ifEmpty { uiString(R.string.tap_the_screenshot_to_inspect_a_view) },
                        Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (snapshot.nodes.isNotEmpty()) TextButton({ list = !list }) { Text(if (list) uiString(R.string.back_to_screenshot) else uiString(R.string.view_hierarchy)) }
        }
    )
}
