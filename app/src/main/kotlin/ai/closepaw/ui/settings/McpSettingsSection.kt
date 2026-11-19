package ai.closepaw.ui.settings

import ai.closepaw.R
import ai.closepaw.app.McpServerController
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpSettingsStore
import ai.closepaw.ui.theme.closePaw
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "MCP" settings section: the ClosePaw MCP server toggle (default OFF), its access token with a
 * regenerate button (the old token stops working immediately), and the endpoint hint. Store and
 * server work runs on [Dispatchers.IO] (Keystore-backed token, socket bind).
 */
@Composable
internal fun McpSettingsSection() {
    SettingsSection(title = stringResource(R.string.mcp_section_title)) {
        McpServerRow()
    }
}

@Composable
private fun McpServerRow() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val serverState by McpServerController.state.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    var addresses by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(context) {
        val (on, t, ips) = withContext(Dispatchers.IO) {
            val on = McpSettingsStore(context).isEnabled()
            Triple(on, if (on) McpServerController.tokenForDisplay(context) else null, localIpv4Addresses())
        }
        enabled = on
        token = t
        addresses = ips
    }

    val copiedMessage = stringResource(R.string.mcp_token_copied)
    val regeneratedMessage = stringResource(R.string.mcp_token_regenerated)

    val details: (@Composable ColumnScope.() -> Unit)? = if (!enabled) null else {
        {
            McpServerDetails(
                serverState = serverState,
                token = token,
                addresses = addresses,
                onCopy = { value ->
                    copyToClipboard(context, value)
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                },
                onRegenerate = {
                    scope.launch {
                        token = withContext(Dispatchers.IO) { McpServerController.regenerateToken(context) }
                        Toast.makeText(context, regeneratedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    ToolSettingsCard(
        title = stringResource(R.string.mcp_server_title),
        status = ToolStatusUi(
            label = stringResource(if (enabled) R.string.mcp_server_on_label else R.string.mcp_server_off_label),
            subtitle = stringResource(if (enabled) R.string.mcp_server_on else R.string.mcp_server_off),
            tone = if (enabled) ToolStatusTone.Warning else ToolStatusTone.Neutral,
        ),
        switchChecked = enabled,
        onSwitchChange = { checked ->
            enabled = checked
            scope.launch {
                val t = withContext(Dispatchers.IO) {
                    McpServerController.setEnabled(context, checked)
                    if (checked) McpServerController.tokenForDisplay(context) else null
                }
                token = t
                if (checked) addresses = withContext(Dispatchers.IO) { localIpv4Addresses() }
            }
        },
        expanded = details,
    )
}

@Composable
private fun McpServerDetails(
    serverState: McpServerController.State,
    token: String?,
    addresses: List<String>,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val port = McpHandler.DEFAULT_PORT
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)) {
        Text(
            text = when (serverState) {
                is McpServerController.State.Running -> stringResource(R.string.mcp_server_status_running, serverState.port)
                is McpServerController.State.Failed -> stringResource(R.string.mcp_server_status_failed, serverState.reason)
                McpServerController.State.Off -> stringResource(R.string.mcp_server_status_starting)
            },
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = if (addresses.isEmpty()) {
                stringResource(R.string.mcp_address_hint_no_network, port)
            } else {
                stringResource(
                    R.string.mcp_address_hint,
                    addresses.joinToString(", ") { "http://$it:$port${McpHandler.PATH}" },
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        Text(
            text = stringResource(R.string.mcp_token_label),
            style = MaterialTheme.typography.labelMedium,
        )
        if (token != null) {
            SelectionContainer {
                Text(
                    text = token,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { token?.let(onCopy) }, enabled = token != null) {
                Text(stringResource(R.string.mcp_token_copy))
            }
            Button(onClick = onRegenerate) {
                Text(stringResource(R.string.mcp_token_regenerate))
            }
        }
        Text(
            text = stringResource(R.string.mcp_token_warning),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("ClosePaw MCP token", value) // l10n-ok (clip label, not shown)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    clipboard.setPrimaryClip(clip)
}

/** Non-loopback IPv4 addresses of up interfaces (Wi-Fi / hotspot), for the endpoint hint. */
private fun localIpv4Addresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .distinct()
} catch (e: Exception) {
    emptyList()
}
