package ai.closepaw.platform.virtualdisplay

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orders the run-end display-0 task return ([VirtualDisplayPlatform.restoreMainDisplayTasks])
 * against everything around it:
 * - it is RECORDED when the session's task ends ([onTaskEnded], before the session goes Idle),
 *   so a follow-up start ([awaitRunEnd]) and a replacing session ([inFlight]) can wait for it;
 * - when the run owes a result report ([expectResultReport], Shelly background runs), it runs
 *   only after the service has reported that result ([resultReported]): the result callback
 *   still precedes the restore, also when a follow-up start is waiting for it. Waiting never
 *   releases the gate.
 * - a report that has not arrived within [resultReportTimeoutMs] ABANDONS the restore: it is
 *   never executed (the report may still come, late, and must precede every task move). The
 *   recorded job completes, so nothing waiting on it wedges, and `onAbandoned` leaves the
 *   placement debt owed where the platform's other triggers find it (the next start's
 *   reconcile, teardown). A report arriving after that executes nothing either.
 */
internal class RunEndRestoreSequencer(
        private val scope: CoroutineScope,
        private val resultReportTimeoutMs: Long = RESULT_REPORT_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "RunEndRestore"
        private const val RESULT_REPORT_TIMEOUT_MS = 10_000L
    }

    private val lock = Any()
    private var job: Job? = null
    private var resultGate: CompletableDeferred<Unit>? = null
    private var resultOwed = false
    /** The result was reported before the task end recorded the restore (run finished early). */
    private var reportedEarly = false

    /** The current run reports a result (Shelly tracker attached): gate its run-end restore on it. */
    fun expectResultReport() {
        synchronized(lock) {
            resultOwed = true
            reportedEarly = false
        }
    }

    /**
     * The task ended: record [restore] (gated on the result report when one is owed) and start
     * it. When the gate times out, [restore] is NOT run; [onAbandoned] runs instead (it must not
     * move tasks: it only records the debt as owed).
     */
    fun onTaskEnded(onAbandoned: () -> Unit = {}, restore: suspend () -> Unit) {
        val recorded = synchronized(lock) {
            val gate = if (resultOwed && !reportedEarly) CompletableDeferred<Unit>() else null
            resultOwed = false
            reportedEarly = false
            resultGate = gate
            scope.launch(start = CoroutineStart.LAZY) {
                if (gate != null && withTimeoutOrNull(resultReportTimeoutMs) { gate.await() } == null) {
                    // Cannot tell "never reports" from "reports late": never move before a report.
                    Log.w(TAG, "result not reported after $resultReportTimeoutMs ms; " +
                            "abandoning the run-end restore (the debt stays owed)")
                    onAbandoned()
                    return@launch
                }
                restore()
            }.also { job = it }
        }
        recorded.start()
    }

    /** The service reported the run's result: a gated run-end restore may run now. */
    fun resultReported() {
        synchronized(lock) {
            val gate = resultGate
            if (gate != null) {
                gate.complete(Unit)
                resultGate = null
            } else {
                reportedEarly = true
            }
        }
    }

    /** Wait until the recorded run-end restore has completed or was abandoned (no-op when none). */
    suspend fun awaitRunEnd() {
        synchronized(lock) { job }?.join()
    }

    /** The recorded run-end restore while it has not completed (waiting for its gate included). */
    fun inFlight(): Job? = synchronized(lock) { job }?.takeIf { !it.isCompleted }
}
