package ai.closepaw.ui.overlay.model

import ai.closepaw.R
import ai.closepaw.protocol.PlatformMode
import androidx.annotation.StringRes

/**
 * CapsuleRenderSpec — pure rendering specification derived from CapsuleMode.
 *
 * Maps CapsuleMode → visual properties. Both overlay and in-app Compose renderers
 * read from this spec. This is the SINGLE source of truth for "what does the
 * capsule look like in each mode."
 *
 * No business logic. No callbacks. No state management.
 * Just data that renderers mechanically apply to their UI framework.
 */
data class CapsuleRenderSpec(
    val dot: DotSpec?,
    val thought: ThoughtSpec,
    val expandedBody: String?,
    val buttons: ButtonsSpec,
    val input: InputSpec?,
) {
    /** Status dot configuration. null = dot hidden. */
    data class DotSpec(val status: GlowState, val pulsing: Boolean)

    /**
     * Status-line text (the agent's thought).
     *
     * Either free [text] (agent output) or a localized [textRes] with optional
     * [formatArgs]; when [textRes] is non-null the UI layer resolves it and
     * ignores [text].
     */
    data class ThoughtSpec(
        val text: String = "",
        @StringRes val textRes: Int? = null,
        val formatArgs: List<String> = emptyList(),
        val dimmed: Boolean = false,
    )

    /** A single button in the control bar. The Compose layer chooses the icon. */
    data class ButtonSpec(@StringRes val textRes: Int, val enabled: Boolean = true)

    enum class ControlLayout {
        SharedWithNav,
        RequiredActionsOnly,
    }

    /** Control-bar button configuration. null fields = button hidden. */
    data class ButtonsSpec(
        val primary: ButtonSpec?,
        val secondary: ButtonSpec? = null,
        val stop: ButtonSpec?,
        val layout: ControlLayout = ControlLayout.SharedWithNav,
    )

    /** Input-bar specification. null = input bar hidden. */
    data class InputSpec(
        @StringRes val hintRes: Int,
        @StringRes val submitLabelRes: Int,
        val clearDraft: Boolean = false,
    )

    companion object {
        /**
         * Derive the render spec from a CapsuleMode.
         * [previousMode] is used to decide whether to clear the input-bar draft
         * on transitions into WaitingForInput.
         */
        fun from(
            mode: CapsuleMode,
            previousMode: CapsuleMode? = null,
            isStopPending: Boolean = false,
        ): CapsuleRenderSpec =
            when (mode) {
                is CapsuleMode.Running -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Active, pulsing = true),
                    thought = freeOrRes(
                        mode.thought,
                        mode.thoughtRes ?: R.string.capsule_render_spec_thinking,
                        preferRes = mode.thoughtRes != null,
                    ),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(R.string.capsule_render_spec_takeover),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec(R.string.capsule_render_spec_note_hint, R.string.capsule_render_spec_add_note),
                )

                is CapsuleMode.TakeoverPending -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec(textRes = R.string.capsule_render_spec_handing_over_status),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(R.string.capsule_render_spec_handing_over, enabled = false),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec(R.string.capsule_render_spec_note_hint, R.string.capsule_render_spec_add_note),
                )

                is CapsuleMode.Takeover -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = freeOrRes(
                        mode.lastThought,
                        mode.lastThoughtRes ?: R.string.capsule_render_spec_paused,
                        preferRes = mode.lastThoughtRes != null,
                    ).copy(dimmed = true),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(R.string.capsule_render_spec_resume),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec(R.string.capsule_render_spec_note_hint, R.string.capsule_render_spec_add_note),
                )

                is CapsuleMode.WaitingForInput -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(textRes = R.string.capsule_render_spec_awaiting_response),
                    expandedBody = mode.question,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = stopButtonSpec(isStopPending),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = InputSpec(
                        hintRes = R.string.capsule_render_spec_response_hint,
                        submitLabelRes = R.string.capsule_render_spec_send,
                        clearDraft = previousMode != null
                            && previousMode !is CapsuleMode.WaitingForInput,
                    ),
                )

                is CapsuleMode.WaitingForAction -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(textRes = R.string.capsule_render_spec_action_needed),
                    expandedBody = mode.instruction,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(R.string.capsule_render_spec_done),
                        stop = stopButtonSpec(isStopPending),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = null,
                )

                is CapsuleMode.WaitingForApproval -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec(
                        textRes = R.string.capsule_render_spec_approval_prompt,
                        formatArgs = listOf(mode.appLabel),
                    ),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(R.string.capsule_render_spec_always),
                        secondary = ButtonSpec(R.string.capsule_render_spec_session),
                        stop = ButtonSpec(R.string.capsule_render_spec_reject),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = null,
                )

                is CapsuleMode.Done -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Success, pulsing = false),
                    thought = ThoughtSpec(text = mode.message, textRes = mode.messageRes),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    input = null,
                )

                is CapsuleMode.Error -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Error, pulsing = false),
                    thought = ThoughtSpec(text = mode.message, textRes = mode.messageRes),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = ButtonSpec(R.string.capsule_render_spec_close),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = null,
                )

                is CapsuleMode.Hidden -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    input = InputSpec(R.string.capsule_render_spec_task_hint, R.string.capsule_render_spec_send),
                )
            }

        private fun stopButtonSpec(isStopPending: Boolean): ButtonSpec =
            if (isStopPending) ButtonSpec(R.string.capsule_render_spec_stopping, enabled = false)
            else ButtonSpec(R.string.capsule_render_spec_stop)

        /**
         * Free agent text wins unless it is empty or a localized status id was
         * explicitly supplied ([preferRes]); then the resource is used.
         */
        private fun freeOrRes(text: String, @StringRes res: Int, preferRes: Boolean): ThoughtSpec =
            if (preferRes || text.isEmpty()) ThoughtSpec(textRes = res)
            else ThoughtSpec(text = text)
    }
}

/**
 * NavSpec — navigation button visibility derived from context.
 *
 * Separate from CapsuleRenderSpec because nav visibility depends on
 * CapsuleContext + PlatformMode, not CapsuleMode.
 */
data class NavSpec(
    val showMinimize: Boolean,
    val showApp: Boolean,
    val showWatch: Boolean,
) {
    companion object {
        fun from(
            context: CapsuleContext,
            platformMode: PlatformMode,
            hasIsland: Boolean,
            mode: CapsuleMode? = null,
        ): NavSpec {
            // Done mode hides the entire control bar, so its nav cluster must also hide.
            val controlBarHidden = mode is CapsuleMode.Done

            return NavSpec(
                showMinimize = !controlBarHidden
                    && hasIsland
                    && context != CapsuleContext.MAIN_APP
                    && mode !is CapsuleMode.WaitingForInput
                    && mode !is CapsuleMode.WaitingForAction
                    && mode !is CapsuleMode.WaitingForApproval
                    && mode !is CapsuleMode.Error,
                showApp = !controlBarHidden
                    && context != CapsuleContext.MAIN_APP
                    && platformMode != PlatformMode.ACCESSIBILITY,
                showWatch = !controlBarHidden
                    && platformMode != PlatformMode.ACCESSIBILITY
                    && context != CapsuleContext.SCREEN_VIEWING,
            )
        }
    }
}
