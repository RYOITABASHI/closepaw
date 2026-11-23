package ai.closepaw.app

import ai.closepaw.mcp.BrainAdmissionGate

/** Forwards per-start tokens to the shared registry without keeping separate claim state. */
internal object BrainSessionAdmission {
    val gate = BrainAdmissionGate()

    fun tryClaimInternal(token: Any): Boolean = gate.tryClaimInternal(token)

    fun releaseInternal(token: Any) = gate.releaseInternal(token)
}
