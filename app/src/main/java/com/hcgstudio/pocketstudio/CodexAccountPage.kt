package com.hcgstudio.pocketstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexAccountPage(c: CodexController, close: () -> Unit) {
    var apiForm by rememberSaveable { mutableStateOf(false) }
    val state = c.account
    val back = {
        if (apiForm) {
            c.error = null
            apiForm = false
        } else {
            c.cancelLogin()
            close()
        }
    }
    BackHandler(onBack = back)
    val title = when {
        apiForm -> R.string.api_connection
        c.usable || state?.signedInAccounts?.isNotEmpty() == true -> R.string.account_and_usage
        else -> R.string.sign_in_to_codex
    }
    Surface(Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
                StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), onClick = back)
            }, windowInsets = WindowInsets(0, 0, 0, 0))
            if (state == null && c.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            val accountScroll = rememberScrollState()
            StudioPageTransition(apiForm, { if (it) 1 else 0 }, Modifier.weight(1f).widthIn(max = 448.dp).fillMaxWidth()) { form ->
                Column(Modifier.fillMaxSize().verticalScroll(if (form) rememberScrollState() else accountScroll)
                    .padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state != null) {
                        if (form) ApiConnectionForm(c, state, close)
                        else AccountMethods(c, state) {
                            c.error = null
                            apiForm = true
                        }
                    }
                    c.error?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountMethods(c: CodexController, state: CodexAuthState, editApi: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    if (c.signingIn) {
        val code = c.deviceCode
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (code == null) {
                if (c.replacingDeviceCode) Text(stringResource(R.string.codex_requesting_replacement_code))
                LinearProgressIndicator(Modifier.width(242.dp))
            } else {
                val instructions = if (c.replacingDeviceCode) R.string.codex_enter_replacement_code else R.string.codex_enter_device_code
                Text(stringResource(instructions), style = MaterialTheme.typography.bodyLarge)
                SelectionContainer {
                    Text(code.userCode, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                }
                Button({
                    try {
                        c.error = null
                        uriHandler.openUri(code.verificationUrl)
                    } catch (_: IllegalArgumentException) {
                        c.error = uiString(R.string.no_browser_available_to_open_this_link) + "\n" + code.verificationUrl
                    }
                }) { Text(stringResource(R.string.codex_open_verification_page)) }
            }
            if (c.offerDeviceCode) DeviceCodeButton(c)
            TextButton(c::cancelLogin) { Text(stringResource(R.string.cancel_sign_in)) }
        }
        return
    }
    val enabled = !c.accountBusy && !c.working
    val active = state.selected?.takeIf { state.useChatGpt }
    if (state.signedInAccounts.isNotEmpty()) {
        val labelCounts = remember(state.signedInAccounts) { state.signedInAccounts.groupingBy { it.label }.eachCount() }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            state.signedInAccounts.forEach { profile ->
                val selected = active?.id == profile.id
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled,
                    role = Role.RadioButton, onClick = { if (!selected) c.configure { it.selectAccount(profile.id) } }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected, onClick = null, enabled = enabled)
                    val label = if (labelCounts[profile.label] == 1) profile.label else "${profile.label} · ${profile.id.takeLast(6)}"
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    if (active?.signedIn == true) {
        LaunchedEffect(active.id) {
            while (true) {
                c.restoreQuota()
                delay(60_000)
            }
        }
        c.quota.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (c.quota.isNotEmpty()) Text(stringResource(R.string.updated) + DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT).format(Date(c.quotaAsOf)), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManageChatGptUsage(c)
            TextButton(c::logout, enabled = enabled) { Text(stringResource(R.string.sign_out)) }
        }
        HorizontalDivider()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button({ c.login(uriHandler::openUri) }, enabled = enabled, modifier = Modifier.width(242.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)) {
            Text(stringResource(R.string.codex_cli_sign_in))
        }
        if (c.offerDeviceCode) DeviceCodeButton(c, enabled)
        OutlinedButton(editApi, enabled = enabled, modifier = Modifier.width(242.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)) {
            Text(stringResource(if (state.hasApiKey) R.string.api_connection else R.string.use_an_api_key))
        }
    }
}

@Composable
private fun DeviceCodeButton(c: CodexController, enabled: Boolean = true) {
    TextButton(c::useDeviceCode, enabled = enabled) { Text(stringResource(R.string.codex_use_device_code)) }
}

@Composable
private fun ApiConnectionForm(c: CodexController, state: CodexAuthState, close: () -> Unit) {
    var endpoint by rememberSaveable(state.apiEndpoint) { mutableStateOf(state.apiEndpoint) }
    // Keep the secret out of the Activity's saved-instance-state bundle.
    var key by remember { mutableStateOf("") }
    val enabled = !c.accountBusy && !c.working
    var fieldError by rememberSaveable { mutableStateOf<Int?>(null) }
    val endpointError = fieldError == R.string.invalid_api_endpoint
    val keyError = fieldError?.takeUnless { endpointError }
    val canSave = enabled && (key.isNotBlank() || state.hasApiKey)
    OutlinedTextField(endpoint, { endpoint = it; fieldError = null; c.error = null }, Modifier.fillMaxWidth(),
        singleLine = true, enabled = enabled, label = { Text(stringResource(R.string.api_endpoint)) }, isError = endpointError,
        supportingText = if (endpointError) { { Text(stringResource(R.string.invalid_api_endpoint)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next))
    OutlinedTextField(key, { key = it; fieldError = null; c.error = null }, Modifier.fillMaxWidth(),
        singleLine = true, enabled = enabled, visualTransformation = PasswordVisualTransformation(),
        label = { Text(stringResource(if (state.hasApiKey) R.string.replace_api_key else R.string.api_key)) },
        isError = keyError != null, supportingText = if (keyError != null) { { Text(stringResource(keyError)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done))
    Text(stringResource(R.string.api_connection_disclosure), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            val base = endpoint
            val secret = key
            c.configure(onSaved = close, onInvalid = { fieldError = it.reason }) { it.saveApiConfiguration(base, secret) }
        }, enabled = canSave) { Text(stringResource(R.string.save_api_connection)) }
        if (state.hasApiKey) TextButton({
            c.configure(onSaved = { key = ""; fieldError = null }) { it.removeApiKey() }
        }, enabled = enabled) { Text(stringResource(R.string.remove_key)) }
    }
}
