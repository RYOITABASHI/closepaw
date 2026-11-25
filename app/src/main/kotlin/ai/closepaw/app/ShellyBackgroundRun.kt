package ai.closepaw.app

import ai.closepaw.R
import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.ActionExecuted
import ai.closepaw.protocol.ApprovalRequired
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.ScreenCaptured
import ai.closepaw.protocol.SessionCompleted
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionResumed
import ai.closepaw.protocol.SessionTakeover
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.TurnPhaseChanged
import kotlinx.coroutines.launch

/** Shared admission, broadcast result codes, and callback correlation for background work. */
object ShellyBackgroundRun {
    /** Request ID and nonce echoed by [ShellyResultCallback] when work finishes. */
    const val EXTRA_SHELLY_REQ_ID = "shelly_req_id"
    const val EXTRA_SHELLY_RESULT_NONCE = "shelly_result_nonce"
    private val SHELLY_REQ_ID_RE = Regex("^[0-9]{10,16}-[0-9a-f]{8}$")
    private val SHELLY_RESULT_NONCE_RE = Regex("^[0-9a-f]{32}$")

    /** Correlation for the result callback; null unless BOTH extras are well-formed. */
    data class ResultCorrelation(val reqId: String, val nonce: String)

    fun parseResultCorrelation(reqIdExtra: String?, nonceExtra: String?): ResultCorrelation? {
        val reqId = reqIdExtra?.takeIf { SHELLY_REQ_ID_RE.matches(it) } ?: return null
        val nonce = nonceExtra?.takeIf { SHELLY_RESULT_NONCE_RE.matches(it) } ?: return null
        return ResultCorrelation(reqId, nonce)
    }

    // Ordered-broadcast result codes (Shelly should send with initialCode = 0, so 0 means
    // "no receiver answered": ClosePaw missing, too old, or stopped).
    const val RESULT_STARTED_VIRTUAL_DISPLAY = 1
    const val RESULT_REJECTED = 10

    /**
     * Ordered-result extra (Int 1): this run will send [ShellyResultCallback]. Shelly treats a
     * started run without it as "no result will come" instead of waiting forever.
     */
    const val RESULT_EXTRA_RESULT_CALLBACK = "result_callback"

    /**
     * Replacing a session: must the new session wait until the replaced session's shutdown has
     * COMPLETED before it starts? Yes whenever the old teardown may still move tasks on display 0
     * (Android task ids and display 0 are global, a separate VD does not isolate them):
     * - [returnsMainDisplayTasks]: the old platform restores / hands over display-0 tasks in its
     *   teardown (Shelly background runs), whether or not anything is recorded yet;
     * - [runEndRestoreInFlight]: its run-end restore was launched and has not finished, so a
     *   deferral may be recorded (and then executed by the teardown) after this check;
     * - [deferredWork]: a deferred display-0 restore is recorded.
     * The wait has no give-up: a bound may only fail the new start, never let it run concurrently.
     */
    fun mustAwaitReplacedShutdown(
        returnsMainDisplayTasks: Boolean,
        runEndRestoreInFlight: Boolean,
        deferredWork: Boolean,
    ): Boolean = returnsMainDisplayTasks || runEndRestoreInFlight || deferredWork

    enum class Block(val resultCode: Int) {
        ACCESSIBILITY_OFF(11),
        ONBOARDING_REQUIRED(12),
        OVERLAY_PERMISSION_MISSING(13),
        CREDENTIALS_MISSING(14),
        BUSY(15),
        VIRTUAL_DISPLAY_UNAVAILABLE(16),
    }

    sealed interface Decision {
        data class Start(val platformMode: PlatformMode) : Decision
        data class Blocked(val block: Block) : Decision
    }

    /** Preconditions, checked synchronously before anything becomes visible. */
    fun precheck(
        accessibilityConnected: Boolean,
        onboardingCompleted: Boolean,
        canDrawOverlays: Boolean,
        credentialsMissing: Boolean,
        sessionBusy: Boolean,
        virtualDisplayReady: Boolean,
        allowMainDisplay: Boolean,
    ): Decision = when {
        !accessibilityConnected -> Decision.Blocked(Block.ACCESSIBILITY_OFF)
        !onboardingCompleted -> Decision.Blocked(Block.ONBOARDING_REQUIRED)
        !canDrawOverlays -> Decision.Blocked(Block.OVERLAY_PERMISSION_MISSING)
        credentialsMissing -> Decision.Blocked(Block.CREDENTIALS_MISSING)
        sessionBusy -> Decision.Blocked(Block.BUSY)
        virtualDisplayReady -> Decision.Start(PlatformMode.VIRTUAL_DISPLAY)
        allowMainDisplay -> Decision.Start(PlatformMode.ACCESSIBILITY)
        else -> Decision.Blocked(Block.VIRTUAL_DISPLAY_UNAVAILABLE)
    }
}

enum class ShellyRunOrigin {
    SHELLY,
    MCP;

    fun shouldNotify(state: ShellyRunState): Boolean =
        this == SHELLY || state !is ShellyRunState.Finished
}

/** What the user should be told about a running Shelly background run. */
sealed interface ShellyRunState {
    /** Agent is working. [onMainDisplay] = it is driving the real screen (do not touch). */
    data class Working(val onMainDisplay: Boolean, val appPackages: List<String>) : ShellyRunState

    data class WaitingForApproval(
        val onMainDisplay: Boolean,
        val callId: String,
        val packageName: String,
        val description: String,
    ) : ShellyRunState

    /** Paused because the user opened the app the agent was operating ([packageName]), or took over. */
    data class Paused(val packageName: String?) : ShellyRunState

    data class Finished(val kind: Kind, val message: String?) : ShellyRunState {
        enum class Kind { DONE, STOPPED, FAILED }
    }
}

/**
 * Tracks one Shelly background run (one session). Pure state machine; the Android side feeds it
 * agent events and accessibility window events and renders [state].
 */
class ShellyRunTracker(
    val sessionId: String,
    val onMainDisplay: Boolean,
    private val ignoredPackages: Set<String>,
    /** Where to report the final state (null: the caller did not ask for a result). */
    val correlation: ShellyBackgroundRun.ResultCorrelation? = null,
    /**
     * The start's shared report-once guard (see [ShellyPendingResult]). AgentService passes the
     * guard it created when it accepted the start, so the tracker and the pre-tracker paths
     * (startup failure / cancellation / destroy) can never both report.
     */
    val result: ShellyPendingResult = ShellyPendingResult(correlation),
    val origin: ShellyRunOrigin = ShellyRunOrigin.SHELLY,
) {
    /** True once the final state was reported to Shelly (by any path of this start). */
    val resultReported: Boolean get() = result.isClaimed

    /**
     * Claims this run's result once it is finished: the payload for the first claim, null when
     * still running, already claimed (by any path of this start), or no result was requested.
     */
    fun claimResult(nowMs: Long): ShellyResultCallback.Payload? {
        val finished = state as? ShellyRunState.Finished ?: return null
        return result.claimBrainTerminal(finished.kind, finished.message, nowMs, sessionId)
    }

    private val vdPackages = LinkedHashSet<String>()

    var state: ShellyRunState = ShellyRunState.Working(onMainDisplay, emptyList())
        private set

    val isFinished: Boolean get() = state is ShellyRunState.Finished

    /** Apps the agent is (or recently was) operating on the virtual display, most recent last. */
    val operatedPackages: List<String> get() = vdPackages.toList()

    private fun rememberPackage(pkg: String?): Boolean {
        if (onMainDisplay || pkg.isNullOrBlank() || pkg in ignoredPackages) return false
        if (vdPackages.lastOrNull() == pkg) return false
        vdPackages.remove(pkg)
        vdPackages.add(pkg)
        while (vdPackages.size > MAX_TRACKED_APPS) vdPackages.remove(vdPackages.first())
        return true
    }

    private fun refreshWorking(): Boolean {
        val current = state
        if (current is ShellyRunState.Working) {
            val next = current.copy(appPackages = operatedPackages)
            if (next != current) {
                state = next
                return true
            }
        }
        return false
    }

    /** A window appeared on the virtual display (display id != 0). Returns true if [state] changed. */
    fun onVirtualDisplayWindow(pkg: String?): Boolean {
        if (isFinished) return false
        return rememberPackage(pkg) && refreshWorking()
    }

    /**
     * A window appeared on the main display. If it belongs to an app the agent is operating on the
     * virtual display, the user just pulled that app's task off the VD: pause instead of fighting
     * the user. Returns the conflicting package (caller must request a takeover/pause), else null.
     */
    fun onMainDisplayWindow(pkg: String?): String? {
        if (onMainDisplay || pkg.isNullOrBlank() || pkg in ignoredPackages) return null
        if (state !is ShellyRunState.Working) return null
        if (pkg !in vdPackages) return null
        state = ShellyRunState.Paused(pkg)
        return pkg
    }

    /** The user answered the pending approval (capsule or notification). */
    fun onApprovalResolved(callId: String): Boolean {
        val current = state as? ShellyRunState.WaitingForApproval ?: return false
        if (current.callId != callId) return false
        state = ShellyRunState.Working(onMainDisplay, operatedPackages)
        return true
    }

    /** Returns true if [state] changed. */
    fun onEvent(event: AgentEvent): Boolean {
        if (isFinished) return false
        val before = state
        when (event) {
            is ScreenCaptured -> {
                rememberPackage(event.packageName)
                refreshWorking()
            }
            is ApprovalRequired -> {
                rememberPackage(event.details.packageName)
                state = ShellyRunState.WaitingForApproval(
                    onMainDisplay = onMainDisplay,
                    callId = event.details.callId,
                    packageName = event.details.packageName,
                    description = event.details.description.ifBlank { event.description },
                )
            }
            is ActionExecuted, is TurnPhaseChanged -> {
                if (state is ShellyRunState.WaitingForApproval) {
                    state = ShellyRunState.Working(onMainDisplay, operatedPackages)
                }
            }
            is SessionTakeover -> {
                if (state !is ShellyRunState.Paused) state = ShellyRunState.Paused(null)
            }
            is SessionResumed -> {
                state = ShellyRunState.Working(onMainDisplay, operatedPackages)
            }
            is TaskCompleted -> {
                val kind = when (event.outcome) {
                    TaskOutcome.GOAL_ACHIEVED -> ShellyRunState.Finished.Kind.DONE
                    TaskOutcome.USER_STOPPED -> ShellyRunState.Finished.Kind.STOPPED
                    TaskOutcome.TASK_IMPOSSIBLE, TaskOutcome.ERROR -> ShellyRunState.Finished.Kind.FAILED
                }
                state = ShellyRunState.Finished(kind, event.result?.takeIf { it.isNotBlank() })
            }
            is SessionError -> {
                state = ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, event.message)
            }
            is SessionCompleted -> {
                // USER_STOPPED is a genuine stop, including STOP-ALL.
                // Idle timeout and interruption end the session without a final answer.
                state = when (event.reason) {
                    SessionEndReason.USER_STOPPED ->
                        ShellyRunState.Finished(ShellyRunState.Finished.Kind.STOPPED, null)
                    SessionEndReason.IDLE_TIMEOUT, SessionEndReason.INTERRUPTED ->
                        ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, NO_ANSWER_MESSAGE)
                }
            }
            else -> Unit
        }
        return state != before
    }

    /** Ends the run from outside the event stream (e.g. replaced by another session). */
    fun finish(kind: ShellyRunState.Finished.Kind, message: String? = null) {
        if (!isFinished) state = ShellyRunState.Finished(kind, message)
    }

    companion object {
        const val NO_ANSWER_MESSAGE = "The session ended without a final answer"
        private const val MAX_TRACKED_APPS = 3
    }
}

/**
 * User-facing text for [ShellyRunState]. All strings live in `res/values{,-ja}/strings_shelly.xml`;
 * [res] resolves a string resource with format args (production: `context::getString`), which
 * keeps this class testable on the JVM.
 */
class ShellyRunText(private val res: (id: Int, args: Array<out Any>) -> String) {
    constructor(context: android.content.Context) :
        this({ id, args -> context.getString(id, *args) })

    data class Content(val title: String, val body: String)

    private fun s(id: Int, vararg args: Any) = res(id, args)

    private fun quote(label: String) = s(R.string.shelly_quoted_app, label)

    fun render(state: ShellyRunState, labelOf: (String) -> String): Content = when (state) {
        is ShellyRunState.Working ->
            if (state.onMainDisplay) {
                Content(s(R.string.shelly_main_display_title), s(R.string.shelly_main_display_body))
            } else {
                val apps = state.appPackages.map(labelOf).map(::quote)
                Content(
                    s(R.string.shelly_background_title),
                    if (apps.isEmpty()) {
                        s(R.string.shelly_background_body_generic)
                    } else {
                        s(R.string.shelly_background_body_apps, apps.joinToString(s(R.string.shelly_list_separator)))
                    },
                )
            }
        is ShellyRunState.WaitingForApproval -> {
            val ask = s(R.string.shelly_approval_body, quote(labelOf(state.packageName)))
            Content(
                s(R.string.shelly_approval_title),
                ask + (state.description.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
            )
        }
        is ShellyRunState.Paused -> Content(
            s(R.string.shelly_paused_title),
            state.packageName?.let { s(R.string.shelly_paused_body_app, quote(labelOf(it))) }
                ?: s(R.string.shelly_paused_body_generic),
        )
        is ShellyRunState.Finished -> when (state.kind) {
            ShellyRunState.Finished.Kind.DONE -> Content(s(R.string.shelly_done_title), state.message ?: "")
            ShellyRunState.Finished.Kind.STOPPED -> Content(s(R.string.shelly_stopped_title), state.message ?: "")
            ShellyRunState.Finished.Kind.FAILED -> Content(s(R.string.shelly_failed_title), state.message ?: "")
        }
    }

    fun blocked(block: ShellyBackgroundRun.Block): Content = when (block) {
        ShellyBackgroundRun.Block.ACCESSIBILITY_OFF ->
            Content(s(R.string.shelly_blocked_title), s(R.string.shelly_blocked_accessibility))
        ShellyBackgroundRun.Block.ONBOARDING_REQUIRED ->
            Content(s(R.string.shelly_blocked_title), s(R.string.shelly_blocked_onboarding))
        ShellyBackgroundRun.Block.OVERLAY_PERMISSION_MISSING ->
            Content(s(R.string.shelly_blocked_title), s(R.string.shelly_blocked_overlay))
        ShellyBackgroundRun.Block.CREDENTIALS_MISSING ->
            Content(s(R.string.shelly_blocked_title), s(R.string.shelly_blocked_credentials))
        ShellyBackgroundRun.Block.BUSY ->
            Content(s(R.string.shelly_busy_title), s(R.string.shelly_busy_body))
        ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE ->
            Content(s(R.string.shelly_no_vd_title), s(R.string.shelly_no_vd_body))
    }

    val actionStop get() = s(R.string.shelly_action_stop)
    val actionResume get() = s(R.string.shelly_action_resume)
    val actionAllowAlways get() = s(R.string.shelly_action_allow_always)
    val actionAllowSession get() = s(R.string.shelly_action_allow_session)
    val actionReject get() = s(R.string.shelly_action_reject)
    val lockScreenSummary get() = s(R.string.shelly_lock_screen_summary)
    val channelStatus get() = s(R.string.shelly_channel_status)
    val channelAttention get() = s(R.string.shelly_channel_attention)
}

/**
 * Shut a replaced session down so that the barrier's view of it is truthful (gate round 3).
 *
 * Returns the RECONCILED signal to register in [ReplacedSessionShutdownBarrier]: a parentless
 * job that completes normally only once the teardown has actually run ([shutdown] returned and
 * [isTornDown] holds afterwards), and completes exceptionally otherwise. It is never a child of
 * [scope], so cancelling [scope] cannot complete it "successfully".
 *
 * The teardown-critical path (join the run-end restore, then [shutdown]) runs under
 * NonCancellable: once started, cancellation of [scope] cannot skip it. Production passes a
 * process-lifetime scope that nothing cancels (AgentService.replacedTeardownScope), so the
 * body always starts; should it ever be cancelled before starting anyway, the launched job's
 * completion fails [reconciled] (the barrier keeps the entry and starts fail) instead of letting
 * it look reconciled.
 *
 * [shutdown] may return without the teardown having run (the session was already claimed by an
 * earlier shutdown that did not reach platform stop): then [stopPlatform] (idempotent) runs once
 * and [isTornDown] is checked again. Any failure goes to [onFailure] (logging) and fails
 * [reconciled].
 */
internal fun launchReplacedSessionTeardown(
        scope: kotlinx.coroutines.CoroutineScope,
        runEndRestore: kotlinx.coroutines.Job?,
        shutdown: suspend () -> Unit,
        isTornDown: () -> Boolean,
        stopPlatform: suspend () -> Unit,
        onFailure: (Throwable) -> Unit = {},
): kotlinx.coroutines.Job {
    val reconciled = kotlinx.coroutines.Job()
    val body = scope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try {
                // Its deferral decision is recorded before the teardown executes / hands it over.
                runEndRestore?.join()
                shutdown()
                if (!isTornDown()) stopPlatform()
                check(isTornDown()) { "the replaced session's platform did not stop" }
                reconciled.complete()
            } catch (e: Throwable) {
                onFailure(e)
                reconciled.completeExceptionally(e)
            }
        }
    }
    // Cancelled before the body started (or anything else that ends it without a verdict).
    body.invokeOnCompletion { cause ->
        if (!reconciled.isCompleted) {
            val failure = cause ?: IllegalStateException("replaced-session teardown ended without running")
            onFailure(failure)
            reconciled.completeExceptionally(failure)
        }
    }
    return reconciled
}

/**
 * The one way AgentService tears a session down while a later session may start (gate round 4):
 * used for a REPLACED session and for the session still attached when the service is DESTROYED
 * (a service restart in the same process starts its next session against the same process-wide
 * [barrier]). Launches [launchReplacedSessionTeardown] on [scope] and, when [mustAwait]
 * ([ShellyBackgroundRun.mustAwaitReplacedShutdown]), registers its reconciled signal in [barrier]
 * under [owner] BEFORE returning, i.e. before the caller detaches the session. The entry is removed
 * only when the teardown verifiably completed (shutdown returned + platform torn down); failure or
 * cancellation before the body started leaves it, and waiting starts fail closed.
 */
internal fun launchRegisteredSessionTeardown(
        barrier: ReplacedSessionShutdownBarrier,
        scope: kotlinx.coroutines.CoroutineScope,
        mustAwait: Boolean,
        owner: Any?,
        runEndRestore: kotlinx.coroutines.Job?,
        shutdown: suspend () -> Unit,
        isTornDown: () -> Boolean,
        stopPlatform: suspend () -> Unit,
        onFailure: (Throwable) -> Unit = {},
): kotlinx.coroutines.Job {
    val reconciled = launchReplacedSessionTeardown(
            scope = scope,
            runEndRestore = runEndRestore,
            shutdown = shutdown,
            isTornDown = isTornDown,
            stopPlatform = stopPlatform,
            onFailure = onFailure,
    )
    if (mustAwait) barrier.add(reconciled, owner = owner)
    return reconciled
}

/**
 * onDestroy's bounded wait, observe-only (gate round 4): logs via [onTimeout] when [teardown] has
 * not completed within [timeoutMs]. It only JOINS [teardown]; the timeout cancels the join, never
 * the teardown, so the bound can no longer abandon a teardown (before or after it started) — the
 * teardown and its barrier entry continue independently of this watcher and of [scope].
 */
internal fun watchSessionTeardown(
        scope: kotlinx.coroutines.CoroutineScope,
        teardown: kotlinx.coroutines.Job,
        timeoutMs: Long,
        onTimeout: () -> Unit,
): kotlinx.coroutines.Job = scope.launch {
    val finished = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        teardown.join()
        true
    }
    if (finished != true) onTimeout()
}

/**
 * Barrier for shutdowns of replaced sessions that a new run must not overlap
 * ([ShellyBackgroundRun.mustAwaitReplacedShutdown]). A shutdown stays registered until it
 * completes SUCCESSFULLY ([launchReplacedSessionTeardown]'s reconciled signal), independent of
 * who waits for it: a start that gives up waiting (and fails) leaves it in place, so every later
 * start waits for it as well. A shutdown that completes UNSUCCESSFULLY (cancelled or failed:
 * the teardown did not verifiably run) is never treated as reconciled: it stays registered and
 * every start that would wait for it fails right away (the old platform may still act on the
 * global tasks; failing the start is the safe side).
 *
 * Android tasks and display 0 are global, so the production barrier is process-wide
 * ([process]): AgentService registers every replaced session's shutdown in it, and EVERY
 * session waits for it right before its platform starts a task (AgentSession's first-task and
 * Hot Idle follow-up platform start), whoever created the session (AgentService, MainActivity,
 * onboarding) and whoever submitted the task.
 */
internal class ReplacedSessionShutdownBarrier {
    companion object {
        /** The process-wide barrier (see the class doc). */
        val process = ReplacedSessionShutdownBarrier()

        /** Liveness bound for a session start waiting on [process]; past it the start fails. */
        const val START_TIMEOUT_MS = 30_000L

        private val NO_OWNER = Any()
    }

    /**
     * Registered shutdown -> its owner (the replaced session's platform), [NO_OWNER] when unknown.
     * Holds running shutdowns and ones that completed unsuccessfully (never removed).
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<kotlinx.coroutines.Job, Any>()

    /**
     * Register [shutdown]; [owner] identifies whose teardown it is (see [awaitClear]'s exceptOwner).
     * Only a NORMAL completion deregisters it: cancellation or failure leaves it registered.
     */
    fun add(shutdown: kotlinx.coroutines.Job, owner: Any? = null) {
        pending[shutdown] = owner ?: NO_OWNER
        // Fires immediately when already complete.
        shutdown.invokeOnCompletion { cause -> if (cause == null) pending.remove(shutdown) }
    }

    /** Shutdowns still registered: running, or ended without reconciling (for tests and logs). */
    val outstanding: Int get() = pending.size

    /** Tests only: drop [shutdown] (a deliberately failed entry must not leak into other tests via [process]). */
    internal fun discardForTest(shutdown: kotlinx.coroutines.Job) {
        pending.remove(shutdown)
    }

    /**
     * Wait until every registered shutdown has completed successfully, including ones registered
     * while waiting. Shutdowns owned by [exceptOwner] are not waited for: a session never waits
     * for its own teardown (that teardown needs the session's lifecycle lock, which the waiting
     * start holds). False when [timeoutMs] elapsed first, or right away when a shutdown ended
     * unsuccessfully (it can never reconcile); the barrier itself is left intact either way.
     */
    suspend fun awaitClear(timeoutMs: Long, exceptOwner: Any? = null): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (true) {
                val relevant = pending.entries
                    .filter { exceptOwner == null || it.value !== exceptOwner }
                    .map { it.key }
                val running = ArrayList<kotlinx.coroutines.Job>()
                for (job in relevant) {
                    // Read completion FIRST: once completed, the outcome is final, so a job that
                    // fails between two reads is either still "running" here (joined, then
                    // re-classified on the next pass) or seen completed with its final outcome.
                    if (!job.isCompleted) {
                        running += job
                    } else if (job.isCancelled) {
                        // Cancelled / failed: the teardown did not verifiably run. Never "reconciled".
                        return@withTimeoutOrNull false
                    }
                    // Completed normally but not deregistered yet: it counts as clear.
                }
                if (running.isEmpty()) break
                running.forEach { it.join() }
            }
            true
        } == true
}
