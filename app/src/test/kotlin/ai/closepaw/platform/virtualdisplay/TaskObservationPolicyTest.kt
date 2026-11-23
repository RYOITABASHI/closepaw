package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.*
import org.junit.Test

class TaskObservationPolicyTest {
    @Test fun gatesBySdkSwitchAndAttachment() {
        assertFalse(TaskObservationPolicy.useHelperRoute(31, true, true))
        assertFalse(TaskObservationPolicy.useHelperRoute(32, true, true))
        assertTrue(TaskObservationPolicy.useHelperRoute(33, true, true))
        assertFalse(TaskObservationPolicy.useHelperRoute(33, false, true))
        assertFalse(TaskObservationPolicy.useHelperRoute(33, true, false))
    }
}
