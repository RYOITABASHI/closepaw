package ai.closepaw.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object McpStopClassification {
    fun isStopCall(body: String): Boolean {
        val decoded = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return false
        }
        val root = decoded as? JsonObject ?: return false
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method != "tools/call") return false
        val params = root["params"] as? JsonObject ?: return false
        return (params["name"] as? JsonPrimitive)?.contentOrNull == "stop_run"
    }
}
