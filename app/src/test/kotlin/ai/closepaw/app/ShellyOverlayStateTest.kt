package ai.closepaw.app

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.overlay.model.CapsuleMode
import org.junit.Test

/**
 * Ordering rules of the Shelly background-run overlay state that ServiceOverlayController feeds
 * into [deriveOverlayVisibility].
 */
class ShellyOverlayStateTest {

    private val running = CapsuleMode.Running("thinking")
    private val done = CapsuleMode.Done("done")
    private val approval = CapsuleMode.WaitingForApproval(
        callId = "1",
        description = "open Settings",
        appLabel = "Settings",
        packageName = "com.android.settings",
        reason = "test",
    )

    /** What applyVisibility would decide for the user in another app on the main display. */
    private fun decide(state: ShellyOverlayState, mode: CapsuleMode, hasActiveTask: Boolean) =
        deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.OTHER_APP,
            mode = mode,
            hasActiveTask = hasActiveTask,
            showPreference = ShowPreference.CAPSULE,
            shellyBackgroundRun = state.active,
            shellyRunOnMainDisplay = state.onMainDisplay,
        )

    @Test
    fun `off by default`() {
        val state = ShellyOverlayState()
        assertThat(state.active).isFalse()
        assertThat(state.onMainDisplay).isFalse()
        assertThat(state.releasePending).isFalse()
        // Non-Shelly runs keep the capsule while running in another app.
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isTrue()
    }

    @Test
    fun `start activates and records the display`() {
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        assertThat(state.active).isTrue()
        assertThat(state.onMainDisplay).isFalse()
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isFalse()
    }

    @Test
    fun `update display only reports a real change while active`() {
        val state = ShellyOverlayState()
        assertThat(state.updateDisplay(onMainDisplay = true)).isFalse()
        assertThat(state.onMainDisplay).isFalse()

        state.start(onMainDisplay = false)
        assertThat(state.updateDisplay(onMainDisplay = false)).isFalse()
        assertThat(state.updateDisplay(onMainDisplay = true)).isTrue()
        assertThat(state.onMainDisplay).isTrue()
        // A run on the main display is never suppressed.
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isTrue()
    }

    @Test
    fun `tracker finishing before the overlay keeps Done invisible until Hidden`() {
        // AgentService feeds the tracker first: TaskCompleted retires the tracker (release) while
        // the capsule is still Running, then the overlay turns Done, then auto-hides.
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)

        assertThat(state.release(running)).isFalse()
        assertThat(state.active).isTrue()
        assertThat(state.releasePending).isTrue()

        assertThat(state.onMode(done)).isFalse()
        val doneDecision = decide(state, done, hasActiveTask = false)
        assertThat(doneDecision.showCapsule).isFalse()
        assertThat(doneDecision.showIsland).isFalse()
        assertThat(doneDecision.showGlow).isFalse()

        assertThat(state.onMode(CapsuleMode.Hidden)).isTrue()
        assertThat(state.active).isFalse()
        assertThat(state.releasePending).isFalse()
    }

    @Test
    fun `release while already hidden drops immediately`() {
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        assertThat(state.release(CapsuleMode.Hidden)).isTrue()
        assertThat(state.active).isFalse()
        assertThat(state.releasePending).isFalse()
    }

    @Test
    fun `release without a run is a no-op`() {
        val state = ShellyOverlayState()
        assertThat(state.release(running)).isFalse()
        assertThat(state.releasePending).isFalse()
        assertThat(state.onMode(CapsuleMode.Hidden)).isFalse()
    }

    @Test
    fun `hidden without a pending release does not drop an active run`() {
        // The capsule is Hidden between startShellyRun and the session's first task.
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        assertThat(state.onMode(CapsuleMode.Hidden)).isFalse()
        assertThat(state.active).isTrue()
    }

    @Test
    fun `follow-up task in the finished shelly session is visible again`() {
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        state.release(done)
        // MainActivity rebinds to the session and sends a follow-up before Done auto-hides.
        state.onTaskStarted()
        assertThat(state.active).isFalse()
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isTrue()
    }

    @Test
    fun `task start of an active shelly run keeps it invisible`() {
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        state.onTaskStarted()
        assertThat(state.active).isTrue()
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isFalse()
    }

    @Test
    fun `clear drops an active or pending run and reports it`() {
        val state = ShellyOverlayState()
        assertThat(state.clear()).isFalse()

        state.start(onMainDisplay = false)
        assertThat(state.clear()).isTrue()
        assertThat(state.active).isFalse()

        state.start(onMainDisplay = false)
        state.release(running)
        assertThat(state.clear()).isTrue()
        assertThat(state.releasePending).isFalse()
        assertThat(state.active).isFalse()
    }

    @Test
    fun `a new start cancels a pending release`() {
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        state.release(running)
        state.start(onMainDisplay = false)
        assertThat(state.releasePending).isFalse()
        assertThat(state.onMode(CapsuleMode.Hidden)).isFalse()
        assertThat(state.active).isTrue()
    }

    @Test
    fun `approval shows the capsule and answering it hides it again`() {
        val state = ShellyOverlayState()
        state.start(onMainDisplay = false)
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isFalse()
        assertThat(decide(state, approval, hasActiveTask = true).showCapsule).isTrue()
        // Answered from the notification: the mode collector re-applies on the mode-kind change.
        assertThat(decide(state, running, hasActiveTask = true).showCapsule).isFalse()
    }
}
