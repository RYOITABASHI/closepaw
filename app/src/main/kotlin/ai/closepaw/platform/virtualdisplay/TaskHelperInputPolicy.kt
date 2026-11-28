package ai.closepaw.platform.virtualdisplay

internal object TaskHelperInputPolicy {
    // Switch in a separate commit after the device B1/B2 gate.
    const val HELPER_ROUTE_ENABLED = true

    fun useHelper(sdkInt: Int, routeEnabled: Boolean = HELPER_ROUTE_ENABLED): Boolean =
        routeEnabled && sdkInt >= 33
}

/**
 * Judgment item 2 (2026-11-28): Shizuku as an explicit second input channel when the adb channel
 * is confirmed unavailable BEFORE dispatch. Off by default; enabling it as the default requires
 * the device negative checks (no display 0, STOP-ALL refusal, root refusal, unobservable refusal).
 */
internal object SecondChannelPolicy {
    const val ENABLED = true // VERIFY BUILD ONLY — never merge
    const val SHELL_UID = 2000

    sealed interface Decision {
        data object Allow : Decision
        data class Refuse(val reason: String) : Decision
    }

    /**
     * Only reached when the adb channel is confirmed unavailable before anything was sent, so a
     * resend can never duplicate input (the PR #29 rationale). Every missing guarantee refuses.
     */
    fun decide(
        enabled: Boolean,
        binderAlive: Boolean,
        serverUid: Int?,
        targetDisplayId: Int?,
        displayExists: Boolean,
        displayIdInjection: Boolean,
        canObserve: Boolean,
    ): Decision = when {
        !enabled -> Decision.Refuse("disabled")
        !binderAlive -> Decision.Refuse("shizuku_unavailable")
        serverUid == null -> Decision.Refuse("server_uid_unknown")
        serverUid != SHELL_UID -> Decision.Refuse("server_uid_$serverUid")
        targetDisplayId == null || targetDisplayId <= 0 -> Decision.Refuse("no_virtual_display")
        !displayExists -> Decision.Refuse("display_missing")
        // Without setDisplayId the incumbent path would use the shell (the adb channel itself).
        !displayIdInjection -> Decision.Refuse("display_id_injection_unsupported")
        !canObserve -> Decision.Refuse("unobservable")
        else -> Decision.Allow
    }
}

/**
 * Process-wide record of the channel VD agent input last used (guard 6), reported by
 * closepaw_status. "shizuku" means degraded: the adb channel was down; every later operation
 * still tries the adb channel first, so it switches back at the next operation boundary.
 */
internal object VdInputChannelState {
    @Volatile var channel: String = "adb"
        private set
    @Volatile var degradedSinceMs: Long? = null
        private set

    fun record(channel: String, nowMs: Long) {
        if (channel == "shizuku") {
            if (this.channel != "shizuku") degradedSinceMs = nowMs
        } else {
            degradedSinceMs = null
        }
        this.channel = channel
    }
}

/** Facts for [SecondChannelPolicy], supplied by the platform that owns the virtual display. */
internal interface SecondChannelProbe {
    fun enabled(): Boolean = SecondChannelPolicy.ENABLED
    fun binderAlive(): Boolean
    fun serverUid(): Int?
    fun displayExists(displayId: Int): Boolean
    fun canObserve(): Boolean
}

internal enum class InputOutcome {
    CONFIRMED, UNAVAILABLE, INVALID_TARGET_OR_REQUEST, REFLECTION_FAILED,
    REJECTED, EXCEPTION, TIMEOUT, HELPER_DIED, UNCONFIRMED, CHANNEL_UNAVAILABLE,
}

internal fun InputOutcome?.blocksFallback(): Boolean =
    this == InputOutcome.INVALID_TARGET_OR_REQUEST || this == InputOutcome.CHANNEL_UNAVAILABLE ||
        this == InputOutcome.HELPER_DIED

internal data class ViewerMotionResult(
    val outcome: InputOutcome,
    val mayHaveDispatched: Boolean = outcome.mayHaveDispatchedMotion(),
)

internal fun InputOutcome.mayHaveDispatchedMotion(): Boolean =
    this == InputOutcome.CONFIRMED || this == InputOutcome.TIMEOUT || this == InputOutcome.EXCEPTION ||
        this == InputOutcome.UNCONFIRMED || this == InputOutcome.REJECTED

/** A gesture needs both acknowledgements. A viewer packet represents just one event. */
internal fun inputOutcome(
    singleEvent: Boolean,
    reflectionOk: Any?,
    down: Any?,
    up: Any?,
    error: Any?,
): InputOutcome = when (error) {
    null -> if (reflectionOk == true && down == true && (if (singleEvent) up == null else up == true)) {
        InputOutcome.CONFIRMED
    } else {
        InputOutcome.UNCONFIRMED
    }
    "reflection_failed" -> InputOutcome.REFLECTION_FAILED
    "invalid_target_or_request" -> InputOutcome.INVALID_TARGET_OR_REQUEST
    "rejected" -> InputOutcome.REJECTED
    "exception" -> InputOutcome.EXCEPTION
    "timeout" -> InputOutcome.TIMEOUT
    else -> InputOutcome.UNCONFIRMED
}
