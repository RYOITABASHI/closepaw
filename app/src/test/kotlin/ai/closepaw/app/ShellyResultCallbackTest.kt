package ai.closepaw.app

import ai.closepaw.app.ShellyRunState.Finished.Kind
import ai.closepaw.protocol.SessionCompleted
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

class ShellyResultCallbackTest {

    private val sid = SessionId("s1")
    private val reqId = "1760500000000-0123abcd"
    private val nonce = "0123456789abcdef0123456789abcdef"

    // ── Correlation parsing on the goal broadcast ──

    @Test
    fun `well-formed req id and nonce become a correlation`() {
        val req = ShellyBackgroundRun.parseRequest(
            ShellyBackgroundRun.ACTION_RUN_GOAL, "set an alarm", true, false, reqId, nonce,
        )!!
        assertThat(req.correlation).isEqualTo(ShellyBackgroundRun.ResultCorrelation(reqId, nonce))
    }

    @Test
    fun `missing or malformed correlation is dropped without failing the request`() {
        fun corr(r: String?, n: String?) =
            ShellyBackgroundRun.parseRequest(ShellyBackgroundRun.ACTION_RUN_GOAL, "g", true, false, r, n)!!.correlation
        assertThat(corr(null, null)).isNull()
        assertThat(corr(reqId, null)).isNull()
        assertThat(corr(null, nonce)).isNull()
        assertThat(corr("../etc", nonce)).isNull()
        assertThat(corr(reqId, "ZZ")).isNull()
        assertThat(corr(reqId, nonce.uppercase())).isNull()
    }

    @Test
    fun `an unverified caller never gets a request, correlation or not`() {
        assertThat(
            ShellyBackgroundRun.parseRequest(ShellyBackgroundRun.ACTION_RUN_GOAL, "g", false, false, reqId, nonce),
        ).isNull()
    }

    @Test
    fun `result_callback capability only for a started run with a correlation`() {
        val corr = ShellyBackgroundRun.ResultCorrelation(reqId, nonce)
        assertThat(ShellyBackgroundRun.reportsResult(ShellyBackgroundRun.RESULT_STARTED_VIRTUAL_DISPLAY, corr)).isTrue()
        assertThat(ShellyBackgroundRun.reportsResult(ShellyBackgroundRun.RESULT_STARTED_MAIN_DISPLAY, corr)).isTrue()
        assertThat(ShellyBackgroundRun.reportsResult(ShellyBackgroundRun.RESULT_STARTED_VIRTUAL_DISPLAY, null)).isFalse()
        assertThat(ShellyBackgroundRun.reportsResult(ShellyBackgroundRun.RESULT_REJECTED, corr)).isFalse()
        assertThat(ShellyBackgroundRun.reportsResult(ShellyBackgroundRun.Block.BUSY.resultCode, corr)).isFalse()
        assertThat(ShellyBackgroundRun.RESULT_EXTRA_RESULT_CALLBACK).isEqualTo("result_callback")
    }

    // ── Payload ──

    private fun tracker(withCorrelation: Boolean = true) = ShellyRunTracker(
        "s1",
        onMainDisplay = false,
        ignoredPackages = emptySet(),
        correlation = if (withCorrelation) ShellyBackgroundRun.ResultCorrelation(reqId, nonce) else null,
    )

    @Test
    fun `completed task reports the agent's final answer`() {
        val t = tracker()
        t.onEvent(TaskCompleted(sid, 0L, "task", "Alarm set for 7:00", TaskOutcome.GOAL_ACHIEVED))
        val p = ShellyResultCallback.payloadFor(t, 42L)!!
        assertThat(p.reqId).isEqualTo(reqId)
        assertThat(p.nonce).isEqualTo(nonce)
        assertThat(p.status).isEqualTo(ShellyResultCallback.STATUS_COMPLETED)
        assertThat(p.summary).isEqualTo("Alarm set for 7:00")
        assertThat(p.finishedAtMs).isEqualTo(42L)
        assertThat(p.sessionId).isEqualTo("s1")
    }

    @Test
    fun `stopped and failed runs map to cancelled and failed`() {
        val stopped = tracker().also { it.onEvent(TaskCompleted(sid, 0L, "t", null, TaskOutcome.USER_STOPPED)) }
        assertThat(ShellyResultCallback.payloadFor(stopped, 0L)!!.status).isEqualTo(ShellyResultCallback.STATUS_CANCELLED)
        val impossible = tracker().also { it.onEvent(TaskCompleted(sid, 0L, "t", "no app", TaskOutcome.TASK_IMPOSSIBLE)) }
        assertThat(ShellyResultCallback.payloadFor(impossible, 0L)!!.status).isEqualTo(ShellyResultCallback.STATUS_FAILED)
        val error = tracker().also { it.onEvent(SessionError(sid, 0L, "boom")) }
        assertThat(ShellyResultCallback.payloadFor(error, 0L)!!.summary).isEqualTo("boom")
        val ended = tracker().also { it.onEvent(SessionCompleted(sid, 0L, SessionEndReason.values().first())) }
        assertThat(ShellyResultCallback.payloadFor(ended, 0L)!!.status).isEqualTo(ShellyResultCallback.STATUS_CANCELLED)
    }

    @Test
    fun `no payload while running or without a correlation`() {
        assertThat(ShellyResultCallback.payloadFor(tracker(), 0L)).isNull()
        val noCorr = tracker(withCorrelation = false)
        noCorr.onEvent(TaskCompleted(sid, 0L, "t", "ok", TaskOutcome.GOAL_ACHIEVED))
        assertThat(ShellyResultCallback.payloadFor(noCorr, 0L)).isNull()
    }

    @Test
    fun `start failures report failed only with a correlation`() {
        assertThat(ShellyResultCallback.failurePayload(null, "x", 0L)).isNull()
        val p = ShellyResultCallback.failurePayload(ShellyBackgroundRun.ResultCorrelation(reqId, nonce), "busy", 0L)!!
        assertThat(p.status).isEqualTo(ShellyResultCallback.STATUS_FAILED)
        assertThat(p.summary).isEqualTo("busy")
    }

    // ── Report-once guard per accepted start (startup cancellation / destroy) ──

    private fun pending() = ShellyPendingResult(ShellyBackgroundRun.ResultCorrelation(reqId, nonce))

    private fun trackerFor(guard: ShellyPendingResult) = ShellyRunTracker(
        "s1",
        onMainDisplay = false,
        ignoredPackages = emptySet(),
        correlation = guard.correlation,
        result = guard,
    )

    @Test
    fun `cancelled payload carries STATUS_CANCELLED and echoes the correlation`() {
        assertThat(ShellyResultCallback.cancelledPayload(null, 0L)).isNull()
        val p = ShellyResultCallback.cancelledPayload(ShellyBackgroundRun.ResultCorrelation(reqId, nonce), 7L)!!
        assertThat(p.status).isEqualTo(ShellyResultCallback.STATUS_CANCELLED)
        assertThat(p.reqId).isEqualTo(reqId)
        assertThat(p.nonce).isEqualTo(nonce)
        assertThat(p.summary).isEqualTo(ShellyResultCallback.CANCELLED_DURING_START_MESSAGE)
        assertThat(p.finishedAtMs).isEqualTo(7L)
        assertThat(p.sessionId).isNull()
    }

    @Test
    fun `startup cancellation claim wins and later destroy or tracker claims are no-ops`() {
        val guard = pending()
        val first = guard.claimCancelled(1L)!!
        assertThat(first.status).isEqualTo(ShellyResultCallback.STATUS_CANCELLED)
        assertThat(first.reqId).isEqualTo(reqId)
        assertThat(first.nonce).isEqualTo(nonce)
        // onDestroy's own cancellation claim, a start failure, and a tracker sharing the guard.
        assertThat(guard.claimCancelled(2L)).isNull()
        assertThat(guard.claimFailed("boom", 2L)).isNull()
        val t = trackerFor(guard)
        t.finish(ShellyRunState.Finished.Kind.STOPPED)
        assertThat(t.resultReported).isTrue()
        assertThat(t.claimResult(3L)).isNull()
    }

    @Test
    fun `destroy claim wins and the coroutine's cancellation claim is a no-op`() {
        val guard = pending()
        // onDestroy during startup (no tracker yet) reports once ...
        assertThat(guard.claimCancelled(1L)!!.status).isEqualTo(ShellyResultCallback.STATUS_CANCELLED)
        // ... then serviceScope.cancel() drives the startup coroutine into its catch.
        assertThat(guard.claimCancelled(2L)).isNull()
        assertThat(guard.isClaimed).isTrue()
    }

    @Test
    fun `finished tracker claims once and blocks every later report of the start`() {
        val guard = pending()
        val t = trackerFor(guard)
        assertThat(t.claimResult(0L)).isNull() // still running: nothing claimed
        assertThat(guard.isClaimed).isFalse()
        t.onEvent(TaskCompleted(sid, 0L, "task", "done", TaskOutcome.GOAL_ACHIEVED))
        val p = t.claimResult(5L)!!
        assertThat(p.status).isEqualTo(ShellyResultCallback.STATUS_COMPLETED)
        assertThat(p.summary).isEqualTo("done")
        assertThat(t.resultReported).isTrue()
        assertThat(t.claimResult(6L)).isNull()
        assertThat(guard.claimCancelled(6L)).isNull()
        assertThat(guard.claimFailed("late", 6L)).isNull()
    }

    @Test
    fun `startup failure claims the guard once`() {
        val guard = pending()
        val p = guard.claimFailed("ClosePaw failed to start the task", 1L)!!
        assertThat(p.status).isEqualTo(ShellyResultCallback.STATUS_FAILED)
        assertThat(p.reqId).isEqualTo(reqId)
        assertThat(guard.claimFailed("again", 2L)).isNull()
        assertThat(guard.claimCancelled(2L)).isNull()
    }

    @Test
    fun `a start without correlation consumes the claim but sends nothing`() {
        val guard = ShellyPendingResult(null)
        assertThat(guard.claimCancelled(1L)).isNull()
        assertThat(guard.isClaimed).isTrue()
    }

    @Test
    fun `a standalone tracker gets its own guard`() {
        val t = tracker()
        t.onEvent(TaskCompleted(sid, 0L, "t", null, TaskOutcome.USER_STOPPED))
        assertThat(t.claimResult(0L)!!.status).isEqualTo(ShellyResultCallback.STATUS_CANCELLED)
        assertThat(t.claimResult(0L)).isNull()
    }

    @Test
    fun `summary is capped by code points without splitting surrogate pairs`() {
        val emoji = "😀"
        val long = emoji.repeat(ShellyResultCallback.MAX_SUMMARY_CODE_POINTS + 5)
        val out = ShellyResultCallback.truncateSummary(long)
        assertThat(out.codePointCount(0, out.length)).isEqualTo(ShellyResultCallback.MAX_SUMMARY_CODE_POINTS)
        assertThat(out.endsWith("…")).isTrue()
        assertThat(ShellyResultCallback.truncateSummary("  short  ")).isEqualTo("short")
        assertThat(ShellyResultCallback.truncateSummary(null)).isEmpty()
    }
    @Test
    fun `brain payload echoes the correlation and truncates all terminal summaries`() {
        val correlation = ShellyBackgroundRun.ResultCorrelation(reqId, nonce)
        val boundary = ShellyResultCallback.MAX_SUMMARY_CODE_POINTS
        val prefix = "😀".repeat(boundary - 1)
        val message = " ${prefix}abc "
        for ((kind, status) in mapOf(Kind.DONE to "completed", Kind.FAILED to "failed", Kind.STOPPED to "cancelled")) {
            assertThat(ShellyResultCallback.brainPayload(correlation, kind, message, 42L, "s1"))
                .isEqualTo(ShellyResultCallback.Payload(reqId, nonce, status, prefix + "…", 42L, "s1"))
        }
        assertThat(ShellyResultCallback.brainPayload(null, Kind.DONE, message, 42L, "s1")).isNull()
    }

    @Test
    fun `racing brain terminals deliver only the winning kind`() {
        val pending = ShellyPendingResult(ShellyBackgroundRun.ResultCorrelation(reqId, nonce))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val attempts = listOf(Kind.DONE, Kind.STOPPED).map { kind ->
                executor.submit<ShellyResultCallback.Payload?> {
                    barrier.await(5, TimeUnit.SECONDS)
                    pending.claimBrainTerminal(kind, null, 42L, "s1")
                }
            }
            val (done, stopped) = attempts.map { it.get(5, TimeUnit.SECONDS) }
            assertThat(listOfNotNull(done, stopped)).hasSize(1)
            assertThat(done?.status ?: stopped?.status).isEqualTo(if (done != null) "completed" else "cancelled")
            assertThat(pending.claimBrainTerminal(Kind.FAILED, null, 43L, "s1")).isNull()
            assertThat(pending.claimFailed("late failure", 43L)).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `brain refusal consumes the terminal claim`() {
        val pending = ShellyPendingResult(ShellyBackgroundRun.ResultCorrelation(reqId, nonce))
        assertThat(pending.claimFailed("refused", 42L)?.status).isEqualTo("failed")
        assertThat(pending.claimBrainTerminal(Kind.DONE, null, 43L, "s1")).isNull()
    }

}
