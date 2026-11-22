package ai.closepaw.app

import ai.closepaw.agent.cognition.context.ScreenSignature
import ai.closepaw.mcp.BrainActionRecord
import ai.closepaw.mcp.BrainActivityTracker
import ai.closepaw.mcp.BrainGuardTrip
import ai.closepaw.mcp.BrainSessionGuard
import ai.closepaw.mcp.BrainSessionLimits
import ai.closepaw.mcp.BrainStaleness
import ai.closepaw.mcp.BrainTerminalGate
import ai.closepaw.mcp.BrainTerminalKind
import ai.closepaw.mcp.BrainTranscript
import ai.closepaw.mcp.ElementIdentity
import ai.closepaw.mcp.McpExternalResults
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpRunRegistry
import ai.closepaw.mcp.McpSessionArgs
import ai.closepaw.mcp.McpTypedOpError
import ai.closepaw.mcp.brainSignature
import ai.closepaw.mcp.completeBrainTerminal
import ai.closepaw.mcp.identity
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.perception.Perceptor
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.ApprovalDetails
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionState
import ai.closepaw.session.AgentSession
import ai.closepaw.tool.SimpleToolRouterContext
import ai.closepaw.tool.ToolCallResult
import ai.closepaw.tool.action.UiChangeDetector
import ai.closepaw.tool.impl.resolveOpenApp
import ai.closepaw.trace.TraceEventRecord
import ai.closepaw.trace.TraceRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.json.JSONObject

internal class AgentServiceBrainSessions(
    private val serviceProvider: () -> AgentService?,
    backend: AgentServiceMcpBackend,
) : McpExternalResults {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, BrainSession>()
    private val results = ConcurrentHashMap<String, BrainRunRecord>()
    private val resultsLock = Object()
    private val ownershipLock = Any()
    private val ownership = BrainOwnership(
        BrainSessionAdmission.gate,
        ownershipLock,
        BrainStart::handle,
        { it.stopped.complete(Unit) },
        orphanedTraceDirectories,
    )
    @Volatile private var backend = backend

    fun updateBackend(backend: AgentServiceMcpBackend) {
        this.backend = backend
    }

    private data class BrainRunRecord(
        val status: McpRunRegistry.Status,
        val summary: String,
        val transcriptRecorded: Boolean,
    )

    private class BrainStart(val ownerService: AgentService?, val handle: String) {
        val stopped = CompletableDeferred<Unit>()
        // Only the worker writes these. Discard reads them after joining the worker.
        var recorder: TraceRecorder? = null
        var session: AgentSession? = null
        var prepared: BrainSession? = null
    }

    override fun snapshot(runHandle: String): McpRunRegistry.Snapshot? {
        val result = results[runHandle]
            ?: if (sessions.containsKey(runHandle)) {
                return McpRunRegistry.Snapshot(runHandle, McpRunRegistry.Status.RUNNING, "")
            } else {
                // Completion may have removed the live entry since the first result lookup.
                results[runHandle] ?: return null
            }
        return McpRunRegistry.Snapshot(runHandle, result.status, result.summary)
    }

    override fun await(runHandle: String, waitMs: Long): McpRunRegistry.Snapshot? {
        val startedAtMs = SystemClock.elapsedRealtime()
        synchronized(resultsLock) {
            var current = snapshot(runHandle)
            if (waitMs <= 0) return current
            while (current != null && !current.status.terminal) {
                val remainingMs = waitMs - (SystemClock.elapsedRealtime() - startedAtMs)
                if (remainingMs <= 0) return snapshot(runHandle)
                resultsLock.wait(remainingMs)
                current = snapshot(runHandle)
            }
            return current
        }
    }

    override fun activeCount(): Int = sessions.size

    private fun newHandle(): String {
        val bytes = ByteArray(12).also(RANDOM::nextBytes)
        return "run_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class BrainSession(
        val handle: String,
        val session: AgentSession,
        val ownerService: AgentService,
        val goalFingerprint: String,
        val cancelledFlag: AtomicBoolean,
        val startedAtMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val guard = BrainSessionGuard(startedAtMs)
        val mutex = Mutex()
        val terminalGate = BrainTerminalGate()
        @Volatile var attached = false
        val recorder = session.getServices().traceRecorder
        @Volatile var pendingApproval: ApprovalDetails? = null
        var observedElements: Map<Int, ElementIdentity>? = null
        val backgroundOutcomes = ArrayDeque<JsonObject>()
        var expiry: Job? = null
        var healthWatcher: Job? = null
        var completion: Deferred<McpRunRegistry.Status>? = null

        val activity = BrainActivityTracker(terminalGate).also { it.seed(startedAtMs) }

        fun noteCallStarted() = activity.noteCallStarted(SystemClock.elapsedRealtime())

        fun noteCallFinished() = activity.noteCallFinished(SystemClock.elapsedRealtime())

        fun hasCallInFlight(): Boolean = activity.hasCallInFlight()

        fun lastSeenMs(): Long = activity.lastSeenMs()

        fun deadlineExceeded() = SystemClock.elapsedRealtime() - guard.startedAtMs >=
            BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS

        fun elapsedMs() = SystemClock.elapsedRealtime() - startedAtMs

        fun vdFailure(): String? =
            (session.getServices().platform as? VirtualDisplayPlatform)?.vdFailure()

        fun liveVdFailure(): String? =
            if (session.state.value == SessionState.Shutdown) null else vdFailure()
    }

    fun handle(name: String, args: JsonObject): JsonObject = runBlocking {
        if (name == McpHandler.TOOL_START_SESSION) return@runBlocking start(args)
        val handle = McpSessionArgs.runHandle(args)
        val brain = sessions[handle] ?: run {
            results[handle]?.let {
                if (name == McpHandler.TOOL_FINISH_RUN) {
                    return@runBlocking finishResult(handle, it.status)
                }
                throw sessionEnded()
            }
            throw McpTypedOpError("unknown_handle", "No external session owns this handle")
        }
        brain.noteCallStarted()
        try {
            dispatch(brain, name, args)
        } finally {
            brain.noteCallFinished()
        }
    }

    private suspend fun dispatch(brain: BrainSession, name: String, args: JsonObject): JsonObject {
        // Only the immutable approval header is read without the action mutex.
        if (name != McpHandler.TOOL_FINISH_RUN) {
            brain.pendingApproval?.let {
                checkLive(brain)
                return awaitingApproval(brain, it)
            }
        }
        if (name == McpHandler.TOOL_OBSERVE) {
            if (!brain.mutex.tryLock()) {
                checkLive(brain)
                brain.pendingApproval?.let { return awaitingApproval(brain, it) }
                throw McpTypedOpError("action_in_progress", "An external session operation is in progress")
            }
            return try {
                checkLive(brain)
                observe(brain, McpSessionArgs.includeImage(args))
            } finally {
                brain.mutex.unlock()
            }
        }
        if (name == McpHandler.TOOL_FINISH_RUN) {
            // Bound admission separately so a finish that has acquired the lock can commit.
            var locked = false
            try {
                withTimeoutOrNull(FINISH_LOCK_TIMEOUT_MS) {
                    brain.mutex.lock()
                    locked = true
                } ?: run {
                    results[brain.handle]?.let { return finishResult(brain.handle, it.status) }
                    throw McpTypedOpError(
                        "action_in_progress", "An action is still running; retry finish_run shortly.",
                    )
                }
                return finish(brain, args)
            } finally {
                if (locked) brain.mutex.unlock()
            }
        }
        return act(brain, name, args)
    }

    private suspend fun start(args: JsonObject): JsonObject {
        val claim = synchronized(ownershipLock) {
            val candidate = BrainStart(serviceProvider(), newHandle())
            if (!ownership.reserve(candidate)) {
                throw McpTypedOpError("busy", "ClosePaw is busy with another task")
            }
            candidate
        }
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        var adopted: BrainSession? = null
        var worker: Deferred<BrainSession>? = null
        fun checkDeadline() {
            if (claim.stopped.isCompleted) throw startStopped()
            if (SystemClock.elapsedRealtime() >= deadline) throw startTimedOut()
        }
        try {
            val service = claim.ownerService
                ?: throw McpTypedOpError("service_unavailable", "ClosePaw's accessibility service is unavailable")
            // This worker owns its resources independently of the caller's bounded wait.
            val preparation = scope.async(Dispatchers.IO) {
                checkDeadline()
                val precheck = runInterruptible { backend.externalPrecheck() }
                val decision = withContext(Dispatchers.Main.immediate) {
                    checkDeadline()
                    precheck(service)
                }
                val mode = when (decision) {
                    is ShellyBackgroundRun.Decision.Blocked ->
                        throw McpTypedOpError(decision.block.name, McpRunStatus.refusalMessage(decision.block))
                    is ShellyBackgroundRun.Decision.Start -> decision.platformMode
                }
                val fingerprint = goalFingerprint(McpSessionArgs.goal(args))
                val reqIdPresent = McpSessionArgs.reqId(args) != null
                val noncePresent = McpSessionArgs.nonce(args) != null
                try {
                    service.awaitReplacedShutdowns()
                } catch (_: IllegalStateException) {
                    throw McpTypedOpError("start_failed", "ClosePaw is still shutting down the previous task; try again")
                }
                checkDeadline()
                val cancelledFlag = AtomicBoolean(false)
                val startedAtMs = SystemClock.elapsedRealtime()
                val session = service.createExternalSession(mode, claim.handle, isDispatchCancelled = {
                    claim.stopped.isCompleted || !service.isExternalSessionOwnerActive() ||
                        cancelledFlag.get() || SystemClock.elapsedRealtime() - startedAtMs >=
                        BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS
                }) { claim.recorder = it }.also { claim.session = it }
                checkDeadline()
                val brain = BrainSession(claim.handle, session, service, fingerprint, cancelledFlag, startedAtMs)
                claim.prepared = brain
                check(brain.recorder.enabled) { "External session trace unavailable" }
                if (mode == PlatformMode.VIRTUAL_DISPLAY && session.getServices().platform.mode != mode) {
                    throw McpTypedOpError("virtual_display_unavailable", "ClosePaw's virtual display became unavailable")
                }
                try {
                    session.startForExternalDriver()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    val vd = brain.vdFailure()
                    throw McpTypedOpError(
                        if (vd != null) "virtual_display_unavailable" else "start_failed",
                        vd ?: "ClosePaw failed to start the external session",
                    )
                }
                checkDeadline()
                brain.record(
                    BrainTranscript.TYPE_SESSION_STARTED,
                    BrainTranscript.sessionStarted(fingerprint, "mcp", reqIdPresent, noncePresent),
                )
                brain.recorder.runDirAbsolutePath?.let { File(it, TRACE_MARKER).writeText("") }
                withContext(Dispatchers.Main.immediate) {
                    checkDeadline()
                    try {
                        if (!service.attachExternalSession(session, session.getServices().platform.mode)) {
                            throw McpTypedOpError("busy", "ClosePaw is busy with another task")
                        }
                    } finally {
                        brain.attached = service.ownsExternalSession(session)
                        if (claim.stopped.isCompleted || SystemClock.elapsedRealtime() >= deadline) {
                            detachExternalSession(brain)
                        }
                    }
                }
                checkDeadline()
                brain
            }
            worker = preparation
            val ready = withTimeoutOrNull(deadline - SystemClock.elapsedRealtime()) {
                select<BrainSession> {
                    claim.stopped.onAwait { throw startStopped() }
                    preparation.onAwait { it }
                }
            } ?: throw startTimedOut()

            currentCoroutineContext().ensureActive()
            synchronized(ownershipLock) {
                checkDeadline()
                val accepted = ownership.adopt(claim, validate = {
                    if (!ownerIsActive(ready) || !ready.attached || !service.ownsExternalSession(ready.session) ||
                        ready.session.state.value == SessionState.Shutdown
                    ) {
                        throw McpTypedOpError("start_failed", "External session lost its owning service or attachment")
                    }
                    ready.vdFailure()?.let {
                        throw McpTypedOpError("virtual_display_unavailable", "ClosePaw's virtual display became unavailable")
                    }
                }) {
                    adopted = ready
                    synchronized(ready.terminalGate) {
                        sessions[ready.handle] = ready
                        ready.expiry = scope.launch {
                            delay((BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS - ready.elapsedMs()).coerceAtLeast(0L))
                            terminate(ready, "External session exceeded its wall-clock limit")
                        }
                        ready.healthWatcher = scope.launch { watchHealth(ready) }
                    }
                }
                if (!accepted) throw startStopped()
            }
            currentCoroutineContext().ensureActive()
            checkLive(ready)
            scope.launch(Dispatchers.IO) { pruneTraces(ready) }
            return buildJsonObject {
                put("runHandle", ready.handle)
                put("status", "running")
            }
        } catch (e: Exception) {
            // Cancellation after adoption still retires the published handle.
            adopted?.let { requestStop(it, "External session start was interrupted") }
            if (e is CancellationException && claim.stopped.isCompleted) throw startStopped()
            if (e is McpTypedOpError || e is CancellationException) throw e
            throw McpTypedOpError("start_failed", "ClosePaw failed to start the external session")
        } finally {
            if (adopted == null) {
                val cleanup = scope.launch(Dispatchers.IO) {
                    worker?.cancel()
                    worker?.join()
                    try {
                        claim.prepared?.let { detachExternalSession(it) }
                        val owner = claim.ownerService
                        val session = claim.session
                        if (owner != null && session != null) owner.fallbackExternalSessionTeardown(session)
                    } catch (e: Exception) {
                        Log.e(TAG, "Discarded external startup teardown failed", e)
                    } finally {
                        claim.recorder?.let { closeRecorder(it) }
                    }
                }
                trackTraceCleanup(claim.handle, cleanup)
                try {
                    if (!claim.stopped.isCompleted && worker?.isCompleted == true) {
                        withContext(NonCancellable) {
                            withTimeoutOrNull(
                                minOf(TEARDOWN_TIMEOUT_MS, deadline - SystemClock.elapsedRealtime()),
                            ) {
                                select {
                                    cleanup.onJoin {}
                                    claim.stopped.onAwait {}
                                }
                            }
                        }
                    }
                } finally {
                    ownership.retire(claim)
                    if (!cleanup.isCompleted) {
                        Log.w(TAG, "External startup retired while resource cleanup continues")
                    }
                }
            }
        }
    }

    private fun startStopped() = McpTypedOpError("start_failed", "External session start was stopped")

    private fun startTimedOut() =
        McpTypedOpError("start_timeout", "ClosePaw did not start the session in time")

    private suspend fun detachExternalSession(brain: BrainSession) {
        if (!brain.attached) return
        withContext(Dispatchers.Main.immediate) {
            if (!brain.attached) return@withContext
            try {
                brain.ownerService.detachExternalSession(brain.session)
            } catch (e: Exception) {
                Log.e(TAG, "External session detach failed", e)
            } finally {
                brain.attached = false
            }
        }
    }

    private suspend fun settleTeardown(brain: BrainSession) {
        val service = brain.ownerService
        val session = brain.session
        settleBrainTeardown(
            scope = scope,
            dispatcher = Dispatchers.IO,
            timeoutMs = TEARDOWN_TIMEOUT_MS,
            hardTimeoutMs = TEARDOWN_FALLBACK_HARD_TIMEOUT_MS,
            submit = { service.shutdownExternalSession(session, it) },
            fallback = { service.fallbackExternalSessionTeardown(session) },
            fence = {
                try {
                    withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) {
                        service.fallbackExternalSessionTeardown(session)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "External session teardown fence failed", e)
                }
            },
            close = { closeRecorder(brain.recorder) },
            detach = { detachExternalSession(brain) },
            track = { trackTraceCleanup(brain.handle, it) },
            onSubmissionError = { Log.e(TAG, "Could not submit or join external session teardown", it) },
            onFallback = { Log.e(TAG, "Registered external session teardown failed; attempting direct teardown") },
            onCleanupError = { Log.e(TAG, "Direct external session teardown failed", it) },
            onTimeout = {
                Log.e(TAG, "External teardown did not settle within the bound; releasing admission")
                scope.launch { detachExternalSession(brain) }
            },
        )
    }

    private fun trackTraceCleanup(handle: String, cleanup: Job) {
        ownership.trackCleanup(scope, handle, cleanup, TRACE_CLEANUP_HARD_BOUND_MS) {
            Log.e(TAG, "External trace cleanup exceeded its hard bound; directory excluded from pruning")
        }
    }

    private suspend fun closeRecorder(recorder: TraceRecorder) = withContext(Dispatchers.IO) {
        try {
            try {
                withTimeoutOrNull(TERMINAL_RECORD_TIMEOUT_MS) { recorder.flush() }
            } finally {
                recorder.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "External session recorder cleanup failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun watchHealth(brain: BrainSession) {
        while (!brain.terminalGate.isClaimed()) {
            delay(VD_HEALTH_POLL_MS)
            if (stopIfOwnerLost(brain)) return
            val failure = brain.liveVdFailure()
            if (brain.session.state.value == SessionState.Shutdown) {
                end(brain, ShellyRunState.Finished.Kind.STOPPED, "External session ended")
                return
            }
            failure?.let {
                terminate(brain, displayLostMessage(it))
                return
            }
            // The claim revalidates activity under the terminalGate monitor (see
            // claimTerminal's guard): a call that starts after any earlier
            // observation but before the claim increments the in-flight count and
            // stamps fresh activity under that same monitor, so the guard
            // observes it and stands the termination down — a session is never
            // staleness-terminated while a call is executing. The guard's first
            // conjunct also keeps the staleness predicate itself from being
            // evaluated once the gate has been claimed by a competing terminal
            // path. When the guard stands down, terminate returns false and the
            // watch loop continues.
            if (terminate(
                    brain,
                    "Brain stopped responding",
                    preClaimGuard = {
                        !brain.terminalGate.isClaimed() &&
                            BrainStaleness.shouldTerminate(
                                lastSeenMs = brain.lastSeenMs(),
                                inFlight = brain.hasCallInFlight(),
                                nowMs = SystemClock.elapsedRealtime(),
                                boundMs = BrainSessionLimits.BRAIN_STALENESS_MS,
                            )
                    },
                )
            ) return
        }
    }

    private fun pruneTraces(brain: BrainSession) {
        val path = brain.recorder.runDirAbsolutePath ?: return
        val root = File(path).parentFile ?: return
        val entries = root.listFiles { file -> file.isDirectory && file.name.startsWith("run_") } ?: return
        val candidates = entries.map { Triple(it.name, it.lastModified(), File(it, TRACE_MARKER).isFile) }
        val protected = synchronized(ownershipLock) {
            sessions.keys.toSet() + brain.handle + ownership.protectedHandles()
        }
        for (name in BrainTranscript.retentionPrune(candidates, protected, keep = 50)) {
            File(root, name).deleteRecursively()
        }
    }

    private data class Capture(
        val snapshot: ScreenSnapshot,
        val packageName: String?,
        val contentBlocked: Boolean,
        val masked: Boolean,
        val partiallyMasked: Boolean,
        val imageArtifactPath: String?,
    )

    private sealed interface CaptureResult {
        data class Ok(val capture: Capture) : CaptureResult
        data class VdLost(val reason: String) : CaptureResult
        data class Unavailable(val note: String) : CaptureResult
    }

    private suspend fun capture(brain: BrainSession): CaptureResult {
        val services = brain.session.getServices()
        brain.vdFailure()?.let { return CaptureResult.VdLost(it) }
        val before = services.platform.getCurrentPackageName()
        val raw = try {
            services.platform.captureScreen()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return brain.vdFailure()?.let { CaptureResult.VdLost(it) }
                ?: CaptureResult.Unavailable(e.javaClass.simpleName)
        }
        brain.vdFailure()?.let { return CaptureResult.VdLost(it) }
        (services.platform as? VirtualDisplayPlatform)?.lastCaptureFailure()?.let {
            return CaptureResult.Unavailable(it)
        }
        if (before == null && raw.elements.isEmpty() && raw.image == null) {
            return CaptureResult.Unavailable("no accessibility roots on display")
        }
        val pkg = services.platform.getCurrentPackageName()
        if (before != pkg) return CaptureResult.Unavailable("foreground changed during capture")
        val classifier = services.appClassifier
        val blocked = classifier.classify(pkg) == AppTier.BLOCKED
        val safe = classifier.maskIfBlocked(raw, pkg)
        val elements = safe.elements.filterNot {
            it.packageName.isNotEmpty() && classifier.classify(it.packageName) == AppTier.BLOCKED
        }
        val filtered = elements.size != safe.elements.size
        return CaptureResult.Ok(
            Capture(
                snapshot = safe.copy(elements = elements, image = if (filtered) null else safe.image),
                packageName = pkg,
                contentBlocked = blocked,
                masked = blocked || filtered,
                partiallyMasked = filtered && !blocked,
                imageArtifactPath = safe.debug?.screenshotPath,
            ),
        )
    }

    private suspend fun displayLost(brain: BrainSession, reason: String): Nothing {
        val message = displayLostMessage(reason)
        terminate(brain, message)
        throw McpTypedOpError("virtual_display_lost", message)
    }

    private fun displayLostMessage(reason: String) = "Virtual display lost: $reason"

    private suspend fun observe(brain: BrainSession, includeImage: Boolean): JsonObject {
        val result = capture(brain)
        if (result is CaptureResult.VdLost) displayLost(brain, result.reason)
        checkLive(brain)
        val captured = (result as? CaptureResult.Ok)?.capture
        val snapshot = captured?.snapshot ?: ScreenSnapshot(System.currentTimeMillis(), emptyList())
        val contentBlocked = captured?.contentBlocked == true
        val observation = buildJsonObject {
            put("runHandle", brain.handle)
            put("status", if (brain.pendingApproval != null) "awaiting_approval" else "working")
            put("actionsExecuted", brain.guard.actionsExecuted)
            put("elapsedMs", brain.elapsedMs())
            put("goalFingerprint", brain.goalFingerprint)
            put("packageName", captured?.packageName)
            put("keyboardVisible", snapshot.keyboardVisible)
            put("contentBlocked", contentBlocked)
            put("elements", elements(snapshot))
            if (result is CaptureResult.Unavailable) {
                put("captureUnavailable", true)
                put("captureNote", result.note)
            }
            brain.backgroundOutcomes.removeFirstOrNull()?.let { put("lastActionOutcome", it) }
            if (brain.backgroundOutcomes.isNotEmpty()) put("queuedBackgroundOutcomes", brain.backgroundOutcomes.size)
            if (includeImage) {
                val image = snapshot.image
                when {
                    image == null -> put("imageOmitted", "unavailable")
                    image.bytes.size > MAX_IMAGE_BYTES -> put("imageOmitted", "too_large")
                    else -> put("image", buildJsonObject {
                        put("mime", image.mimeType)
                        put("base64", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
                        put("width", image.width)
                        put("height", image.height)
                    })
                }
            }
        }
        synchronized(brain.terminalGate) {
            if (!brain.terminalGate.isClaimed()) {
                brain.record(
                    BrainTranscript.TYPE_OBSERVATION,
                    BrainTranscript.observation(
                        captured?.packageName,
                        snapshot.elements.size,
                        masked = contentBlocked,
                        partiallyMasked = captured?.partiallyMasked == true,
                        imageArtifactPath = captured?.imageArtifactPath,
                        imageTaken = snapshot.hasScreenshot,
                        atMs = brain.elapsedMs(),
                    ),
                )
            }
        }
        brain.observedElements = snapshot.elements.associate { it.index to it.identity() }
        return observation
    }

    private suspend fun act(brain: BrainSession, name: String, args: JsonObject): JsonObject {
        val completed = CompletableDeferred<Result<JsonObject>>()
        val approval = CompletableDeferred<ApprovalDetails>()
        val returnInBackground = CompletableDeferred<Boolean>()
        // Reserve the action before launching, so a competing request can't queue behind it.
        if (!brain.mutex.tryLock()) {
            checkLive(brain)
            brain.pendingApproval?.let { return awaitingApproval(brain, it) }
            throw McpTypedOpError("action_in_progress", "An external session operation is in progress")
        }
        scope.launch {
            brain.noteCallStarted()
            try {
                val outcome = try {
                    checkLive(brain)
                    enforceCaps(brain)
                    val result = executeAction(brain, name, args, approval)
                    completed.complete(Result.success(result))
                    result
                } catch (e: Exception) {
                    completed.complete(Result.failure(e))
                    buildJsonObject {
                        if (e is CancellationException) {
                            put("status", "cancelled")
                        } else {
                            put("status", "failed")
                            put("error", "action_exception")
                            put("exception", e.javaClass.simpleName)
                        }
                    }
                }
                try {
                    withContext(NonCancellable) {
                        if (returnInBackground.await()) synchronized(brain.terminalGate) {
                            if (!brain.terminalGate.isClaimed()) brain.backgroundOutcomes.addLast(outcome)
                        }
                    }
                } finally {
                    brain.pendingApproval = null
                    brain.mutex.unlock()
                }
            } finally {
                brain.noteCallFinished()
            }
        }
        var background = true
        try {
            return withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                select {
                    approval.onAwait {
                        checkLive(brain)
                        awaitingApproval(brain, it)
                    }
                    completed.onAwait { result ->
                        background = false
                        result.getOrThrow()
                    }
                }
            } ?: buildJsonObject {
                put("runHandle", brain.handle)
                put("tool", name)
                put("status", "action_timeout")
            }
        } finally {
            returnInBackground.complete(background)
        }
    }

    private suspend fun enforceCaps(brain: BrainSession) {
        val trip = brain.guard.checkBeforeAction(SystemClock.elapsedRealtime())
        val (code, message) = when (trip) {
            BrainGuardTrip.ACTION_CAP -> "action_cap" to "External session reached its action limit"
            BrainGuardTrip.WALL_CLOCK -> "session_expired" to "External session exceeded its wall-clock limit"
            else -> return
        }
        synchronized(brain.terminalGate) {
            checkLive(brain)
            brain.record(
                BrainTranscript.TYPE_GUARD_VERDICT,
                BrainTranscript.guardVerdict(trip.name.lowercase(), brain.guard.actionsExecuted, brain.elapsedMs()),
            )
        }
        terminate(brain, message)
        throw McpTypedOpError(code, message)
    }

    private suspend fun executeAction(
        brain: BrainSession,
        name: String,
        args: JsonObject,
        approval: CompletableDeferred<ApprovalDetails>,
    ): JsonObject {
        val params = JSONObject()
        val (tool, initialTarget) = when (name) {
            McpHandler.TOOL_TAP -> {
                val index = McpSessionArgs.elementIndex(args)
                params.put("action", "click").put("element_index", index)
                "mobile_action" to "element:$index"
            }
            McpHandler.TOOL_TYPE_TEXT -> {
                val value = McpSessionArgs.text(args)
                params.put("action", "type").put("input_text", value)
                    .put("clear", McpSessionArgs.clearFirst(args))
                "mobile_action" to goalFingerprint(value)
            }
            McpHandler.TOOL_SYSTEM_BUTTON -> {
                val button = McpSessionArgs.button(args)
                params.put("button", button)
                "system_button" to button
            }
            McpHandler.TOOL_OPEN_APP -> {
                val app = McpSessionArgs.appName(args)
                params.put("app_name", app)
                "open_app" to "app:${goalFingerprint(app)}"
            }
            McpHandler.TOOL_WAIT -> {
                params.put("duration_ms", McpSessionArgs.durationMs(args))
                "wait" to "wait"
            }
            else -> error("Unexpected external session tool")
        }
        val waiting = name == McpHandler.TOOL_WAIT
        val services = brain.session.getServices()
        val callId = UUID.randomUUID().toString()
        val preResult = if (waiting) null else capture(brain)
        if (preResult is CaptureResult.VdLost) displayLost(brain, preResult.reason)
        checkLive(brain)
        val pre = (preResult as? CaptureResult.Ok)?.capture
        val tapIndex = if (name == McpHandler.TOOL_TAP) McpSessionArgs.elementIndex(args) else null
        val observedIdentity = tapIndex?.let { brain.observedElements?.get(it) }
        val tapElement = observedIdentity?.let { identity ->
            pre?.snapshot?.elements?.singleOrNull { it.identity() == identity }
        }
        val target = if (tapIndex == null) initialTarget else {
            val fingerprint = tapElement?.let {
                goalFingerprint("${it.resourceId}|${it.className}|${goalFingerprint(it.text)}")
            }
            "$initialTarget:${fingerprint ?: "unresolved"}"
        }
        fun rejected(error: String, note: String? = null): JsonObject {
            val record = BrainActionRecord(
                name, target, ScreenSignature(setOf("unavailable:$callId")), System.currentTimeMillis(),
                countsTowardGuard = false, signatureReliable = false,
            )
            synchronized(brain.terminalGate) {
                checkLive(brain)
                brain.record(
                    BrainTranscript.TYPE_ACTION,
                    BrainTranscript.action(record, error, verified = null),
                )
            }
            return buildJsonObject {
                put("runHandle", brain.handle)
                put("tool", name)
                put("status", "failed")
                put("error", error)
                put("miniObservation", JsonNull)
                if (note != null) put("captureNote", note)
            }
        }
        if (preResult is CaptureResult.Unavailable) {
            return rejected("capture_unavailable", preResult.note)
        }
        if (tapIndex != null && tapElement == null) return rejected("element_changed")
        val snapshotTargetMatches: ((ScreenSnapshot) -> Boolean)? = if (tapElement != null) {
            params.put("element_index", tapElement.index)
            val identity = tapElement.identity()
            val matches: (ScreenSnapshot) -> Boolean = { snapshot ->
                snapshot.elements.singleOrNull { it.identity() == identity }?.index == tapElement.index
            }
            matches
        } else null
        val openAppResolution = if (name == McpHandler.TOOL_OPEN_APP) {
            resolveOpenApp(params.getString("app_name").trim(), services.platform.getInstalledApps())
        } else null
        val guardTarget = openAppResolution?.match?.let { "package:${it.packageName}" } ?: target
        val context = SimpleToolRouterContext(
            services.platform, pre?.snapshot, brain.cancelledFlag, suppressObservation = true, external = true,
            deadlineExceeded = brain::deadlineExceeded,
            openAppResolution = openAppResolution,
            snapshotTargetMatches = snapshotTargetMatches,
        )
        brain.vdFailure()?.let { displayLost(brain, it) }
        if (!waiting) enforceCaps(brain)
        checkLive(brain)
        val dispatchPackage = services.platform.getCurrentPackageName()
        if (pre != null && dispatchPackage != pre.packageName) return rejected("foreground_changed")
        fun endedAction() = buildJsonObject {
            put("runHandle", brain.handle)
            put("tool", name)
            put("status", "cancelled")
            put("error", "session_ended")
        }
        var enteredApproval: ApprovalDetails? = null
        val result = try {
            services.toolRouter.execute(
                toolName = tool,
                params = params,
                context = context,
                packageName = dispatchPackage,
                callId = callId,
                onStateChange = {},
                onApprovalRequired = { details ->
                    // The router's type description includes part of the input.
                    val displayDetails = if (name == McpHandler.TOOL_TYPE_TEXT) {
                        details.copy(args = JSONObject(), description = "Type text into the focused field")
                    } else details
                    withContext(Dispatchers.Main.immediate) {
                        synchronized(brain.terminalGate) {
                            checkLive(brain)
                            val service = checkNotNull(serviceProvider())
                            check(service.surfaceExternalApproval(brain.session, displayDetails))
                            if (enteredApproval == null) {
                                enteredApproval = displayDetails
                                brain.record(
                                    BrainTranscript.TYPE_APPROVAL,
                                    BrainTranscript.approval(
                                        "entered", goalFingerprint(callId), displayDetails.packageName, brain.elapsedMs(),
                                    ),
                                )
                            }
                            brain.pendingApproval = displayDetails
                        }
                    }
                    approval.complete(displayDetails)
                },
            )
        } catch (e: CancellationException) {
            if (!isLive(brain)) return endedAction()
            throw e
        } catch (_: Exception) {
            if (!isLive(brain)) return endedAction()
            ToolCallResult.Error(callId, "Tool execution failed")
        } finally {
            enteredApproval?.let { details ->
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    synchronized(brain.terminalGate) {
                        if (isLive(brain)) {
                            brain.record(
                                BrainTranscript.TYPE_APPROVAL,
                                BrainTranscript.approval(
                                    "resolved", goalFingerprint(callId), details.packageName, brain.elapsedMs(),
                                ),
                            )
                        }
                    }
                    brain.ownerService.clearExternalApproval(brain.session, callId)
                    brain.pendingApproval = null
                }
            }
        }
        if (brain.deadlineExceeded() && !brain.terminalGate.isClaimed()) {
            terminate(brain, "External session exceeded its wall-clock limit")
        }
        if (!isLive(brain)) return endedAction()
        if ((result is ToolCallResult.Cancelled && result.reason == "element_changed") ||
            (name == McpHandler.TOOL_TAP && result is ToolCallResult.Error && result.error == "element_changed")
        ) {
            return rejected("element_changed")
        }
        if (waiting) brain.vdFailure()?.let { displayLost(brain, it) }
        val noPostCapture = waiting
        val postResult = if (noPostCapture) null else capture(brain)
        if (!isLive(brain)) return endedAction()
        if (postResult is CaptureResult.VdLost) displayLost(brain, postResult.reason)
        val post = (postResult as? CaptureResult.Ok)?.capture
        val (status, outcome, output) = when (result) {
            is ToolCallResult.Success -> Triple("ok", "success", result.output)
            is ToolCallResult.Error -> Triple("failed", "failed", result.error)
            is ToolCallResult.Cancelled -> Triple("cancelled", "cancelled", result.reason)
        }
        val reliable = post != null && post.snapshot.hasElements && !post.masked &&
            pre?.packageName == post.packageName
        val signature = post?.takeIf { reliable }?.snapshot?.brainSignature()
            ?: ScreenSignature(setOf("unavailable:$callId"))
        val changed = if (pre != null && post != null) {
            pre.packageName != post.packageName || pre.snapshot.elements != post.snapshot.elements
        } else null
        val record = BrainActionRecord(
            name, target, signature, System.currentTimeMillis(),
            countsTowardGuard = !waiting, signatureReliable = reliable,
        )
        val loop = synchronized(brain.terminalGate) {
            if (!isLive(brain)) return endedAction()
            val tripped = brain.guard.recordAndCheck(record.copy(targetKey = guardTarget)) == BrainGuardTrip.LOOP
            brain.record(
                BrainTranscript.TYPE_ACTION,
                BrainTranscript.action(
                    record, outcome, verified = null,
                    guardVerdict = if (waiting) null else if (tripped) "loop" else "ok",
                    changed = changed, postPackageName = post?.packageName,
                ),
            )
            tripped
        }
        if (loop) terminate(brain, "loop guard: repeated action with unchanged screen")
        return buildJsonObject {
            put("runHandle", brain.handle)
            put("status", status)
            put("tool", name)
            put("output", if (name == McpHandler.TOOL_TYPE_TEXT) {
                val length = McpSessionArgs.text(args).length
                if (result is ToolCallResult.Success) "typed $length chars" else "requested $length chars"
            } else output.take(500))
            if (post == null || pre == null) {
                put("miniObservation", JsonNull)
                if (!noPostCapture) put("captureNote", (postResult as? CaptureResult.Unavailable)?.note
                    ?: "Post-action screen unavailable")
            } else put("miniObservation", buildJsonObject {
                put("changed", UiChangeDetector.compare(pre.snapshot, post.snapshot).name.lowercase())
                put("packageName", post.packageName)
                put("elements", elements(post.snapshot))
            })
            if (loop) {
                put("loopDetected", true)
                put("sessionEnded", true)
            }
        }
    }

    private fun elements(snapshot: ScreenSnapshot) =
        Json.parseToJsonElement(Perceptor.toPromptJson(snapshot)).jsonArray

    private fun ownerIsActive(brain: BrainSession): Boolean =
        ownerIsCurrent(serviceProvider(), brain.ownerService, brain.ownerService::isExternalSessionOwnerActive)

    private fun stopIfOwnerLost(brain: BrainSession): Boolean {
        if (ownerIsActive(brain)) return false
        requestStop(brain, "External session lost its owning service")
        return true
    }

    private fun isLive(brain: BrainSession): Boolean =
        !stopIfOwnerLost(brain) && !brain.cancelledFlag.get() && !brain.terminalGate.isClaimed() &&
            brain.session.state.value != SessionState.Shutdown

    private fun sessionEnded() = McpTypedOpError("session_ended", "External session already ended")

    private fun checkLive(brain: BrainSession) {
        if (!isLive(brain)) throw sessionEnded()
    }

    private fun awaitingApproval(brain: BrainSession, details: ApprovalDetails) = buildJsonObject {
        put("runHandle", brain.handle)
        put("status", "awaiting_approval")
        put("pendingApproval", buildJsonObject {
            put("callId", details.callId)
            put("packageName", details.packageName)
            put("description", details.description)
        })
    }

    private suspend fun finish(brain: BrainSession, args: JsonObject): JsonObject =
        withContext(NonCancellable) {
            val vdLost = synchronized(brain.terminalGate) {
                if (brain.terminalGate.isClaimed() || stopIfOwnerLost(brain)) return@synchronized false
                val reason = brain.liveVdFailure()
                if (brain.session.state.value == SessionState.Shutdown) {
                    claimTerminal(brain, ShellyRunState.Finished.Kind.STOPPED, "External session ended")
                    return@synchronized false
                }
                if (reason != null) {
                    return@synchronized claimTerminal(
                        brain, ShellyRunState.Finished.Kind.FAILED, displayLostMessage(reason),
                    )
                }
                val kind = if (McpSessionArgs.outcome(args) == "failed") {
                    ShellyRunState.Finished.Kind.FAILED
                } else {
                    ShellyRunState.Finished.Kind.DONE
                }
                claimTerminal(brain, kind, McpSessionArgs.summary(args))
                false
            }
            finishResult(brain.handle, awaitTerminal(brain), vdLost)
        }

    private fun finishResult(
        handle: String,
        status: McpRunRegistry.Status,
        vdLost: Boolean = false,
    ) = buildJsonObject {
        put("runHandle", handle)
        put("status", status.wire)
        put("verified", false)
        if (vdLost) put("error", "virtual_display_lost")
    }

    private suspend fun terminate(
        brain: BrainSession,
        reason: String,
        preClaimGuard: () -> Boolean = { true },
    ): Boolean {
        if (stopIfOwnerLost(brain)) {
            awaitTerminal(brain)
            return true
        }
        return end(brain, ShellyRunState.Finished.Kind.FAILED, reason, preClaimGuard)
    }

    fun stop(handle: String): Boolean {
        val brain = sessions[handle] ?: return false
        requestStop(brain, "Stopped by request")
        return brain.terminalGate.claimedKind() == BrainTerminalKind.STOPPED
    }

    fun stopSession(session: AgentSession): Boolean = synchronized(ownershipLock) {
        val brain = sessions.values.firstOrNull { it.session === session } ?: return@synchronized false
        requestStop(brain, "Stopped by request")
        true
    }

    fun requestStopAll() {
        synchronized(ownershipLock) {
            ownership.retireCurrent()
            sessions.values.forEach { requestStop(it, "Stopped by request") }
        }
    }

    fun onOwnerServiceLost(service: AgentService) {
        synchronized(ownershipLock) {
            ownership.retireCurrent { it.ownerService === service }
            sessions.values.filter { it.ownerService === service }.forEach {
                requestStop(it, "External session lost its owning service")
            }
        }
    }

    private fun requestStop(brain: BrainSession, message: String) {
        claimTerminal(brain, ShellyRunState.Finished.Kind.STOPPED, message)
    }

    private fun ShellyRunState.Finished.Kind.toBrainTerminalKind(): BrainTerminalKind = when (this) {
        ShellyRunState.Finished.Kind.DONE -> BrainTerminalKind.DONE
        ShellyRunState.Finished.Kind.FAILED -> BrainTerminalKind.FAILED
        ShellyRunState.Finished.Kind.STOPPED -> BrainTerminalKind.STOPPED
    }

    private suspend fun end(
        brain: BrainSession,
        kind: ShellyRunState.Finished.Kind,
        message: String?,
        preClaimGuard: () -> Boolean = { true },
    ): Boolean = withContext(NonCancellable) {
        claimTerminal(brain, kind, message, preClaimGuard)
        // brain.completion is assigned by whichever claim landed (ours or a
        // competitor's) inside the same monitor block; it is null only when
        // the guard stood our claim down and no terminal claim exists — the
        // session is still live and there is nothing to await.
        val completion = synchronized(brain.terminalGate) { brain.completion }
        if (completion == null) {
            false
        } else {
            awaitTerminal(brain)
            true
        }
    }

    private fun claimTerminal(
        brain: BrainSession,
        kind: ShellyRunState.Finished.Kind,
        message: String?,
        preClaimGuard: () -> Boolean = { true },
    ): Boolean = synchronized(brain.terminalGate) {
        // The guard is evaluated under the same monitor that serializes
        // call-start bookkeeping (BrainActivityTracker), immediately before
        // the claim, so a call that starts after any earlier observation but
        // before this claim is observed by the guard and stands it down.
        if (!preClaimGuard()) return@synchronized false
        if (!brain.terminalGate.claim(kind.toBrainTerminalKind())) return@synchronized false
        val event = brain.event(
            BrainTranscript.TYPE_SESSION_FINISHED,
            BrainTranscript.sessionFinished(
                kind.name,
                verified = false,
                summaryFingerprint = message?.let(::goalFingerprint),
                actionsExecuted = brain.guard.actionsExecuted,
                elapsedMs = brain.elapsedMs(),
                reasonFingerprint = null,
            ),
        )
        brain.cancelledFlag.set(true)
        brain.expiry?.cancel()
        brain.healthWatcher?.cancel()
        brain.session.getServices().toolRouter.cancelAll()
        val status = McpRunStatus.of(kind)
        brain.completion = scope.async { completeTerminal(brain, status, message, event) }
        true
    }

    private suspend fun awaitTerminal(brain: BrainSession): McpRunRegistry.Status {
        val completion = synchronized(brain.terminalGate) { checkNotNull(brain.completion) }
        return completion.await()
    }

    private suspend fun completeTerminal(
        brain: BrainSession,
        status: McpRunRegistry.Status,
        message: String?,
        event: TraceEventRecord,
    ): McpRunRegistry.Status {
        // Every terminal path gets bounded acknowledgment retries on the original event.
        // Even finish_run commits its claimed status when the writer fails permanently.
        completeBrainTerminal(
            acknowledge = {
                try {
                    withTimeoutOrNull(TERMINAL_RECORD_TIMEOUT_MS) {
                        brain.recorder.recordAcknowledged(event, TERMINAL_RECORD_TIMEOUT_MS)
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            },
            waitBeforeRetry = { delay(TERMINAL_RECORD_RETRY_DELAY_MS) },
            publish = { recorded ->
                if (!recorded) Log.e(TAG, "Could not acknowledge the terminal session transcript")
                synchronized(ownershipLock) {
                    ownership.protect(brain.handle)
                    results[brain.handle] = BrainRunRecord(status, truncateSummary(message), recorded)
                    sessions.remove(brain.handle, brain)
                }
                synchronized(resultsLock) { resultsLock.notifyAll() }
            },
            settle = { settleTeardown(brain) },
            release = {
                synchronized(ownershipLock) {
                    BrainSessionAdmission.gate.sessionEnded()
                }
            },
        )
        return status
    }

    private fun truncateSummary(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        val limit = BrainSessionLimits.MAX_SUMMARY_CHARS
        if (trimmed.codePointCount(0, trimmed.length) <= limit) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, limit - 1)) + "…"
    }

    private fun BrainSession.record(type: String, payload: JsonObject) {
        recorder.record(event(type, payload))
    }

    private fun BrainSession.event(type: String, payload: JsonObject) = TraceEventRecord(
        runId = handle,
        seq = recorder.nextSeq(),
        tsMs = System.currentTimeMillis(),
        sessionId = session.sessionId.value,
        type = type,
        data = payload,
    )

    private companion object {
        private val RANDOM = SecureRandom()
        private val orphanedTraceDirectories = ConcurrentHashMap.newKeySet<String>()
        const val TAG = "ClosePawMcp"
        const val TRACE_MARKER = "brain-session.marker"
        const val TERMINAL_RECORD_TIMEOUT_MS = 2_000L
        const val TERMINAL_RECORD_RETRY_DELAY_MS = 150L
        const val TEARDOWN_TIMEOUT_MS = 10_000L
        private const val TEARDOWN_FALLBACK_HARD_TIMEOUT_MS = 60_000L
        const val TRACE_CLEANUP_HARD_BOUND_MS = 10 * 60_000L
        const val FINISH_LOCK_TIMEOUT_MS = 5_000L
        const val VD_HEALTH_POLL_MS = 1_000L
        const val START_TIMEOUT_MS = 60_000L
        const val ACTION_TIMEOUT_MS = 120_000L
        const val MAX_IMAGE_BYTES = 1_125_000
    }
}
