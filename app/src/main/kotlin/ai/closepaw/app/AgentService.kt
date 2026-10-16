package ai.closepaw.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import ai.closepaw.util.recycleCompat
import android.view.accessibility.AccessibilityWindowInfo
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
import kotlinx.coroutines.CoroutineScope
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
        private const val FOREGROUND_RESTORE_DELAY_MS = 400L

        const val ACTION_STOP_AGENT = "ai.closepaw.STOP_AGENT"

        private const val OUR_PACKAGE = "ai.closepaw"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

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
    private val shellyNotifier by lazy { ShellyRunNotifier(this) }

    fun observeExternalSession(
            externalSession: AgentSession,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring observeExternalSession while service is shutting down")
            return
        }
        Log.i(TAG, "Observing external session: ${externalSession.sessionId}, mode=$platformMode")
        retireShellyRunUnlessFor(externalSession)
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

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        val currentSession = session
        if (currentSession != null) {
            // Detach shutdown onto a scope that outlives `serviceScope`. The session handles
            // its own checkpoint persistence via NonCancellable, so the main goal here
            // is to avoid blocking the main thread (ANR risk).
            val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            shutdownScope.launch {
                try {
                    val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        currentSession.submit(Op.Shutdown)
                        true
                    }
                    if (completed != true) {
                        Log.w(TAG, "Timed out waiting for session shutdown")
                    }
                } finally {
                    shutdownScope.cancel()
                }
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
            serviceScope.launch { oldSession?.submit(Op.Shutdown) }
        }

        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)

        serviceScope.launch {
            try {
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
        if (shellyStartInFlight) return true
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
     * Visible surface: Smart Capsule overlay + [ShellyRunNotifier] only.
     */
    internal fun startShellyRun(
            goal: String,
            platformMode: PlatformMode,
            correlation: ShellyBackgroundRun.ResultCorrelation? = null,
    ) {
        if (!isServiceActive) {
            shellyNotifier.showBlocked(ShellyBackgroundRun.Block.ACCESSIBILITY_OFF, goal)
            reportShellyStartFailure(correlation, "ClosePaw's accessibility service is not active")
            return
        }
        if (shellyStartInFlight) {
            Log.w(TAG, "Shelly run already starting; refusing a second start")
            shellyNotifier.showBlocked(ShellyBackgroundRun.Block.BUSY, goal)
            reportShellyStartFailure(correlation, "ClosePaw is busy with another task")
            return
        }
        shellyStartInFlight = true
        // Remember what the user was looking at BEFORE anything of ours becomes visible, so a
        // main-display run can hand the screen back to it when it ends (ShellyForegroundReturn).
        val foregroundAtStart = currentForegroundPackageOnDefaultDisplay()
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
            serviceScope.launch { old.submit(Op.Shutdown) }
        }

        val onMainDisplay = platformMode != PlatformMode.VIRTUAL_DISPLAY
        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)
        overlayController?.onBackgroundRunRequested()
        shellyNotifier.render(ShellyRunState.Working(onMainDisplay, emptyList()))

        val startJob = serviceScope.launch {
            try {
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
                    reportShellyStartFailure(correlation, "ClosePaw's virtual display became unavailable")
                    overlayController?.hideAll()
                    return@launch
                }
                if (settings.llmBackend == LLMBackendType.LOCAL) {
                    (newSession.getServices().llmClient as? LFMLLMClient)?.loadModel()
                }

                val ignored = buildSet {
                    add(packageName)
                    addAll(ai.closepaw.session.SystemSurfaces.resolve(this@AgentService))
                }
                val tracker = ShellyRunTracker(
                        sessionId = newSession.sessionId.value,
                        onMainDisplay = effectiveMode != PlatformMode.VIRTUAL_DISPLAY,
                        ignoredPackages = ignored,
                        correlation = correlation,
                        returnToPackage = ShellyForegroundReturn.choose(
                                foregroundPackage = foregroundAtStart,
                                onMainDisplay = effectiveMode != PlatformMode.VIRTUAL_DISPLAY,
                                selfPackage = packageName,
                                systemSurfaces = ignored,
                        ),
                )
                tracker.returnToPackage?.let { Log.i(TAG, "Shelly run: will return to $it when finished") }
                // Defensive: never leave a different session running unobserved (it would keep
                // driving the virtual display with no capsule / notification attached).
                session?.takeIf { it !== newSession }?.let { stray ->
                    Log.w(TAG, "Shelly run: shutting down stray session ${stray.sessionId}")
                    eventCollectorJob?.cancel()
                    eventCollectorJob = null
                    session = null
                    serviceScope.launch { stray.submit(Op.Shutdown) }
                }
                observeExternalSession(newSession, effectiveMode)
                shellyRun = tracker
                renderShellyRun()
                Log.i(TAG, "Shelly run started: session=${newSession.sessionId}, mode=$effectiveMode")
                newSession.submit(Op.UserInput(text = goal))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Shelly run failed to start", e)
                shellyRun = null
                shellyNotifier.render(
                        ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, e.message)
                )
                reportShellyStartFailure(correlation, "ClosePaw failed to start the task")
                overlayController?.hideAll()
            }
        }
        // Cleared on every exit path: success, VD-lost refusal, exception and cancellation.
        startJob.invokeOnCompletion {
            android.os.Handler(android.os.Looper.getMainLooper()).post { shellyStartInFlight = false }
        }
    }

    internal fun stopShellyRun() {
        if (session != null) {
            submitOp(Op.Shutdown)
        } else {
            shellyRun?.finish(ShellyRunState.Finished.Kind.STOPPED)
            renderShellyRun()
            shellyRun?.let {
                reportShellyResult(it)
                restoreForegroundApp(it)
            }
            shellyRun = null
        }
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
            restoreForegroundApp(tracker)
            shellyRun = null
        }
    }

    // ── Foreground hand-back for main-display runs ──

    /**
     * Package of the app window in front on the DEFAULT display right now (null if unknown).
     * Only ever used to decide which app to bring BACK after a main-display run; never a value
     * supplied by the caller.
     */
    private fun currentForegroundPackageOnDefaultDisplay(): String? {
        return try {
            val defaultDisplayWindows: List<AccessibilityWindowInfo> =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY) ?: emptyList()
                    } else {
                        this.windows ?: emptyList()
                    }
            val candidates = defaultDisplayWindows
                    .filter { w ->
                        w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
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
     * Bring the app that was in front when a main-display run started back to the front, once
     * the run has finished (done / failed / stopped). Uses the app's launcher intent exactly like
     * a home-screen tap (NEW_TASK + RESET_TASK_IF_NEEDED), which moves the EXISTING task to the
     * front with its state intact instead of restarting the app; an accessibility service bound
     * by the system is exempt from background-activity-start limits. No-op for VD runs.
     */
    private fun restoreForegroundApp(tracker: ShellyRunTracker) {
        val pkg = tracker.returnToPackage ?: return
        if (tracker.foregroundRestored || !ShellyForegroundReturn.shouldRestore(tracker.state)) return
        tracker.foregroundRestored = true
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "Shelly run: no launch intent for $pkg; not restoring foreground")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Let the session shutdown / overlay teardown settle first so our own surfaces do not
        // land on top of the restored app.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                startActivity(intent)
                Log.i(TAG, "Shelly run: returned to $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "Shelly run: failed to return to $pkg: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, FOREGROUND_RESTORE_DELAY_MS)
    }

    /**
     * Send a finished run's final state back to Shelly (at most once per run, and only when the
     * goal broadcast carried a result correlation). See [ShellyResultCallback].
     */
    private fun reportShellyResult(tracker: ShellyRunTracker) {
        if (tracker.resultReported || !tracker.isFinished) return
        val payload = ShellyResultCallback.payloadFor(tracker, System.currentTimeMillis()) ?: return
        tracker.resultReported = true
        ShellyResultCallback.send(applicationContext, payload)
    }

    /** A verified request that never got a tracker (it failed to start). */
    private fun reportShellyStartFailure(
            correlation: ShellyBackgroundRun.ResultCorrelation?,
            message: String,
    ) {
        ShellyResultCallback.send(
                applicationContext,
                ShellyResultCallback.failurePayload(correlation, message, System.currentTimeMillis()),
        )
    }

    private fun trackShellyRunWindow(packageName: String?, className: String?, displayId: Int) {
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
