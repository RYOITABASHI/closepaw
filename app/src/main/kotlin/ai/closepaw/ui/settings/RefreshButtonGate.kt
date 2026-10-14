package ai.closepaw.ui.settings

import ai.closepaw.R
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.OtherBaseUrlValidator
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Pure refresh-button gating logic — when discovery's prerequisites are met
 * for [provider]. Designed so the Compose layer renders a disabled button
 * with a tooltip naming the missing piece without re-doing validation.
 *
 * - `OPENROUTER` needs a non-blank API key (the URL is seed-fixed).
 * - `OTHER` needs a non-blank API key AND a base URL that passes
 *   [OtherBaseUrlValidator] (validated against debug-vs-release policy).
 */
object RefreshButtonGate {

    sealed interface State {
        data object Enabled : State
        /**
         * [reasonRes] is the localized reason. [detail], when non-null, is a pre-formatted
         * message (e.g. the URL validator's error) that takes precedence over [reasonRes].
         * Resolve for display with the composable [reason] extension.
         */
        data class Disabled(
            @StringRes val reasonRes: Int,
            val detail: String? = null,
        ) : State
    }

    fun evaluate(
        provider: LLMProvider,
        apiKey: String,
        otherBaseUrl: String,
        allowDebugHttp: Boolean,
    ): State {
        return when (provider) {
            LLMProvider.OPENROUTER -> {
                if (apiKey.isBlank()) State.Disabled(R.string.refresh_button_gate_openrouter_key_missing)
                else State.Enabled
            }
            LLMProvider.OTHER -> {
                if (apiKey.isBlank()) State.Disabled(R.string.refresh_button_gate_api_key_missing)
                else {
                    val urlResult = OtherBaseUrlValidator.validate(otherBaseUrl, allowDebugHttp)
                    if (urlResult.isFailure) State.Disabled(
                        reasonRes = R.string.refresh_button_gate_base_url_invalid,
                        detail = urlResult.exceptionOrNull()?.message,
                    ) else State.Enabled
                }
            }
            else -> State.Disabled(R.string.refresh_button_gate_unsupported_provider)
        }
    }
}

/** Display text for a disabled refresh gate: [RefreshButtonGate.State.Disabled.detail] if set, else the localized reason. */
internal val RefreshButtonGate.State.Disabled.reason: String
    @Composable get() = detail ?: stringResource(reasonRes)
