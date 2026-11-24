package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperInput
import ai.closepaw.taskhelper.TaskHelperWire
import org.junit.Assert.*
import org.junit.Test

class TaskHelperInputTest {
    @Test fun preservesWireCodesAndKeepsShizukuPrimary() {
        assertEquals(TaskHelperWire.ATTACH + 9, TaskHelperWire.DISPLAY_RESULT)
        assertEquals(TaskHelperWire.ATTACH + 10, TaskHelperWire.WRITE_RESULT)
        assertEquals(TaskHelperWire.ATTACH + 11, TaskHelperWire.INJECT_INPUT)
        assertEquals(TaskHelperWire.ATTACH + 12, TaskHelperWire.INPUT_RESULT)
        assertTrue(TaskHelperInputPolicy.useHelper(37, TaskHelperInputPolicy.HELPER_ROUTE_ENABLED))
        assertFalse(TaskHelperInputPolicy.useHelper(32, true))
        assertTrue(TaskHelperInputPolicy.useHelper(33, true))
    }

    @Test fun validatesPrimitiveRequestsBeforeInjection() {
        assertTrue(TaskHelperInput.valid(TaskHelperInput.TAP, 42, 1f, 2f, 0f, 0f, 0, 0, 0, 0))
        for (id in listOf(0, -1, Int.MIN_VALUE)) {
            assertFalse(TaskHelperInput.valid(TaskHelperInput.TAP, id, 1f, 2f, 0f, 0f, 0, 0, 0, 0))
        }
        assertFalse(TaskHelperInput.valid(TaskHelperInput.SWIPE, 42, 1f, 2f, Float.NaN, 0f, 100, 0, 0, 0))
        assertFalse(TaskHelperInput.valid(TaskHelperInput.MOTION, 42, 1f, 2f, 0f, 0f, 0, 1, 20, 19))
        assertFalse(TaskHelperInput.valid(TaskHelperInput.MOTION, 42, 1f, 2f, 0f, 0f, 0, 5, 20, 20))
    }

    @Test fun requiresAnExplicitCompleteAcknowledgement() {
        assertEquals(InputOutcome.CONFIRMED, inputOutcome(false, true, true, true, null))
        assertEquals(InputOutcome.UNCONFIRMED, inputOutcome(false, true, true, false, null))
        assertEquals(InputOutcome.UNCONFIRMED, inputOutcome(false, true, true, null, null))
        assertEquals(InputOutcome.UNCONFIRMED, inputOutcome(false, true, true, true, "unknown"))
        assertEquals(InputOutcome.REFLECTION_FAILED,
                inputOutcome(false, false, null, null, "reflection_failed"))
        assertEquals(InputOutcome.REJECTED, inputOutcome(false, true, true, false, "rejected"))
        assertEquals(InputOutcome.CONFIRMED, inputOutcome(true, true, true, null, null))
        assertEquals(InputOutcome.UNCONFIRMED, inputOutcome(true, true, null, null, null))
    }
}
