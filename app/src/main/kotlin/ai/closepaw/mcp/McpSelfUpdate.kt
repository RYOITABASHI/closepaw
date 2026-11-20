package ai.closepaw.mcp

import kotlinx.serialization.json.JsonObject

/** The typed ClosePaw reinstall operation and its persisted status. */
interface McpSelfUpdate {
    fun start(connectPort: Int?): JsonObject
    fun status(): JsonObject
}
