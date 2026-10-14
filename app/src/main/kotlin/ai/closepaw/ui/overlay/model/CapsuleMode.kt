package ai.closepaw.ui.overlay.model

import androidx.annotation.StringRes

/**
 * CapsuleMode — the single source of truth for Smart Capsule UI.
 *
 * One value drives the entire capsule rendering.
 * No boolean soup, no ambient state, no side channels.
 * You look at the mode, you know exactly what to draw.
 *
 * Text fields carry either free text (agent output) or, for app-authored
 * status copy, a localized string resource id (`*Res`). When a `*Res` id is
 * non-null it wins over the raw text; renderers resolve it at the UI layer.
 */
sealed interface CapsuleMode {

    /** Agent is actively executing. Shows thought + controls. */
    data class Running(
        val thought: String,
        @StringRes val thoughtRes: Int? = null,
    ) : CapsuleMode

    /** User requested takeover, waiting for current action to finish. */
    data class TakeoverPending(
        val lastThought: String,
        @StringRes val lastThoughtRes: Int? = null,
    ) : CapsuleMode

    /** User has control. Agent paused. Shows dimmed thought + [Resume][Stop]. */
    data class Takeover(
        val lastThought: String,
        @StringRes val lastThoughtRes: Int? = null,
    ) : CapsuleMode

    /** Agent asked a question, waiting for text answer. Capsule expanded. */
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode

    /** Agent asked user to do something on phone. Capsule expanded. */
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode

    /** Agent needs user approval to operate an app. Shows Always/Session/Reject buttons. */
    data class WaitingForApproval(
        val callId: String,
        val description: String,  // action description for logs/traces, not prompt copy
        val appLabel: String,     // "Chrome" (resolved from packageName)
        val packageName: String,  // package that owns the app-level approval
        val reason: String        // policy reason for logs/traces, not prompt copy
    ) : CapsuleMode

    /** Task completed successfully. Auto-hides after 3s. */
    data class Done(
        val message: String,
        @StringRes val messageRes: Int? = null,
    ) : CapsuleMode

    /** Error occurred. Stays until dismissed. */
    data class Error(
        val message: String,
        @StringRes val messageRes: Int? = null,
    ) : CapsuleMode

    /** No active task. Capsule hidden. */
    data object Hidden : CapsuleMode
}
