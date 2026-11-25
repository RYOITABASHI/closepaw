package ai.closepaw.onboarding

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private lateinit var store: OnboardingStore
    private lateinit var permissionMonitor: PermissionStateMonitor

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        permissionMonitor = mockk(relaxed = true)
        every { store.loadOutcomes() } returns StepOutcomes()
        every { permissionMonitor.isAccessibilityEnabled() } returns false
        every { permissionMonitor.isOverlayEnabled() } returns false
        every { permissionMonitor.isBatteryOptimized() } returns false
    }

    @After
    fun tearDown() = unmockkAll()

    private fun makeVm(scope: CoroutineScope) = OnboardingViewModel(store, permissionMonitor, scope)

    private suspend fun drain(scope: CoroutineScope, testScope: TestScope) {
        repeat(20) {
            testScope.testScheduler.advanceUntilIdle()
            val children = scope.coroutineContext.job.children.toList()
            if (children.isEmpty()) {
                testScope.testScheduler.advanceUntilIdle()
                return
            }
            children.forEach { it.join() }
        }
    }

    private fun allPermissionsGranted() {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
    }

    @Test
    fun `onHostResumed re-checks accessibility and advances when permission now granted`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isInstanceOf(PermissionStepState.Ready::class.java)

        every { permissionMonitor.isAccessibilityEnabled() } returns true
        vm.onHostResumed()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        verify { store.saveOutcome(WizardStep.Accessibility, StepOutcome.Done) }

        scope.coroutineContext.job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Characterization: WizardStep funnel + StepOutcome rollup
    // Spec: doc/main/state_machines/onboarding_wizard.md
    // ─────────────────────────────────────────────────────────────────────────

    private fun newScope(testScope: TestScope) =
        CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler) + Job())

    private val allDoneExceptDemo = StepOutcomes(
        accessibility = StepOutcome.Done,
        overlay = StepOutcome.Done,
        battery = StepOutcome.Skipped,
    )

    private val allComplete = StepOutcomes(
        accessibility = StepOutcome.Done,
        overlay = StepOutcome.Done,
        battery = StepOutcome.Skipped,
    )

    // ── firstIncompleteStep — initial step selection (8 rules) ──

    @Test
    fun `firstIncompleteStep — no permissions → Accessibility`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — accessibility only → Overlay`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — battery pending and gate unsatisfied → Battery`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        // battery Pending + !isBatteryOptimized → rule 3
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — battery pending despite gate satisfied → Battery (rule 5)`() = runTest {
        // acc+overlay+batteryOpt all true; outcomes.battery still Pending → rule 5 forces Battery
        allPermissionsGranted()
        // Default outcomes = all Pending. Rule 3 fails (gate satisfied). Rule 5 fires.
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        // After enterStep(Battery): permission satisfied + autoAdvance=true → onPermissionSatisfied
        // → advances to Complete. So currentStep ends at Complete, but we can verify Battery was
        // visited by checking outcomes.battery flipped to Done.
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Done)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — all done → Complete (rule 8)`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — stored Done but live a11y revoked routes back to Accessibility`() = runTest {
        // Hard-gate re-validation: acc Done in store, but isAccessibilityEnabled=false.
        every { store.loadOutcomes() } returns allComplete
        every { permissionMonitor.isOverlayEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
        // isAccessibilityEnabled stays false (default).
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — stored Done but overlay revoked routes back to Overlay`() = runTest {
        every { store.loadOutcomes() } returns allComplete
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
        // overlay still false
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        scope.coroutineContext.job.cancel()
    }

    // ── Forward transitions ──

    @Test
    fun `Overlay → Battery on permission satisfied`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)

        every { permissionMonitor.isOverlayEnabled() } returns true
        // Battery gate unsatisfied so we stop on Battery (don't cascade).
        vm.onHostResumed(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        assertThat(vm.outcomes.overlay).isEqualTo(StepOutcome.Done)
        verify { store.saveOutcome(WizardStep.Overlay, StepOutcome.Done) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Battery → Complete on permission satisfied`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)

        every { permissionMonitor.isBatteryOptimized() } returns true
        vm.onHostResumed(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Done)
        verify { store.saveOutcome(WizardStep.Battery, StepOutcome.Done) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Battery → Complete on skip persists Skipped`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)

        vm.skipStep(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Skipped)
        verify { store.saveOutcome(WizardStep.Battery, StepOutcome.Skipped) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Complete → Complete is a self-loop on continueForward`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        vm.continueForward(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        scope.coroutineContext.job.cancel()
    }

    // ── goBack — backward transitions ──

    @Test
    fun `goBack from Accessibility is no-op`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Overlay → Accessibility`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        // Acc satisfied + autoAdvance=false → Satisfied, NOT advanced
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Satisfied)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Battery → Overlay (no auto-advance even when satisfied)`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Satisfied)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Complete → Battery`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Satisfied)
        scope.coroutineContext.job.cancel()
    }

    // ── skipStep guard rejections (only Battery + Demo allowed) ──

    @Test
    fun `skipStep on Accessibility is no-op`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.skipStep(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Accessibility, any()) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `skipStep on Overlay is no-op`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.skipStep(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Overlay, any()) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `skipStep on Complete is no-op`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        vm.skipStep(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Complete, any()) }
        // Per spec, only Battery + Demo accept Skipped — Complete never persists outcome at all.
        scope.coroutineContext.job.cancel()
    }

    // ── Complete recovery: stored Done but AuthStore lost the credential ──

    // ── Special transitions ──

    // ── Guard early-returns (currentStep mismatch) ──

    // ── Invariants ──

    @Test
    fun `finish persists completion via store setCompleted`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)

        vm.finish()
        verify { store.setCompleted() }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Complete is never persisted as a StepOutcome`() = runTest {
        // Drive a full happy path and assert saveOutcome(Complete, *) never fires.
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        every { demoController.run(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg() as (String) -> Unit).invoke("ok")
        }
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.startDemo(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        verify(exactly = 0) { store.saveOutcome(WizardStep.Complete, any()) }
        scope.coroutineContext.job.cancel()
    }

}
