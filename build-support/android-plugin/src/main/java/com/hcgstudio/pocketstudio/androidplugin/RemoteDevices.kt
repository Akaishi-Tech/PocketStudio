package com.hcgstudio.pocketstudio.androidplugin

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hcgstudio.pocketstudio.plugin.RemoteConnection

@Composable
internal fun RemoteDeviceList(strings: Context, enabled: Boolean, usb: () -> Unit, network: (RemoteConnection) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.getString(R.string.other_devices), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                ConnectionRow(strings, R.drawable.studio_usb, R.string.connect_usb, enabled, usb)
                ConnectionRow(strings, R.drawable.studio_wifi, R.string.pair_over_wi_fi, enabled) {
                    network(RemoteConnection.PAIR)
                }
                ConnectionRow(strings, R.drawable.studio_link, R.string.connect_paired_device, enabled) {
                    network(RemoteConnection.WIRELESS)
                }
                ConnectionRow(strings, R.drawable.studio_lan, R.string.connect_over_adb_tcp, enabled) {
                    network(RemoteConnection.TCP)
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(strings: Context, icon: Int, label: Int, enabled: Boolean, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = .38f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(pluginIcon(strings, icon), null, Modifier.size(24.dp), tint)
        Text(strings.getString(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) colors.onSurface else tint)
        Icon(pluginIcon(strings, R.drawable.studio_chevron_right), null, Modifier.size(20.dp), tint)
    }
}

@Composable
internal fun RemoteDeviceForm(strings: Context, mode: RemoteConnection, connect: (String, Int, String) -> Unit, dismiss: () -> Unit) {
    val host = LocalContext.current
    val prefs = remember(host) { host.getSharedPreferences("studio", Context.MODE_PRIVATE) }
    val tcp = mode == RemoteConnection.TCP
    val pair = mode == RemoteConnection.PAIR
    val hostKey = if (tcp) "adbHost" else "wirelessHost"
    var address by rememberSaveable(mode) { mutableStateOf(prefs.getString(hostKey, "").orEmpty()) }
    var port by rememberSaveable(mode) {
        mutableStateOf(if (tcp) "5555" else if (pair) "" else prefs.getString("wirelessPort", "").orEmpty())
    }
    var code by remember(mode) { mutableStateOf("") }
    val title = when (mode) {
        RemoteConnection.TCP -> R.string.connect_over_adb_tcp
        RemoteConnection.PAIR -> R.string.pair_wireless_device
        RemoteConnection.WIRELESS -> R.string.connect_paired_device
    }
    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(onDismissRequest = dismiss, title = { Text(strings.getString(title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(strings.getString(R.string.host_or_ip_address)) })
            OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(strings.getString(if (pair) R.string.pairing_port else R.string.connection_port)) })
            if (pair) OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                label = { Text(strings.getString(R.string.six_digit_pairing_code)) })
        }
    }, confirmButton = {
        TextButton(enabled = address.isNotBlank() && parsedPort != null && (!pair || code.matches(Regex("[0-9]{6}"))),
            onClick = {
                val endpoint = address.trim()
                prefs.edit().apply {
                    putString(hostKey, endpoint)
                    if (mode == RemoteConnection.WIRELESS) putString("wirelessPort", port)
                }.apply()
                connect(endpoint, checkNotNull(parsedPort), code)
            }) { Text(strings.getString(if (pair) R.string.pair else R.string.connect)) }
    }, dismissButton = { TextButton(dismiss) { Text(strings.getString(R.string.cancel)) } })
}
