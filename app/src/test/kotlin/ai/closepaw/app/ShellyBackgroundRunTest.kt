package ai.closepaw.app

import ai.closepaw.protocol.ApprovalDetails
import ai.closepaw.protocol.ApprovalRequired
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.ScreenCaptured
import ai.closepaw.protocol.ScreenStatePhase
import ai.closepaw.protocol.SessionCompleted
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SessionResumed
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class ShellyBackgroundRunTest {

    private val sid = SessionId("s1")

    // ── Trust gate on the exported receiver ──

    @Test
    fun `untrusted caller is ignored even with a valid action and goal`() {
        val req = ShellyBackgroundRun.parseRequest(
            ShellyBackgroundRun.ACTION_RUN_GOAL, "open chrome", callerVerified = false, allowMainDisplay = false,
        )
        assertThat(req).isNull()
    }

    @Test
    fun `wrong action is ignored even for a verified caller`() {
        val req = ShellyBackgroundRun.parseRequest(
            "android.intent.action.VIEW", "open chrome", callerVerified = true, allowMainDisplay = false,
        )
        assertThat(req).isNull()
    }

    @Test
    fun `missing or blank goal is ignored`() {
        assertThat(ShellyBackgroundRun.parseRequest(ShellyBackgroundRun.ACTION_RUN_GOAL, null, true, false)).isNull()
        assertThat(ShellyBackgroundRun.parseRequest(ShellyBackgroundRun.ACTION_RUN_GOAL, "   ", true, false)).isNull()
    }

    @Test
    fun `verified request is accepted, trimmed and length-capped`() {
        val long = " " + "x".repeat(ShellyBackgroundRun.MAX_GOAL_CHARS + 10) + " "
        val req = ShellyBackgroundRun.parseRequest(ShellyBackgroundRun.ACTION_RUN_GOAL, long, true, true)!!
        assertThat(req.goal.length).isEqualTo(ShellyBackgroundRun.MAX_GOAL_CHARS)
        assertThat(req.allowMainDisplay).isTrue()
    }

    // ── Sender identity (API 34+) ──

    private val pins = ShellyTrustedCaller.PINNED_SHELLY_CERT_SHA256
    private val pinBytes = ShellyTrustedCaller.hexToBytes(pins.first())!!
    private val shellyCert = { pkg: String, cert: ByteArray ->
        pkg == ShellyTrustedCaller.SHELLY_PACKAGE && cert.contentEquals(pinBytes)
    }

    @Test
    fun `api 34+ rejects a leaked token sent by another app`() {
        assertThat(ShellyTrustedCaller.isBroadcastSenderTrusted(34, "com.evil.listener", pins) { _, _ -> true })
            .isFalse()
    }

    @Test
    fun `api 34+ rejects a broadcast without shared sender identity`() {
        assertThat(ShellyTrustedCaller.isBroadcastSenderTrusted(35, null, pins, shellyCert)).isFalse()
    }

    @Test
    fun `api 34+ accepts Shelly with the pinned cert only`() {
        assertThat(ShellyTrustedCaller.isBroadcastSenderTrusted(34, "dev.shelly.terminal", pins, shellyCert)).isTrue()
        assertThat(ShellyTrustedCaller.isBroadcastSenderTrusted(34, "dev.shelly.terminal", pins) { _, _ -> false })
            .isFalse()
    }

    @Test
    fun `below api 34 the token is the only signal`() {
        assertThat(ShellyTrustedCaller.isBroadcastSenderTrusted(33, null, pins) { _, _ -> false }).isTrue()
    }

    // ── Preconditions ──

    private fun precheck(
        a11y: Boolean = true,
        onboarded: Boolean = true,
        overlay: Boolean = true,
        missingCreds: Boolean = false,
        busy: Boolean = false,
        vd: Boolean = true,
        allowMain: Boolean = false,
    ) = ShellyBackgroundRun.precheck(a11y, onboarded, overlay, missingCreds, busy, vd, allowMain)

    @Test
    fun `starts on the virtual display when everything is ready`() {
        assertThat(precheck()).isEqualTo(ShellyBackgroundRun.Decision.Start(PlatformMode.VIRTUAL_DISPLAY))
    }

    @Test
    fun `refuses instead of taking over the screen when the virtual display is unavailable`() {
        assertThat(precheck(vd = false)).isEqualTo(
            ShellyBackgroundRun.Decision.Blocked(ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE)
        )
    }

    @Test
    fun `main display only with explicit opt-in`() {
        assertThat(precheck(vd = false, allowMain = true))
            .isEqualTo(ShellyBackgroundRun.Decision.Start(PlatformMode.ACCESSIBILITY))
    }

    @Test
    fun `busy session is never clobbered`() {
        assertThat(precheck(busy = true))
            .isEqualTo(ShellyBackgroundRun.Decision.Blocked(ShellyBackgroundRun.Block.BUSY))
    }

    @Test
    fun `setup problems block before anything starts`() {
        assertThat(precheck(a11y = false)).isEqualTo(
            ShellyBackgroundRun.Decision.Blocked(ShellyBackgroundRun.Block.ACCESSIBILITY_OFF))
        assertThat(precheck(onboarded = false)).isEqualTo(
            ShellyBackgroundRun.Decision.Blocked(ShellyBackgroundRun.Block.ONBOARDING_REQUIRED))
        assertThat(precheck(overlay = false)).isEqualTo(
            ShellyBackgroundRun.Decision.Blocked(ShellyBackgroundRun.Block.OVERLAY_PERMISSION_MISSING))
        assertThat(precheck(missingCreds = true)).isEqualTo(
            ShellyBackgroundRun.Decision.Blocked(ShellyBackgroundRun.Block.CREDENTIALS_MISSING))
    }

    // ── Run state ──

    private fun tracker(onMain: Boolean = false) =
        ShellyRunTracker("s1", onMainDisplay = onMain, ignoredPackages = setOf("ai.closepaw", "com.android.systemui"))

    private fun screen(pkg: String?) = ScreenCaptured(
        sid, 0L, 0, pkg, null, "t", 1, ScreenStatePhase.PRE_TURN, null, null, null, null,
    )

    private fun approval(callId: String, pkg: String) = ApprovalRequired(
        sid, 0L, "desc",
        ApprovalDetails(callId = callId, toolName = "tap", args = JSONObject(), packageName = pkg),
    )

    @Test
    fun `tracks operated apps on the virtual display`() {
        val t = tracker()
        assertThat(t.onEvent(screen("com.android.chrome"))).isTrue()
        t.onEvent(screen("ai.closepaw"))
        assertThat(t.state).isEqualTo(ShellyRunState.Working(false, listOf("com.android.chrome")))
    }

    @Test
    fun `opening the operated app on the main display pauses the run`() {
        val t = tracker()
        t.onEvent(screen("com.android.chrome"))
        assertThat(t.onMainDisplayWindow("com.openai.chatgpt")).isNull()
        assertThat(t.onMainDisplayWindow("com.android.chrome")).isEqualTo("com.android.chrome")
        assertThat(t.state).isEqualTo(ShellyRunState.Paused("com.android.chrome"))
        assertThat(t.onEvent(SessionResumed(sid, 0L))).isTrue()
        assertThat(t.state).isInstanceOf(ShellyRunState.Working::class.java)
    }

    @Test
    fun `main display runs never report app conflicts`() {
        val t = tracker(onMain = true)
        t.onEvent(screen("com.android.chrome"))
        assertThat(t.onMainDisplayWindow("com.android.chrome")).isNull()
        assertThat(t.state).isEqualTo(ShellyRunState.Working(true, emptyList()))
    }

    @Test
    fun `approval waits until resolved for the same call id`() {
        val t = tracker()
        t.onEvent(approval("c1", "com.example.app"))
        assertThat(t.state).isInstanceOf(ShellyRunState.WaitingForApproval::class.java)
        assertThat(t.onApprovalResolved("other")).isFalse()
        assertThat(t.onApprovalResolved("c1")).isTrue()
        assertThat(t.state).isEqualTo(ShellyRunState.Working(false, listOf("com.example.app")))
    }

    @Test
    fun `completion maps outcomes and becomes terminal`() {
        val t = tracker()
        t.onEvent(TaskCompleted(sid, 0L, "task", "ok", TaskOutcome.GOAL_ACHIEVED))
        assertThat(t.state).isEqualTo(ShellyRunState.Finished(ShellyRunState.Finished.Kind.DONE, "ok"))
        assertThat(t.onEvent(SessionCompleted(sid, 0L, SessionEndReason.IDLE_TIMEOUT))).isFalse()
        assertThat(t.isFinished).isTrue()

        val stopped = tracker()
        stopped.onEvent(SessionCompleted(sid, 0L, SessionEndReason.USER_STOPPED))
        assertThat(stopped.state).isEqualTo(ShellyRunState.Finished(ShellyRunState.Finished.Kind.STOPPED, null))
    }

    @Test
    fun `text tells the user whether they may use the phone`() {
        val en = ShellyRunText(japanese = false)
        val ja = ShellyRunText(japanese = true)
        val vd = ShellyRunState.Working(false, listOf("com.android.chrome"))
        val label = { _: String -> "Chrome" }
        assertThat(en.render(vd, label).title).contains("you can keep using your phone")
        assertThat(en.render(vd, label).body).contains("Don't open \"Chrome\"")
        assertThat(ja.render(vd, label).body).contains("「Chrome」は開かないでください")
        assertThat(en.render(ShellyRunState.Working(true, emptyList()), label).title).contains("please don't touch")
    }
}
