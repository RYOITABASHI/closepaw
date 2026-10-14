package ai.closepaw.ui.settings

import ai.closepaw.R
import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus

/**
 * Pure mapper: collapse the live Termux bridge state, the runtime permission disposition,
 * and the user's enable pref into the display-only [ToolStatusUi] the unified tool card
 * renders. No Compose dependency — covered by JVM unit tests.
 *
 * Returns string resource ids (resolved by [ToolSettingsCard]). The English label words MUST
 * stay exactly `"Ready" / "Needs Setup" / "Setting up…" / "Not Installed" / "Disabled"` in
 * `values/strings_settings_b.xml` — `SettingsTermuxRowTest` matches on them.
 */
internal fun termuxStatusUi(
    state: TermuxBridgeStatus,
    permissionDisposition: RunCommandPermissionDisposition?,
    enabledPref: Boolean,
): ToolStatusUi {
    val displayed = if (enabledPref) state else TermuxBridgeStatus.Disabled
    return ToolStatusUi(
        labelRes = displayed.labelRes(),
        subtitleRes = displayed.subtitleResFor(permissionDisposition),
        tone = displayed.tone(),
        showSpinner = displayed is TermuxBridgeStatus.SetupInProgress,
    )
}

private fun TermuxBridgeStatus.labelRes(): Int = when (this) {
    TermuxBridgeStatus.NotInstalled -> R.string.termux_status_mapper_not_installed
    is TermuxBridgeStatus.NeedsSetup -> R.string.termux_status_mapper_needs_setup
    TermuxBridgeStatus.SetupInProgress -> R.string.termux_status_mapper_setting_up
    TermuxBridgeStatus.Ready -> R.string.termux_status_mapper_ready
    TermuxBridgeStatus.Disabled -> R.string.termux_status_mapper_disabled
}

private fun TermuxBridgeStatus.tone(): ToolStatusTone = when (this) {
    TermuxBridgeStatus.Ready -> ToolStatusTone.Positive
    TermuxBridgeStatus.NotInstalled,
    is TermuxBridgeStatus.NeedsSetup -> ToolStatusTone.Warning
    TermuxBridgeStatus.SetupInProgress,
    TermuxBridgeStatus.Disabled -> ToolStatusTone.Neutral
}

private fun TermuxBridgeStatus.subtitleRes(): Int = when (this) {
    TermuxBridgeStatus.NotInstalled -> R.string.termux_status_mapper_sub_not_installed
    is TermuxBridgeStatus.NeedsSetup -> reason.toDisplayTextRes()
    TermuxBridgeStatus.SetupInProgress -> R.string.termux_status_mapper_sub_setup_in_progress
    TermuxBridgeStatus.Ready -> R.string.termux_status_mapper_sub_ready
    TermuxBridgeStatus.Disabled -> R.string.termux_status_mapper_sub_disabled
}

/**
 * PERMISSION_MISSING is the only reason whose subtitle depends on the runtime disposition.
 * `OpenAppSettings` means the user picked "Don't ask again", so the system dialog no longer
 * surfaces — point them to App Settings instead of "tap to grant".
 */
private fun TermuxBridgeStatus.subtitleResFor(
    permissionDisposition: RunCommandPermissionDisposition?,
): Int {
    if (this is TermuxBridgeStatus.NeedsSetup &&
        reason == NeedsSetupReason.PERMISSION_MISSING
    ) {
        return when (permissionDisposition) {
            RunCommandPermissionDisposition.OpenAppSettings ->
                R.string.termux_status_mapper_permission_permanently_denied
            RunCommandPermissionDisposition.Request,
            RunCommandPermissionDisposition.Granted,
            null -> R.string.termux_status_mapper_tap_to_grant
        }
    }
    return subtitleRes()
}

private fun NeedsSetupReason.toDisplayTextRes(): Int = when (this) {
    NeedsSetupReason.PERMISSION_MISSING -> R.string.termux_status_mapper_tap_to_grant
    NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING ->
        R.string.termux_status_mapper_allow_external_apps_missing
    NeedsSetupReason.TERMUX_NOT_RUNNING -> R.string.termux_status_mapper_termux_not_running
    NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE ->
        R.string.termux_status_mapper_run_command_unavailable
    NeedsSetupReason.PACKAGES_MISSING -> R.string.termux_status_mapper_packages_missing
    NeedsSetupReason.BRIDGE_OUTDATED -> R.string.termux_status_mapper_bridge_outdated
    NeedsSetupReason.HEALTH_TIMEOUT -> R.string.termux_status_mapper_health_timeout
    NeedsSetupReason.TERMUX_TIMEOUT -> R.string.termux_status_mapper_termux_timeout
    NeedsSetupReason.PORT_IN_USE -> R.string.termux_status_mapper_port_in_use
    NeedsSetupReason.UNKNOWN -> R.string.termux_status_mapper_unknown
}
