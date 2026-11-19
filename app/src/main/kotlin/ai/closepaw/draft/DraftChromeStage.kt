package ai.closepaw.draft

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A root task on a display, as far as the draft run cares. */
data class DraftRootTask(val id: Int, val chrome: Boolean)

/** The task operations the draft run needs. Each one reports failure instead of throwing. */
interface DraftTaskControl {
    /** Root tasks on the main display, the one in front first; null when they cannot be read. */
    suspend fun mainDisplayTasks(): List<DraftRootTask>?

    /** Chrome's root task ids on the run's display; null when they cannot be read. */
    suspend fun chromeTasksOnRunDisplay(): Set<Int>?

    suspend fun moveToRunDisplay(taskId: Int): Boolean

    suspend fun moveToMainDisplay(taskId: Int): Boolean

    suspend fun moveToFront(taskId: Int): Boolean

    suspend fun remove(taskId: Int): Boolean
}

/**
 * Keeps Chrome off the owner's screen during a draft run, and puts it back afterwards.
 *
 * Chrome's launcher activity sends every intent to Chrome's existing window, wherever that is, so
 * Chrome cannot be opened on the virtual display while its window sits on the main one. Instead:
 * - [prepare]: if Chrome is what the owner has in front, the run does not start. Otherwise every
 *   Chrome task on the main display moves to the run's display, where the run works unseen. With
 *   no Chrome task at all, the run's own launch creates one there.
 * - [restore], on every exit, after the run's tab is closed: the moved tasks go back to the main
 *   display, and whatever the owner has in front at that moment is put back in front, so Chrome
 *   does not cover it. If the owner is using Chrome by then, nothing is put over it. Chrome tasks
 *   that appeared on the run's display during the run (the run's own launch) are removed.
 *
 * Logs task ids and outcomes only.
 */
class DraftChromeStage(
    private val control: DraftTaskControl,
    private val log: (String) -> Unit,
) {
    private var prepared = false
    private var moved: List<Int> = emptyList()
    private var runDisplayBefore: Set<Int> = emptySet()
    private var topBefore: Int? = null

    /** Null to go ahead, or why the run must not start. Call [restore] in either case. */
    suspend fun prepare(): String? {
        val main = safely { control.mainDisplayTasks() }
        if (main == null) {
            log("chrome stage: main display tasks unreadable; not starting")
            return CHROME_TASKS_UNREADABLE
        }
        val top = main.firstOrNull()
        if (top?.chrome == true) {
            log("chrome stage: Chrome is in front (task ${top.id}); not starting")
            return CHROME_IN_USE
        }
        val onRun = safely { control.chromeTasksOnRunDisplay() }
        if (onRun == null) {
            log("chrome stage: run display tasks unreadable; not starting")
            return CHROME_TASKS_UNREADABLE
        }
        val chrome = main.filter { it.chrome }.map { it.id }
        // Ownership first, moves second: every task this run is about to move is recorded as the
        // owner's BEFORE the first move. A cancellation between moves, or after a binder move
        // landed but before its call returned, then still leaves each task marked as the owner's,
        // so [restore] moves it back (it reconciles where each one actually is) and can never
        // take it for a task the run created and remove it.
        moved = chrome
        topBefore = top?.id
        runDisplayBefore = onRun
        prepared = true
        val done = mutableListOf<Int>()
        for (id in chrome) {
            if (safely { control.moveToRunDisplay(id) } == true) {
                done += id
            } else {
                log("chrome stage: moving task $id to the run display failed; moved=$done")
                return CHROME_MOVE_FAILED
            }
        }
        log("chrome stage: top=${top?.id} moved=$done alreadyOnRunDisplay=$onRun")
        return null
    }

    /** Puts the owner's Chrome back. Safe to call more than once and after a refused [prepare]. */
    suspend fun restore() {
        if (!prepared) return
        prepared = false
        val onRun = safely { control.chromeTasksOnRunDisplay() }
        // What the owner has in front right now, before Chrome comes back on top of it.
        val front = safely { control.mainDisplayTasks() }?.firstOrNull()

        val back = mutableListOf<Int>()
        val stuck = mutableListOf<Int>()
        for (id in moved) {
            // Reconciled against where the task actually is: one the owner already took back to
            // the main display, or one a cut-short [prepare] never got to move, is left alone.
            if (onRun != null && id !in onRun) continue
            if (safely { control.moveToMainDisplay(id) } == true) back += id else stuck += id
        }

        val created = if (onRun == null) emptySet() else onRun - runDisplayBefore - moved.toSet()
        val removed = created.filter { safely { control.remove(it) } == true }

        val refront = when {
            back.isEmpty() -> null
            front == null -> topBefore
            front.chrome -> null
            else -> front.id
        }
        val refronted = refront?.let { safely { control.moveToFront(it) } == true }
        log(
            "chrome stage restored: back=$back stuck=$stuck created=$created removed=$removed " +
                "front=${front?.id} refront=$refront ok=$refronted",
        )
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("chrome stage: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        const val CHROME_IN_USE = "chrome-in-use"
        const val CHROME_TASKS_UNREADABLE = "chrome-tasks-unreadable"
        const val CHROME_MOVE_FAILED = "chrome-move-failed"
    }
}

/**
 * The draft run's last steps, in this order whatever the ending: close the run's tab, so nothing
 * of it reaches the owner's window, then close any tab known only by its pending marker
 * ([recoverTabs]), then put the owner's Chrome back. A failed close does not stop the later steps.
 *
 * Each step has its own budget ([closeBudgetMs], [recoverBudgetMs], [restoreBudgetMs]): a close
 * that hangs on a dead CDP connection can no longer eat the time the restore needs (gate fix
 * round 1, finding 3), nor the time the marker recovery needs (gate fix round 4, finding 1). The
 * caller runs this under NonCancellable, so a Stop or the service going away does not skip any
 * step; the display release that follows is the caller's own, separate step.
 */
internal suspend fun finishDraftChrome(
    tabId: String?,
    closeTab: suspend (String) -> Unit,
    restore: suspend () -> Unit,
    log: (String) -> Unit,
    recoverTabs: (suspend () -> Unit)? = null,
    closeBudgetMs: Long = DRAFT_CLOSE_BUDGET_MS,
    recoverBudgetMs: Long = DRAFT_RECOVER_BUDGET_MS,
    restoreBudgetMs: Long = DRAFT_RESTORE_BUDGET_MS,
) {
    if (tabId != null) {
        log("draft tab " + budgeted(closeBudgetMs) { closeTab(tabId) })
    }
    if (recoverTabs != null) {
        log("draft marker tabs " + budgeted(recoverBudgetMs) { recoverTabs() })
    }
    if (withTimeoutOrNull(restoreBudgetMs) { restore() } == null) log("chrome restore timed out")
}

/** Runs one teardown step under its own budget; the outcome is for the log only. */
private suspend fun budgeted(budgetMs: Long, step: suspend () -> Unit): String =
    withTimeoutOrNull(budgetMs) {
        try {
            step()
            "closed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "failed-" + e.javaClass.simpleName
        }
    } ?: "timed-out"

/**
 * Budgets for [finishDraftChrome]; together with the display release they stay under the 30 s
 * start barrier (6 + 4 + 15 s). The marker recovery's budget is its own, never shared with the
 * named tab's close (gate fix round 4, finding 1).
 */
internal const val DRAFT_CLOSE_BUDGET_MS = 6_000L
internal const val DRAFT_RECOVER_BUDGET_MS = 4_000L
internal const val DRAFT_RESTORE_BUDGET_MS = 15_000L
