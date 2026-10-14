package ai.closepaw.ui.settings

import ai.closepaw.app.ShellyRunNotifier
import ai.closepaw.app.ShellyRunPrefs
import ai.closepaw.app.ShellyRunText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * "Allow Shelly to control the main screen" (default OFF). Only when this is ON does a verified
 * Shelly request's allow_main_display opt-in let a background run fall back to the main display
 * when the Shizuku virtual display is unavailable.
 */
@Composable
internal fun ShellyMainDisplayRow() {
    val context = LocalContext.current.applicationContext
    val text = remember(context) { ShellyRunText(ShellyRunNotifier.isJapanese(context)) }
    var allowed by remember(context) { mutableStateOf(ShellyRunPrefs.isMainDisplayAllowed(context)) }
    ToolSettingsCard(
        title = text.mainDisplaySettingTitle,
        status = ToolStatusUi(
            label = if (allowed) "On" else "Off",
            subtitle = if (allowed) text.mainDisplaySettingOn else text.mainDisplaySettingOff,
            tone = if (allowed) ToolStatusTone.Warning else ToolStatusTone.Neutral,
        ),
        switchChecked = allowed,
        onSwitchChange = { checked ->
            ShellyRunPrefs.setMainDisplayAllowed(context, checked)
            allowed = checked
        },
    )
}
