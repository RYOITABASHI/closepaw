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
 * and swallowed (the run itself must not depend on it).
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

internal class MainDisplayTaskRestorer(
        private val shizuku: ShizukuClient,
        homePackages: Set<String>,
        /** Builds the no-animation ActivityOptions (needs a Context); null in tests. */
        private val context: Context? = null,
) {
    companion object {
        private const val TAG = "MainDisplayTasks"
        private const val MAIN = Display.DEFAULT_DISPLAY
        /** FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_NO_ANIMATION (0x10000), for `am -f`. */
        private const val FLAG_NEW_TASK_NO_ANIMATION = 0x10000000 or 0x10000
        /** Bounded wait for VD-release task reparenting before the handover refront (~1.5 s). */
        private const val HANDOVER_POLLS = 30
        private const val HANDOVER_POLL_MS = 50L
    }

    private val guard = MainDisplayTaskGuard(homePackages)
    private val mutex = Mutex()

    /**
     * Wrap one launch of [packageName] on [vdDisplayId]. [launch] receives whether to force a new
     * task (FLAG_ACTIVITY_MULTIPLE_TASK) so the user's display-0 task is not moved.
     */
    suspend fun <T> aroundLaunch(packageName: String, vdDisplayId: Int, launch: suspend (newTask: Boolean) -> T): T =
            mutex.withLock {
                val mainBefore = withContext(Dispatchers.IO) { shizuku.rootTasksOnDisplay(MAIN) }
                val vdBefore = withContext(Dispatchers.IO) { shizuku.rootTasksOnDisplay(vdDisplayId) }
                if (mainBefore != null) synchronized(guard) { guard.observeMain(mainBefore) }
                val newTask = mainBefore != null && vdBefore != null &&
                        MainDisplayTaskGuard.shouldLaunchInNewTask(packageName, mainBefore, vdBefore)
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

    /** Display-0 activity window came to the foreground (accessibility event thread). */
    fun onMainDisplayForeground(packageName: String?) {
        synchronized(guard) { guard.onMainDisplayForeground(packageName) }
    }

    /** Tasks the virtual-display cleanup must not remove (displaced, not yet moved back). */
    fun protectedTaskIds(): Set<Int> = synchronized(guard) { guard.protectedTaskIds }

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
    suspend fun restore(vdDisplayId: Int) {
        if (vdDisplayId < 0) return
        mutex.withLock {
            if (!synchronized(guard) { guard.hasPendingWork }) return
            withContext(Dispatchers.IO) {
                try {
                    val vdNow = shizuku.rootTasksOnDisplay(vdDisplayId) ?: return@withContext
                    val mainNow = shizuku.rootTasksOnDisplay(MAIN) ?: return@withContext
                    val plan = synchronized(guard) { guard.plan(vdNow, mainNow) }
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
