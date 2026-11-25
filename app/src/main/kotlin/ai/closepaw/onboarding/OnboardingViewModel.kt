package ai.closepaw.onboarding

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Onboarding wizard state machine.
 *
 * Wizard progress (step outcomes, completion) persists via [OnboardingStore].
 */
class OnboardingViewModel(
    private val store: OnboardingStore,
    private val permissionMonitor: PermissionStateMonitor,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "OnboardingVM"
        private const val AUTO_ADVANCE_DELAY_MS = 400L
        private const val A11Y_POLL_INTERVAL_MS = 200L
        private const val A11Y_POLL_MAX_ATTEMPTS = 15 // 3 seconds
    }

    // ── Observable state ──

    var currentStep by mutableStateOf(WizardStep.Accessibility)
        private set

    var stepState by mutableStateOf<OnboardingStepState>(PermissionStepState.Checking)
        private set

    var outcomes by mutableStateOf(StepOutcomes())
        private set

    private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        outcomes = store.loadOutcomes()
        enterStep(firstIncompleteStep(), isResume = false)
    }

    // ── Lifecycle ──

    /** Called from MainActivity.onResume() — re-check current permission step. */
    fun onHostResumed() {
        if (currentStep in listOf(WizardStep.Accessibility, WizardStep.Overlay, WizardStep.Battery)) {
            checkCurrentPermission(isReturnFromSettings = true)
        }
    }

    // ── Actions ──

    fun goBack() {
        val prev = previousStep(currentStep) ?: return
        enterStep(prev, isResume = false, autoAdvance = false)
    }

    /** Manual advance from a satisfied step (used after back navigation). */
    fun continueForward() {
        advanceToNextStep()
    }

    fun openSystemSettings() {
        when (currentStep) {
            WizardStep.Accessibility -> {
                stepState = PermissionStepState.OpeningSettings
                _effects.trySend(OnboardingEffect.OpenAccessibilitySettings)
            }
            WizardStep.Overlay -> {
                stepState = PermissionStepState.OpeningSettings
                _effects.trySend(OnboardingEffect.OpenOverlaySettings)
            }
            WizardStep.Battery -> {
                stepState = PermissionStepState.OpeningSettings
                _effects.trySend(OnboardingEffect.OpenBatteryOptimization)
            }
            else -> {}
        }
    }

    fun skipStep() {
        when (currentStep) {
            WizardStep.Battery -> {
                store.saveOutcome(WizardStep.Battery, StepOutcome.Skipped)
                outcomes = outcomes.copy(battery = StepOutcome.Skipped)
                advanceToNextStep()
            }
            else -> {}
        }
    }

    fun finish() {
        store.setCompleted()
        Log.d(TAG, "Onboarding completed")
    }

    // ── Internal ──

    private fun firstIncompleteStep(): WizardStep {
        // Live hard-gate checks override stored Done
        if (!isAccessibilityEnabled()) return WizardStep.Accessibility
        if (!isOverlayEnabled()) return WizardStep.Overlay
        if (outcomes.battery == StepOutcome.Pending) return WizardStep.Battery
        return WizardStep.Complete
    }

    private fun enterStep(step: WizardStep, isResume: Boolean, autoAdvance: Boolean = true) {
        currentStep = step
        when (step) {
            WizardStep.Accessibility, WizardStep.Overlay, WizardStep.Battery -> {
                checkCurrentPermission(isReturnFromSettings = isResume, autoAdvance = autoAdvance)
            }
            WizardStep.Complete -> Unit
        }
    }

    private fun checkCurrentPermission(isReturnFromSettings: Boolean, autoAdvance: Boolean = true) {
        stepState = PermissionStepState.Checking

        val satisfied = when (currentStep) {
            WizardStep.Accessibility -> isAccessibilityEnabled()
            WizardStep.Overlay -> isOverlayEnabled()
            WizardStep.Battery -> isBatteryOptimized()
            else -> return
        }

        if (satisfied) {
            if (autoAdvance) {
                onPermissionSatisfied()
            } else {
                stepState = PermissionStepState.Satisfied
            }
            return
        }

        if (currentStep == WizardStep.Accessibility && isReturnFromSettings) {
            // A11y service connection can lag — poll briefly
            scope.launch {
                for (i in 1..A11Y_POLL_MAX_ATTEMPTS) {
                    delay(A11Y_POLL_INTERVAL_MS)
                    if (isAccessibilityEnabled()) {
                        onPermissionSatisfied()
                        return@launch
                    }
                }
                stepState = if (isReturnFromSettings) PermissionStepState.Unsatisfied
                    else PermissionStepState.Ready
            }
            return
        }

        stepState = if (isReturnFromSettings) PermissionStepState.Unsatisfied
            else PermissionStepState.Ready
    }

    private fun onPermissionSatisfied() {
        stepState = PermissionStepState.Satisfied
        val outcomeStep = currentStep
        store.saveOutcome(outcomeStep, StepOutcome.Done)
        outcomes = when (outcomeStep) {
            WizardStep.Accessibility -> outcomes.copy(accessibility = StepOutcome.Done)
            WizardStep.Overlay -> outcomes.copy(overlay = StepOutcome.Done)
            WizardStep.Battery -> outcomes.copy(battery = StepOutcome.Done)
            else -> outcomes
        }
        scope.launch {
            delay(AUTO_ADVANCE_DELAY_MS)
            advanceToNextStep()
        }
    }

    private fun advanceToNextStep() {
        val next = nextStep(currentStep)
        enterStep(next, isResume = false)
    }

    private fun nextStep(current: WizardStep): WizardStep = when (current) {
        WizardStep.Accessibility -> WizardStep.Overlay
        WizardStep.Overlay -> WizardStep.Battery
        WizardStep.Battery -> WizardStep.Complete
        WizardStep.Complete -> WizardStep.Complete
    }

    /** Returns null for the first step (no back from Accessibility). */
    private fun previousStep(current: WizardStep): WizardStep? = when (current) {
        WizardStep.Accessibility -> null
        WizardStep.Overlay -> WizardStep.Accessibility
        WizardStep.Battery -> WizardStep.Overlay
        WizardStep.Complete -> WizardStep.Battery
    }

    // ── Permission checks (delegate to monitor) ──

    fun isAccessibilityEnabled(): Boolean = permissionMonitor.isAccessibilityEnabled()
    fun isOverlayEnabled(): Boolean = permissionMonitor.isOverlayEnabled()
    fun isBatteryOptimized(): Boolean = permissionMonitor.isBatteryOptimized()
}
