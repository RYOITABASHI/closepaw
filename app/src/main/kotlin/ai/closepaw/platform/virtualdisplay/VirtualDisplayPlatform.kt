package ai.closepaw.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.accessibility.AccessibilityWindowInfo
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.model.ScreenSnapshotDebug
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.NodeActionPerformer
import ai.closepaw.platform.UIAction
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.trace.TraceRecorder
import ai.closepaw.ui.overlay.visualizer.ActionVisualizerManager
import ai.closepaw.util.recycleCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * VIRTUAL_DISPLAY_FLAG_OWN_FOCUS (1 shl 14) | VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
 * (1 shl 16): hidden, API 34+, both require VIRTUAL_DISPLAY_FLAG_TRUSTED (and STEAL_TOP_FOCUS_
 * DISABLED requires OWN_FOCUS). Without them only the top-focused display has a focused window
 * (per-display focus is off on phones): every launch / injected tap on the VD moves it to the
 * top, display 0 loses window focus, and the user's own display-0 app loses its IME connection
 * (build 61, defect 1). With them the VD keeps its own focused window (agent key events still
 * reach it) and never becomes the top-focused display, so the IME stays with display 0.
 */
internal const val VD_FOCUS_ISOLATION_FLAGS = (1 shl 14) or (1 shl 16)

/**
 * Optional creation flags for the VD (requested fail-open, see [createWithOptionalFlags]):
 * the focus-isolation flags on API 34+ for background runs that must leave the user's display-0
 * keyboard alone ([backgroundRun] = Shelly runs, SessionConfig.preserveMainDisplayTasks); 0
 * otherwise, so API 31-33 and foreground (MainActivity) sessions create the VD exactly as before.
 * Foreground sessions are excluded because a VD that can never take top focus cannot get an IME
 * for viewer takeover typing, their main takeover path.
 */
internal fun vdOptionalDisplayFlags(sdkInt: Int, backgroundRun: Boolean): Int =
        if (backgroundRun && sdkInt >= 34) VD_FOCUS_ISOLATION_FLAGS else 0

/**
 * VirtualDisplayPlatform — AndroidPlatform running on a Shizuku virtual display.
 *
 * Lifecycle is serialized through [VdLifecycleArbiter]: start/stop take exclusive access,
 * captureScreen/performAction run under a shared Running lease. Binder death transitions
 * the platform to Broken and clears cached proxies.
 */
class VirtualDisplayPlatform(
        private val service: AccessibilityService,
        private val shizuku: ShizukuClient,
        private val config: VirtualDisplayConfig,
        private val sessionConfig: SessionConfig,
        private val traceRecorder: TraceRecorder,
        private val visualizer: ActionVisualizerManager? = null,
        private val isPackageBlocked: (String?) -> Boolean = { false }
) : AndroidPlatform {

    override val mode: ai.closepaw.protocol.PlatformMode = ai.closepaw.protocol.PlatformMode.VIRTUAL_DISPLAY

    companion object {
        private const val TAG = "VirtualDisplayPlatform"

        /**
         * Virtual display flags bitmask:
         *   0x001 = VIRTUAL_DISPLAY_FLAG_PUBLIC
         *   0x008 = VIRTUAL_DISPLAY_FLAG_SECURE
         *   0x040 = VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
         *   0x200 = VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
         *   0x400 = VIRTUAL_DISPLAY_FLAG_TRUSTED (hidden)
         *   0x800 = VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP (hidden)
         */
        private const val DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800

        /**
         * WindowManager.DISPLAY_IME_POLICY_* (hidden). HIDE: an IME request from the VD shows no
         * keyboard anywhere (without a policy the IME falls back to display 0, bound to the VD
         * app). FALLBACK: show it on display 0 — needed while the viewer is open so takeover
         * typing works. The per-action SHOW_MODE_HIDDEN suppression stays as a second layer.
         */
        private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
        private const val DISPLAY_IME_POLICY_HIDE = 2

        private const val SURFACE_READY_DELAY_MS = 200L
        private const val IMAGE_READER_MAX_IMAGES = 2
    }

    // ── Lifecycle State ──────────────────────────────────────────

    private val arbiter = VdLifecycleArbiter()
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null

    /** The VD viewer is open (takeover typing needs the IME on display 0). */
    @Volatile private var viewerOpen = false

    /**
     * IME policy last applied to the current VD (null = none / unknown). Guarded by
     * [imePolicyLock] together with the apply, so the last writer always applies the latest
     * [viewerOpen] (viewer callbacks race with [start] publishing Running).
     */
    private var appliedImePolicy: Int? = null
    private val imePolicyLock = Any()

    /**
     * Build 59 deferred task return: ACTION_SCREEN_OFF receiver, registered only while the
     * restorer holds a deferred restore (and unregistered when it clears or the VD stops).
     * Guarded by [deferralLock].
     */
    private var screenOffReceiver: BroadcastReceiver? = null
    private val deferralLock = Any()

    /** Runs deferred-restore triggers off the caller's thread; never cancelled (jobs are short). */
    private val deferralScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Records / orders the run-end display-0 return ([onTaskEnded]); a follow-up [start] waits for it. */
    private val runEnd = RunEndRestoreSequencer(deferralScope)

    // ── Component Wiring ─────────────────────────────────────────

    private val displayIdProvider: () -> Int = {
        when (val s = arbiter.state) {
            is VdState.Running -> s.displayId
            is VdState.Draining -> s.displayId
            else -> Display.INVALID_DISPLAY
        }
    }
    private val imageReaderProvider: () -> ImageReader? = {
        when (val s = arbiter.state) {
            is VdState.Running -> s.imageReader
            is VdState.Draining -> s.imageReader
            else -> null
        }
    }

    private val windowAccessor = VirtualDisplayWindowAccessor(service, displayIdProvider)

    private val nodeActionPerformer =
            NodeActionPerformer(rootProvider = { windowAccessor.getRootOnDisplay() })

    private val inputInjector = VirtualDisplayInputInjector(shizuku, displayIdProvider)
    private val screenshotProcessor =
            VirtualDisplayScreenshotProcessor(
                    service = service,
                    sessionConfig = sessionConfig,
                    traceRecorder = traceRecorder
            )
    /** Shelly background runs only (see [MainDisplayTaskGuard]); null keeps the old behavior. */
    private val mainDisplayTasks: MainDisplayTaskRestorer? =
            if (sessionConfig.preserveMainDisplayTasks) {
                MainDisplayTaskRestorer(
                        shizuku = shizuku,
                        homePackages = ai.closepaw.session.SystemSurfaces.resolve(service) -
                                ai.closepaw.session.SystemSurfaces.SYSTEM_UI,
                        context = service,
                )
            } else null

    private val appController =
            VirtualDisplayAppController(
                    service = service,
                    shizuku = shizuku,
                    displayIdProvider = displayIdProvider,
                    mainDisplayTasks = mainDisplayTasks,
            )
    private val surfaceController =
            VirtualDisplaySurfaceController(
                    shizuku = shizuku,
                    displayIdProvider = displayIdProvider,
                    imageReaderProvider = imageReaderProvider
            )
    private val captureCoordinator =
            VirtualDisplayCaptureCoordinator(
                    config = config,
                    windowAccessor = windowAccessor,
                    imageReaderProvider = imageReaderProvider,
                    surfaceController = surfaceController,
                    switchToImageReader = { switchToImageReader() },
                    screenshotProcessor = screenshotProcessor,
                    traceRecorder = traceRecorder
            )
    private val viewerTouchHandler =
            VirtualDisplayViewerTouchHandler(
                    config = config,
                    displayIdProvider = displayIdProvider,
                    inputInjector = inputInjector,
                    shizuku = shizuku
            )

    // ── Lifecycle ────────────────────────────────────────────────

    override suspend fun start() {
        arbiter.withLifecycleTransition { previous ->
            if (previous is VdState.Running) {
                // A new run (Hot Idle follow-up) needs the VD: a deferred display-0 restore is
                // executed and CONFIRMED first (bounded retry), so the run never starts with the
                // user's tasks still on the VD. If that cannot be confirmed, the start fails (the
                // session reports "Platform start failed" and runs nothing); the deferral degrades
                // to a pending retry so it does not hold Hot Idle teardown (which restores / hands
                // the tasks over), and the tasks stay owed, so the next start reconciles again.
                mainDisplayTasks?.let { restorer ->
                    // The previous task's run-end restore (requested before the session went
                    // Idle) decides / records its deferral first, then the reconcile sees it.
                    runEnd.awaitRunEnd() // never releases it: a result report it waits for stays first
                    val outcome = restorer.reconcileBeforeRun(previous.displayId)
                    if (outcome == MainDisplayReconcileOutcome.FAILED) restorer.dropDeferral()
                    syncScreenOffReceiver()
                    if (outcome == MainDisplayReconcileOutcome.FAILED) {
                        throw IllegalStateException(
                                "Display-0 tasks ${restorer.unreconciledTaskIds()} are still on the " +
                                        "virtual display; not starting a run on it"
                        )
                    }
                }
                return@withLifecycleTransition
            }

            // Clean up leaked resources from a Broken state before allocating new ones
            if (previous is VdState.Broken) {
                Log.w(TAG, "start() from Broken state — cleaning up old resources first")
                binderDeadListener?.let { shizuku.removeBinderDeadListener(it) }
                binderDeadListener = null
                surfaceController.reset()
                shizuku.releaseVirtualDisplay(previous.displayId)
                previous.imageReader.close()
                shizuku.clearCachedProxies()
            }

            shizuku.bypassHiddenApis()

            val reader =
                    ImageReader.newInstance(
                            config.width,
                            config.height,
                            PixelFormat.RGBA_8888,
                            IMAGE_READER_MAX_IMAGES
                    )

            val id =
                    try {
                        shizuku.createVirtualDisplay(
                                name = "closepaw_agent_display",
                                width = config.width,
                                height = config.height,
                                densityDpi = config.densityDpi,
                                surface = reader.surface,
                                flags = DISPLAY_FLAGS,
                                imePolicy = DISPLAY_IME_POLICY_HIDE,
                                optionalFlags = vdOptionalDisplayFlags(
                                        android.os.Build.VERSION.SDK_INT,
                                        backgroundRun = sessionConfig.preserveMainDisplayTasks,
                                )
                        )
                    } catch (e: Exception) {
                        reader.close()
                        throw e
                    }

            if (id < 0) {
                reader.close()
                throw IllegalStateException("Failed to create virtual display (returned $id)")
            }

            // IME policy before anything can launch on the VD (the IME display is resolved at
            // startInput time). Fail-open: a missing API or permission only loses this layer.
            val atCreation = try {
                shizuku.imePolicyAppliedAtCreation(id)
            } catch (e: Exception) {
                false
            }
            synchronized(imePolicyLock) {
                appliedImePolicy = if (atCreation) DISPLAY_IME_POLICY_HIDE else null
            }
            reconcileImePolicy(id)

            // Resources allocated — register binder death listener and commit state.
            // If anything fails here, roll back: release display, close reader, reset state.
            try {
                val listener =
                        Shizuku.OnBinderDeadListener {
                            Log.e(TAG, "Shizuku binder died! displayId=$id")
                            arbiter.markBroken("Shizuku binder died")
                            shizuku.clearCachedProxies()
                        }
                binderDeadListener = listener
                shizuku.addBinderDeadListener(listener)

                arbiter.transitionTo(VdState.Running(id, reader))
                // A viewer open/close that arrived before Running was published saw no running
                // display and only recorded its state: apply the latest one now.
                reconcileImePolicy(id)
                delay(SURFACE_READY_DELAY_MS)

                Log.i(
                        TAG,
                        "Started: displayId=$id, " +
                                "${config.width}x${config.height}@${config.densityDpi}dpi"
                )
            } catch (e: Exception) {
                binderDeadListener?.let { shizuku.removeBinderDeadListener(it) }
                binderDeadListener = null
                shizuku.releaseVirtualDisplay(id)
                reader.close()
                shizuku.clearCachedProxies()
                arbiter.transitionTo(VdState.Stopped)
                throw e
            }
        }
    }

    /**
     * Release platform resources. Must be idempotent.
     * Serialized through the lifecycle arbiter — waits for in-flight ops to complete.
     */
    override suspend fun stop() {
        arbiter.withLifecycleTransition(
            preDrainTransform = { current ->
                // Move to Draining so new withRunningLease calls fail fast, but providers
                // can still resolve displayId/imageReader for in-flight ops.
                when (current) {
                    is VdState.Running -> VdState.Draining(current.displayId, current.imageReader)
                    else -> current
                }
            }
        ) { previous ->
            if (previous == VdState.Stopped) return@withLifecycleTransition

            // Restore keyboard first — prevent permanently disabled IME on crash
            setKeyboardAuto()

            binderDeadListener?.let { shizuku.removeBinderDeadListener(it) }
            binderDeadListener = null

            surfaceController.reset()

            // A pending deferred restore needs no trigger any more: the restore below executes it
            // (frozen order, refront from display 0 now); what it cannot move is handed over.
            unregisterScreenOffReceiver()

            // Before the VD task cleanup below removes them: tasks this run moved off display 0
            // go back there (no-op unless a Shelly run displaced something).
            mainDisplayTasks?.let { restorer ->
                when (previous) {
                    is VdState.Running -> restorer.restore(previous.displayId)
                    is VdState.Draining -> restorer.restore(previous.displayId)
                    is VdState.Broken -> restorer.restore(previous.displayId)
                    VdState.Stopped -> {}
                }
            }

            // Protected tasks still on the VD are kept by the cleanup below, so the release hands
            // them to display 0, where they land on top. Decide the refront that undoes that now
            // (from display 0 as the user has it), execute it once the release has handed them over.
            val handover = mainDisplayTasks?.let { restorer ->
                when (previous) {
                    is VdState.Running -> restorer.prepareHandover(previous.displayId)
                    is VdState.Draining -> restorer.prepareHandover(previous.displayId)
                    is VdState.Broken -> restorer.prepareHandover(previous.displayId)
                    VdState.Stopped -> null
                }
            }

            // Clean up resources from Running, Draining, or Broken state
            when (previous) {
                is VdState.Running -> {
                    releaseDisplayResources(previous.displayId, previous.imageReader)
                }
                is VdState.Draining -> {
                    releaseDisplayResources(previous.displayId, previous.imageReader)
                }
                is VdState.Broken -> {
                    releaseDisplayResources(previous.displayId, previous.imageReader)
                }
                VdState.Stopped -> {} // Already handled by early return above
            }

            if (handover != null) mainDisplayTasks?.refrontAfterHandover(handover)

            shizuku.clearCachedProxies()
            arbiter.transitionTo(VdState.Stopped)

            Log.i(TAG, "Stopped")
        }
    }

    private fun releaseDisplayResources(displayId: Int, imageReader: ImageReader) {
        removeRootTasksBeforeRelease(displayId)
        shizuku.releaseVirtualDisplay(displayId)
        imageReader.close()
    }

    private fun removeRootTasksBeforeRelease(displayId: Int) {
        try {
            // Displaced user tasks that could not be moved back are kept: releasing the display
            // then hands them to display 0 instead of destroying them.
            shizuku.removeRootTasksOnDisplay(displayId, mainDisplayTasks?.protectedTaskIds().orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "VD task cleanup before release failed; continuing teardown", e)
        }
    }

    /**
     * Return app tasks this run moved off the main display (Shelly background runs). Called at
     * run end so the user's display-0 context comes back immediately rather than at the Hot Idle
     * teardown; [stop] repeats it as a safety net. No-op for other sessions.
     *
     * Build 59: when the user has moved on (a refront would be owed) the return is DEFERRED
     * ([MainDisplayTaskRestorer.restoreAtRunEnd]) to avoid the move-then-refront flash: the tasks
     * stay on the VD (kept alive, see [hasDeferredWork]) until the screen turns off, a new run
     * starts ([start]) or the VD is torn down ([stop]). With the screen already off, nobody can
     * see the flash: the restore runs immediately.
     */
    suspend fun restoreMainDisplayTasks() {
        val restorer = mainDisplayTasks ?: return
        val displayId = when (val s = arbiter.state) {
            is VdState.Running -> s.displayId
            else -> return
        }
        if (isScreenInteractive()) restorer.restoreAtRunEnd(displayId) else restorer.restore(displayId)
        syncScreenOffReceiver()
    }

    /**
     * The session's task ended (TaskCompleted emitted, before it goes Idle): record the run-end
     * display-0 return ([restoreMainDisplayTasks]) so a follow-up [start] and a replacing session
     * (AgentService) can order themselves after it. When the run owes a result report
     * ([expectRunResultReport]) it runs only after [onRunResultReported] (result callback first,
     * as before). No-op for sessions that do not preserve display-0 tasks.
     */
    override fun onTaskEnded() {
        val restorer = mainDisplayTasks ?: return
        if (arbiter.state !is VdState.Running) return
        runEnd.onTaskEnded(
                // The result report never arrived in time: no move now (it may still arrive). Every
                // displaced task stays owed: the next start's reconcile (start) or teardown (stop)
                // places it; Hot Idle is not held, so the idle-timeout teardown bounds the wait.
                onAbandoned = { restorer.oweAbandonedRunEndReturn() },
        ) {
            try {
                restoreMainDisplayTasks()
            } catch (e: Exception) {
                Log.w(TAG, "run-end main display restore failed", e)
            }
        }
    }

    /** The current run's result will be reported by the service (Shelly run tracker attached). */
    fun expectRunResultReport() = runEnd.expectResultReport()

    /** The service reported the run's result: the run-end return may run now. */
    fun onRunResultReported() = runEnd.resultReported()

    /** The run-end restore recorded by [onTaskEnded] while it has not completed (incl. still gated). */
    fun runEndRestoreInFlight(): kotlinx.coroutines.Job? = runEnd.inFlight()

    /**
     * A deferred display-0 restore is pending on a running VD: Hot Idle teardown waits (see
     * [ai.closepaw.platform.AndroidPlatform.hasDeferredWork]). False once the VD is not Running
     * (e.g. Broken), so a dead VD never keeps the session alive.
     */
    override fun hasDeferredWork(): Boolean =
            arbiter.state is VdState.Running && mainDisplayTasks?.hasDeferredRestore() == true

    /**
     * Teardown ([stop]) of this platform may move tasks to / refront on display 0 (Shelly runs:
     * restore + release handover). A session replacing this one must not start before that
     * teardown has completed (see AgentService.shutdownReplacedSession).
     */
    val returnsMainDisplayTasks: Boolean get() = mainDisplayTasks != null

    /**
     * [stop] has completed (or the VD never ran). From here on this platform cannot move tasks:
     * the screen-off receiver is unregistered, and every deferral trigger / run-end restore still
     * launched on [deferralScope] needs a Running lease or Running state and does nothing.
     */
    val isStopped: Boolean get() = arbiter.state == VdState.Stopped

    /**
     * The VD viewer closed: re-evaluate a pending deferral. Screen on -> stay deferred (or clear
     * if the user already pulled every deferred task back); screen off -> execute it. Opening the
     * viewer never triggers a restore.
     */
    fun onViewerClosedRecheckDeferral() {
        if (mainDisplayTasks?.hasDeferredRestore() != true) return
        deferralScope.launch {
            runDeferredTrigger("viewer closed") { restorer, displayId ->
                if (isScreenInteractive()) restorer.recheckDeferred(displayId) else restorer.restoreDeferred(displayId)
            }
        }
    }

    /** Screen turned off: execute the deferred restore (nobody sees the moves now). */
    private fun onScreenOff() {
        deferralScope.launch {
            runDeferredTrigger("screen off") { restorer, displayId -> restorer.restoreDeferred(displayId) }
        }
    }

    /**
     * Run a deferral trigger under a Running lease ([stop] drains it before releasing the VD),
     * then register / unregister the screen-off receiver to match what is left.
     */
    private suspend fun runDeferredTrigger(
            reason: String,
            action: suspend (MainDisplayTaskRestorer, Int) -> Unit,
    ) {
        val restorer = mainDisplayTasks ?: return
        try {
            arbiter.withRunningLease { running -> action(restorer, running.displayId) }
        } catch (e: PlatformNotRunningException) {
            Log.d(TAG, "deferred restore ($reason): VD not running; teardown handles it")
        } catch (e: Exception) {
            Log.w(TAG, "deferred restore ($reason) failed", e)
        } finally {
            syncScreenOffReceiver()
        }
    }

    /**
     * Keep the screen-off receiver registered exactly while a deferral is pending on a running
     * VD. The eligibility check and the registration happen under [deferralLock], the lock
     * [stop]'s unregister takes AFTER it has left Running: a late call either registers before
     * that unregister (which then removes it) or sees a non-running VD and registers nothing.
     * Registering re-checks the screen afterwards: a screen-off that happened before the receiver
     * existed would otherwise be missed. If registration fails, no trigger can fire: the deferral
     * is dropped and the restore runs now (build-58 behavior), so Hot Idle teardown is not held.
     */
    private fun syncScreenOffReceiver() {
        val registered = synchronized(deferralLock) {
            if (!hasDeferredWork()) {
                unregisterScreenOffReceiverLocked()
                return
            }
            if (screenOffReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
                }
            }
            try {
                val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    // A protected system broadcast is still delivered to a non-exported receiver.
                    service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    service.registerReceiver(receiver, filter)
                }
                screenOffReceiver = receiver
                true
            } catch (e: Exception) {
                Log.w(TAG, "screen-off receiver registration failed; restoring now instead of deferring", e)
                false
            }
        }
        if (!registered) {
            restoreNowWithoutDeferral()
        } else if (!isScreenInteractive()) {
            onScreenOff()
        }
    }

    /** No deferral trigger is available: degrade to a build-58 restore right away (one attempt). */
    private fun restoreNowWithoutDeferral() {
        val restorer = mainDisplayTasks ?: return
        restorer.dropDeferral()
        deferralScope.launch {
            try {
                arbiter.withRunningLease { running -> restorer.restore(running.displayId) }
            } catch (e: PlatformNotRunningException) {
                Log.d(TAG, "fallback restore: VD not running; teardown handles it")
            } catch (e: Exception) {
                Log.w(TAG, "fallback restore failed", e)
            }
        }
    }

    private fun unregisterScreenOffReceiver() {
        synchronized(deferralLock) { unregisterScreenOffReceiverLocked() }
    }

    /** Call with [deferralLock] held. */
    private fun unregisterScreenOffReceiverLocked() {
        val receiver = screenOffReceiver ?: return
        screenOffReceiver = null
        try {
            service.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "screen-off receiver was not registered", e)
        }
    }

    /** Whether the device is interactive (screen on); unknown counts as on (the safe side). */
    private fun isScreenInteractive(): Boolean = try {
        (service.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive ?: true
    } catch (e: Exception) {
        true
    }

    /**
     * An activity window of [packageName] came to the foreground on the main display. Lets the
     * restore tell "user untouched display 0" from "user acted there" (no-op for other sessions).
     */
    fun onMainDisplayForeground(packageName: String?) {
        mainDisplayTasks?.onMainDisplayForeground(packageName)
    }

    // ── Per-display IME policy ───────────────────────────────────

    /**
     * The VD viewer opened or closed: the VD's IME policy follows (FALLBACK while open so the
     * user can type during takeover, HIDE otherwise). The policy state lives here, with the
     * display id; the service layer only signals open/close.
     */
    fun onViewerVisibilityChanged(open: Boolean) {
        viewerOpen = open
        val displayId = (arbiter.state as? VdState.Running)?.displayId ?: return
        reconcileImePolicy(displayId)
    }

    /** Apply the policy [viewerOpen] calls for, unless it is already in effect. Fail-open. */
    private fun reconcileImePolicy(displayId: Int) {
        synchronized(imePolicyLock) {
            val policy = if (viewerOpen) DISPLAY_IME_POLICY_FALLBACK_DISPLAY else DISPLAY_IME_POLICY_HIDE
            if (appliedImePolicy == policy) return
            try {
                if (shizuku.setDisplayImePolicy(displayId, policy)) {
                    appliedImePolicy = policy
                } else {
                    Log.w(TAG, "VD IME policy $policy not applied (display $displayId); per-action suppression only")
                }
            } catch (e: Exception) {
                Log.w(TAG, "VD IME policy $policy failed (display $displayId); continuing", e)
            }
        }
    }

    // ── Hybrid Surface Switching ──────────────────────────────────

    /**
     * Switch VirtualDisplay output to the Viewer's SurfaceView for 60fps live preview. Called when
     * the Viewer Activity becomes visible.
     */
    fun switchToLivePreview(surfaceView: SurfaceView) {
        if (arbiter.state !is VdState.Running) return

        surfaceController.switchToLivePreview(surfaceView)
        // Always reset fail counter after a successful surface switch — not just on
        // mode change. Surface replacement (already LIVE_PREVIEW) also provides a fresh
        // surface that should get a clean slate for PixelCopy attempts.
        if (surfaceController.mode() == VirtualDisplaySurfaceMode.LIVE_PREVIEW) {
            captureCoordinator.onLivePreviewActivated()
        }
    }

    /**
     * Switch VirtualDisplay output back to the ImageReader for headless capture. Called when the
     * Viewer Activity is hidden or destroyed.
     */
    fun switchToImageReader() {
        if (arbiter.state !is VdState.Running) return
        surfaceController.switchToImageReader()
    }

    /** Current surface mode, for UI to check. */
    fun getSurfaceMode(): VirtualDisplaySurfaceMode = surfaceController.mode()

    /**
     * Forward a touch stream from VirtualDisplayViewerActivity into the virtual display.
     *
     * Primary path uses raw MotionEvent injection when InputEvent#setDisplayId is available.
     * Fallback path uses shell `input tap/swipe --display` when that hidden API is unavailable.
     */
    fun onViewerTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        return viewerTouchHandler.onViewerTouch(
                action = action,
                x = x,
                y = y,
                downTime = downTime,
                eventTime = eventTime,
                viewWidth = viewWidth,
                viewHeight = viewHeight
        )
    }

    // ── Screen Capture ──────────────────────────────────────────

    override suspend fun captureScreen(): ScreenSnapshot {
        val currentPkg = getCurrentPackageName()

        // Null package = unknown foreground app — skip capture to avoid leaking artifacts
        if (currentPkg == null) {
            return ScreenSnapshot(
                timestamp = System.currentTimeMillis(),
                elements = emptyList(),
                image = null
            )
        }

        // Privacy gate: BLOCKED apps get masked snapshot — no artifacts created
        if (isPackageBlocked(currentPkg)) {
            return ScreenSnapshot(
                timestamp = System.currentTimeMillis(),
                elements = emptyList(),
                image = null
            )
        }

        return try {
            arbiter.withRunningLease {
            val timestamp = System.currentTimeMillis()
            val pc = sessionConfig.perceptionConfig

            // 1. Capture a11y tree with trace artifacts (when tracing enabled)
            val a11yResult =
                    if (pc.capturesAccessibility)
                            captureCoordinator.captureA11yTreeWithArtifacts()
                    else
                            VirtualDisplayCaptureCoordinator.A11yCaptureResult(
                                    emptyList(), null, null
                            )

            // 2. Screenshot capture (when config requires it OR trace is enabled)
            val shouldCaptureScreenshot = pc.capturesScreenshot || traceRecorder.enabled
            val imageCapture =
                    if (shouldCaptureScreenshot) captureCoordinator.captureScreenshot() else null

            // 3. Only include screenshot in the snapshot if the perception config wants it
            val image = if (pc.capturesScreenshot) imageCapture?.image else null

            Log.d(
                    TAG,
                    "Captured screen: ${a11yResult.elements.size} elements, screenshot=${image != null}"
            )

            // 4. Build debug info
            val debug =
                    if (traceRecorder.enabled) {
                        ScreenSnapshotDebug(
                                rawA11yTreePath = a11yResult.rawTreeArtifactPath,
                                sanitizedA11yTreePath = a11yResult.sanitizedTreeArtifactPath,
                                screenshotPath = imageCapture?.tracePath
                        )
                    } else {
                        null
                    }

            ScreenSnapshot(
                    timestamp = timestamp,
                    elements = a11yResult.elements,
                    image = image,
                    debug = debug
            )
        }
        } catch (e: PlatformNotRunningException) {
            Log.w(TAG, "captureScreen: ${e.message}")
            ScreenSnapshot(
                timestamp = System.currentTimeMillis(),
                elements = emptyList(),
                image = null
            )
        }
    }

    override fun allowTapToFocus(): Boolean = false

    override suspend fun performAction(action: UIAction): ActionResult {
        return try {
            arbiter.withRunningLease {
                val suppressIme = action.mayTriggerIme() && !isKeyboardVisibleOnMainDisplay()
                if (suppressIme) setKeyboardHidden()

                try {
                    // Owner-present ordering signal (build 61): display 0 just before the action.
                    mainDisplayTasks?.beforeAgentAction()
                    dispatchAction(action)
                } finally {
                    if (suppressIme) setKeyboardAuto()
                }
            }
        } catch (e: PlatformNotRunningException) {
            ActionResult.Failure("Virtual display not running (${e.message})")
        }
    }

    private suspend fun dispatchAction(action: UIAction): ActionResult =
            when (action) {
                is UIAction.ClickNodeAt -> {
                        showClick(action.x, action.y)
                        nodeActionPerformer.performNodeClickAt(action.x, action.y, action.semanticHint)
                }
                is UIAction.LongClickNodeAt -> {
                        showClick(action.x, action.y, longPress = true)
                        nodeActionPerformer.performNodeLongClickAt(action.x, action.y, action.semanticHint)
                }
                is UIAction.SetTextOnNodeAt ->
                        nodeActionPerformer.performSetTextOnNodeAt(
                                action.x,
                                action.y,
                                action.text,
                                action.clear
                        )
                is UIAction.SetTextOnFocused ->
                        nodeActionPerformer.performSetTextOnFocused(action.text, action.clear)
                is UIAction.TapAt -> {
                        showClick(action.x, action.y)
                        inputInjector.injectTap(action.x, action.y)
                }
                is UIAction.LongPressAt -> {
                        showClick(action.x, action.y, longPress = true)
                        inputInjector.injectLongPress(action.x, action.y, action.durationMs)
                }
                is UIAction.ScrollNodeAt ->
                        nodeActionPerformer.performScrollAt(action.x, action.y, action.direction)
                is UIAction.Swipe -> performSwipe(action)
                is UIAction.SystemButton -> inputInjector.injectSystemButton(action.button)
                is UIAction.Wait -> {
                    delay(action.durationMs)
                    ActionResult.Success("Waited ${action.durationMs}ms")
                }
            }

    private fun showClick(x: Int, y: Int, longPress: Boolean = false) {
        visualizer?.showClick(
                x = x.toFloat(),
                y = y.toFloat(),
                longPress = longPress
        )
    }

    // ===== Action Helpers =====

    /**
     * Clamp swipe coordinates to virtual display bounds.
     * No edge inset needed — virtual display doesn't have gesture-nav interference.
     */
    private suspend fun performSwipe(action: UIAction.Swipe): ActionResult {
        val maxX = (config.width - 1).coerceAtLeast(0)
        val maxY = (config.height - 1).coerceAtLeast(0)

        val startX = action.startX.coerceIn(0, maxX)
        val startY = action.startY.coerceIn(0, maxY)
        val endX = action.endX.coerceIn(0, maxX)
        val endY = action.endY.coerceIn(0, maxY)

        if (startX != action.startX ||
                        startY != action.startY ||
                        endX != action.endX ||
                        endY != action.endY
        ) {
            Log.w(TAG, "Swipe coordinates clamped to virtual display bounds")
        }

        Log.d(
                TAG,
                "Swipe: ($startX,$startY) -> ($endX,$endY), duration=${action.durationMs}ms"
        )
        visualizer?.showSwipe(
                startX = startX.toFloat(),
                startY = startY.toFloat(),
                endX = endX.toFloat(),
                endY = endY.toFloat(),
                durationMs = action.durationMs
        )
        return inputInjector.injectSwipe(
                UIAction.Swipe(startX, startY, endX, endY, action.durationMs)
        )
    }

    override fun hasRequiredPermissions(): Boolean {
        return shizuku.isAvailable() && shizuku.hasPermission()
    }

    override fun getCurrentPackageName(): String? {
        val root = windowAccessor.getRootOnDisplay() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycleCompat()
        }
    }

    override fun getDisplayInfo(): DisplayInfo {
        return DisplayInfo(
                widthPixels = config.width,
                heightPixels = config.height,
                density = config.density
        )
    }

    override suspend fun getInstalledApps(): List<AppInfo> {
        return appController.getInstalledApps()
    }

    override suspend fun launchApp(packageName: String): ActionResult {
        return try {
            arbiter.withRunningLease { appController.launchApp(packageName) }
        } catch (e: PlatformNotRunningException) {
            ActionResult.Failure("Virtual display not running (${e.message})")
        }
    }

    // ── IME Suppression ──────────────────────────────────────────

    /** Actions that can trigger IME on the wrong display. */
    private fun UIAction.mayTriggerIme(): Boolean =
            this is UIAction.ClickNodeAt ||
                    this is UIAction.TapAt ||
                    this is UIAction.LongClickNodeAt ||
                    this is UIAction.LongPressAt ||
                    this is UIAction.SetTextOnNodeAt ||
                    this is UIAction.SetTextOnFocused

    /** Set SHOW_MODE_HIDDEN to prevent VD-triggered IME from appearing on display 0. */
    private fun setKeyboardHidden() {
        try {
            service.softKeyboardController.setShowMode(
                    AccessibilityService.SHOW_MODE_HIDDEN
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to suppress keyboard", e)
        }
    }

    /** Restore keyboard to auto mode. Safe to call multiple times. */
    private fun setKeyboardAuto() {
        try {
            service.softKeyboardController.setShowMode(
                    AccessibilityService.SHOW_MODE_AUTO
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore keyboard", e)
        }
    }

    /** Check if IME window is visible on the main display (display 0). */
    private fun isKeyboardVisibleOnMainDisplay(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val allWindows = service.getWindowsOnAllDisplays()
                val mainWindows = allWindows.get(Display.DEFAULT_DISPLAY) ?: return false
                try {
                    mainWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                } finally {
                    // Recycle all window objects across all displays
                    for (i in 0 until allWindows.size()) {
                        allWindows.valueAt(i)?.forEach { w ->
                            runCatching { w.recycle() }
                        }
                    }
                }
            } else {
                val windows = service.windows ?: return false
                try {
                    windows.any {
                        it.displayId == Display.DEFAULT_DISPLAY &&
                                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }
                } finally {
                    windows.forEach { w -> runCatching { w.recycle() } }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
