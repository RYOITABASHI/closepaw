package ai.closepaw.mcp

import ai.closepaw.agent.cognition.context.ScreenSignature
import ai.closepaw.agent.cognition.context.toSignature
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.ScreenSnapshot

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
    const val MAX_WAIT_MS = 30_000L
    const val MAX_TYPE_TEXT_CHARS = 4_000
    const val MAX_APP_NAME_CHARS = 200
    const val MAX_SUMMARY_CHARS = 2_000
    const val LOOP_WINDOW = 5
    const val LOOP_SIMILARITY_THRESHOLD = 0.95
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

/** One reservation for brain startup, adopted brain sessions, and internal work. */
internal class BrainAdmissionGate {
    private var startToken: Any? = null
    private var activeSessions = 0
    private val internalClaims = mutableSetOf<Any>()

    @Synchronized
    fun tryBeginStart(token: Any): Boolean {
        if (isAnyActive()) return false
        startToken = token
        return true
    }

    @Synchronized
    fun startAborted(token: Any) {
        if (startToken === token) startToken = null
    }

    @Synchronized
    fun startAdopted(token: Any) {
        check(startToken === token)
        startToken = null
        activeSessions++
    }

    /** Release only once teardown has settled or its bounded wait has expired. */
    @Synchronized
    fun sessionEnded() {
        if (activeSessions > 0) activeSessions--
    }

    @Synchronized
    fun tryClaimInternal(token: Any): Boolean {
        if (isBrainActive() || (internalClaims.isNotEmpty() && token !in internalClaims)) return false
        internalClaims.add(token)
        return true
    }

    @Synchronized
    fun releaseInternal(token: Any) {
        internalClaims.remove(token)
    }

    @Synchronized
    fun isBrainActive(): Boolean = startToken != null || activeSessions > 0

    @Synchronized
    fun isInternalActive(): Boolean = internalClaims.isNotEmpty()

    @Synchronized
    fun isAnyActive(): Boolean = isBrainActive() || isInternalActive()

    @Synchronized
    fun hasLiveSession(): Boolean = activeSessions > 0
}
