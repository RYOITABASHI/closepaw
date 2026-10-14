package ai.closepaw.ui.overlay.model

import ai.closepaw.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapsuleRenderSpecTest {

    @Test
    fun `running mode shows stopping disabled when stop pending`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.Running("thinking"),
            previousMode = null,
            isStopPending = true,
        )

        val stop = spec.buttons.stop
        assertThat(stop).isNotNull()
        assertThat(stop?.textRes).isEqualTo(R.string.capsule_render_spec_stopping)
        assertThat(stop?.enabled).isFalse()
    }

    @Test
    fun `error mode keeps close action even when stop pending`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.Error("oops"),
            previousMode = null,
            isStopPending = true,
        )

        val stop = spec.buttons.stop
        assertThat(stop).isNotNull()
        assertThat(stop?.textRes).isEqualTo(R.string.capsule_render_spec_close)
        assertThat(stop?.enabled).isTrue()
    }

    @Test
    fun `entering WaitingForInput from a different mode sets clearInput true`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.WaitingForInput("question?", callId = "c1"),
            previousMode = CapsuleMode.Running("thinking"),
        )
        assertThat(spec.input?.clearDraft).isTrue()
    }

    @Test
    fun `re-entering WaitingForInput from itself leaves clearInput false`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.WaitingForInput("another?", callId = "c2"),
            previousMode = CapsuleMode.WaitingForInput("first?", callId = "c1"),
        )
        assertThat(spec.input?.clearDraft).isFalse()
    }

    @Test
    fun `waiting for approval renders app-level three-button prompt`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.WaitingForApproval(
                callId = "approval-1",
                description = "Tap OK",
                appLabel = "Chrome",
                packageName = "com.android.chrome",
                reason = "Unknown app requires approval",
            )
        )

        assertThat(spec.thought.textRes).isEqualTo(R.string.capsule_render_spec_approval_prompt)
        assertThat(spec.thought.formatArgs).containsExactly("Chrome")
        assertThat(spec.expandedBody).isNull()
        assertThat(spec.buttons.primary?.textRes).isEqualTo(R.string.capsule_render_spec_always)
        assertThat(spec.buttons.secondary?.textRes).isEqualTo(R.string.capsule_render_spec_session)
        assertThat(spec.buttons.stop?.textRes).isEqualTo(R.string.capsule_render_spec_reject)
    }
}
