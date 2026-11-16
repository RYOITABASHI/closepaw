package ai.closepaw.mcp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Post a block to another thread (the Android main looper) and wait for it, with abandon-on-timeout
 * semantics so a caller that was told "timed out" can never be surprised by a late side effect:
 *
 *  - the block has NOT started when the wait times out: it is marked abandoned and never executes;
 *  - the block IS executing when the wait times out: the caller gets null, and when the block
 *    finishes its result is handed to [reconcile] (on the executing thread) so the effect it
 *    created can be undone. The result is never delivered to the caller in that case.
 *
 * Pure JVM (no Android types): the adapter supplies [post] and [onTargetThread]. One call object
 * can serve many calls; all state is per call.
 */
class McpMainCall(
    private val post: (Runnable) -> Unit,
    private val onTargetThread: () -> Boolean,
    private val timeoutMs: Long,
) {
    /**
     * Runs [block] on the target thread and returns its result, or null on timeout. A block failure
     * is rethrown. See the class doc for what happens to [block] / its result after a timeout.
     */
    fun <T> call(reconcile: (T) -> Unit = {}, block: () -> T): T? {
        if (onTargetThread()) return block()
        val state = AtomicInteger(PENDING)
        val result = AtomicReference<T?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        post(Runnable {
            if (!state.compareAndSet(PENDING, RUNNING)) return@Runnable // abandoned before it started
            try {
                result.set(block())
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                if (!state.compareAndSet(RUNNING, DONE)) {
                    // The waiter already gave up on us: nobody will read the result, so undo it.
                    val late = result.get()
                    if (late != null) reconcile(late)
                }
                done.countDown()
            }
        })
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (state.compareAndSet(PENDING, ABANDONED)) return null // never runs
            if (state.compareAndSet(RUNNING, TIMED_OUT)) return null // runs on; reconciled on completion
            // else the block finished between the timeout and the CAS: fall through and use it
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val DONE = 2
        const val ABANDONED = 3
        const val TIMED_OUT = 4
    }
}

/**
 * Undoes a start whose reply was lost to a timeout, so no run exists that no client can address:
 * the registry entry is completed as cancelled FIRST (so the client-visible summary says why, and
 * the stop's own terminal report is ignored), then the run is stopped through its [guards] entry,
 * and the guard is dropped. Idempotent against a run that already ended.
 */
fun <G> reconcileLateStart(
    handle: String,
    registry: McpRunRegistry,
    guards: MutableMap<String, G>,
    stop: (G) -> Unit,
) {
    registry.complete(handle, McpRunRegistry.Status.CANCELLED, "Start reply timed out; the run was stopped")
    val guard = guards.remove(handle)
    if (guard != null) stop(guard)
}

/**
 * Identity check for a targeted intake stop: the live session may be shut down on behalf of a run
 * only when it is that run's own session (or no session is live). A different live session belongs
 * to a replacement run and must never receive the stop.
 */
fun stopTargetsLiveSession(trackerSessionId: String, liveSessionId: String?): Boolean =
    liveSessionId == null || liveSessionId == trackerSessionId
