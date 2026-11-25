package ai.closepaw.app

import ai.closepaw.R
import ai.closepaw.history.SessionHistoryManager
import ai.closepaw.history.storage.SessionStorage
import ai.closepaw.memory.MemoryStore
import ai.closepaw.onboarding.OnboardingEffect
import ai.closepaw.onboarding.OnboardingStore
import ai.closepaw.onboarding.OnboardingViewModel
import ai.closepaw.onboarding.PermissionStateMonitor
import ai.closepaw.tool.AppClassifierHolder
import ai.closepaw.ui.chat.ChatStrings
import ai.closepaw.ui.chat.ChatViewModel
import ai.closepaw.ui.onboarding.OnboardingScreen
import ai.closepaw.ui.theme.ClosePawTheme
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_VOICE_PERMISSION = "request_voice_permission"
        private const val REQUEST_POST_NOTIFICATIONS = 0x5E12
    }

    private lateinit var onboardingStore: OnboardingStore
    private var onboardingViewModel: OnboardingViewModel? = null
    private var onboardingRequired by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var pendingVoicePermissionRequest by mutableStateOf(false)
    private var repairModel by mutableStateOf<PermissionStateMonitor.PermissionRepairModel?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeVoicePermissionRequestIfPresent(intent)

        val settingsState = AppSettingsState.create(applicationContext).also { it.load() }
        onboardingStore = OnboardingStore(applicationContext)
        onboardingRequired = !onboardingStore.isCompleted
        if (onboardingRequired) {
            onboardingViewModel = OnboardingViewModel(
                onboardingStore, PermissionStateMonitor(applicationContext), lifecycleScope,
            )
        }

        val history = SessionHistoryManager.create(SessionStorage(applicationContext), lifecycleScope)
        val viewModel = ChatViewModel(
            sessionProvider = { null },
            sessionHistoryManager = history,
            strings = ChatStrings.from(applicationContext),
        )
        val memoryStore = MemoryStore(java.io.File(filesDir, "memory"))
        val memoryEditGate = MemoryEditGate(AgentService.observedSessionState, lifecycleScope)
        val classifier = AppClassifierHolder.get(applicationContext)

        setContent {
            val onboarding = onboardingViewModel
            if (onboardingRequired && onboarding != null) {
                ClosePawTheme {
                    OnboardingScreen(
                        currentStep = onboarding.currentStep,
                        stepState = onboarding.stepState,
                        outcomes = onboarding.outcomes,
                        accessibilityGranted = onboarding.isAccessibilityEnabled(),
                        overlayGranted = onboarding.isOverlayEnabled(),
                        batteryGranted = onboarding.isBatteryOptimized(),
                        effects = onboarding.effects,
                        onBack = onboarding::goBack,
                        onContinue = onboarding::continueForward,
                        onOpenSettings = onboarding::openSystemSettings,
                        onSkipStep = onboarding::skipStep,
                        onFinish = {
                            onboarding.finish()
                            onboardingRequired = false
                            repairModel = deriveRepairModel()
                        },
                        onEffect = ::handleOnboardingEffect,
                    )
                }
            } else {
                MainActivityContent(
                    viewModel = viewModel,
                    settingsState = settingsState,
                    showSettings = showSettings,
                    onShowSettingsChange = { showSettings = it },
                    onSessionSelect = { viewModel.resumeSession(it) },
                    onNewSession = viewModel::clearConversation,
                    onOpenViewer = { openViewer(this) },
                    onOpenApp = { pkg ->
                        packageManager.getLaunchIntentForPackage(pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let(::startActivity)
                    },
                    isAccessibilityEnabled = AgentService.instance != null,
                    isOverlayEnabled = Settings.canDrawOverlays(this),
                    onAccessibilityClick = { openAccessibilitySettings(this) },
                    onOverlayClick = { openOverlaySettings(this) },
                    repairModel = repairModel,
                    onFixBattery = { handleOnboardingEffect(OnboardingEffect.OpenBatteryOptimization) },
                    effectivePlatformModeFlow = AgentService.observedPlatformMode,
                    appClassifier = classifier,
                    currentSessionStateFlow = AgentService.observedSessionState,
                    memoryStore = memoryStore,
                    memoryEditGate = memoryEditGate,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeVoicePermissionRequestIfPresent(intent)
    }

    override fun onStart() {
        super.onStart()
        AgentService.instance?.onMainAppVisible()
    }

    override fun onResume() {
        super.onResume()
        AgentService.instance?.onMainAppVisible()
        if (onboardingRequired) onboardingViewModel?.onHostResumed()
        repairModel = deriveRepairModel()
        maybeRequestNotificationPermissionForShellyRuns()
    }

    override fun onStop() {
        super.onStop()
        AgentService.instance?.onMainAppHidden()
    }

    internal fun isVoicePermissionRequestPending(): Boolean = pendingVoicePermissionRequest

    internal fun clearVoicePermissionRequest() {
        pendingVoicePermissionRequest = false
    }

    private fun consumeVoicePermissionRequestIfPresent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_REQUEST_VOICE_PERMISSION, false)) {
            pendingVoicePermissionRequest = true
            // Defensively strip the extra so subsequent recompositions / intent re-reads do not re-fire.
            intent.removeExtra(EXTRA_REQUEST_VOICE_PERMISSION)
        }
    }

    private fun maybeRequestNotificationPermissionForShellyRuns() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (onboardingRequired) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!ShellyRunPrefs.shouldAskNotificationPermission(this)) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun handleOnboardingEffect(effect: OnboardingEffect) {
        when (effect) {
            OnboardingEffect.OpenAccessibilitySettings ->
                openAccessibilitySettings(this)
            OnboardingEffect.OpenOverlaySettings ->
                openOverlaySettings(this)
            OnboardingEffect.OpenBatteryOptimization -> {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, getString(R.string.main_activity_battery_settings_unavailable), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun deriveRepairModel(): PermissionStateMonitor.PermissionRepairModel? {
        if (!onboardingStore.isCompleted) return null
        val outcomes = onboardingStore.loadOutcomes()
        val batteryWasDone = outcomes.battery == ai.closepaw.onboarding.StepOutcome.Done
        return PermissionStateMonitor(applicationContext).deriveRepairModel(batteryWasDone)
    }
}
