package ai.closepaw.ui.overlay.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * UI-layer resolution of the localized text carried by [CapsuleRenderSpec] and
 * [CapsuleMode]. The model types stay Context-free (string resource ids only);
 * these helpers turn them into display strings inside composition.
 */
@Composable
fun CapsuleRenderSpec.ThoughtSpec.resolveText(): String {
    val res = textRes ?: return text
    return if (formatArgs.isEmpty()) stringResource(res)
    else stringResource(res, *formatArgs.toTypedArray())
}

/** Localized thought of a Running mode (resource id wins over raw text). */
@Composable
fun CapsuleMode.Running.resolveThought(): String =
    thoughtRes?.let { stringResource(it) } ?: thought

/** Localized message of a Done mode (resource id wins over raw text). */
@Composable
fun CapsuleMode.Done.resolveMessage(): String =
    messageRes?.let { stringResource(it) } ?: message

/** Localized message of an Error mode (resource id wins over raw text). */
@Composable
fun CapsuleMode.Error.resolveMessage(): String =
    messageRes?.let { stringResource(it) } ?: message
