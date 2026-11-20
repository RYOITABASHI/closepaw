package ai.closepaw.spike.vd

import ai.closepaw.mcp.McpBackend
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpHttpRequest
import ai.closepaw.mcp.McpRunRegistry
import ai.closepaw.mcp.McpVdPhase0
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VdMcpWiringTest {
    @Test fun listsAndCallsSpikeAndReturnsStatusThroughAuthenticatedSession() {
        val token = "b".repeat(64)
        var started = 0
        val handler = McpHandler(
            expectedToken = { token },
            registry = McpRunRegistry(),
            backend = object : McpBackend {
                override fun startRun(goal: String): McpBackend.StartResult = error("Unexpected run")
                override fun stopRun(runHandle: String) = false
                override fun status() = McpBackend.Status("test", false, true)
            },
            audit = {},
            serverVersion = "test",
            vdPhase0 = object : McpVdPhase0 {
                override fun start(appDeathCheck: Boolean) = buildJsonObject {
                    put("accepted", ++started == 1)
                    put("appDeathCheck", appDeathCheck)
                }
                override fun status() = buildJsonObject { put("running", started == 1) }
            },
        )
        val auth = mapOf("authorization" to "Bearer $token")
        val init = handler.handle(McpHttpRequest(
            "POST", "/mcp", auth, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""",
        ))
        assertEquals(200, init.status)
        val headers = auth + ("mcp-session-id" to init.headers.getValue("Mcp-Session-Id"))

        fun request(method: String, params: String = "{}"): JsonObject {
            val response = handler.handle(McpHttpRequest(
                "POST", "/mcp", headers,
                """{"jsonrpc":"2.0","id":2,"method":"$method","params":$params}""",
            ))
            assertEquals(200, response.status)
            return Json.parseToJsonElement(response.body!!).jsonObject
        }

        fun content(reply: JsonObject): JsonObject {
            val text = reply.getValue("result").jsonObject.getValue("content").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content
            return Json.parseToJsonElement(text).jsonObject
        }

        val tools = request("tools/list").getValue("result").jsonObject.getValue("tools").jsonArray
        assertTrue(tools.any { it.jsonObject["name"] == JsonPrimitive("spike_vd_phase0") })
        val bad = request("tools/call", """{"name":"spike_vd_phase0","arguments":{"unexpected":true}}""")
        assertEquals(JsonPrimitive(-32602), bad.getValue("error").jsonObject["code"])
        assertEquals(0, started)
        val ok = request("tools/call", """{"name":"spike_vd_phase0","arguments":{}}""")
        assertEquals(JsonPrimitive(true), content(ok)["accepted"])
        val status = request("tools/call", """{"name":"closepaw_status"}""")
        assertEquals(JsonPrimitive(true), content(status).getValue("spikeVd").jsonObject["running"])
        val supervised = request("tools/call", """{"name":"spike_vd_phase0","arguments":{"appDeathCheck":true}}""")
        assertEquals(JsonPrimitive(true), content(supervised)["appDeathCheck"])
        val stringValue = request("tools/call", """{"name":"spike_vd_phase0","arguments":{"appDeathCheck":"true"}}""")
        assertEquals(JsonPrimitive(-32602), stringValue.getValue("error").jsonObject["code"])
    }
}
