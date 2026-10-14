package ai.closepaw.ui.settings

import ai.closepaw.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the browser_script mapping table. The mapper resolves four
 * observable inputs (enable pref, gate pending, gate error, CDP probe) into a single
 * [ToolStatusUi] + optional [RowAction]; mis-ordering the precedence (e.g. showing the
 * probe result while a gate error is live) is exactly the regression these tests guard.
 *
 * Wording lives in `strings_settings_a.xml`; these tests pin which resource each state maps to.
 */
class BrowserScriptStatusMapperTest {

    // Row 1: Off, no error → "Disabled"
    @Test
    fun `pref off with no error and no pending maps to Disabled`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_disabled)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_status_mapper_toggle_to_enable)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.statusSpec.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 2: Pending (gate running) → "Setting up…"
    @Test
    fun `gate pending wins over pref off and probe state — Setting up with spinner`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = true,
            gateError = null,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_setting_up)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_status_mapper_checking_shizuku)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.statusSpec.showSpinner).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 3: Gate error (any variant) → "Needs Setup" + ClearErrorAndRetry row action
    @Test
    fun `gate error ShizukuUnavailable maps to Needs Setup with verbatim message subtitle`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuUnavailable,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_needs_setup)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_toggle_gate_shizuku_unavailable)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.statusSpec.showSpinner).isFalse()
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    @Test
    fun `gate error ShizukuPermissionDenied maps to Needs Setup with retry action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuPermissionDenied,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_needs_setup)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_toggle_gate_permission_denied)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    @Test
    fun `gate error WriteFailed maps to Needs Setup with retry action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.WriteFailed,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_needs_setup)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_toggle_gate_write_failed)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    // Row 4: On, probe Probing → "Checking…"
    @Test
    fun `pref on with probe Probing maps to Checking with spinner`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Probing,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_checking)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_status_mapper_checking_socket)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.statusSpec.showSpinner).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 5: On, probe Bound → "Ready"
    @Test
    fun `pref on with probe Bound maps to Ready Positive`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_ready)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_status_mapper_active)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(result.statusSpec.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 6: On, probe NotBound → "Needs Setup" (expanded slot owns the CTA, NOT row tap)
    @Test
    fun `pref on with probe NotBound maps to Needs Setup with no row action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.NotBound,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_needs_setup)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_status_mapper_socket_not_exposed)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.statusSpec.showSpinner).isFalse()
        // NotBound's CTA lives in the expanded slot, not on row tap — see design doc decision 3.
        assertThat(result.rowAction).isNull()
    }

    // Row 7: On, probe Unknown → "Not available"
    @Test
    fun `pref on with probe Unknown maps to Not available Warning`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_not_available)
        assertThat(result.statusSpec.subtitleRes).isEqualTo(R.string.browser_script_status_mapper_cannot_probe)
        assertThat(result.statusSpec.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.statusSpec.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Precedence — the mapper's branch order is `pending > error > !enabledPref > probe`.
    // The design doc's table doesn't enumerate combinations; pinning current behavior so a
    // re-order shows up here rather than only in production.

    @Test
    fun `gate pending wins over gate error`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = true,
            gateError = BrowserScriptToggleError.WriteFailed,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_setting_up)
        assertThat(result.rowAction).isNull()
    }

    @Test
    fun `gate error wins over pref off`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuUnavailable,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.statusSpec.labelRes).isEqualTo(R.string.browser_script_status_mapper_needs_setup)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }
}
