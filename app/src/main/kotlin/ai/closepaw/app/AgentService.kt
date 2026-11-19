package ai.closepaw.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.accessibility.AccessibilityEvent
import ai.closepaw.debug.ActionDebugReceiver
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.llm.LFMLLMClient
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.LocalLLMConfig
import ai.closepaw.protocol.Op
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.protocol.SessionState
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.session.AgentSession
import ai.closepaw.ui.overlay.compose.IslandOverlayHost
import ai.closepaw.ui.overlay.compose.ServiceLifecycleOwner
import ai.closepaw.ui.overlay.visualizer.ActionVisualizerManager
import ai.closepaw.R
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.draft.ChromeDraftBrowser
import ai.closepaw.draft.DraftOutcome
import ai.closepaw.draft.SaveDraftRequest
import ai.closepaw.draft.SaveDraftResult
import ai.closepaw.draft.SaveDraftRoutine
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.util.recycleCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentService"

        const val ACTION_STOP_AGENT = "ai.closepaw.STOP_AGENT"

        private const val OUR_PACKAGE = "ai.closepaw"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /**
         * Liveness bound on waiting for a replaced session's teardown (its display-0 restore /
         * handover). Past it the NEW start fails with an error; it never proceeds concurrently.
         */
        private const val REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS = 30_000L

        /**
         * Runs every session teardown a later session may race ([shutdownReplacedSession]):
         * replaced sessions and, since gate round 4, the session attached at [onDestroy].
         * Process-lifetime and NEVER cancelled: destroying the service, which cancels
         * `serviceScope`, must not skip a teardown the process-wide barrier is waiting for.
         */
        private val replacedTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile
        var instance: AgentService? = null
            private set

        private val _statusFlow = MutableStateFlow<String>("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    }

    private val _effectivePlatformMode = MutableStateFlow<PlatformMode?>(null)
    val effectivePlatformMode: StateFlow<PlatformMode?> = _effectivePlatformMode.asStateFlow()

    /**
     * Signal that the VirtualDisplayViewerActivity should finish itself. Emitted by the
     * overlay controller when the agent reaches a terminal idle state with the viewer
     * still in front (see [shouldFinishViewerOnIdle]). The activity collects this in its
     * lifecycle scope and calls finish() so the user lands back on MainActivity instead
     * of being stranded on a frozen VD surface.
     *
     * SharedFlow with replay=0 + buffer=1 + DROP_OLDEST so a missed signal during
     * activity recreation doesn't leak — the next emit replaces it, and we only ever
     * care about the most recent finish request.
     */
    private val _viewerFinishSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val viewerFinishSignal: SharedFlow<Unit> = _viewerFinishSignal.asSharedFlow()

    /**
     * Long-lived service-owned coroutine scope. Cancelled in [onDestroy].
     *
     * Exposed as `internal` so [AgentSession] callers in the app module can
     * scope sessions to the service's lifetime instead of an Activity's. This
     * is essential: a session created against `MainActivity.sessionScope`
     * dies on rotation/recreation, killing in-flight LLM streams.
     */
    internal val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private var session: AgentSession? = null
    private var overlayController: ServiceOverlayController? = null
    private var actionVisualizer: ActionVisualizerManager? = null
    private var currentPlatformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    @Volatile private var isServiceActive = false
    private val viewerBridge by lazy {
        AgentServiceViewerBridge(
            logTag = TAG,
            overlayControllerProvider = { overlayController },
            platformProvider = { session?.getServices()?.platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform },
            openViewerActivity = { openViewerActivity() },
            finishViewerActivity = { _viewerFinishSignal.tryEmit(Unit) },
        )
    }
    private val eventHandler =
            AgentServiceEventHandler(
                    logTag = TAG,
                    updateStatus = ::updateStatus,
                    sessionCleared = {
                        session = null
                        _effectivePlatformMode.value = null
                    },
                    overlayController = { overlayController }
            )

    val capsuleStateHolder get() = overlayController?.stateHolder

    fun getActiveSession(): AgentSession? = session

    internal fun getActionVisualizer(): ActionVisualizerManager? = actionVisualizer

    internal fun getOverlayTouchGate(): ai.closepaw.platform.OverlayTouchGate? =
            overlayController?.overlayTouchGate

    internal fun dismissError() {
        overlayController?.dismissError()
    }

    private var eventCollectorJob: Job? = null

    // ── Shelly background runs (see ShellyGoalReceiver) ──
    private var shellyRun: ShellyRunTracker? = null

    /**
     * True from [startShellyRun] until its async session creation has finished on every exit
     * path. While true the service reports busy, so a second verified request arriving in the
     * window where the old session was dropped and the new one is not yet attached is refused
     * instead of creating a second, orphaned session on the virtual display. Main thread only.
     */
    private var shellyStartInFlight = false

    /** The running save-draft routine, if any (no session, no LLM). Set and cleared on the main thread. */
    private var draftJob: Job? = null

    /** Why the running draft routine was stopped from outside. Main thread. */
    private var draftStopReason: String? = null

    /**
     * Replaced-session shutdowns every new session start waits for: the process-wide barrier,
     * awaited by every session right before its platform starts a task (AgentSession), and by
     * [runAgent] / [startShellyRun] before they even create one ([awaitReplacedSessionReconcile]).
     */
    private val replacedShutdowns = ReplacedSessionShutdownBarrier.process

    /**
     * Report-once guard of the start currently being set up: set when [startShellyRun] accepts a
     * start (before the async work), cleared once the tracker adopts it or startup has ended. Lets
     * [onDestroy] report `cancelled` for a start that has no tracker yet. Main thread only.
     */
    private var shellyPendingStart: ShellyPendingResult? = null

    /**
     * Second intake (MCP, see McpIntake.kt): terminal observers keyed by the start's report-once
     * guard. Shelly starts register none, so for them every hook below is a no-op. Main thread.
     */
    private val intakeObservers = java.util.IdentityHashMap<ShellyPendingResult, (ShellyRunState.Finished) -> Unit>()

    /** An MCP stop_run that arrived while its start was still being set up (see [stopIntakeRun]). */
    private var stopRequestedForStart: ShellyPendingResult? = null
    private val shellyNotifier by lazy { ShellyRunNotifier(this) }

    fun observeExternalSession(
            externalSession: AgentSession,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring observeExternalSession while service is shutting down")
            return
        }
        // A MainActivity / onboarding session: back to the normal (visible) overlay behavior.
        overlayController?.clearShellyBackgroundRun()
        // A running draft yields; its teardown is in the barrier the session's first task awaits.
        stopDraftForForegroundSession()
        attachObservedSession(externalSession, platformMode)
    }

    /** Shared by [observeExternalSession] and the Shelly path (which keeps its invisible overlay). */
    private fun attachObservedSession(externalSession: AgentSession, platformMode: PlatformMode) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring observeExternalSession while service is shutting down")
            return
        }
        Log.i(TAG, "Observing external session: ${externalSession.sessionId}, mode=$platformMode")
        retireShellyRunUnlessFor(externalSession)
        // The session observed so far is being replaced by one created elsewhere (MainActivity,
        // onboarding). If its teardown may still move display-0 tasks (a Shelly-run VD), shut it
        // down through the barrier now: left running unobserved, its later teardown (idle timeout)
        // would move / refront tasks in the middle of the new session's run. Registered before the
        // caller submits the new session's first task, which then waits for it (AgentSession).
        // Other sessions keep the previous behavior (their owner shuts them down).
        session?.takeIf { it !== externalSession }?.let { previous ->
            shutdownReplacedSession(previous, onlyIfItMayMoveTasks = true)
        }
        session = externalSession
        currentPlatformMode = platformMode
        _effectivePlatformMode.value = platformMode
        overlayController?.setPlatformMode(platformMode)
        observeSession(externalSession)
    }

    private val stopReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_STOP_AGENT) {
                        Log.i(TAG, "Received STOP_AGENT broadcast")
                        stopAgent()
                    }
                }
            }

    private val debugExecReceiver = ActionDebugReceiver()
    private val debugMobileActionReceiver = ai.closepaw.debug.MobileActionDebugReceiver()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceActive = true
        instance = this
        Log.i(TAG, "AgentService connected")

        val info = serviceInfo
        if (info != null) {
            info.flags =
                    info.flags or
                            android.accessibilityservice.AccessibilityServiceInfo
                                    .FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
            Log.i(TAG, "Updated service info flags: ${info.flags}")
        } else {
            Log.w(TAG, "Service info was null!")
        }

        updateStatus("Accessibility Service connected")

        val controller =
                ServiceOverlayController(
                        context = this,
                        lifecycleOwner = serviceLifecycleOwner,
                        savedStateRegistryOwner = serviceLifecycleOwner,
                        scope = serviceScope,
                        appPackage = OUR_PACKAGE,
                        logTag = TAG,
                        onStop = { submitOp(Op.Shutdown) },
                        onTakeover = { submitOp(Op.Takeover) },
                        onResume = { submitOp(Op.Resume) },
                        onSupplement = { text -> submitOp(Op.Supplement(text)) },
                        onSupplementAndResume = { text ->
                            submitOps(Op.Supplement(text), Op.Resume)
                        },
                        onUserResponse = { callId, response -> submitOp(Op.UserResponse(callId, response)) },
                        onApprovalResponse = { callId, decision, scope, packageName ->
                            submitOp(Op.Approve(callId, decision, scope, packageName))
                            shellyRun?.let { if (it.onApprovalResolved(callId)) renderShellyRun() }
                        },
                        onOpenApp = {
                            val intent =
                                    Intent(this, MainActivity::class.java).apply {
                                        flags =
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                            startActivity(intent)
                        },
                        onOpenViewer = { viewerBridge.openViewer() },
                        onFinishViewer = { viewerBridge.finishViewer() },
                        statusIslandManager =
                                IslandOverlayHost(
                                        service = this,
                                        lifecycleOwner = serviceLifecycleOwner,
                                        savedStateRegistryOwner = serviceLifecycleOwner,
                                        onExpandCapsule = {
                                            // Tap island → expand Smart Capsule overlay, hide island
                                            overlayController?.onIslandTapped()
                                        }
                                )
                )
        overlayController = controller

        actionVisualizer = ActionVisualizerManager(
                context = this,
                lifecycleOwner = serviceLifecycleOwner,
                savedStateRegistryOwner = serviceLifecycleOwner,
                renderContext = controller.stateHolder.context,
        )
        Log.i(TAG, "ActionVisualizerManager initialized")

        registerDebugStopReceiverIfNeeded(this, stopReceiver)
        // MCP intake server: runs only while its settings toggle is ON (Keystore read: off main).
        serviceScope.launch(Dispatchers.IO) { McpServerController.sync(applicationContext) }
        registerDebugExecReceiverIfNeeded(this, debugExecReceiver)
        registerDebugMobileActionReceiverIfNeeded(this, debugMobileActionReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                event.displayId
            } else {
                Display.DEFAULT_DISPLAY
            }
            overlayController?.handleWindowStateChanged(
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString(),
                    displayId = eventDisplayId,
            )
            trackShellyRunWindow(
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString(),
                    displayId = eventDisplayId,
            )
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentService interrupted")
    }

    override fun onDestroy() {
        isServiceActive = false
        instance = null

        shellyRun?.let {
            it.finish(ShellyRunState.Finished.Kind.STOPPED)
            shellyNotifier.render(it.state)
            reportShellyResult(it)
        }
        shellyRun = null
        // A start still being set up (no tracker yet) would otherwise end silently when
        // serviceScope is cancelled below. The startup coroutine's own cancellation report then
        // finds the shared guard claimed and stays quiet.
        shellyPendingStart?.let { reportShellyStartCancelled(it) }
        shellyPendingStart = null

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        val currentSession = session
        if (currentSession != null) {
            // Same path as a replaced session (gate round 4): a service restart in this process
            // starts its next session against the same process-wide barrier, so this teardown is
            // registered there (when it may move display-0 tasks) BEFORE the session is detached,
            // and runs on the never-cancelled teardown scope under NonCancellable. Nothing here
            // blocks the main thread (ANR risk); the session handles its own checkpoint
            // persistence via NonCancellable.
            val teardown = shutdownReplacedSession(currentSession)
            if (teardown != null) {
                // The former 5 s bound, now observe-only: it can no longer abandon the teardown.
                val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                watchSessionTeardown(watchScope, teardown, SHUTDOWN_TIMEOUT_MS) {
                    Log.w(TAG, "Timed out waiting for session shutdown; it continues in the background")
                }.invokeOnCompletion { watchScope.cancel() }
            }
        }
        session = null
        _effectivePlatformMode.value = null

        overlayController?.dispose()
        overlayController = null
        actionVisualizer?.dispose()
        actionVisualizer = null
        unregisterDebugStopReceiverIfNeeded(this, stopReceiver)
        unregisterDebugExecReceiverIfNeeded(this, debugExecReceiver)
        unregisterDebugMobileActionReceiverIfNeeded(this, debugMobileActionReceiver)
        serviceLifecycleOwner.onDestroy()
        super.onDestroy()
        _statusFlow.value = ""
        serviceScope.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()
    }

    private fun submitOp(op: Op) {
        submitOps(op)
    }

    private fun submitOps(vararg ops: Op) {
        if (!isServiceActive) {
            Log.w(TAG, "Dropping ops while service is not active: ${ops.toList()}")
            return
        }
        val currentSession = session
        Log.d(TAG, "submitOps: ${ops.toList()}, session=${currentSession?.sessionId}")

        if (currentSession == null) {
            Log.w(TAG, "No active session for ops: ${ops.toList()}")
            return
        }

        serviceScope.launch {
            ops.forEach { op -> currentSession.submit(op) }
        }
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, status)
        _statusFlow.value = status
    }

    private fun observeSession(agentSession: AgentSession) {
        eventCollectorJob?.cancel()

        val recordingService = agentSession.getServices().recordingService

        eventCollectorJob =
                serviceScope.launch {
                    try {
                        agentSession.events.collect { event ->
                            try {
                                // Tracker first: the overlay may resolve an approval synchronously
                                // (auto-deny), which must find the tracker already waiting.
                                onShellyRunEvent(agentSession, event)
                                eventHandler.handleEvent(event, recordingService)
                            } catch (e: Exception) {
                                Log.e(
                                        TAG,
                                        "Failed to handle event: ${event::class.simpleName}",
                                        e
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Session event collector crashed", e)
                    }
                }
    }

    fun runAgent(
            goal: String,
            authStore: ai.closepaw.auth.AuthStore? = null,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring runAgent because service is not active")
            return
        }
        overlayController?.clearShellyBackgroundRun()
        // A running draft yields; its teardown is in the barrier awaitReplacedSessionReconcile
        // (below) waits for before the new session is even created.
        stopDraftForForegroundSession()
        shellyRun?.let {
            it.finish(ShellyRunState.Finished.Kind.STOPPED)
            shellyNotifier.render(it.state)
            reportShellyResult(it)
            shellyRun = null
        }
        if (session != null) {
            Log.i(TAG, "Stopping existing session before starting new one")
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            val oldSession = session
            session = null
            shutdownReplacedSession(oldSession)
        }

        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)

        serviceScope.launch {
            try {
                awaitReplacedSessionReconcile()
                val settings = AppSettingsStore(this@AgentService).load()
                val sessionConfig =
                        SessionConfig(
                                debugMode = true,
                                traceEnabled = settings.traceEnabled,
                                platformMode = platformMode
                        )
                val visualizer = actionVisualizer
                val touchGate = overlayController?.overlayTouchGate
                val newSession =
                        withContext(Dispatchers.Default) {
                            AgentSession.create(
                                    config = sessionConfig,
                                    service = this@AgentService,
                                    scope = serviceScope,
                                    authStore = authStore,
                                    visualizer = visualizer,
                                    overlayTouchGate = touchGate,
                            )
                        }

                // A session attached while this one was being created (a Shelly run started in
                // the meantime) is replaced here: its result first, then its teardown through the
                // barrier, which newSession's first task (submitted below) waits for.
                session?.takeIf { it !== newSession }?.let { stray ->
                    Log.w(TAG, "runAgent: replacing session ${stray.sessionId} attached meanwhile")
                    eventCollectorJob?.cancel()
                    eventCollectorJob = null
                    retireShellyRunUnlessFor(newSession)
                    session = null
                    shutdownReplacedSession(stray, onlyIfItMayMoveTasks = true)
                }
                session = newSession

                observeSession(newSession)

                newSession.submit(Op.UserInput(text = goal))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                updateStatus("❌ Failed to start: ${e.message}")
                overlayController?.hideAll()
            }
        }
    }

    // ── Shelly background runs ──

    /** A session is mid-task (a new background run must not clobber it). */
    internal fun isSessionBusy(): Boolean {
        if (shellyStartInFlight || draftJob != null) return true
        return when (session?.state?.value) {
            SessionState.Running, SessionState.Paused, SessionState.TakeoverPending -> true
            else -> false
        }
    }

    /**
     * Start a verified Shelly goal as a NEW session, without MainActivity. Mirrors MainActivity's
     * FORCE_FRESH session creation (same persisted settings, credentials and recording), so the
     * run shows up in ClosePaw's history and MainActivity rebinds to it when the user opens it.
     *
     * Visible surface: while the run works normally on the virtual display, ONLY the
     * [ShellyRunNotifier] notification (incl. its heads-up for attention states) — no Smart
     * Capsule, island or edge glow on the main display, and its completion (Done) is reported by
     * the notifier alone. The capsule appears on the main display only in attention states:
     * waiting for approval / input / action, error, and takeover (pending or confirmed, i.e. the
     * user paused it). Opening the VirtualDisplayViewer shows the usual viewer overlays. A run
     * that falls back to the main display (accessibility mode) is never hidden. Decision rule:
     * [shouldSuppressForShellyBackgroundRun].
     */
    internal fun startShellyRun(
            goal: String,
            platformMode: PlatformMode,
            correlation: ShellyBackgroundRun.ResultCorrelation? = null,
            /**
             * MCP intake only (Shelly passes null): called once on the main thread with the run's
             * terminal state, from the same report-once paths that send the Shelly result.
             */
            onTerminal: ((ShellyRunState.Finished) -> Unit)? = null,
    ): ShellyPendingResult? {
        if (!isServiceActive) {
            shellyNotifier.showBlocked(ShellyBackgroundRun.Block.ACCESSIBILITY_OFF, goal)
            reportShellyStartFailure(correlation, "ClosePaw's accessibility service is not active")
            onTerminal?.invoke(ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, "ClosePaw's accessibility service is not active"))
            return null
        }
        if (shellyStartInFlight) {
            Log.w(TAG, "Shelly run already starting; refusing a second start")
            shellyNotifier.showBlocked(ShellyBackgroundRun.Block.BUSY, goal)
            reportShellyStartFailure(correlation, "ClosePaw is busy with another task")
            onTerminal?.invoke(ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, "ClosePaw is busy with another task"))
            return null
        }
        shellyStartInFlight = true
        // The start is accepted (the receiver will answer result_callback=1 when a correlation is
        // present): from here every exit must claim this guard exactly once.
        val pending = ShellyPendingResult(correlation)
        shellyPendingStart = pending
        onTerminal?.let { intakeObservers[pending] = it }
        // Replace an idle/finished session (FORCE_FRESH semantics). Busy sessions were refused
        // by the receiver's precheck.
        shellyRun?.let {
            it.finish(ShellyRunState.Finished.Kind.STOPPED)
            reportShellyResult(it)
            shellyRun = null
        }
        session?.let { old ->
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            session = null
            _effectivePlatformMode.value = null
            shutdownReplacedSession(old)
        }

        val onMainDisplay = platformMode != PlatformMode.VIRTUAL_DISPLAY
        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)
        overlayController?.onBackgroundRunRequested(onMainDisplay)
        shellyNotifier.render(ShellyRunState.Working(onMainDisplay, emptyList()))

        val startJob = serviceScope.launch {
            try {
                awaitReplacedSessionReconcile()
                val settings = AppSettingsState.create(applicationContext).also { it.load() }
                val authStore = AuthStoreHolder.get(applicationContext)
                val baseUrlOverrides: Map<LLMProvider, String> =
                        if (settings.openaiBaseUrl.isNotBlank()) {
                            mapOf(LLMProvider.OPENAI_API to settings.openaiBaseUrl)
                        } else emptyMap()
                val localConfig =
                        if (settings.llmBackend == LLMBackendType.LOCAL) {
                            LocalLLMConfig(
                                    modelSlug = settings.localModel.modelSlug,
                                    quantizationSlug = settings.localModel.quantizationSlug
                            )
                        } else null
                val config =
                        SessionConfig(
                                approvalMode = settings.approvalMode,
                                mainModel = settings.selectedModel,
                                debugMode = settings.debugMode,
                                traceEnabled = settings.traceEnabled,
                                llm = SessionLlmConfig(
                                        backendType = settings.llmBackend,
                                        localConfig = localConfig
                                ),
                                perceptionConfig = when (settings.perceptionMode) {
                                    "screenshot_only" -> PerceptionConfig.ScreenshotOnly()
                                    "hybrid" -> PerceptionConfig.Hybrid()
                                    else -> PerceptionConfig.AccessibilityOnly
                                },
                                platformMode = platformMode,
                                unattended = true,
                                preserveMainDisplayTasks = true,
                        )
                val newSession =
                        withContext(Dispatchers.Default) {
                            AgentSession.create(
                                    config = config,
                                    service = this@AgentService,
                                    scope = serviceScope,
                                    authStore = authStore,
                                    baseUrlOverrides = baseUrlOverrides,
                                    visualizer = actionVisualizer,
                                    overlayTouchGate = overlayController?.overlayTouchGate,
                            )
                        }
                val effectiveMode = newSession.getServices().platform.mode
                if (platformMode == PlatformMode.VIRTUAL_DISPLAY && effectiveMode != PlatformMode.VIRTUAL_DISPLAY) {
                    // Shizuku went away between the precheck and session creation: PlatformFactory
                    // fell back to the main display. Refuse instead of taking over the screen.
                    Log.w(TAG, "Shelly run: virtual display lost during setup; not starting")
                    serviceScope.launch { newSession.submit(Op.Shutdown) }
                    shellyNotifier.cancel()
                    shellyNotifier.showBlocked(ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE, goal)
                    reportShellyStartFailure(pending, "ClosePaw's virtual display became unavailable")
                    overlayController?.clearShellyBackgroundRun()
                    overlayController?.hideAll()
                    return@launch
                }
                if (settings.llmBackend == LLMBackendType.LOCAL) {
                    (newSession.getServices().llmClient as? LFMLLMClient)?.loadModel()
                }
                if (stopRequestedForStart === pending) {
                    // MCP stop_run during setup: cancel before the goal is ever submitted. No suspension
                    // point between this check and the tracker taking over below, so none is lost.
                    Log.i(TAG, "Background run: stop requested during setup; not starting")
                    stopRequestedForStart = null
                    serviceScope.launch { newSession.submit(Op.Shutdown) }
                    shellyNotifier.cancel()
                    reportShellyStartCancelled(pending)
                    overlayController?.clearShellyBackgroundRun()
                    overlayController?.hideAll()
                    return@launch
                }

                val ignored = buildSet {
                    add(packageName)
                    addAll(ai.closepaw.session.SystemSurfaces.resolve(this@AgentService))
                }
                val tracker = ShellyRunTracker(
                        sessionId = newSession.sessionId.value,
                        onMainDisplay = effectiveMode != PlatformMode.VIRTUAL_DISPLAY,
                        ignoredPackages = ignored,
                        correlation = pending.correlation,
                        result = pending,
                )
                // Defensive: never leave a different session running unobserved (it would keep
                // driving the virtual display with no capsule / notification attached).
                session?.takeIf { it !== newSession }?.let { stray ->
                    Log.w(TAG, "Shelly run: shutting down stray session ${stray.sessionId}")
                    eventCollectorJob?.cancel()
                    eventCollectorJob = null
                    session = null
                    // Through the barrier like any replaced session: the first task of
                    // newSession (submitted below) waits for this teardown (AgentSession).
                    shutdownReplacedSession(stray)
                }
                attachObservedSession(newSession, effectiveMode)
                // This run's result is reported by the tracker: its run-end display-0 return waits
                // for that report (set before the task can end).
                (newSession.getServices().platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform)
                        ?.expectRunResultReport()
                overlayController?.updateShellyRunDisplay(tracker.onMainDisplay)
                shellyRun = tracker
                // The tracker now owns the guard; tracker paths (incl. onDestroy) report from here.
                if (shellyPendingStart === pending) shellyPendingStart = null
                renderShellyRun()
                Log.i(TAG, "Shelly run started: session=${newSession.sessionId}, mode=$effectiveMode")
                newSession.submit(Op.UserInput(text = goal))
            } catch (e: CancellationException) {
                // Service destroyed / scope cancelled mid-startup. Once the tracker owns the guard
                // its paths report (onDestroy finishes it); before that, claim it here — a no-op if
                // onDestroy already claimed it.
                if (shellyRun?.result !== pending) reportShellyStartCancelled(pending)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Shelly run failed to start", e)
                shellyRun = null
                shellyNotifier.render(
                        ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, e.message)
                )
                reportShellyStartFailure(pending, "ClosePaw failed to start the task")
                overlayController?.clearShellyBackgroundRun()
                overlayController?.hideAll()
            }
        }
        // Cleared on every exit path: success, VD-lost refusal, exception and cancellation.
        startJob.invokeOnCompletion {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                shellyStartInFlight = false
                if (shellyPendingStart === pending) shellyPendingStart = null
                if (stopRequestedForStart === pending) stopRequestedForStart = null
            }
        }
        return pending
    }

    /**
     * MCP stop_run for the start owning [pending]: the running tracker is stopped through
     * [stopShellyRun] (the notification's Stop path); a start still being set up is cancelled
     * before its goal is submitted. False when [pending] is no longer this service's run.
     */
    internal fun stopIntakeRun(pending: ShellyPendingResult): Boolean {
        val tracker = shellyRun
        if (tracker?.result === pending) {
            if (ai.closepaw.mcp.stopTargetsLiveSession(tracker.sessionId, session?.sessionId?.value)) {
                stopShellyRun()
            } else {
                // A replacement run's session is already live: Op.Shutdown would kill it. Finish
                // only this tracker (mirrors retireShellyRunUnlessFor / stopShellyRun's no-session path).
                tracker.finish(ShellyRunState.Finished.Kind.STOPPED)
                renderShellyRun()
                reportShellyResult(tracker)
                shellyRun = null
                overlayController?.releaseShellyBackgroundRunWhenIdle()
            }
            return true
        }
        if (shellyPendingStart === pending) {
            stopRequestedForStart = pending
            return true
        }
        return false
    }

    /** Hands a terminal state to the MCP observer of [pending], at most once. No-op for Shelly starts. */
    private fun notifyIntake(pending: ShellyPendingResult, finished: ShellyRunState.Finished?) {
        finished ?: return
        val observer = intakeObservers.remove(pending) ?: return
        try {
            observer(finished)
        } catch (e: Exception) {
            Log.w(TAG, "intake observer failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Shut a replaced session down asynchronously (as before). A run-end restore still in flight
     * for it is joined first, so its deferral decision is recorded before the teardown (which
     * executes / hands it over) runs. Whenever a new session must not overlap that shutdown
     * ([ShellyBackgroundRun.mustAwaitReplacedShutdown]) it is registered in [replacedShutdowns]
     * until the teardown has ACTUALLY completed (owned by the old platform; a teardown that could
     * not complete stays registered and fails later starts), and every later task start waits for it
     * (AgentSession's platform start, plus [awaitReplacedSessionReconcile] on the service paths);
     * otherwise no wait (unchanged timing for sessions that never touch display-0 tasks).
     * [onlyIfItMayMoveTasks]: do nothing at all unless that wait is needed (paths that used to
     * leave such a session alone keep doing so).
     */
    private fun shutdownReplacedSession(old: AgentSession?, onlyIfItMayMoveTasks: Boolean = false): Job? {
        if (old == null) return null
        val platform = try {
            old.getServices().platform
        } catch (e: Exception) {
            null
        }
        val vdPlatform = platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
        // Recorded by the platform before the old session went Idle (onTaskEnded).
        val runEndRestore = vdPlatform?.runEndRestoreInFlight()
        val mustAwait = ShellyBackgroundRun.mustAwaitReplacedShutdown(
                returnsMainDisplayTasks = vdPlatform?.returnsMainDisplayTasks == true,
                runEndRestoreInFlight = runEndRestore?.isActive == true,
                deferredWork = try {
                    platform?.hasDeferredWork() == true
                } catch (e: Exception) {
                    // Unknown: wait rather than risk the old teardown overlapping the new run.
                    true
                },
        )
        if (onlyIfItMayMoveTasks && !mustAwait) return null
        // Its result (if any) was reported by the replacing path before this call: release a
        // run-end restore still gated on that report, then order the teardown after it.
        vdPlatform?.onRunResultReported()
        // Not in serviceScope: service destruction must not cancel it before the teardown ran
        // (launchReplacedSessionTeardown: non-cancellable critical path, truthful verdict).
        // Owned by the old platform: the old session itself never waits for its own teardown.
        // Registered (when mustAwait) before this returns, i.e. before the caller detaches it.
        return launchRegisteredSessionTeardown(
                barrier = replacedShutdowns,
                scope = replacedTeardownScope,
                mustAwait = mustAwait,
                owner = platform,
                runEndRestore = runEndRestore,
                shutdown = { old.submit(Op.Shutdown) },
                // VD: stop() reached Stopped (receiver gone, deferral triggers inert). Others: the
                // shutdown returning is the teardown.
                isTornDown = { vdPlatform?.isStopped ?: true },
                stopPlatform = { vdPlatform?.stop() },
                onFailure = { e ->
                    Log.e(TAG, "Replaced session teardown did not complete" +
                            (if (mustAwait) "; it stays in the barrier and new starts fail" else ""), e)
                },
        )
    }

    /**
     * Reconcile first: the replaced session's teardown (VirtualDisplayPlatform.stop: display-0
     * restore, deferred or not, and the release handover) must have COMPLETED before the new
     * session starts; Android tasks and display 0 are global, so an overlapping old teardown
     * could move the very task the new run operates. Every start waits for EVERY such shutdown
     * still running, not only the one it replaced. There is no give-up: past the liveness bound
     * the new start FAILS (the caller's start-failure path reports it) instead of proceeding, and
     * the shutdown stays registered, so a later start waits for it too.
     */
    private suspend fun awaitReplacedSessionReconcile() {
        if (!replacedShutdowns.awaitClear(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS)) {
            Log.e(TAG, "Replaced session still shutting down after ${REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS} ms; not starting")
            throw IllegalStateException("the previous task is still shutting down; try again")
        }
    }

    internal fun stopShellyRun() {
        // The notification's Stop during a save-draft routine (it has no session or tracker; a
        // draft and a goal run never coexist, each refuses while the other is busy).
        draftJob?.let {
            it.cancel()
            return
        }
        if (session != null) {
            submitOp(Op.Shutdown)
        } else {
            shellyRun?.finish(ShellyRunState.Finished.Kind.STOPPED)
            renderShellyRun()
            shellyRun?.let { reportShellyResult(it) }
            shellyRun = null
            overlayController?.releaseShellyBackgroundRunWhenIdle()
        }
    }

    // ── Save-draft routine (fixed, LLM-free; see ShellySaveDraftReceiver) ──

    /** True when Chrome is, or may be, what the user has in front on the main display. */
    internal fun isChromeInFront(): Boolean {
        val window = currentForegroundPackageOnDefaultDisplay()
        val task = chromeTaskInFrontOnMainDisplay()
        val chrome = ShellySaveDraft.chromeInFront(window, task)
        if (chrome) Log.i(TAG, "chrome in front: windowIsChrome=${window == ChromeFlagDeepLink.CHROME_PACKAGE} windowKnown=${window != null} task=$task")
        return chrome
    }

    private val taskReader by lazy { ShizukuClient() }

    /**
     * A second look, from the task list: true when the front visible root task on the main display
     * is Chrome's. Null when the list cannot be read (the window check then fails closed).
     */
    private fun chromeTaskInFrontOnMainDisplay(): Boolean? = try {
        val tasks = taskReader.rootTasksWithPackagesOnDisplay(Display.DEFAULT_DISPLAY)
        val front = tasks?.firstOrNull { it.visible != false } ?: tasks?.firstOrNull()
        if (front == null) null else ChromeFlagDeepLink.CHROME_PACKAGE in front.packages
    } catch (e: Exception) {
        null
    }

    /**
     * Package of the application window in front on the DEFAULT display right now (null if
     * unknown). Used only by [isChromeInFront].
     */
    private fun currentForegroundPackageOnDefaultDisplay(): String? {
        return try {
            val defaultDisplayWindows: List<android.view.accessibility.AccessibilityWindowInfo> =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY) ?: emptyList()
                    } else {
                        this.windows ?: emptyList()
                    }
            val candidates = defaultDisplayWindows
                    .filter { w ->
                        w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                                        w.displayId == Display.DEFAULT_DISPLAY)
                    }
                    .sortedByDescending { it.layer }
            val top = candidates.firstOrNull { it.isActive } ?: candidates.firstOrNull()
            val fromWindow = top?.root?.let { root ->
                try {
                    root.packageName?.toString()
                } finally {
                    root.recycleCompat()
                }
            }
            fromWindow ?: rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "foreground package lookup failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Runs the fixed draft routine for a verified Shelly request. Unlike [startShellyRun] it opens
     * no session: no LLM, no tools, no policy engine. The receiver's precheck has already refused a
     * busy service, so the caller's Shelly approval is the only gate, as on the goal path.
     * Main thread.
     */
    internal fun startShellySaveDraft(
            request: SaveDraftRequest,
            correlation: ShellyBackgroundRun.ResultCorrelation,
    ) {
        // No notification rendering here (gate fix round 2, finding 4): this runs inside the
        // receiver's ordered answer, after its last deadline check. The Working notification is
        // posted below, to run after the answer has been committed.
        val browser = ChromeDraftBrowser(this, serviceScope)
        // Gate fix round 2, finding 2: a run-end display-0 restore of the session still attached
        // (idle build 61 session) is joined before the draft touches Chrome's tasks.
        // Gate fix round 3, finding 2: the attached platform is kept too, so its DEFERRED display-0
        // restore (fired later by screen off / viewer close, outside any barrier) is accounted for.
        val attachedPlatform = try {
            session?.getServices()?.platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
        } catch (e: Exception) {
            null
        }
        val attachedRunEndRestore = try {
            attachedPlatform?.runEndRestoreInFlight()
        } catch (e: Exception) {
            null
        }
        val routine = SaveDraftRoutine(browser, request, ownerHasChrome = ::isChromeInFront)
        draftStopReason = null
        // Gate fix round 1, finding 6. The draft owns Chrome's tasks (and its virtual display)
        // until its teardown has COMPLETED: registered in the process-wide barrier now, so every
        // session start (runAgent, MainActivity / onboarding sessions via AgentSession's own wait)
        // waits for it. Completed normally on every path below, never cancelled: a cancelled
        // entry would fail every later start.
        val teardown = Job()
        replacedShutdowns.add(teardown, owner = browser)
        // From acceptance on, exactly one terminal callback: the body's own, or the fallback
        // below when the job ended without reaching it.
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        // ATOMIC: a Stop or the service going away before the body runs still runs the body, so
        // its finally (teardown + callback) is never skipped.
        val job = serviceScope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            var result: SaveDraftResult? = null
            try {
                // Gate fix round 2, finding 2: the reverse direction of the session barrier. A
                // replaced session's teardown (display-0 task returns) or a previous service's
                // draft teardown still running is waited for before the draft acts on any task;
                // this draft's own entry (owner = browser) is excluded. Past the same liveness
                // bound as session starts, the draft fails without touching Chrome.
                //
                // Gate fix round 3, finding 2: fail closed. A run-end restore that did not finish
                // within the bound, or an attached session's deferred display-0 restore still
                // pending (or running), means another party may still move tasks: the draft does
                // not start and reports FAILED previous-task-shutting-down via the normal callback.
                // The run-end restore is joined BEFORE the deferral check: its completion can
                // create a deferral.
                val runEndDone = attachedRunEndRestore?.let { restore ->
                    withTimeoutOrNull(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS) { restore.join() } != null
                } ?: true
                val barrierClear = runEndDone &&
                        replacedShutdowns.awaitClear(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS, exceptOwner = browser)
                // Checked last, immediately before browser.start(): nothing awaited above can be
                // what clears it later.
                val deferredPending = barrierClear && try {
                    attachedPlatform?.displayZeroRestorePending() == true
                } catch (e: Exception) {
                    true // unknown counts as pending
                }
                val clear = barrierClear && !deferredPending
                result = if (!clear) {
                    Log.e(TAG, "Draft routine: previous task still shutting down; not starting " +
                            "(runEndDone=$runEndDone deferredPending=$deferredPending)")
                    routine.resultSoFar("previous-task-shutting-down")
                } else if (browser.start()) {
                    routine.run()
                } else {
                    routine.resultSoFar("virtual-display-unavailable")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Draft routine failed: ${e.javaClass.simpleName}")
                result = routine.resultSoFar("error-${e.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable) {
                    try {
                        browser.stop()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Draft routine teardown failed: ${e.javaClass.simpleName}")
                    }
                    teardown.complete()
                    withContext(Dispatchers.Main) {
                        // Before the callback, as before: Shelly sends its next draft on it, and
                        // must not find this one still busy. (No other draft can be current here:
                        // a second one is refused while this one is set.)
                        draftJob = null
                        // Gate fix round 2, finding 3: the callback does not depend on the
                        // notification. Rendering is isolated in its own try; the guard is claimed
                        // only once the callback has actually been dispatched, so anything that
                        // stops this block before that leaves the completion handler's fallback
                        // armed.
                        if (!reported.get()) {
                            val finalResult = result ?: routine.resultSoFar(draftStopReason ?: "stopped")
                            try {
                                renderDraftFinished(finalResult)
                            } catch (e: Throwable) {
                                Log.w(TAG, "Draft routine notification failed: ${e.javaClass.simpleName}")
                            }
                            // The completion handler runs only after this body has ended, so the
                            // two never race: send first, then claim.
                            ShellyResultCallback.send(applicationContext, draftPayload(finalResult, correlation))
                            reported.set(true)
                        }
                    }
                }
            }
        }
        draftJob = job
        job.invokeOnCompletion {
            // Normally a no-op: the body's finally has reported and completed the teardown.
            teardown.complete()
            if (reported.compareAndSet(false, true)) {
                Log.w(TAG, "Draft routine ended before its report; reporting it stopped")
                ShellyResultCallback.send(
                        applicationContext,
                        draftPayload(routine.resultSoFar(draftStopReason ?: "stopped"), correlation),
                )
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (draftJob === job) draftJob = null }
        }
        // After the receiver's answer is committed (this runs inside it, on the main thread, so a
        // post lands after it), and only while this draft is still the current one, so it can never
        // overwrite the finished notification.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (draftJob === job && !reported.get()) {
                try {
                    shellyNotifier.render(ShellyRunState.Working(false, listOf(ChromeFlagDeepLink.CHROME_PACKAGE)))
                } catch (e: Exception) {
                    Log.w(TAG, "Draft routine notification failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * A foreground session (MainActivity, onboarding) is about to start: a running draft yields.
     * It is stopped like the notification's Stop; its teardown is in the barrier, which the new
     * session's start waits for, so the two never act on Chrome's tasks at the same time.
     */
    private fun stopDraftForForegroundSession() {
        val job = draftJob ?: return
        Log.i(TAG, "Draft routine: a foreground session is starting; stopping the draft")
        if (draftStopReason == null) draftStopReason = "replaced-by-session"
        job.cancel()
    }

    private fun draftPayload(
            result: SaveDraftResult,
            correlation: ShellyBackgroundRun.ResultCorrelation,
    ) = ShellyResultCallback.Payload(
            reqId = correlation.reqId,
            nonce = correlation.nonce,
            status = result.outcome.status,
            summary = ShellyResultCallback.truncateSummary(result.toSummary()),
            finishedAtMs = System.currentTimeMillis(),
            sessionId = null,
    )

    /**
     * The finished draft's notification only. The terminal callback is sent by the caller,
     * independently of this (gate fix round 2, finding 3), so a rendering failure cannot suppress it.
     */
    private fun renderDraftFinished(result: SaveDraftResult) {
        // Never the article text, never the page: only what the summary already says.
        Log.i(TAG, "Draft routine: ${result.platform.wire} ${result.outcome} reason=${result.reason}")
        val (kind, messageId) = when (result.outcome) {
            DraftOutcome.SAVED ->
                if (result.imagesPlaced < result.imagesExpected) {
                    ShellyRunState.Finished.Kind.DONE to R.string.shelly_draft_saved_missing_images
                } else {
                    ShellyRunState.Finished.Kind.DONE to R.string.shelly_draft_saved
                }
            DraftOutcome.ALREADY_EXISTS -> ShellyRunState.Finished.Kind.DONE to R.string.shelly_draft_exists
            DraftOutcome.UNCERTAIN -> ShellyRunState.Finished.Kind.FAILED to R.string.shelly_draft_uncertain
            DraftOutcome.FAILED -> ShellyRunState.Finished.Kind.FAILED to R.string.shelly_draft_failed
        }
        val message = if (messageId == R.string.shelly_draft_saved_missing_images) {
            getString(messageId, result.imagesPlaced, result.imagesExpected)
        } else {
            getString(messageId)
        }
        shellyNotifier.render(ShellyRunState.Finished(kind, message))
    }

    internal fun resumeShellyRun() {
        submitOp(Op.Resume)
    }

    internal fun resolveShellyApproval(
            callId: String,
            decision: ApprovalDecision,
            scope: ApprovalScope,
            packageName: String,
    ) {
        val resolved = overlayController?.resolveApprovalExternally(callId, decision, scope, packageName) == true
        if (!resolved) Log.d(TAG, "Shelly approval $callId already resolved or stale")
    }

    private fun renderShellyRun() {
        shellyRun?.let { shellyNotifier.render(it.state) }
    }

    private fun retireShellyRunUnlessFor(newSession: AgentSession) {
        val tracker = shellyRun ?: return
        if (tracker.sessionId == newSession.sessionId.value) return
        tracker.finish(ShellyRunState.Finished.Kind.STOPPED)
        shellyNotifier.render(tracker.state)
        reportShellyResult(tracker)
        shellyRun = null
    }

    private fun onShellyRunEvent(agentSession: AgentSession, event: ai.closepaw.protocol.AgentEvent) {
        val tracker = shellyRun ?: return
        if (tracker.sessionId != agentSession.sessionId.value) return
        if (tracker.onEvent(event)) renderShellyRun()
        if (tracker.isFinished) {
            reportShellyResult(tracker)
            shellyRun = null
            // Result reported: now the display-0 task return the platform recorded when the
            // session's task ended (VirtualDisplayPlatform.onTaskEnded, before Hot Idle) may run.
            // Build 59: it may DEFER the return when the user has moved on (no flash while they look).
            (agentSession.getServices().platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform)
                    ?.onRunResultReported()
            // The overlay handles this terminal event right after: keep its Done invisible.
            overlayController?.releaseShellyBackgroundRunWhenIdle()
        }
    }

    /**
     * Send a finished run's final state back to Shelly (at most once per run, and only when the
     * goal broadcast carried a result correlation). See [ShellyResultCallback].
     */
    private fun reportShellyResult(tracker: ShellyRunTracker) {
        // Claims the start's shared guard: no-op if a startup path already reported this start.
        ShellyResultCallback.send(applicationContext, tracker.claimResult(System.currentTimeMillis()))
        notifyIntake(tracker.result, tracker.state as? ShellyRunState.Finished)
    }

    /**
     * A verified request refused before a start was accepted (no [ShellyPendingResult] exists yet;
     * each refusal is its own single terminal report).
     */
    private fun reportShellyStartFailure(
            correlation: ShellyBackgroundRun.ResultCorrelation?,
            message: String,
    ) {
        ShellyResultCallback.send(
                applicationContext,
                ShellyResultCallback.failurePayload(correlation, message, System.currentTimeMillis()),
        )
    }

    /** An accepted start failed before it ran. Claims the start's guard (at most one report). */
    private fun reportShellyStartFailure(pending: ShellyPendingResult, message: String) {
        ShellyResultCallback.send(applicationContext, pending.claimFailed(message, System.currentTimeMillis()))
        notifyIntake(pending, ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, message))
    }

    /** An accepted start was cancelled before it ran. Claims the start's guard (at most one report). */
    private fun reportShellyStartCancelled(pending: ShellyPendingResult) {
        ShellyResultCallback.send(applicationContext, pending.claimCancelled(System.currentTimeMillis()))
        notifyIntake(
                pending,
                ShellyRunState.Finished(
                        ShellyRunState.Finished.Kind.STOPPED,
                        ShellyResultCallback.CANCELLED_DURING_START_MESSAGE,
                ),
        )
    }

    private fun trackShellyRunWindow(packageName: String?, className: String?, displayId: Int) {
        // Event.displayId exists only on API 33+; below that every event reports display 0 (incl.
        // virtual-display ones), so do not feed them: the guard then falls back to comparing
        // display 0's final foreground task (see MainDisplayTaskGuard.plan).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                displayId == Display.DEFAULT_DISPLAY && isActivityWindowClass(className)) {
            // Display-0 foreground changes, for the run-end restore (no-op unless a Shelly run
            // displaced a task; see MainDisplayTaskGuard).
            (session?.getServices()?.platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform)
                    ?.onMainDisplayForeground(packageName)
        }
        // A save-draft routine has no tracker, so it is checked before the tracker: the owner
        // opening Chrome on the main display stops it at once.
        draftJob?.let { job ->
            val opened = ShellySaveDraft.ownerOpenedChrome(
                    packageName,
                    isActivityWindowClass(className),
                    displayId == Display.DEFAULT_DISPLAY,
            )
            if (!opened) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.i(TAG, "Draft routine: owner opened Chrome; stopping")
                draftStopReason = "chrome-opened"
                job.cancel()
                return
            }
            // Gate fix round 2, finding 5: below API 33 every event reports display 0, so the
            // draft's own Chrome on its virtual display looks the same as the owner opening
            // Chrome. Cancel only once the task list positively shows Chrome in front on display 0
            // (read off the main thread; an unreadable list does not cancel - the routine's own
            // ownerHasChrome checks, which fail closed, still stop it at its next step).
            serviceScope.launch(Dispatchers.IO) {
                val onMainDisplay = chromeTaskInFrontOnMainDisplay() == true
                withContext(Dispatchers.Main) {
                    if (onMainDisplay && draftJob === job) {
                        Log.i(TAG, "Draft routine: owner opened Chrome (task list); stopping")
                        draftStopReason = "chrome-opened"
                        job.cancel()
                    }
                }
            }
            return
        }
        val tracker = shellyRun ?: return
        if (!isActivityWindowClass(className)) return
        if (displayId != Display.DEFAULT_DISPLAY) {
            if (tracker.onVirtualDisplayWindow(packageName)) renderShellyRun()
            return
        }
        val conflict = tracker.onMainDisplayWindow(packageName) ?: return
        // The user brought the app ClosePaw is operating to the main display, pulling its task
        // off the virtual display. Pause rather than fight over it; Resume/Stop on the notification.
        Log.i(TAG, "Shelly run: user opened operated app $conflict on main display; pausing")
        overlayController?.requestTakeover()
        renderShellyRun()
    }

    fun stopAgent() {
        submitOp(Op.Shutdown)
        overlayController?.hideAll()
        updateStatus("🛑 Agent stopped")
    }

    fun onViewerOpened() {
        viewerBridge.onViewerOpened()
    }

    /**
     * Synchronous version of the [shouldFinishViewerOnIdle] rule. Used by
     * VirtualDisplayViewerActivity at onStart to race-proof the SharedFlow path: if the
     * agent is already idle when the user opens the viewer, the SharedFlow emit may
     * happen before the activity's collector subscribes, so we also poll directly here.
     */
    fun shouldFinishViewerNow(): Boolean = overlayController?.shouldFinishViewerNow() == true

    fun onViewerClosed() {
        viewerBridge.onViewerClosed()
    }

    fun onMainAppVisible() {
        viewerBridge.onMainAppVisible()
    }

    /**
     * Bring MainActivity to the foreground and ask it to prompt for RECORD_AUDIO. Used by the
     * overlay path — `RequestPermission` is an Activity-result contract, so the prompt cannot
     * be launched from this Service. MainActivity reads the extra in onCreate/onNewIntent and
     * fires the launcher once Compose is mounted.
     */
    fun requestVoicePermissionViaMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_VOICE_PERMISSION, true)
        }
        startActivity(intent)
    }

    fun onMainAppHidden() {
        viewerBridge.onMainAppHidden()
    }

    private fun openViewerActivity() {
        try {
            val intent =
                    Intent().setClassName(
                                    this,
                                    "ai.closepaw.ui.viewer.VirtualDisplayViewerActivity"
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open viewer", e)
        }
    }

    fun notifyViewerVisible(surfaceView: SurfaceView) {
        viewerBridge.notifyViewerVisible(surfaceView)
    }

    fun notifyViewerHidden() {
        viewerBridge.notifyViewerHidden()
    }

    fun onViewerTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        return viewerBridge.onViewerTouch(
                action = action,
                x = x,
                y = y,
                downTime = downTime,
                eventTime = eventTime,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
        )
    }

}
