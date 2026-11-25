package ai.closepaw.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.PlatformMode

class AppSettingsState(
    private val store: AppSettingsStore,
) {
    companion object {
        fun create(context: Context) = AppSettingsState(AppSettingsStore(context.applicationContext))
    }

    var debugMode by mutableStateOf(AppSettingsStore.DEFAULT_DEBUG_MODE)
        private set
    var perceptionMode by mutableStateOf(AppSettingsStore.DEFAULT_PERCEPTION_MODE)
        private set
    var platformMode by mutableStateOf(AppSettingsStore.DEFAULT_PLATFORM_MODE)
        private set
    var traceEnabled by mutableStateOf(AppSettingsStore.DEFAULT_TRACE_ENABLED)
        private set
    var browserScriptEnabled by mutableStateOf(AppSettingsStore.DEFAULT_BROWSER_SCRIPT_ENABLED)
        private set
    var approvalMode by mutableStateOf(AppSettingsStore.DEFAULT_APPROVAL_MODE)
        private set

    fun load() {
        val settings = store.load()
        debugMode = settings.debugMode
        perceptionMode = settings.perceptionMode
        platformMode = settings.platformMode
        traceEnabled = settings.traceEnabled
        browserScriptEnabled = settings.browserScriptEnabled
        approvalMode = settings.approvalMode
    }

    fun updateDebugMode(value: Boolean) {
        debugMode = value
        store.saveDebugMode(value)
    }

    fun updateTraceEnabled(value: Boolean) {
        traceEnabled = value
        store.saveTraceEnabled(value)
    }

    fun updateBrowserScriptEnabled(value: Boolean) {
        browserScriptEnabled = value
        store.saveBrowserScriptEnabled(value)
    }

    fun updatePerceptionMode(value: String) {
        perceptionMode = value
        store.savePerceptionMode(value)
    }

    fun updatePlatformMode(value: PlatformMode) {
        platformMode = value
        store.savePlatformMode(value)
    }

    fun updateApprovalMode(value: ApprovalMode) {
        approvalMode = value
        store.saveApprovalMode(value)
    }
}
