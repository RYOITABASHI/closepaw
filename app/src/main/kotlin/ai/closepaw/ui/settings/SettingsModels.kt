package ai.closepaw.ui.settings

import ai.closepaw.R
import ai.closepaw.llm.ModelEntry
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Converts ModelEntry list to (id, displayName) pairs for dropdowns. */
fun catalogModelOptions(entries: List<ModelEntry>): List<Pair<String, String>> =
    entries.map { it.name to it.displayName }

/** Local LLM models available in Leap SDK. See LeapDownloadableModel.resolve() for catalog. */
internal val AVAILABLE_LOCAL_MODELS = listOf(
    LocalModelOption(
        id = "LFM2.5-1.2B-Instruct",
        displayName = "LFM 1.2B Instruct (Recommended)",
        modelSlug = "LFM2.5-1.2B-Instruct",
        quantizationSlug = "Q4_K_M",
        description = "On-device inference, ~1.2B parameters, tool-calling support",
        displayNameRes = R.string.settings_models_lfm_display_name,
        descriptionRes = R.string.settings_models_lfm_description,
    )
)

data class LocalModelOption(
    val id: String,
    val displayName: String,
    val modelSlug: String,
    val quantizationSlug: String,
    val description: String,
    /** Localized UI label; 0 falls back to [displayName]. */
    @StringRes val displayNameRes: Int = 0,
    /** Localized UI description; 0 falls back to [description]. */
    @StringRes val descriptionRes: Int = 0,
)

/** UI label for this model, localized when a resource is available. */
@Composable
internal fun LocalModelOption.localizedDisplayName(): String =
    if (displayNameRes != 0) stringResource(displayNameRes) else displayName

/** UI description for this model, localized when a resource is available. */
@Composable
internal fun LocalModelOption.localizedDescription(): String =
    if (descriptionRes != 0) stringResource(descriptionRes) else description

sealed interface ModelLoadingStatus {
    data object Idle : ModelLoadingStatus
    data class Downloading(val progress: Float) : ModelLoadingStatus
    data object Loading : ModelLoadingStatus
    data object Ready : ModelLoadingStatus
    data class Error(val message: String) : ModelLoadingStatus
}
