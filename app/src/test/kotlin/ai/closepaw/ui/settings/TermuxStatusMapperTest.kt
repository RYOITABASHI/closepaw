package ai.closepaw.ui.settings

import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the Termux mapping table.
 *
 * The label words are load-bearing — `SettingsTermuxRowTest` (instrumentation) matches on
 * them. Tone / spinner / subtitle assertions guard against
 * silent UX regressions when someone edits the mapper.
 */
class TermuxStatusMapperTest {

    @Test
    fun `Disabled state maps to Disabled label, Neutral tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Disabled,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.labelRes).isEqualTo(R.string.termux_status_mapper_disabled)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_sub_disabled)
    }

    @Test
    fun `enabledPref false forces Disabled regardless of underlying state`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Ready,
            permissionDisposition = null,
            enabledPref = false,
        )
        assertThat(ui.labelRes).isEqualTo(R.string.termux_status_mapper_disabled)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_sub_disabled)
    }

    @Test
    fun `NotInstalled maps to Not Installed label, Warning tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NotInstalled,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.labelRes).isEqualTo(R.string.termux_status_mapper_not_installed)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_sub_not_installed)
    }

    @Test
    fun `NeedsSetup maps to Needs Setup label, Warning tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.labelRes).isEqualTo(R.string.termux_status_mapper_needs_setup)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(ui.showSpinner).isFalse()
    }

    @Test
    fun `SetupInProgress maps to Setting up label, Neutral tone, spinner shown`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.SetupInProgress,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.labelRes).isEqualTo(R.string.termux_status_mapper_setting_up)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isTrue()
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_sub_setup_in_progress)
    }

    @Test
    fun `Ready maps to Ready label, Positive tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Ready,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.labelRes).isEqualTo(R.string.termux_status_mapper_ready)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_sub_ready)
    }

    // Below: NeedsSetupReason subtitle matrix. The design table only requires the umbrella
    // label "Needs Setup" / Warning tone for every NeedsSetup variant, but the subtitle is
    // what tells the user what to actually do. Pin each reason so a future copy edit shows
    // up in CI rather than only in the user's hands.
    //
    // Design doc is the source of truth for label wording — these tests pin current subtitle
    // wording of the mapper.

    @Test
    fun `NeedsSetup PERMISSION_MISSING with null disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_tap_to_grant)
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with Request disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.Request,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_tap_to_grant)
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with Granted disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.Granted,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_tap_to_grant)
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with OpenAppSettings disposition redirects to App Settings`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.OpenAppSettings,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_permission_permanently_denied)
    }

    @Test
    fun `NeedsSetup ALLOW_EXTERNAL_APPS_MISSING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_allow_external_apps_missing)
    }

    @Test
    fun `NeedsSetup TERMUX_NOT_RUNNING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_NOT_RUNNING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_termux_not_running)
    }

    @Test
    fun `NeedsSetup TERMUX_RUN_COMMAND_UNAVAILABLE subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_run_command_unavailable)
    }

    @Test
    fun `NeedsSetup PACKAGES_MISSING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_packages_missing)
    }

    @Test
    fun `NeedsSetup BRIDGE_OUTDATED subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.BRIDGE_OUTDATED),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_bridge_outdated)
    }

    @Test
    fun `NeedsSetup HEALTH_TIMEOUT subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_health_timeout)
    }

    @Test
    fun `NeedsSetup TERMUX_TIMEOUT subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_TIMEOUT),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_termux_timeout)
    }

    @Test
    fun `NeedsSetup PORT_IN_USE subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PORT_IN_USE),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_port_in_use)
    }

    @Test
    fun `NeedsSetup UNKNOWN subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.UNKNOWN),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitleRes).isEqualTo(R.string.termux_status_mapper_unknown)
    }
}
