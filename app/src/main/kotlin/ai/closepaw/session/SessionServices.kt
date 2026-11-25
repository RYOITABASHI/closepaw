package ai.closepaw.session

import android.content.Context
import android.util.Log
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.agent.cognition.prompt.AppSkillRepository
import ai.closepaw.agent.cognition.prompt.AssetAppSkillRepository
import ai.closepaw.agent.cognition.prompt.EmptyAppSkillRepository
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.agent.cognition.skills.BundledAgentSkillInstaller
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.memory.MemoryRecaller
import ai.closepaw.memory.MemoryStore
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.BrowserScriptInvoker
import ai.closepaw.tool.impl.BrowserScriptTool
import ai.closepaw.tool.impl.BrowserScriptTraceMetadata
import ai.closepaw.tool.impl.BrowserScriptTraceSink
import ai.closepaw.tool.impl.DefaultBrowserScriptCapabilityGate
import ai.closepaw.tool.impl.RememberExperienceTool
import ai.closepaw.trace.TraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal interface TermuxSessionBridge {
    suspend fun healthCheck(): TermuxBridgeStatus
    suspend fun ensureReadyForSession(timeoutMs: Long): TermuxBridgeStatus = healthCheck()
    fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot
}

private class TermuxBridgeManagerSessionBridge(
        private val manager: TermuxBridgeManager
) : TermuxSessionBridge {
    override suspend fun healthCheck(): TermuxBridgeStatus = manager.healthCheck()

    override suspend fun ensureReadyForSession(timeoutMs: Long): TermuxBridgeStatus =
            manager.ensureReadyForSession(timeoutMs)

    override fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot = manager.snapshot(enabled)
}

/** Platform, tools, recording, and policy used by an externally driven session. */
class SessionServices internal constructor(
        val toolRegistry: ToolRegistry,
        val toolRouter: ToolRouter,
        val historyManager: HistoryManager,
        val sessionState: AgentSessionState,
        val policyEngine: PolicyEngine,
        val appClassifier: AppClassifier,
        val platform: AndroidPlatform,
        val config: SessionConfig,
        val traceRecorder: TraceRecorder,
        val recordingService: SessionRecordingService,
        val browserSessionManager: BrowserSessionManager? = null,
        val termuxSnapshot: TermuxCapabilitySnapshot = TermuxCapabilitySnapshot.Unavailable,
        internal val appSkillRepository: AppSkillRepository = EmptyAppSkillRepository,
        val agentSkillManager: AgentSkillManager = AgentSkillManager(java.io.File("")),
        val userResponseChannel: UserResponseChannel = UserResponseChannel(),
        val memoryStore: MemoryStore = MemoryStore(java.io.File("")),
        val memoryRecaller: MemoryRecaller = MemoryRecaller(memoryStore),
        private val externalDriver: Boolean = false,
) {
    companion object {
        private const val TAG = "SessionServices"
        private const val TERMUX_HEALTH_CHECK_TIMEOUT_MS = 2_000L

        /**
         * Create a new SessionServices container with all services initialized.
         *
         * @param config Session configuration
         * @param platform Android platform abstraction
         * @param context Android context (required for LOCAL backend for model downloading)
         * @return Fully initialized SessionServices
         */
        fun create(
                config: SessionConfig,
                platform: AndroidPlatform,
                context: Context,
                scope: CoroutineScope,
                traceRecorder: TraceRecorder,
                appClassifier: AppClassifier,
                externalDriver: Boolean = false,
        ): SessionServices {
            Log.d(TAG, "Creating SessionServices...")

            val skillsDir = java.io.File(context.filesDir, "skills")
            installBundledAgentSkills(context, skillsDir)
            val settingsStore = AppSettingsStore(context)
            // Snapshot once at session start — KISS "next session" semantics: toggling a
            // skill in Settings does not mutate this manager mid-session.
            val disabledAgentSkills = settingsStore.disabledAgentSkills.value
            val agentSkillManager = AgentSkillManager(skillsDir, disabledAgentSkills)
            val termuxManager = TermuxBridgeManager.get(context)
            val termuxSnapshot = captureTermuxSnapshot(
                    bridge = TermuxBridgeManagerSessionBridge(termuxManager),
                    termuxShellEnabled = settingsStore.termuxShellEnabled.value
            )
            val effectiveExcludedTools = config.excludedTools +
                if (settingsStore.browserScriptEnabled.value) emptySet() else setOf(ToolName.BrowserScript.raw)
            val effectiveConfig =
                    if (effectiveExcludedTools == config.excludedTools) config
                    else config.copy(excludedTools = effectiveExcludedTools)
            val tooling = SessionToolingBootstrapper.create(
                approvalMode = effectiveConfig.approvalMode,
                appClassifier = appClassifier,
                agentSkillManager = agentSkillManager,
                termuxSnapshot = termuxSnapshot,
                excludedTools = effectiveConfig.excludedTools,
                context = context.applicationContext,
                systemSurfacePackages = SystemSurfaces.resolve(context),
                denySystemSurfaceActions = effectiveConfig.unattended,
                strictBlockedFloor = externalDriver,
            )
            val policyEngine = tooling.policyEngine
            val sessionState = tooling.sessionState
            val toolRegistry = tooling.toolRegistry
            val toolRouter = tooling.toolRouter
            val browserSessionManager = registerBrowserScriptTool(
                toolRegistry = toolRegistry,
                context = context.applicationContext,
                scope = scope,
                traceRecorder = traceRecorder,
                settingsStore = settingsStore,
                browserScriptToolExcluded =
                        ToolName.BrowserScript.raw in effectiveExcludedTools,
            )

            val history = SessionHistoryBootstrapper.create(context, scope)
            val historyManager = history.historyManager
            val recordingService = history.recordingService
            val appSkillRepository = AssetAppSkillRepository(context.assets)

            // Memory system — eval hygiene is handled by the eval bridge clearing files/memory
            // before each task launch.
            val memoryDir = java.io.File(context.filesDir ?: java.io.File("/tmp"), "memory")
            val memoryStore = MemoryStore(memoryDir)
            val memoryRecaller = MemoryRecaller(memoryStore)
            toolRegistry.register(RememberExperienceTool(memoryStore, appClassifier))

            Log.i(TAG, "SessionServices created successfully")

            return SessionServices(
                    toolRegistry = toolRegistry,
                    toolRouter = toolRouter,
                    historyManager = historyManager,
                    sessionState = sessionState,
                    policyEngine = policyEngine,
                    appClassifier = appClassifier,
                    platform = platform,
                    config = effectiveConfig,
                    traceRecorder = traceRecorder,
                    recordingService = recordingService,
                    browserSessionManager = browserSessionManager,
                    termuxSnapshot = termuxSnapshot,
                    appSkillRepository = appSkillRepository,
                    agentSkillManager = agentSkillManager,
                    memoryStore = memoryStore,
                    memoryRecaller = memoryRecaller,
                    externalDriver = externalDriver,
            )
        }

        internal fun captureTermuxSnapshot(
                bridge: TermuxSessionBridge,
                termuxShellEnabled: Boolean,
                timeoutMs: Long = TERMUX_HEALTH_CHECK_TIMEOUT_MS
        ): TermuxCapabilitySnapshot {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutMs) {
                        bridge.ensureReadyForSession(timeoutMs)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Termux readiness probe before session snapshot failed", error)
            }

            return bridge.snapshot(termuxShellEnabled)
        }

        internal fun installBundledAgentSkills(context: Context, skillsDir: java.io.File) {
            val hasExistingBrowserUse = BundledAgentSkillInstaller.hasCompletedBrowserUseInstall(skillsDir)
            try {
                BundledAgentSkillInstaller(context.assets).install(skillsDir)
            } catch (e: Exception) {
                if (!hasExistingBrowserUse) {
                    throw IllegalStateException("Failed to install bundled browser-use skill", e)
                }
                Log.w(TAG, "Failed to refresh bundled agent skills; keeping existing install", e)
            }
        }

        internal fun registerBrowserScriptTool(
            toolRegistry: ToolRegistry,
            context: Context,
            scope: CoroutineScope,
            traceRecorder: TraceRecorder,
            settingsStore: AppSettingsStore,
            browserScriptToolExcluded: Boolean = false,
        ): BrowserSessionManager {
            val browserSessionManager = BrowserSessionManager(
                context = context.applicationContext,
                sessionScope = scope,
                traceRecorder = traceRecorder,
            )
            // Skip registration when the user pref excludes browser_script. Defense in depth:
            // the LLM allowlist already hides it via SessionConfig.excludedTools, but a missing
            // registry entry also blocks any path that builds the tool list directly from the
            // registry. We still construct BrowserSessionManager so the runtime path stays the
            // same shape — its constructor is cheap and side-effect-free.
            if (browserScriptToolExcluded) {
                Log.d(TAG, "Skipping BrowserScriptTool registration — excluded by pref")
                return browserSessionManager
            }
            val browserGate = DefaultBrowserScriptCapabilityGate(
                isExperimentalEnabled = { settingsStore.load().browserScriptEnabled },
                preflight = browserSessionManager::preflight,
                invokerFactory = {
                    BrowserScriptInvoker { script, timeout ->
                        browserSessionManager.run(script, timeout)
                    }
                },
            )
            toolRegistry.register(
                BrowserScriptTool(
                    capabilityGate = browserGate,
                    traceSink = browserScriptTraceSink(traceRecorder),
                )
            )
            return browserSessionManager
        }

        private fun browserScriptTraceSink(traceRecorder: TraceRecorder): BrowserScriptTraceSink =
            BrowserScriptTraceSink { metadata ->
                if (!traceRecorder.enabled) return@BrowserScriptTraceSink
                traceRecorder.storeText(
                    kind = "browser_script",
                    filenameHint = "browser_script_${metadata.callId ?: "call"}.json",
                    content = browserScriptTraceJson(metadata).toString(),
                    mimeType = "application/json",
                    description = "Raw browser_script execution metadata",
                )
            }

        private fun browserScriptTraceJson(metadata: BrowserScriptTraceMetadata): JSONObject {
            return JSONObject().apply {
                put("call_id", metadata.callId ?: JSONObject.NULL)
                put("script", metadata.script)
                put("timeout_ms", metadata.timeoutMs)
                put("duration_ms", metadata.durationMs)
                put("outcome", metadata.outcome)
                put("outcome_code", metadata.outcomeCode ?: JSONObject.NULL)
                put("severity", metadata.severity?.name ?: JSONObject.NULL)
                put("retryable", metadata.retryable)
                put("raw_result_json", metadata.rawResultJson ?: JSONObject.NULL)
                put("error_message", metadata.errorMessage ?: JSONObject.NULL)
                put("original_chars", metadata.originalChars)
                put("truncated_chars", metadata.truncatedChars)
            }
        }
    }

    /** Create a copy with optionally replaced services. */
    internal fun copy(
            toolRegistry: ToolRegistry = this.toolRegistry,
            toolRouter: ToolRouter = this.toolRouter,
            historyManager: HistoryManager = this.historyManager,
            sessionState: AgentSessionState = this.sessionState,
            policyEngine: PolicyEngine = this.policyEngine,
            appClassifier: AppClassifier = this.appClassifier,
            platform: AndroidPlatform = this.platform,
            config: SessionConfig = this.config,
            traceRecorder: TraceRecorder = this.traceRecorder,
            recordingService: SessionRecordingService = this.recordingService,
            browserSessionManager: BrowserSessionManager? = this.browserSessionManager,
            termuxSnapshot: TermuxCapabilitySnapshot = this.termuxSnapshot,
            appSkillRepository: AppSkillRepository = this.appSkillRepository,
            agentSkillManager: AgentSkillManager = this.agentSkillManager,
            userResponseChannel: UserResponseChannel = this.userResponseChannel,
            memoryStore: MemoryStore = this.memoryStore,
            memoryRecaller: MemoryRecaller = this.memoryRecaller
    ): SessionServices {
        return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                historyManager = historyManager,
                sessionState = sessionState,
                policyEngine = policyEngine,
                appClassifier = appClassifier,
                platform = platform,
                config = config,
                traceRecorder = traceRecorder,
                recordingService = recordingService,
                browserSessionManager = browserSessionManager,
                termuxSnapshot = termuxSnapshot,
                appSkillRepository = appSkillRepository,
                agentSkillManager = agentSkillManager,
                userResponseChannel = userResponseChannel,
                memoryStore = memoryStore,
                memoryRecaller = memoryRecaller,
                externalDriver = externalDriver,
        )
    }

    /**
     * Cleanup all services. Aggregates per-step failures rather than aborting,
     * so callers can surface partial teardown errors.
     */
    suspend fun cleanup(): CleanupResult {
        Log.d(TAG, "Cleaning up SessionServices...")

        toolRouter.cancelAll()
        userResponseChannel.cancel()
        historyManager.clear()

        val failures = mutableListOf<CleanupFailure>()
        runStep("browserSessionManager.close", failures) { browserSessionManager?.close() }
        runStep("platform.stop", failures) { platform.stop() }
        // Flush/close trace last so we still capture teardown artifacts if needed
        if (externalDriver) {
            Log.d(TAG, "External session trace recorder stays owned by the caller")
        } else {
            runStep("traceRecorder.close", failures) { traceRecorder.close() }
        }

        Log.i(TAG, "SessionServices cleaned up (failures=${failures.size})")
        return if (failures.isEmpty()) CleanupResult.Success else CleanupResult.PartialFailure(failures)
    }

    private suspend inline fun runStep(
        name: String,
        failures: MutableList<CleanupFailure>,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$name failed (non-fatal)", e)
            failures.add(CleanupFailure(name, e))
        }
    }
}

data class CleanupFailure(val step: String, val cause: Throwable)

sealed class CleanupResult {
    object Success : CleanupResult()
    data class PartialFailure(val failures: List<CleanupFailure>) : CleanupResult()
}
