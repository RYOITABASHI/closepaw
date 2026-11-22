package ai.closepaw.app

import ai.closepaw.mcp.BrainTranscript
import ai.closepaw.mcp.completeBrainTerminal
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrainOwnershipTest {
    private class Owner(var active: Boolean = true)
    private class Start(val owner: Owner, val handle: String) {
        val stopped = CompletableDeferred<Unit>()
    }

    private class Fixture {
        val gate = BrainSessionAdmission.gate
        val ownership = BrainOwnership(gate, Any(), Start::handle, { it.stopped.complete(Unit) })
        val published = mutableListOf<String>()
        var currentOwner: Owner? = Owner()

        fun adopt(start: Start): Boolean = ownership.adopt(start, validate = {
            check(ownerIsCurrent(currentOwner, start.owner) { start.owner.active })
        }) { published.add(start.handle) }
    }

    @Test
    fun failedStartDiscardReleasesWithoutPublishing() {
        val f = Fixture()
        val failed = Start(checkNotNull(f.currentOwner), "failed")
        val next = Start(failed.owner, "next")
        try {
            assertTrue(f.ownership.reserve(failed))
            assertFalse(f.ownership.reserve(next))
            f.ownership.retire(failed)
            assertTrue(failed.stopped.isCompleted)
            assertTrue(f.published.isEmpty())
            assertTrue(f.ownership.reserve(next))
            assertTrue(f.adopt(next))
            assertEquals(listOf("next"), f.published)
        } finally {
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun recreatedOwnerAdoptsAndOldOwnerCannotDispatch() = runBlocking {
        val f = Fixture()
        val old = Start(checkNotNull(f.currentOwner), "old")
        try {
            assertTrue(f.ownership.reserve(old))
            assertTrue(f.adopt(old))
            old.owner.active = false
            f.currentOwner = null
            assertFalse(ownerIsCurrent(f.currentOwner, old.owner) { old.owner.active })
            finish(f) {}

            val newOwner = Owner()
            f.currentOwner = newOwner
            val next = Start(newOwner, "new")
            assertTrue(f.ownership.reserve(next))
            assertTrue(f.adopt(next))
            assertFalse(ownerIsCurrent(f.currentOwner, old.owner) { true })
            assertTrue(ownerIsCurrent(f.currentOwner, newOwner) { newOwner.active })
            assertEquals(listOf("old", "new"), f.published)
        } finally {
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun stopAllRetiresGenerationAndLateWorkerCannotPublishOrReleaseSuccessor() {
        val f = Fixture()
        val inFlight = Start(checkNotNull(f.currentOwner), "old")
        val successor = Start(inFlight.owner, "successor")
        try {
            assertTrue(f.ownership.reserve(inFlight))
            f.ownership.retireCurrent()
            assertTrue(inFlight.stopped.isCompleted)
            assertTrue(f.ownership.reserve(successor))
            f.ownership.retire(inFlight)
            assertFalse(f.adopt(inFlight))
            assertFalse(successor.stopped.isCompleted)
            assertTrue(f.published.isEmpty())
            assertEquals(setOf("old", "successor"), f.ownership.protectedHandles())
            assertTrue(f.adopt(successor))
            assertEquals(listOf("successor"), f.published)
        } finally {
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun foregroundHandoffKeepsBrainClosedUntilTheLastInternalTokenIsReleased() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "brain")
        val predecessor = Any()
        val successor = Any()
        try {
            assertTrue(BrainSessionAdmission.tryClaimInternal(predecessor))
            assertFalse(f.ownership.reserve(start))
            assertTrue(BrainSessionAdmission.tryClaimInternal(successor))
            BrainSessionAdmission.releaseInternal(predecessor)
            assertFalse(f.ownership.reserve(start))
            BrainSessionAdmission.releaseInternal(successor)
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            assertFalse(BrainSessionAdmission.tryClaimInternal(successor))
            finish(f) { assertFalse(BrainSessionAdmission.tryClaimInternal(successor)) }
            assertTrue(BrainSessionAdmission.tryClaimInternal(successor))
        } finally {
            BrainSessionAdmission.releaseInternal(predecessor)
            BrainSessionAdmission.releaseInternal(successor)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun submissionExceptionFallsBackBeforeAdmissionReopens() = runBlocking {
        verifyFallback(submitThrows = true)
    }

    @Test
    fun failedRegisteredJobFallsBackAndCompletesItsBarrierSignalNormally() = runBlocking {
        verifyFallback(submitThrows = false)
    }

    private suspend fun verifyFallback(
        submitThrows: Boolean,
        waitPastSoftBound: Boolean = false,
    ) = coroutineScope {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "run")
        val internal = Any()
        val entered = CompletableDeferred<Unit>()
        val finishFallback = CompletableDeferred<Unit>()
        val softBoundReached = CompletableDeferred<Unit>()
        var barrierEntry: Job? = null
        var fenced = false
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            val settle = async {
                finish(f) {
                    settleBrainTeardown(
                        scope = this@coroutineScope,
                        dispatcher = EmptyCoroutineContext,
                        timeoutMs = if (waitPastSoftBound) 100 else 5_000,
                        hardTimeoutMs = 10_000,
                        submit = { signal ->
                            barrierEntry = signal
                            if (submitThrows) error("submission failed")
                            Job().apply { cancel() }
                        },
                        fallback = { entered.complete(Unit); finishFallback.await() },
                        fence = { fenced = true },
                        close = {},
                        detach = {},
                        track = {},
                        onSubmissionError = {},
                        onFallback = {},
                        onCleanupError = {},
                        onTimeout = {
                            check(waitPastSoftBound) { "unexpected timeout" }
                            softBoundReached.complete(Unit)
                        },
                    )
                }
            }
            withTimeout(5_000) {
                entered.await()
                if (waitPastSoftBound) softBoundReached.await()
            }
            assertFalse(settle.isCompleted)
            assertFalse(BrainSessionAdmission.tryClaimInternal(internal))
            assertFalse(checkNotNull(barrierEntry).isCompleted)
            finishFallback.complete(Unit)
            withTimeout(5_000) { settle.await() }
            assertTrue(checkNotNull(barrierEntry).isCompleted)
            assertFalse(checkNotNull(barrierEntry).isCancelled)
            assertFalse(fenced)
            assertTrue(BrainSessionAdmission.tryClaimInternal(internal))
        } finally {
            finishFallback.complete(Unit)
            BrainSessionAdmission.releaseInternal(internal)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun fallbackOutlivesSoftBoundAndAdmissionStaysClosedUntilItCompletes() = runBlocking {
        verifyFallback(submitThrows = false, waitPastSoftBound = true)
    }

    @Test
    fun fallbackThrowsAndFenceRunsBeforeReconciliationCompletes() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "run")
        val internal = Any()
        val events = Collections.synchronizedList(mutableListOf<String>())
        lateinit var reconciliation: Job
        var reconciledDuringFence = true
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            finish(f) {
                settleBrainTeardown(
                    scope = this,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 5_000,
                    hardTimeoutMs = 10_000,
                    submit = {
                        reconciliation = it
                        error("submission failed")
                    },
                    fallback = {
                        events.add("fallback")
                        error("fallback failed")
                    },
                    fence = {
                        reconciledDuringFence = reconciliation.isCompleted
                        events.add("fence")
                    },
                    close = {},
                    detach = {},
                    track = {},
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = {},
                    onTimeout = { error("unexpected timeout") },
                )
            }
            assertEquals(listOf("fallback", "fence"), events)
            assertFalse(reconciledDuringFence)
            assertTrue(reconciliation.isCompleted)
            assertTrue(BrainSessionAdmission.tryClaimInternal(internal))
        } finally {
            BrainSessionAdmission.releaseInternal(internal)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun fallbackHangsPastHardBoundAndFenceRuns() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "run")
        lateinit var reconciliation: Job
        var cleanup: Job? = null
        var fenced = false
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            withTimeout(5_000) {
                finish(f) {
                    settleBrainTeardown(
                        scope = this@runBlocking,
                        dispatcher = EmptyCoroutineContext,
                        timeoutMs = 50,
                        hardTimeoutMs = 300,
                        submit = {
                            reconciliation = it
                            Job().apply { cancel() }
                        },
                        fallback = { awaitCancellation() },
                        fence = { fenced = true },
                        close = {},
                        detach = {},
                        track = { cleanup = it },
                        onSubmissionError = {},
                        onFallback = {},
                        onCleanupError = {},
                        onTimeout = {},
                    )
                }
            }
            assertTrue(fenced)
            assertTrue(reconciliation.isCompleted)
            assertFalse(checkNotNull(cleanup).isCompleted)
        } finally {
            cleanup?.cancel()
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun teardownBoundReleasesAdmissionWhileCleanupRemainsPruneExcluded() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "retiring")
        val internal = Any()
        val shutdown = Job()
        var cleanup: Job? = null
        var timedOut = false
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            f.ownership.protect(start.handle)
            finish(f) {
                settleBrainTeardown(
                    scope = this,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 25,
                    hardTimeoutMs = 300,
                    submit = { shutdown },
                    fallback = { error("unexpected fallback") },
                    fence = {},
                    close = {},
                    detach = {},
                    track = { cleanup = it; f.ownership.trackCleanup(this, start.handle, it, 5_000) {} },
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = {},
                    onTimeout = { timedOut = true },
                )
            }
            assertTrue(timedOut)
            assertTrue(BrainSessionAdmission.tryClaimInternal(internal))
            assertFalse(checkNotNull(cleanup).isCompleted)
            val candidates = listOf(Triple(start.handle, 0L, true))
            assertTrue(BrainTranscript.retentionPrune(candidates, f.ownership.protectedHandles(), keep = 0).isEmpty())
            shutdown.complete()
            checkNotNull(cleanup).join()
            assertEquals(listOf(start.handle), BrainTranscript.retentionPrune(candidates, f.ownership.protectedHandles(), keep = 0))
        } finally {
            shutdown.complete()
            BrainSessionAdmission.releaseInternal(internal)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun registeredTeardownCancelledAfterSoftBoundFallsBackBeforeReconciliationCompletes() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "run")
        val internal = Any()
        val registeredTeardown = Job()
        val softBoundReached = CompletableDeferred<Unit>()
        val fallbackEntered = CompletableDeferred<Unit>()
        val finishFallback = CompletableDeferred<Unit>()
        lateinit var reconciliation: Job
        var fenced = false
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            val settle = async {
                finish(f) {
                    settleBrainTeardown(
                        scope = this@runBlocking,
                        dispatcher = EmptyCoroutineContext,
                        timeoutMs = 100,
                        hardTimeoutMs = 10_000,
                        submit = {
                            reconciliation = it
                            registeredTeardown
                        },
                        fallback = {
                            fallbackEntered.complete(Unit)
                            finishFallback.await()
                        },
                        fence = { fenced = true },
                        close = {},
                        detach = {},
                        track = {},
                        onSubmissionError = {},
                        onFallback = {},
                        onCleanupError = {},
                        onTimeout = { softBoundReached.complete(Unit) },
                    )
                }
            }
            withTimeout(5_000) { softBoundReached.await() }
            registeredTeardown.cancel()
            withTimeout(5_000) { fallbackEntered.await() }
            assertFalse(settle.isCompleted)
            assertFalse(reconciliation.isCompleted)
            assertFalse(BrainSessionAdmission.tryClaimInternal(internal))

            finishFallback.complete(Unit)
            withTimeout(5_000) { settle.await() }
            assertTrue(reconciliation.isCompleted)
            assertFalse(reconciliation.isCancelled)
            assertFalse(fenced)
            assertTrue(BrainSessionAdmission.tryClaimInternal(internal))
        } finally {
            registeredTeardown.cancel()
            finishFallback.complete(Unit)
            BrainSessionAdmission.releaseInternal(internal)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun trackedCleanupStaysOpenWhileTheFenceRuns() = runBlocking {
        val fallbackEntered = CompletableDeferred<Unit>()
        val finishFallback = CompletableDeferred<Unit>()
        val fenceEntered = CompletableDeferred<Unit>()
        val finishFence = CompletableDeferred<Unit>()
        lateinit var cleanup: Job
        try {
            val settle = async {
                settleBrainTeardown(
                    scope = this@runBlocking,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 50,
                    hardTimeoutMs = 200,
                    submit = { error("submission failed") },
                    fallback = {
                        fallbackEntered.complete(Unit)
                        finishFallback.await()
                    },
                    fence = {
                        fenceEntered.complete(Unit)
                        finishFence.await()
                    },
                    close = {},
                    detach = {},
                    track = { cleanup = it },
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = {},
                    onTimeout = {},
                )
            }
            withTimeout(5_000) {
                fallbackEntered.await()
                fenceEntered.await()
            }
            finishFallback.complete(Unit)
            delay(100)
            assertFalse(cleanup.isCompleted)

            finishFence.complete(Unit)
            withTimeout(5_000) {
                settle.await()
                cleanup.join()
            }
            assertTrue(cleanup.isCompleted)
        } finally {
            finishFallback.complete(Unit)
            finishFence.complete(Unit)
        }
    }

    @Test
    fun closeFailureStillPublishesOutcomeAndHoldsCleanupUntilFenceCompletes() = runBlocking {
        val fenceEntered = CompletableDeferred<Unit>()
        val finishFence = CompletableDeferred<Unit>()
        val closeError = IllegalStateException("close failed")
        val cleanupErrors = mutableListOf<Throwable>()
        lateinit var reconciliation: Job
        lateinit var cleanup: Job
        var detached = false
        var timedOut = false
        try {
            val settle = async {
                settleBrainTeardown(
                    scope = this@runBlocking,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 5_000,
                    hardTimeoutMs = 10_000,
                    submit = {
                        reconciliation = it
                        error("submission failed")
                    },
                    fallback = { error("fallback failed") },
                    fence = {
                        fenceEntered.complete(Unit)
                        finishFence.await()
                    },
                    close = { throw closeError },
                    detach = { detached = true },
                    track = { cleanup = it },
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = { cleanupErrors.add(it) },
                    onTimeout = { timedOut = true },
                )
            }
            withTimeout(5_000) { fenceEntered.await() }
            assertFalse(timedOut)
            assertFalse(cleanup.isCompleted)
            assertTrue(detached)
            assertTrue(closeError in cleanupErrors)

            finishFence.complete(Unit)
            withTimeout(5_000) {
                settle.await()
                cleanup.join()
            }
            assertTrue(cleanup.isCompleted)
            assertFalse(cleanup.isCancelled)
            assertTrue(reconciliation.isCompleted)
            assertFalse(reconciliation.isCancelled)
        } finally {
            finishFence.complete(Unit)
        }
    }

    @Test
    fun registeredTeardownPendingAtHardBoundReleasesWithFenceAndLateFallbackIsOrphaned() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "run")
        val internal = Any()
        val registeredTeardown = Job()
        val fallbackEntered = CompletableDeferred<Unit>()
        lateinit var reconciliation: Job
        lateinit var cleanup: Job
        var fenced = false
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            withTimeout(5_000) {
                finish(f) {
                    settleBrainTeardown(
                        scope = this@runBlocking,
                        dispatcher = EmptyCoroutineContext,
                        timeoutMs = 50,
                        hardTimeoutMs = 300,
                        submit = {
                            reconciliation = it
                            registeredTeardown
                        },
                        fallback = { fallbackEntered.complete(Unit) },
                        fence = { fenced = true },
                        close = {},
                        detach = {},
                        track = { cleanup = it },
                        onSubmissionError = {},
                        onFallback = {},
                        onCleanupError = {},
                        onTimeout = {},
                    )
                }
            }
            assertTrue(fenced)
            assertTrue(reconciliation.isCompleted)
            assertFalse(reconciliation.isCancelled)
            assertFalse(fallbackEntered.isCompleted)
            assertFalse(cleanup.isCompleted)
            assertTrue(BrainSessionAdmission.tryClaimInternal(internal))
            BrainSessionAdmission.releaseInternal(internal)

            registeredTeardown.cancel()
            withTimeout(5_000) {
                fallbackEntered.await()
                cleanup.join()
            }
            assertTrue(cleanup.isCompleted)
            assertFalse(cleanup.isCancelled)
        } finally {
            registeredTeardown.cancel()
            BrainSessionAdmission.releaseInternal(internal)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    @Test
    fun cancellingTheWaiterStillCompletesReconciliationExactlyOnce() = runBlocking {
        lateinit var reconciliation: Job
        val registeredTeardown = Job()
        var cleanup: Job? = null
        try {
            val settle = async {
                settleBrainTeardown(
                    scope = this@runBlocking,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 60_000,
                    hardTimeoutMs = 60_000,
                    submit = {
                        reconciliation = it
                        registeredTeardown
                    },
                    fallback = {},
                    fence = {},
                    close = {},
                    detach = {},
                    track = { cleanup = it },
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = {},
                    onTimeout = {},
                )
            }
            delay(50)
            settle.cancel()
            try {
                settle.await()
                fail("Expected the settle waiter to be cancelled")
            } catch (_: CancellationException) {
                assertTrue(settle.isCancelled)
                assertTrue(settle.isCompleted)
            }
            assertTrue(reconciliation.isCompleted)
        } finally {
            cleanup?.cancel()
            registeredTeardown.cancel()
        }
    }

    @Test
    fun trackingThrowStillCompletesReconciliationAndOpensTheGate() = runBlocking {
        val trackingError = IllegalStateException("tracking failed")
        val submitted = CompletableDeferred<Job>()
        // A regression that leaves the gate closed must not hang runBlocking.
        val workerLifetime = Job()
        val workerScope = CoroutineScope(coroutineContext + workerLifetime)
        lateinit var cleanup: Job
        try {
            try {
                settleBrainTeardown(
                    scope = workerScope,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 5_000,
                    hardTimeoutMs = 10_000,
                    submit = {
                        submitted.complete(it)
                        Job().apply { complete() }
                    },
                    fallback = { error("unexpected fallback") },
                    fence = {},
                    close = {},
                    detach = {},
                    track = {
                        cleanup = it
                        throw trackingError
                    },
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = {},
                    onTimeout = {},
                )
                fail("Expected the tracking exception")
            } catch (e: IllegalStateException) {
                assertEquals(trackingError, e)
            }
            val reconciliation = withTimeout(5_000) { submitted.await() }
            assertTrue(reconciliation.isCompleted)
            assertFalse(reconciliation.isCancelled)
            withTimeout(5_000) { cleanup.join() }
        } finally {
            workerLifetime.cancel()
        }
    }

    @Test
    fun throwingCleanupErrorCallbackStillPublishesOutcomeAndAwaitsGate() = runBlocking {
        supervisorScope {
            lateinit var reconciliation: Job
            lateinit var cleanup: Job
            var fenced = false
            var timedOut = false
            var detached = false
            withTimeout(5_000) {
                settleBrainTeardown(
                    scope = this@supervisorScope,
                    dispatcher = EmptyCoroutineContext,
                    timeoutMs = 10_000,
                    hardTimeoutMs = 20_000,
                    submit = {
                        reconciliation = it
                        Job().apply { complete() }
                    },
                    fallback = { error("unexpected fallback") },
                    fence = { fenced = true },
                    close = { error("close failed") },
                    detach = { detached = true },
                    track = { cleanup = it },
                    onSubmissionError = {},
                    onFallback = {},
                    onCleanupError = { error("cleanup error callback failed") },
                    onTimeout = { timedOut = true },
                )
                cleanup.join()
            }
            assertFalse(fenced)
            assertFalse(timedOut)
            assertTrue(reconciliation.isCompleted)
            assertFalse(reconciliation.isCancelled)
            assertTrue(cleanup.isCompleted)
            assertFalse(cleanup.isCancelled)
            assertTrue(detached)
        }
    }

    @Test
    fun cancelledWorkerDoesNotStripPruneProtectionMidFence() = runBlocking {
        val f = Fixture()
        val start = Start(checkNotNull(f.currentOwner), "retiring")
        val internal = Any()
        val fenceStarted = CompletableDeferred<Unit>()
        val finishFence = CompletableDeferred<Unit>()
        lateinit var reconciliation: Job
        lateinit var cleanup: Job
        try {
            assertTrue(f.ownership.reserve(start))
            assertTrue(f.adopt(start))
            f.ownership.protect(start.handle)
            val settle = async {
                finish(f) {
                    settleBrainTeardown(
                        scope = this@runBlocking,
                        dispatcher = EmptyCoroutineContext,
                        timeoutMs = 5_000,
                        hardTimeoutMs = 10_000,
                        submit = {
                            reconciliation = it
                            Job().apply { cancel() }
                        },
                        fallback = { error("fallback failed") },
                        fence = {
                            fenceStarted.complete(Unit)
                            finishFence.await()
                        },
                        close = {},
                        detach = {},
                        track = {
                            cleanup = it
                            f.ownership.trackCleanup(this@runBlocking, start.handle, it, 60_000) {}
                        },
                        onSubmissionError = {},
                        onFallback = {},
                        onCleanupError = {},
                        onTimeout = {},
                    )
                }
            }
            withTimeout(5_000) { fenceStarted.await() }
            cleanup.cancel()
            delay(300)
            assertTrue(start.handle in f.ownership.protectedHandles())

            finishFence.complete(Unit)
            withTimeout(5_000) { settle.await() }
            assertTrue(reconciliation.isCompleted)
            assertTrue(BrainSessionAdmission.tryClaimInternal(internal))
            withTimeout(5_000) { cleanup.join() }
            assertFalse(start.handle in f.ownership.protectedHandles())
        } finally {
            finishFence.complete(Unit)
            BrainSessionAdmission.releaseInternal(internal)
            f.ownership.retireCurrent()
            f.gate.sessionEnded()
        }
    }

    private suspend fun finish(f: Fixture, settle: suspend () -> Unit) {
        completeBrainTerminal(
            acknowledge = { true },
            waitBeforeRetry = {},
            publish = {},
            settle = settle,
            release = f.gate::sessionEnded,
        )
    }

    @Test
    fun stopIdentityTargetsOnlyTheCurrentRun() {
        var stopped = false
        if (matchesRunStop("previous", "current")) stopped = true
        assertFalse(stopped)
        if (matchesRunStop("current", "current")) stopped = true
        assertTrue(stopped)
        assertFalse(matchesRunStop("current", null))
    }
}
