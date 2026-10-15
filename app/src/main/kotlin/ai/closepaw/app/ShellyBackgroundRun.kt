package ai.closepaw.app

import ai.closepaw.R
import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.ActionExecuted
import ai.closepaw.protocol.ApprovalRequired
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.ScreenCaptured
import ai.closepaw.protocol.SessionCompleted
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionResumed
import ai.closepaw.protocol.SessionTakeover
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.TurnPhaseChanged

/**
 * Background ("headless") runs requested by a verified Shelly caller.
 *
 * Entry point: [ShellyGoalReceiver] (explicit broadcast). Unlike the MainActivity hand-off, this
 * path never opens ClosePaw's UI: the goal runs on the Shizuku virtual display and the only
 * visible indicators are the Smart Capsule overlay and an ongoing notification
 * ([ShellyRunNotifier]). Everything in this file is pure logic so it can be unit tested.
 */
object ShellyBackgroundRun {
    const val ACTION_RUN_GOAL = "ai.closepaw.action.SHELLY_RUN_GOAL"
    const val EXTRA_GOAL = "goal"

    /**
     * Opt-in: when the virtual display is unavailable, run on the main display instead of
     * refusing. Default false — the run would take over the user's screen.
     */
    const val EXTRA_ALLOW_MAIN_DISPLAY = "allow_main_display"

    const val MAX_GOAL_CHARS = 4_000

    /**
     * Result callback correlation (Shelly >= shim v14, see [ShellyResultCallback]). Shelly sends
     * its request id and a per-run random nonce with the goal; ClosePaw echoes both back when the
     * run finishes. Both are optional: without them (older Shelly, agent-run hand-offs) no result
     * is sent back. Malformed values are dropped, never fail the request.
     */
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
    const val RESULT_STARTED_MAIN_DISPLAY = 2
    const val RESULT_REJECTED = 10

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

    data class Request(
        val goal: String,
        val allowMainDisplay: Boolean,
        val correlation: ResultCorrelation? = null,
    )

    /**
     * Trust gate for the exported receiver. Returns null (= ignore the broadcast, do nothing
     * visible) unless the action matches AND the caller token verified AND a goal is present.
     */
    fun parseRequest(
        action: String?,
        goalExtra: String?,
        callerVerified: Boolean,
        allowMainDisplay: Boolean,
        reqIdExtra: String? = null,
        nonceExtra: String? = null,
    ): Request? {
        if (action != ACTION_RUN_GOAL) return null
        if (!callerVerified) return null
        val goal = goalExtra?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Request(goal.take(MAX_GOAL_CHARS), allowMainDisplay, parseResultCorrelation(reqIdExtra, nonceExtra))
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
) {
    /** Set once the final state was reported to Shelly, so it is sent at most once. */
    var resultReported: Boolean = false

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
                state = ShellyRunState.Finished(ShellyRunState.Finished.Kind.STOPPED, null)
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
