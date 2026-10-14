package ai.closepaw.app

import android.content.Context
import androidx.annotation.StringRes
import ai.closepaw.R
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.ModelIdValidator
import ai.closepaw.llm.OtherBaseUrlValidator
import ai.closepaw.protocol.LLMBackendType

/**
 * Deep-link target for a missing-credential banner tap.
 *
 * The user-visible message is carried as a string resource id + format args so this
 * Context-free validator stays unit-testable; resolve it with [message].
 */
data class MissingCredentialTarget(
    val provider: LLMProvider,
    @StringRes val messageRes: Int,
    val messageArgs: List<String> = emptyList(),
) {
    fun message(context: Context): String =
        if (messageArgs.isEmpty()) context.getString(messageRes)
        else context.getString(messageRes, *messageArgs.toTypedArray())
}

/**
 * Validate that a credential exists for the main model selected for the next session.
 * Returns one entry per missing credential with provider info so the caller can deep-link
 * into the right settings tab.
 *
 * Short-circuits on `selectedModel == "other-custom"` because when `otherBaseUrl` or
 * `otherModelId` are blank, the synth entry is not yet in the catalog and
 * `modelCatalog.resolveOrNull` returns null. Without the short-circuit the banner would
 * say "Unknown model: other-custom" instead of the actionable "Other needs base URL".
 *
 * Also short-circuits discovered OTHER entries (`selectedModel.startsWith("other:")`)
 * that have fallen out of the catalog — typically because the user changed
 * `otherBaseUrl` after a previous refresh. In that case the cache still has the entry
 * but the visible-name scope hides it (see [ModelCatalogRepository]). The banner sends
 * the user to the Other tab with a "Refresh or select a model for this endpoint" hint
 * instead of the generic "Unknown model" wall.
 */
internal fun findMissingCloudKeys(
    settingsState: AppSettingsState,
    modelCatalog: ModelCatalog,
    authStore: AuthStore,
): List<MissingCredentialTarget> {
    if (settingsState.llmBackend != LLMBackendType.OPENAI) return emptyList()

    val modelName = settingsState.selectedModel

    if (modelName == ModelCatalogRepository.OTHER_CUSTOM_NAME) {
        return findOtherMissing(settingsState, authStore)
    }

    if (modelName.startsWith(DISCOVERED_OTHER_PREFIX) &&
        modelCatalog.resolveOrNull(modelName) == null) {
        return listOf(
            MissingCredentialTarget(
                LLMProvider.OTHER,
                R.string.main_activity_model_validation_refresh_model,
            )
        )
    }

    val entry = modelCatalog.resolveOrNull(modelName)
        ?: return listOf(MissingCredentialTarget(
            LLMProvider.OPENAI_API,
            R.string.main_activity_model_validation_unknown_model,
            listOf(modelName),
        ))
    val provider = entry.provider
    if (provider == LLMProvider.LOCAL_LFM) return emptyList()
    if (authStore.has(provider)) return emptyList()

    val labelRes = when (provider) {
        LLMProvider.OPENAI_CODEX -> R.string.main_activity_model_validation_chatgpt_sign_in_required
        LLMProvider.OPENAI_API -> R.string.main_activity_model_validation_openai_key_required
        LLMProvider.OPENROUTER -> R.string.main_activity_model_validation_openrouter_key_required
        LLMProvider.OTHER -> R.string.main_activity_model_validation_api_key_required
        LLMProvider.LOCAL_LFM -> return emptyList()
    }
    return listOf(MissingCredentialTarget(provider, labelRes, listOf(entry.displayName)))
}

private const val DISCOVERED_OTHER_PREFIX = "other:"

private fun findOtherMissing(
    settingsState: AppSettingsState,
    authStore: AuthStore,
): List<MissingCredentialTarget> {
    val missing = mutableListOf<MissingCredentialTarget>()
    if (!authStore.has(LLMProvider.OTHER)) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, R.string.main_activity_model_validation_other_api_key_required)
    }
    val baseUrl = settingsState.otherBaseUrl
    if (baseUrl.isBlank()) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, R.string.main_activity_model_validation_other_base_url_required)
    } else if (OtherBaseUrlValidator.validate(baseUrl).isFailure) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, R.string.main_activity_model_validation_other_base_url_invalid)
    }
    val modelId = settingsState.otherModelId
    if (modelId.isBlank()) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, R.string.main_activity_model_validation_other_model_id_required)
    } else if (ModelIdValidator.validate(modelId).isFailure) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, R.string.main_activity_model_validation_other_model_id_invalid)
    }
    return missing
}
