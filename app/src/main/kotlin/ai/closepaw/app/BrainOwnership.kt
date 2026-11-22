package ai.closepaw.app

import ai.closepaw.mcp.BrainAdmissionGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Start generations and trace protection share the manager's publication lock. */
internal class BrainOwnership<Start : Any>(
    private val gate: BrainAdmissionGate,
    private val lock: Any,
    private val handle: (Start) -> String,
    private val signalStopped: (Start) -> Unit,
    private val orphaned: MutableSet<String> = mutableSetOf(),
) {
    private var starting: Start? = null
    private val protected = mutableSetOf<String>()

    fun reserve(candidate: Start): Boolean = synchronized(lock) {
        if (!gate.tryBeginStart(candidate)) return@synchronized false
        protected.add(handle(candidate))
        starting = candidate
        true
    }

    fun adopt(candidate: Start, validate: () -> Unit, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (starting !== candidate) return@synchronized false
            validate()
            gate.startAdopted(candidate)
            starting = null
            publish()
            protected.remove(handle(candidate))
            true
        }

    fun retire(candidate: Start) {
        synchronized(lock) {
            if (starting !== candidate) return
            starting = null
            signalStopped(candidate)
            gate.startAborted(candidate)
        }
    }

    fun retireCurrent(matches: (Start) -> Boolean = { true }) {
        synchronized(lock) { starting?.takeIf(matches)?.let(::retire) }
    }

    fun protect(handle: String) {
        synchronized(lock) { protected.add(handle) }
    }

    fun protectedHandles(): Set<String> = synchronized(lock) { protected + orphaned }

    fun trackCleanup(
        scope: CoroutineScope,
        handle: String,
        cleanup: Job,
        hardBoundMs: Long,
        onHardBound: () -> Unit,
    ) {
        val bound = scope.launch {
            if (withTimeoutOrNull(hardBoundMs) { cleanup.join(); true } != true) {
                synchronized(lock) {
                    orphaned.add(handle)
                    protected.remove(handle)
                }
                onHardBound()
            }
        }
        cleanup.invokeOnCompletion {
            synchronized(lock) { protected.remove(handle) }
            bound.cancel()
        }
    }
}

internal inline fun <Owner : Any> ownerIsCurrent(current: Owner?, owner: Owner, isActive: () -> Boolean) =
    current === owner && isActive()

internal fun matchesRunStop(requested: String, current: String?) = requested == current

private data class BrainTeardownOutcome(
    val fallbackInvoked: Boolean,
    val fallbackCompleted: Boolean,
)

/** The worker can outlive the bounded wait. Only reconciliation is registered in the barrier. */
internal suspend fun settleBrainTeardown(
    scope: CoroutineScope,
    dispatcher: CoroutineContext,
    timeoutMs: Long,
    hardTimeoutMs: Long,
    submit: (Job) -> Job,
    fallback: suspend () -> Unit,
    fence: suspend () -> Unit,
    close: suspend () -> Unit,
    detach: suspend () -> Unit,
    track: (Job) -> Unit,
    onSubmissionError: (Throwable) -> Unit,
    onFallback: () -> Unit,
    onCleanupError: (Throwable) -> Unit,
    onTimeout: () -> Unit,
) {
    val reconciliation = Job()
    val fallbackInvoked = AtomicBoolean(false)
    val outcomeSignal = CompletableDeferred<BrainTeardownOutcome>()
    val fenceGate = CompletableDeferred<Unit>()
    val cleanup = scope.async(dispatcher) {
        var fallbackCompleted = false
        val outcome: BrainTeardownOutcome
        try {
            val reconciled = try {
                val shutdown = submit(reconciliation)
                shutdown.join()
                !shutdown.isCancelled
            } catch (e: Throwable) {
                onSubmissionError(e)
                false
            }
            if (!reconciled) {
                onFallback()
                fallbackInvoked.set(true)
                fallback()
                fallbackCompleted = true
            }
        } catch (e: Throwable) {
            runCatching { onCleanupError(e) }
        } finally {
            try {
                try {
                    close()
                } finally {
                    detach()
                }
            } catch (e: Throwable) {
                runCatching { onCleanupError(e) }
            }
            outcome = BrainTeardownOutcome(fallbackInvoked.get(), fallbackCompleted)
            outcomeSignal.complete(outcome)
            withContext(NonCancellable) { fenceGate.await() }
        }
        outcome
    }
    try {
        track(cleanup)
        val softOutcome = withTimeoutOrNull(timeoutMs) { outcomeSignal.await() }
        val outcome = if (softOutcome == null) {
            onTimeout()
            withTimeoutOrNull(hardTimeoutMs) { outcomeSignal.await() }
        } else {
            softOutcome
        }
        val needsFence = if (outcome != null) {
            outcome.fallbackInvoked && !outcome.fallbackCompleted
        } else {
            // At the hard-bound release, reconciliation completes with cleanup outstanding.
            // The registered teardown and any fallback it later triggers continue as orphaned cleanup.
            true
        }
        if (needsFence) {
            try {
                fence()
            } catch (e: Throwable) {
                onCleanupError(e)
            }
        }
    } finally {
        fenceGate.complete(Unit)
        reconciliation.complete()
    }
}
