package ai.closepaw.taskhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHelperWriteWireTest {
    @Test fun lifecycleAndWriteCodesRetainTheirOriginalOffsets() {
        val lifecycle = listOf(
            TaskHelperWire.CREATE_DISPLAY,
            TaskHelperWire.RELEASE_DISPLAY,
            TaskHelperWire.SET_DISPLAY_SURFACE,
            TaskHelperWire.SET_DISPLAY_IME_POLICY,
            TaskHelperWire.DISPLAY_RESULT,
        )
        val writes = listOf(
            TaskHelperWire.REMOVE_TASK,
            TaskHelperWire.MOVE_ROOT_TASK_TO_DISPLAY,
            TaskHelperWire.START_ACTIVITY_FROM_RECENTS,
            TaskHelperWire.MOVE_TASK_TO_FRONT,
            TaskHelperWire.START_ACTIVITY_AS_USER,
        )
        assertEquals((5..9).map { TaskHelperWire.ATTACH + it }, lifecycle)
        assertEquals(lifecycle, writes)
        assertEquals(TaskHelperWire.ATTACH + 10, TaskHelperWire.WRITE_RESULT)
        for (code in writes) {
            assertTrue(TaskHelperWire.isTaskWriteCommand(code, true, false))
            assertFalse(TaskHelperWire.isTaskWriteCommand(code, false, true))
            assertFalse(TaskHelperWire.isTaskWriteCommand(code, true, true))
            assertFalse(TaskHelperWire.isTaskWriteCommand(code, false, false))
        }
        assertFalse(TaskHelperWire.isTaskWriteCommand(TaskHelperWire.QUERY_ROOT_TASKS, true, false))
    }
}
