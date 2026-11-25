package ai.closepaw.qa

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryStore
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.PermissionsAdvancedSettingsPage
import ai.closepaw.ui.settings.SettingsSheet
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Render the real SettingsSheet with sensible defaults; callers override what they need.
 * Used by navigation tests (S1-S4) that exercise the full sheet.
 */
@Composable
internal fun TestSettingsSheet(
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val memoryStore = remember(context) {
        MemoryStore(java.io.File(context.cacheDir, "qa-memory-${System.nanoTime()}"))
    }
    val gate = remember(context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        MemoryEditGate(kotlinx.coroutines.flow.MutableStateFlow(null), scope)
    }
    ClosePawTheme {
        SettingsSheet(
            perceptionMode = "accessibility_only",
            onPerceptionModeChange = {},
            debugMode = false,
            onDebugModeChange = {},
            traceEnabled = false,
            onTraceEnabledChange = {},
            browserScriptEnabled = false,
            onBrowserScriptEnabledChange = {},
            isAccessibilityEnabled = true,
            isOverlayEnabled = true,
            onAccessibilityClick = {},
            onOverlayClick = {},
            platformMode = PlatformMode.ACCESSIBILITY,
            effectivePlatformMode = null,
            onPlatformModeChange = {},
            onDismiss = onDismiss,
            memoryStore = memoryStore,
            memoryEditGate = gate,
        )
    }
}

/** Render the Permissions page with caller-controlled trace toggle state. */
@Composable
internal fun TestPermissionsPage(
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit = {},
) {
    ClosePawTheme {
        PermissionsAdvancedSettingsPage(
            isAccessibilityEnabled = true,
            isOverlayEnabled = true,
            onAccessibilityClick = {},
            onOverlayClick = {},
            debugMode = false,
            onDebugModeChange = {},
            traceEnabled = traceEnabled,
            onTraceEnabledChange = onTraceEnabledChange,
            onBack = {},
            onClose = {},
        )
    }
}
