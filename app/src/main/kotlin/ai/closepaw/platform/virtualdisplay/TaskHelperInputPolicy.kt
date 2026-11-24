package ai.closepaw.platform.virtualdisplay

internal object TaskHelperInputPolicy {
    // Switch in a separate commit after the device B1/B2 gate.
    const val HELPER_ROUTE_ENABLED = true

    fun useHelper(sdkInt: Int, routeEnabled: Boolean = HELPER_ROUTE_ENABLED): Boolean =
        routeEnabled && sdkInt >= 33
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
