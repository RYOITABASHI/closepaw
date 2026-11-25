package ai.closepaw.app

import ai.closepaw.protocol.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryEditGateTest {
    @Test
    fun tracksActiveSessionAndUnlocksAfterShutdown() = runTest {
        val state = MutableStateFlow<SessionState?>(null)
        val gate = MemoryEditGate(state, backgroundScope)
        assertTrue(gate.memoryEditLocked.value)
        runCurrent()
        assertFalse(gate.memoryEditLocked.value)

        for (active in listOf(SessionState.Created, SessionState.Running, SessionState.Idle,
                SessionState.TakeoverPending, SessionState.Paused)) {
            state.value = active
            assertTrue(gate.isLockedNow())
            runCurrent()
            assertTrue(gate.memoryEditLocked.value)
        }

        state.value = SessionState.Shutdown
        assertFalse(gate.isLockedNow())
        runCurrent()
        assertFalse(gate.memoryEditLocked.value)
    }

    @Test
    fun synchronousCheckSeesChangesBeforeUiCollector() = runTest {
        val state = MutableStateFlow<SessionState?>(null)
        val gate = MemoryEditGate(state, backgroundScope)
        runCurrent()
        assertFalse(gate.memoryEditLocked.value)
        state.value = SessionState.Created
        assertFalse(gate.memoryEditLocked.value)
        assertTrue(gate.isLockedNow())
    }
}
