package ai.closepaw.platform.virtualdisplay

/**
 * One root task as seen on a display. [packageName] is the top (else base) activity's package;
 * null for empty/organizer-created root tasks, which are never treated as user tasks.
 */
internal data class DisplayTask(
        val taskId: Int,
        val packageName: String?,
        /** Picture-in-picture: always on top, never "the app the user is in". */
        val pinned: Boolean = false,
)

/** What to bring to the front of the main display after the displaced tasks went back. */
internal sealed interface MainDisplayRefront {
    /** The pre-run foreground is restored by the restore order itself. */
    data object None : MainDisplayRefront
    /** The user is on the home screen: return there. */
    data object Home : MainDisplayRefront
    /** The user has another app focused: bring that task back over the restored ones. */
    data class Task(val taskId: Int) : MainDisplayRefront
}

internal data class MainDisplayRestorePlan(
        /** Tasks to move back to the main display, in order (each lands on top; last = top-most). */
        val restoreTaskIds: List<Int>,
        val refront: MainDisplayRefront,
) {
    companion object {
        val NOTHING = MainDisplayRestorePlan(emptyList(), MainDisplayRefront.None)
    }
}

/**
 * Pure bookkeeping that keeps a Shelly background run from disturbing the main display.
 *
 * Why it exists: launching an app on the virtual display (`am start --display <vd>`) reuses the
 * app's existing task. If that task lives on display 0, Android MOVES it to the virtual display,
 * and display 0 falls back to whatever was behind it (typically the launcher). For a
 * standard-launch app this is avoided up front with FLAG_ACTIVITY_MULTIPLE_TASK
 * ([shouldLaunchInNewTask]); a singleTask / singleInstance activity (e.g. Shelly's MainActivity)
 * ignores that flag, so its task is unavoidably displaced while the agent operates it. This guard
 * remembers which tasks came from display 0 and plans their return at run end.
 *
 * Not thread-safe; the Android side ([MainDisplayTaskRestorer]) serializes access.
 */
internal class MainDisplayTaskGuard(private val homePackages: Set<String>) {

    private data class Seen(val sequence: Long, val zIndex: Int)

    /** Tasks seen on display 0: latest snapshot that contained them and their z-index there. */
    private val seenOnMain = HashMap<Int, Seen>()
    private var sequence = 0L

    /** The task that was display 0's foreground when a launch displaced it. */
    private var displacedTopTaskId: Int? = null

    /** Display 0's foreground right after that displacement (what the run left behind). */
    private var revealedTopTaskId: Int? = null
    private var revealedTopPackage: String? = null

    /** The user brought some other app to display 0's foreground after the displacement. */
    private var mainTouched = false

    /**
     * The restore as first planned: its move order and, above all, its intended final foreground
     * ([MainDisplayRestorePlan.refront]). Frozen at the first [plan] of a displacement cycle and
     * replayed by every retry until the moves AND the refront have succeeded ([completeRestore]),
     * so a partial restore cannot change what ends on top (a restored task now on display 0's
     * top is our own doing, not the user's choice).
     */
    private var pendingRestore: MainDisplayRestorePlan? = null

    /** Task ids of the last plan handed out (what [completeRestore] reports on). */
    private var lastPlannedTaskIds: List<Int> = emptyList()

    /**
     * Set once a restore has been planned: from then on display-0 foreground events are (or may
     * be) produced by our own moves / refront, so they no longer count as user actions. Cleared
     * by the next displacement, which starts a new cycle.
     */
    private var restoreStarted = false

    /** There is something left to restore: displaced candidates, or a refront still pending. */
    val hasPendingWork: Boolean get() = seenOnMain.isNotEmpty() || pendingRestore != null

    /** Whether display 0 counts as touched by the user since the displacement (for tests/logs). */
    val userTouchedMainDisplay: Boolean get() = mainTouched

    /** Record a display-0 snapshot (top-most first). */
    fun observeMain(main: List<DisplayTask>) {
        sequence++
        appTasks(main).forEachIndexed { index, task -> seenOnMain[task.taskId] = Seen(sequence, index) }
    }

    /**
     * After a launch: if display 0's foreground task ([before]'s top) is now on the virtual
     * display ([vdAfter]), remember it and what display 0 showed instead ([mainAfter]).
     */
    fun onLaunched(before: List<DisplayTask>, mainAfter: List<DisplayTask>, vdAfter: List<DisplayTask>) {
        val beforeTop = topOf(before) ?: return
        if (vdAfter.none { it.taskId == beforeTop.taskId }) return
        displacedTopTaskId = beforeTop.taskId
        val revealed = topOf(mainAfter)
        revealedTopTaskId = revealed?.taskId
        revealedTopPackage = revealed?.packageName
        mainTouched = false
        // A new displacement starts a new cycle: an earlier frozen intent no longer describes
        // display 0, so the next plan is computed afresh (still from every remembered candidate).
        pendingRestore = null
        restoreStarted = false
    }

    /**
     * An activity window of [packageName] came to the foreground on display 0 (accessibility
     * event). Anything other than what the displacement revealed means the user acted there, even
     * if they later returned to the same screen (e.g. Home → Chrome → Home).
     */
    fun onMainDisplayForeground(packageName: String?) {
        // Once the restore has begun, display-0 foreground changes are our own moves / refront.
        if (restoreStarted) return
        if (displacedTopTaskId == null || packageName.isNullOrBlank()) return
        if (packageName != revealedTopPackage) mainTouched = true
    }

    /** Tasks that may be the user's and must not be destroyed by the virtual-display cleanup. */
    val protectedTaskIds: Set<Int> get() = seenOnMain.keys.toSet()

    /** Drop tasks that are back on display 0; the rest stay protected for a retry. */
    private fun forget(taskIds: Collection<Int>) {
        taskIds.forEach { seenOnMain.remove(it) }
        if (displacedTopTaskId in taskIds) {
            displacedTopTaskId = null
            revealedTopTaskId = null
            revealedTopPackage = null
            mainTouched = false
        }
    }

    /**
     * Plan the restore: every task now on the virtual display ([vdNow]) that was seen on display 0
     * goes back. Display 0's foreground is left exactly as the user has it ([mainNow]): if they
     * have not touched display 0 since the displacement, the displaced foreground task ends on
     * top again; otherwise their current foreground (home or another app) is brought back over
     * the restored tasks.
     *
     * The first plan of a cycle is frozen; while it is pending, a retry returns the moves still
     * to do (in the original order) and the ORIGINAL refront, whatever display 0 shows now. An
     * original [MainDisplayRefront.None] ("the last restored task ends on top") becomes an
     * explicit refront of that task when it was already restored and the retry would cover it.
     * Report the outcome with [completeRestore].
     */
    fun plan(vdNow: List<DisplayTask>, mainNow: List<DisplayTask>): MainDisplayRestorePlan {
        val pending = pendingRestore
        val result = if (pending == null) {
            freshPlan(vdNow, mainNow).also { if (it != MainDisplayRestorePlan.NOTHING) pendingRestore = it }
        } else {
            val remaining = movableCandidates(vdNow).map { it.first }.toSet()
            val stillToMove = pending.restoreTaskIds.filter { it in remaining }
            // Candidates displaced after the plan was frozen (background moves) go first, so the
            // original order still decides what ends on top.
            val extra = movableCandidates(vdNow)
                    .filter { it.first !in pending.restoreTaskIds }
                    .sortedWith(compareBy<Pair<Int, Seen>>({ it.second.sequence }, { -it.second.zIndex }))
                    .map { it.first }
            val moves = extra + stillToMove
            // A frozen None means "the original plan's last task ends on top". If it is already
            // back and this retry moves other tasks over it, say so explicitly, and keep that
            // explicit refront pending (a refront-only retry must not fall back to None).
            val intendedTop = pending.restoreTaskIds.last()
            if (pending.refront == MainDisplayRefront.None && moves.isNotEmpty() &&
                    moves.last() != intendedTop) {
                pendingRestore = pending.copy(refront = MainDisplayRefront.Task(intendedTop))
            }
            MainDisplayRestorePlan(moves, pendingRestore!!.refront)
        }
        if (pendingRestore != null) restoreStarted = true
        lastPlannedTaskIds = result.restoreTaskIds
        return result
    }

    /**
     * Outcome of executing the last [plan]: [moved] = tasks now back on display 0, [refronted] =
     * the plan's refront succeeded (true for [MainDisplayRefront.None]). Moved tasks are
     * forgotten; everything is cleared only when every planned move AND the refront succeeded.
     * Otherwise the failed moves stay protected and the frozen refront stays pending for the
     * next [plan] (e.g. at teardown).
     */
    fun completeRestore(moved: Collection<Int>, refronted: Boolean) {
        if (pendingRestore == null) return
        forget(moved)
        if (refronted && moved.containsAll(lastPlannedTaskIds)) reset()
    }

    /**
     * Remembered display-0 tasks now on the virtual display, matched by task id: [observeMain]
     * only records app tasks, so a remembered id is the user's task even when its package is
     * momentarily unknown (no top activity); dropping it would let a reset strip its protection.
     */
    private fun movableCandidates(vdNow: List<DisplayTask>): List<Pair<Int, Seen>> =
            vdNow.filter { it.packageName !in homePackages }
                    .mapNotNull { task -> seenOnMain[task.taskId]?.let { task.taskId to it } }

    private fun freshPlan(vdNow: List<DisplayTask>, mainNow: List<DisplayTask>): MainDisplayRestorePlan {
        val restore = movableCandidates(vdNow)
        if (restore.isEmpty()) return MainDisplayRestorePlan.NOTHING

        // Oldest observations first, deeper tasks first; the displaced foreground task last.
        val ordered = restore
                .sortedWith(compareBy<Pair<Int, Seen>>({ it.second.sequence }, { -it.second.zIndex }))
                .map { it.first }
                .let { ids ->
                    val top = displacedTopTaskId
                    if (top != null && top in ids) ids - top + top else ids
                }

        val currentTop = topOf(mainNow)
        val untouched = displacedTopTaskId in ordered && !mainTouched &&
                currentTop?.taskId == revealedTopTaskId
        val refront = when {
            untouched -> MainDisplayRefront.None
            currentTop == null -> MainDisplayRefront.None
            currentTop.packageName in homePackages -> MainDisplayRefront.Home
            else -> MainDisplayRefront.Task(currentTop.taskId)
        }
        return MainDisplayRestorePlan(ordered, refront)
    }

    /** Forget everything (after a complete restore). */
    fun reset() {
        seenOnMain.clear()
        displacedTopTaskId = null
        revealedTopTaskId = null
        revealedTopPackage = null
        mainTouched = false
        pendingRestore = null
        lastPlannedTaskIds = emptyList()
        restoreStarted = false
    }

    companion object {
        /**
         * Launch in a NEW task (FLAG_ACTIVITY_MULTIPLE_TASK) when the app has a task on display 0
         * but none on the virtual display yet: the user's task stays where it is. With a task
         * already on the virtual display, reuse it (the agent's in-app state lives there).
         */
        fun shouldLaunchInNewTask(
                packageName: String,
                main: List<DisplayTask>,
                vd: List<DisplayTask>,
        ): Boolean = vd.none { it.packageName == packageName } && main.any { it.packageName == packageName }

        private fun appTasks(tasks: List<DisplayTask>): List<DisplayTask> =
                tasks.filter { it.packageName != null && !it.pinned }

        private fun topOf(tasks: List<DisplayTask>): DisplayTask? = appTasks(tasks).firstOrNull()
    }
}
