package ai.closepaw.app

import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.mcp.McpBackend
import ai.closepaw.protocol.LLMBackendType

internal sealed interface RunGoalBrainAdmission {
    data class Admitted(val catalog: ModelCatalog) : RunGoalBrainAdmission
    data class Refused(val result: McpBackend.StartResult.Refused) : RunGoalBrainAdmission
}

internal fun admitRunGoalBrain(
    backend: LLMBackendType,
    modelName: String,
    catalog: ModelCatalog,
    authStore: () -> AuthStore,
): RunGoalBrainAdmission {
    if (catalog.resolveOrNull(modelName)?.provider != LLMProvider.OPENROUTER) {
        return RunGoalBrainAdmission.Admitted(catalog)
    }

    if (backend == LLMBackendType.LOCAL) {
        // Match AgentModelResolver's construction attempt. Building the client sends no request.
        val factory = LLMClientFactory(catalog, authStore())
        if (runCatching { factory.create(modelName) }.isFailure) {
            // Carry the fallback decision in the run's catalog so later credential changes
            // cannot make the planner or compactor select OpenRouter for this run.
            return RunGoalBrainAdmission.Admitted(catalog.withoutProvider(LLMProvider.OPENROUTER))
        }
    }

    return RunGoalBrainAdmission.Refused(
        McpBackend.StartResult.Refused(
            "legacy_brain_unavailable",
            "run_goal's internal brain still points at the deprecated OpenRouter path. " +
                "The supported driver is a Shelly-driven session (start_session). " +
                "Selecting a Codex provider is the tracked migration route.",
        ),
    )
}
