package ai.closepaw.onboarding

import android.content.Context
import androidx.annotation.StringRes

/**
 * User-visible onboarding text produced by Context-free logic (view model, validator).
 *
 * [Res] carries a string resource id plus format args and is resolved at the UI layer;
 * [Raw] carries text that is already final (e.g. a server-provided error message).
 */
sealed interface OnboardingText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : OnboardingText
    data class Raw(val text: String) : OnboardingText

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is Raw -> text
    }
}
