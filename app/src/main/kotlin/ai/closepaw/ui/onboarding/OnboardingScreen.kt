package ai.closepaw.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import ai.closepaw.R
import ai.closepaw.onboarding.OnboardingEffect
import ai.closepaw.onboarding.OnboardingStepState
import ai.closepaw.onboarding.PermissionStepState
import ai.closepaw.onboarding.StepOutcomes
import ai.closepaw.onboarding.WizardStep
import kotlinx.coroutines.flow.Flow

/**
 * Full-screen onboarding wizard.
 *
 * Routes to per-step composables based on [currentStep]. Uses [OnboardingShell]
 * for shared scaffold (progress bar, step count, title, back arrow).
 */
@Composable
fun OnboardingScreen(
    currentStep: WizardStep,
    stepState: OnboardingStepState?,
    outcomes: StepOutcomes,
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    batteryGranted: Boolean,
    effects: Flow<OnboardingEffect>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipStep: () -> Unit,
    onFinish: () -> Unit,
    onEffect: (OnboardingEffect) -> Unit
) {
    // Consume one-shot effects
    LaunchedEffect(Unit) {
        effects.collect { effect -> onEffect(effect) }
    }

    val totalSteps = 3
    // Back arrow on all steps except the first
    val backAction: (() -> Unit)? = if (currentStep != WizardStep.Accessibility) onBack else null

    when (currentStep) {
        WizardStep.Accessibility -> {
            OnboardingShell(
                stepIndex = 1,
                totalSteps = totalSteps,
                title = stringResource(R.string.onboarding_screen_title_accessibility)
            ) {
                PermissionStepContent(
                    step = WizardStep.Accessibility,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {},
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Overlay -> {
            OnboardingShell(
                stepIndex = 2,
                totalSteps = totalSteps,
                title = stringResource(R.string.onboarding_screen_title_overlay),
                onBack = backAction
            ) {
                PermissionStepContent(
                    step = WizardStep.Overlay,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {},
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Battery -> {
            OnboardingShell(
                stepIndex = 3,
                totalSteps = totalSteps,
                title = stringResource(R.string.onboarding_screen_title_battery),
                onBack = backAction
            ) {
                PermissionStepContent(
                    step = WizardStep.Battery,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = onSkipStep,
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Complete -> {
            OnboardingShell(
                stepIndex = totalSteps,
                totalSteps = totalSteps,
                title = stringResource(R.string.onboarding_screen_title_complete),
                onBack = backAction
            ) {
                CompleteStepContent(
                    outcomes = outcomes,
                    accessibilityGranted = accessibilityGranted,
                    overlayGranted = overlayGranted,
                    batteryGranted = batteryGranted,
                    onFinish = onFinish
                )
            }
        }
    }
}
