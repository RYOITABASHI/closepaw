package ai.closepaw.app

import ai.closepaw.BuildConfig
import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.mcp.McpBackend
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpHttpServer
import ai.closepaw.mcp.McpRunRegistry
import ai.closepaw.mcp.McpSettingsStore
import ai.closepaw.onboarding.OnboardingStore
import ai.closepaw.update.SelfUpdate
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object McpRunStatus {
    fun of(kind: ShellyRunState.Finished.Kind): McpRunRegistry.Status = when (kind) {
        ShellyRunState.Finished.Kind.DONE -> McpRunRegistry.Status.COMPLETED
        ShellyRunState.Finished.Kind.STOPPED -> McpRunRegistry.Status.CANCELLED
        ShellyRunState.Finished.Kind.FAILED -> McpRunRegistry.Status.FAILED
    }

    /** Records a background run's terminal state for [handle] (first report wins). */
    fun complete(registry: McpRunRegistry, handle: String, finished: ShellyRunState.Finished): Boolean =
        registry.complete(handle, of(finished.kind), ShellyResultCallback.truncateSummary(finished.message))

    /** Message returned to the MCP client for a refused start. */
    fun refusalMessage(block: ShellyBackgroundRun.Block): String = when (block) {
        ShellyBackgroundRun.Block.ACCESSIBILITY_OFF -> "ClosePaw's accessibility service is not bound"
        ShellyBackgroundRun.Block.ONBOARDING_REQUIRED -> "ClosePaw setup is not finished"
        ShellyBackgroundRun.Block.OVERLAY_PERMISSION_MISSING -> "ClosePaw lacks the display-over-other-apps permission"
        ShellyBackgroundRun.Block.CREDENTIALS_MISSING -> "No credentials for ClosePaw's selected model"
        ShellyBackgroundRun.Block.BUSY -> "ClosePaw is busy with another task"
        ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE ->
            "ClosePaw's virtual display is unavailable (background execution is not ready)"
    }
}

/** Service readiness and result callbacks for externally driven sessions. */
internal class AgentServiceMcpBackend(
    private val app: Context,
    private val adbChannel: AdbShellChannel,
) : McpBackend {
    @Volatile var brainStopHandler: ((String) -> Boolean)? = null

    internal fun reportExternalRefusal(
        correlation: ShellyBackgroundRun.ResultCorrelation,
        message: String?,
    ): Boolean {
        return try {
            val payload = ShellyPendingResult(correlation).claimFailed(message, System.currentTimeMillis())
                ?: return false
            ShellyResultCallback.send(app, payload, logRequestId = false)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "External refusal callback failed: ${e.javaClass.simpleName}")
            false
        }
    }

    internal fun sendBrainResult(payload: ShellyResultCallback.Payload?) {
        payload ?: return
        try {
            ShellyResultCallback.send(app, payload, logRequestId = false)
        } catch (e: Throwable) {
            Log.e(TAG, "External session callback failed: ${e.javaClass.simpleName}")
        }
    }

    internal fun externalPrecheck(): (AgentService) -> ShellyBackgroundRun.Decision {
        val onboardingCompleted = OnboardingStore(app).isCompleted
        val canDrawOverlays = Settings.canDrawOverlays(app)
        val virtualDisplayReady = probeVirtualDisplayReady(app, adbChannel)
        return { service ->
            ShellyBackgroundRun.precheck(
                accessibilityConnected = AgentService.instance === service,
                onboardingCompleted = onboardingCompleted,
                canDrawOverlays = canDrawOverlays,
                // The external driver never calls ClosePaw's LLM.
                credentialsMissing = false,
                sessionBusy = service.isSessionBusy(),
                virtualDisplayReady = virtualDisplayReady,
                allowMainDisplay = false,
            )
        }
    }

    override fun stopRun(runHandle: String): Boolean = brainStopHandler?.invoke(runHandle) == true

    override fun status(): McpBackend.Status {
        val bound = AgentService.instance != null
        val settingOn = accessibilitySettingOn(app)
        return McpBackend.Status(
            version = BuildConfig.VERSION_NAME,
            virtualDisplayReady = probeVirtualDisplayReady(app, adbChannel),
            accessibilityReady = bound,
            accessibilitySettingOn = settingOn,
        )
    }

    companion object {
        private const val TAG = "ClosePawMcp"
    }
}

/**
 * Owns the MCP HTTP server. The server runs only while the settings toggle is ON (default OFF):
 * [sync] is called when AgentService is created or connects, and whenever the toggle changes.
 * Call off the main thread (Keystore-backed token read, socket bind).
 */
internal object McpServerController {
    private var adbChannel: AdbShellChannel? = null
    private const val TAG = "ClosePawMcp"
    private const val AUDIT_TAG = "ClosePawMcpAudit"

    sealed interface State {
        data object Off : State
        data class Running(val port: Int) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val registry = McpRunRegistry()
    @Volatile internal var brainSessions: AgentServiceBrainSessions? = null
        private set

    @Volatile private var token: String? = null
    private var server: McpHttpServer? = null
    private var handler: McpHandler? = null
    private var maintenance: McpMaintenance? = null

    @Synchronized
    fun sync(context: Context) {
        val app = context.applicationContext
        val runStore = RunRecoveryStore.get(app)
        val store = McpSettingsStore(app)
        if (!store.isEnabled()) {
            stopLocked()
            return
        }
        if (server?.isRunning == true) return
        token = store.tokenOrCreate()
        val channel = adbChannel ?: AdbShellChannel(app).also { adbChannel = it }
        val worker = maintenance ?: McpMaintenance(app, channel, runStore).also {
            maintenance = it
            it.start()
        }
        val backend = AgentServiceMcpBackend(app, channel)
        val brains = brainSessions ?: AgentServiceBrainSessions({ AgentService.instance }, backend, runStore)
            .also { brainSessions = it }
        brains.updateBackend(backend)
        backend.brainStopHandler = brains::stop
        val h = McpHandler(
            expectedToken = { token },
            registry = registry,
            backend = backend,
            audit = { line -> Log.i(AUDIT_TAG, line) },
            serverVersion = BuildConfig.VERSION_NAME,
            adbStatus = {
                val ports = channel.portStatus()
                McpHandler.AdbStatus(
                    paired = channel.paired,
                    connected = channel.isConnected(),
                    lastPort = ports.lastPort,
                    lastSuccessfulAt = ports.lastSuccessfulAt,
                    discoveredPort = ports.discoveredPort,
                    discoveredAt = ports.discoveredAt,
                    discoverySource = ports.discoverySource,
                )
            },
            selfUpdate = SelfUpdate.mcpHook(app, channel),
            typedOps = McpDeviceOps(app) { line -> Log.i(AUDIT_TAG, line) }::call,
            sessionOps = brains::handle,
            sessionResults = brains,
            sessionStartRefusal = brains::reportRefusedStart,
            recoveryStatus = worker::status,
        )
        val s = McpHttpServer(h, onError = { Log.w(TAG, it) })
        try {
            s.start()
            server = s
            handler = h
            _state.value = State.Running(s.boundPort)
            Log.i(TAG, "MCP server listening on 0.0.0.0:${s.boundPort}")
        } catch (e: IOException) {
            Log.w(TAG, "MCP server failed to start: ${e.javaClass.simpleName}")
            _state.value = State.Failed(e.javaClass.simpleName)
        }
    }

    @Synchronized
    fun checkRecovery() {
        maintenance?.checkRecovery()
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        McpSettingsStore(context.applicationContext).setEnabled(enabled)
        sync(context)
        if (enabled) {
            McpRecoveryService.ensureStarted(context, "settings_enabled")
        } else {
            context.stopService(android.content.Intent(context, McpRecoveryService::class.java))
        }
    }

    /** Current token (created on first use), for the settings screen. */
    @Synchronized
    fun tokenForDisplay(context: Context): String = McpSettingsStore(context.applicationContext).tokenOrCreate()

    /** New token; the old one is rejected from the next request on, and all sessions are dropped. */
    @Synchronized
    fun regenerateToken(context: Context): String {
        val fresh = McpSettingsStore(context.applicationContext).regenerateToken()
        token = fresh
        handler?.clearSessions()
        Log.i(AUDIT_TAG, "mcp token regenerated")
        return fresh
    }

    private fun stopLocked() {
        maintenance?.stop()
        maintenance = null
        server?.stop()
        server = null
        handler = null
        token = null
        if (_state.value != State.Off) Log.i(TAG, "MCP server stopped")
        _state.value = State.Off
    }
}
