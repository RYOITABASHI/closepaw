package ai.closepaw.app

import ai.closepaw.R
import ai.closepaw.platform.virtualdisplay.DisplayTask
import ai.closepaw.platform.virtualdisplay.MainDisplayRefront
import ai.closepaw.platform.virtualdisplay.MainDisplayRestorePlan
import ai.closepaw.platform.virtualdisplay.MainDisplayTaskGuard
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
}
