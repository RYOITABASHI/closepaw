package ai.closepaw.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenancePolicyTest {
    @Test fun recoveryPlansFollowSettingAndBindState() {
        assertEquals(AccessibilityRecoveryPlan.APPEND, AccessibilityRecoveryPlan.decide(false, false))
        assertEquals(AccessibilityRecoveryPlan.APPEND, AccessibilityRecoveryPlan.decide(false, true))
        assertEquals(AccessibilityRecoveryPlan.NO_OP, AccessibilityRecoveryPlan.decide(true, true))
        assertEquals(AccessibilityRecoveryPlan.TOGGLE_REBIND, AccessibilityRecoveryPlan.decide(true, false))
    }

    @Test fun scanClaimsRespectTheWindowAndInFlightWork() {
        assertEquals(MaintenanceScanGate.CLAIM, MaintenanceScanGate.decide(0, null, false))
        assertEquals(MaintenanceScanGate.SKIP, MaintenanceScanGate.decide(59_999, 0, false))
        assertEquals(MaintenanceScanGate.JOIN, MaintenanceScanGate.decide(60_000, 0, true))
        assertEquals(MaintenanceScanGate.CLAIM, MaintenanceScanGate.decide(60_000, 0, false))
    }
}
