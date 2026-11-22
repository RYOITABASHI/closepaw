package ai.closepaw.platform.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android side of [MainDisplayTaskGuard]: snapshots display 0 / the virtual display around each
 * agent app launch and returns displaced tasks to display 0. Enabled only for Shelly background
 * runs ([ai.closepaw.protocol.SessionConfig.preserveMainDisplayTasks]); every failure is logged
 * and swallowed (the run itself must not depend on it), except that [reconcileBeforeRun] reports
 * a deferred return it could not complete, so a new run is not started on that VD.
 *
 * [mutex] orders launches against restores; `synchronized(guard)` protects the guard's memory,
 * which is also fed from the accessibility event thread ([onMainDisplayForeground]).
 */
/** What a VD release will hand to display 0 ([taskIds]) and the refront owed afterwards. */
internal data class MainDisplayHandover(val taskIds: Set<Int>, val refront: MainDisplayRefront) {
    companion object {
        val NONE = MainDisplayHandover(emptySet(), MainDisplayRefront.None)
    }
}

/** Outcome of [MainDisplayTaskRestorer.reconcileBeforeRun]. */
internal enum class MainDisplayReconcileOutcome {
    /** No deferred task was owed: nothing to reconcile. */
    NOTHING_PENDING,
    /** Every deferred task is confirmed off the virtual display (moved back, or gone). */
    RESTORED,
    /** A deferred task is still on the virtual display, or the VD could not be read. */
    FAILED,
}

internal class LaunchRefusedException(val reason: String) : Exception(reason)

internal class MainDisplayTaskRestorer(
        private val shizuku: ShizukuClient,
        homePackages: Set<String>,
        /** Builds the no-animation ActivityOptions (needs a Context); null in tests. */
        private val context: Context? = null,
        private val strictIsolation: Boolean = false,
) {
    companion object {
        private const val TAG = "MainDisplayTasks"
        private const val MAIN = Display.DEFAULT_DISPLAY
        /** FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_NO_ANIMATION (0x10000), for `am -f`. */
        private const val FLAG_NEW_TASK_NO_ANIMATION = 0x10000000 or 0x10000
        /** Bounded wait for VD-release task reparenting before the handover refront (~1.5 s). */
        private const val HANDOVER_POLLS = 30
        private const val HANDOVER_POLL_MS = 50L
        /** Bounded retry of the reconcile a new run needs before it may use the VD. */
        private const val RECONCILE_ATTEMPTS = 3
        private const val RECONCILE_RETRY_MS = 250L
    }

    private val guard = MainDisplayTaskGuard(homePackages)
    private val mutex = Mutex()

    /**
     * Wrap one launch of [packageName] on [vdDisplayId]. [launch] receives whether to force a new
     * task (FLAG_ACTIVITY_MULTIPLE_TASK) so the user's display-0 task is not moved.
     */
    suspend fun <T> aroundLaunch(
            packageName: String,
            vdDisplayId: Int,
            launchGate: ((List<DisplayTask>) -> String?)? = null,
            launch: suspend (newTask: Boolean) -> T,
    ): T =
            mutex.withLock {
                val initialMain = withContext(Dispatchers.IO) { shizuku.rootTasksOnDisplay(MAIN) }
                if (launchGate != null && initialMain == null) {
                    throw LaunchRefusedException("Could not verify main-display tasks for an isolated launch")
                }
                val vdBefore = withContext(Dispatchers.IO) { shizuku.rootTasksOnDisplay(vdDisplayId) }
                val mainBefore = if (launchGate != null) {
                    withContext(Dispatchers.IO) { shizuku.rootTasksOnDisplay(MAIN) }
                        ?: throw LaunchRefusedException("Could not revalidate main-display tasks for an isolated launch")
                } else initialMain
                synchronized(guard) {
                    if (mainBefore != null) guard.observeMain(mainBefore)
                    // A failed pre-launch read (null) must reach the guard too: this launch may be
                    // the one that retakes the displaced task, so a stale attribution is cleared.
                    guard.beforeAgentAction(mainBefore)
                }
                val newTask = mainBefore != null && vdBefore != null &&
                        MainDisplayTaskGuard.shouldLaunchInNewTask(packageName, mainBefore, vdBefore)
                if (launchGate != null && mainBefore != null) {
                    launchGate(mainBefore)?.let { throw LaunchRefusedException(it) }
                }
                val result = launch(newTask)
                if (mainBefore != null) {
                    withContext(Dispatchers.IO) {
                        val mainAfter = shizuku.rootTasksOnDisplay(MAIN)
                        val vdAfter = shizuku.rootTasksOnDisplay(vdDisplayId)
                        if (mainAfter != null && vdAfter != null) {
                            synchronized(guard) { guard.onLaunched(mainBefore, mainAfter, vdAfter) }
                        }
                    }
                }
                result
            }

    /**
     * Display-0 activity window came to the foreground (accessibility event thread). Only when the
     * guard cannot decide the event alone (the revealed screen after the user returned to the
     * displaced app, [MainDisplayTaskGuard.needsMainSnapshot]) is display 0 read, right away and
     * outside the guard lock; a failed read leaves the guard's conservative default.
     */
    fun onMainDisplayForeground(packageName: String?) {
        val mainNow = if (synchronized(guard) { guard.needsMainSnapshot(packageName) }) {
            try {
                shizuku.rootTasksOnDisplay(MAIN)
            } catch (e: Exception) {
                Log.w(TAG, "display-0 read for a foreground event failed", e)
                null
            }
        } else {
            null
        }
        synchronized(guard) { guard.onMainDisplayForeground(packageName, mainNow) }
    }

    /**
     * Right BEFORE an agent action is dispatched on the virtual display: while the owner's return
     * to the displaced app is undecided ([MainDisplayTaskGuard.needsActionSnapshot]) read display
     * 0, so a later reveal of the home screen can be attributed to this action only if the owner
     * was still in that app when it was taken ([MainDisplayTaskGuard.beforeAgentAction]). Reads
     * nothing otherwise; a failed read clears the record (a later reveal then counts as moved on).
     */
    suspend fun beforeAgentAction() {
        if (!synchronized(guard) { guard.needsActionSnapshot() }) return
        val mainBefore = withContext(Dispatchers.IO) {
            try {
                shizuku.rootTasksOnDisplay(MAIN)
            } catch (e: Exception) {
                Log.w(TAG, "display-0 read before an agent action failed", e)
                null
            }
        }
        synchronized(guard) { guard.beforeAgentAction(mainBefore) }
    }

    /** Tasks the virtual-display cleanup must not remove (displaced, not yet moved back). */
    fun protectedTaskIds(): Set<Int> = synchronized(guard) { guard.protectedTaskIds }

    /** A deferred restore is pending ([restoreAtRunEnd]); in memory only, never persisted. */
    fun hasDeferredRestore(): Boolean = synchronized(guard) { guard.isDeferred }

    /** See [MainDisplayTaskGuard.dropDeferral]: the deferral degrades to a build-58 pending retry. */
    fun dropDeferral() {
        synchronized(guard) { guard.dropDeferral() }
    }

    /** The deferred tasks in their frozen move order (for tests and logs). */
    fun deferredTaskIds(): List<Int> = synchronized(guard) { guard.deferredTaskIds }

    /**
     * Run end (build 59): like [restore], except that when the user has moved on (the plan owes a
     * refront) the displaced tasks are NOT moved now: the restore is deferred
     * ([MainDisplayTaskGuard.planOrDefer]) and the tasks stay on the virtual display until
     * [restoreDeferred] (screen off, a new run) or teardown ([restore] + handover) executes it.
     * A plan without a refront (the user has not moved on) is executed immediately, as before.
     */
    suspend fun restoreAtRunEnd(vdDisplayId: Int) = restore(vdDisplayId, mayDefer = true, onlyIfDeferred = false)

    /**
     * Execute a pending deferred restore now: the frozen move order, then the refront recomputed
     * from display 0 at this moment (the retry rule). Deferred tasks the user already pulled back
     * to display 0 are dropped; with none left the deferral just clears (no move, no refront).
     * No-op when nothing is deferred (a build-58 pending retry is left for teardown).
     */
    suspend fun restoreDeferred(vdDisplayId: Int) = restore(vdDisplayId, mayDefer = false, onlyIfDeferred = true)

    /**
     * Re-evaluate a pending deferral without executing it while the user has moved on (viewer
     * closed, screen on): it stays deferred, or clears when no deferred task is left on the VD.
     * No-op when nothing is deferred.
     */
    suspend fun recheckDeferred(vdDisplayId: Int) = restore(vdDisplayId, mayDefer = true, onlyIfDeferred = true)

    /**
     * The run-end return was ABANDONED (its result report did not arrive in time; see
     * RunEndRestoreSequencer): nothing is moved now. Every displaced task stays owed, recorded
     * as unreconciled so the next start's [reconcileBeforeRun] executes the return and confirms
     * it before the new run (a failure fails that start); teardown ([restore] + handover) still
     * places it as well. In memory only, like a deferral.
     */
    fun oweAbandonedRunEndReturn() {
        synchronized(guard) { guard.oweAllUnreconciled() }
    }

    /** Deferred tasks not yet confirmed off the virtual display (see [reconcileBeforeRun]). */
    fun unreconciledTaskIds(): Set<Int> = synchronized(guard) { guard.unreconciledTaskIds }

    /**
     * A new run on the running VD ([VirtualDisplayPlatform.start]) must not begin while a task
     * whose return was deferred is still there. Executes the deferred restore (or, after an
     * earlier failed attempt, its pending retry: the same frozen order, refront from display 0
     * now) and then CONFIRMS by reading the VD that every deferred task is off it; retried a
     * bounded number of times. Reports [MainDisplayReconcileOutcome.NOTHING_PENDING] when no
     * deferred task was owed, [MainDisplayReconcileOutcome.RESTORED] once none is left on the VD
     * (moved back, pulled back by the user, or gone: self-heal), else
     * [MainDisplayReconcileOutcome.FAILED] (a failed read or move after every attempt). A FAILED
     * reconcile stays owed: the next call tries again; teardown restores / hands it over.
     */
    suspend fun reconcileBeforeRun(
            vdDisplayId: Int,
            attempts: Int = RECONCILE_ATTEMPTS,
    ): MainDisplayReconcileOutcome {
        if (vdDisplayId < 0) return MainDisplayReconcileOutcome.NOTHING_PENDING
        // Under the mutex: a run-end restore in progress (holding it while it reads the displays)
        // records its deferral before this decision is made, never after.
        if (mutex.withLock { unreconciledTaskIds().isEmpty() }) return MainDisplayReconcileOutcome.NOTHING_PENDING
        repeat(attempts) { attempt ->
            restore(vdDisplayId)
            if (unreconciledSettled(vdDisplayId)) return MainDisplayReconcileOutcome.RESTORED
            if (attempt < attempts - 1) delay(RECONCILE_RETRY_MS)
        }
        Log.w(TAG, "reconcile: ${unreconciledTaskIds()} still on the virtual display after $attempts attempts")
        return MainDisplayReconcileOutcome.FAILED
    }

    /** Read the VD: true once no unreconciled task is on it (a failed read proves nothing). */
    private suspend fun unreconciledSettled(vdDisplayId: Int): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (unreconciledTaskIds().isEmpty()) return@withContext true
            val vdNow = (try {
                shizuku.rootTasksOnDisplay(vdDisplayId)
            } catch (e: Exception) {
                Log.w(TAG, "reconcile: reading the virtual display failed", e)
                null
            }) ?: return@withContext false
            synchronized(guard) {
                guard.settleUnreconciled(vdNow)
                guard.unreconciledTaskIds.isEmpty()
            }
        }
    }

    /**
     * Return tasks displaced from display 0 by this run, then bring back the intended foreground.
     * Safe on every terminal path and idempotent: the guard freezes the first plan (order and
     * intended foreground) and replays it until every move AND the refront succeeded. A task whose
     * move failed stays protected for the next call (teardown) and is then kept out of the VD task
     * cleanup; a failed refront is retried by the next call as well.
     *
     * With a refront owed, only the plan's top task (the displaced foreground) is moved before it,
     * so at most one task lands on top before the user's foreground is back; the other planned
     * tasks are moved right after, in the same pass, followed by one more refront. Each refront
     * re-reads display 0 first ([MainDisplayTaskGuard.refrontNow]).
     */
    suspend fun restore(vdDisplayId: Int) = restore(vdDisplayId, mayDefer = false, onlyIfDeferred = false)

    private suspend fun restore(vdDisplayId: Int, mayDefer: Boolean, onlyIfDeferred: Boolean) {
        if (vdDisplayId < 0) return
        mutex.withLock {
            if (!synchronized(guard) { guard.hasPendingWork }) return
            if (onlyIfDeferred && !synchronized(guard) { guard.isDeferred }) return
            withContext(Dispatchers.IO) {
                try {
                    val vdNow = shizuku.rootTasksOnDisplay(vdDisplayId) ?: return@withContext
                    val mainNow = shizuku.rootTasksOnDisplay(MAIN) ?: return@withContext
                    val plan = synchronized(guard) {
                        if (mayDefer) guard.planOrDefer(vdNow, mainNow) else guard.plan(vdNow, mainNow)
                    }
                    if (plan == null) {
                        Log.i(TAG, "user moved on: deferring the return of " +
                                "${synchronized(guard) { guard.deferredTaskIds }} to display 0")
                        return@withContext
                    }
                    val ids = plan.restoreTaskIds
                    val phases = if (plan.refront != MainDisplayRefront.None && ids.size > 1) {
                        listOf(listOf(ids.last()), ids.dropLast(1))
                    } else {
                        listOf(ids)
                    }
                    val moved = mutableListOf<Int>()
                    val refronts = mutableListOf<MainDisplayRefront>()
                    // None: the restore order itself leaves the intended task on top.
                    var refronted = plan.refront == MainDisplayRefront.None
                    for (phase in phases) {
                        val phaseMoved = phase.filter { taskId ->
                            try {
                                shizuku.moveRootTaskToDisplay(taskId, MAIN)
                            } catch (e: Exception) {
                                Log.w(TAG, "restore: moving task $taskId failed", e)
                                false
                            }
                        }
                        moved += phaseMoved
                        if (phaseMoved.isNotEmpty() && plan.refront != MainDisplayRefront.None) {
                            refronted = refrontNow(plan.refront, moved, refronts)
                        }
                    }
                    if (ids.isEmpty()) {
                        // Refront-only retry: the moves are done, the foreground is still owed.
                        refronted = plan.refront == MainDisplayRefront.None ||
                                refrontNow(plan.refront, moved, refronts)
                    } else if (moved.isEmpty()) {
                        // Nothing changed on display 0: keep the refront for the retry.
                        refronted = false
                    }
                    synchronized(guard) { guard.completeRestore(moved, refronted) }
                    if (ids.isNotEmpty() || plan.refront != MainDisplayRefront.None) {
                        Log.i(TAG, "restored $moved of $ids to display 0, refront=${plan.refront} " +
                                "executed=$refronts ok=$refronted")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                }
            }
        }
    }

    /**
     * Re-read display 0 right before refronting and follow a user switch since the plan's read
     * (our own moved tasks are skipped); falls back to [planned] when the read fails.
     */
    private fun refrontNow(
            planned: MainDisplayRefront,
            movedInPass: List<Int>,
            executed: MutableList<MainDisplayRefront>,
    ): Boolean {
        val mainNow = shizuku.rootTasksOnDisplay(MAIN)
        if (strictIsolation && mainNow == null) return false
        val target = if (mainNow == null) {
            planned
        } else {
            synchronized(guard) { guard.refrontNow(mainNow, movedInPass, planned) }
        }
        executed += target
        return refront(target)
    }

    /**
     * Teardown, right BEFORE the virtual display is released: which protected tasks the release
     * will hand to display 0 (still on [vdDisplayId], kept by the VD task cleanup), and the
     * refront owed afterwards, decided from display 0 now. Empty [MainDisplayHandover.taskIds] /
     * [MainDisplayRefront.None] = nothing to do.
     */
    suspend fun prepareHandover(vdDisplayId: Int): MainDisplayHandover {
        if (vdDisplayId < 0) return MainDisplayHandover.NONE
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val protectedIds = synchronized(guard) { guard.protectedTaskIds }
                    if (protectedIds.isEmpty()) return@withContext MainDisplayHandover.NONE
                    // Unknown VD contents: assume every protected task is handed over.
                    val onVd = shizuku.rootTasksOnDisplay(vdDisplayId)?.map { it.taskId }?.toSet()
                    val handedOver = if (onVd == null) protectedIds else protectedIds intersect onVd
                    val mainNow = shizuku.rootTasksOnDisplay(MAIN).orEmpty()
                    val refront = synchronized(guard) { guard.handoverRefront(mainNow, handedOver) }
                    if (handedOver.isNotEmpty()) {
                        Log.i(TAG, "handover: release will hand $handedOver to display 0, refront=$refront")
                    }
                    MainDisplayHandover(handedOver, refront)
                } catch (e: Exception) {
                    Log.w(TAG, "handover: planning failed", e)
                    MainDisplayHandover.NONE
                }
            }
        }
    }

    /**
     * Teardown, right AFTER the release: the handed-over tasks land on display 0's top. The
     * release returns before WindowManager has necessarily reparented them (display removal is
     * processed asynchronously), so first wait (bounded) until display 0 shows them. Then the
     * refront is decided again by the same rule ([MainDisplayTaskGuard.handoverRefront], the
     * handed-over tasks skipped) on display 0 as it is NOW, so a user switch during the wait is
     * followed; [prepareHandover]'s decision is the fallback when display 0 cannot be read. The
     * VD is gone, so nothing is left to restore afterwards: the guard is cleared.
     */
    suspend fun refrontAfterHandover(handover: MainDisplayHandover) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (handover.taskIds.isNotEmpty()) {
                        val landed = awaitOnMain(handover.taskIds)
                        val mainNow = shizuku.rootTasksOnDisplay(MAIN)
                        if (strictIsolation && mainNow == null) return@withContext
                        val target = if (mainNow == null) {
                            handover.refront
                        } else {
                            synchronized(guard) { guard.handoverRefront(mainNow, handover.taskIds) }
                        }
                        val ok = refront(target)
                        Log.i(TAG, "handover refront=$target (planned ${handover.refront}) landed=$landed ok=$ok")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "handover: refront failed", e)
                } finally {
                    synchronized(guard) { guard.reset() }
                }
            }
        }
    }

    /** Poll display 0 until every one of [taskIds] is on it, or the wait budget runs out. */
    private suspend fun awaitOnMain(taskIds: Set<Int>): Boolean {
        repeat(HANDOVER_POLLS) { attempt ->
            val onMain = shizuku.rootTasksOnDisplay(MAIN)?.map { it.taskId }?.toSet()
            if (onMain != null && onMain.containsAll(taskIds)) return true
            if (attempt < HANDOVER_POLLS - 1) delay(HANDOVER_POLL_MS)
        }
        Log.w(TAG, "handover: $taskIds not seen on display 0 after ${HANDOVER_POLLS * HANDOVER_POLL_MS} ms; refronting anyway")
        return false
    }

    private fun refront(refront: MainDisplayRefront): Boolean = try {
        when (refront) {
            MainDisplayRefront.None -> true
            MainDisplayRefront.Home -> refrontHome()
            is MainDisplayRefront.Task ->
                shizuku.startActivityFromRecents(refront.taskId, noAnimationOptions(launchDisplayId = null))
        }
    } catch (e: Exception) {
        Log.w(TAG, "restore: refront $refront failed", e)
        false
    }

    /**
     * HOME on display 0: binder startActivity (no process spawn, no animation); the shell
     * `am start` (now with FLAG_ACTIVITY_NO_ANIMATION) only when the binder start fails.
     */
    private fun refrontHome(): Boolean {
        val intent = try {
            Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        } catch (e: Exception) {
            null
        }
        if (intent != null && shizuku.startActivityAsShell(intent, noAnimationOptions(launchDisplayId = MAIN))) {
            return true
        }
        Log.w(TAG, "restore: binder HOME start failed; falling back to am start")
        return shizuku.executeShellCommand(
                arrayOf(
                        "am", "start", "--display", "$MAIN",
                        "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.HOME",
                        "-f", "0x" + Integer.toHexString(FLAG_NEW_TASK_NO_ANIMATION),
                )
        ) == 0
    }

    /** ActivityOptions without a transition animation (null when it cannot be built). */
    private fun noAnimationOptions(launchDisplayId: Int?): Bundle? {
        val ctx = context ?: return null
        return try {
            ActivityOptions.makeCustomAnimation(ctx, 0, 0)
                    .apply { if (launchDisplayId != null) setLaunchDisplayId(launchDisplayId) }
                    .toBundle()
        } catch (e: Exception) {
            Log.w(TAG, "restore: no-animation ActivityOptions unavailable", e)
            null
        }
    }
}
