package ai.closepaw.mcp

import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import ai.closepaw.model.ScreenSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSessionPolicyTest {
    @Test
    fun freshBrainActivityKeepsTheSession() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS

        assertFalse(BrainStaleness.shouldTerminate(1L, false, 1L, bound))
        assertFalse(BrainStaleness.shouldTerminate(1L, false, bound, bound))
    }

    @Test
    fun staleIdleBrainTerminatesTheSession() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS

        assertTrue(BrainStaleness.shouldTerminate(1L, false, bound + 2L, bound))
        assertTrue(BrainStaleness.shouldTerminate(1L, false, bound * 10, bound))
    }

    @Test
    fun brainIdleExactlyAtTheBoundKeepsTheSession() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS

        assertFalse(BrainStaleness.shouldTerminate(1L, false, 1L + bound, bound))
    }

    @Test
    fun staleBrainWithACallInFlightKeepsTheSession() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS

        assertFalse(BrainStaleness.shouldTerminate(1L, true, bound * 10, bound))
    }

    @Test
    fun unseededBrainTimestampsKeepTheSession() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS

        assertFalse(BrainStaleness.shouldTerminate(0L, false, Long.MAX_VALUE, bound))
        assertFalse(BrainStaleness.shouldTerminate(-1L, false, bound * 10, bound))
    }

    @Test
    fun brainStalenessBoundIsFiveMinutesAndBelowTheWallClockLimit() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS

        assertEquals(300_000L, bound)
        assertTrue(bound < BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS)
    }

    @Test
    fun differentResourceIdsProduceUnequalIdentities() {
        val first = button("example:id/first")
        val second = first.copy(resourceId = "example:id/second")

        assertNotEquals(first.identity(), second.identity())
    }

    @Test
    fun identicalElementsProduceEqualIdentities() {
        val first = button("example:id/first")
        val second = button("example:id/first")

        assertEquals(first.identity(), second.identity())
    }

    @Test
    fun savedIdentityMatchesOnlyTheCandidateWithTheSameResourceId() {
        val observed = button("example:id/first")
        val savedIdentity = observed.identity()
        val matching = observed.copy(index = 1)
        val other = observed.copy(resourceId = "example:id/second")

        assertEquals(matching, listOf(other, matching).singleOrNull { it.identity() == savedIdentity })
    }

    private fun button(resourceId: String) = PerceptionElement(
        index = 0,
        text = "Continue",
        resourceId = resourceId,
        className = "Button",
        description = "Continue",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        isEnabled = true,
        isFocused = false,
        isLongClickable = false,
        bounds = Bounds(0, 0, 100, 100),
        center = Point(50, 50),
        isSelected = false,
        hintText = "",
        isChecked = false,
        isCheckable = false,
        rangeInfo = null,
        packageName = "example",
    )

    @Test
    fun terminalGateKeepsTheFirstClaim() {
        val pairs = listOf(
            BrainTerminalKind.STOPPED to BrainTerminalKind.DONE,
            BrainTerminalKind.DONE to BrainTerminalKind.STOPPED,
            BrainTerminalKind.FAILED to BrainTerminalKind.DONE,
        )
        for ((first, second) in pairs) {
            val gate = BrainTerminalGate()
            assertNull(gate.claimedKind())
            assertTrue(gate.claim(first))
            assertFalse(gate.claim(second))
            assertEquals(first, gate.claimedKind())
        }
    }

    @Test
    fun terminalGateIsClaimedOnlyAfterAClaim() {
        val gate = BrainTerminalGate()
        assertFalse(gate.isClaimed())
        assertTrue(gate.claim(BrainTerminalKind.FAILED))
        assertTrue(gate.isClaimed())
    }

    @Test
    fun abortingAStartFreesTheAdmissionGate() {
        val gate = BrainAdmissionGate()
        val start = Any()
        assertTrue(gate.tryBeginStart(start))
        assertTrue(gate.isBrainActive())
        gate.startAborted(start)
        assertFalse(gate.isBrainActive())
        assertTrue(gate.tryBeginStart(start))
    }

    @Test
    fun adoptingAStartKeepsTheGateActiveUntilTheSessionEnds() {
        val gate = BrainAdmissionGate()
        val start = Any()
        assertTrue(gate.tryBeginStart(start))
        assertFalse(gate.hasLiveSession())
        gate.startAdopted(start)
        assertTrue(gate.hasLiveSession())
        assertTrue(gate.isBrainActive())
        gate.sessionEnded()
        assertFalse(gate.hasLiveSession())
        assertFalse(gate.isBrainActive())
        assertTrue(gate.tryBeginStart(start))
    }

    @Test
    fun admissionRejectsAnotherStartBeforeAndAfterAdoption() {
        val gate = BrainAdmissionGate()
        val start = Any()
        assertTrue(gate.tryBeginStart(start))
        assertFalse(gate.tryBeginStart(start))
        gate.startAdopted(start)
        assertFalse(gate.tryBeginStart(start))
    }

    @Test
    fun internalReservationIsExclusiveAndReentrant() {
        val gate = BrainAdmissionGate()
        val start = Any()
        val owner = Any()
        val other = Any()
        assertTrue(gate.tryClaimInternal(owner))
        assertTrue(gate.tryClaimInternal(owner))
        assertTrue(gate.isInternalActive())
        assertTrue(gate.isAnyActive())
        assertFalse(gate.isBrainActive())
        assertFalse(gate.tryClaimInternal(other))
        assertFalse(gate.tryBeginStart(start))
        gate.releaseInternal(other)
        assertFalse(gate.tryBeginStart(start))
        gate.releaseInternal(owner)
        gate.releaseInternal(owner)
        assertFalse(gate.isAnyActive())
        assertTrue(gate.tryBeginStart(start))
        assertFalse(gate.tryClaimInternal(owner))
        gate.startAdopted(start)
        assertFalse(gate.tryClaimInternal(owner))
        gate.sessionEnded()
        assertTrue(gate.tryClaimInternal(other))
    }

    @Test
    fun terminalCompletionPublishesAfterAcknowledgmentAndReleasesAfterTeardown() = runBlocking {
        val steps = mutableListOf<String>()
        var attempts = 0
        completeBrainTerminal(
            acknowledge = { steps.add("ack"); ++attempts == 2 },
            waitBeforeRetry = { steps.add("retry") },
            publish = { steps.add("publish:$it") },
            settle = { steps.add("settle") },
            release = { steps.add("release") },
        )
        assertEquals(listOf("ack", "retry", "ack", "publish:true", "settle", "release"), steps)
    }

    @Test
    fun persistentAckFailureStillPublishesAndReleasesAdmissionAfterTeardown() = runBlocking {
        val gate = BrainAdmissionGate()
        val start = Any()
        assertTrue(gate.tryBeginStart(start))
        gate.startAdopted(start)
        var attempts = 0
        var recorded: Boolean? = null
        var settled = false
        completeBrainTerminal(
            acknowledge = { attempts++; false },
            waitBeforeRetry = {},
            publish = {
                recorded = it
                assertTrue(gate.isBrainActive())
            },
            settle = {
                assertEquals(false, recorded)
                assertFalse(gate.tryClaimInternal(Any()))
                settled = true
            },
            release = {
                assertTrue(settled)
                gate.sessionEnded()
            },
        )
        assertEquals(3, attempts)
        assertTrue(gate.tryClaimInternal(Any()))
    }

    @Test
    fun actionCapAllowsTheHundredthRecord() {
        val guard = BrainSessionGuard(0)
        val screen = snapshot("button")
        repeat(99) { index ->
            assertNull(guard.recordAndCheck(action(screen, index)))
        }

        assertNull(guard.checkBeforeAction(99))
        assertNull(guard.recordAndCheck(action(screen, 99)))
        assertEquals(100, guard.actionsExecuted)
        assertEquals(BrainGuardTrip.ACTION_CAP, guard.checkBeforeAction(100))
    }

    @Test
    fun wallClockCapTripsAtTheDeadline() {
        val startedAt = 250L
        val guard = BrainSessionGuard(startedAt)
        val deadline = startedAt + BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS

        assertNull(guard.checkBeforeAction(deadline - 1))
        assertEquals(BrainGuardTrip.WALL_CLOCK, guard.checkBeforeAction(deadline))
    }

    @Test
    fun loopTripsOnFiveMatchingTargetsAndStableScreens() {
        val guard = BrainSessionGuard(0)
        assertNull(guard.recordAndCheck(action(snapshot("older screen"), targetIndex = 1)))
        repeat(4) { assertNull(guard.recordAndCheck(action(snapshot("button")))) }

        assertEquals(BrainGuardTrip.LOOP, guard.recordAndCheck(action(snapshot("button"))))
    }

    @Test
    fun loopDoesNotTripWhenScreensChange() {
        val guard = BrainSessionGuard(0)

        repeat(5) { assertNull(guard.recordAndCheck(action(snapshot("button $it")))) }
    }

    @Test
    fun loopDoesNotTripWhenTargetsChange() {
        val guard = BrainSessionGuard(0)
        val screen = snapshot("button")

        repeat(5) { assertNull(guard.recordAndCheck(action(screen, targetIndex = it))) }
    }

    @Test
    fun loopDoesNotTripWithOnlyFourMatchingRecords() {
        val guard = BrainSessionGuard(0)
        val screen = snapshot("button")

        repeat(4) { assertNull(guard.recordAndCheck(action(screen))) }
    }

    @Test
    fun sameElementsProduceEqualSignatureTokens() {
        val first = snapshot("button")
        val second = snapshot("button").copy(timestamp = 1)

        assertEquals(first.brainSignature().tokens, second.brainSignature().tokens)
    }

    @Test
    fun nonCountedRecordsNeverFeedTheLoopOrConsumeTheCap() {
        val guard = BrainSessionGuard(0)
        val counted = action(snapshot("button"))
        val ignored = counted.copy(countsTowardGuard = false)

        repeat(BrainSessionLimits.MAX_ACTIONS_PER_SESSION) {
            assertNull(guard.recordAndCheck(ignored))
        }
        assertEquals(0, guard.actionsExecuted)
        assertNull(guard.checkBeforeAction(0))
        assertNull(guard.recordAndCheck(counted))
        assertEquals(1, guard.actionsExecuted)
    }

    @Test
    fun unreliableSignatureBreaksAnOtherwiseStableWindow() {
        val guard = BrainSessionGuard(0)
        val reliable = action(snapshot("button"))
        val window = BrainSessionLimits.LOOP_WINDOW

        assertNull(guard.recordAndCheck(reliable.copy(signatureReliable = false)))
        repeat(window - 1) { assertNull(guard.recordAndCheck(reliable)) }
        assertEquals(window, guard.actionsExecuted)
        assertEquals(BrainGuardTrip.LOOP, guard.recordAndCheck(reliable))
    }

    private fun action(screen: ScreenSnapshot, targetIndex: Int = 0) =
        BrainActionRecord("tap", "element:$targetIndex", screen.brainSignature(), screen.timestamp)

    private fun snapshot(text: String) = ScreenSnapshot(
        timestamp = 0,
        elements = listOf(
            PerceptionElement(
                index = 0,
                text = text,
                resourceId = "",
                className = "Button",
                description = "",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isEnabled = true,
                isFocused = false,
                isLongClickable = false,
                bounds = Bounds(0, 0, 100, 100),
                center = Point(50, 50),
            ),
        ),
    )

    @Test
    fun callStartingAfterTheStalenessSamplePreventsTheClaim() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS
        val gate = BrainTerminalGate()
        val tracker = BrainActivityTracker(gate)
        tracker.seed(1L)

        assertTrue(BrainStaleness.shouldTerminate(tracker.lastSeenMs(), tracker.hasCallInFlight(), bound + 2L, bound))
        tracker.noteCallStarted(bound + 2L)
        assertFalse(guardedStalenessClaim(tracker, gate, bound + 2L, bound))
        assertNull(gate.claimedKind())

        tracker.noteCallFinished(bound + 2L)
        assertFalse(guardedStalenessClaim(tracker, gate, bound + 2L, bound))
        assertTrue(guardedStalenessClaim(tracker, gate, 2L * bound + 3L, bound))
        assertEquals(BrainTerminalKind.FAILED, gate.claimedKind())
    }

    @Test
    fun callInFlightAtClaimTimeIsNeverStalenessTerminated() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS
        val gate = BrainTerminalGate()
        val tracker = BrainActivityTracker(gate)
        tracker.seed(1L)
        tracker.noteCallStarted(1L)

        assertFalse(guardedStalenessClaim(tracker, gate, bound * 10, bound))
        assertNull(gate.claimedKind())
    }

    @Test
    fun guardedStalenessClaimRefusesOnceTheGateIsClaimed() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS
        val gate = BrainTerminalGate()
        val tracker = BrainActivityTracker(gate)
        tracker.seed(1L)
        assertTrue(gate.claim(BrainTerminalKind.STOPPED))

        assertFalse(guardedStalenessClaim(tracker, gate, bound * 10, bound))
        assertEquals(BrainTerminalKind.STOPPED, gate.claimedKind())
    }

    @Test
    fun concurrentCallStartsNeverOverlapAStalenessClaim() {
        val bound = BrainSessionLimits.BRAIN_STALENESS_MS
        val gate = BrainTerminalGate()
        val tracker = BrainActivityTracker(gate)
        tracker.seed(1L)
        val staleNow = 1L
        val now = bound + 2L
        val mirror = AtomicBoolean()
        val violation = AtomicBoolean()
        val claimed = AtomicBoolean()
        val caller = Thread {
            repeat(300) {
                tracker.noteCallStarted(staleNow)
                mirror.set(true)
                Thread.sleep(1)
                mirror.set(false)
                tracker.noteCallFinished(staleNow)
            }
        }
        val watcher = Thread {
            while (caller.isAlive && !claimed.get()) {
                // Sample the mirror before a later call can acquire the gate.
                synchronized(gate) {
                    if (guardedStalenessClaim(tracker, gate, now, bound)) {
                        claimed.set(true)
                        if (mirror.get()) violation.set(true)
                    }
                }
            }
        }
        caller.start()
        watcher.start()
        caller.join(10_000L)
        watcher.join(10_000L)
        assertFalse(caller.isAlive)
        assertFalse(watcher.isAlive)
        assertFalse(violation.get())
    }

    private fun guardedStalenessClaim(
        tracker: BrainActivityTracker,
        gate: BrainTerminalGate,
        nowMs: Long,
        boundMs: Long,
    ): Boolean = synchronized(gate) {
        if (gate.isClaimed()) return@synchronized false
        if (!BrainStaleness.shouldTerminate(
                tracker.lastSeenMs(), tracker.hasCallInFlight(), nowMs, boundMs,
            )
        ) return@synchronized false
        gate.claim(BrainTerminalKind.FAILED)
    }
}
