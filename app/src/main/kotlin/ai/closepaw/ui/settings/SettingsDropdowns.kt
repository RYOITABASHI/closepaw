package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ai.closepaw.R

@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String) -> Unit
) {
    val matched = modelOptions.find { it.first == selectedModel }
    val selectedDisplayName = matched?.second ?: stringResource(R.string.settings_dropdowns_select_model)

    SettingsDropdown(
        label = stringResource(R.string.settings_dropdowns_model),
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        options = modelOptions,
        isSelected = { (modelId, _) -> modelId == selectedModel },
        onOptionSelected = { (modelId, _) -> onModelChange(modelId) },
        optionText = { (_, displayName) -> Text(displayName) },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

@Composable
internal fun LocalModelDropdown(
    selectedModelId: String,
    onModelChange: (LocalModelOption) -> Unit
) {
    val selectedModel = AVAILABLE_LOCAL_MODELS.find { it.id == selectedModelId }
        ?: AVAILABLE_LOCAL_MODELS.first()

    SettingsDropdown(
        label = stringResource(R.string.settings_dropdowns_local_model),
        value = selectedModel.localizedDisplayName(),
        leadingIcon = Icons.Outlined.Memory,
        options = AVAILABLE_LOCAL_MODELS,
        isSelected = { model -> model.id == selectedModelId },
        onOptionSelected = onModelChange,
        optionText = { model ->
            Column {
                Text(model.localizedDisplayName())
                Text(
                    text = model.localizedDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}
