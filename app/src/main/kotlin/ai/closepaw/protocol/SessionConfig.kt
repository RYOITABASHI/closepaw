package ai.closepaw.protocol

import ai.closepaw.perception.PerceptionConfig

/**
 * SessionConfig - Configuration for an agent session.
 *
 * Contains all settings that affect session behavior.
 * Immutable after session creation.
 */
data class SessionConfig(
        /** Delay between actions in milliseconds (for UI to settle) */
        val actionDelayMs: Long = 2000,
        /** Approval mode for tool execution */
        val approvalMode: ApprovalMode = ApprovalMode.SMART,
        /** Enable verbose debug logging */
        val debugMode: Boolean = false,
        /** Persist a full JSONL trace (for inspection_tool) */
        val traceEnabled: Boolean = false,
        /** Trace run id (folder name) for correlating host/device artifacts */
        val traceRunId: String? = null,
        /** Controls which perception modalities (a11y tree, screenshot, both) are active */
        val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
        /** Platform mode: real screen (accessibility) or virtual display (Shizuku) */
        val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
        /** Tool names to exclude from the agent's allowed tool set (e.g. for eval) */
        val excludedTools: Set<String> = emptySet(),
        /**
         * Started without the user watching ClosePaw (Shelly background hand-off). The policy
         * engine then denies — instead of asking about — direct actions on the home launcher /
         * System UI, so the agent uses open_app. Default false keeps interactive behavior.
         */
        val unattended: Boolean = false,
        /**
         * Shelly background runs on the virtual display: never leave the user's display-0 tasks
         * moved by the run (see ai.closepaw.platform.virtualdisplay.MainDisplayTaskGuard).
         * Default false keeps MainActivity-initiated sessions unchanged.
         */
        val preserveMainDisplayTasks: Boolean = false,
        /**
         * Background focus policy only (API 34+ VD focus-isolation flags, see
         * VirtualDisplayPlatform.vdOptionalDisplayFlags), WITHOUT [preserveMainDisplayTasks]'
         * display-0 task restorer. For the save-draft routine, which runs unattended and must
         * leave the user's display-0 focus / IME alone, but owns its own Chrome task moves
         * (ai.closepaw.draft.DraftChromeStage). [preserveMainDisplayTasks] implies it.
         */
        val isolateFocus: Boolean = false
)

/** Platform mode — which display the agent operates on. */
enum class PlatformMode {
        /** Agent operates on the real screen via AccessibilityService. */
        ACCESSIBILITY,
        /** Agent operates on a Shizuku-powered virtual display. */
        VIRTUAL_DISPLAY
}

/** ApprovalMode - How tool execution approvals are handled. */
enum class ApprovalMode {
        /** Always ask user before executing any tool */
        ALWAYS_ASK,
        /** Never ask, auto-approve all tools */
        AUTO_APPROVE,
        /** Smart mode: auto-approve low-risk, ask for high-risk */
        SMART
}
