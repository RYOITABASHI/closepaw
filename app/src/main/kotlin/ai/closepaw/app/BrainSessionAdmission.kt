package ai.closepaw.app

import ai.closepaw.mcp.BrainAdmissionGate

/** Process-wide reservation shared by brain and internal session owners. */
internal object BrainSessionAdmission {
    val gate = BrainAdmissionGate()
    private val internalOwner = Any()
    private val internalTokens = mutableSetOf<Any>()

    // Stage A admits one internal owner. Keep independent lifetimes under that owner
    // so baseline internal replacements can overlap without reopening brain admission.
    @Synchronized
    fun tryClaimInternal(token: Any): Boolean {
        if (!gate.tryClaimInternal(internalOwner)) return false
        internalTokens.add(token)
        return true
    }

    @Synchronized
    fun releaseInternal(token: Any) {
        if (internalTokens.remove(token) && internalTokens.isEmpty()) {
            gate.releaseInternal(internalOwner)
        }
    }
}
