package ai.closepaw.app

internal enum class AccessibilityRecoveryPlan {
    NO_OP, APPEND, TOGGLE_REBIND;

    companion object {
        // The setting may be missing even while an old binding is still alive.
        fun decide(settingContainsAgent: Boolean, bound: Boolean): AccessibilityRecoveryPlan = when {
            !settingContainsAgent -> APPEND
            bound -> NO_OP
            else -> TOGGLE_REBIND
        }
    }
}

internal enum class MaintenanceScanGate {
    CLAIM, SKIP, JOIN;

    companion object {
        private const val SCAN_INTERVAL_MS = 60_000L

        fun decide(nowMs: Long, lastScanMs: Long?, inFlight: Boolean): MaintenanceScanGate = when {
            inFlight -> JOIN
            lastScanMs != null && nowMs - lastScanMs < SCAN_INTERVAL_MS -> SKIP
            else -> CLAIM
        }
    }
}
