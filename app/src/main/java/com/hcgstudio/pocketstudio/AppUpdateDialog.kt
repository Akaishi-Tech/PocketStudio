package com.hcgstudio.pocketstudio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun AppUpdateDialog(updates: AppUpdates) {
    val context = LocalContext.current
    val installerMissing = stringResource(R.string.system_apk_installer_not_found)
    val permissionDenied = stringResource(R.string.allow_pocket_studio_to_install_unknown_apps_then)
    var pendingInstall by rememberSaveable { mutableStateOf<Intent?>(null) }
    fun openInstaller(intent: Intent) {
        try {
            context.startActivity(intent)
            updates.dismiss()
        } catch (_: ActivityNotFoundException) {
            updates.installerFailed(installerMissing)
        } catch (e: SecurityException) {
            updates.installerFailed(e.localizedMessage ?: permissionDenied)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val intent = pendingInstall
        pendingInstall = null
        if (intent != null) {
            if (context.packageManager.canRequestPackageInstalls()) openInstaller(intent)
            else updates.installerFailed(permissionDenied)
        }
    }
    fun install(intent: Intent) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller(intent)
        else try {
            pendingInstall = intent
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            pendingInstall = null
            updates.installerFailed(permissionDenied)
        } catch (e: SecurityException) {
            pendingInstall = null
            updates.installerFailed(e.localizedMessage ?: permissionDenied)
        }
    }

    if (!updates.visible) return
    val state = updates.state ?: return
    AlertDialog(onDismissRequest = updates::dismiss,
        title = { Text(stringResource(R.string.settings_app_updates)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    AppUpdateState.Checking -> {
                        Text(stringResource(R.string.app_update_checking))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    AppUpdateState.Current -> Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME))
                    AppUpdateState.Unpublished -> Text(stringResource(R.string.app_update_unpublished))
                    is AppUpdateState.Available -> Text(stringResource(R.string.app_update_available,
                        state.release.version, Formatter.formatShortFileSize(context, state.release.size)))
                    is AppUpdateState.Downloading -> {
                        if (state.percent < 100) {
                            Text(stringResource(R.string.app_update_downloading, state.release.version, state.percent))
                            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(stringResource(R.string.app_update_verifying))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is AppUpdateState.Ready -> Text(stringResource(R.string.app_update_ready, state.release.version))
                    is AppUpdateState.Failed -> Text(state.message)
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateState.Available -> TextButton({ updates.download(state.release) }) { Text(stringResource(R.string.download)) }
                is AppUpdateState.Ready -> TextButton({ install(state.intent) }, enabled = pendingInstall == null) {
                    Text(stringResource(R.string.app_update_install))
                }
                is AppUpdateState.Failed -> TextButton({ updates.check(automatic = false) }) { Text(stringResource(R.string.app_update_retry)) }
                AppUpdateState.Checking, is AppUpdateState.Downloading ->
                    TextButton(updates::dismiss) { Text(stringResource(R.string.cancel)) }
                else -> TextButton(updates::dismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            when (state) {
                is AppUpdateState.Available, is AppUpdateState.Ready ->
                    TextButton(updates::dismiss) { Text(stringResource(R.string.update_later)) }
                is AppUpdateState.Failed -> TextButton(updates::dismiss) { Text(stringResource(R.string.close)) }
                else -> Unit
            }
        }
    )
}
