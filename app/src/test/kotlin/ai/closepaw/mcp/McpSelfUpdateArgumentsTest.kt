package ai.closepaw.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class McpSelfUpdateArgumentsTest {
    @Test fun malformedArgumentsAreRejectedBeforeDispatch() {
        var starts = 0
        val token = "a".repeat(64)
        val handler = McpHandler(
            expectedToken = { token },
            registry = McpRunRegistry(),
            backend = object : McpBackend {
                override fun startRun(goal: String): McpBackend.StartResult = error("Unexpected startRun")
                override fun stopRun(runHandle: String) = false
                override fun status() = McpBackend.Status("test", false, false)
            },
            audit = {},
            serverVersion = "test",
            selfUpdate = object : McpSelfUpdate {
                override fun start(connectPort: Int?): JsonObject {
                    starts++
                    return JsonObject(emptyMap())
                }

                override fun status() = JsonObject(mapOf("selfUpdateAdb" to JsonObject(emptyMap())))
            },
        )
        val auth = mapOf("authorization" to "Bearer $token")
        val initialize = handler.handle(McpHttpRequest(
            "POST", "/mcp", auth, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""",
        ))
        assertEquals(initialize.body, 200, initialize.status)
        val headers = auth + ("mcp-session-id" to initialize.headers.getValue("Mcp-Session-Id"))

        fun call(name: String, arguments: String): JsonObject {
            val response = handler.handle(McpHttpRequest(
                "POST", "/mcp", headers,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name"$arguments}}""",
            ))
            assertEquals(200, response.status)
            return Json.parseToJsonElement(checkNotNull(response.body)) as JsonObject
        }

        for (invalid in listOf("[]", "null", "true", "123", "\"text\"")) {
            val response = call("self_update", ""","arguments":$invalid""")
            assertEquals(JsonPrimitive(-32602), (response["error"] as JsonObject)["code"])
        }
        assertEquals(0, starts)
        check("result" in call("closepaw_status", ""","arguments":[]"""))
        check("result" in call("self_update", ""))
        check("result" in call("self_update", ""","arguments":{}"""))
        assertEquals(2, starts)
    }
}
