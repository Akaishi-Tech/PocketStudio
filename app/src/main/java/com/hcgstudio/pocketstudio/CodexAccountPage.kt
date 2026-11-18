package com.hcgstudio.pocketstudio

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.IOException
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
            if (c.signingIn) c.cancelLogin()
            close()
        }
    }
    BackHandler(onBack = back)
    val title = when {
        apiForm -> R.string.api_connection
        c.usable || state?.accounts?.isNotEmpty() == true -> R.string.account_and_usage
        else -> R.string.sign_in_to_codex
    }
    Surface(Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
                StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), onClick = back)
            }, windowInsets = WindowInsets(0, 0, 0, 0))
            if (state == null && c.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).widthIn(max = 448.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state != null) {
                    if (apiForm) ApiConnectionForm(c, state, close)
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

@Composable
private fun AccountMethods(c: CodexController, state: CodexAuthState, editApi: () -> Unit) {
    val context = LocalContext.current
    val enabled = !c.accountBusy && !c.working
    val active = state.selected?.takeIf { state.useChatGpt }
    if (state.accounts.isNotEmpty()) {
        val labelCounts = remember(state.accounts) { state.accounts.groupingBy { it.label }.eachCount() }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            state.accounts.forEach { profile ->
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
        if (active.planEnabled) {
            LaunchedEffect(active.id) {
                while (true) {
                    c.restoreQuota()
                    delay(60_000)
                }
            }
            c.quota.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (c.quota.isNotEmpty()) Text(stringResource(R.string.updated) + DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(Date(c.quotaAsOf)), style = MaterialTheme.typography.bodySmall)
        } else Text(stringResource(R.string.this_account_has_not_authorized_plan_access))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManageChatGptUsage(c)
            TextButton(c::logout, enabled = enabled) { Text(stringResource(R.string.sign_out)) }
        }
        HorizontalDivider()
    }
    if (active?.planEnabled != true) Text(stringResource(R.string.chatgpt_plan_sign_in_summary),
        style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (c.signingIn) {
            LinearProgressIndicator(Modifier.width(242.dp))
            OutlinedButton(c::cancelLogin) { Text(stringResource(R.string.cancel_sign_in)) }
        } else {
            ContinueWithChatGpt(enabled) {
                c.login(state.selected?.takeUnless { it.signedIn && it.planEnabled }?.id) { uri ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        throw IOException(uiString(R.string.no_browser_available_to_open_this_link))
                    }
                }
            }
        }
        OutlinedButton(editApi, enabled = enabled, modifier = Modifier.width(242.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)) {
            Text(stringResource(if (state.hasApiKey) R.string.api_connection else R.string.use_an_api_key))
        }
    }
}

@Composable
private fun ContinueWithChatGpt(enabled: Boolean, onClick: () -> Unit) {
    val onDark = isSystemInDarkTheme()
    val face = if (onDark) Color.White else Color.Black
    val ink = if (onDark) Color.Black else Color.White
    val font = remember { FontFamily(Font(R.font.siwc_inter_medium, FontWeight.Medium)) }
    Button(onClick, enabled = enabled, modifier = Modifier.width(242.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = face, contentColor = ink)) {
        Symbol(R.drawable.chatgpt_logo, null, Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.continue_with_chatgpt), fontFamily = font, fontWeight = FontWeight.Medium,
            fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    }
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
