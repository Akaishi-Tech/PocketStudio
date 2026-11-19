package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.agent.AgentSession
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource

@Composable
internal fun ManageAgentUsage(c: AgentSession, primary: Boolean = false) {
    val url = c.usageUrl ?: return
    val uriHandler = LocalUriHandler.current
    val open = {
        try {
            uriHandler.openUri(url)
        } catch (e: Exception) {
            c.error = e.localizedMessage ?: uiString(R.string.action_incomplete)
        }
    }
    val label = stringResource(R.string.manage_agent_usage)
    if (primary) Button(open) { Text(label) } else TextButton(open) { Text(label) }
}
