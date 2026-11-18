package com.hcgstudio.pocketstudio

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource

@Composable
internal fun ManageChatGptUsage(c: CodexController, primary: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val open = {
        try {
            uriHandler.openUri(CodexAuth.USAGE_URL)
        } catch (e: Exception) {
            c.error = e.localizedMessage ?: uiString(R.string.action_incomplete)
        }
    }
    val label = stringResource(R.string.manage_chatgpt_usage)
    if (primary) Button(open) { Text(label) } else TextButton(open) { Text(label) }
}
