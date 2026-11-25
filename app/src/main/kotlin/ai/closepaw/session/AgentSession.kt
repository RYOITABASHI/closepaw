@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.closepaw.session

import android.accessibilityservice.AccessibilityService
import android.util.Log
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.OverlayTouchGate
import ai.closepaw.platform.PlatformFactory
import ai.closepaw.protocol.*
import ai.closepaw.tool.AppClassifierHolder
import ai.closepaw.trace.NoopTraceRecorder
import ai.closepaw.trace.TraceRecorder
import ai.closepaw.trace.TraceRecorderFactory
import ai.closepaw.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the session is shutting down — drives [SessionEndReason] in the emitted event. */
enum class ShutdownCause {
    /** User (or caller) explicitly requested shutdown. */
    UserRequested,
    /** No follow-up arrived within the idle-timeout window. */
    IdleTimeout,
    /** Repeated failure to reacquire platform from Idle — session can't make progress. */
    ReacquireFailed,
}

class AgentSession
internal constructor(
        val sessionId: SessionId,
        private val config: SessionConfig,
        private val scope: CoroutineScope,
        private val services: SessionServices,
        internal val externalDriver: Boolean = true,
) {
    companion object {
        private const val TAG = "AgentSession"

        internal fun createForExternalDriver(
                config: SessionConfig,
                service: AccessibilityService,
                scope: CoroutineScope,
                visualizer: ActionVisualizerManager? = null,
                overlayTouchGate: OverlayTouchGate? = null,
                onTraceRecorderCreated: ((TraceRecorder) -> Unit)? = null,
                isDispatchCancelled: (() -> Boolean)? = null,
        ): AgentSession {
            val sessionId = SessionId.generate()
            val traceRecorder = TraceRecorderFactory.create(service, config, sessionId, boundedCleanup = true)
            onTraceRecorderCreated?.invoke(traceRecorder)
            val appClassifier = AppClassifierHolder.get(service.applicationContext)
            val platform: AndroidPlatform =
                    PlatformFactory.create(
                            config = config,
                            service = service,
                            visualizer = visualizer,
                            // External runs trace only their fingerprint-based transcript.
                            traceRecorder = NoopTraceRecorder,
                            overlayTouchGate = overlayTouchGate,
                            isPackageBlocked = { appClassifier.classify(it) == AppTier.BLOCKED },
                            externalDriver = true,
                            isDispatchCancelled = isDispatchCancelled,
                    )
            val services =
                    SessionServices.create(
                            config = config,
                            platform = platform,
                            context = service,
                            scope = scope,
                            traceRecorder = traceRecorder,
                            appClassifier = appClassifier,
                            externalDriver = true,
                    )

            return AgentSession(
                    sessionId = sessionId,
                    config = config,
                    scope = scope,
                    services = services,
                    externalDriver = true,
            )
        }

    }

    fun getServices(): SessionServices = services

    private val _state = MutableStateFlow<SessionState>(SessionState.Created)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(replay = 8, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Serializes all lifecycle state transitions (user ops, runner completion, idle timeout). */
    private val lifecycleMutex = Mutex()

    private val checkpointCoordinator = SessionCheckpointCoordinator(
            sessionId = sessionId.value,
            config = config,
            historyManager = services.historyManager,
            sessionState = services.sessionState,
            recordingService = services.recordingService,
            externalDriver = externalDriver,
    )

    init {
        services.historyManager.setMutationListener { checkpointCoordinator.scheduleCheckpoint(_state.value) }
        services.sessionState.todos.setMutationListener { checkpointCoordinator.scheduleCheckpoint(_state.value) }
        services.sessionState.scratchpad.setMutationListener { checkpointCoordinator.scheduleCheckpoint(_state.value) }

    }

    suspend fun submit(op: Op) {
        Log.d(TAG, "Received Op: $op (current state: ${_state.value})")

        when (op) {
            is Op.Takeover -> handleTakeover()
            is Op.Resume -> lifecycleMutex.withLock { handleResume() }
            is Op.Interrupt -> lifecycleMutex.withLock { handleInterrupt() }
            is Op.Shutdown -> lifecycleMutex.withLock { handleShutdown(ShutdownCause.UserRequested) }
            is Op.UserInput -> Unit
            is Op.Supplement -> handleSupplement(op.text)
            is Op.UserResponse -> handleUserResponse(op.callId, op.response)
            is Op.Approve -> handleApproval(op)
        }
    }

    internal fun emitStatus(status: String) {
        scope.launch {
            emit(
                    StatusUpdate(
                            sessionId = sessionId,
                            timestamp = now(),
                            status = status
                    )
            )
        }
    }

    suspend fun startForExternalDriver(): Unit = lifecycleMutex.withLock {
        check(_state.value == SessionState.Created)
        check(externalDriver)
        check(withContext(Dispatchers.Default) { initializeForFirstTask() }) {
            "Could not initialize the external driver platform"
        }
        _state.value = SessionState.Running
    }

    /** Initialize recording and start the platform for the external driver. */
    private suspend fun initializeForFirstTask(): Boolean {
        val recorderSessionId = services.recordingService.getCurrentSessionId()
        if (recorderSessionId == null || recorderSessionId != sessionId.value) {
            services.recordingService.initializeNewSession(
                    sessionId = sessionId.value,
                    model = "external"
            )
        }
        try {
            awaitReplacedTeardowns()
            services.platform.start()
        } catch (e: Exception) {
            Log.e(TAG, "Platform start failed", e)
            emitStatus("⚠️ Platform initialization failed: ${e.message}")
            return false
        }
        return true
    }

    private suspend fun awaitReplacedTeardowns() {
        val barrier = ai.closepaw.app.ReplacedSessionShutdownBarrier.process
        val timeoutMs = ai.closepaw.app.ReplacedSessionShutdownBarrier.START_TIMEOUT_MS
        if (!barrier.awaitClear(timeoutMs, exceptOwner = services.platform)) {
            throw IllegalStateException("a replaced session is still shutting down; try again")
        }
    }

    private suspend fun handleTakeover() {
        lifecycleMutex.withLock {
            if (_state.value != SessionState.Running) return
            _state.value = SessionState.Paused
        }
        emit(SessionTakeover(sessionId = sessionId, timestamp = now()))
    }

    private suspend fun handleResume() {
        if (_state.value != SessionState.Paused) return
        _state.value = SessionState.Running
        emit(SessionResumed(sessionId = sessionId, timestamp = now()))
    }

    private suspend fun handleSupplement(text: String) {
        val currentState = _state.value
        if (currentState != SessionState.Running && currentState != SessionState.Paused && currentState != SessionState.TakeoverPending) {
            Log.w(TAG, "Cannot supplement: not running or paused (state: $currentState)")
            return
        }

        services.historyManager.addItem(
            ai.closepaw.history.ResponseItem.Message(
                kind = ai.closepaw.history.MessageKind.USER_INTENT,
                content = text
            )
        )

        emit(SupplementReceived(sessionId = sessionId, timestamp = now(), text = text))

        Log.i(TAG, "Supplement received: ${text.take(50)}")
    }

    private suspend fun handleUserResponse(callId: String, response: String) {
        val delivered = services.userResponseChannel.deliver(callId, response)
        if (delivered) {
            Log.i(TAG, "UserResponse delivered: callId=$callId")
        } else {
            Log.w(TAG, "UserResponse not delivered (no matching pending request): callId=$callId")
        }
    }

    private suspend fun handleInterrupt() {
        if (_state.value != SessionState.Running && _state.value != SessionState.TakeoverPending) {
            Log.w(TAG, "Cannot interrupt: not running (state: ${_state.value})")
            return
        }

        services.userResponseChannel.cancel()

        Log.i(TAG, "Interrupt requested")
    }

    private suspend fun handleShutdown(cause: ShutdownCause) {
        // Idempotency guard — safe against double-call from timeout + explicit shutdown
        if (_state.value == SessionState.Shutdown) {
            Log.d(TAG, "Already shut down, ignoring duplicate Shutdown")
            return
        }

        Log.i(TAG, "Shutting down session: $sessionId (cause=$cause)")

        _state.value = SessionState.Shutdown
        if (!checkpointCoordinator.flushClosed()) {
            Log.w(TAG, "Failed to flush CLOSED checkpoint for $sessionId")
        }
        disableCheckpointMutationListeners()
        services.userResponseChannel.cancel()

        val cleanupResult = withContext(NonCancellable) { services.cleanup() }
        if (cleanupResult is CleanupResult.PartialFailure) {
            Log.w(TAG, "Cleanup partial failure: ${cleanupResult.failures.joinToString(", ") { it.step }}")
        }

        val reason = when (cause) {
            ShutdownCause.UserRequested -> SessionEndReason.USER_STOPPED
            ShutdownCause.IdleTimeout -> SessionEndReason.IDLE_TIMEOUT
            ShutdownCause.ReacquireFailed -> SessionEndReason.INTERRUPTED
        }
        emit(
                SessionCompleted(
                        sessionId = sessionId,
                        timestamp = now(),
                        reason = reason
                )
        )

        Log.i(TAG, "Session shutdown complete: $sessionId")
    }

    private suspend fun handleApproval(op: Op.Approve) {
        if (op.decision == ApprovalDecision.APPROVED && !isValidApprovalPackageName(op.packageName)) {
            val resolved = services.toolRouter.resolveApproval(op.actionId, ApprovalDecision.DENIED)
            if (!resolved) {
                Log.w(TAG, "Discarding approval with no pending match: ${op.actionId}")
            } else {
                Log.w(TAG, "Denied approval with invalid package: ${op.packageName}")
            }
            return
        }

        val resolved = services.toolRouter.resolveApproval(op.actionId, op.decision)
        if (!resolved) {
            Log.w(TAG, "Discarding approval with no pending match: ${op.actionId}")
            return
        }

        // Rejections are current-call only; they do not mutate allow-lists.
        if (op.decision == ApprovalDecision.APPROVED) {
            when (op.scope) {
                ApprovalScope.SESSION -> services.policyEngine.allowPackageForSession(op.packageName)
                ApprovalScope.ALWAYS -> {
                    // Persistent "Always" — single writer is the classifier. Same code path
                    // as the settings UI tap; IO-launched because the persistence callback
                    // touches SharedPreferences.
                    scope.launch(Dispatchers.IO) {
                        services.appClassifier.setOverride(op.packageName, AppTier.NORMAL)
                    }
                }
            }
        }

        Log.d(TAG, "Resolved approval: ${op.actionId} -> ${op.decision}")
    }

    private fun isValidApprovalPackageName(packageName: String): Boolean =
            packageName.isNotBlank() && '.' in packageName

    private suspend fun emit(event: AgentEvent) {
        try {
            _events.emit(event)
            Log.d(TAG, "Emitted event: ${event::class.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit event: $event", e)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun disableCheckpointMutationListeners() {
        services.historyManager.setMutationListener(null)
        services.sessionState.todos.setMutationListener(null)
        services.sessionState.scratchpad.setMutationListener(null)
    }
}
