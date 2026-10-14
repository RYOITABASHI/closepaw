package ai.closepaw.ui.settings

import ai.closepaw.R
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Possible CDP probe states the unified card needs to render. Wraps [ChromeCdpProbe.Result]
 * with an additional [Probing] state for the brief window the probe coroutine is in flight.
 * Lives in the mapper file (not the probe class) because it's a display concern.
 */
internal sealed interface BrowserScriptProbeState {
    data object Probing : BrowserScriptProbeState
    data object Bound : BrowserScriptProbeState
    data object NotBound : BrowserScriptProbeState
    data object Unknown : BrowserScriptProbeState
}

/**
 * What the card's row-tap should do, computed by the mapper so the composable doesn't need
 * to re-derive it from raw state. Null in [BrowserScriptStatusResult.rowAction] means the
 * row is non-interactive (the Switch is still tappable independently).
 */
internal sealed class RowAction {
    /** Wipe the inline gate error and re-attempt enabling — mirrors the Switch's tap contract. */
    data object ClearErrorAndRetry : RowAction()
    /** Explicit no-op; mappers may emit null instead to mean the same thing. */
    data object None : RowAction()
}

/**
 * Context-free description of the card status: string resource ids instead of resolved
 * text, so the mapper stays a pure JVM-testable function. Resolved to a [ToolStatusUi]
 * at the Compose layer via [BrowserScriptStatusResult.status].
 */
internal data class BrowserScriptStatusSpec(
    @StringRes val labelRes: Int,
    @StringRes val subtitleRes: Int,
    val tone: ToolStatusTone,
    val showSpinner: Boolean = false,
)

internal data class BrowserScriptStatusResult(
    val statusSpec: BrowserScriptStatusSpec,
    val rowAction: RowAction?,
)

/** Resolves [BrowserScriptStatusResult.statusSpec] into display text for [ToolSettingsCard]. */
internal val BrowserScriptStatusResult.status: ToolStatusUi
    @Composable get() = ToolStatusUi(
        label = stringResource(statusSpec.labelRes),
        subtitle = stringResource(statusSpec.subtitleRes),
        tone = statusSpec.tone,
        showSpinner = statusSpec.showSpinner,
    )

/**
 * Pure mapper: collapse the four observable inputs (enable pref, gate pending flag, gate
 * error, CDP probe result) into the unified card's status spec plus an optional row
 * action. Mapping table is the source of truth in `design_claude.md`.
 *
 * Probe state is ignored when the pref is off or the gate is busy/errored — those states
 * always win because the probe result is meaningless if the tool isn't actually enabled.
 */
internal fun browserScriptStatusUi(
    enabledPref: Boolean,
    gatePending: Boolean,
    gateError: BrowserScriptToggleError?,
    probeResult: BrowserScriptProbeState,
): BrowserScriptStatusResult {
    if (gatePending) {
        return BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_setting_up,
                subtitleRes = R.string.browser_script_status_mapper_checking_shizuku,
                tone = ToolStatusTone.Neutral,
                showSpinner = true,
            ),
            rowAction = null,
        )
    }
    if (gateError != null) {
        return BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_needs_setup,
                subtitleRes = gateError.messageRes(),
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            rowAction = RowAction.ClearErrorAndRetry,
        )
    }
    if (!enabledPref) {
        return BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_disabled,
                subtitleRes = R.string.browser_script_status_mapper_toggle_to_enable,
                tone = ToolStatusTone.Neutral,
                showSpinner = false,
            ),
            rowAction = null,
        )
    }
    return when (probeResult) {
        BrowserScriptProbeState.Probing -> BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_checking,
                subtitleRes = R.string.browser_script_status_mapper_checking_socket,
                tone = ToolStatusTone.Neutral,
                showSpinner = true,
            ),
            rowAction = null,
        )
        BrowserScriptProbeState.Bound -> BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_ready,
                subtitleRes = R.string.browser_script_status_mapper_active,
                tone = ToolStatusTone.Positive,
                showSpinner = false,
            ),
            rowAction = null,
        )
        BrowserScriptProbeState.NotBound -> BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_needs_setup,
                subtitleRes = R.string.browser_script_status_mapper_socket_not_exposed,
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            rowAction = null,
        )
        BrowserScriptProbeState.Unknown -> BrowserScriptStatusResult(
            statusSpec = BrowserScriptStatusSpec(
                labelRes = R.string.browser_script_status_mapper_not_available,
                subtitleRes = R.string.browser_script_status_mapper_cannot_probe,
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            rowAction = null,
        )
    }
}
