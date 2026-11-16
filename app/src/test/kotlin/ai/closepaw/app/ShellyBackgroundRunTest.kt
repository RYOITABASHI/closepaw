package ai.closepaw.app

import ai.closepaw.R
import ai.closepaw.platform.virtualdisplay.DisplayTask
import ai.closepaw.platform.virtualdisplay.MainDisplayHandover
import ai.closepaw.platform.virtualdisplay.MainDisplayRefront
import ai.closepaw.platform.virtualdisplay.MainDisplayRestorePlan
import ai.closepaw.platform.virtualdisplay.MainDisplayTaskGuard
import ai.closepaw.platform.virtualdisplay.MainDisplayTaskRestorer
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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
        every { s.rootTasksOnDisplay(vdId) } answers { d.vd.toList() }
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
}
