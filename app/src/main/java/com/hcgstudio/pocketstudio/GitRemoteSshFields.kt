package com.hcgstudio.pocketstudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.transport.URIish
import java.io.IOException

internal fun isGitSshUrl(value: String): Boolean =
    runCatching { GitRemoteSsh.matches(URIish(value.trim())) }.getOrDefault(false)

internal class GitSshFiles {
    var key by mutableStateOf<Uri?>(null)
    var hosts by mutableStateOf<Uri?>(null)
    val ready get() = key != null && hosts != null

    fun load(context: Context, passphrase: String): GitRemoteSsh {
        fun read(uri: Uri?, label: Int, limit: Int): ByteArray {
            val name = uiString(label)
            val source = uri?.let { context.contentResolver.openInputStream(it) }
                ?: throw IOException(uiString(R.string.git_remote_read_error, name))
            return source.use {
                // Bound provider reads before allocating/copying user-supplied key material.
                val bytes = ByteArray(limit + 1)
                try {
                    var count = 0
                    while (count < bytes.size) {
                        val size = it.read(bytes, count, bytes.size - count)
                        if (size < 0) break
                        count += size
                    }
                    if (count > limit) throw IOException(uiString(R.string.git_remote_large_file, name))
                    bytes.copyOf(count)
                } finally { bytes.fill(0) }
            }
        }
        val known = read(hosts, R.string.git_remote_known_hosts, 1024 * 1024)
        return GitRemoteSsh.load(read(key, R.string.git_remote_key, 128 * 1024), known, passphrase.toByteArray())
    }
}

@Composable
internal fun GitSshFields(files: GitSshFiles, passphrase: String, onPassphrase: (String) -> Unit, enabled: Boolean) {
    GitSshDocument(R.string.git_remote_key, files.key, enabled) { files.key = it }
    GitSshDocument(R.string.git_remote_known_hosts, files.hosts, enabled) { files.hosts = it }
    OutlinedTextField(passphrase, onPassphrase, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        label = { Text(stringResource(R.string.git_remote_passphrase)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation())
}

@Composable
private fun GitSshDocument(labelId: Int, uri: Uri?, enabled: Boolean, select: (Uri) -> Unit) {
    val label = stringResource(labelId)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
        if (result != null) select(result)
    }
    val context = LocalContext.current
    var name by remember(uri) { mutableStateOf("") }
    LaunchedEffect(uri) {
        if (uri != null) name = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        }
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(if (name.isEmpty()) label else "$label · $name", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
