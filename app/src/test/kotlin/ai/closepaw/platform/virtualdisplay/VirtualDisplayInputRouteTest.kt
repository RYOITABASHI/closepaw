package ai.closepaw.platform.virtualdisplay

import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.SystemButtonType
import ai.closepaw.platform.UIAction
import ai.closepaw.taskhelper.TaskHelperInput
import android.view.MotionEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayInputRouteTest {
    @Test fun confirmedGesturesUseOnlyTheHelper() = runBlocking<Unit> {
        val requests = mutableListOf<TaskHelperInputRequest>()
        val injector = VirtualDisplayInputInjector(ShizukuClient(), { 42 }, { error("Unexpected shell injection") }, { true }) {
            requests += it
            InputOutcome.CONFIRMED
        }
        assertTrue(injector.injectTap(1, 2) is ActionResult.Success)
        assertTrue(injector.injectLongPress(2, 3, 500) is ActionResult.Success)
        assertTrue(injector.injectSwipe(UIAction.Swipe(3, 4, 5, 6, 300)) is ActionResult.Success)
        assertTrue(injector.injectSystemButton(SystemButtonType.ENTER) is ActionResult.Success)
        assertEquals(listOf(TaskHelperInput.TAP, TaskHelperInput.LONG_PRESS, TaskHelperInput.SWIPE,
            TaskHelperInput.KEY), requests.map { it.kind })
        assertTrue(requests.all { it.displayId == 42 && it.valid })
        assertEquals(500L, requests[1].durationMs)
        assertEquals(5f, requests[2].endX, 0f)
        assertEquals(66, requests[3].action)
    }

    @Test fun timeoutFallsBackForOnlyThatCall() = runBlocking<Unit> {
        // The Android SDK stubs omit hidden setDisplayId, so the incumbent route
        // proceeds to its shell fallback in this host test.
        var helperCalls = 0
        val commands = mutableListOf<List<String>>()
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { command ->
                commands += command.toList()
                0
            },
            { true },
        ) {
            helperCalls++
            if (helperCalls == 1) InputOutcome.TIMEOUT else InputOutcome.CONFIRMED
        }
        val first = injector.injectTap(1, 2) as ActionResult.Success
        assertTrue(first.message.endsWith("[shell]"))
        val second = injector.injectTap(3, 4) as ActionResult.Success
        assertEquals("Tap at (3,4)", second.message)
        assertEquals(2, helperCalls)
        assertEquals(listOf(listOf("input", "-d", "42", "tap", "1", "2")), commands)
    }

    @Test fun flagDisabledUsesTheIncumbentForBothGestures() = runBlocking<Unit> {
        val commands = mutableListOf<List<String>>()
        val helper: suspend (TaskHelperInputRequest) -> InputOutcome = { error("Helper disabled") }
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { command ->
                commands += command.toList()
                if (commands.size == 1) 0 else 1
            },
            confirmChannel = { true },
            helper = helper.takeIf { TaskHelperInputPolicy.useHelper(sdkInt = 33, routeEnabled = false) },
        )
        assertTrue(injector.injectTap(1, 2) is ActionResult.Success)
        assertTrue(injector.injectSwipe(UIAction.Swipe(1, 2, 3, 4, 50)) is ActionResult.Failure)
        assertEquals(listOf(
            listOf("input", "-d", "42", "tap", "1", "2"),
            listOf("input", "-d", "42", "swipe", "1", "2", "3", "4", "50"),
        ), commands)
    }

    @Test fun unconfirmedSwipeNeedsSuccessfulFallbackForThatCall() = runBlocking<Unit> {
        var helperCalls = 0
        val commands = mutableListOf<List<String>>()
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { command -> commands += command.toList(); 1 }, { true },
        ) {
            helperCalls++
            inputOutcome(singleEvent = false, reflectionOk = true, down = true, up = helperCalls == 2, error = null)
        }
        val swipe = UIAction.Swipe(1, 2, 3, 4, 2000)
        assertTrue(injector.injectSwipe(swipe) is ActionResult.Failure)
        assertTrue(injector.injectSwipe(swipe) is ActionResult.Success)
        assertEquals(2, helperCalls)
        assertEquals(listOf(listOf("input", "-d", "42", "swipe", "1", "2", "3", "4", "2000")), commands)
    }

    @Test fun helperDeathOrChannelLossLeavesTheCallFailed() = runBlocking<Unit> {
        for (failure in listOf(InputOutcome.HELPER_DIED, InputOutcome.CHANNEL_UNAVAILABLE)) {
            val injector = VirtualDisplayInputInjector(ShizukuClient(), { 42 }, { error("Unexpected shell injection") }, { true }) {
                failure
            }
            val result = injector.injectTap(1, 2)
            assertTrue(result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).reason.contains(failure.name))
        }
    }

    @Test fun missingChannelEvidenceBlocksIncumbentInput() = runBlocking<Unit> {
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { false },
        )
        val result = injector.injectTap(1, 2) as ActionResult.Failure
        assertTrue(result.reason.contains(InputOutcome.CHANNEL_UNAVAILABLE.name))
    }

    @Test fun channelLossDuringHelperDispatchOverridesConfirmation() = runBlocking<Unit> {
        var reachable = true
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { reachable },
        ) {
            reachable = false
            InputOutcome.CONFIRMED
        }
        val result = injector.injectTap(1, 2) as ActionResult.Failure
        assertTrue(result.reason.contains(InputOutcome.CHANNEL_UNAVAILABLE.name))
    }

    @Test fun viewerRetiresDisplayOnChannelLossWithoutProbingForCancel() = runBlocking<Unit> {
        var probes = 0
        val failures = mutableListOf<InputOutcome>()
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { probes++; false },
        ) { error("Unexpected helper injection") }
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { error("Unexpected shell injection") }, { _, outcome -> failures += outcome },
        )
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
        assertEquals(1, probes)
        assertEquals(listOf(InputOutcome.CHANNEL_UNAVAILABLE), failures)
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 50f, 100, 110, 100, 100))
        assertEquals(1, probes)
    }

    @Test fun invalidViewerDownDoesNotProbeOrRetireDisplay() = runBlocking<Unit> {
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { error("Unexpected channel probe") },
        ) { error("Unexpected helper injection") }
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { error("Unexpected shell injection") }, { _, _ -> error("Unexpected display retirement") },
        )
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 90, 100, 100))
    }

    @Test fun shellOnlyViewerRetiresDisplayWhenPostDispatchEvidenceIsMissing() = runBlocking<Unit> {
        for (exitCode in listOf(0, -1)) {
            val probes = mutableListOf<Boolean>()
            val failures = mutableListOf<Pair<Int, InputOutcome>>()
            val commands = mutableListOf<List<String>>()
            val injector = VirtualDisplayInputInjector(
                ShizukuClient(), { 42 }, { error("Unexpected direct shell injection") },
                confirmChannel = { reconnect -> probes += reconnect; commands.isEmpty() },
            )
            val viewer = VirtualDisplayViewerTouchHandler(
                VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
                { command -> commands += command.toList(); exitCode },
                { displayId, outcome -> failures += displayId to outcome },
            )

            assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
            assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 50f, 100, 120, 100, 100))
            assertEquals(listOf(listOf("input", "-d", "42", "tap", "250", "1000")), commands)
            assertEquals(listOf(true, true, false), probes)
            assertEquals(listOf(42 to InputOutcome.CHANNEL_UNAVAILABLE), failures)
        }
    }

    @Test fun malformedViewerPacketRetiresDisplayWithAnActiveRawDown() = runBlocking<Unit> {
        data class Packet(
            val description: String,
            val action: Int = MotionEvent.ACTION_MOVE,
            val x: Float = 25f,
            val width: Int = 100,
            val downTime: Long = 100,
            val eventTime: Long = 110,
        )
        val malformedPackets = listOf(
            Packet("non-finite coordinate", x = Float.NaN),
            Packet("invalid dimensions", width = 0),
            Packet("invalid MOVE timing", eventTime = 90),
            Packet("invalid replacement DOWN timing", action = MotionEvent.ACTION_DOWN, downTime = 120),
        )
        for ((description, action, x, width, downTime, eventTime) in malformedPackets) {
            val requests = mutableListOf<Int>()
            val failures = mutableListOf<Pair<Int, InputOutcome>>()
            val injector = VirtualDisplayInputInjector(
                ShizukuClient(), { 42 }, { error("Unexpected shell injection") }, confirmChannel = { true },
            ) {
                requests += it.action
                InputOutcome.CONFIRMED
            }
            val viewer = VirtualDisplayViewerTouchHandler(
                VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
                { error("Unexpected shell injection") },
                { displayId, outcome -> failures += displayId to outcome },
            )

            assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
            assertFalse(description, viewer.onViewerTouch(action, x, 50f, downTime, eventTime, width, 100))
            assertEquals(description, listOf(42 to InputOutcome.INVALID_TARGET_OR_REQUEST), failures)
            assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 50f, 100, 140, 100, 100))
            assertEquals(listOf(MotionEvent.ACTION_DOWN), requests)
        }
    }

    @Test fun malformedInitialShellDownNeverBuffersATap() = runBlocking<Unit> {
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected direct shell injection") },
            confirmChannel = { true },
        )
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { error("Unexpected shell injection") }, { _, _ -> error("Unexpected display retirement") },
        )

        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 90, 100, 100))
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 50f, 100, 120, 100, 100))
    }

    @Test fun malformedShellMoveDiscardsTheBufferedGesture() = runBlocking<Unit> {
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected direct shell injection") },
            confirmChannel = { true },
        )
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { error("Unexpected shell injection") }, { _, _ -> error("Unexpected display retirement") },
        )

        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_MOVE, 25f, 50f, 100, 90, 100, 100))
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 50f, 100, 120, 100, 100))
    }

    @Test fun viewerChecksChannelBeforeCancellingAnAcceptedDown() = runBlocking<Unit> {
        val reconnect = mutableListOf<Boolean>()
        val failures = mutableListOf<InputOutcome>()
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { allowReconnect ->
                reconnect += allowReconnect
                reconnect.size < 5
            },
        ) {
            if (it.action == MotionEvent.ACTION_DOWN) InputOutcome.CONFIRMED else InputOutcome.TIMEOUT
        }
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { error("Unexpected shell injection") }, { _, outcome -> failures += outcome },
        )
        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_MOVE, 25f, 55f, 100, 110, 100, 100))
        assertEquals(listOf(true, false, true, false, false), reconnect)
        assertEquals(1, failures.size)
    }

    @Test fun viewerRetainsBufferedShellFallbackWhenRawFallbackIsUnavailable() = runBlocking<Unit> {
        val commands = mutableListOf<List<String>>()
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected direct shell injection") },
            confirmChannel = { true },
        ) { InputOutcome.TIMEOUT }
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { command -> commands += command.toList(); 0 },
            { _, _ -> error("Unexpected display retirement") },
        )
        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
        assertTrue(commands.isEmpty())
        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 50f, 100, 120, 100, 100))
        assertEquals(listOf(listOf("input", "-d", "42", "tap", "250", "1000")), commands)
    }

    @Test fun viewerRetiresAfterHelperDeathMidGestureWithoutFallback() = runBlocking<Unit> {
        val failures = mutableListOf<InputOutcome>()
        val requests = mutableListOf<Int>()
        var probes = 0
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { probes++; true },
        ) {
            requests += it.action
            if (it.action == MotionEvent.ACTION_DOWN) InputOutcome.CONFIRMED else InputOutcome.HELPER_DIED
        }
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { 42 }, injector,
            { error("Unexpected shell injection") }, { _, outcome -> failures += outcome },
        )
        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_MOVE, 25f, 55f, 100, 110, 100, 100))
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_UP, 25f, 55f, 100, 120, 100, 100))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), requests)
        assertEquals(listOf(InputOutcome.HELPER_DIED), failures)
        assertEquals(3, probes)
    }

    @Test fun viewerPreservesEventTimingAndRefusesDefaultDisplay() = runBlocking<Unit> {
        var display = 42
        val requests = mutableListOf<TaskHelperInputRequest>()
        val injector = VirtualDisplayInputInjector(ShizukuClient(), { display }, { error("Unexpected shell injection") }, { true }) {
            requests += it
            InputOutcome.CONFIRMED
        }
        val viewer = VirtualDisplayViewerTouchHandler(
            VirtualDisplayConfig(1000, 2000, 320, 2f), { display }, injector, { error("Unexpected shell injection") },
            { _, _ -> error("Unexpected display retirement") },
        )
        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 25f, 50f, 100, 100, 100, 100))
        assertTrue(viewer.onViewerTouch(MotionEvent.ACTION_UP, 50f, 75f, 100, 120, 100, 100))
        display = 0
        assertFalse(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 50f, 75f, 130, 130, 100, 100))
        assertTrue(injector.injectTap(5, 6) is ActionResult.Failure)
        assertTrue(injector.injectSwipe(UIAction.Swipe(1, 2, 3, 4, 50)) is ActionResult.Failure)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), requests.map { it.action })
        assertEquals(listOf(100L, 100L), requests.map { it.downTime })
        assertEquals(listOf(100L, 120L), requests.map { it.eventTime })
        assertEquals(250f, requests[0].x, 0f)
        assertEquals(1500f, requests[1].y, 0f)
    }
}
