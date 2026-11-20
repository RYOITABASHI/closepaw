package ai.closepaw.spike

import ai.closepaw.mcp.McpSpikeHook
import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SPIKE (throwaway, never merges): one MCP hook for both probes, bound to the app context. */
internal object SpikeMcpHooks {
    fun mcpHook(ctx: Context): McpSpikeHook {
        val app = ctx.applicationContext
        val selfUpdate = SelfUpdateSpike.mcpHook(app)
        return object : McpSpikeHook {
            override fun startSelfReinstall(): JsonObject = selfUpdate.startSelfReinstall()
            override fun status(): JsonObject = selfUpdate.status()

            override fun startShizukuStart(args: JsonObject): JsonObject {
                val (parsed, error) = ShizukuStartArgs.parse(args)
                if (parsed == null) return JsonObject(mapOf("invalidArgs" to JsonPrimitive(error ?: "invalid arguments")))
                return ShizukuStartSpike.start(app, parsed)
            }

            override fun shizukuStartStatus(): JsonObject = ShizukuStartSpike.statusJson(app)
        }
    }
}
