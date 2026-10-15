package ai.closepaw.app

import ai.closepaw.protocol.SessionCompleted
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import com.google.common.truth.Truth.assertThat
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
}
