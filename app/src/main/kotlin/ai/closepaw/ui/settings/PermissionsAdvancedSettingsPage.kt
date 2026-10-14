package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import ai.closepaw.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun PermissionsAdvancedSettingsPage(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = stringResource(R.string.permissions_advanced_settings_page_title), onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg)
        ) {
            SettingsSection(title = stringResource(R.string.permissions_advanced_settings_page_section_permissions)) {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
                    SettingsRow(
                        icon = Icons.Outlined.Settings,
                        title = stringResource(R.string.permissions_advanced_settings_page_accessibility_service),
                        isEnabled = isAccessibilityEnabled,
                        onClick = onAccessibilityClick
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Layers,
                        title = stringResource(R.string.permissions_advanced_settings_page_overlay_permission),
                        isEnabled = isOverlayEnabled,
                        onClick = onOverlayClick
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = stringResource(R.string.permissions_advanced_settings_page_section_debug)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.permissions_advanced_settings_page_debug_mode),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.permissions_advanced_settings_page_debug_mode_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = debugMode,
                            onCheckedChange = onDebugModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            DataStorageSection(
                traceEnabled = traceEnabled,
                onTraceEnabledChange = onTraceEnabledChange
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.permissions_advanced_settings_page_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Fleuron()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private const val TRACE_DIR = "inspection-trace"
private const val SESSIONS_DIR = "sessions"

private sealed interface ClearDataState {
    data object Idle : ClearDataState
    data object Clearing : ClearDataState
    data object Cleared : ClearDataState
    data class Failed(@StringRes val messageRes: Int, @StringRes val labelRes: Int) : ClearDataState
}

@Composable
private fun DataStorageSection(
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var traceClearState by remember { mutableStateOf<ClearDataState>(ClearDataState.Idle) }
    var sessionClearState by remember { mutableStateOf<ClearDataState>(ClearDataState.Idle) }
    var showClearTracesConfirm by remember { mutableStateOf(false) }
    var showClearSessionsConfirm by remember { mutableStateOf(false) }

    fun clearTraces() {
        if (traceClearState is ClearDataState.Clearing) return
        traceClearState = ClearDataState.Clearing
        scope.launch {
            traceClearState = withContext(Dispatchers.IO) {
                clearDirectory(context.getExternalFilesDir(TRACE_DIR), labelRes = R.string.permissions_advanced_settings_page_label_traces)
            }
        }
    }

    fun clearSessions() {
        if (sessionClearState is ClearDataState.Clearing) return
        sessionClearState = ClearDataState.Clearing
        scope.launch {
            sessionClearState = withContext(Dispatchers.IO) {
                clearDirectory(File(context.filesDir, SESSIONS_DIR), labelRes = R.string.permissions_advanced_settings_page_label_session_history)
            }
        }
    }

    if (showClearTracesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTracesConfirm = false },
            title = { Text(stringResource(R.string.permissions_advanced_settings_page_clear_traces)) },
            text = { Text(stringResource(R.string.permissions_advanced_settings_page_clear_traces_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearTracesConfirm = false
                        clearTraces()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.permissions_advanced_settings_page_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearTracesConfirm = false }) { Text(stringResource(R.string.permissions_advanced_settings_page_cancel)) }
            }
        )
    }

    if (showClearSessionsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearSessionsConfirm = false },
            title = { Text(stringResource(R.string.permissions_advanced_settings_page_clear_sessions)) },
            text = { Text(stringResource(R.string.permissions_advanced_settings_page_clear_sessions_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSessionsConfirm = false
                        clearSessions()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.permissions_advanced_settings_page_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionsConfirm = false }) { Text(stringResource(R.string.permissions_advanced_settings_page_cancel)) }
            }
        )
    }

    SettingsSection(title = stringResource(R.string.permissions_advanced_settings_page_section_data_storage)) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
            // Trace toggle
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.permissions_advanced_settings_page_session_traces),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.permissions_advanced_settings_page_session_traces_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = traceEnabled,
                            onCheckedChange = onTraceEnabledChange,
                            modifier = Modifier.testTag("qa-session-traces-switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                    if (traceEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = stringResource(R.string.permissions_advanced_settings_page_traces_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.md, vertical = MaterialTheme.closePaw.spacing.sm)
                            )
                        }
                    }
                }
            }

            // Clear traces button
            ClearDataButton(
                label = clearDataButtonLabel(traceClearState, idle = R.string.permissions_advanced_settings_page_clear_traces, clearing = R.string.permissions_advanced_settings_page_clearing_traces, cleared = R.string.permissions_advanced_settings_page_traces_cleared),
                enabled = traceClearState !is ClearDataState.Clearing && traceClearState !is ClearDataState.Cleared,
                onClick = { showClearTracesConfirm = true }
            )
            ClearFailureAlert(state = traceClearState, onRetry = { clearTraces() })

            // Clear session history button
            ClearDataButton(
                label = clearDataButtonLabel(sessionClearState, idle = R.string.permissions_advanced_settings_page_clear_sessions, clearing = R.string.permissions_advanced_settings_page_clearing_sessions, cleared = R.string.permissions_advanced_settings_page_sessions_cleared),
                enabled = sessionClearState !is ClearDataState.Clearing && sessionClearState !is ClearDataState.Cleared,
                onClick = { showClearSessionsConfirm = true }
            )
            ClearFailureAlert(state = sessionClearState, onRetry = { clearSessions() })
        }
    }
}

private fun clearDirectory(directory: File?, @StringRes labelRes: Int): ClearDataState {
    if (directory == null) {
        return ClearDataState.Failed(R.string.permissions_advanced_settings_page_error_access, labelRes)
    }
    return try {
        if (directory.deleteRecursively()) {
            ClearDataState.Cleared
        } else {
            ClearDataState.Failed(R.string.permissions_advanced_settings_page_error_clear, labelRes)
        }
    } catch (e: SecurityException) {
        ClearDataState.Failed(R.string.permissions_advanced_settings_page_error_denied, labelRes)
    }
}

@Composable
private fun clearDataButtonLabel(
    state: ClearDataState,
    @StringRes idle: Int,
    @StringRes clearing: Int,
    @StringRes cleared: Int,
): String = when (state) {
    ClearDataState.Idle,
    is ClearDataState.Failed -> stringResource(idle)
    ClearDataState.Clearing -> stringResource(clearing)
    ClearDataState.Cleared -> stringResource(cleared)
}

@Composable
private fun ClearFailureAlert(state: ClearDataState, onRetry: () -> Unit) {
    val failed = state as? ClearDataState.Failed ?: return
    SettingsAlertCard(
        message = stringResource(failed.messageRes, stringResource(failed.labelRes)),
        tone = AlertTone.Error,
        action = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.permissions_advanced_settings_page_retry))
            }
        },
    )
}

@Composable
private fun ClearDataButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}
