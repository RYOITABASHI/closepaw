package ai.closepaw.mcp

import ai.closepaw.agent.cognition.context.ScreenSignature
import ai.closepaw.agent.cognition.context.toSignature
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.ScreenSnapshot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal data class ElementIdentity(
    val packageName: String,
    val className: String,
    val text: String,
    val description: String,
    val bounds: Bounds,
    val resourceId: String,
)

internal fun PerceptionElement.identity() =
    ElementIdentity(packageName, className, text, description, bounds, resourceId)

/**
 * D4 generic defaults for brain-driven sessions.
 *
 * The loop window and similarity threshold follow the internal agent's LoopDetectionPolicy
 * (stableScreenWindow = 5, similarityThreshold = 0.95). A loop is a hard stop here because
 * the external brain has no turn limit of its own.
 */
object BrainSessionLimits {
    const val MAX_ACTIONS_PER_SESSION = 100
    const val MAX_SESSION_WALL_CLOCK_MS = 30 * 60 * 1000L
    /**
     * Brain-liveness bound (5 min). A session is terminated as unresponsive when its brain
     * makes no tool call for longer than this bound and no call is in flight.
     * [MAX_SESSION_WALL_CLOCK_MS] is the separate 30-minute absolute expiry.
     */
    const val BRAIN_STALENESS_MS = 300_000L

    const val MAX_WAIT_MS = 30_000L
    const val MAX_TYPE_TEXT_CHARS = 4_000
    const val MAX_APP_NAME_CHARS = 200
    const val MAX_SUMMARY_CHARS = 2_000
    const val LOOP_WINDOW = 5
    const val LOOP_SIMILARITY_THRESHOLD = 0.95
}

internal object BrainStaleness {
    /**
     * An in-flight call always exempts the session. The manager seeds [lastSeenMs] at
     * session creation, so a non-positive timestamp is a bookkeeping error and keeps
     * the session alive. Otherwise the idle interval must strictly exceed [boundMs].
     */
    fun shouldTerminate(lastSeenMs: Long, inFlight: Boolean, nowMs: Long, boundMs: Long): Boolean =
        !inFlight && lastSeenMs > 0 && nowMs - lastSeenMs > boundMs
}

/**
 * [tool] is the MCP tool name. [targetKey] is `element:<index>` for tap, a goal-style
 * fingerprint for type_text, `back` for system_button, an app-name fingerprint for
 * open_app, and `wait` for wait. Fingerprint caller-supplied text before creating a record.
 * [countsTowardGuard] controls whether the record consumes the action cap and enters the loop window.
 * [signatureReliable] indicates whether the signature can be used to establish screen stability.
 * An unreliable signature breaks the stable-screen window.
 */
internal data class BrainActionRecord(
    val tool: String,
    val targetKey: String,
    val screenSignature: ScreenSignature,
    val atMs: Long,
    val countsTowardGuard: Boolean = true,
    val signatureReliable: Boolean = true,
)

internal enum class BrainGuardTrip { ACTION_CAP, WALL_CLOCK, LOOP }

internal class BrainSessionGuard(val startedAtMs: Long) {
    private val lock = Any()
    private val actions = mutableListOf<BrainActionRecord>()

    val actionsExecuted: Int
        get() = synchronized(lock) { actions.size }

    fun checkBeforeAction(nowMs: Long): BrainGuardTrip? = synchronized(lock) {
        when {
            nowMs - startedAtMs >= BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS ->
                BrainGuardTrip.WALL_CLOCK
            actions.size >= BrainSessionLimits.MAX_ACTIONS_PER_SESSION -> BrainGuardTrip.ACTION_CAP
            else -> null
        }
    }

    fun recordAndCheck(record: BrainActionRecord): BrainGuardTrip? = synchronized(lock) {
        if (!record.countsTowardGuard) return@synchronized null
        actions.add(record)
        if (actions.size < BrainSessionLimits.LOOP_WINDOW) return@synchronized null

        val recent = actions.takeLast(BrainSessionLimits.LOOP_WINDOW)
        val comparable = recent.all { it.signatureReliable && it.targetKey == record.targetKey }
        val stableScreen = comparable && recent.zipWithNext().all { (previous, next) ->
            previous.screenSignature.similarityTo(next.screenSignature) >=
                BrainSessionLimits.LOOP_SIMILARITY_THRESHOLD
        }
        if (stableScreen) BrainGuardTrip.LOOP else null
    }
}

internal fun ScreenSnapshot.brainSignature(): ScreenSignature = toSignature()

internal enum class BrainTerminalKind { DONE, FAILED, STOPPED }

/**
 * Terminal-ownership reservation for one brain session. The FIRST claim wins and is
 * never revised: every terminal path (finish_run, stop, VD death, expiry, guard trip,
 * service teardown) must claim here before acting, and the transcript's terminal record
 * is written by the claim owner's reporting path inside the manager. The record, the
 * manager's result store, and the polling surface all derive the kind from that claim.
 * An action that observes a claim after its router call returns must skip its guard
 * accounting and its transcript action record — the terminal record already accounts
 * for the session.
 */
internal class BrainTerminalGate {
    private var claimed: BrainTerminalKind? = null

    /** Atomically claim [kind]. True only for the first caller. */
    @Synchronized
    fun claim(kind: BrainTerminalKind): Boolean {
        if (claimed != null) return false
        claimed = kind
        return true
    }

    @Synchronized
    fun claimedKind(): BrainTerminalKind? = claimed

    fun isClaimed(): Boolean = synchronized(this) { claimed != null }
}

/**
 * Activity bookkeeping for one brain session: the last tool-call timestamp and
 * the number of calls currently executing. Every read and write is serialized
 * on the terminal [gate]'s monitor — the same monitor the manager's claim path
 * (claimTerminal) holds while it claims on that gate. A call start/finish and
 * a guarded staleness claim therefore cannot interleave: whichever enters the
 * monitor first is fully observed by the other.
 */
internal class BrainActivityTracker(private val gate: BrainTerminalGate) {
    private var lastSeenMs: Long = 0L
    private var callsInFlight: Int = 0

    /** Seed at session creation: a session that never receives a call is measured from creation. */
    fun seed(startedAtMs: Long) {
        synchronized(gate) { lastSeenMs = startedAtMs }
    }

    fun noteCallStarted(nowMs: Long) {
        synchronized(gate) {
            callsInFlight++
            lastSeenMs = nowMs
        }
    }

    /** The completion stamp is written before the in-flight count is decremented. */
    fun noteCallFinished(nowMs: Long) {
        synchronized(gate) {
            lastSeenMs = nowMs
            callsInFlight--
        }
    }

    fun lastSeenMs(): Long = synchronized(gate) { lastSeenMs }

    fun hasCallInFlight(): Boolean = synchronized(gate) { callsInFlight > 0 }
}

/**
 * Runs the reporting protocol for one immutable terminal claim. A failed transcript still
 * reaches publication and teardown, with the failure carried into the stored result.
 */
internal suspend fun completeBrainTerminal(
    acknowledge: suspend () -> Boolean,
    waitBeforeRetry: suspend () -> Unit,
    publish: (transcriptRecorded: Boolean) -> Unit,
    settle: suspend () -> Unit,
    release: () -> Unit,
) {
    var recorded = false
    for (attempt in 1..3) {
        recorded = acknowledge()
        if (recorded) break
        if (attempt < 3) waitBeforeRetry()
    }
    publish(recorded)
    try {
        settle()
    } finally {
        release()
    }
}

/** A correlation stays reserved from acceptance through refusal or terminal publication. */
internal class BrainCorrelationReservations {
    private val reserved = mutableSetOf<String>()

    @Synchronized
    fun tryReserve(identity: String): Boolean = reserved.add(identity)

    @Synchronized
    fun release(identity: String) {
        reserved.remove(identity)
    }

    @Synchronized
    fun isReserved(identity: String): Boolean = identity in reserved
}

/**
 * Shared ownership registry for pending brain starts, adopted brain sessions, and internal claims.
 * Each start carries its own token, and every release removes that token.
 */
internal class BrainAdmissionGate {
    private var startToken: Any? = null
    private val sessionTokens = mutableSetOf<Any>()
    private val internalClaims = mutableSetOf<Any>()
    private val constructions = linkedMapOf<Any, Boolean>()
    private val settled = MutableStateFlow(0L)

    @Synchronized
    fun beginConstruction(token: Any, blocksIntake: Boolean = false): Boolean {
        if (blocksIntake && hasPendingIntakeConstruction()) return false
        if (!tryClaimInternal(token)) return false
        constructions[token] = blocksIntake
        return true
    }

    @Synchronized
    fun settleConstruction(token: Any) {
        if (constructions.remove(token) == null) return
        releaseInternal(token)
        settled.value++
    }

    @Synchronized
    fun hasPendingConstruction(except: Any? = null): Boolean =
        (startToken != null && startToken !== except) || constructions.keys.any { it !== except }

    @Synchronized
    fun hasPendingIntakeConstruction(): Boolean = constructions.containsValue(true)

    /** A later registration waits for this start, so waiting for it would form a cycle. */
    @Synchronized
    fun hasPendingPredecessor(token: Any): Boolean {
        if (startToken != null && startToken !== token) return true
        return constructions.keys.firstOrNull()?.let { it !== token } ?: false
    }

    suspend fun awaitPendingPredecessors(token: Any) {
        settled.first { !hasPendingPredecessor(token) }
    }

    @Synchronized
    fun tryBeginStart(token: Any): Boolean {
        if (isAnyActive()) return false
        startToken = token
        return true
    }

    @Synchronized
    fun startAborted(token: Any) {
        if (startToken === token) {
            startToken = null
            settled.value++
        }
    }

    @Synchronized
    fun startAdopted(token: Any) {
        check(startToken === token)
        startToken = null
        sessionTokens.add(token)
        settled.value++
    }

    /** Release only once teardown has settled or its bounded wait has expired. */
    @Synchronized
    fun sessionEnded(token: Any) {
        sessionTokens.remove(token)
    }

    @Synchronized
    fun tryClaimInternal(token: Any): Boolean {
        if (isBrainActive()) return false
        internalClaims.add(token)
        return true
    }

    @Synchronized
    fun releaseInternal(token: Any) {
        internalClaims.remove(token)
    }

    @Synchronized
    fun isBrainActive(): Boolean = startToken != null || sessionTokens.isNotEmpty()

    @Synchronized
    fun isInternalActive(): Boolean = internalClaims.isNotEmpty()

    @Synchronized
    fun isAnyActive(): Boolean = isBrainActive() || isInternalActive()

    @Synchronized
    fun hasLiveSession(): Boolean = sessionTokens.isNotEmpty()
}
