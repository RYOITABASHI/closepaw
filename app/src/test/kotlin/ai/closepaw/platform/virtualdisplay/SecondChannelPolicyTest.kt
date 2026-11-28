package ai.closepaw.platform.virtualdisplay

import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.SystemButtonType
import ai.closepaw.platform.UIAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondChannelPolicyTest {
    private fun decide(
        enabled: Boolean = true,
        alive: Boolean = true,
        uid: Int? = 2000,
        target: Int? = 42,
        exists: Boolean = true,
        displayIdInjection: Boolean = true,
        observe: Boolean = true,
    ) = SecondChannelPolicy.decide(enabled, alive, uid, target, exists, displayIdInjection, observe)

    private fun refuse(reason: String) = SecondChannelPolicy.Decision.Refuse(reason)

    @Test fun allowsOnlyWhenEveryGuardHolds() {
        assertEquals(SecondChannelPolicy.Decision.Allow, decide())
    }

    @Test fun eachMissingGuaranteeRefuses() {
        assertEquals(refuse("disabled"), decide(enabled = false))
        assertEquals(refuse("shizuku_unavailable"), decide(alive = false))
        assertEquals(refuse("server_uid_unknown"), decide(uid = null))
        // Root-run Shizuku is refused (guard 4).
        assertEquals(refuse("server_uid_0"), decide(uid = 0))
        assertEquals(refuse("server_uid_10123"), decide(uid = 10123))
        // Display 0 and a missing VD are refused (guard 3).
        assertEquals(refuse("no_virtual_display"), decide(target = 0))
        assertEquals(refuse("no_virtual_display"), decide(target = null))
        assertEquals(refuse("display_missing"), decide(exists = false))
        // Without setDisplayId the incumbent path would use the shell, i.e. the adb channel.
        assertEquals(refuse("display_id_injection_unsupported"), decide(displayIdInjection = false))
        // No blind input (guard 5).
        assertEquals(refuse("unobservable"), decide(observe = false))
    }

    @Test fun shippedDisabledByDefault() {
        assertFalse(SecondChannelPolicy.ENABLED)
        val probe = object : SecondChannelProbe {
            override fun binderAlive() = true
            override fun serverUid() = 2000
            override fun displayExists(displayId: Int) = true
            override fun canObserve() = true
        }
        assertFalse(probe.enabled())
    }

    private class FakeProbe(
        val enabled: Boolean = true,
        val uid: Int? = 2000,
        val exists: Boolean = true,
        val observe: Boolean = true,
    ) : SecondChannelProbe {
        var displayChecks = 0
        override fun enabled() = enabled
        override fun binderAlive() = true
        override fun serverUid() = uid
        override fun displayExists(displayId: Int): Boolean { displayChecks++; return exists }
        override fun canObserve() = observe
    }

    @Test fun channelDownWithoutAProbeStaysUnavailableAndNeverTouchesTheShell() = runBlocking<Unit> {
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
            confirmChannel = { false },
        )
        val result = injector.injectTap(1, 2) as ActionResult.Failure
        assertTrue(result.reason.contains(InputOutcome.CHANNEL_UNAVAILABLE.name))
    }

    @Test fun refusedSecondChannelNeverInjectsOnAnyRoute() = runBlocking<Unit> {
        // JVM: setDisplayId cannot be verified, so even an otherwise-allowed probe is refused and
        // neither the helper nor the shell (the adb channel) is used.
        for (probe in listOf(FakeProbe(), FakeProbe(enabled = false), FakeProbe(uid = 0),
            FakeProbe(exists = false), FakeProbe(observe = false))) {
            var helperCalls = 0
            val injector = VirtualDisplayInputInjector(
                ShizukuClient(), { 42 }, { error("Unexpected shell injection") },
                confirmChannel = { false },
                helper = { helperCalls++; InputOutcome.CONFIRMED },
                secondChannel = probe,
            )
            for (result in listOf(
                injector.injectTap(1, 2),
                injector.injectLongPress(1, 2, 500),
                injector.injectSwipe(UIAction.Swipe(1, 2, 3, 4, 300)),
                injector.injectSystemButton(SystemButtonType.ENTER),
            )) {
                assertTrue((result as ActionResult.Failure).reason.contains(InputOutcome.CHANNEL_UNAVAILABLE.name))
            }
            assertEquals(0, helperCalls)
            assertEquals("adb", injector.lastInputChannel)
        }
    }

    @Test fun channelStateMarksDegradationAndClearsOnReturnToAdb() {
        VdInputChannelState.record("adb", 1)
        assertEquals(null, VdInputChannelState.degradedSinceMs)
        VdInputChannelState.record("shizuku", 100)
        VdInputChannelState.record("shizuku", 200)
        assertEquals("shizuku", VdInputChannelState.channel)
        assertEquals(100L, VdInputChannelState.degradedSinceMs)  // first degraded operation
        VdInputChannelState.record("adb", 300)
        assertEquals("adb", VdInputChannelState.channel)
        assertEquals(null, VdInputChannelState.degradedSinceMs)
    }

    @Test fun defaultDisplayIsNeverASecondChannelTarget() = runBlocking<Unit> {
        val probe = FakeProbe()
        val injector = VirtualDisplayInputInjector(
            ShizukuClient(), { 0 }, { error("Unexpected shell injection") },
            confirmChannel = { false },
            secondChannel = probe,
        )
        assertTrue(injector.injectTap(1, 2) is ActionResult.Failure)
        assertEquals(0, probe.displayChecks)
    }
}
