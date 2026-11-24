package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskWriteResultTest {

    @Test fun queuedWriteFallsBackAndCannotStartAfterAbort() {
        val dispatch = TaskWriteDispatch(System.nanoTime() + 60_000_000_000L)
        assertEquals(true, dispatch.abort().resolve(false) { true })
        assertEquals(false, dispatch.tryStart())
    }

    @Test fun expiredWriteCannotStart() {
        val dispatch = TaskWriteDispatch(System.nanoTime())
        assertEquals(false, dispatch.tryStart())
        assertEquals(TaskWriteResult.FAILED, dispatch.abort())
    }

    @Test fun startedWriteWithNoReplyStaysUnknown() {
        val dispatch = TaskWriteDispatch(System.nanoTime() + 60_000_000_000L)
        assertEquals(true, dispatch.tryStart())
        assertNull(dispatch.abort().resolve(false) { error("Unexpected replay") })
    }

    @Test fun typedReplyDistinguishesAppliedAbsentAndUnconfirmed() {
        assertEquals(TaskWriteResult.APPLIED, decodeTaskWriteResult(true))
        assertEquals(TaskWriteResult.NOT_APPLIED, decodeTaskWriteResult(false))
        assertEquals(TaskWriteResult.UNKNOWN, decodeTaskWriteResult(null))
        assertEquals(TaskWriteResult.UNKNOWN, decodeTaskWriteResult("true"))
    }

    @Test fun appliedAndAbsentRemoveDoNotRetry() {
        assertEquals(true, TaskWriteResult.APPLIED.resolve(false) { error("Unexpected retry") })
        assertEquals(false, TaskWriteResult.NOT_APPLIED.resolve(false) { error("Unexpected retry") })
    }

    @Test fun failureReturnsTheFallbackResultForThisCall() {
        assertEquals(false, TaskWriteResult.FAILED.resolve(false) { false })
        assertEquals(true, TaskWriteResult.FAILED.resolve(false) { true })
        assertNull(TaskWriteResult.FAILED.resolve(false) { null })
        assertEquals(true, TaskWriteResult.NOT_APPLIED.resolve(true) { true })
    }

    @Test fun ambiguousWriteWaitsForReconciliation() {
        assertNull(TaskWriteResult.UNKNOWN.resolve(true) { error("Unexpected replay") })
    }
}
