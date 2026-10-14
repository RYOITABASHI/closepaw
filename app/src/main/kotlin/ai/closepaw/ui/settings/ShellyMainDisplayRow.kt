package ai.closepaw.ui.settings

import ai.closepaw.R
import ai.closepaw.app.ShellyRunPrefs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * "Allow Shelly to control the main screen" (default OFF). Only when this is ON does a verified
 * Shelly request's allow_main_display opt-in let a background run fall back to the main display
 * when the Shizuku virtual display is unavailable.
 */
@Composable
internal fun ShellyMainDisplayRow() {
    val context = LocalContext.current.applicationContext
    var allowed by remember(context) { mutableStateOf(ShellyRunPrefs.isMainDisplayAllowed(context)) }
    ToolSettingsCard(
        title = stringResource(R.string.shelly_main_display_setting_title),
        status = ToolStatusUi(
            label = stringResource(
                if (allowed) R.string.shelly_main_display_setting_on_label
                else R.string.shelly_main_display_setting_off_label
            ),
            subtitle = stringResource(
                if (allowed) R.string.shelly_main_display_setting_on
                else R.string.shelly_main_display_setting_off
            ),
            tone = if (allowed) ToolStatusTone.Warning else ToolStatusTone.Neutral,
        ),
        switchChecked = allowed,
        onSwitchChange = { checked ->
            ShellyRunPrefs.setMainDisplayAllowed(context, checked)
            allowed = checked
        },
    )
}
