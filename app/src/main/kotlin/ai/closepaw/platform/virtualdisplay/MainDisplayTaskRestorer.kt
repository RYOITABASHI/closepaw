package ai.closepaw.platform.virtualdisplay

import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
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
internal class MainDisplayTaskRestorer(
        private val shizuku: ShizukuClient,
        homePackages: Set<String>,
) {
    companion object {
        private const val TAG = "MainDisplayTasks"
        private const val MAIN = Display.DEFAULT_DISPLAY
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
                    val moved = plan.restoreTaskIds.filter { taskId ->
                        try {
                            shizuku.moveRootTaskToDisplay(taskId, MAIN)
                        } catch (e: Exception) {
                            Log.w(TAG, "restore: moving task $taskId failed", e)
                            false
                        }
                    }
                    // Nothing changed on display 0 if every planned move failed: keep the refront
                    // for the retry. Otherwise (incl. a refront-only retry) apply it now, so the
                    // user's foreground is right even while a failed move is still pending.
                    val refronted = if (plan.restoreTaskIds.isNotEmpty() && moved.isEmpty()) {
                        false
                    } else {
                        refront(plan.refront)
                    }
                    synchronized(guard) { guard.completeRestore(moved, refronted) }
                    if (plan.restoreTaskIds.isNotEmpty() || plan.refront != MainDisplayRefront.None) {
                        Log.i(TAG, "restored $moved of ${plan.restoreTaskIds} to display 0, refront=${plan.refront} ok=$refronted")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                }
            }
        }
    }

    private fun refront(refront: MainDisplayRefront): Boolean = try {
        when (refront) {
            MainDisplayRefront.None -> true
            MainDisplayRefront.Home -> shizuku.executeShellCommand(
                    arrayOf(
                            "am", "start", "--display", "$MAIN",
                            "-a", "android.intent.action.MAIN",
                            "-c", "android.intent.category.HOME",
                    )
            ) == 0
            is MainDisplayRefront.Task -> shizuku.startActivityFromRecents(refront.taskId)
        }
    } catch (e: Exception) {
        Log.w(TAG, "restore: refront $refront failed", e)
        false
    }
}
