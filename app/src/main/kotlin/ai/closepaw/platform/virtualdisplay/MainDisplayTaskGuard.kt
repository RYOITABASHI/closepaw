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
    private var displacedTopPackage: String? = null

    /** Display 0's foreground right after that displacement (what the run left behind). */
    private var revealedTopTaskId: Int? = null
    private var revealedTopPackage: String? = null

    /**
     * The user's latest display-0 choice since the displacement is some other app (they moved
     * on). Set by a foreground of a package other than the revealed one; cleared again when the
     * user brings the displaced app itself back to display 0 (build 61: a return to the run's
     * displaced app is the pre-run foreground, not "moved on"); set when a launch later takes the
     * displaced task from display 0's background (the user was elsewhere at that moment).
     */
    private var mainTouched = false

    /**
     * The user's latest display-0 choice was the displaced app itself (they brought it back).
     * While set, a foreground of the revealed screen is ambiguous: the user leaving the app for it,
     * or the agent taking the task back to the VD (which reveals that screen under the user); a
     * display-0 read tells them apart ([needsMainSnapshot]).
     */
    private var returnedToDisplaced = false

    /**
     * While [returnedToDisplaced]: the latest display-0 read taken right BEFORE an agent action
     * ([beforeAgentAction]) that still had the displaced task on display 0 showed it as display
     * 0's top, i.e. the owner was in it when the agent last acted. Only this lets a revealed-screen
     * foreground whose read misses the displaced task count as the agent's retake: the read alone
     * cannot order "owner went Home, then the agent took the task" against "the agent took the
     * task, revealing Home" (both read [Home], displaced absent). Reads without the displaced task
     * leave it unchanged (the earlier departure's attribution stands).
     */
    private var agentActedOnDisplacedTop = false

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
     * Display 0's (filtered) top task the frozen refront was computed from. A retry keeps the
     * frozen refront while display 0 still shows this; a different top is the user's newer choice.
     */
    private var frozenTopTaskId: Int? = null

    /**
     * The user's latest choice observed AFTER the freeze: the refront a retry, handover or
     * pre-refront re-read derived from a filtered top that differed from its reference, and that
     * top. Once set, it replaces the frozen (refront, [frozenTopTaskId]) pair as the reference for
     * later "unchanged top" decisions, so returning to the frozen top later (e.g. Home -> Chrome
     * -> Home) is followed instead of resurrecting the frozen intent. The frozen move order is not
     * affected. Null while display 0's top never differed (untouched semantics kept).
     */
    private var observedChoice: Pair<MainDisplayRefront, Int>? = null

    /** Display 0's (filtered) top the last plan's refront was computed from (in-pass re-reads). */
    private var lastPlanTopTaskId: Int? = null

    /**
     * Tasks this cycle already moved back to display 0 ([completeRestore]). On display 0's top
     * they are our own doing, never the user's choice, so current-top refront decisions skip them.
     */
    private val movedThisCycle = HashSet<Int>()

    /**
     * Set once a restore has been planned: from then on display-0 foreground events are (or may
     * be) produced by our own moves / refront, so they no longer count as user actions. Cleared
     * by the next displacement, which starts a new cycle.
     */
    private var restoreStarted = false

    /**
     * Build 59: the frozen restore is DEFERRED ([planOrDefer]): the user had moved on at run end,
     * so the displaced tasks stay on the virtual display (moving them back would flash them on
     * display 0's top before the refront) until a trigger the user cannot see or that needs the
     * VD (screen off, a new run, teardown) executes the frozen plan via [plan]. In memory only.
     */
    private var deferred = false

    /**
     * Tasks whose return was deferred ([planOrDefer]) and that are not yet confirmed off the
     * virtual display. Unlike [deferred], NOT cleared by executing the deferral ([plan]) or by
     * [dropDeferral]: only a task's confirmed absence from the VD (moved back by us
     * ([completeRestore]), or seen missing from a VD read ([settleUnreconciled] / [plan])) or a
     * [reset] removes it. A new run must not start on the VD while this is non-empty.
     * Invariant: a subset of [seenOnMain]'s keys (so [hasPendingWork] stays true while non-empty).
     */
    private val unreconciled = LinkedHashSet<Int>()

    /** Deferred tasks not yet confirmed off the virtual display (see [unreconciled]). */
    val unreconciledTaskIds: Set<Int> get() = unreconciled.toSet()

    /**
     * An abandoned run-end return: every remembered task is owed (a new run must not start
     * before a VD read confirms it is off the VD; [settleUnreconciled] / [plan] drop the ones
     * that never left display 0). Keeps the invariant: a subset of [seenOnMain]'s keys.
     */
    fun oweAllUnreconciled() {
        unreconciled.addAll(seenOnMain.keys)
    }

    /** Drop unreconciled tasks absent from [vdNow] (they are back on display 0, or gone). */
    fun settleUnreconciled(vdNow: List<DisplayTask>) {
        val onVd = vdNow.map { it.taskId }.toSet()
        unreconciled.retainAll(onVd)
    }

    /** A deferred restore is pending (see [planOrDefer]). */
    val isDeferred: Boolean get() = deferred

    /** The deferred tasks in their frozen move order (empty when nothing is deferred). */
    val deferredTaskIds: List<Int> get() = if (deferred) pendingRestore?.restoreTaskIds.orEmpty() else emptyList()

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
        if (vdAfter.none { it.taskId == beforeTop.taskId }) {
            // Build 61: the cycle's displaced task, which the user had brought back to display 0,
            // taken again from display 0's BACKGROUND (its top, the user's app, stayed): the user
            // was not in it, so their earlier return to it no longer describes display 0.
            val displaced = displacedTopTaskId
            if (!restoreStarted && displaced != null && before.any { it.taskId == displaced } &&
                    vdAfter.any { it.taskId == displaced }) {
                mainTouched = true
                returnedToDisplaced = false
                agentActedOnDisplacedTop = false
            }
            return
        }
        displacedTopTaskId = beforeTop.taskId
        displacedTopPackage = beforeTop.packageName
        val revealed = topOf(mainAfter)
        revealedTopTaskId = revealed?.taskId
        revealedTopPackage = revealed?.packageName
        mainTouched = false
        returnedToDisplaced = false
        agentActedOnDisplacedTop = false
        // A new displacement starts a new cycle: an earlier frozen intent no longer describes
        // display 0, so the next plan is computed afresh (still from every remembered candidate).
        pendingRestore = null
        restoreStarted = false
        movedThisCycle.clear()
        frozenTopTaskId = null
        observedChoice = null
        lastPlanTopTaskId = null
        deferred = false
    }

    /**
     * An activity window of [packageName] came to the foreground on display 0 (accessibility
     * event). Anything other than what the displacement revealed means the user acted there, even
     * if they later returned to the same screen (e.g. Home → Chrome → Home) — except the displaced
     * app itself: the user brought the run's displaced task back to display 0 (launcher, Recents,
     * a notification; singleTask reuse pulls it off the VD), i.e. returned to where they were
     * before the run. If the agent takes it again afterwards (e.g. by tapping it on the virtual
     * display, which no launch snapshot sees), the run end must give it back on top instead of
     * treating the user as moved on (build 61, defect 2: Home refront / deferral stranded them).
     *
     * After such a return, a foreground of the revealed screen is decided by [mainNow] (display
     * 0 read right after the event, see [needsMainSnapshot]): the displaced task still on display
     * 0 = the user left it for that screen (moved on). Gone from display 0 is NOT enough on its
     * own: the read comes after the event (delivery + read latency), so "the owner went Home, then
     * the agent took the task" reads the same as "the agent took the task, revealing Home". It
     * counts as the agent's retake (not a choice) only when the last agent action was taken while
     * the displaced task was display 0's top ([beforeAgentAction]); otherwise, and when display 0
     * is unknown (null), it counts as moved on (the pre-build-61 outcome, keeps the deferral).
     */
    fun onMainDisplayForeground(packageName: String?, mainNow: List<DisplayTask>? = null) {
        // Once the restore has begun, display-0 foreground changes are our own moves / refront.
        if (restoreStarted) return
        val displaced = displacedTopTaskId
        if (displaced == null || packageName.isNullOrBlank()) return
        when (packageName) {
            revealedTopPackage -> {
                val agentRetook = mainNow != null && mainNow.none { it.taskId == displaced } &&
                        agentActedOnDisplacedTop
                if (returnedToDisplaced && !agentRetook) {
                    mainTouched = true
                    returnedToDisplaced = false
                    agentActedOnDisplacedTop = false
                }
            }
            displacedTopPackage -> {
                // A fresh return: no agent action has been seen over it yet. (A repeated event of
                // the app the owner is still in keeps what the agent's reads recorded.)
                if (!returnedToDisplaced) agentActedOnDisplacedTop = false
                mainTouched = false
                returnedToDisplaced = true
            }
            else -> {
                mainTouched = true
                returnedToDisplaced = false
                agentActedOnDisplacedTop = false
            }
        }
    }

    /**
     * Whether [beforeAgentAction] needs a display-0 read: only while the owner's return to the
     * displaced app is pending a decision, so the action path reads nothing in the common case.
     */
    fun needsActionSnapshot(): Boolean = !restoreStarted && displacedTopTaskId != null && returnedToDisplaced

    /**
     * Display 0 as read right BEFORE an agent action on the virtual display is dispatched (a tap,
     * key, swipe, or a launch's pre-launch read). Records whether the owner was in the displaced
     * app at that moment; a read without the displaced task on display 0 changes nothing. A failed
     * read (null) clears the record: this action may be the one that takes the task, and the
     * owner's position before it is unknown, so a later reveal counts as moved on.
     */
    fun beforeAgentAction(mainBefore: List<DisplayTask>?) {
        if (!needsActionSnapshot()) return
        val displaced = displacedTopTaskId ?: return
        if (mainBefore == null) {
            agentActedOnDisplacedTop = false
            return
        }
        if (mainBefore.none { it.taskId == displaced }) return
        agentActedOnDisplacedTop = topOf(mainBefore)?.taskId == displaced
    }

    /**
     * Whether [onMainDisplayForeground] for [packageName] needs a display-0 read to be decided
     * (the revealed screen after the user returned to the displaced app); false otherwise, so the
     * event path reads nothing in the common case.
     */
    fun needsMainSnapshot(packageName: String?): Boolean =
            !restoreStarted && displacedTopTaskId != null && returnedToDisplaced &&
                    !packageName.isNullOrBlank() && packageName == revealedTopPackage

    /** Tasks that may be the user's and must not be destroyed by the virtual-display cleanup. */
    val protectedTaskIds: Set<Int> get() = seenOnMain.keys.toSet()

    /** Drop tasks that are back on display 0; the rest stay protected for a retry. */
    private fun forget(taskIds: Collection<Int>) {
        taskIds.forEach { seenOnMain.remove(it) }
        unreconciled.removeAll(taskIds.toSet())
        if (displacedTopTaskId in taskIds) {
            displacedTopTaskId = null
            displacedTopPackage = null
            revealedTopTaskId = null
            revealedTopPackage = null
            mainTouched = false
            returnedToDisplaced = false
            agentActedOnDisplacedTop = false
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
     * to do (in the original order). Its refront is recomputed from display 0's CURRENT top,
     * ignoring tasks this cycle already moved back itself (those are our own doing): a home top
     * refronts Home, another task refronts that task. When that filtered top is empty, or is still
     * the top the frozen refront was computed from, the ORIGINAL refront is kept; once a later
     * decision followed a different top, that choice and top are the reference instead. An original
     * [MainDisplayRefront.None] ("the last restored task ends on top") becomes an explicit
     * refront of that task when it was already restored and the retry would cover it.
     * Report the outcome with [completeRestore].
     *
     * Executing a deferred restore ([planOrDefer]) goes through here too and ends the deferral.
     * Deferred tasks no longer on the virtual display (the user pulled them back to display 0)
     * are dropped silently; when none is left, nothing of this cycle is on display 0 either (a
     * deferral is only recorded before any move), so no refront is owed: the cycle is cleared
     * and [MainDisplayRestorePlan.NOTHING] returned.
     */
    fun plan(vdNow: List<DisplayTask>, mainNow: List<DisplayTask>): MainDisplayRestorePlan {
        settleUnreconciled(vdNow)
        val wasDeferred = deferred
        deferred = false
        val pending = pendingRestore
        val result = if (pending == null) {
            freshPlan(vdNow, mainNow).also {
                if (it != MainDisplayRestorePlan.NOTHING) {
                    pendingRestore = it
                    frozenTopTaskId = topOf(mainNow)?.taskId
                    lastPlanTopTaskId = frozenTopTaskId
                }
            }
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
            if (wasDeferred && moves.isEmpty()) {
                reset()
                return MainDisplayRestorePlan.NOTHING
            }
            // A frozen None means "the original plan's last task ends on top". If it is already
            // back and this retry moves other tasks over it, say so explicitly, and keep that
            // explicit refront pending (a refront-only retry must not fall back to None).
            val intendedTop = pending.restoreTaskIds.last()
            if (pending.refront == MainDisplayRefront.None && moves.isNotEmpty() &&
                    moves.last() != intendedTop) {
                pendingRestore = pending.copy(refront = MainDisplayRefront.Task(intendedTop))
            }
            val (refront, basisTop) = refrontFromReference(
                    mainNow, alsoExclude = emptyList(), intent = pendingRestore!!.refront)
            lastPlanTopTaskId = basisTop
            MainDisplayRestorePlan(moves, refront)
        }
        if (pendingRestore != null) restoreStarted = true
        lastPlannedTaskIds = result.restoreTaskIds
        return result
    }

    /**
     * Run end (build 59): [plan], but when the plan has moves and a refront (the user has moved
     * on to another app or Home) and nothing of this cycle is back on display 0 yet, DEFER it:
     * return null, execute nothing, keep the frozen plan (its move order trimmed to the tasks
     * still on the virtual display) and the tasks protected. A later [plan] executes it, with the
     * refront recomputed from display 0 at that moment by the retry rule. A [MainDisplayRefront.None]
     * plan (the user is still where the run left display 0) is returned for immediate execution,
     * exactly as before. Re-evaluating an existing deferral (e.g. viewer closed) goes through here
     * as well: it stays deferred, or clears itself when no deferred task is left on the VD.
     */
    fun planOrDefer(vdNow: List<DisplayTask>, mainNow: List<DisplayTask>): MainDisplayRestorePlan? {
        val plan = plan(vdNow, mainNow)
        val pending = pendingRestore
        if (pending != null && plan.restoreTaskIds.isNotEmpty() &&
                plan.refront != MainDisplayRefront.None && movedThisCycle.isEmpty()) {
            pendingRestore = pending.copy(restoreTaskIds = plan.restoreTaskIds)
            deferred = true
            unreconciled.addAll(plan.restoreTaskIds)
            return null
        }
        return plan
    }

    /**
     * Give up waiting for a deferral trigger (none can fire, or one failed to execute): the frozen
     * plan stays pending as an ordinary build-58 retry for the next [plan] / teardown handover,
     * the tasks stay protected. No-op when nothing is deferred.
     */
    fun dropDeferral() {
        deferred = false
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
        movedThisCycle.addAll(moved)
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

        return MainDisplayRestorePlan(ordered, freshRefront(displacedTopTaskId in ordered, topOf(mainNow)))
    }

    /** The first plan's refront rule, for display 0's [currentTop]. */
    private fun freshRefront(displacedTopIncluded: Boolean, currentTop: DisplayTask?): MainDisplayRefront {
        val untouched = displacedTopIncluded && !mainTouched && currentTop?.taskId == revealedTopTaskId
        return when {
            untouched -> MainDisplayRefront.None
            currentTop == null -> MainDisplayRefront.None
            currentTop.packageName in homePackages -> MainDisplayRefront.Home
            else -> MainDisplayRefront.Task(currentTop.taskId)
        }
    }

    /**
     * The one current-top refront rule (retry plans, the restorer's re-read right before a
     * refront, the post-release handover): display 0's top app task, skipping tasks this cycle
     * moved back ([movedThisCycle]) and [alsoExclude]. Empty, or unchanged from [basisTopTaskId]
     * -> [fallback]; home -> Home; another task -> that task. Also returns the top it decided on.
     * A decision that followed a top different from [basisTopTaskId] is recorded as
     * [observedChoice].
     */
    private fun refrontFromCurrent(
            mainNow: List<DisplayTask>,
            alsoExclude: Collection<Int>,
            basisTopTaskId: Int?,
            fallback: MainDisplayRefront,
    ): Pair<MainDisplayRefront, Int?> {
        val top = topOf(mainNow.filter { it.taskId !in movedThisCycle && it.taskId !in alsoExclude })
        if (top == null || top.taskId == basisTopTaskId) return fallback to basisTopTaskId
        val followed = if (top.packageName in homePackages) MainDisplayRefront.Home else MainDisplayRefront.Task(top.taskId)
        observedChoice = followed to top.taskId
        return followed to top.taskId
    }

    /**
     * [refrontFromCurrent] against the cycle's reference: the latest [observedChoice] if any, else
     * the frozen [intent] and [frozenTopTaskId].
     */
    private fun refrontFromReference(
            mainNow: List<DisplayTask>,
            alsoExclude: Collection<Int>,
            intent: MainDisplayRefront,
    ): Pair<MainDisplayRefront, Int?> {
        val observed = observedChoice
        return if (observed == null) {
            refrontFromCurrent(mainNow, alsoExclude, frozenTopTaskId, intent)
        } else {
            refrontFromCurrent(mainNow, alsoExclude, observed.second, observed.first)
        }
    }

    /**
     * Re-check of the last plan's refront ([planned]) against display 0 as read right before the
     * refront executes, skipping tasks moved back in this pass ([movedInPass]) or earlier in the
     * cycle. Narrows the read-then-act window: a user switch since the plan's read is followed
     * (and becomes the cycle's [observedChoice]). Within the pass the reference stays the plan's.
     */
    fun refrontNow(
            mainNow: List<DisplayTask>,
            movedInPass: Collection<Int>,
            planned: MainDisplayRefront,
    ): MainDisplayRefront =
            refrontFromCurrent(mainNow, movedInPass, lastPlanTopTaskId, planned).first

    /**
     * Refront owed after releasing the virtual display hands [handedOver] (protected tasks still
     * on it) to display 0, where they land on top. Decided from display 0 BEFORE the release
     * ([mainNow]), skipping the handed-over tasks and tasks this cycle moved back:
     * - nothing handed over -> None (no refront);
     * - a pending restore -> its intent, re-checked against display 0's current top as a retry
     *   is; a frozen None whose intended top is not among the handed-over tasks (already back,
     *   or gone) becomes an explicit refront of it, because the handed-over tasks cover it; a
     *   user choice observed since the freeze ([observedChoice]) is the reference instead;
     * - no plan was ever made -> the first plan's rule for display 0's current top.
     */
    fun handoverRefront(mainNow: List<DisplayTask>, handedOver: Collection<Int>): MainDisplayRefront {
        if (handedOver.isEmpty()) return MainDisplayRefront.None
        val pending = pendingRestore
        if (pending == null) {
            val top = topOf(mainNow.filter { it.taskId !in movedThisCycle && it.taskId !in handedOver })
            return freshRefront(displacedTopTaskId in handedOver, top)
        }
        val intendedTop = pending.restoreTaskIds.lastOrNull()
        val intent = if (pending.refront == MainDisplayRefront.None && intendedTop != null &&
                intendedTop !in handedOver) {
            MainDisplayRefront.Task(intendedTop)
        } else {
            pending.refront
        }
        return refrontFromReference(mainNow, handedOver, intent).first
    }

    /** Forget everything (after a complete restore). */
    fun reset() {
        seenOnMain.clear()
        displacedTopTaskId = null
        displacedTopPackage = null
        revealedTopTaskId = null
        revealedTopPackage = null
        mainTouched = false
        returnedToDisplaced = false
        agentActedOnDisplacedTop = false
        pendingRestore = null
        lastPlannedTaskIds = emptyList()
        restoreStarted = false
        movedThisCycle.clear()
        frozenTopTaskId = null
        observedChoice = null
        lastPlanTopTaskId = null
        deferred = false
        unreconciled.clear()
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
