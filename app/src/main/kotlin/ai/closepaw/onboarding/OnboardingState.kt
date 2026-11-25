package ai.closepaw.onboarding


/** Steps in the onboarding wizard, in funnel order. */
enum class WizardStep { Accessibility, Overlay, Battery, Complete }

/** Durable outcome persisted for each step. */
enum class StepOutcome { Pending, Done, Skipped }

/** Persisted step outcomes — loaded from store, used to derive current step. */
data class StepOutcomes(
    val accessibility: StepOutcome = StepOutcome.Pending,
    val overlay: StepOutcome = StepOutcome.Pending,
    val battery: StepOutcome = StepOutcome.Pending,
)

// ── Per-step transient state (one active at a time) ──

sealed interface OnboardingStepState

sealed interface PermissionStepState : OnboardingStepState {
    data object Checking : PermissionStepState
    data object Ready : PermissionStepState
    data object OpeningSettings : PermissionStepState
    data object Satisfied : PermissionStepState
    data object Unsatisfied : PermissionStepState
    data object Skipped : PermissionStepState // Battery only
}

/** One-shot effects emitted by ViewModel, consumed by composable. */
sealed interface OnboardingEffect {
    data object OpenAccessibilitySettings : OnboardingEffect
    data object OpenOverlaySettings : OnboardingEffect
    data object OpenBatteryOptimization : OnboardingEffect
}
