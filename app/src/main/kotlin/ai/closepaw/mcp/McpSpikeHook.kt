package ai.closepaw.mcp

import kotlinx.serialization.json.JsonObject

/**
 * SPIKE (throwaway, never merges): self-reinstall probe surface. When [McpHandler] is given one,
 * it lists the `spike_self_reinstall` tool and adds a `spike` field to `closepaw_status`; without
 * one (the default) the MCP surface is unchanged.
 */
interface McpSpikeHook {
    /** Starts the probe in the background (no-op if one is running); returns the current stage at once. */
    fun startSelfReinstall(): JsonObject

    /** Parsed stage file plus this package's lastUpdateTime. */
    fun status(): JsonObject
}
