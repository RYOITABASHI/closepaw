package ai.closepaw.app

import ai.closepaw.R
import ai.closepaw.mcp.McpAuth
import ai.closepaw.mcp.McpBackend
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpHttpRequest
import ai.closepaw.mcp.McpHttpServer
import ai.closepaw.mcp.McpMainCall
import ai.closepaw.mcp.McpRunRegistry
import ai.closepaw.mcp.reconcileLateStart
import ai.closepaw.platform.virtualdisplay.DisplayTask
import ai.closepaw.platform.virtualdisplay.MainDisplayHandover
import ai.closepaw.platform.virtualdisplay.MainDisplayReconcileOutcome
import ai.closepaw.platform.virtualdisplay.MainDisplayRefront
import ai.closepaw.platform.virtualdisplay.MainDisplayRestorePlan
import ai.closepaw.platform.virtualdisplay.MainDisplayTaskGuard
import ai.closepaw.platform.virtualdisplay.MainDisplayTaskRestorer
import ai.closepaw.platform.virtualdisplay.RunEndRestoreSequencer
import ai.closepaw.platform.virtualdisplay.ShizukuActivityTaskTransport
import ai.closepaw.platform.virtualdisplay.ShizukuClient
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        // Fake resolver: "<resource id>[args]" so the test checks which string is chosen.
        val text = ShellyRunText { id, args -> "$id" + args.joinToString(prefix = "[", postfix = "]") }
        val label = { _: String -> "Chrome" }
        val vd = text.render(ShellyRunState.Working(false, listOf("com.android.chrome")), label)
        assertThat(vd.title).isEqualTo("${R.string.shelly_background_title}[]")
        assertThat(vd.body).startsWith("${R.string.shelly_background_body_apps}[")
        assertThat(vd.body).contains("${R.string.shelly_quoted_app}[Chrome]")
        val main = text.render(ShellyRunState.Working(true, emptyList()), label)
        assertThat(main.title).isEqualTo("${R.string.shelly_main_display_title}[]")
        val waiting = text.render(ShellyRunState.WaitingForApproval(false, "c", "com.android.chrome", ""), label)
        assertThat(waiting.title).isEqualTo("${R.string.shelly_approval_title}[]")
    }

    // ── Main display preservation (MainDisplayTaskGuard) ──

    private val launcher = "com.sec.android.app.launcher"
    private val home = DisplayTask(1, launcher)
    private val shelly = DisplayTask(10, "dev.shelly.terminal")
    private val chrome = DisplayTask(20, "com.android.chrome")
    private val agentTask = DisplayTask(30, "com.example.notes")

    private fun guard() = MainDisplayTaskGuard(setOf(launcher))

    /** The user is in Shelly (singleTask) on display 0; the agent's open_app moves that task. */
    private fun displaceShelly(g: MainDisplayTaskGuard, mainBefore: List<DisplayTask> = listOf(shelly, home)) {
        g.observeMain(mainBefore)
        g.onLaunched(before = mainBefore, mainAfter = mainBefore - shelly, vdAfter = listOf(shelly))
    }

    @Test
    fun `untouched display 0 gets the displaced foreground task back on top without a refront`() {
        val g = guard()
        displaceShelly(g)
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(home))
        assertThat(plan).isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.None))
    }

    @Test
    fun `an app the user focused on display 0 meanwhile is brought back over the restored task`() {
        val g = guard()
        displaceShelly(g)
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(plan.restoreTaskIds).containsExactly(shelly.taskId)
        assertThat(plan.refront).isEqualTo(MainDisplayRefront.Task(chrome.taskId))
    }

    @Test
    fun `user who went home from the revealed app is returned home`() {
        val g = guard()
        displaceShelly(g, mainBefore = listOf(shelly, chrome, home)) // Chrome was revealed
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome))
        assertThat(plan.restoreTaskIds).containsExactly(shelly.taskId)
        assertThat(plan.refront).isEqualTo(MainDisplayRefront.Home)
    }

    @Test
    fun `returning to the revealed screen after using another app still counts as touched`() {
        val g = guard()
        displaceShelly(g)
        g.onMainDisplayForeground(launcher) // the displacement itself revealing home: not a touch
        g.onMainDisplayForeground("com.android.chrome")
        g.onMainDisplayForeground(launcher) // user deliberately went back home
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome))
        assertThat(plan.restoreTaskIds).containsExactly(shelly.taskId)
        assertThat(plan.refront).isEqualTo(MainDisplayRefront.Home)
    }

    @Test
    fun `a task whose move failed stays protected from the cleanup and is retried`() {
        val g = guard()
        val before = listOf(shelly, chrome, home)
        g.observeMain(before)
        g.onLaunched(before, mainAfter = listOf(chrome, home), vdAfter = listOf(shelly))
        g.observeMain(listOf(chrome, home))
        g.onLaunched(listOf(chrome, home), mainAfter = listOf(home), vdAfter = listOf(chrome, shelly))
        g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home))
        g.completeRestore(moved = listOf(shelly.taskId), refronted = true) // Chrome's move failed
        assertThat(g.protectedTaskIds).contains(chrome.taskId)
        assertThat(g.protectedTaskIds).doesNotContain(shelly.taskId)
        assertThat(g.plan(vdNow = listOf(chrome), mainNow = listOf(shelly, home)).restoreTaskIds)
            .containsExactly(chrome.taskId)
    }

    @Test
    fun `a displaced background task is restored without taking the foreground`() {
        val g = guard()
        val before = listOf(chrome, shelly, home)
        g.observeMain(before)
        // Shelly (in the background) is moved; Chrome stays on top of display 0.
        g.onLaunched(before, mainAfter = listOf(chrome, home), vdAfter = listOf(shelly))
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(plan.restoreTaskIds).containsExactly(shelly.taskId)
        assertThat(plan.refront).isEqualTo(MainDisplayRefront.Task(chrome.taskId))
    }

    @Test
    fun `tasks the agent created on the virtual display or the user already pulled back are left alone`() {
        val g = guard()
        displaceShelly(g)
        // User reopened Shelly on display 0 mid-run (task back on display 0); agent task stays on VD.
        assertThat(g.plan(vdNow = listOf(agentTask), mainNow = listOf(shelly, home)))
            .isEqualTo(MainDisplayRestorePlan.NOTHING)
        assertThat(guard().plan(vdNow = listOf(agentTask), mainNow = listOf(home)))
            .isEqualTo(MainDisplayRestorePlan.NOTHING)
    }

    @Test
    fun `the displaced foreground task is restored last so it ends on top`() {
        val g = guard()
        val before = listOf(shelly, chrome, home)
        g.observeMain(before)
        g.onLaunched(before, mainAfter = listOf(chrome, home), vdAfter = listOf(shelly))
        // A later launch (another singleTask app) moves Chrome as well; display 0 now shows home.
        g.observeMain(listOf(chrome, home))
        g.onLaunched(listOf(chrome, home), mainAfter = listOf(home), vdAfter = listOf(chrome, shelly))
        val plan = g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home))
        assertThat(plan).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId, chrome.taskId), MainDisplayRefront.None)
        )
    }

    @Test
    fun `restore is planned once`() {
        val g = guard()
        displaceShelly(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home))
        g.completeRestore(moved = listOf(shelly.taskId), refronted = true)
        assertThat(g.hasPendingWork).isFalse()
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = listOf(home))).isEqualTo(MainDisplayRestorePlan.NOTHING)
    }

    @Test
    fun `pinned and empty root tasks are never treated as the display 0 foreground`() {
        val g = guard()
        val pip = DisplayTask(40, "com.google.android.youtube", pinned = true)
        val emptyRoot = DisplayTask(41, null)
        val before = listOf(pip, emptyRoot, shelly, home)
        g.observeMain(before)
        g.onLaunched(before, mainAfter = listOf(pip, emptyRoot, home), vdAfter = listOf(shelly))
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(pip, emptyRoot, home))
        assertThat(plan).isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.None))
    }

    @Test
    fun `a new task is forced only when the app's task is on display 0 and not yet on the virtual display`() {
        val pkg = chrome.packageName!!
        assertThat(MainDisplayTaskGuard.shouldLaunchInNewTask(pkg, main = listOf(chrome, home), vd = emptyList())).isTrue()
        assertThat(MainDisplayTaskGuard.shouldLaunchInNewTask(pkg, main = listOf(chrome), vd = listOf(DisplayTask(21, pkg))))
            .isFalse()
        assertThat(MainDisplayTaskGuard.shouldLaunchInNewTask(pkg, main = listOf(home), vd = emptyList())).isFalse()
    }

    // ── Partial restores keep the original intent (independent-gate fix round) ──

    private val mail = DisplayTask(50, "com.example.mail")

    /** Shelly (display-0 foreground) then Chrome (revealed, then displaced too) end up on the VD. */
    private fun displaceShellyThenChrome(g: MainDisplayTaskGuard) {
        val before = listOf(shelly, chrome, home)
        g.observeMain(before)
        g.onLaunched(before, mainAfter = listOf(chrome, home), vdAfter = listOf(shelly))
        g.observeMain(listOf(chrome, home))
        g.onLaunched(listOf(chrome, home), mainAfter = listOf(home), vdAfter = listOf(chrome, shelly))
    }

    @Test
    fun `a retry after a partial restore replays the remaining move and the original refront`() {
        val g = guard()
        displaceShellyThenChrome(g)
        // The user focused Mail on display 0 meanwhile.
        val first = g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(mail, home))
        assertThat(first).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId, chrome.taskId), MainDisplayRefront.Task(mail.taskId))
        )
        g.completeRestore(moved = listOf(shelly.taskId), refronted = true) // Chrome's move failed
        // Teardown: display 0 now shows the restored Shelly on top, which is our own doing.
        val retry = g.plan(vdNow = listOf(chrome), mainNow = listOf(shelly, mail, home))
        assertThat(retry).isEqualTo(
            MainDisplayRestorePlan(listOf(chrome.taskId), MainDisplayRefront.Task(mail.taskId))
        )
        g.completeRestore(moved = listOf(chrome.taskId), refronted = true)
        assertThat(g.hasPendingWork).isFalse()
    }

    @Test
    fun `a restored task on top after a partial restore is not taken for user intent`() {
        val g = guard()
        displaceShellyThenChrome(g)
        // Display 0 untouched: Chrome (the last displaced foreground) must end on top.
        val first = g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home))
        assertThat(first).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId, chrome.taskId), MainDisplayRefront.None)
        )
        g.completeRestore(moved = listOf(shelly.taskId), refronted = true) // Chrome's move failed
        val retry = g.plan(vdNow = listOf(chrome), mainNow = listOf(shelly, home))
        assertThat(retry).isEqualTo(MainDisplayRestorePlan(listOf(chrome.taskId), MainDisplayRefront.None))
    }

    @Test
    fun `when the last-planned task was already restored the retry refronts it explicitly`() {
        val g = guard()
        displaceShellyThenChrome(g)
        val first = g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home))
        assertThat(first).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId, chrome.taskId), MainDisplayRefront.None)
        )
        g.completeRestore(moved = listOf(chrome.taskId), refronted = true) // Shelly's move failed
        assertThat(g.hasPendingWork).isTrue()
        // Moving Shelly back would cover Chrome, which the original plan put on top.
        val retry = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(retry).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Task(chrome.taskId))
        )
        g.completeRestore(moved = listOf(shelly.taskId), refronted = false) // that refront failed
        // Refront-only retry: the explicit Chrome refront is still owed (not None).
        val again = g.plan(vdNow = emptyList(), mainNow = listOf(shelly, chrome, home))
        assertThat(again).isEqualTo(MainDisplayRestorePlan(emptyList(), MainDisplayRefront.Task(chrome.taskId)))
        g.completeRestore(moved = emptyList(), refronted = true)
        assertThat(g.hasPendingWork).isFalse()
    }

    @Test
    fun `a failed refront stays pending and is planned again until it succeeds`() {
        val g = guard()
        displaceShelly(g)
        val first = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(first).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Task(chrome.taskId))
        )
        g.completeRestore(moved = listOf(shelly.taskId), refronted = false) // refront rejected
        assertThat(g.hasPendingWork).isTrue()
        assertThat(g.protectedTaskIds).doesNotContain(shelly.taskId)
        // Next opportunity (teardown): no move left, the same refront again — Shelly on top now is ours.
        val retry = g.plan(vdNow = emptyList(), mainNow = listOf(shelly, chrome, home))
        assertThat(retry).isEqualTo(MainDisplayRestorePlan(emptyList(), MainDisplayRefront.Task(chrome.taskId)))
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.plan(vdNow = emptyList(), mainNow = listOf(shelly, chrome, home)).refront)
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        g.completeRestore(moved = emptyList(), refronted = true)
        assertThat(g.hasPendingWork).isFalse()
        assertThat(g.plan(vdNow = emptyList(), mainNow = listOf(chrome, home))).isEqualTo(MainDisplayRestorePlan.NOTHING)
    }

    @Test
    fun `a pending task that momentarily reports no package is still retried and stays protected`() {
        val g = guard()
        displaceShelly(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home))
        g.completeRestore(moved = emptyList(), refronted = false) // Shelly's move failed
        val shellyWithoutActivity = DisplayTask(shelly.taskId, null)
        val retry = g.plan(vdNow = listOf(shellyWithoutActivity), mainNow = listOf(home))
        assertThat(retry).isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.None))
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.protectedTaskIds).contains(shelly.taskId)
        assertThat(g.hasPendingWork).isTrue()
    }

    @Test
    fun `a remembered task whose package is momentarily unknown is still planned`() {
        val g = guard()
        displaceShellyThenChrome(g)
        val chromeWithoutActivity = DisplayTask(chrome.taskId, null)
        val plan = g.plan(vdNow = listOf(chromeWithoutActivity, shelly), mainNow = listOf(home))
        assertThat(plan).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId, chrome.taskId), MainDisplayRefront.None)
        )
        g.completeRestore(moved = listOf(shelly.taskId), refronted = true) // Chrome's move failed
        assertThat(g.protectedTaskIds).contains(chrome.taskId)
        assertThat(g.hasPendingWork).isTrue()
    }

    @Test
    fun `foreground events caused by the restore do not mark display 0 as touched`() {
        val g = guard()
        displaceShellyThenChrome(g)
        g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home))
        // Our own move brings Shelly to display 0's foreground; Chrome's move fails.
        g.onMainDisplayForeground(shelly.packageName)
        g.completeRestore(moved = listOf(shelly.taskId), refronted = true)
        // Late events from the restore keep arriving on the accessibility thread.
        g.onMainDisplayForeground(shelly.packageName)
        assertThat(g.userTouchedMainDisplay).isFalse()
        assertThat(g.plan(vdNow = listOf(chrome), mainNow = listOf(shelly, home)).refront)
            .isEqualTo(MainDisplayRefront.None)
    }

    @Test
    fun `user actions before the restore still count as touched`() {
        val g = guard()
        displaceShelly(g)
        g.onMainDisplayForeground("com.android.chrome")
        assertThat(g.userTouchedMainDisplay).isTrue()
    }

    // ── Build 58: retry refront from the current top, post-release refront, fast refront ──

    /** Shelly displaced with Chrome revealed; the user then went Home (touched). */
    private fun displaceShellyUserWentHome(g: MainDisplayTaskGuard) {
        displaceShelly(g, mainBefore = listOf(shelly, chrome, home))
        g.onMainDisplayForeground(launcher)
    }

    @Test
    fun `a teardown retry refronts the app the user opened after a failed restore, not the stale Home`() {
        val g = guard()
        displaceShellyUserWentHome(g)
        // Run end: the user is on Home; Shelly's move fails, so the restore is incomplete.
        val first = g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome))
        assertThat(first).isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Home))
        g.completeRestore(moved = emptyList(), refronted = false)
        // The user opens Chrome; Hot Idle teardown retries the restore.
        val retry = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(retry).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Task(chrome.taskId))
        )
    }

    @Test
    fun `a retry keeps the frozen refront when display 0 shows only tasks the cycle moved back`() {
        val g = guard()
        displaceShellyUserWentHome(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome))
        g.completeRestore(moved = listOf(shelly.taskId), refronted = false) // Home refront failed
        // Filtered top is empty (only our own Shelly, and a package-less root): frozen intent.
        val retry = g.plan(vdNow = emptyList(), mainNow = listOf(shelly, DisplayTask(41, null)))
        assertThat(retry).isEqualTo(MainDisplayRestorePlan(emptyList(), MainDisplayRefront.Home))
    }

    @Test
    fun `a retry while display 0 still shows the frozen top keeps the frozen intent`() {
        val g = guard()
        displaceShellyUserWentHome(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome))
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome)))
            .isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Home))
    }

    @Test
    fun `the refront re-read follows a user switch and skips tasks moved in this pass`() {
        val g = guard()
        displaceShelly(g)
        val plan = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(plan.refront).isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        // Our own Shelly on top, Chrome still the user's: unchanged.
        assertThat(g.refrontNow(listOf(shelly, chrome, home), listOf(shelly.taskId), plan.refront))
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        // The user went Home between the plan's read and the refront.
        assertThat(g.refrontNow(listOf(shelly, home, chrome), listOf(shelly.taskId), plan.refront))
            .isEqualTo(MainDisplayRefront.Home)
        // Nothing but our own task readable: the planned refront.
        assertThat(g.refrontNow(listOf(shelly), listOf(shelly.taskId), plan.refront))
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
    }

    @Test
    fun `no handover refront when nothing is handed over by the release`() {
        val g = guard()
        displaceShellyUserWentHome(g)
        assertThat(g.handoverRefront(mainNow = listOf(chrome, home), handedOver = emptyList()))
            .isEqualTo(MainDisplayRefront.None)
    }

    @Test
    fun `a task handed over by the release is covered by the user's current app`() {
        val g = guard()
        displaceShellyUserWentHome(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome))
        g.completeRestore(moved = emptyList(), refronted = false) // run end: move failed
        g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        g.completeRestore(moved = emptyList(), refronted = false) // teardown: failed again
        assertThat(g.protectedTaskIds).contains(shelly.taskId)
        assertThat(g.handoverRefront(mainNow = listOf(chrome, home), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        assertThat(g.handoverRefront(mainNow = listOf(home, chrome), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.Home)
    }

    @Test
    fun `an untouched display 0 lets the handed-over displaced foreground stay on top`() {
        val g = guard()
        displaceShelly(g) // Home revealed, user never touched display 0
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home))
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.handoverRefront(mainNow = listOf(home), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.None)
    }

    @Test
    fun `a handed-over task must not cover the intended top that was already restored`() {
        val g = guard()
        displaceShellyThenChrome(g)
        g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home)) // None: Chrome ends on top
        g.completeRestore(moved = listOf(chrome.taskId), refronted = true) // Shelly's move failed
        assertThat(g.handoverRefront(mainNow = listOf(chrome, home), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
    }

    @Test
    fun `without any plan the handover refront follows the first-plan rule`() {
        val g = guard()
        displaceShellyUserWentHome(g)
        assertThat(g.handoverRefront(mainNow = listOf(home, chrome), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.Home)
        val untouched = guard()
        displaceShelly(untouched)
        assertThat(untouched.handoverRefront(mainNow = listOf(home), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.None)
    }

    // ── Gate fix round: a user choice observed after the freeze is the new reference ──

    @Test
    fun `after Chrome was observed as the user's choice a return to Home is followed, not the frozen None`() {
        val g = guard()
        displaceShelly(g) // Home revealed, display 0 untouched
        val first = g.plan(vdNow = listOf(shelly), mainNow = listOf(home))
        assertThat(first).isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.None))
        g.completeRestore(moved = emptyList(), refronted = false) // Shelly's move fails
        // The user opens Chrome; the teardown retry follows it, and its move fails again.
        val teardown = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(teardown).isEqualTo(
            MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Task(chrome.taskId))
        )
        g.completeRestore(moved = emptyList(), refronted = false)
        // Before the release display 0 still shows Chrome: Chrome stays the decision.
        assertThat(g.handoverRefront(mainNow = listOf(chrome, home), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        // During the handover wait the user returns Home: Home, never the frozen None.
        assertThat(g.handoverRefront(mainNow = listOf(home, chrome), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.Home)
    }

    @Test
    fun `a retry after a newer user choice follows a return to the frozen top`() {
        val g = guard()
        displaceShelly(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home)) // frozen None, basis Home
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home)).refront)
            .isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        g.completeRestore(moved = emptyList(), refronted = false)
        // Back on Home: Home is the user's current top.
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome)))
            .isEqualTo(MainDisplayRestorePlan(listOf(shelly.taskId), MainDisplayRefront.Home))
        g.completeRestore(moved = emptyList(), refronted = false)
        // Still Home (unchanged from the new reference): Home again; nothing readable: Home too.
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = listOf(home, chrome)).refront)
            .isEqualTo(MainDisplayRefront.Home)
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = emptyList()).refront)
            .isEqualTo(MainDisplayRefront.Home)
    }

    @Test
    fun `a switch seen by the pre-refront re-read is the reference for later decisions`() {
        val g = guard()
        displaceShellyThenChrome(g)
        g.plan(vdNow = listOf(chrome, shelly), mainNow = listOf(home)) // None: Chrome ends on top
        g.completeRestore(moved = listOf(chrome.taskId), refronted = true) // Shelly's move fails
        val retry = g.plan(vdNow = listOf(shelly), mainNow = listOf(chrome, home))
        assertThat(retry.refront).isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        // Shelly is moved; right before the refront the user is in Mail: followed.
        assertThat(g.refrontNow(listOf(shelly, mail, chrome, home), listOf(shelly.taskId), retry.refront))
            .isEqualTo(MainDisplayRefront.Task(mail.taskId))
        g.completeRestore(moved = listOf(shelly.taskId), refronted = false) // that refront fails
        // The user then goes Home: Home, not the frozen Chrome intent.
        assertThat(g.plan(vdNow = emptyList(), mainNow = listOf(home, shelly, mail, chrome)).refront)
            .isEqualTo(MainDisplayRefront.Home)
    }

    @Test
    fun `while display 0's top never differs the frozen None is kept by every retry and the handover`() {
        val g = guard()
        displaceShelly(g)
        g.plan(vdNow = listOf(shelly), mainNow = listOf(home))
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = listOf(home)).refront).isEqualTo(MainDisplayRefront.None)
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.plan(vdNow = listOf(shelly), mainNow = emptyList()).refront).isEqualTo(MainDisplayRefront.None)
        g.completeRestore(moved = emptyList(), refronted = false)
        assertThat(g.handoverRefront(mainNow = listOf(home), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.None)
        assertThat(g.handoverRefront(mainNow = emptyList(), handedOver = listOf(shelly.taskId)))
            .isEqualTo(MainDisplayRefront.None)
    }

    // ── Gate fix round: binder start results outside 0..99 are failures ──

    /** Shape-compatible IActivityTaskManager stand-in for the binder HOME start. */
    class FakeStartProxy(private val result: Int) {
        var calls = 0

        @Suppress("UNUSED_PARAMETER")
        fun startActivityAsUser(
            caller: Any?, callingPackage: String?, callingFeatureId: String?, intent: android.content.Intent?,
            resolvedType: String?, resultTo: Any?, resultWho: String?, requestCode: Int, flags: Int,
            profilerInfo: Any?, options: android.os.Bundle?, userId: Int,
        ): Int {
            calls++
            return result
        }
    }

    @Test
    fun `binder start success is exactly the 0 to 99 range`() {
        assertThat(ShizukuActivityTaskTransport.isStartSuccess(0)).isTrue()
        assertThat(ShizukuActivityTaskTransport.isStartSuccess(2)).isTrue() // START_TASK_TO_FRONT
        assertThat(ShizukuActivityTaskTransport.isStartSuccess(99)).isTrue()
        assertThat(ShizukuActivityTaskTransport.isStartSuccess(100)).isFalse() // START_SWITCHES_CANCELED
        assertThat(ShizukuActivityTaskTransport.isStartSuccess(101)).isFalse() // lock task violation
        assertThat(ShizukuActivityTaskTransport.isStartSuccess(-1)).isFalse()
    }

    @Test
    fun `binder HOME start reports 100 and 101 as failures and 0 as success`() {
        for ((result, expected) in listOf(100 to false, 101 to false, 0 to true, -91 to false)) {
            val proxy = FakeStartProxy(result)
            val transport = ShizukuActivityTaskTransport { proxy }
            assertThat(transport.startActivity(android.content.Intent(), null)).isEqualTo(expected)
            assertThat(proxy.calls).isEqualTo(1)
        }
    }

    @Test
    fun `binder HOME start without the method reports failure`() {
        assertThat(ShizukuActivityTaskTransport { Any() }.startActivity(android.content.Intent(), null)).isFalse()
    }

    // Restorer against a fake display model (moves land on top; refronts bring a task to the top).

    private val vdId = 7

    private class FakeDisplays(main: List<DisplayTask>) {
        val main = main.toMutableList()
        val vd = mutableListOf<DisplayTask>()
        val failMoves = mutableSetOf<Int>()
        /** VD reads return null (a transient Shizuku read failure). */
        var failVdReads = false
        val log = mutableListOf<String>()
        var onMove: () -> Unit = {}
        var onMainRead: () -> Unit = {}

        fun toVd(task: DisplayTask) { main.remove(task); vd.add(0, task) }
        fun front(task: DisplayTask) { main.remove(task); main.add(0, task) }
        /** VD release without destroy-content: kept tasks land on display 0's top. */
        fun release() { vd.reversed().forEach { front(it) }; vd.clear() }
    }

    private fun fakeShizuku(d: FakeDisplays): ShizukuClient {
        val s = mockk<ShizukuClient>(relaxed = true)
        every { s.rootTasksOnDisplay(0) } answers { d.onMainRead(); d.main.toList() }
        every { s.rootTasksOnDisplay(vdId) } answers { if (d.failVdReads) null else d.vd.toList() }
        every { s.moveRootTaskToDisplay(any(), 0) } answers {
            val id = firstArg<Int>()
            val task = d.vd.firstOrNull { it.taskId == id }
            if (task == null || id in d.failMoves) {
                false
            } else {
                d.vd.remove(task)
                d.front(task)
                d.log += "move $id"
                d.onMove()
                true
            }
        }
        every { s.startActivityFromRecents(any(), any()) } answers {
            val id = firstArg<Int>()
            d.log += "refront $id"
            d.main.firstOrNull { it.taskId == id }?.let { d.front(it); true } ?: false
        }
        every { s.startActivityAsShell(any(), any()) } answers { d.log += "refront home"; d.front(home); true }
        every { s.executeShellCommand(any()) } answers { d.log += "refront home"; d.front(home); 0 }
        return s
    }

    /** Agent opens Shelly on the VD: its singleTask task leaves display 0 (Chrome revealed). */
    private suspend fun launchShellyOnVd(r: MainDisplayTaskRestorer, d: FakeDisplays) {
        r.aroundLaunch(shelly.packageName!!, vdId) { d.toVd(shelly) }
    }

    @Test
    fun `restorer - after a failed run-end restore and a switch to Chrome, teardown leaves Chrome on top`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val shizuku = fakeShizuku(d)
        val r = MainDisplayTaskRestorer(shizuku, setOf(launcher))
        launchShellyOnVd(r, d)
        d.front(home) // the user goes Home during the run
        r.onMainDisplayForeground(launcher)
        d.failMoves += shelly.taskId
        r.restore(vdId) // run end: Shelly's move fails, nothing changes
        assertThat(d.main.first()).isEqualTo(home)
        d.front(chrome) // the user opens Chrome
        d.failMoves.clear()
        r.restore(vdId) // Hot Idle teardown
        assertThat(d.main.first()).isEqualTo(chrome)
        assertThat(d.main).contains(shelly)
        assertThat(d.log).doesNotContain("refront home")
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `restorer - with a refront owed only the displaced top moves before it`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d) // Chrome revealed
        r.aroundLaunch(chrome.packageName!!, vdId) { d.toVd(chrome) } // Chrome displaced too
        d.main.add(0, mail) // the user opens Mail
        r.onMainDisplayForeground(mail.packageName)
        r.restore(vdId)
        assertThat(d.log).containsExactly(
            "move ${chrome.taskId}", "refront ${mail.taskId}", "move ${shelly.taskId}", "refront ${mail.taskId}",
        ).inOrder()
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(d.vd).isEmpty()
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `restorer - the refront re-reads display 0 and follows a switch during the move`() = runTest {
        val d = FakeDisplays(listOf(shelly, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d) // Home revealed
        d.front(chrome) // the user opens Chrome
        r.onMainDisplayForeground(chrome.packageName)
        d.onMove = { d.front(home); d.front(shelly) } // ...and goes Home while Shelly is moved back
        r.restore(vdId)
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "refront home").inOrder()
        assertThat(d.main.first()).isEqualTo(home)
    }

    @Test
    fun `restorer - a task handed over by the VD release is refronted over`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val shizuku = fakeShizuku(d)
        val r = MainDisplayTaskRestorer(shizuku, setOf(launcher))
        launchShellyOnVd(r, d)
        d.front(home)
        r.onMainDisplayForeground(launcher)
        d.failMoves += shelly.taskId
        r.restore(vdId) // run end: fails
        d.front(chrome) // the user opens Chrome
        r.restore(vdId) // teardown restore: fails again
        assertThat(r.protectedTaskIds()).contains(shelly.taskId)
        val handover = r.prepareHandover(vdId)
        assertThat(handover).isEqualTo(MainDisplayHandover(setOf(shelly.taskId), MainDisplayRefront.Task(chrome.taskId)))
        d.release() // Shelly lands on display 0's top
        assertThat(d.main.first()).isEqualTo(shelly)
        r.refrontAfterHandover(handover)
        assertThat(d.main.first()).isEqualTo(chrome)
        assertThat(r.protectedTaskIds()).isEmpty()
        verify(exactly = 0) { shizuku.startActivityAsShell(any(), any()) }
    }

    @Test
    fun `restorer - nothing handed over means no post-release refront`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        r.restore(vdId) // succeeds (untouched: Shelly back on top)
        assertThat(d.main.first()).isEqualTo(shelly)
        val handover = r.prepareHandover(vdId)
        assertThat(handover).isEqualTo(MainDisplayHandover.NONE)
        val logBefore = d.log.toList()
        r.refrontAfterHandover(handover)
        assertThat(d.log).isEqualTo(logBefore)
        assertThat(d.main.first()).isEqualTo(shelly)
    }

    @Test
    fun `restorer - the handover refront waits until the release has reparented the task`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        d.front(home)
        r.onMainDisplayForeground(launcher)
        d.failMoves += shelly.taskId
        r.restore(vdId)
        d.front(chrome)
        r.restore(vdId)
        val handover = r.prepareHandover(vdId)
        // The release returns before WindowManager reparents: Shelly shows up on the 3rd read.
        var reads = 0
        d.onMainRead = { if (++reads == 3) { d.release(); d.log += "landed" } }
        r.refrontAfterHandover(handover)
        assertThat(d.log).containsExactly("landed", "refront ${chrome.taskId}").inOrder()
        assertThat(d.main.first()).isEqualTo(chrome)
    }

    @Test
    fun `restorer - a user switch while waiting for the handover is followed`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        d.front(home)
        r.onMainDisplayForeground(launcher)
        d.failMoves += shelly.taskId
        r.restore(vdId)
        d.front(chrome)
        r.restore(vdId)
        val handover = r.prepareHandover(vdId)
        assertThat(handover.refront).isEqualTo(MainDisplayRefront.Task(chrome.taskId))
        // While the release is pending the user opens Mail; then Shelly lands on top.
        var reads = 0
        d.onMainRead = {
            reads++
            if (reads == 1) d.front(mail)
            if (reads == 2) { d.release(); d.log += "landed" }
        }
        r.refrontAfterHandover(handover)
        assertThat(d.log).containsExactly("landed", "refront ${mail.taskId}").inOrder()
        assertThat(d.main.first()).isEqualTo(mail)
    }

    // ── Gate fix round (restorer level) ──

    @Test
    fun `restorer - after Chrome was the user's choice, a return Home during the handover wait refronts Home`() = runTest {
        val d = FakeDisplays(listOf(shelly, home))
        val shizuku = fakeShizuku(d)
        val r = MainDisplayTaskRestorer(shizuku, setOf(launcher))
        launchShellyOnVd(r, d) // Home revealed, display 0 untouched
        d.failMoves += shelly.taskId
        r.restore(vdId) // run end: frozen None, Shelly's move fails
        d.front(chrome) // the user opens Chrome
        r.restore(vdId) // teardown: follows Chrome, the move fails again
        val handover = r.prepareHandover(vdId)
        assertThat(handover).isEqualTo(MainDisplayHandover(setOf(shelly.taskId), MainDisplayRefront.Task(chrome.taskId)))
        // During the release wait the user returns Home, then Shelly lands on top.
        var reads = 0
        d.onMainRead = {
            reads++
            if (reads == 1) d.front(home)
            if (reads == 2) { d.release(); d.log += "landed" }
        }
        r.refrontAfterHandover(handover)
        assertThat(d.log).containsExactly("landed", "refront home").inOrder()
        assertThat(d.main.first()).isEqualTo(home)
    }

    /**
     * The mockable android.jar returns null from Intent's fluent builders, which would make the
     * restorer skip the binder HOME start entirely; make them return the Intent for [block].
     */
    private inline fun <T> withFluentIntent(block: () -> T): T {
        mockkConstructor(android.content.Intent::class)
        try {
            every { anyConstructed<android.content.Intent>().addCategory(any()) } answers { self as android.content.Intent }
            every { anyConstructed<android.content.Intent>().addFlags(any()) } answers { self as android.content.Intent }
            return block()
        } finally {
            unmockkConstructor(android.content.Intent::class)
        }
    }

    @Test
    fun `restorer - a rejected binder HOME start falls back to am start`() = runTest {
      withFluentIntent {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val shizuku = fakeShizuku(d)
        // What the transport now reports for START_SWITCHES_CANCELED (100) and friends.
        every { shizuku.startActivityAsShell(any(), any()) } answers { d.log += "binder home rejected"; false }
        val r = MainDisplayTaskRestorer(shizuku, setOf(launcher))
        launchShellyOnVd(r, d) // Chrome revealed
        d.front(home)
        r.onMainDisplayForeground(launcher) // the user went Home: refront Home owed
        r.restore(vdId)
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "binder home rejected", "refront home").inOrder()
        assertThat(d.main.first()).isEqualTo(home)
        assertThat(r.protectedTaskIds()).isEmpty()
        verify(exactly = 1) { shizuku.startActivityAsShell(any(), any()) }
        verify(exactly = 1) { shizuku.executeShellCommand(any()) }
      }
    }

    @Test
    fun `restorer - a HOME refront whose fallback fails too stays owed`() = runTest {
      withFluentIntent {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val shizuku = fakeShizuku(d)
        every { shizuku.startActivityAsShell(any(), any()) } answers { d.log += "binder home rejected"; false }
        every { shizuku.executeShellCommand(any()) } answers { d.log += "am start failed"; 1 }
        val r = MainDisplayTaskRestorer(shizuku, setOf(launcher))
        launchShellyOnVd(r, d)
        d.front(home)
        r.onMainDisplayForeground(launcher)
        r.restore(vdId) // Shelly moves back (on top), the Home refront fails both ways
        assertThat(d.main.first()).isEqualTo(shelly)
        every { shizuku.startActivityAsShell(any(), any()) } answers { d.log += "refront home"; d.front(home); true }
        r.restore(vdId) // refront-only retry
        assertThat(d.log).containsExactly(
            "move ${shelly.taskId}", "binder home rejected", "am start failed", "refront home",
        ).inOrder()
        assertThat(d.main.first()).isEqualTo(home)
        verify(exactly = 1) { shizuku.executeShellCommand(any()) } // the retry's binder start succeeded
      }
    }

    // ── Gate fix round 2: the Recents task refront uses the same 0..99 success range ──

    /** Shape-compatible IActivityTaskManager stand-in for startActivityFromRecents. */
    class FakeRecentsProxy(var result: Int) {
        var calls = 0

        @Suppress("UNUSED_PARAMETER")
        fun startActivityFromRecents(taskId: Int, options: android.os.Bundle?): Int {
            calls++
            return result
        }
    }

    @Test
    fun `recents task start reports 100 and 101 as failures and 0 and 2 as success`() {
        for ((result, expected) in listOf(100 to false, 101 to false, 0 to true, 2 to true, -1 to false)) {
            val proxy = FakeRecentsProxy(result)
            val transport = ShizukuActivityTaskTransport { proxy }
            assertThat(transport.startActivityFromRecents(42, null)).isEqualTo(expected)
            assertThat(proxy.calls).isEqualTo(1)
        }
    }

    @Test
    fun `restorer - a rejected Task refront stays owed and the refront-only retry completes it`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val shizuku = fakeShizuku(d)
        // Through the real transport: the binder first answers START_RETURN_LOCK_TASK_MODE_VIOLATION.
        val proxy = FakeRecentsProxy(101)
        val transport = ShizukuActivityTaskTransport { proxy }
        every { shizuku.startActivityFromRecents(any(), any()) } answers {
            val id = firstArg<Int>()
            val ok = transport.startActivityFromRecents(id, secondArg<android.os.Bundle?>())
            if (ok) {
                d.log += "refront $id"
                d.front(d.main.first { it.taskId == id })
            } else {
                d.log += "refront $id rejected"
            }
            ok
        }
        val r = MainDisplayTaskRestorer(shizuku, setOf(launcher))
        launchShellyOnVd(r, d) // Chrome revealed
        d.main.add(0, mail) // the user opens Mail: refront Mail owed
        r.onMainDisplayForeground(mail.packageName)
        r.restore(vdId) // Shelly moves back (on top), the Mail refront is rejected (101)
        assertThat(d.main.first()).isEqualTo(shelly)
        proxy.result = 2 // START_TASK_TO_FRONT
        r.restore(vdId) // refront-only retry
        assertThat(d.log).containsExactly(
            "move ${shelly.taskId}", "refront ${mail.taskId} rejected", "refront ${mail.taskId}",
        ).inOrder()
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(proxy.calls).isEqualTo(2)
        r.restore(vdId) // completed: nothing is owed any more
        assertThat(proxy.calls).isEqualTo(2)
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    // ── MCP intake (second intake into the same background-run machinery) ──
    // Kept in this CI-enumerated class (build-shelly-variant.yml) on purpose.

    private val mcpToken = "a".repeat(64)

    private class FakeMcpBackend(private val registry: McpRunRegistry) : McpBackend {
        var refuse: McpBackend.StartResult.Refused? = null
        val started = mutableListOf<String>()
        val stopRequests = mutableListOf<String>()
        /** Simulates AgentService: a stop request ends the session -> tracker finishes STOPPED. */
        var stopFinishesRun = true

        override fun startRun(goal: String): McpBackend.StartResult {
            refuse?.let { return it }
            val handle = registry.issue()
            registry.markRunning(handle)
            started += goal
            return McpBackend.StartResult.Started(handle)
        }

        override fun stopRun(runHandle: String): Boolean {
            stopRequests += runHandle
            if (stopFinishesRun) {
                McpRunStatus.complete(registry, runHandle, ShellyRunState.Finished(ShellyRunState.Finished.Kind.STOPPED, null))
            }
            return true
        }

        override fun status() = McpBackend.Status(version = "test", shizukuReady = true, accessibilityReady = false)
    }

    private class McpFixture(token: String?) {
        var token: String? = token
        val audit = mutableListOf<String>()
        val registry = McpRunRegistry()
        val backend = FakeMcpBackend(registry)
        private var nextSession = 0
        val handler = McpHandler(
            expectedToken = { this.token },
            registry = registry,
            backend = backend,
            audit = { audit += it },
            serverVersion = "test",
            newSessionId = { "session-${++nextSession}" },
        )
    }

    private fun mcpRequest(
        body: String,
        token: String? = mcpToken,
        session: String? = null,
        method: String = "POST",
        path: String = "/mcp",
        origin: String? = null,
    ): McpHttpRequest {
        val headers = buildMap {
            token?.let { put("authorization", "Bearer $it") }
            session?.let { put("mcp-session-id", it) }
            origin?.let { put("origin", it) }
        }
        return McpHttpRequest(method, path, headers, body)
    }

    private fun rpc(method: String, params: String = "{}", id: Int? = 1): String =
        if (id == null) """{"jsonrpc":"2.0","method":"$method","params":$params}"""
        else """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    private fun McpFixture.initialize(): String {
        val res = handler.handle(mcpRequest(rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")))
        assertThat(res.status).isEqualTo(200)
        return res.headers.getValue(McpHandler.SESSION_HEADER)
    }

    private fun McpFixture.callTool(session: String, name: String, args: String): kotlinx.serialization.json.JsonObject {
        val res = handler.handle(mcpRequest(rpc("tools/call", """{"name":"$name","arguments":$args}"""), session = session))
        assertThat(res.status).isEqualTo(200)
        return kotlinx.serialization.json.Json.parseToJsonElement(res.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun kotlinx.serialization.json.JsonObject.toolText(): String =
        this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun kotlinx.serialization.json.JsonObject.toolData(): kotlinx.serialization.json.JsonObject {
        assertThat(this["isError"]!!.jsonPrimitive.boolean).isFalse()
        return kotlinx.serialization.json.Json.parseToJsonElement(toolText()).jsonObject
    }

    @Test
    fun `mcp token - only the exact bearer token is accepted`() {
        assertThat(McpAuth.isAuthorized("Bearer $mcpToken", mcpToken)).isTrue()
        assertThat(McpAuth.isAuthorized("Bearer ${"b".repeat(64)}", mcpToken)).isFalse()
        assertThat(McpAuth.isAuthorized(mcpToken, mcpToken)).isFalse()
        assertThat(McpAuth.isAuthorized("bearer $mcpToken", mcpToken)).isFalse()
        assertThat(McpAuth.isAuthorized("Bearer $mcpToken ", mcpToken)).isFalse()
        assertThat(McpAuth.isAuthorized("Bearer ${mcpToken.dropLast(1)}", mcpToken)).isFalse()
        assertThat(McpAuth.isAuthorized(null, mcpToken)).isFalse()
        // An unconfigured / malformed server token rejects everything, including its own value.
        assertThat(McpAuth.isAuthorized("Bearer ", "")).isFalse()
        assertThat(McpAuth.isAuthorized("Bearer short", "short")).isFalse()
        assertThat(McpAuth.isAuthorized("Bearer x", null)).isFalse()
    }

    @Test
    fun `mcp token - generated tokens are 32 random bytes in hex`() {
        val a = McpAuth.generateToken()
        val b = McpAuth.generateToken()
        assertThat(McpAuth.isWellFormedToken(a)).isTrue()
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `mcp token - every request is gated and regenerating invalidates the old token at once`() {
        val f = McpFixture(mcpToken)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), token = null)).status).isEqualTo(401)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), token = "c".repeat(64))).status).isEqualTo(401)
        val session = f.initialize()
        assertThat(f.handler.handle(mcpRequest(rpc("tools/list"), session = session, token = null)).status).isEqualTo(401)

        val fresh = "d".repeat(64)
        f.token = fresh
        f.handler.clearSessions()
        assertThat(f.handler.handle(mcpRequest(rpc("tools/list"), session = session)).status).isEqualTo(401)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), token = fresh)).status).isEqualTo(200)
        // The token never reaches the audit log.
        assertThat(f.audit.none { mcpToken in it || fresh in it }).isTrue()
        assertThat(f.audit).contains("mcp rejected: unauthorized")
    }

    @Test
    fun `mcp origin guard refuses non-loopback origins before auth`() {
        val f = McpFixture(mcpToken)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), origin = "https://evil.example")).status).isEqualTo(403)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), origin = "null")).status).isEqualTo(403)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), origin = "http://localhost:3000")).status).isEqualTo(200)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), origin = "http://127.0.0.1")).status).isEqualTo(200)
        assertThat(f.handler.handle(mcpRequest(rpc("initialize"), path = "/other")).status).isEqualTo(404)
    }

    @Test
    fun `mcp session handshake - initialize, initialized, tools list, delete`() {
        val f = McpFixture(mcpToken)
        // No session yet: everything but initialize is refused.
        val noSession = f.handler.handle(mcpRequest(rpc("tools/list")))
        assertThat(noSession.status).isEqualTo(400)
        assertThat(noSession.body).contains("-32000")

        val init = f.handler.handle(mcpRequest(rpc("initialize", """{"protocolVersion":"2025-06-18"}""")))
        assertThat(init.status).isEqualTo(200)
        val session = init.headers.getValue("Mcp-Session-Id")
        val result = kotlinx.serialization.json.Json.parseToJsonElement(init.body!!).jsonObject["result"]!!.jsonObject
        assertThat(result["protocolVersion"]!!.jsonPrimitive.content).isEqualTo("2025-06-18")
        assertThat(result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("closepaw")

        val initialized = f.handler.handle(mcpRequest(rpc("notifications/initialized", id = null), session = session))
        assertThat(initialized.status).isEqualTo(202)
        assertThat(initialized.body).isNull()

        assertThat(f.handler.handle(mcpRequest(rpc("tools/list"), session = "bogus")).status).isEqualTo(400)
        val list = f.handler.handle(mcpRequest(rpc("tools/list"), session = session))
        val names = kotlinx.serialization.json.Json.parseToJsonElement(list.body!!).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        // Typed surface only: run_goal is the single execution entry; no raw input tools.
        assertThat(names).containsExactly("run_goal", "get_run_result", "stop_run", "closepaw_status")

        // Unknown protocol versions negotiate down to 2025-06-18.
        val other = f.handler.handle(mcpRequest(rpc("initialize", """{"protocolVersion":"1999-01-01"}""")))
        assertThat(other.body).contains("\"protocolVersion\":\"2025-06-18\"")

        assertThat(f.handler.handle(mcpRequest("", session = session, method = "GET")).status).isEqualTo(405)
        assertThat(f.handler.handle(mcpRequest("", session = session, method = "DELETE")).status).isEqualTo(200)
        assertThat(f.handler.handle(mcpRequest(rpc("tools/list"), session = session)).status).isEqualTo(400)
        assertThat(f.handler.handle(mcpRequest("{not json", session = session)).status).isEqualTo(400)
    }

    @Test
    fun `mcp unknown and raw-input tools are refused`() {
        val f = McpFixture(mcpToken)
        val session = f.initialize()
        for (tool in listOf("tap", "swipe", "input_text", "shell")) {
            val res = f.handler.handle(mcpRequest(rpc("tools/call", """{"name":"$tool","arguments":{}}"""), session = session))
            assertThat(res.body).contains("-32602")
        }
        assertThat(f.backend.started).isEmpty()
    }

    @Test
    fun `mcp run handle registry - issue, poll, terminal state`() {
        val registry = McpRunRegistry()
        val h = registry.issue()
        assertThat(registry.snapshot(h)!!.status).isEqualTo(McpRunRegistry.Status.STARTING)
        assertThat(registry.markRunning(h)).isTrue()
        assertThat(registry.activeCount()).isEqualTo(1)
        // Non-terminal: returns the current state after waiting.
        assertThat(registry.await(h, 30)!!.status).isEqualTo(McpRunRegistry.Status.RUNNING)

        // A long poll wakes up as soon as the run finishes.
        val waiter = Thread { Thread.sleep(50); registry.complete(h, McpRunRegistry.Status.COMPLETED, "done") }
        waiter.start()
        val t0 = System.nanoTime()
        val snap = registry.await(h, 10_000)!!
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5_000L)
        waiter.join()
        assertThat(snap).isEqualTo(McpRunRegistry.Snapshot(h, McpRunRegistry.Status.COMPLETED, "done"))
        // First terminal report wins.
        assertThat(registry.complete(h, McpRunRegistry.Status.FAILED, "late")).isFalse()
        assertThat(registry.snapshot(h)!!.status).isEqualTo(McpRunRegistry.Status.COMPLETED)
        assertThat(registry.markRunning(h)).isFalse()
        assertThat(registry.activeCount()).isEqualTo(0)
        assertThat(registry.await("run_unknown", 10)).isNull()
    }

    @Test
    fun `mcp registry is bounded and evicts finished handles first`() {
        var n = 0
        val registry = McpRunRegistry(maxEntries = 2, newHandle = { "h${n++}" })
        val running = registry.issue()
        val done = registry.issue()
        registry.complete(done, McpRunRegistry.Status.FAILED, "x")
        val third = registry.issue()
        assertThat(registry.snapshot(done)).isNull()
        assertThat(registry.snapshot(running)).isNotNull()
        assertThat(registry.snapshot(third)).isNotNull()
    }

    @Test
    fun `mcp run status maps background run outcomes`() {
        val registry = McpRunRegistry()
        val h = registry.issue()
        McpRunStatus.complete(registry, h, ShellyRunState.Finished(ShellyRunState.Finished.Kind.DONE, "  Wi-Fi is on  "))
        assertThat(registry.snapshot(h)).isEqualTo(McpRunRegistry.Snapshot(h, McpRunRegistry.Status.COMPLETED, "Wi-Fi is on"))
        assertThat(McpRunStatus.of(ShellyRunState.Finished.Kind.FAILED)).isEqualTo(McpRunRegistry.Status.FAILED)
        assertThat(McpRunStatus.of(ShellyRunState.Finished.Kind.STOPPED)).isEqualTo(McpRunRegistry.Status.CANCELLED)
    }

    @Test
    fun `mcp run_goal, get_run_result and audit lines`() {
        val f = McpFixture(mcpToken)
        val session = f.initialize()
        val handle = f.callTool(session, "run_goal", """{"goal":"open Settings"}""").toolData()["runHandle"]!!.jsonPrimitive.content
        assertThat(f.backend.started).containsExactly("open Settings")

        val running = f.callTool(session, "get_run_result", """{"runHandle":"$handle","waitMs":10}""").toolData()
        assertThat(running["status"]!!.jsonPrimitive.content).isEqualTo("running")

        f.registry.complete(handle, McpRunRegistry.Status.COMPLETED, "opened")
        val done = f.callTool(session, "get_run_result", """{"runHandle":"$handle","waitMs":999999}""").toolData()
        assertThat(done["status"]!!.jsonPrimitive.content).isEqualTo("completed")
        assertThat(done["summary"]!!.jsonPrimitive.content).isEqualTo("opened")

        assertThat(f.callTool(session, "run_goal", """{"goal":"  "}""")["isError"]!!.jsonPrimitive.boolean).isTrue()
        f.backend.refuse = McpBackend.StartResult.Refused("BUSY", "ClosePaw is busy with another task")
        val refused = f.callTool(session, "run_goal", """{"goal":"x"}""")
        assertThat(refused.toolText()).isEqualTo("Error: [BUSY] ClosePaw is busy with another task")

        val unknown = f.callTool(session, "get_run_result", """{"runHandle":"nope"}""")
        assertThat(unknown["isError"]!!.jsonPrimitive.boolean).isTrue()

        val status = f.callTool(session, "closepaw_status", "{}").toolData()
        assertThat(status["serverOn"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(status["activeRuns"]!!.jsonPrimitive.int).isEqualTo(0)

        // One line per tool call, with tool, handle and outcome — and never the goal text.
        val toolLines = f.audit.filter { it.startsWith("mcp tool=") }
        assertThat(toolLines).hasSize(7)
        assertThat(toolLines[0]).isEqualTo("mcp tool=run_goal handle=$handle outcome=started")
        assertThat(toolLines[2]).isEqualTo("mcp tool=get_run_result handle=$handle outcome=completed")
        assertThat(toolLines[4]).isEqualTo("mcp tool=run_goal handle=- outcome=refused:BUSY")
        assertThat(f.audit.none { "open Settings" in it }).isTrue()
    }

    @Test
    fun `mcp stop_run propagates to the run and the poll sees cancelled`() {
        val f = McpFixture(mcpToken)
        val session = f.initialize()
        val handle = f.callTool(session, "run_goal", """{"goal":"g"}""").toolData()["runHandle"]!!.jsonPrimitive.content

        val stop = f.callTool(session, "stop_run", """{"runHandle":"$handle"}""").toolData()
        assertThat(f.backend.stopRequests).containsExactly(handle)
        assertThat(stop["stopRequested"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(stop["status"]!!.jsonPrimitive.content).isEqualTo("cancelled")
        val polled = f.callTool(session, "get_run_result", """{"runHandle":"$handle"}""").toolData()
        assertThat(polled["status"]!!.jsonPrimitive.content).isEqualTo("cancelled")

        // Stopping a finished run does not reach the backend again.
        val again = f.callTool(session, "stop_run", """{"runHandle":"$handle"}""").toolData()
        assertThat(again["stopRequested"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(f.backend.stopRequests).hasSize(1)
    }

    @Test
    fun `mcp runs are halted by STOP-ALL through the shared tracker`() {
        // STOP-ALL / notification Stop end the session (Op.Shutdown -> SessionCompleted). The MCP
        // run shares the Shelly tracker, whose terminal state AgentService hands to the registry.
        val registry = McpRunRegistry()
        val handle = registry.issue()
        registry.markRunning(handle)
        val t = tracker()
        t.onEvent(screen("com.android.settings"))
        t.onEvent(SessionCompleted(sid, 0L, SessionEndReason.USER_STOPPED))
        assertThat(t.isFinished).isTrue()
        // MCP runs carry no Shelly correlation: nothing would be sent to Shelly.
        assertThat(t.claimResult(0L)).isNull()
        McpRunStatus.complete(registry, handle, t.state as ShellyRunState.Finished)
        assertThat(registry.await(handle, 0)!!.status).isEqualTo(McpRunRegistry.Status.CANCELLED)
    }

    @Test
    fun `mcp http server round trip over a real socket`() {
        val f = McpFixture(mcpToken)
        val server = McpHttpServer(f.handler, port = 0, bindAddress = java.net.InetAddress.getLoopbackAddress())
        server.start()
        try {
            fun post(body: String, session: String?): Pair<String, String> {
                java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.boundPort).use { s ->
                    val bytes = body.toByteArray()
                    val head = buildString {
                        append("POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n")
                        append("Authorization: Bearer $mcpToken\r\n")
                        session?.let { append("Mcp-Session-Id: $it\r\n") }
                        append("Content-Length: ${bytes.size}\r\n\r\n")
                    }
                    s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                    val raw = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                    return raw.substringBefore("\r\n\r\n") to raw.substringAfter("\r\n\r\n")
                }
            }
            val (head, body) = post(rpc("initialize"), null)
            assertThat(head).startsWith("HTTP/1.1 200")
            val session = Regex("Mcp-Session-Id: (\\S+)").find(head)!!.groupValues[1]
            assertThat(body).contains("2025-06-18")
            val (listHead, listBody) = post(rpc("tools/list"), session)
            assertThat(listHead).startsWith("HTTP/1.1 200")
            assertThat(listBody).contains("run_goal")
        } finally {
            server.stop()
        }
        assertThat(server.isRunning).isFalse()
    }

    // ── Build 59: deferred task return (no run-end flash when the user has moved on) ──

    /** Shelly displaced (Chrome revealed); the user then opens Mail on display 0. */
    private suspend fun displaceShellyUserOpensMail(r: MainDisplayTaskRestorer, d: FakeDisplays) {
        launchShellyOnVd(r, d)
        d.main.add(0, mail)
        r.onMainDisplayForeground(mail.packageName)
    }

    @Test
    fun `deferred return - the user moved on at run end, so nothing moves and the return is deferred`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        assertThat(d.log).isEmpty() // no move, no refront: no flash
        assertThat(d.vd).containsExactly(shelly)
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(r.hasDeferredRestore()).isTrue()
        assertThat(r.deferredTaskIds()).containsExactly(shelly.taskId)
        assertThat(r.protectedTaskIds()).contains(shelly.taskId) // kept by the VD cleanup
    }

    @Test
    fun `deferred return - screen off executes the frozen order with a refront from a fresh read`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d) // Chrome revealed
        r.aroundLaunch(chrome.packageName!!, vdId) { d.toVd(chrome) } // Chrome displaced too
        d.main.add(0, mail) // the user opens Mail
        r.onMainDisplayForeground(mail.packageName)
        r.restoreAtRunEnd(vdId) // frozen: [Shelly, Chrome], refront Mail; deferred
        assertThat(d.log).isEmpty()
        assertThat(r.deferredTaskIds()).containsExactly(shelly.taskId, chrome.taskId).inOrder()
        d.front(home) // later the user goes Home, then turns the screen off
        r.restoreDeferred(vdId) // the ACTION_SCREEN_OFF trigger
        // Frozen order (the displaced top first while a refront is owed, as build 58), and the
        // refront follows display 0 as read NOW (Home), not the frozen Mail.
        assertThat(d.log).containsExactly(
            "move ${chrome.taskId}", "refront home", "move ${shelly.taskId}", "refront home",
        ).inOrder()
        assertThat(d.main.first()).isEqualTo(home)
        assertThat(d.vd).isEmpty()
        assertThat(r.hasDeferredRestore()).isFalse()
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `deferred return - an untouched display 0 is still restored immediately at run end`() = runTest {
        val d = FakeDisplays(listOf(shelly, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d) // Home revealed, the user never touches display 0
        r.restoreAtRunEnd(vdId)
        assertThat(d.log).containsExactly("move ${shelly.taskId}")
        assertThat(d.main.first()).isEqualTo(shelly)
        assertThat(r.hasDeferredRestore()).isFalse()
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `deferred return - a deferred task the user already pulled back clears the deferral without any move`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        assertThat(r.hasDeferredRestore()).isTrue()
        // The user opens Shelly from the launcher: its task leaves the VD for display 0 by itself.
        d.vd.remove(shelly)
        d.front(shelly)
        r.restoreDeferred(vdId) // screen off
        assertThat(d.log).isEmpty() // no move, and no refront over the app the user chose
        assertThat(d.main.first()).isEqualTo(shelly)
        assertThat(r.hasDeferredRestore()).isFalse()
        assertThat(r.protectedTaskIds()).isEmpty()
        r.restore(vdId) // teardown afterwards: nothing left to do
        assertThat(d.log).isEmpty()
    }

    @Test
    fun `deferred return - a re-check drops a pulled-back task and keeps the rest deferred`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        r.aroundLaunch(chrome.packageName!!, vdId) { d.toVd(chrome) }
        d.main.add(0, mail)
        r.onMainDisplayForeground(mail.packageName)
        r.restoreAtRunEnd(vdId)
        d.vd.remove(shelly) // the user pulls Shelly back to display 0 themselves
        d.front(shelly)
        r.recheckDeferred(vdId) // viewer closed, screen on: stays deferred
        assertThat(d.log).isEmpty()
        assertThat(r.hasDeferredRestore()).isTrue()
        assertThat(r.deferredTaskIds()).containsExactly(chrome.taskId)
        r.restoreDeferred(vdId) // screen off: Chrome goes back, Shelly (the user's choice) stays on top
        assertThat(d.log).containsExactly("move ${chrome.taskId}", "refront ${shelly.taskId}").inOrder()
        assertThat(d.main.first()).isEqualTo(shelly)
        assertThat(r.hasDeferredRestore()).isFalse()
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `deferred return - a new run reconciles the deferral before its own work`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        // Hot Idle follow-up: VirtualDisplayPlatform.start() on the running VD reconciles first...
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        // ...and only then does the new run launch anything.
        r.aroundLaunch(agentTask.packageName!!, vdId) { d.log += "new run launch"; d.vd.add(0, agentTask) }
        assertThat(d.log).containsExactly(
            "move ${shelly.taskId}", "refront ${mail.taskId}", "new run launch",
        ).inOrder()
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(r.hasDeferredRestore()).isFalse()
        assertThat(d.vd).containsExactly(agentTask)
        assertThat(r.unreconciledTaskIds()).isEmpty()
    }

    // ── Gate fix: reconcile-before-run reports an outcome; a failed reconcile fails the start ──

    @Test
    fun `reconcile - nothing deferred reports NOTHING_PENDING and leaves a build-58 retry alone`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.NOTHING_PENDING)
        launchShellyOnVd(r, d)
        d.failMoves += shelly.taskId
        r.restore(vdId) // untouched display 0: immediate (not deferred) restore, the move fails
        d.failMoves.clear()
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.NOTHING_PENDING)
        assertThat(d.log).isEmpty() // still teardown's job, as before
        assertThat(r.protectedTaskIds()).contains(shelly.taskId)
    }

    @Test
    fun `reconcile - a failed VD read reports FAILED and the task stays owed for the next start`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        d.failVdReads = true
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.FAILED)
        assertThat(d.log).isEmpty()
        assertThat(d.vd).containsExactly(shelly)
        assertThat(r.unreconciledTaskIds()).containsExactly(shelly.taskId)
        r.dropDeferral() // what start() does on FAILED (Hot Idle not held), before it throws
        // Dropping the deferral does not reconcile: the next start still owes Shelly's return.
        assertThat(r.unreconciledTaskIds()).containsExactly(shelly.taskId)
        d.failVdReads = false
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "refront ${mail.taskId}").inOrder()
        assertThat(d.vd).isEmpty()
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(r.unreconciledTaskIds()).isEmpty()
    }

    @Test
    fun `reconcile - a failed move reports FAILED even though executing the deferral cleared it`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        d.failMoves += shelly.taskId
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.FAILED)
        assertThat(r.hasDeferredRestore()).isFalse() // the deferral flag is gone...
        assertThat(d.vd).containsExactly(shelly) // ...but Shelly is still on the VD
        assertThat(r.unreconciledTaskIds()).containsExactly(shelly.taskId)
        assertThat(r.protectedTaskIds()).contains(shelly.taskId)
        d.failMoves.clear()
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "refront ${mail.taskId}").inOrder()
        assertThat(d.main.first()).isEqualTo(mail)
    }

    @Test
    fun `reconcile - a transient move failure is retried within the same start`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        d.failMoves += shelly.taskId
        // Attempt 1 (display-0 read #1) fails the move; attempt 2's read (#2) finds Shizuku healed.
        var mainReads = 0
        d.onMainRead = { if (++mainReads == 2) d.failMoves.clear() }
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "refront ${mail.taskId}").inOrder()
        assertThat(d.vd).isEmpty()
    }

    @Test
    fun `reconcile - a deferred task the user already pulled back counts as reconciled`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        d.vd.remove(shelly) // the user opens Shelly from the launcher
        d.front(shelly)
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        assertThat(d.log).isEmpty() // self-heal: no move, no refront over the user's choice
        assertThat(r.unreconciledTaskIds()).isEmpty()
        assertThat(r.hasDeferredRestore()).isFalse()
    }

    @Test
    fun `reconcile - waits for a run-end restore that is still deciding, and then reconciles its deferral`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // The run-end restore stalls on its display-0 read, holding the restorer, BEFORE it has
        // recorded the deferral.
        d.onMainRead = { reading.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val runEnd = launch(Dispatchers.Default) { r.restoreAtRunEnd(vdId) }
        assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        val reconcile = async(Dispatchers.Default) { r.reconcileBeforeRun(vdId) }
        Thread.sleep(100) // give a broken (unserialized) debt check the chance to answer NOTHING_PENDING
        assertThat(reconcile.isCompleted).isFalse()
        release.countDown()
        assertThat(reconcile.await()).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        runEnd.join()
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "refront ${mail.taskId}").inOrder()
        assertThat(d.vd).isEmpty()
        assertThat(r.unreconciledTaskIds()).isEmpty()
    }

    // ── Gate fix: run-end restore ordering (result report -> restore -> follow-up start) ──

    @Test
    fun `run end - a follow-up start waits for the restore, which still waits for the result report`() = runTest {
        val seq = RunEndRestoreSequencer(this)
        val log = mutableListOf<String>()
        seq.expectResultReport() // Shelly run: the tracker will report a result
        seq.onTaskEnded { log += "restore" } // recorded before the session goes Idle
        runCurrent()
        assertThat(log).isEmpty() // gated on the result report
        assertThat(seq.inFlight()).isNotNull()
        launch { seq.awaitRunEnd(); log += "follow-up start" } // the follow-up reaches start() first
        runCurrent()
        assertThat(log).isEmpty() // waiting does NOT release the gate
        log += "result reported"
        seq.resultReported()
        advanceUntilIdle()
        assertThat(log).containsExactly("result reported", "restore", "follow-up start").inOrder()
        assertThat(seq.inFlight()).isNull()
    }

    @Test
    fun `run end - a result reported before the task end does not hold the restore`() = runTest {
        val seq = RunEndRestoreSequencer(this)
        val log = mutableListOf<String>()
        seq.expectResultReport()
        seq.resultReported() // the run finished on SessionError first; TaskCompleted comes later
        seq.onTaskEnded { log += "restore" }
        advanceUntilIdle()
        assertThat(log).containsExactly("restore")
    }

    @Test
    fun `run end - a run without a result report restores right away`() = runTest {
        val seq = RunEndRestoreSequencer(this)
        val log = mutableListOf<String>()
        seq.onTaskEnded { log += "restore" } // e.g. a Hot Idle follow-up typed in ClosePaw
        advanceUntilIdle()
        assertThat(log).containsExactly("restore")
        seq.awaitRunEnd() // completed: no wait
    }

    /** A Shelly run's run-end restore, wired as VirtualDisplayPlatform.onTaskEnded wires it. */
    private fun recordRunEnd(seq: RunEndRestoreSequencer, r: MainDisplayTaskRestorer) {
        seq.expectResultReport() // Shelly run: the tracker will report a result
        seq.onTaskEnded(onAbandoned = { r.oweAbandonedRunEndReturn() }) { r.restoreAtRunEnd(vdId) }
    }

    @Test
    fun `run end - a result report that never comes abandons the restore - no move, no wedge, the debt stays owed`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d) // display 0 untouched: a run-end restore would move Shelly at once
        val seq = RunEndRestoreSequencer(this, resultReportTimeoutMs = 10_000)
        recordRunEnd(seq, r)
        advanceTimeBy(9_999)
        runCurrent()
        assertThat(seq.inFlight()).isNotNull() // still waiting for the report
        advanceUntilIdle() // the gate times out
        // Abandoned: no move, no refront (the report may still come and must come first)...
        assertThat(d.log).isEmpty()
        assertThat(d.vd).containsExactly(shelly)
        // ...the recorded run end has completed, so follow-ups / replacements do not wedge...
        assertThat(seq.inFlight()).isNull()
        seq.awaitRunEnd()
        // ...and the work is still owed: protected from the VD cleanup, unreconciled for a start.
        assertThat(r.protectedTaskIds()).contains(shelly.taskId)
        assertThat(r.unreconciledTaskIds()).contains(shelly.taskId)
        assertThat(r.hasDeferredRestore()).isFalse() // Hot Idle not held: idle-timeout teardown bounds it
        // The report arrives late: it releases nothing; still no move.
        seq.resultReported()
        advanceUntilIdle()
        assertThat(d.log).isEmpty()
        // The next start's reconcile (VirtualDisplayPlatform.start) places it and confirms it.
        assertThat(r.reconcileBeforeRun(vdId)).isEqualTo(MainDisplayReconcileOutcome.RESTORED)
        assertThat(d.log).containsExactly("move ${shelly.taskId}")
        assertThat(d.vd).isEmpty()
        assertThat(d.main.first()).isEqualTo(shelly)
        assertThat(r.unreconciledTaskIds()).isEmpty()
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `run end - an abandoned restore is placed by teardown as well`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        val seq = RunEndRestoreSequencer(this, resultReportTimeoutMs = 10_000)
        recordRunEnd(seq, r)
        advanceUntilIdle() // abandoned
        assertThat(d.log).isEmpty()
        r.restore(vdId) // VirtualDisplayPlatform.stop(): the teardown restore
        assertThat(d.log).containsExactly("move ${shelly.taskId}")
        assertThat(d.main.first()).isEqualTo(shelly)
        assertThat(r.protectedTaskIds()).isEmpty()
        assertThat(r.unreconciledTaskIds()).isEmpty()
    }

    @Test
    fun `run end - a report within the timeout still restores, after the report`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        val seq = RunEndRestoreSequencer(this, resultReportTimeoutMs = 10_000)
        recordRunEnd(seq, r)
        advanceTimeBy(9_000)
        runCurrent()
        assertThat(d.log).isEmpty()
        d.log += "result reported"
        seq.resultReported()
        advanceUntilIdle()
        seq.awaitRunEnd()
        assertThat(d.log).containsExactly("result reported", "move ${shelly.taskId}").inOrder()
        assertThat(r.unreconciledTaskIds()).isEmpty()
    }

    @Test
    fun `replacement - the new session waits for the old teardown whenever it may still move display-0 tasks`() {
        fun must(returns: Boolean, inFlight: Boolean, deferred: Boolean) =
            ShellyBackgroundRun.mustAwaitReplacedShutdown(returns, inFlight, deferred)
        assertThat(must(returns = false, inFlight = false, deferred = false)).isFalse()
        // A Shelly-run platform: its teardown restores / hands over, recorded deferral or not.
        assertThat(must(returns = true, inFlight = false, deferred = false)).isTrue()
        // The run-end restore is still suspended: no deferral recorded YET, the wait is still needed.
        assertThat(must(returns = false, inFlight = true, deferred = false)).isTrue()
        assertThat(must(returns = false, inFlight = false, deferred = true)).isTrue()
    }

    @Test
    fun `replacement - a new start does not proceed until the old shutdown has completed`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val oldShutdown = Job() // the replaced session's teardown, still restoring / handing over
        barrier.add(oldShutdown)
        var started = false
        launch { if (barrier.awaitClear(30_000)) started = true }
        runCurrent()
        assertThat(started).isFalse()
        oldShutdown.complete()
        advanceUntilIdle()
        assertThat(started).isTrue()
        assertThat(barrier.outstanding).isEqualTo(0)
    }

    @Test
    fun `replacement - a start that timed out leaves the barrier, so a retry still waits`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val oldShutdown = Job()
        barrier.add(oldShutdown)
        // Start A gives up (it fails; it never proceeds concurrently with the old teardown).
        assertThat(barrier.awaitClear(30_000)).isFalse()
        assertThat(barrier.outstanding).isEqualTo(1)
        // Start B (the old session is already detached, so B replaces nothing) still waits for it.
        var bStarted = false
        launch { if (barrier.awaitClear(30_000)) bStarted = true }
        runCurrent()
        assertThat(bStarted).isFalse()
        oldShutdown.complete()
        advanceUntilIdle()
        assertThat(bStarted).isTrue()
        assertThat(barrier.awaitClear(30_000)).isTrue() // nothing outstanding: immediate
    }

    @Test
    fun `replacement - a shutdown registered while waiting is waited for too`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val first = Job()
        val second = Job()
        barrier.add(first)
        var started = false
        launch { if (barrier.awaitClear(30_000)) started = true }
        runCurrent()
        barrier.add(second)
        first.complete()
        runCurrent()
        assertThat(started).isFalse()
        second.complete()
        advanceUntilIdle()
        assertThat(started).isTrue()
    }

    @Test
    fun `replacement - a session never waits for its own teardown, but still for every other one`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val own = Any() // the waiting session's platform
        val other = Any()
        val ownTeardown = Job()
        val otherTeardown = Job()
        barrier.add(ownTeardown, owner = own)
        assertThat(barrier.awaitClear(30_000, exceptOwner = own)).isTrue() // no self-wait
        barrier.add(otherTeardown, owner = other)
        var started = false
        launch { if (barrier.awaitClear(30_000, exceptOwner = own)) started = true }
        runCurrent()
        assertThat(started).isFalse()
        otherTeardown.complete()
        advanceUntilIdle()
        assertThat(started).isTrue()
        // Every other session still waits for it.
        assertThat(barrier.awaitClear(1_000)).isFalse()
        assertThat(barrier.awaitClear(1_000, exceptOwner = other)).isFalse()
        ownTeardown.complete()
        assertThat(barrier.outstanding).isEqualTo(0)
    }

    // ── Gate fix round 3: only a teardown that actually ran clears the barrier ──

    /** A scope like serviceScope: cancellable, on the test scheduler. */
    private fun kotlinx.coroutines.test.TestScope.cancellableScope() = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob()
    )

    @Test
    fun `replacement - a cancelled teardown never counts as reconciled`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val teardown = Job()
        barrier.add(teardown)
        var result: Boolean? = null
        launch { result = barrier.awaitClear(30_000) }
        runCurrent()
        assertThat(result).isNull() // waiting
        teardown.cancel()
        advanceUntilIdle()
        assertThat(result).isFalse() // the waiting start fails; it does not proceed
        assertThat(currentTime).isLessThan(30_000L) // right away, not after the bound
        assertThat(barrier.outstanding).isEqualTo(1) // the entry stays
        assertThat(barrier.awaitClear(30_000)).isFalse() // so every later start fails too
    }

    @Test
    fun `replacement - a failed teardown never counts as reconciled`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val teardown = Job()
        barrier.add(teardown, owner = Any())
        teardown.completeExceptionally(IllegalStateException("platform stop threw"))
        assertThat(barrier.awaitClear(30_000)).isFalse()
        assertThat(barrier.outstanding).isEqualTo(1)
    }

    @Test
    fun `replacement - destroying the service mid-wait cannot skip the replaced teardown`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val scope = cancellableScope()
        val runEndRestore = Job() // session A's run-end restore, still suspended
        val log = mutableListOf<String>()
        var stopped = false
        val reconciled = launchReplacedSessionTeardown(
            scope = scope,
            runEndRestore = runEndRestore,
            shutdown = { log += "A teardown"; stopped = true },
            isTornDown = { stopped },
            stopPlatform = { log += "forced stop"; stopped = true },
        )
        barrier.add(reconciled, owner = Any())
        runCurrent() // the teardown waits for the run-end restore
        scope.cancel() // service destroyed: serviceScope-like cancellation
        runCurrent()
        assertThat(log).isEmpty()
        assertThat(barrier.outstanding).isEqualTo(1) // not deregistered by the cancellation
        var cStarted = false
        launch { if (barrier.awaitClear(30_000)) cStarted = true } // session C after reconnection
        runCurrent()
        assertThat(cStarted).isFalse()
        runEndRestore.complete()
        advanceUntilIdle()
        assertThat(log).containsExactly("A teardown") // ran to completion despite the cancel
        assertThat(reconciled.isCompleted && !reconciled.isCancelled).isTrue()
        assertThat(cStarted).isTrue() // only now
        assertThat(barrier.outstanding).isEqualTo(0)
    }

    @Test
    fun `replacement - a teardown cancelled before it started fails closed`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val scope = cancellableScope()
        var shutdownRan = false
        val failures = mutableListOf<Throwable>()
        val reconciled = launchReplacedSessionTeardown(
            scope = scope,
            runEndRestore = null,
            shutdown = { shutdownRan = true },
            isTornDown = { shutdownRan },
            stopPlatform = {},
            onFailure = { failures += it },
        )
        barrier.add(reconciled)
        scope.cancel() // before the body was dispatched
        advanceUntilIdle()
        assertThat(shutdownRan).isFalse()
        assertThat(reconciled.isCancelled).isTrue() // failed, not "reconciled"
        assertThat(failures).hasSize(1) // surfaced (AgentService logs it)
        assertThat(barrier.awaitClear(30_000)).isFalse()
        assertThat(barrier.outstanding).isEqualTo(1)
    }

    @Test
    fun `replacement - a shutdown that returns without stopping the platform stops it, or fails`() = runTest {
        // The old session was already claimed by a shutdown that never reached platform stop:
        // its Shutdown op returns at once. The platform is stopped directly (idempotent).
        val log = mutableListOf<String>()
        var stopped = false
        val forced = launchReplacedSessionTeardown(
            scope = this, // test scope: advanceUntilIdle runs it (backgroundScope work it would not)
            runEndRestore = null,
            shutdown = { log += "shutdown (already claimed)" },
            isTornDown = { stopped },
            stopPlatform = { log += "forced stop"; stopped = true },
        )
        advanceUntilIdle()
        assertThat(log).containsExactly("shutdown (already claimed)", "forced stop").inOrder()
        assertThat(forced.isCompleted && !forced.isCancelled).isTrue()

        // The stop itself fails: never reconciled, the barrier keeps it.
        val barrier = ReplacedSessionShutdownBarrier()
        val failures = mutableListOf<Throwable>()
        val failed = launchReplacedSessionTeardown(
            scope = this, // test scope: advanceUntilIdle runs it (backgroundScope work it would not)
            runEndRestore = null,
            shutdown = {},
            isTornDown = { false },
            stopPlatform = { throw IllegalStateException("release failed") },
            onFailure = { failures += it },
        )
        barrier.add(failed)
        advanceUntilIdle()
        assertThat(failed.isCancelled).isTrue()
        assertThat(failures.single()).hasMessageThat().isEqualTo("release failed")
        assertThat(barrier.awaitClear(30_000)).isFalse()
        assertThat(barrier.outstanding).isEqualTo(1)
    }

    // ── Gate fix round 4: the destroy path (AgentService.onDestroy) uses the same registered teardown ──
    // onDestroy itself is Android-bound (AccessibilityService); it calls shutdownReplacedSession,
    // whose registration + teardown is launchRegisteredSessionTeardown, plus the observe-only
    // watchSessionTeardown for the former 5 s bound. These tests drive those two helpers.

    @Test
    fun `destroy - the attached session's teardown is registered before detach and outlives the 5 s bound`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val teardownScope = cancellableScope()
        val checkpoint = Job() // A's teardown suspended in checkpointing / a display read
        val log = mutableListOf<String>()
        var stopped = false
        val reconciled = launchRegisteredSessionTeardown(
            barrier = barrier,
            scope = teardownScope,
            mustAwait = true, // A holds deferred Shelly tasks
            owner = Any(),
            runEndRestore = null,
            shutdown = { checkpoint.join(); log += "A teardown"; stopped = true },
            isTornDown = { stopped },
            stopPlatform = { log += "forced stop"; stopped = true },
        )
        // Registered synchronously, i.e. before onDestroy detaches the session.
        assertThat(barrier.outstanding).isEqualTo(1)
        var timedOut = false
        watchSessionTeardown(cancellableScope(), reconciled, timeoutMs = 5_000) { timedOut = true }
        runCurrent() // A's teardown is inside its critical section, suspended
        teardownScope.cancel() // the service is destroyed
        var cStarted = false
        launch { if (barrier.awaitClear(30_000)) cStarted = true } // session C of the restarted service
        advanceTimeBy(5_001)
        runCurrent()
        assertThat(timedOut).isTrue() // the 5 s bound only logs ...
        assertThat(reconciled.isActive).isTrue() // ... it did not abandon the teardown
        assertThat(cStarted).isFalse() // C does not overlap A's teardown
        assertThat(log).isEmpty()
        checkpoint.complete()
        advanceUntilIdle()
        assertThat(log).containsExactly("A teardown")
        assertThat(reconciled.isCompleted && !reconciled.isCancelled).isTrue()
        assertThat(cStarted).isTrue() // only once A's platform is stopped
        assertThat(barrier.outstanding).isEqualTo(0)
    }

    @Test
    fun `destroy - a teardown cancelled before it started stays registered and starts fail closed`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        val scope = cancellableScope()
        var shutdownRan = false
        val reconciled = launchRegisteredSessionTeardown(
            barrier = barrier,
            scope = scope,
            mustAwait = true,
            owner = Any(),
            runEndRestore = null,
            shutdown = { shutdownRan = true },
            isTornDown = { shutdownRan },
            stopPlatform = {},
        )
        assertThat(barrier.outstanding).isEqualTo(1)
        scope.cancel() // before the body was dispatched
        advanceUntilIdle()
        assertThat(shutdownRan).isFalse()
        assertThat(reconciled.isCancelled).isTrue()
        assertThat(barrier.awaitClear(30_000)).isFalse()
        assertThat(currentTime).isLessThan(30_000L) // right away
        assertThat(barrier.outstanding).isEqualTo(1)
    }

    @Test
    fun `destroy - only a task-moving session is registered, and a failed teardown keeps its entry`() = runTest {
        val barrier = ReplacedSessionShutdownBarrier()
        // A session that never touches display-0 tasks: torn down, not waited for (as before).
        var stopped = false
        val plain = launchRegisteredSessionTeardown(
            barrier = barrier,
            scope = this, // test scope: advanceUntilIdle runs it
            mustAwait = false,
            owner = Any(),
            runEndRestore = null,
            shutdown = { stopped = true },
            isTornDown = { stopped },
            stopPlatform = {},
        )
        assertThat(barrier.outstanding).isEqualTo(0)
        var timedOut = false
        watchSessionTeardown(this, plain, timeoutMs = 5_000) { timedOut = true }
        advanceUntilIdle()
        assertThat(stopped).isTrue()
        assertThat(plain.isCompleted && !plain.isCancelled).isTrue()
        assertThat(timedOut).isFalse()
        assertThat(barrier.awaitClear(30_000)).isTrue()

        // A task-moving session whose platform stop throws: never reconciled, the entry stays.
        val failed = launchRegisteredSessionTeardown(
            barrier = barrier,
            scope = this,
            mustAwait = true,
            owner = Any(),
            runEndRestore = null,
            shutdown = {},
            isTornDown = { false },
            stopPlatform = { throw IllegalStateException("release failed") },
        )
        advanceUntilIdle()
        assertThat(failed.isCancelled).isTrue()
        assertThat(barrier.awaitClear(30_000)).isFalse()
        assertThat(barrier.outstanding).isEqualTo(1)
    }

    // ── Gate fix round 2: the start choke point is AgentSession's platform start (every path) ──

    /**
     * A session as MainActivity / onboarding / AgentService create it (AgentSession), whose
     * platform start is recorded and then fails, so the test ends at the platform start without
     * running an agent.
     */
    private fun sessionRecordingPlatformStart(scope: kotlinx.coroutines.CoroutineScope, log: MutableList<String>):
            Pair<ai.closepaw.session.AgentSession, ai.closepaw.platform.AndroidPlatform> {
        val platform = mockk<ai.closepaw.platform.AndroidPlatform>(relaxed = true)
        coEvery { platform.start() } answers {
            log += "platform start"
            throw IllegalStateException("test: stop at the platform start")
        }
        val services = mockk<ai.closepaw.session.SessionServices>(relaxed = true)
        every { services.platform } returns platform
        val session = ai.closepaw.session.AgentSession.createWithServices(
            config = ai.closepaw.protocol.SessionConfig(),
            service = mockk<android.accessibilityservice.AccessibilityService>(relaxed = true),
            scope = scope,
            services = services,
        )
        return session to platform
    }

    @Test
    fun `wiring - a session's first task does not start its platform while a replaced teardown is running`() = runTest {
        val log = mutableListOf<String>()
        // E.g. MainActivity created this session (observeExternalSession) while a Shelly
        // replacement's old teardown is still restoring / handing over display-0 tasks.
        val (session, _) = sessionRecordingPlatformStart(backgroundScope, log)
        val oldTeardown = Job()
        ReplacedSessionShutdownBarrier.process.add(oldTeardown, owner = Any())
        try {
            val firstTask = launch { session.submit(ai.closepaw.protocol.Op.UserInput(text = "goal")) }
            runCurrent()
            assertThat(log).isEmpty() // the platform has not started: no task can run yet
            assertThat(firstTask.isCompleted).isFalse()
            log += "old teardown completed"
            oldTeardown.complete()
            advanceUntilIdle()
            firstTask.join()
            assertThat(log).containsExactly("old teardown completed", "platform start").inOrder()
        } finally {
            oldTeardown.complete()
        }
    }

    @Test
    fun `wiring - a start whose wait times out fails without starting the platform`() = runTest {
        val log = mutableListOf<String>()
        val (session, _) = sessionRecordingPlatformStart(backgroundScope, log)
        val stuckTeardown = Job()
        ReplacedSessionShutdownBarrier.process.add(stuckTeardown, owner = Any())
        try {
            session.submit(ai.closepaw.protocol.Op.UserInput(text = "goal")) // waits the bound out
            assertThat(log).isEmpty() // failed (bootstrap failure path): never started
            assertThat(session.state.value).isEqualTo(ai.closepaw.protocol.SessionState.Created)
            assertThat(ReplacedSessionShutdownBarrier.process.outstanding).isEqualTo(1) // still registered
        } finally {
            stuckTeardown.complete()
        }
    }

    @Test
    fun `wiring - a cancelled replaced teardown fails the start instead of permitting it`() = runTest {
        val log = mutableListOf<String>()
        val (session, _) = sessionRecordingPlatformStart(backgroundScope, log)
        val cancelledTeardown = Job()
        ReplacedSessionShutdownBarrier.process.add(cancelledTeardown, owner = Any())
        try {
            cancelledTeardown.cancel() // e.g. ended before its teardown ran
            session.submit(ai.closepaw.protocol.Op.UserInput(text = "goal"))
            assertThat(log).isEmpty() // the platform never started
            assertThat(session.state.value).isEqualTo(ai.closepaw.protocol.SessionState.Created)
            assertThat(ReplacedSessionShutdownBarrier.process.outstanding).isEqualTo(1)
        } finally {
            ReplacedSessionShutdownBarrier.process.discardForTest(cancelledTeardown)
        }
    }

    @Test
    fun `wiring - a session never waits for its own registered teardown`() = runTest {
        val log = mutableListOf<String>()
        val (session, platform) = sessionRecordingPlatformStart(backgroundScope, log)
        val ownTeardown = Job()
        ReplacedSessionShutdownBarrier.process.add(ownTeardown, owner = platform)
        try {
            session.submit(ai.closepaw.protocol.Op.UserInput(text = "goal"))
            assertThat(log).containsExactly("platform start")
        } finally {
            ownTeardown.complete()
        }
    }

    @Test
    fun `deferred return - reconciling without a deferral leaves a build-58 retry to teardown`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        launchShellyOnVd(r, d)
        d.failMoves += shelly.taskId
        r.restore(vdId) // untouched display 0: immediate restore, Shelly's move fails
        assertThat(r.hasDeferredRestore()).isFalse()
        d.failMoves.clear()
        r.restoreDeferred(vdId) // new run / screen off: nothing deferred, nothing done
        assertThat(d.log).isEmpty()
        assertThat(r.protectedTaskIds()).contains(shelly.taskId)
    }

    @Test
    fun `deferred return - teardown while deferred degrades to the build-58 restore and handover`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        assertThat(r.hasDeferredRestore()).isTrue()
        // stop(): the restore executes the deferral; Shelly's move fails, so the release hands it over.
        d.failMoves += shelly.taskId
        r.restore(vdId)
        assertThat(r.hasDeferredRestore()).isFalse()
        val handover = r.prepareHandover(vdId)
        assertThat(handover).isEqualTo(MainDisplayHandover(setOf(shelly.taskId), MainDisplayRefront.Task(mail.taskId)))
        d.release() // Shelly lands on display 0's top
        r.refrontAfterHandover(handover)
        assertThat(d.log).containsExactly("refront ${mail.taskId}") // one landing, one refront
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(d.main).contains(shelly)
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    @Test
    fun `deferred return - a dropped deferral becomes an ordinary retry that teardown completes`() = runTest {
        val d = FakeDisplays(listOf(shelly, chrome, home))
        val r = MainDisplayTaskRestorer(fakeShizuku(d), setOf(launcher))
        displaceShellyUserOpensMail(r, d)
        r.restoreAtRunEnd(vdId)
        r.dropDeferral() // no trigger available (receiver registration failed / reconcile read failed)
        assertThat(r.hasDeferredRestore()).isFalse()
        assertThat(r.protectedTaskIds()).contains(shelly.taskId) // still kept by the VD cleanup
        r.restoreDeferred(vdId) // nothing deferred any more: no-op
        assertThat(d.log).isEmpty()
        r.restore(vdId) // the fallback / teardown restore executes the frozen plan
        assertThat(d.log).containsExactly("move ${shelly.taskId}", "refront ${mail.taskId}").inOrder()
        assertThat(d.main.first()).isEqualTo(mail)
        assertThat(r.protectedTaskIds()).isEmpty()
    }

    // ---- MCP intake: post-and-await with abandon/reconcile on timeout -------------------------

    @Test
    fun `mcp main call - a block posted but not yet run at timeout never executes`() {
        val queued = mutableListOf<Runnable>()
        val call = McpMainCall(post = { queued += it }, onTargetThread = { false }, timeoutMs = 50)
        var ran = false
        val result = call.call<String> { ran = true; "started" }
        assertThat(result).isNull()
        queued.forEach { it.run() } // the looper finally gets to it
        assertThat(ran).isFalse()
    }

    @Test
    fun `mcp main call - a block that completes after the timeout has its created run reconciled`() {
        val registry = McpRunRegistry()
        val guards = java.util.concurrent.ConcurrentHashMap<String, String>()
        val stopped = mutableListOf<String>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var worker: Thread? = null
        val call = McpMainCall(
            post = { r -> worker = Thread(r).also { it.start() } },
            onTargetThread = { false },
            timeoutMs = 300,
        )
        val result = call.call<String>(
            reconcile = { handle -> reconcileLateStart(handle, registry, guards) { stopped += it } },
        ) {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val handle = registry.issue()
            guards[handle] = "guard-$handle"
            registry.markRunning(handle)
            handle
        }
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        assertThat(result).isNull() // the client is told TIMEOUT
        release.countDown()
        worker!!.join(5_000)

        assertThat(registry.activeCount()).isEqualTo(0)
        assertThat(guards).isEmpty()
        assertThat(stopped).hasSize(1)
        val handle = stopped.single().removePrefix("guard-")
        val snap = registry.snapshot(handle)!!
        assertThat(snap.status).isEqualTo(McpRunRegistry.Status.CANCELLED)
        assertThat(snap.summary).contains("timed out")
    }

    @Test
    fun `mcp main call - reconcile of an already finished run is harmless`() {
        val registry = McpRunRegistry()
        val guards = java.util.concurrent.ConcurrentHashMap<String, String>()
        val handle = registry.issue()
        registry.markRunning(handle)
        registry.complete(handle, McpRunRegistry.Status.COMPLETED, "done")
        reconcileLateStart(handle, registry, guards) { error("no guard, nothing to stop") }
        assertThat(registry.snapshot(handle)!!.status).isEqualTo(McpRunRegistry.Status.COMPLETED)
    }

    @Test
    fun `mcp main call - the normal path returns the block result unchanged and does not reconcile`() {
        var reconciled = false
        val call = McpMainCall(
            post = { r -> Thread(r).start() },
            onTargetThread = { false },
            timeoutMs = 5_000,
        )
        val result = call.call<String>(reconcile = { reconciled = true }) { "run-1" }
        assertThat(result).isEqualTo("run-1")
        assertThat(reconciled).isFalse()
    }

    @Test
    fun `mcp main call - a block failure is rethrown and on the target thread the block runs inline`() {
        val failing = McpMainCall(post = { r -> Thread(r).start() }, onTargetThread = { false }, timeoutMs = 5_000)
        val thrown = runCatching { failing.call<String> { throw IllegalStateException("boom") } }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)

        val inline = McpMainCall(post = { error("must not post") }, onTargetThread = { true }, timeoutMs = 1)
        assertThat(inline.call<String> { "inline" }).isEqualTo("inline")
    }
}
