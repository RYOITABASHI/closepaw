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
import ai.closepaw.protocol.ApprovalDetails
import ai.closepaw.protocol.ApprovalRequired
import ai.closepaw.protocol.Op
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.protocol.SessionState
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.session.AgentSession
import ai.closepaw.trace.TraceRecorder
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
import ai.closepaw.platform.virtualdisplay.MainDisplayTaskReader
import ai.closepaw.platform.virtualdisplay.TaskObservationPolicy
import ai.closepaw.platform.virtualdisplay.VirtualDisplaySurfaceSwitchFailure
import ai.closepaw.platform.virtualdisplay.VdState
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

        private val _effectivePlatformMode = MutableStateFlow<PlatformMode?>(null)
        val observedPlatformMode = _effectivePlatformMode.asStateFlow()

        private val _observedSessionState = MutableStateFlow<SessionState?>(null)
        val observedSessionState = _observedSessionState.asStateFlow()

        private val _statusFlow = MutableStateFlow<String>("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    }

    private val _externalOverlayActive = MutableStateFlow(false)
    internal val externalOverlayActive: StateFlow<Boolean> = _externalOverlayActive.asStateFlow()

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
     * keeps sessions running across activity recreation.
     */
    internal val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    @Volatile private var session: AgentSession? = null // Read off main via isVirtualDisplaySessionActive().
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
    internal fun isVirtualDisplaySessionActive(): Boolean =
        (session?.getServices()?.platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform)
            ?.isSessionActive == true ||
            ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform.anySessionActive()

    private val eventHandler =
            AgentServiceEventHandler(
                    logTag = TAG,
                    updateStatus = ::updateStatus,
                    sessionCleared = ::sessionCleared,
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

    /** The running save-draft routine, if any (no session, no LLM). Set and cleared on the main thread. */
    private var draftJob: Job? = null
    private var draftStopIdentity: String? = null

    /** Why the running draft routine was stopped from outside. Main thread. */
    private var draftStopReason: String? = null

    private val replacedShutdowns = ReplacedSessionShutdownBarrier.process
    private val shellyNotifier by lazy { ShellyRunNotifier(this) }

    private fun sessionCleared(sessionId: ai.closepaw.protocol.SessionId) {
        if (session?.let { it.sessionId == sessionId && !it.externalDriver } != true) return
        session = null
        _observedSessionState.value = null
        _effectivePlatformMode.value = null
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
        syncMcpListener()

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
                        onStop = ::requestStopAll,
                        onTakeover = { submitOp(Op.Takeover) },
                        onResume = { submitOp(Op.Resume) },
                        onSupplement = { text -> submitOp(Op.Supplement(text)) },
                        onSupplementAndResume = { text ->
                            submitOps(Op.Supplement(text), Op.Resume)
                        },
                        onUserResponse = { callId, response -> submitOp(Op.UserResponse(callId, response)) },
                        onApprovalResponse = { callId, decision, scope, packageName ->
                            submitOp(Op.Approve(callId, decision, scope, packageName))
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

    private fun clearAccessibilityBinding() {
        isServiceActive = false
        if (instance === this) instance = null
        McpServerController.brainSessions?.onOwnerServiceLost(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearAccessibilityBinding()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearAccessibilityBinding()

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        session = null
        _observedSessionState.value = null
        _effectivePlatformMode.value = null

        overlayController?.retireExternalOverlay()
        overlayController?.dispose()
        overlayController = null
        _externalOverlayActive.value = false
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
        // Start listening even if Android creates the service but delays the bind callback.
        // onServiceConnected also syncs, so it can retry a failed early start.
        syncMcpListener()
    }

    private var mcpWatchdog: Job? = null

    private fun syncMcpListener() {
        McpRecoveryService.ensureStarted(applicationContext, "accessibility")
        if (mcpWatchdog?.isActive != true) {
            mcpWatchdog = serviceScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(McpRecoveryService.CHECK_INTERVAL_MS)
                    McpRecoveryService.ensureStarted(applicationContext, "accessibility_watchdog")
                    if (!McpRecoveryService.resident) syncMcpAndCheck()
                }
            }
        }
        serviceScope.launch { syncMcpAndCheck() }
    }

    private suspend fun syncMcpAndCheck() = withContext(Dispatchers.IO) {
        try {
            McpServerController.sync(applicationContext)
            McpServerController.checkRecovery()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MCP startup failed: ${e.javaClass.simpleName}")
        }
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
                            if (session !== agentSession) return@collect
                            if (agentSession.externalDriver && event is ai.closepaw.protocol.SessionCompleted) return@collect
                            try {
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

    internal fun isSessionBusy(): Boolean =
        BrainSessionAdmission.gate.isInternalActive() ||
            session?.state?.value?.let { it != SessionState.Shutdown } == true ||
            isInternalIntakeBusy()

    // Chrome draft admission uses the same shared construction fence as external sessions.
    internal fun isInternalIntakeBusy(): Boolean =
        BrainSessionAdmission.gate.hasPendingIntakeConstruction() || draftJob != null ||
            when (session?.state?.value) {
                SessionState.Running, SessionState.Paused, SessionState.TakeoverPending -> true
                else -> false
            }

    private fun shutdownReplacedSession(old: AgentSession?, externalReconciliation: Job? = null): Job? {
        if (old == null || (old.externalDriver && externalReconciliation == null)) return null
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
        // Its result (if any) was reported by the replacing path before this call: release a
        // run-end restore still gated on that report, then order the teardown after it.
        vdPlatform?.onRunResultReported()
        // Not in serviceScope: service destruction must not cancel it before the teardown ran
        // (launchReplacedSessionTeardown: non-cancellable critical path, truthful verdict).
        // Owned by the old platform: the old session itself never waits for its own teardown.
        // Registered (when mustAwait) before this returns, i.e. before the caller detaches it.
        if (mustAwait && externalReconciliation != null) {
            replacedShutdowns.add(externalReconciliation, owner = platform)
        }
        return launchRegisteredSessionTeardown(
                barrier = replacedShutdowns,
                scope = replacedTeardownScope,
                mustAwait = mustAwait && externalReconciliation == null,
                owner = platform,
                runEndRestore = runEndRestore,
                shutdown = { old.submit(Op.Shutdown) },
                // VD: stop() reached Stopped (receiver gone, deferral triggers inert). Others: the
                // shutdown returning is the teardown.
                isTornDown = { vdPlatform?.isStopped ?: true },
                stopPlatform = { vdPlatform?.stop() },
                onFailure = { e ->
                    Log.e(TAG, "Replaced session teardown did not complete" +
                            (if (mustAwait && externalReconciliation == null) "; it stays in the barrier and new starts fail" else ""), e)
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
    internal suspend fun awaitReplacedShutdowns(except: Any, beforeReconcile: () -> Unit = {}) {
        val clear = withTimeoutOrNull(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS) {
            BrainSessionAdmission.gate.awaitPendingPredecessors(except)
            beforeReconcile()
            replacedShutdowns.awaitClear(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS)
        } == true
        if (!clear) {
            Log.e(TAG, "Previous start or shutdown did not settle within ${REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS} ms; not starting")
            throw IllegalStateException("the previous task is still shutting down; try again")
        }
    }

    internal fun stopShellyRun(identity: String) {
        session?.takeIf { it.externalDriver && matchesRunStop(identity, it.sessionId.value) }?.let {
            McpServerController.brainSessions?.stopSession(it)
            return
        }
        if (matchesRunStop(identity, draftStopIdentity)) {
            draftJob?.cancel()
            return
        }
    }

    // ── Save-draft routine (fixed, LLM-free; see ShellySaveDraftReceiver) ──

    /** True when Chrome is, or may be, what the user has in front on the main display. */
    internal suspend fun isChromeInFront(): Boolean {
        val window = currentForegroundPackageOnDefaultDisplay()
        val requireTaskObservation = TaskObservationPolicy.helperRouteEnabled(Build.VERSION.SDK_INT)
        val task = chromeTaskInFrontOnMainDisplay(requireTaskObservation)
        val chrome = ShellySaveDraft.chromeInFront(window, task, requireTaskObservation)
        if (chrome) Log.i(TAG, "chrome in front: windowIsChrome=${window == ChromeFlagDeepLink.CHROME_PACKAGE} windowKnown=${window != null} task=$task")
        return chrome
    }

    private val taskReader by lazy { MainDisplayTaskReader(this) }

    /**
     * A second look, from the task list: true when the front visible root task on the main display
     * is Chrome's. Null when the front task cannot be determined.
     */
    private suspend fun chromeTaskInFrontOnMainDisplay(useHelper: Boolean): Boolean? = try {
        val tasks = taskReader.read(useHelper)
        val front = tasks?.firstOrNull { it.visible != false } ?: tasks?.firstOrNull()
        if (front == null || (useHelper && front.packages.isEmpty())) null
        else ChromeFlagDeepLink.CHROME_PACKAGE in front.packages
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
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
        val token = Any()
        check(BrainSessionAdmission.tryClaimInternal(token))
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
                        draftStopIdentity = null
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
        val stopIdentity = java.util.UUID.randomUUID().toString()
        draftStopIdentity = stopIdentity
        draftJob = job
        job.invokeOnCompletion {
            BrainSessionAdmission.releaseInternal(token)
            // Normally a no-op: the body's finally has reported and completed the teardown.
            teardown.complete()
            if (reported.compareAndSet(false, true)) {
                Log.w(TAG, "Draft routine ended before its report; reporting it stopped")
                ShellyResultCallback.send(
                        applicationContext,
                        draftPayload(routine.resultSoFar(draftStopReason ?: "stopped"), correlation),
                )
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (draftJob === job) {
                    draftJob = null
                    draftStopIdentity = null
                }
            }
        }
        // After the receiver's answer is committed (this runs inside it, on the main thread, so a
        // post lands after it), and only while this draft is still the current one, so it can never
        // overwrite the finished notification.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (draftJob === job && !reported.get()) {
                try {
                    shellyNotifier.render(ShellyRunState.Working(false, listOf(ChromeFlagDeepLink.CHROME_PACKAGE)),
                            stopIdentity = stopIdentity)
                } catch (e: Exception) {
                    Log.w(TAG, "Draft routine notification failed: ${e.javaClass.simpleName}")
                }
            }
        }
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
                val onMainDisplay = chromeTaskInFrontOnMainDisplay(
                    TaskObservationPolicy.helperRouteEnabled(Build.VERSION.SDK_INT)
                ) == true
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
    }

    /** Builds a session for an external (MCP brain) driver: creates only the platform and supporting session services. */
    internal fun createExternalSession(
            platformMode: PlatformMode,
            runHandle: String,
            isDispatchCancelled: () -> Boolean,
            onTraceRecorderCreated: (TraceRecorder) -> Unit,
    ): AgentSession {
        val settings = AppSettingsState.create(applicationContext).also { it.load() }
        val config =
                SessionConfig(
                        approvalMode = settings.approvalMode,
                        debugMode = settings.debugMode,
                        traceEnabled = true,
                        traceRunId = runHandle,
                        perceptionConfig = when (settings.perceptionMode) {
                            "screenshot_only" -> PerceptionConfig.ScreenshotOnly()
                            "hybrid" -> PerceptionConfig.Hybrid()
                            else -> PerceptionConfig.AccessibilityOnly
                        },
                        platformMode = platformMode,
                        unattended = true,
                        preserveMainDisplayTasks = true,
                )
        // Publish the recorder before construction can fail. Once constructed, return
        // directly so the manager can register the session before the dispatcher hop ends.
        return AgentSession.createForExternalDriver(
                config = config,
                service = this,
                scope = serviceScope,
                // The shared host creates a display-0 window even in viewer context.
                visualizer = null,
                overlayTouchGate = overlayController?.overlayTouchGate,
                onTraceRecorderCreated = onTraceRecorderCreated,
                isDispatchCancelled = isDispatchCancelled,
        )
    }

    /** Waits (bounded, service-internal) for a replaced session's teardown to settle. */

    internal fun ownsExternalSession(external: AgentSession): Boolean = session === external

    internal fun isExternalSessionOwnerActive(): Boolean = isServiceActive && instance === this

    internal fun shutdownExternalSession(external: AgentSession, reconciliation: Job): Job =
        checkNotNull(shutdownReplacedSession(external, externalReconciliation = reconciliation))

    internal suspend fun fallbackExternalSessionTeardown(external: AgentSession) {
        val platform = external.getServices().platform
        val vdPlatform = platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
        try {
            try {
                vdPlatform?.onRunResultReported()
            } finally {
                external.submit(Op.Shutdown)
            }
        } finally {
            platform.stop()
            check(vdPlatform?.isStopped != false) { "External session platform did not stop" }
        }
    }

    /**
     * Attaches an external-driver session for observation (VD viewer, approval surfaces).
     * No Shelly tracker, pending result or notifier state is created. False when another
     * live session or an in-flight start owns the service — the caller must refuse.
     * Main thread only.
     */
    internal fun attachExternalSession(newSession: AgentSession, platformMode: PlatformMode, startToken: Any): Boolean {
        if (!isExternalSessionOwnerActive() || BrainSessionAdmission.gate.isInternalActive()) return false
        if (BrainSessionAdmission.gate.hasPendingConstruction(except = startToken)) return false
        if (draftJob != null) return false
        if (session?.state?.value?.let { it != SessionState.Shutdown } == true) return false

        val previousMode = currentPlatformMode
        val onMainDisplay = platformMode != PlatformMode.VIRTUAL_DISPLAY
        try {
            // Install suppression and observation before committing the session identity.
            _externalOverlayActive.value = true
            overlayController?.onBackgroundRunRequested(onMainDisplay, ShellyRunOrigin.MCP, externalDriver = true)
            overlayController?.setPlatformMode(platformMode)
            overlayController?.updateShellyRunDisplay(onMainDisplay)
            observeSession(newSession)
        } catch (failure: Throwable) {
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            try {
                overlayController?.retireExternalOverlay()
                _externalOverlayActive.value = false
                overlayController?.setPlatformMode(previousMode)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        session = newSession
        _observedSessionState.value = newSession.state.value
        currentPlatformMode = platformMode
        _effectivePlatformMode.value = platformMode
        return true
    }

    /** Detaches an external-driver session previously attached with [attachExternalSession]. */
    internal fun detachExternalSession(detached: AgentSession) {
        if (session !== detached) return
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        try {
            overlayController?.retireExternalOverlay()
            _externalOverlayActive.value = false
        } finally {
            session = null
            _observedSessionState.value = null
            _effectivePlatformMode.value = null
        }
    }

    /** Feeds an external session's approval into the capsule state so it can be resolved
     *  from the VD viewer (Op.Approve). No tracker or notification is involved. */
    internal fun surfaceExternalApproval(live: AgentSession, details: ApprovalDetails): Boolean {
        if (!isServiceActive || session !== live) return false
        val event = ApprovalRequired(live.sessionId, System.currentTimeMillis(), details.description, details)
        eventHandler.handleEvent(event, live.getServices().recordingService)
        return true
    }

    /** Clears a resolved external approval from the capsule state. */
    internal fun clearExternalApproval(live: AgentSession, callId: String) {
        if (session !== live) return
        overlayController?.stateHolder?.onApprovalResolved(callId)
    }

    private fun requestStopAll() {
        val brains = McpServerController.brainSessions
        val observed = session
        if (observed?.externalDriver == true) brains?.stopSession(observed)
        brains?.requestStopAll()
        if (observed?.externalDriver != true) submitOp(Op.Shutdown)
    }

    fun stopAgent() {
        requestStopAll()
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

    @Throws(VirtualDisplaySurfaceSwitchFailure::class)
    fun notifyViewerVisible(surfaceView: SurfaceView) {
        viewerBridge.notifyViewerVisible(surfaceView)
    }

    @Throws(VirtualDisplaySurfaceSwitchFailure::class)
    fun notifyViewerHidden() {
        viewerBridge.notifyViewerHidden()
    }

    internal fun viewerTouchTarget(): VdState.Running? = viewerBridge.viewerTouchTarget()

    internal suspend fun onViewerTouch(
            target: VdState.Running,
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        return viewerBridge.onViewerTouch(
                target = target,
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
