package ai.closepaw.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SPIKE (throwaway, never merges): probe surfaces. When [McpHandler] is given one, it lists the
 * `spike_self_reinstall` and `spike_shizuku_start` tools and adds the `spike` / `spikeStart`
 * fields to `closepaw_status`; without one (the default) the MCP surface is unchanged.
 */
interface McpSpikeHook {
    /** Starts the probe in the background (no-op if one is running); returns the current stage at once. */
    fun startSelfReinstall(): JsonObject

    /** Parsed stage file plus this package's lastUpdateTime. */
    fun status(): JsonObject

    /**
     * Shizuku-start probe: [args] are the raw tool arguments (`pairPort`, `pairCode`, `connectPort`).
     * Returns the current stage at once, or `{"invalidArgs": "<message>"}` (never echoing pairCode).
     * Defaults keep hooks written for the self-reinstall probe alone compiling unchanged.
     */
    fun startShizukuStart(args: JsonObject): JsonObject = SPIKE_START_NOT_ENABLED

    /** The `spikeStart` field: `{running, current, stages}`. */
    fun shizukuStartStatus(): JsonObject = SPIKE_START_NOT_ENABLED
}

private val SPIKE_START_NOT_ENABLED = JsonObject(mapOf("error" to JsonPrimitive("shizuku-start spike not enabled")))
