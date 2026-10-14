package ai.closepaw.ui.capsule.voice

import ai.closepaw.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * UI-facing state for the voice-input session. The state machine is:
 *
 *   Idle ──start()──▶ Listening ──stop()──▶ Stopping ──onFinal/onError──▶ Idle
 *                       │                                     │
 *                       └──cancel()──▶ Idle                   └──LangUnavail──▶ Unavailable
 *
 * [Unavailable] is terminal for the session — the user has to restart it to retry.
 */
enum class VoiceState { Idle, Listening, Stopping, Unavailable }

/**
 * Drives a [Recognizer] session and surfaces a small state machine plus the partial/final
 * text-edit semantics used by the capsule voice input UI.
 *
 * Plain (non-Compose, non-Android) class so it can be exercised by JVM unit tests with a fake
 * recognizer. The companion @Composable [rememberVoiceInputController] wires it into Compose.
 *
 * Threading: assumes single-threaded use from the main thread — same constraint as [Recognizer].
 *
 * Generation counter: every terminal callback, [cancel], and [dispose] bumps [generation] so any
 * late callbacks captured in the previous session's closure are dropped. This is how we keep a
 * delayed `onPartial` from a cancelled or finished session from overwriting freshly-typed text.
 */
class VoiceInputController(
    private val factory: RecognizerFactory,
    private val languageTag: String,
    private val onText: (String) -> Unit,
    /** Receives a localized string resource id; resolved by the UI layer. */
    private val onToast: (Int) -> Unit = {},
) {
    var state: VoiceState by mutableStateOf(
        if (factory.isAvailable()) VoiceState.Idle else VoiceState.Unavailable
    )
        private set

    var lastPartial: String by mutableStateOf("")
        private set

    var partialAtStop: String by mutableStateOf("")
        private set

    private var generation: Int = 0
    private var disposed: Boolean = false
    private var recognizer: Recognizer? = null
    private var baseText: String = ""

    // Tracks whether the current session has produced ANY successful signal (partial or final).
    // A hard error before any signal means the recognizer is unusable on this hardware/config —
    // we then promote the controller to terminal Unavailable so the mic icon hides (see
    // [handleError]). Reset at the top of [start].
    private var sessionGotAnyCallback: Boolean = false

    fun start(baseText: String) {
        if (disposed || state == VoiceState.Stopping) return
        if (state == VoiceState.Listening) return
        if (state == VoiceState.Unavailable) return
        if (!factory.isAvailable()) {
            state = VoiceState.Unavailable
            return
        }
        this.baseText = baseText
        lastPartial = ""
        partialAtStop = ""
        sessionGotAnyCallback = false
        val r = factory.create() ?: run {
            state = VoiceState.Unavailable
            return
        }
        recognizer = r
        val myGen = generation
        r.start(languageTag, object : RecognizerCallbacks {
            override fun onPartial(text: String) {
                if (myGen != generation || disposed) return
                // During Stopping the partialAtStop snapshot is frozen — incoming partials are
                // noise from audio captured before stop() and must not mutate visible text.
                if (state == VoiceState.Stopping) return
                sessionGotAnyCallback = true
                lastPartial = text
                onText(joinWithSpace(this@VoiceInputController.baseText, text))
            }

            override fun onFinal(text: String) {
                if (myGen != generation || disposed) return
                sessionGotAnyCallback = true
                if (text.isNotEmpty()) {
                    onText(joinWithSpace(this@VoiceInputController.baseText, text))
                } else if (state == VoiceState.Stopping && partialAtStop.isNotEmpty()) {
                    onText(joinWithSpace(this@VoiceInputController.baseText, partialAtStop))
                }
                cleanupAfterTerminal(VoiceState.Idle)
            }

            override fun onError(error: VoiceError) {
                if (myGen != generation || disposed) return
                val next = handleError(error)
                cleanupAfterTerminal(next)
            }
        })
        state = VoiceState.Listening
    }

    fun stop() {
        if (state != VoiceState.Listening) return
        partialAtStop = lastPartial
        state = VoiceState.Stopping
        recognizer?.stop()
    }

    fun cancel() {
        if (state == VoiceState.Idle || state == VoiceState.Unavailable) {
            // Even from Idle, bump generation so any late stale callback from a previous
            // session is dropped.
            generation++
            recognizer?.cancel()
            return
        }
        generation++
        recognizer?.cancel()
        state = VoiceState.Idle
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        recognizer?.destroy()
        recognizer = null
    }

    private fun cleanupAfterTerminal(nextState: VoiceState) {
        state = nextState
        generation++
        recognizer?.destroy()
        recognizer = null
    }

    private fun handleError(error: VoiceError): VoiceState = when (error) {
        VoiceError.NoMatch, VoiceError.SpeechTimeout -> {
            if (state == VoiceState.Stopping && partialAtStop.isNotEmpty()) {
                onText(joinWithSpace(baseText, partialAtStop))
            } else {
                onText(baseText)
            }
            VoiceState.Idle
        }
        VoiceError.InsufficientPermissions -> {
            onText(baseText)
            onToast(R.string.voice_input_controller_permission_revoked)
            VoiceState.Idle
        }
        VoiceError.LanguageUnavailable -> {
            onText(baseText)
            onToast(R.string.voice_input_controller_language_unavailable)
            VoiceState.Unavailable
        }
        VoiceError.Network, VoiceError.NetworkTimeout -> {
            onText(baseText)
            onToast(R.string.voice_input_controller_needs_network)
            VoiceState.Idle
        }
        VoiceError.Busy, VoiceError.ServiceDied, VoiceError.Unknown -> {
            onText(baseText)
            // Hard error before we ever heard back from the recognizer: it's unusable on this
            // device/config (e.g. registered RecognitionService but no default selected, or
            // AppOps blocks binding). Promote to terminal Unavailable so the mic icon hides
            // and the user stops seeing the toast on every tap. Once we've seen at least one
            // partial/final, the same errors are treated as transient (network dropped etc.).
            if (!sessionGotAnyCallback) {
                onToast(R.string.voice_input_controller_unavailable)
                VoiceState.Unavailable
            } else {
                onToast(R.string.voice_input_controller_unavailable)
                VoiceState.Idle
            }
        }
    }

    private fun joinWithSpace(base: String, more: String): String = when {
        base.isEmpty() -> more
        more.isEmpty() -> base
        base.endsWith(' ') -> base + more
        else -> "$base $more"
    }
}

@Composable
fun rememberVoiceInputController(
    factory: RecognizerFactory,
    languageTag: String = Locale.getDefault().toLanguageTag(),
    onText: (String) -> Unit,
    onToast: (Int) -> Unit = {},
): VoiceInputController {
    val controller = remember(factory, languageTag) {
        VoiceInputController(factory, languageTag, onText, onToast)
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}
