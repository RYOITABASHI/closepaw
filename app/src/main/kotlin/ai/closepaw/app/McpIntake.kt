package ai.closepaw.app

import ai.closepaw.BuildConfig
import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.mcp.McpBackend
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpHttpServer
import ai.closepaw.mcp.McpMainCall
import ai.closepaw.mcp.McpRunRegistry
import ai.closepaw.mcp.McpSettingsStore
import ai.closepaw.mcp.reconcileLateStart
import ai.closepaw.onboarding.OnboardingStore
import ai.closepaw.update.SelfUpdate
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP intake: the second way (after [ShellyGoalReceiver]) to hand ClosePaw a goal. An MCP client
 * (Muse, Claude Code, Codex) calls `run_goal` on [McpHandler]; this file joins that call to the
 * SAME background-run machinery as Shelly hand-offs:
 *
 *  - preconditions: [ShellyBackgroundRun.precheck] with the same probes as the receiver, main
 *    display fallback never allowed (MCP runs are virtual-display only);
 *  - start: [AgentService.startShellyRun] (virtual display, invisible overlay, IME policy, task
 *    restore/deferral, notification with Stop) with no Shelly correlation, so nothing is sent to
 *    Shelly; its `onTerminal` hook fires from the same report-once paths as the Shelly result;
 *  - stop: [AgentService.stopIntakeRun] -> [AgentService.stopShellyRun]. STOP-ALL (capsule Stop),
 *    the notification's Stop and STOP_AGENT all end the session, which finishes the tracker and
 *    reports the run as cancelled here too.
 */
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

/** [McpBackend] backed by [AgentService]. Main-thread work is posted and awaited. */
internal class AgentServiceMcpBackend(
    private val app: Context,
    private val registry: McpRunRegistry,
    private val maintenance: McpMaintenance,
    private val adbChannel: AdbShellChannel,
) : McpBackend {
    private val main = Handler(Looper.getMainLooper())

    /** Post-and-await on the main looper; abandon/reconcile-on-timeout logic is the pure [McpMainCall]. */
    private val mainCall = McpMainCall(
        post = { main.post(it) },
        onTargetThread = { Looper.myLooper() == Looper.getMainLooper() },
        timeoutMs = MAIN_TIMEOUT_MS,
    )

    /** runHandle -> the start's report-once guard (identifies the run inside AgentService). */
    private val guards = ConcurrentHashMap<String, ShellyPendingResult>()

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

    override fun startRun(goal: String): McpBackend.StartResult {
        val probes = try {
            probe()
        } catch (e: Throwable) {
            Log.e(TAG, "probe failed: ${e.javaClass.simpleName}")
            return McpBackend.StartResult.Refused("PROBE_FAILED", "ClosePaw could not check its readiness")
        }
        return mainCall.call(reconcile = ::reconcileTimedOutStart) { startOnMain(goal, probes) }
            ?: McpBackend.StartResult.Refused("TIMEOUT", "ClosePaw did not respond in time")
    }

    private fun startOnMain(goal: String, probes: Probes): McpBackend.StartResult {
        val service = AgentService.instance
        val token = Any()
        val claimed = BrainSessionAdmission.tryClaimInternal(token)
        try {
            val decision = ShellyBackgroundRun.precheck(
                accessibilityConnected = service != null,
                onboardingCompleted = probes.onboardingCompleted,
                canDrawOverlays = probes.canDrawOverlays,
                credentialsMissing = probes.credentialsMissing,
                sessionBusy = service?.isInternalIntakeBusy() == true || !claimed,
                virtualDisplayReady = probes.virtualDisplayReady,
                allowMainDisplay = false,
            )
            return when (decision) {
                is ShellyBackgroundRun.Decision.Blocked ->
                    McpBackend.StartResult.Refused(decision.block.name, McpRunStatus.refusalMessage(decision.block))
                is ShellyBackgroundRun.Decision.Start -> {
                    val handle = registry.issue()
                    val guard = service!!.startShellyRun(
                        goal, decision.platformMode, origin = ShellyRunOrigin.MCP,
                    ) { finished ->
                        McpRunStatus.complete(registry, handle, finished)
                        guards.remove(handle)
                    }
                    if (guard == null) {
                        val failed = registry.snapshot(handle)
                        McpBackend.StartResult.Refused("START_FAILED", failed?.summary?.ifBlank { null } ?: "ClosePaw could not start the task")
                    } else {
                        guards[handle] = guard
                        registry.markRunning(handle)
                        McpBackend.StartResult.Started(handle)
                    }
                }
            }
        } finally {
            BrainSessionAdmission.releaseInternal(token)
        }
    }

    override fun stopRun(runHandle: String): Boolean {
        val guard = guards[runHandle] ?: return brainStopHandler?.invoke(runHandle) == true
        return mainCall.call<Boolean> { AgentService.instance?.stopIntakeRun(guard) == true } == true
    }

    override fun status(): McpBackend.Status {
        val bound = AgentService.instance != null
        val settingOn = accessibilitySettingOn(app)
        if (!settingOn || !bound) maintenance.checkAccessibility()
        maintenance.rediscover()
        maintenance.checkShizuku()
        return McpBackend.Status(
            version = BuildConfig.VERSION_NAME,
            virtualDisplayReady = probeVirtualDisplayReady(app, adbChannel),
            accessibilityReady = bound,
            accessibilitySettingOn = settingOn,
        )
    }

    private class Probes(
        val onboardingCompleted: Boolean,
        val canDrawOverlays: Boolean,
        val credentialsMissing: Boolean,
        val virtualDisplayReady: Boolean,
    )

    /** Same probes as [ShellyGoalReceiver] (minus Shelly bookkeeping). Off the main thread. */
    private fun probe(): Probes {
        val settings = AppSettingsState.create(app).also { it.load() }
        val credentialsMissing = try {
            findMissingCloudKeys(
                settings,
                ModelCatalogRepositoryHolder.get(app).catalog.value,
                AuthStoreHolder.get(app),
            ).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "credential check failed: ${e.javaClass.simpleName}")
            true
        }
        return Probes(
            onboardingCompleted = OnboardingStore(app).isCompleted,
            canDrawOverlays = Settings.canDrawOverlays(app),
            credentialsMissing = credentialsMissing,
            virtualDisplayReady = probeVirtualDisplayReady(app, adbChannel),
        )
    }

    /** The client was told TIMEOUT, but the start finished anyway: stop it so nothing unaddressable runs. */
    private fun reconcileTimedOutStart(result: McpBackend.StartResult) {
        if (result !is McpBackend.StartResult.Started) return
        reconcileLateStart(result.runHandle, registry, guards) { AgentService.instance?.stopIntakeRun(it) }
    }

    companion object {
        private const val TAG = "ClosePawMcp"
        private const val MAIN_TIMEOUT_MS = 5_000L
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
        val store = McpSettingsStore(app)
        if (!store.isEnabled()) {
            stopLocked()
            return
        }
        if (server?.isRunning == true) return
        token = store.tokenOrCreate()
        val channel = adbChannel ?: AdbShellChannel(app).also { adbChannel = it }
        maintenance?.stop()
        val worker = McpMaintenance(app, channel)
        val backend = AgentServiceMcpBackend(app, registry, worker, channel)
        val brains = brainSessions ?: AgentServiceBrainSessions({ AgentService.instance }, backend)
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
        )
        val s = McpHttpServer(h, onError = { Log.w(TAG, it) })
        try {
            s.start()
            server = s
            handler = h
            maintenance = worker
            worker.onServerStarted()
            _state.value = State.Running(s.boundPort)
            Log.i(TAG, "MCP server listening on 0.0.0.0:${s.boundPort}")
        } catch (e: IOException) {
            worker.stop()
            Log.w(TAG, "MCP server failed to start: ${e.javaClass.simpleName}")
            _state.value = State.Failed(e.javaClass.simpleName)
        }
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        McpSettingsStore(context.applicationContext).setEnabled(enabled)
        sync(context)
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
